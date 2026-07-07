package com.pinganbaiyun.app.util

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
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

    fun nfcAdapter(context: Context): NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    fun isNfcSupported(context: Context): Boolean = nfcAdapter(context) != null

    fun isNfcEnabled(context: Context): Boolean = nfcAdapter(context)?.isEnabled == true

    fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isBluetoothEnabled(context: Context): Boolean =
        bluetoothAdapter(context)?.isEnabled == true
}
