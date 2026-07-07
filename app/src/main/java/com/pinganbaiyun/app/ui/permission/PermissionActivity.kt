package com.pinganbaiyun.app.ui.permission

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.databinding.ActivityPermissionBinding
import com.pinganbaiyun.app.ui.common.EnableSheets
import com.pinganbaiyun.app.util.PermissionUtils

/** 权限申请引导（原型 P-01）：Android 12+ 免定位，仅 NFC + 蓝牙。 */
class PermissionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionBinding

    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshStates()
            promptEnableIfNeeded()
        }

    private val btEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshStates() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appbar.appbarTitle.setText(R.string.perm_title)
        binding.appbar.appbarSub.setText(R.string.perm_subtitle)
        binding.appbar.appbarSub.visibility = android.view.View.VISIBLE
        binding.appbar.appbarBack.setOnClickListener { finish() }

        binding.permNfc.permIconBox.setBackgroundResource(R.drawable.bg_iconbox_nfc)
        binding.permNfc.permIcon.setImageResource(R.drawable.ic_nfc)
        binding.permNfc.permName.setText(R.string.perm_nfc_name)
        binding.permNfc.permDesc.setText(R.string.perm_nfc_desc)

        binding.permBt.permIconBox.setBackgroundResource(R.drawable.bg_iconbox_bt)
        binding.permBt.permIcon.setImageResource(R.drawable.ic_bluetooth)
        binding.permBt.permName.setText(R.string.perm_bt_name)
        binding.permBt.permDesc.setText(R.string.perm_bt_desc)

        binding.btnGrantAll.setOnClickListener { onGrantAll() }
        binding.btnLater.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshStates()
    }

    private fun onGrantAll() {
        val perms = PermissionUtils.requiredBlePermissions()
        if (perms.isNotEmpty() && !PermissionUtils.hasBlePermissions(this)) {
            permsLauncher.launch(perms)
        } else {
            promptEnableIfNeeded()
        }
    }

    private fun promptEnableIfNeeded() {
        when {
            PermissionUtils.isNfcSupported(this) && !PermissionUtils.isNfcEnabled(this) ->
                EnableSheets.showNfcDisabled(this) { refreshStates() }
            !PermissionUtils.isBluetoothEnabled(this) ->
                EnableSheets.showBluetoothDisabled(this) {
                    btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
        }
    }

    private fun refreshStates() {
        val nfcReady = PermissionUtils.isNfcSupported(this) && PermissionUtils.isNfcEnabled(this)
        applyState(binding.permNfc.permState, nfcReady)
        val btReady = PermissionUtils.hasBlePermissions(this) && PermissionUtils.isBluetoothEnabled(this)
        applyState(binding.permBt.permState, btReady)
    }

    private fun applyState(view: android.widget.TextView, ready: Boolean) {
        if (ready) {
            view.setText(R.string.perm_state_ready)
            view.setBackgroundResource(R.drawable.bg_pill_ok)
            view.setTextColor(ContextCompat.getColor(this, R.color.ok))
        } else {
            view.setText(R.string.perm_state_pending)
            view.setBackgroundResource(R.drawable.bg_pill_err)
            view.setTextColor(ContextCompat.getColor(this, R.color.err))
        }
    }
}
