package com.pinganbaiyun.app.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid

/**
 * 手动扫描附近门禁的轻量扫描器（供原型 B-01/B-02/B-03 手动入口用）。
 *
 * 与 [BleUnlockManager] 内部按 MAC 直连的扫描不同，这里按 Service UUID 候选表过滤、
 * 聚合去重出可见设备列表并持续回调，供设备列表页展示。选中某设备后仍由
 * [BleUnlockManager] 依据已保存四要素完成握手开门——本类只做「发现」，不碰协议/存储。
 *
 * 权限：调用方须在 [start] 前确保已授予 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`（Android 12+ 免定位）。
 */
@SuppressLint("MissingPermission")
class BleScanner(context: Context) {

    data class ScannedDevice(val name: String?, val mac: String, val rssi: Int)

    /** 扫描回调；[onResult] 在设备集合变化时于主线程回调，[onFinished] 在超时结束时回调。 */
    interface Callback {
        fun onResult(devices: List<ScannedDevice>)
        fun onFinished(devices: List<ScannedDevice>)
        fun onFailed(errorCode: Int)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var callback: Callback? = null
    private var scanning = false
    private val found = LinkedHashMap<String, ScannedDevice>()

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address ?: return
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull()
            found[mac] = ScannedDevice(name, mac, result.rssi)
            callback?.onResult(found.values.toList())
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            callback?.onFailed(errorCode)
        }
    }

    private val timeoutRunnable = Runnable { finish() }

    val isScanning: Boolean get() = scanning

    /** 开始扫描（[timeoutMs] 后自动结束并回调 [Callback.onFinished]）。 */
    fun start(callback: Callback, timeoutMs: Long = BleConstants.DISCOVERY_TIMEOUT_MS) {
        stop()
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            callback.onFailed(ERROR_BLUETOOTH_OFF)
            return
        }
        val scanner = bt.bluetoothLeScanner ?: run {
            callback.onFailed(ERROR_NO_SCANNER)
            return
        }
        this.scanner = scanner
        this.callback = callback
        found.clear()
        scanning = true
        val filters = BleConstants.SERVICE_CANDIDATES.map {
            ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(filters, settings, scanCallback)
        handler.postDelayed(timeoutRunnable, timeoutMs)
    }

    private fun finish() {
        if (!scanning) return
        scanning = false
        runCatching { scanner?.stopScan(scanCallback) }
        callback?.onFinished(found.values.toList())
    }

    /** 停止扫描并释放（不回调 onFinished）。 */
    fun stop() {
        handler.removeCallbacks(timeoutRunnable)
        if (scanning) {
            scanning = false
            runCatching { scanner?.stopScan(scanCallback) }
        }
        scanner = null
        callback = null
    }

    companion object {
        const val ERROR_BLUETOOTH_OFF = -1
        const val ERROR_NO_SCANNER = -2
    }
}
