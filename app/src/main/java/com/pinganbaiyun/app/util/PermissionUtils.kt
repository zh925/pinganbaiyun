package com.pinganbaiyun.app.util

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.nfc.NfcAdapter
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 蓝牙 / NFC 权限与开关状态查询（UI 层统一入口）。
 *
 * 权限策略与 [AndroidManifest] 一致——Android 12+（API 31）走免定位的
 * `BLUETOOTH_SCAN`(neverForLocation) / `BLUETOOTH_CONNECT`；更低版本的
 * `BLUETOOTH` / `BLUETOOTH_ADMIN` 为安装期权限，无需运行时申请。
 */
object PermissionUtils {

    /** 运行时需申请的蓝牙权限（低于 Android 12 返回空）。 */
    fun requiredBlePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    /** 是否已授予全部所需蓝牙权限。 */
    fun hasBlePermissions(context: Context): Boolean =
        requiredBlePermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Android 8～11（API 26..30）BLE 扫描所需的位置权限；Android 12+ 走
     * `BLUETOOTH_SCAN`(neverForLocation)，不需要定位。
     */
    fun requiredLocationPermission(): Array<String> =
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.R) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            emptyArray()
        }

    /** Android 8～11 扫描 BLE 所需的位置权限是否已授予。 */
    fun hasLocationPermission(context: Context): Boolean =
        requiredLocationPermission().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Android 8～11 BLE 扫描还需「定位开关」处于开启（与位置权限一并要求）。
     * 任一定位 Provider（GPS/网络）开启即视为开。
     */
    fun isLocationEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun nfcAdapter(context: Context): NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    fun isNfcSupported(context: Context): Boolean = nfcAdapter(context) != null

    fun isNfcEnabled(context: Context): Boolean = nfcAdapter(context)?.isEnabled == true

    fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isBluetoothEnabled(context: Context): Boolean =
        bluetoothAdapter(context)?.isEnabled == true
}
