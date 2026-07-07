package com.pinganbaiyun.app.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.pinganbaiyun.app.core.protocol.DoorProtocol
import com.pinganbaiyun.app.core.protocol.HexUtils
import com.pinganbaiyun.app.data.model.DoorConfig

/**
 * BLE 开门流程状态机（引擎层）。
 *
 * 一次 [start] 走完：Service UUID 过滤扫描 → 匹配 MAC → GATT 连接 → 发现服务 →
 * 按属性动态选 read/write/notify 特征 → 开启通知 → 读种子 → DES 握手 → 解析回执。
 * 状态通过 [UnlockStateListener] 回调（始终在主线程），供 UI（原型 C-01..C-05）驱动界面。
 *
 * 权限：调用方须在 [start] 前确保已授予 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`
 * （Android 12+ 免定位）。所有 BLE 调用以 @SuppressLint("MissingPermission") 标注，
 * 由上层保证权限，避免在引擎层散落权限判断。
 */
@SuppressLint("MissingPermission")
class BleUnlockManager(
    private val context: Context,
    var listener: UnlockStateListener? = null,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var config: DoorConfig? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    private var serviceUuid: java.util.UUID? = null
    private var readChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var notifyChars: List<BluetoothGattCharacteristic> = emptyList()
    private var pendingNotifyEnables = 0

    private var seed: ByteArray? = null
    private var finished = false
    private var currentState: UnlockState = UnlockState.Idle

    val state: UnlockState get() = currentState

    // ---------------- 对外入口 ----------------

    /** 开始开门流程。若正在进行会先 [stop] 复位。 */
    fun start(config: DoorConfig) {
        stop()
        finished = false
        this.config = config
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            fail("蓝牙未开启", UnlockError.BLUETOOTH_DISABLED)
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            fail("无法获取 BLE 扫描器", UnlockError.UNKNOWN)
            return
        }
        this.scanner = scanner
        emit(UnlockState.Scanning)
        startFlowTimeout()
        startScanTimeout()
        val filters = BleConstants.SERVICE_CANDIDATES.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, scanCallback)
    }

    /** 中止并释放所有 BLE 资源。 */
    fun stop() {
        handler.removeCallbacksAndMessages(null)
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        scanner = null
        serviceUuid = null
        readChar = null
        writeChar = null
        notifyChars = emptyList()
        seed = null
    }

    // ---------------- 扫描 ----------------

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val target = config ?: return
            val device = result.device
            if (!device.address.equals(target.mac, ignoreCase = true)) return
            runCatching { scanner?.stopScan(this) }
            handler.removeCallbacks(scanTimeoutRunnable)
            val label = result.scanRecord?.deviceName ?: device.address
            emit(UnlockState.Connecting(label))
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            fail("扫描失败（错误码 $errorCode）", UnlockError.UNKNOWN)
        }
    }

    private fun connect(device: BluetoothDevice) {
        startConnectTimeout()
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    // ---------------- GATT 回调 ----------------

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                handler.removeCallbacks(connectTimeoutRunnable)
                emitMain(UnlockState.PreparingChannel)
                handler.post { runCatching { gatt.discoverServices() } }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                if (!finished) failMain("连接已断开", UnlockError.CONNECT_FAILED)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failMain("服务发现失败（status $status）", UnlockError.SERVICE_NOT_FOUND)
                return
            }
            val service = BleConstants.SERVICE_CANDIDATES
                .firstNotNullOfOrNull { gatt.getService(it) }
            if (service == null) {
                failMain("未找到目标门禁服务", UnlockError.SERVICE_NOT_FOUND)
                return
            }
            serviceUuid = service.uuid
            val chars = service.characteristics
            // 按属性动态选（非硬编码 UUID）
            readChar = chars.firstOrNull { it.hasProperty(BluetoothGattCharacteristic.PROPERTY_READ) }
            writeChar = chars.firstOrNull {
                it.hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE) ||
                    it.hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
            }
            notifyChars = chars.filter {
                it.hasProperty(BluetoothGattCharacteristic.PROPERTY_NOTIFY) ||
                    it.hasProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)
            }
            if (readChar == null || writeChar == null) {
                failMain("未找到可读写的蓝牙特征", UnlockError.CHARACTERISTIC_NOT_FOUND)
                return
            }
            pendingNotifyEnables = notifyChars.size
            if (notifyChars.isEmpty()) {
                readSeed()
            } else {
                notifyChars.forEach { enableNotification(gatt, it) }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            pendingNotifyEnables--
            if (pendingNotifyEnables <= 0) {
                readSeed()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            handleSeedRead(characteristic.value, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleSeedRead(value, status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleNotification(characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(value)
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(BleConstants.CCCD_UUID) ?: run {
            pendingNotifyEnables--
            if (pendingNotifyEnables <= 0) readSeed()
            return
        }
        val value = if (ch.hasProperty(BluetoothGattCharacteristic.PROPERTY_INDICATE)) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private fun readSeed() {
        val g = gatt ?: return
        val ch = readChar ?: return
        emitMain(UnlockState.ReadingSeed)
        startSeedTimeout()
        handler.post { runCatching { g.readCharacteristic(ch) } }
    }

    private fun handleSeedRead(value: ByteArray?, status: Int) {
        handler.removeCallbacks(seedTimeoutRunnable)
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.isEmpty()) {
            failMain("读取随机数种子失败", UnlockError.READ_SEED_TIMEOUT)
            return
        }
        seed = value
        sendHandshake(value)
    }

    private fun sendHandshake(seedBytes: ByteArray) {
        val cfg = config ?: return
        val g = gatt ?: return
        val ch = writeChar ?: return
        val result = runCatching {
            val header = DoorProtocol.deriveHeaderFromMac(cfg.mac)
            DoorProtocol.buildHandshakeCommandWithHeader(seedBytes, header, cfg.key)
        }
        val frame = result.getOrElse {
            failMain("构造握手指令失败：${it.message}", UnlockError.PROTOCOL_ERROR)
            return
        }
        emitMain(UnlockState.Opening)
        startAckTimeout()
        writeFrame(g, ch, frame)
    }

    private fun writeFrame(g: BluetoothGatt, ch: BluetoothGattCharacteristic, frame: ByteArray) {
        val writeType = if (ch.hasProperty(BluetoothGattCharacteristic.PROPERTY_WRITE)) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        handler.post {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, frame, writeType)
                } else {
                    @Suppress("DEPRECATION")
                    ch.writeType = writeType
                    @Suppress("DEPRECATION")
                    ch.value = frame
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
            }
        }
    }

    private fun handleNotification(value: ByteArray?) {
        if (value == null) return
        // GATT 回调运行在 binder 线程，统一切回主线程处理，保证状态回调始终在主线程。
        handler.post { dispatchNotification(value) }
    }

    private fun dispatchNotification(value: ByteArray) {
        if (finished) return
        val cfg = config ?: return
        val hex = HexUtils.bytesToHex(value)
        when (val n = NotificationParser.parse(hex, cfg.key)) {
            is NotificationParser.Notification.HandshakeSuccess -> {
                handler.removeCallbacks(ackTimeoutRunnable)
                // 握手成功即门锁开始执行开门（对应 index.js finalize true）
                succeed(com.pinganbaiyun.app.core.protocol.OpenResult(OPEN_HANDSHAKE_OK, "握手成功，门锁执行开门"))
            }
            is NotificationParser.Notification.HandshakeFailed -> {
                handler.removeCallbacks(ackTimeoutRunnable)
                failMain("握手失败，设备返回码 0x${n.codeWord}", UnlockError.PROTOCOL_ERROR)
            }
            is NotificationParser.Notification.OpenResultReceived -> {
                handler.removeCallbacks(ackTimeoutRunnable)
                if (n.result.isSuccess) {
                    succeed(n.result)
                } else {
                    failMain(n.result.message, UnlockError.PROTOCOL_ERROR)
                }
            }
            is NotificationParser.Notification.Fragment,
            NotificationParser.Notification.Ignored -> Unit // 主流程忽略
        }
    }

    // ---------------- 超时 ----------------

    private val flowTimeoutRunnable = Runnable { failMain("开门流程超时", UnlockError.FLOW_TIMEOUT) }
    private val scanTimeoutRunnable = Runnable {
        runCatching { scanner?.stopScan(scanCallback) }
        failMain("未找到设备", UnlockError.SCAN_TIMEOUT)
    }
    private val connectTimeoutRunnable = Runnable { failMain("连接超时", UnlockError.CONNECT_FAILED) }
    private val seedTimeoutRunnable = Runnable { failMain("读取随机数超时", UnlockError.READ_SEED_TIMEOUT) }
    private val ackTimeoutRunnable = Runnable { failMain("未收到门锁回执", UnlockError.ACK_TIMEOUT) }

    private fun startFlowTimeout() =
        handler.postDelayed(flowTimeoutRunnable, BleConstants.FLOW_TIMEOUT_MS)

    private fun startScanTimeout() =
        handler.postDelayed(scanTimeoutRunnable, BleConstants.DISCOVERY_TIMEOUT_MS)

    private fun startConnectTimeout() =
        handler.postDelayed(connectTimeoutRunnable, BleConstants.CONNECT_TIMEOUT_MS)

    private fun startSeedTimeout() =
        handler.postDelayed(seedTimeoutRunnable, BleConstants.READ_SEED_TIMEOUT_MS)

    private fun startAckTimeout() =
        handler.postDelayed(ackTimeoutRunnable, BleConstants.USER_ACK_TIMEOUT_MS)

    // ---------------- 状态发射 ----------------

    private fun emit(state: UnlockState) {
        currentState = state
        listener?.onStateChanged(state)
    }

    private fun emitMain(state: UnlockState) = handler.post { emit(state) }

    private fun succeed(result: com.pinganbaiyun.app.core.protocol.OpenResult) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        emit(UnlockState.Success(result))
        stop()
    }

    private fun fail(reason: String, error: UnlockError) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        emit(UnlockState.Failed(reason, error))
        stop()
    }

    private fun failMain(reason: String, error: UnlockError) = handler.post { fail(reason, error) }

    companion object {
        /** 握手即开门成功时的伪状态码（区别于回执 00/02）。 */
        const val OPEN_HANDSHAKE_OK = "OK"
    }
}

private fun BluetoothGattCharacteristic.hasProperty(prop: Int): Boolean =
    (properties and prop) != 0
