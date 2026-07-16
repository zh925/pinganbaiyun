package com.pinganbaiyun.app.ui.unlock

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.core.ble.UnlockError
import com.pinganbaiyun.app.core.ble.UnlockState
import com.pinganbaiyun.app.core.protocol.OpenResult
import com.pinganbaiyun.app.data.model.DoorConfig
import com.pinganbaiyun.app.databinding.ActivityUnlockBinding
import com.pinganbaiyun.app.databinding.ItemKvBinding
import com.pinganbaiyun.app.ui.NfcRouterActivity
import com.pinganbaiyun.app.ui.common.EnableSheets
import com.pinganbaiyun.app.ui.devices.edit.DeviceEditActivity
import com.pinganbaiyun.app.util.PermissionUtils

/**
 * 运行流程界面（原型 N-02/N-03 + C-01..C-05 + N-04）。
 * 读卡成功后自动接入 [UnlockViewModel] 的 BLE 开门状态机，按状态切换界面。
 */
class UnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockBinding
    private val viewModel: UnlockViewModel by viewModels()

    private var config: DoorConfig? = null
    private var started = false
    private var autoRetried = false

    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) proceedStart() else notifyBluetoothPermMissing()
        }

    private val btEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (PermissionUtils.isBluetoothEnabled(this)) ensureReadyThenStart()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appbar.appbarBack.setOnClickListener { finish() }

        if (intent.getStringExtra(EXTRA_MODE) == MODE_READ_FAIL) {
            renderReadFail()
            return
        }

        config = readConfig()
        val cfg = config
        if (cfg == null || !cfg.isValid) {
            renderReadFail()
            return
        }

        viewModel.onState = ::renderState
        val fromNfc = intent.getBooleanExtra(EXTRA_FROM_NFC, false)
        if (fromNfc) {
            renderReadOk(cfg)
            binding.unlockLead.postDelayed({ if (!started && !isFinishing) startUnlockFlow() }, 1100)
        } else {
            startUnlockFlow()
        }
    }

    // ---------------- 就绪校验与启动 ----------------

    private fun startUnlockFlow() {
        started = true
        ensureReadyThenStart()
    }

    private fun ensureReadyThenStart() {
        when {
            !PermissionUtils.isBluetoothEnabled(this) ->
                EnableSheets.showBluetoothDisabled(this) {
                    btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            !PermissionUtils.hasBlePermissions(this) -> {
                val perms = PermissionUtils.requiredBlePermissions()
                if (perms.isEmpty()) proceedStart() else permsLauncher.launch(perms)
            }
            else -> proceedStart()
        }
    }

    private fun proceedStart() {
        config?.let {
            autoRetried = false
            viewModel.start(it)
        }
    }

    private fun notifyBluetoothPermMissing() {
        Toast.makeText(this, R.string.unlock_bt_needed, Toast.LENGTH_LONG).show()
        renderFailed(getString(R.string.unlock_bt_needed))
    }

    // ---------------- 状态渲染 ----------------

    private fun renderState(state: UnlockState) {
        when (state) {
            UnlockState.Idle -> Unit
            UnlockState.Scanning -> renderScanning()
            is UnlockState.Connecting -> renderConnecting(handshaking = false)
            UnlockState.PreparingChannel, UnlockState.ReadingSeed -> renderConnecting(handshaking = true)
            UnlockState.Opening -> renderOpening()
            is UnlockState.Success -> renderSuccess(state.result)
            is UnlockState.Failed -> onUnlockFailed(state.reason, state.error)
        }
    }

    private fun onUnlockFailed(reason: String, error: UnlockError) {
        val cfg = config
        if (cfg != null && error in TRANSIENT_ERRORS && !autoRetried) {
            autoRetried = true
            viewModel.start(cfg)
            return
        }
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        renderFailed(reason)
    }

    private fun renderReadOk(cfg: DoorConfig) {
        setAppbar(R.string.nfc_read_ok_title, backVisible = true)
        showIcon(R.drawable.bg_circle_ok, R.drawable.ic_check)
        binding.unlockLead.text = cfg.doorName
        binding.unlockDesc.visibility = View.GONE
        setBadge(getString(R.string.nfc_read_ok_badge), R.drawable.bg_pill_ok, R.color.ok)
        setFourElements(cfg)
        setAlert(getString(R.string.nfc_read_ok_hint), AlertStyle.INFO)
        setPrimary(getString(R.string.btn_connect_open), R.drawable.bg_button_door) { startUnlockFlow() }
        hideSecondary()
    }

    private fun renderScanning() {
        setAppbar(R.string.unlock_connect_title, backVisible = true, sub = config?.doorName)
        showLoading(R.color.bt)
        setTexts(R.string.unlock_scanning_lead, R.string.unlock_scanning_desc)
        hideBadge(); hideCard(); hideAlert(); hidePrimary()
        setSecondary(getString(R.string.action_cancel)) { cancelAndFinish() }
    }

    private fun renderConnecting(handshaking: Boolean) {
        setAppbar(R.string.unlock_connect_title, backVisible = true, sub = config?.doorName)
        showLoading(R.color.bt)
        setTexts(R.string.unlock_connecting_lead, R.string.unlock_connecting_desc)
        hideBadge()
        beginCard()
        if (handshaking) {
            addRow(getString(R.string.unlock_kv_gatt), getString(R.string.unlock_state_connected), R.color.ok)
            addRow(getString(R.string.unlock_kv_handshake), getString(R.string.unlock_state_doing), R.color.bt)
            addRow(getString(R.string.unlock_kv_sync), getString(R.string.unlock_state_waiting), R.color.muted)
        } else {
            addRow(getString(R.string.unlock_kv_gatt), getString(R.string.unlock_state_doing), R.color.bt)
            addRow(getString(R.string.unlock_kv_handshake), getString(R.string.unlock_state_waiting), R.color.muted)
            addRow(getString(R.string.unlock_kv_sync), getString(R.string.unlock_state_waiting), R.color.muted)
        }
        endCard()
        hideAlert(); hidePrimary()
        setSecondary(getString(R.string.action_cancel)) { cancelAndFinish() }
    }

    private fun renderOpening() {
        setAppbar(R.string.unlock_opening_title, backVisible = true, sub = config?.doorName)
        showLoading(R.color.door)
        setTexts(R.string.unlock_opening_lead, R.string.unlock_opening_desc)
        hideBadge()
        beginCard()
        addRow(getString(R.string.unlock_kv_handshake), getString(R.string.unlock_state_done), R.color.ok)
        addRow(getString(R.string.unlock_kv_sync), getString(R.string.unlock_state_done), R.color.ok)
        addRow(getString(R.string.unlock_kv_ack), getString(R.string.unlock_state_doing), R.color.door)
        endCard()
        hideAlert(); hidePrimary()
        setSecondary(getString(R.string.action_cancel)) { cancelAndFinish() }
    }

    private fun renderSuccess(result: OpenResult) {
        val doorName = config?.doorName.orEmpty()
        if (result.code == OpenResult.CODE_ALREADY_OPEN) {
            setAppbar(R.string.unlock_already_title, backVisible = false)
            showIcon(R.drawable.bg_circle_door, R.drawable.ic_door)
            binding.unlockLead.setText(R.string.unlock_already_lead)
            setDesc(getString(R.string.unlock_already_desc))
            setBadge(getString(R.string.unlock_already_badge_02), R.drawable.bg_pill_door, R.color.door)
            hideCard(); hideAlert()
        } else {
            setAppbar(R.string.unlock_ok_title, backVisible = false)
            showIcon(R.drawable.bg_circle_ok, R.drawable.ic_check)
            binding.unlockLead.setText(R.string.unlock_ok_lead)
            setDesc(getString(R.string.unlock_ok_desc, doorName))
            setBadge(getString(R.string.unlock_ok_badge_00), R.drawable.bg_pill_ok, R.color.ok)
            hideCard()
            setAlert(getString(R.string.unlock_ok_hint), AlertStyle.OK)
        }
        setPrimary(getString(R.string.action_done), R.drawable.bg_button_brand) { finish() }
        hideSecondary()
    }

    private fun renderFailed(reason: String) {
        setAppbar(R.string.unlock_fail_title, backVisible = true, sub = config?.doorName)
        showIcon(R.drawable.bg_circle_err, R.drawable.ic_close)
        binding.unlockLead.setText(R.string.unlock_fail_lead)
        setDesc(reason)
        hideBadge(); hideCard()
        setAlert(getString(R.string.unlock_fail_hint), AlertStyle.WARN)
        if (config != null) {
            setPrimary(getString(R.string.unlock_retry), R.drawable.bg_button_door) {
                clearCard()
                autoRetried = false
                viewModel.start(config!!)
            }
        } else {
            hidePrimary()
        }
        setSecondary(getString(R.string.unlock_back_devices)) {
            startActivity(NfcRouterActivity.devicesIntent(this)); finish()
        }
    }

    private fun renderReadFail() {
        setAppbar(R.string.nfc_read_fail_title, backVisible = true)
        showIcon(R.drawable.bg_circle_err, R.drawable.ic_close)
        binding.unlockLead.setText(R.string.nfc_read_fail_lead)
        setDesc(getString(R.string.nfc_read_fail_desc))
        hideBadge(); hideCard()
        setAlert(getString(R.string.nfc_read_fail_hint), AlertStyle.WARN)
        setPrimary(getString(R.string.nfc_read_fail_retry), R.drawable.bg_button_brand) { finish() }
        setSecondary(getString(R.string.nfc_read_fail_goto_write)) {
            startActivity(Intent(this, DeviceEditActivity::class.java)); finish()
        }
    }

    private fun cancelAndFinish() {
        viewModel.stop()
        finish()
    }

    // ---------------- 视图工具 ----------------

    private fun setAppbar(titleRes: Int, backVisible: Boolean, sub: String? = null) {
        binding.appbar.appbarTitle.setText(titleRes)
        binding.appbar.appbarBack.visibility = if (backVisible) View.VISIBLE else View.GONE
        if (sub.isNullOrEmpty()) {
            binding.appbar.appbarSub.visibility = View.GONE
        } else {
            binding.appbar.appbarSub.text = sub
            binding.appbar.appbarSub.visibility = View.VISIBLE
        }
    }

    private fun showLoading(@ColorRes indicatorColor: Int) {
        binding.unlockIconCircle.visibility = View.GONE
        binding.unlockProgress.visibility = View.VISIBLE
        binding.unlockProgress.setIndicatorColor(ContextCompat.getColor(this, indicatorColor))
    }

    private fun showIcon(@DrawableRes circleBg: Int, @DrawableRes icon: Int) {
        binding.unlockProgress.visibility = View.GONE
        binding.unlockIconCircle.visibility = View.VISIBLE
        binding.unlockIconCircle.setBackgroundResource(circleBg)
        binding.unlockIcon.setImageResource(icon)
    }

    private fun setTexts(leadRes: Int, descRes: Int) {
        binding.unlockLead.setText(leadRes)
        binding.unlockDesc.setText(descRes)
        binding.unlockDesc.visibility = View.VISIBLE
    }

    private fun setDesc(text: String) {
        binding.unlockDesc.text = text
        binding.unlockDesc.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setBadge(text: String, @DrawableRes bg: Int, @ColorRes textColor: Int) {
        binding.unlockBadge.text = text
        binding.unlockBadge.setBackgroundResource(bg)
        binding.unlockBadge.setTextColor(ContextCompat.getColor(this, textColor))
        binding.unlockBadge.visibility = View.VISIBLE
    }

    private fun hideBadge() { binding.unlockBadge.visibility = View.GONE }

    private fun setFourElements(cfg: DoorConfig) {
        beginCard()
        addRow(getString(R.string.kv_door_name), cfg.doorName, null)
        addRow(getString(R.string.kv_bt_name), cfg.bluetoothName, null)
        addRow(getString(R.string.kv_mac), cfg.mac, null)
        addRow(getString(R.string.kv_key), cfg.maskedKey, null)
        endCard()
    }

    private fun beginCard() { binding.unlockRows.removeAllViews() }

    private fun endCard() { binding.unlockCard.visibility = View.VISIBLE }

    private fun clearCard() { binding.unlockRows.removeAllViews() }

    private fun hideCard() {
        binding.unlockRows.removeAllViews()
        binding.unlockCard.visibility = View.GONE
    }

    private fun addRow(label: String, value: String, @ColorRes valueColor: Int?) {
        val row = ItemKvBinding.inflate(layoutInflater, binding.unlockRows, false)
        row.kvKey.text = label
        row.kvValue.text = value
        if (valueColor != null) row.kvValue.setTextColor(ContextCompat.getColor(this, valueColor))
        binding.unlockRows.addView(row.root)
    }

    private enum class AlertStyle(@DrawableRes val bg: Int, @DrawableRes val icon: Int, @ColorRes val fg: Int) {
        INFO(R.drawable.bg_alert_info, R.drawable.ic_info, R.color.alert_info_fg),
        OK(R.drawable.bg_alert_ok, R.drawable.ic_check, R.color.alert_ok_fg),
        WARN(R.drawable.bg_alert_warn, R.drawable.ic_warning, R.color.alert_warn_fg),
    }

    private fun setAlert(text: String, style: AlertStyle) {
        binding.unlockAlert.setBackgroundResource(style.bg)
        binding.unlockAlertIcon.setImageResource(style.icon)
        val fg = ContextCompat.getColor(this, style.fg)
        binding.unlockAlertIcon.setColorFilter(fg)
        binding.unlockAlertText.setTextColor(fg)
        binding.unlockAlertText.text = text
        binding.unlockAlert.visibility = View.VISIBLE
    }

    private fun hideAlert() { binding.unlockAlert.visibility = View.GONE }

    private fun setPrimary(text: String, @DrawableRes bg: Int, onClick: () -> Unit) {
        binding.unlockBtnPrimary.text = text
        binding.unlockBtnPrimary.setBackgroundResource(bg)
        binding.unlockBtnPrimary.visibility = View.VISIBLE
        binding.unlockBtnPrimary.setOnClickListener { onClick() }
    }

    private fun hidePrimary() { binding.unlockBtnPrimary.visibility = View.GONE }

    private fun setSecondary(text: String, onClick: () -> Unit) {
        binding.unlockBtnSecondary.text = text
        binding.unlockBtnSecondary.visibility = View.VISIBLE
        binding.unlockBtnSecondary.setOnClickListener { onClick() }
    }

    private fun hideSecondary() { binding.unlockBtnSecondary.visibility = View.GONE }

    private fun readConfig(): DoorConfig? {
        val mac = intent.getStringExtra(EXTRA_MAC) ?: return null
        return DoorConfig.normalized(
            id = intent.getStringExtra(EXTRA_ID).orEmpty(),
            doorName = intent.getStringExtra(EXTRA_DOOR_NAME).orEmpty(),
            mac = mac,
            key = intent.getStringExtra(EXTRA_KEY).orEmpty(),
            bluetoothName = intent.getStringExtra(EXTRA_BT_NAME).orEmpty(),
        )
    }

    companion object {
        private val TRANSIENT_ERRORS = setOf(
            UnlockError.CONNECT_FAILED,
            UnlockError.SCAN_TIMEOUT,
        )
        private const val EXTRA_MODE = "mode"
        private const val MODE_READ_FAIL = "read_fail"
        private const val EXTRA_FROM_NFC = "from_nfc"
        private const val EXTRA_ID = "id"
        private const val EXTRA_DOOR_NAME = "door_name"
        private const val EXTRA_MAC = "mac"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_BT_NAME = "bt_name"

        fun unlockIntent(context: Context, config: DoorConfig, fromNfc: Boolean): Intent =
            Intent(context, UnlockActivity::class.java)
                .putExtra(EXTRA_FROM_NFC, fromNfc)
                .putExtra(EXTRA_ID, config.id)
                .putExtra(EXTRA_DOOR_NAME, config.doorName)
                .putExtra(EXTRA_MAC, config.mac)
                .putExtra(EXTRA_KEY, config.key)
                .putExtra(EXTRA_BT_NAME, config.bluetoothName)

        fun readFailIntent(context: Context): Intent =
            Intent(context, UnlockActivity::class.java).putExtra(EXTRA_MODE, MODE_READ_FAIL)
    }
}
