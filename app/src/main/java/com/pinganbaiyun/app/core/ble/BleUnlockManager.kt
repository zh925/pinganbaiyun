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
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import com.pinganbaiyun.app.BuildConfig
import com.pinganbaiyun.app.core.protocol.DoorProtocol
import com.pinganbaiyun.app.core.protocol.HexUtils
import com.pinganbaiyun.app.data.model.DoorConfig
import com.pinganbaiyun.app.util.PermissionUtils

/**
 * BLE 开门流程状态机（引擎层）。
 *
 * 一次 [start] 走完：**已知 MAC 直连** `connectGatt(TRANSPORT_LE)` → 发现服务 →
 * 按属性动态选 read/write/notify 特征 → 开启通知 → 读种子 → DES 握手 → 解析回执。
 * 直连失败（连接期）转一次扫描兜底（[startScanFallback]，5 重匹配见 [DoorMatcher]）。
 * 状态通过 [UnlockStateListener] 回调（始终在主线程），供 UI（原型 C-01..C-05）驱动界面。
 *
 * 权限：调用方须在 [start] 前确保已授予 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`
 * （Android 12+ 免定位）。所有 BLE 调用以 @SuppressLint("MissingPermission") 标注，
 * 由上层保证权限，避免在引擎层散落权限判断。
 *
 * 真机不开门修复要点：
 *  - **直连（对齐 SafeBaiyun）**：不扫描，用已知 MAC `getRemoteDevice` 直接 `connectGatt`。
 *    门锁广播包不被 Android `startScan` 收到（疑似 BLE 5 扩展广播），但 GATT 可向已知 MAC
 *    直连、无需先被扫到。扫描仅作直连失败的兜底。
 *  - **seed 读取通道（对齐参考 index.js）**：waitingSeed 阶段 read 与 notify 任一值变化都当种子
 *    （见 [handleCharacteristicChanged]）。
 *  - **组包**：notify 按 `A5 <len> … <cs> 5A`（len=总字节数）帧边界组包缓冲再分发
 *    （见 [extractFrames]）。
 *  - 全程协议级日志经 [onLog] 回调推给 UI（与 [listener] 一致，均在主线程），同时
 *    `Log.d(TAG, ...)` 落 logcat，供 `adb logcat -s BLE` 或 App 内日志面板确诊。
 */
