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
import java.util.UUID

class AndroidGattTransport(context: Context) : UnlockTransport {
    private val session = GattSession(AndroidGattClient(context))
    override fun start(door: DoorConfig, listener: UnlockTransport.Listener) = session.start(door, listener)
    override fun cancel() = session.cancel()
}

@SuppressLint("MissingPermission")
class AndroidGattClient(private val context: Context) : GattClient {
    private var callback: GattClient.Callback? = null
    private var gatt: BluetoothGatt? = null
    private val characteristics = mutableMapOf<String, BluetoothGattCharacteristic>()

    override fun connect(mac: String, callback: GattClient.Callback): Boolean {
        return try {
            this.callback = callback
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return false
            if (!BluetoothAdapter.checkBluetoothAddress(mac)) return false
            val device = adapter.getRemoteDevice(mac)
            gatt = device.connectGatt(context, false, androidCallback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        } catch (_: Exception) { false }
    }

    override fun discoverServices(): Boolean = safeCall { gatt?.discoverServices() == true }

    override fun enableNotifications(channel: GattChannel): Boolean = safeCall {
        val characteristic = characteristics[channel.id] ?: return@safeCall false
        gatt?.setCharacteristicNotification(characteristic, true) == true
    }

    override fun writeClientConfiguration(channel: GattChannel, indicate: Boolean): Boolean = safeCall {
        val characteristic = characteristics[channel.id] ?: return@safeCall false
        val descriptor = characteristic.getDescriptor(CCCD) ?: return@safeCall false
        val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val currentGatt = gatt ?: return@safeCall false
        if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            currentGatt.writeDescriptor(descriptor)
        }
    }

    override fun read(channel: GattChannel): Boolean = safeCall {
        gatt?.readCharacteristic(characteristics[channel.id] ?: return@safeCall false) == true
    }

    override fun write(channel: GattChannel, value: ByteArray): Boolean = safeCall {
        val characteristic = characteristics[channel.id] ?: return@safeCall false
        val currentGatt = gatt ?: return@safeCall false
        if (Build.VERSION.SDK_INT >= 33) {
            currentGatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            currentGatt.writeCharacteristic(characteristic)
        }
    }

    override fun close() {
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
        callback = null
        characteristics.clear()
    }

    private val androidCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            callback?.onConnected(status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(UUID.fromString(SafeBaiyunProtocol.SERVICE_UUID))
            val channels = service?.let(::mapChannels).orEmpty()
            callback?.onServicesDiscovered(status == BluetoothGatt.GATT_SUCCESS, service != null, channels)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            callback?.onDescriptorWritten(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("API 33 callback retained for API 26-32")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) callback?.onSeedRead(status == BluetoothGatt.GATT_SUCCESS, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            callback?.onSeedRead(status == BluetoothGatt.GATT_SUCCESS, value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            callback?.onCommandWritten(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    private fun mapChannels(service: BluetoothGattService): List<GattChannel> = service.characteristics.map { characteristic ->
        val id = characteristic.uuid.toString()
        characteristics[id] = characteristic
        val properties = characteristic.properties
        GattChannel(
            id,
            buildSet {
                if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add(GattFeature.READ)
                if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add(GattFeature.WRITE)
                if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add(GattFeature.NOTIFY)
                if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add(GattFeature.INDICATE)
            },
            characteristic.getDescriptor(CCCD) != null,
        )
    }

    private fun safeCall(block: () -> Boolean): Boolean = try { block() } catch (_: Exception) { false }

    companion object {
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
