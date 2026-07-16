package com.pinganbaiyun.app.ui.devices

import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pinganbaiyun.app.BuildConfig
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.core.ble.BleUnlockManager
import com.pinganbaiyun.app.core.ble.UnlockError
import com.pinganbaiyun.app.core.ble.UnlockState
import com.pinganbaiyun.app.data.model.DoorConfig
import com.pinganbaiyun.app.databinding.FragmentDevicesBinding
import com.pinganbaiyun.app.ui.common.EnableSheets
import com.pinganbaiyun.app.ui.devices.edit.DeviceEditActivity
import com.pinganbaiyun.app.ui.write.WriteCardActivity
import com.pinganbaiyun.app.util.PermissionUtils

/**
 * 蓝牙信息维护页（原型 M-01）：门禁四要素的增删改查 + 每条「开门 / 写卡 / 编辑」。
 *
 * 「开门」复用运行流程的 [BleUnlockManager]（BLE 连接 + DES 握手，与碰卡直连同一套逻辑），
 * 懒连接（点了才连），就地反馈开门中 / 成功 / 失败（原型 M-03/M-04/M-05）。一次仅允许一行开门。
 */
class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DevicesViewModel by viewModels()
    private lateinit var adapter: DeviceAdapter

    private lateinit var bleManager: BleUnlockManager
    private val handler = Handler(Looper.getMainLooper())

    /** 协议日志面板（含复制 / 分享外发出口）仅 debug 构建开启；release 下整组关闭，避免明文日志外泄。 */
    private val logPanelEnabled = BuildConfig.DEBUG

    /** 当前正在开门的门禁；非空表示有开门流程进行中（跨行防连点）。 */
    private var openingConfig: DoorConfig? = null
    /** 本次开门是否已因连接层瞬断自动重试过（仅重试 1 次）。 */
    private var autoRetried = false
    /** 等待权限 / 蓝牙开启结果时挂起的门禁。 */
    private var pendingConfig: DoorConfig? = null

    /** 协议日志缓冲（带时间戳），用于日志面板显示 / 复制 / 分享。 */
    private val logBuffer = StringBuilder()
    private val logTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var logLineCount = 0

    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) proceedOpen()
            else failPending(getString(R.string.unlock_bt_needed))
        }

    private val btEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (PermissionUtils.isBluetoothEnabled(requireContext())) ensureReadyThenOpen()
            else failPending(getString(R.string.bt_off_title))
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bleManager = BleUnlockManager(requireContext().applicationContext)
        bleManager.listener = com.pinganbaiyun.app.core.ble.UnlockStateListener { state -> onUnlockState(state) }

        adapter = DeviceAdapter(
            onOpen = { onOpenClicked(it) },
            onDefault = { config, isDefault -> confirmDefaultChange(config, isDefault) },
            onWrite = { openWrite(it) },
            onEdit = { openEdit(it) },
            onDelete = { confirmDelete(it) },
        )
        binding.devicesList.layoutManager = LinearLayoutManager(requireContext())
        binding.devicesList.adapter = adapter
        binding.fabAdd.setOnClickListener {
            startActivity(Intent(requireContext(), DeviceEditActivity::class.java))
        }
        // 协议日志面板（复制 / 分享外发出口）仅 debug 构建挂载 —— release 下整组关闭：不显示面板、不注册外发出口
        if (logPanelEnabled) {
            bleManager.onLog = { msg -> appendLog(msg) }
            binding.bleLogCopy.setOnClickListener { copyLog() }
            binding.bleLogShare.setOnClickListener { shareLog() }
            binding.bleLogClear.setOnClickListener { clearLog() }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val list = viewModel.list()
        adapter.submit(list, viewModel.defaultId())
        binding.devicesSubtitle.text = getString(R.string.devices_subtitle_count, list.size)
        val empty = list.isEmpty()
        binding.devicesEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.devicesList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    // ---------------- 开门（就地反馈） ----------------

    private fun onOpenClicked(config: DoorConfig) {
        // 已有开门进行中（含冷却窗口内按钮禁用）时忽略，防重复下发
        if (openingConfig != null) return
        pendingConfig = config
        ensureReadyThenOpen()
    }

    private fun ensureReadyThenOpen() {
        val ctx = context ?: return
        when {
            !PermissionUtils.isBluetoothEnabled(ctx) ->
                EnableSheets.showBluetoothDisabled(requireActivity()) {
                    btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            !PermissionUtils.hasBlePermissions(ctx) -> {
                val perms = PermissionUtils.requiredBlePermissions()
                if (perms.isEmpty()) proceedOpen() else permsLauncher.launch(perms)
            }
            else -> proceedOpen()
        }
    }

    private fun proceedOpen() {
        val config = pendingConfig ?: return
        pendingConfig = null
        openingConfig = config
        autoRetried = false
        adapter.setOpenState(config.id, DeviceAdapter.OpenState.Loading)
        if (logPanelEnabled) {
            appendLog("──── 开门：${config.doorName}（${config.bluetoothName} · ${config.mac}）────")
            binding.bleLogPanel.visibility = View.VISIBLE
        }
        bleManager.start(config)
    }

    /** 权限 / 蓝牙未就绪导致无法开门，就地标记该行失败。 */
    private fun failPending(reason: String) {
        val config = pendingConfig ?: return
        pendingConfig = null
        adapter.setOpenState(config.id, DeviceAdapter.OpenState.Failed(reason))
        Toast.makeText(requireContext(), reason, Toast.LENGTH_LONG).show()
    }

    private fun onUnlockState(state: UnlockState) {
        if (_binding == null) return
        val config = openingConfig ?: return
        when (state) {
            UnlockState.Idle -> Unit
            UnlockState.Scanning,
            is UnlockState.Connecting,
            UnlockState.PreparingChannel,
            UnlockState.ReadingSeed,
            UnlockState.Opening ->
                adapter.setOpenState(config.id, DeviceAdapter.OpenState.Loading)
            is UnlockState.Success -> onOpenSuccess(config)
            is UnlockState.Failed -> onOpenFailed(config, state.reason, state.error)
        }
    }

    private fun onOpenSuccess(config: DoorConfig) {
        bleManager.stop()
        openingConfig = null
        adapter.setOpenState(config.id, DeviceAdapter.OpenState.Success)
        binding.devicesList.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        maybeShowDefaultGuide(config)
        // 成功后按钮短暂保持「已开」（禁用态即冷却窗口），随后复位回「开门」
        handler.postDelayed({
            if (_binding != null) adapter.setOpenState(config.id, DeviceAdapter.OpenState.Idle)
        }, SUCCESS_RESET_MS)
    }

    private fun maybeShowDefaultGuide(config: DoorConfig) {
        if (viewModel.defaultId() != null || !viewModel.shouldShowDefaultGuide()) return
        viewModel.markDefaultGuideShown()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.device_default_guide_title)
            .setMessage(getString(R.string.device_default_guide_msg, config.doorName))
            .setNegativeButton(R.string.device_default_guide_later, null)
            .setPositiveButton(R.string.device_set_default_action) { _, _ -> setDefault(config) }
            .show()
    }

    private fun onOpenFailed(config: DoorConfig, reason: String, error: UnlockError) {
        // 连接层瞬断自动重试 1 次；鉴权失败（PROTOCOL_ERROR）等不自动重试
        if (error in TRANSIENT_ERRORS && !autoRetried) {
            autoRetried = true
            bleManager.start(config)
            return
        }
        bleManager.stop()
        openingConfig = null
        adapter.setOpenState(config.id, DeviceAdapter.OpenState.Failed(reason))
        Toast.makeText(requireContext(), reason, Toast.LENGTH_LONG).show()
    }

    // ---------------- 其它维护操作 ----------------

    private fun openEdit(config: DoorConfig) {
        startActivity(DeviceEditActivity.editIntent(requireContext(), config.id))
    }

    private fun openWrite(config: DoorConfig) {
        startActivity(WriteCardActivity.intent(requireContext(), config.id))
    }

    private fun confirmDefaultChange(config: DoorConfig, isDefault: Boolean) {
        val current = viewModel.defaultId()?.let(viewModel::get)
        when {
            isDefault -> MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.device_cancel_default_title)
                .setMessage(getString(R.string.device_cancel_default_msg, config.doorName))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.device_cancel_default) { _, _ ->
                    viewModel.setDefault(null)
                    refresh()
                    Toast.makeText(requireContext(), R.string.device_default_cancelled, Toast.LENGTH_SHORT).show()
                }
                .show()
            current != null -> MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.device_replace_default_title)
                .setMessage(getString(R.string.device_replace_default_msg, current.doorName, config.doorName))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.device_replace_default_action) { _, _ ->
                    setDefault(config)
                }
                .show()
            else -> MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.device_set_default_title)
                .setMessage(getString(R.string.device_set_default_msg, config.doorName))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.device_set_default_action) { _, _ ->
                    setDefault(config)
                }
                .show()
        }
    }

    private fun setDefault(config: DoorConfig) {
        viewModel.setDefault(config.id)
        refresh()
        Toast.makeText(
            requireContext(),
            getString(R.string.device_default_set, config.doorName),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun confirmDelete(config: DoorConfig) {
        val isDefault = viewModel.defaultId() == config.id
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.device_delete_confirm_title)
            .setMessage(
                getString(
                    if (isDefault) R.string.device_delete_default_confirm_msg
                    else R.string.device_delete_confirm_msg,
                    config.doorName,
                ),
            )
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.device_delete) { _, _ ->
                viewModel.delete(config.id)
                refresh()
            }
            .show()
    }

    // ---------------- 协议日志面板 ----------------

    /** onLog 回调已在主线程（BleUnlockManager.log 切回主线程）。 */
    private fun appendLog(msg: String) {
        if (!logPanelEnabled) return
        if (_binding == null) return
        val line = "${logTimeFmt.format(Date())}  $msg\n"
        logBuffer.append(line)
        logLineCount++
        // 仅保留最近 MAX_LOG_LINES 行，避免无限增长
        if (logLineCount > MAX_LOG_LINES) {
            val drop = logLineCount - MAX_LOG_LINES
            var dropped = 0
            var idx = 0
            while (dropped < drop) {
                val nl = logBuffer.indexOf('\n', idx)
                if (nl < 0) break
                idx = nl + 1
                dropped++
            }
            if (idx > 0) logBuffer.delete(0, idx)
            logLineCount = MAX_LOG_LINES
        }
        binding.bleLogPanel.visibility = View.VISIBLE
        binding.bleLogText.text = logBuffer
        binding.bleLogScroll.post { binding.bleLogScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun clearLog() {
        logBuffer.setLength(0)
        logLineCount = 0
        if (_binding != null) binding.bleLogText.text = ""
    }

    private fun logText(): String = logBuffer.toString().ifEmpty { "" }

    private fun copyLog() {
        val text = logText()
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), R.string.ble_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = requireContext()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.ble_log_title), text))
        Toast.makeText(requireContext(), R.string.ble_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLog() {
        val text = logText()
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), R.string.ble_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ble_log_share_subject))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.ble_log_share)))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
        bleManager.listener = null
        bleManager.onLog = null
        bleManager.stop()
        openingConfig = null
        pendingConfig = null
        _binding = null
    }

    companion object {
        private const val SUCCESS_RESET_MS = 1600L
        private const val MAX_LOG_LINES = 400
        private val TRANSIENT_ERRORS = setOf(
            UnlockError.CONNECT_FAILED,
            UnlockError.SCAN_TIMEOUT,
        )
    }
}