@SuppressLint("MissingPermission")
class BleUnlockManager(
    private val context: Context,
    var listener: UnlockStateListener? = null,
) {

    /** 协议级日志回调（主线程）。每条日志同时写 logcat（标签 `BLE`）与回调。 */
    var onLog: ((String) -> Unit)? = null

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

    /** 处于「等待随机数种子」阶段：此期间收到的 read 响应或 notify 均视为种子（对齐参考）。 */
    private var waitingSeed = false

    /** notify 组包缓冲（hex），跨分包拼出完整 `A5..5A` 帧后再分发。 */
    private val notifyBuffer = StringBuilder()

    /** 非目标设备的扫描降噪计数（每隔约 1.5s 汇总一条，避免日志被逐条淹没）。 */
    private var nonTargetCount = 0
    private val seenNonTargetNames = mutableSetOf<String>()
    private var lastScanSummaryMs = 0L

    /** 门锁识别预计算值（对齐参考小程序 matchDevice 的 5 重匹配），[start] 时设置。 */
    private var targetMacNorm = ""
    private var targetNameUpper = ""

    /** 是否已进入「服务发现」之后阶段（过了连接期）；为真后连接失败不再回退扫描。 */
    private var reachedServices = false
    /** 直连失败后是否已尝试过一次扫描兜底（仅兜底一次）。 */
    private var fallbackScanTried = false

    val state: UnlockState get() = currentState

    /** 统一日志：落 logcat（标签 BLE）并切回主线程推给 [onLog]。 */
    private fun log(msg: String) {
        Log.d(TAG, msg)
        handler.post { onLog?.invoke(msg) }
    }

    /**
     * 协议密文 hex 脱敏：debug 下透传原 hex 便于 `adb logcat` 排障；release 下仅保留长度，
     * 避免 seed / 握手加密帧 / 回执密文经 [log] 的无条件 `Log.d` 外泄到 logcat。
     * MAC、蓝牙名属公开广播信息，不在此脱敏。
     */
    private fun maskSecretHex(hex: String): String =
        if (BuildConfig.DEBUG) hex else "<len=${hex.length / FrameAssembler.HEX_PER_BYTE}>"

    // ---------------- 对外入口 ----------------

    /**
     * 开始开门流程。若正在进行会先 [stop] 复位。
     *
     * **直连模式（对齐 SafeBaiyun）**：不扫描，校验 MAC 后用已知 MAC `getRemoteDevice` 直接
     * `connectGatt(TRANSPORT_LE)`。Android GATT 可向已知 MAC 直连、无需先被 `startScan` 发现——
     * 这门锁的广播包不被 `BluetoothLeScanner` 收到（疑似 BLE 5 扩展广播），但 MAC 直连不受影响。
     * 直连失败（连接期）会转一次扫描兜底（[startScanFallback]），扫描期沿用 5 重匹配（[DoorMatcher]）。
     */
    fun start(config: DoorConfig) {
        stop()
        finished = false
        this.config = config
        reachedServices = false
        fallbackScanTried = false
        // 预计算门锁识别值（扫描兜底用，对齐参考小程序 matchDevice）
        targetMacNorm = DoorMatcher.normalizeMacHex(config.mac)
        targetNameUpper = config.bluetoothName.uppercase()
        log("start door=${config.doorName} mac=${config.mac} btName=${config.bluetoothName} sdk=${Build.VERSION.SDK_INT} 模式=直连")
        log(
            "perms btEnabled=${PermissionUtils.isBluetoothEnabled(context)} " +
                "bleGranted=${PermissionUtils.hasBlePermissions(context)} " +
                "locGranted=${PermissionUtils.hasLocationPermission(context)} " +
                "locSwitchOn=${PermissionUtils.isLocationEnabled(context)}",
        )
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            log("start abort: 蓝牙未开启")
            fail("蓝牙未开启", UnlockError.BLUETOOTH_DISABLED)
            return
        }
        // MAC 格式校验后用已知 MAC 直接拿设备并 connectGatt（对齐 SafeBaiyun，无需先扫到）
        if (!BluetoothAdapter.checkBluetoothAddress(config.mac)) {
            log("start abort: MAC 格式不合法 ${config.mac}")
            fail("MAC 地址格式不合法", UnlockError.UNKNOWN)
            return
        }
        startFlowTimeout()
        val label = config.bluetoothName.ifBlank { config.mac }
        emit(UnlockState.Connecting(label))
        log("direct connect → ${config.mac}")
        connect(adapter.getRemoteDevice(config.mac))
    }

    /** 直连失败（连接期）的扫描兜底：仅试一次，沿用 5 重匹配定位门锁。 */
    private fun startScanFallback() {
        val adapter = adapter
        val cfg = config
        if (adapter == null || !adapter.isEnabled || cfg == null) {
            fail("蓝牙不可用", UnlockError.BLUETOOTH_DISABLED)
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            fail("无法获取 BLE 扫描器", UnlockError.UNKNOWN)
            return
        }
        this.scanner = scanner
        emit(UnlockState.Scanning)
        startScanTimeout()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        log("scan fallback start（直连失败兜底，按 5 重匹配 ${cfg.bluetoothName}/${cfg.mac}）")
        scanner.startScan(null, settings, scanCallback)
    }

    /** 连接期失败：未过服务发现且未试过扫描时，转一次扫描兜底；否则判失败。 */
    private fun onConnectFailed(reason: String) {
        handler.post {
            if (finished) return@post
            if (!reachedServices && !fallbackScanTried) {
                fallbackScanTried = true
                log("直连失败，转扫描兜底：$reason")
                runCatching { gatt?.disconnect() }
                runCatching { gatt?.close() }
                gatt = null
                startScanFallback()
            } else {
                fail(reason, UnlockError.CONNECT_FAILED)
            }
        }
    }

    /** 中止并释放所有 BLE 资源。 */
    fun stop() {
        log("stop 释放资源")
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
        waitingSeed = false
        notifyBuffer.setLength(0)
        nonTargetCount = 0
        seenNonTargetNames.clear()
        lastScanSummaryMs = 0L
        targetMacNorm = ""
        targetNameUpper = ""
        reachedServices = false
        fallbackScanTried = false
    }

    // ---------------- 扫描 ----------------

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val target = config ?: return
            val device = result.device
            val rec = result.scanRecord
            val name = rec?.deviceName
            // 5 重匹配（对齐参考小程序 matchDevice）：MAC / 蓝牙名 / 广播含 MAC（正序）/ 反序 / 含名
            val way = matchDoor(rec, device.address)
            if (way != null) {
                val uuids = rec?.serviceUuids
                    ?.joinToString(",") { it.uuid.toString() }.orEmpty()
                runCatching { scanner?.stopScan(this) }
                handler.removeCallbacks(scanTimeoutRunnable)
                log("scan MATCH mac=${device.address} name=$name rssi=${result.rssi} 命中方式=$way uuids=$uuids")
                emit(UnlockState.Connecting(name ?: device.address))
                connect(device)
                return
            }
            // 非门锁设备：降噪——仅每 ~1.5s 汇总一条，并采样展示去重后的设备名，
            // 避免 `scan device …` 把 start/perms/timeout 等关键行刷屏淹没。
            nonTargetCount++
            if (!name.isNullOrBlank()) seenNonTargetNames += name
            val now = SystemClock.elapsedRealtime()
            if (now - lastScanSummaryMs >= SCAN_SUMMARY_INTERVAL_MS) {
                lastScanSummaryMs = now
                val sample = seenNonTargetNames.take(SCAN_SUMMARY_NAME_SAMPLE).joinToString("、")
                val more = if (seenNonTargetNames.size > SCAN_SUMMARY_NAME_SAMPLE) {
                    " …共${seenNonTargetNames.size}种"
                } else {
                    ""
                }
                log("scan progress 已扫到 $nonTargetCount 条非目标广播（去重名字：$sample$more），仍按 5 重匹配 ${target.bluetoothName}/${target.mac}")
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("scan failed code=$errorCode")
            fail("扫描失败（错误码 $errorCode）", UnlockError.UNKNOWN)
        }
    }

    /**
     * 门锁识别（对齐参考小程序 matchDevice，index.js:1766-1798）。返回命中方式描述，未命中返回 null。
     * 纯匹配逻辑见 [DoorMatcher.matchWay]；广播原始数据的拼装见 [buildAdvHex]。
     */
    private fun matchDoor(rec: ScanRecord?, deviceAddress: String): String? =
        DoorMatcher.matchWay(
            deviceAddress = deviceAddress,
            deviceName = rec?.deviceName ?: "",
            advHex = buildAdvHex(rec),
            targetMac = targetMacNorm,
            targetName = targetNameUpper,
        )

    /**
     * 拼出可检索的广播 hex（对齐小程序 advertisData）：原始广播包 + 各厂商数据 + 各服务数据 +
     * 服务 UUID + 设备名 ASCII。门锁身份常嵌在 manufacturer/service data 里而非 deviceId/deviceName。
     */
    private fun buildAdvHex(rec: ScanRecord?): String {
        if (rec == null) return ""
        val sb = StringBuilder()
        runCatching { rec.bytes?.let { sb.append(HexUtils.bytesToHex(it)) } }
        val mfd: SparseArray<ByteArray> = rec.manufacturerSpecificData
        for (i in 0 until mfd.size()) {
            mfd.valueAt(i)?.let { sb.append(HexUtils.bytesToHex(it)) }
        }
        rec.serviceData?.forEach { (_, v) -> sb.append(HexUtils.bytesToHex(v)) }
        rec.serviceUuids?.forEach {
            sb.append(it.uuid.toString().replace("-", "").uppercase())
        }
        val name = (rec.deviceName ?: "").uppercase()
        if (name.isNotEmpty()) sb.append(HexUtils.bytesToHex(name.toByteArray(Charsets.UTF_8)))
        return sb.toString()
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
            log("conn state newState=$newState status=$status")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                handler.removeCallbacks(connectTimeoutRunnable)
                emitMain(UnlockState.PreparingChannel)
                // 连上直接发现服务（对齐 SafeBaiyun，不协商 MTU）。
                handler.post { runCatching { gatt.discoverServices() } }
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                if (!finished) onConnectFailed("连接已断开（status $status）")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            reachedServices = true // 进入服务发现阶段后，连接失败不再回退扫描
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("services discovered fail status=$status")
                failMain("服务发现失败（status $status）", UnlockError.SERVICE_NOT_FOUND)
                return
            }
            val service = BleConstants.SERVICE_CANDIDATES
                .firstNotNullOfOrNull { gatt.getService(it) }
            if (service == null) {
                log("未找到目标门禁服务（候选=${BleConstants.SERVICE_CANDIDATES}）")
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
            log(
                "chars total=${chars.size} " +
                    "read=${readChar?.uuid} write=${writeChar?.uuid} " +
                    "notify=${notifyChars.map { it.uuid }}",
            )
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
            log("descriptor write uuid=${descriptor.characteristic?.uuid} status=$status")
            pendingNotifyEnables--
            if (pendingNotifyEnables <= 0) {
                readSeed()
            }
        }

        /** 握手帧写入结果（诊断「握手帧是否真正写入设备」的关键）。 */
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            logWriteResult(characteristic.uuid, status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            log("char read(read-response) uuid=${characteristic.uuid} status=$status")
            @Suppress("DEPRECATION")
            handleSeedRead(characteristic.value, status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            log("char read(read-response) uuid=${characteristic.uuid} status=$status len=${value.size}")
            handleSeedRead(value, status)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            @Suppress("DEPRECATION")
            handleCharacteristicChanged(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(characteristic.uuid, value)
        }
    }

    private fun logWriteResult(uuid: java.util.UUID, status: Int) {
        val ok = status == BluetoothGatt.GATT_SUCCESS
        log("char write uuid=$uuid status=$status ${if (ok) "（握手帧已写入设备）" else "（写入失败）"}")
    }

    private fun enableNotification(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        log("enable notify uuid=${ch.uuid}")
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(BleConstants.CCCD_UUID) ?: run {
            log("no CCCD on ${ch.uuid}，直接读种子")
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
        log("readSeed uuid=${ch.uuid}")
        emitMain(UnlockState.ReadingSeed)
        waitingSeed = true
        startSeedTimeout()
        handler.post { runCatching { g.readCharacteristic(ch) } }
    }

    private fun handleSeedRead(value: ByteArray?, status: Int) {
        // read 响应与 notify 双通道都会进这里，用 waitingSeed 防重复处理
        if (!waitingSeed) {
            log("seed ignored（非 waitingSeed 阶段）status=$status len=${value?.size ?: -1}")
            return
        }
        waitingSeed = false
        handler.removeCallbacks(seedTimeoutRunnable)
        if (status != BluetoothGatt.GATT_SUCCESS || value == null || value.isEmpty()) {
            log("seed read fail status=$status len=${value?.size ?: -1}")
            failMain("读取随机数种子失败", UnlockError.READ_SEED_TIMEOUT)
            return
        }
        seed = value
        log("seed ok len=${value.size} hex=${maskSecretHex(HexUtils.bytesToHex(value))}")
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
        log("handshake frame(${frame.size}B) hex=${maskSecretHex(HexUtils.bytesToHex(frame))}")
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
        log("writeFrame uuid=${ch.uuid} writeType=$writeType len=${frame.size}")
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
            }.onFailure { log("writeFrame error: ${it.message ?: it}") }
        }
    }

    // ---------------- notify 处理（seed 兜底 + 组包） ----------------

    private fun handleCharacteristicChanged(uuid: java.util.UUID, value: ByteArray?) {
        if (value == null) return
        val hex = HexUtils.bytesToHex(value)
        log("notify uuid=$uuid len=${value.size}B hex=${maskSecretHex(hex)}")
        // 对齐参考 handleValueChange：waitingSeed 阶段任何值变化都当种子（notify 通道兜底）
        if (waitingSeed) {
            log("notify-as-seed（参考 onBLECharacteristicValueChange 兜底）")
            handleSeedRead(value, BluetoothGatt.GATT_SUCCESS)
            return
        }
        // 否则按帧边界组包后分发（GATT 回调在 binder 线程，切回主线程）
        handler.post { extractFrames(hex) }
    }

    /**
     * 按 `A5 <len> … <cs> 5A`（len=总字节数）帧边界从 [notifyBuffer] 提取完整帧分发；
     * 不完整则留在缓冲等下一包。长帧在默认 MTU 下会分包，此处组包还原。
     *
     * 组包逻辑抽到 [FrameAssembler]（纯函数，便于单测）；本方法负责把新到的 [hexIn] 追加进
     * 累积缓冲、调 [FrameAssembler.extract]、用剩余串回填缓冲、再逐帧分发（密文脱敏后落日志）。
     */
    private fun extractFrames(hexIn: String) {
        if (hexIn.isNotEmpty()) notifyBuffer.append(hexIn)
        val result = FrameAssembler.extract(notifyBuffer.toString())
        notifyBuffer.setLength(0)
        if (result.remaining.isNotEmpty()) notifyBuffer.append(result.remaining)
        for (frame in result.frames) {
            log("frame assembled(${frame.length / FrameAssembler.HEX_PER_BYTE}B) hex=${maskSecretHex(frame)}")
            dispatchNotification(frame)
        }
    }

    private fun dispatchNotification(frameHex: String) {
        if (finished) return
        val cfg = config ?: return
        when (val n = NotificationParser.parse(frameHex, cfg.key)) {
            is NotificationParser.Notification.HandshakeSuccess -> {
                log("notify→握手成功(回执>24)")
                handler.removeCallbacks(ackTimeoutRunnable)
                // 握手成功即门锁开始执行开门（对应 index.js finalize true）
                succeed(com.pinganbaiyun.app.core.protocol.OpenResult(OPEN_HANDSHAKE_OK, "握手成功，门锁执行开门"))
            }
            is NotificationParser.Notification.HandshakeFailed -> {
                log("notify→握手失败 codeWord=0x${n.codeWord}")
                handler.removeCallbacks(ackTimeoutRunnable)
                failMain("握手失败，设备返回码 0x${n.codeWord}", UnlockError.PROTOCOL_ERROR)
            }
            is NotificationParser.Notification.OpenResultReceived -> {
                log("notify→开门回执 success=${n.result.isSuccess} code=${n.result.code}")
                handler.removeCallbacks(ackTimeoutRunnable)
                if (n.result.isSuccess) {
                    succeed(n.result)
                } else {
                    failMain(n.result.message, UnlockError.PROTOCOL_ERROR)
                }
            }
            is NotificationParser.Notification.Fragment ->
                log("notify→fragment type=${n.type}（主流程忽略）")
            NotificationParser.Notification.Ignored ->
                log("notify→ignored（无法识别帧）")
        }
    }

    // ---------------- 超时 ----------------

    private val flowTimeoutRunnable = Runnable {
        log("timeout: 开门流程超时")
        failMain("开门流程超时", UnlockError.FLOW_TIMEOUT)
    }
    private val scanTimeoutRunnable = Runnable {
        log("timeout: 扫描超时未找到设备")
        runCatching { scanner?.stopScan(scanCallback) }
        failMain("未找到设备", UnlockError.SCAN_TIMEOUT)
    }
    private val connectTimeoutRunnable = Runnable {
        log("timeout: 连接超时")
        onConnectFailed("连接超时")
    }
    private val seedTimeoutRunnable = Runnable {
        log("timeout: 读种子超时（read 与 notify 均未回吐）")
        failMain("读取随机数超时", UnlockError.READ_SEED_TIMEOUT)
    }
    private val ackTimeoutRunnable = Runnable {
        log("timeout: 未收到门锁回执")
        failMain("未收到门锁回执", UnlockError.ACK_TIMEOUT)
    }

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
        log("succeed code=${result.code}")
        handler.removeCallbacksAndMessages(null)
        emit(UnlockState.Success(result))
        stop()
    }

    private fun fail(reason: String, error: UnlockError) {
        if (finished) return
        finished = true
        log("fail reason=$reason error=$error")
        handler.removeCallbacksAndMessages(null)
        emit(UnlockState.Failed(reason, error))
        stop()
    }

    private fun failMain(reason: String, error: UnlockError) = handler.post { fail(reason, error) }

    companion object {
        const val TAG = "BLE"

        /** 握手即开门成功时的伪状态码（区别于回执 00/02）。 */
        const val OPEN_HANDSHAKE_OK = "OK"

        private const val SCAN_SUMMARY_INTERVAL_MS = 1500L
        private const val SCAN_SUMMARY_NAME_SAMPLE = 6
    }
}

private fun BluetoothGattCharacteristic.hasProperty(prop: Int): Boolean =
    (properties and prop) != 0
