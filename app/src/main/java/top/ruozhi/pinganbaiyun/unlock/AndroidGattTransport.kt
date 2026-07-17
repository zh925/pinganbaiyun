package top.ruozhi.pinganbaiyun.unlock

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import top.ruozhi.pinganbaiyun.model.DoorConfig
import top.ruozhi.pinganbaiyun.protocol.SafeBaiyunProtocol
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class AndroidGattTransport(private val context: Context) : UnlockTransport {
    private val ended = AtomicBoolean(false)
    private var listener: UnlockTransport.Listener? = null
    private var door: DoorConfig? = null
    private var gatt: BluetoothGatt? = null
    private var readCharacteristic: BluetoothGattCharacteristic? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private val descriptors = ArrayDeque<Pair<BluetoothGattDescriptor, ByteArray>>()

    override fun start(door: DoorConfig, listener: UnlockTransport.Listener) {
        this.door = door
        this.listener = listener
        listener.onStage(UnlockStage.CONNECTING)
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: return fail("此设备不支持蓝牙")
            require(BluetoothAdapter.checkBluetoothAddress(door.mac)) { "门禁 MAC 无效" }
            val device = adapter.getRemoteDevice(door.mac)
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail("无法发起蓝牙连接")
        } catch (_: SecurityException) {
            fail("附近设备权限已撤回")
        } catch (_: Exception) {
            fail("无法连接门禁设备")
        }
    }

    override fun cancel() {
        ended.set(true)
        closeGatt()
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (ended.get()) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) {
                fail("门禁设备连接失败")
                return
            }
            listener?.onStage(UnlockStage.DISCOVERING_SERVICES)
            if (!safeCall { gatt.discoverServices() }) fail("无法发起服务发现")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (ended.get()) return
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("服务发现失败")
            val service = gatt.getService(UUID.fromString(SafeBaiyunProtocol.SERVICE_UUID))
                ?: return fail("门禁协议服务不兼容")
            if (!prepareCharacteristics(service)) return
            listener?.onStage(UnlockStage.PREPARING_CHANNEL)
            writeNextDescriptorOrRead(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (ended.get()) return
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("通信通道初始化失败")
            writeNextDescriptorOrRead(gatt)
        }

        @Deprecated("API 33 callback retained for API 26-32")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) consumeSeed(status, characteristic.value ?: byteArrayOf(), gatt)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            consumeSeed(status, value, gatt)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (ended.get()) return
            if (status == BluetoothGatt.GATT_SUCCESS) sent() else fail("开门指令写入失败")
        }
    }

    private fun prepareCharacteristics(service: BluetoothGattService): Boolean {
        val sorted = service.characteristics.sortedBy { it.uuid.toString() }
        readCharacteristic = sorted.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 }
        writeCharacteristic = sorted.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 }
        val notify = sorted.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 }
        val indicate = sorted.firstOrNull { it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 }
        if (readCharacteristic == null || writeCharacteristic == null || notify == null || indicate == null) {
            fail("门禁协议特征不完整")
            return false
        }
        val subscriptions = listOf(
            notify to BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
            indicate to BluetoothGattDescriptor.ENABLE_INDICATION_VALUE,
        ).distinctBy { it.first.uuid }
        for ((characteristic, value) in subscriptions) {
            if (!safeCall { gatt?.setCharacteristicNotification(characteristic, true) == true }) {
                fail("无法启用设备通知")
                return false
            }
            val descriptor = characteristic.getDescriptor(CCCD) ?: run {
                fail("门禁通知描述符缺失")
                return false
            }
            descriptors.add(descriptor to value)
        }
        return true
    }

    private fun writeNextDescriptorOrRead(gatt: BluetoothGatt) {
        if (ended.get()) return
        val next = descriptors.pollFirst()
        if (next != null) {
            val started = safeCall {
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(next.first, next.second) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    next.first.value = next.second
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(next.first)
                }
            }
            if (!started) fail("无法初始化通信通道")
            return
        }
        listener?.onStage(UnlockStage.READING_SEED)
        val read = readCharacteristic ?: return fail("读取特征不可用")
        if (!safeCall { gatt.readCharacteristic(read) }) fail("无法发起随机数读取")
    }

    private fun consumeSeed(status: Int, seed: ByteArray, gatt: BluetoothGatt) {
        if (ended.get()) return
        if (status != BluetoothGatt.GATT_SUCCESS) return fail("读取设备随机数失败")
        val snapshot = door ?: return fail("门禁任务已失效")
        val frame = SafeBaiyunProtocol.buildUnlockFrame(snapshot.mac, snapshot.key, seed)
            .getOrElse { return fail("设备协议数据不受支持") }
        val write = writeCharacteristic ?: return fail("写入特征不可用")
        listener?.onStage(UnlockStage.SENDING_COMMAND)
        val started = safeCall {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(write, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                write.value = frame
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(write)
            }
        }
        if (!started) fail("无法发起开门指令写入")
    }

    private fun sent() {
        if (!ended.compareAndSet(false, true)) return
        closeGatt()
        listener?.onSent()
    }

    private fun fail(message: String) {
        if (!ended.compareAndSet(false, true)) return
        closeGatt()
        listener?.onFailure(message)
    }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
        descriptors.clear()
    }

    private fun safeCall(block: () -> Boolean): Boolean = try { block() } catch (_: Exception) { false }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
