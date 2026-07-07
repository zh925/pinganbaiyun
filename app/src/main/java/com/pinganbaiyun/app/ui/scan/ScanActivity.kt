package com.pinganbaiyun.app.ui.scan

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.core.ble.BleScanner
import com.pinganbaiyun.app.core.storage.DoorConfigStore
import com.pinganbaiyun.app.data.model.DoorConfig
import com.pinganbaiyun.app.databinding.ActivityScanBinding
import com.pinganbaiyun.app.ui.common.EnableSheets
import com.pinganbaiyun.app.ui.unlock.UnlockActivity
import com.pinganbaiyun.app.util.PermissionUtils

/**
 * 手动扫描附近门禁（原型 B-01 扫描中 / B-02 设备列表 / B-03 空态）。
 * 发现由 [BleScanner] 完成；选中已保存门禁后交 [UnlockActivity] 走开门流程。
 */
class ScanActivity : AppCompatActivity() {

    /** 列表项：扫描到的设备 + 是否命中已保存门禁。 */
    data class ScanItem(val displayName: String, val mac: String, val matched: Boolean)

    private enum class Step { LOADING, LIST, EMPTY }

    private lateinit var binding: ActivityScanBinding
    private lateinit var scanner: BleScanner
    private lateinit var adapter: ScanAdapter

    private var step = Step.LOADING
    private var selectedMac: String? = null
    private var savedByMac: Map<String, DoorConfig> = emptyMap()
    private var lastDevices: List<BleScanner.ScannedDevice> = emptyList()

    private val permsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) beginScan() else finishWithToast()
        }

    private val btEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (PermissionUtils.isBluetoothEnabled(this)) ensureReadyThenScan()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        scanner = BleScanner(this)

        binding.appbar.appbarTitle.setText(R.string.scan_title)
        binding.appbar.appbarBack.setOnClickListener { finish() }

        adapter = ScanAdapter(onSelect = { selectedMac = it.mac })
        binding.scanList.layoutManager = LinearLayoutManager(this)
        binding.scanList.adapter = adapter

        binding.scanBtnPrimary.setOnClickListener { onPrimary() }
        binding.scanBtnSecondary.setOnClickListener { finish() }

        ensureReadyThenScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.stop()
    }

    // ---------------- 就绪与扫描 ----------------

    private fun ensureReadyThenScan() {
        when {
            !PermissionUtils.isBluetoothEnabled(this) ->
                EnableSheets.showBluetoothDisabled(this) {
                    btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }
            !PermissionUtils.hasBlePermissions(this) -> {
                val perms = PermissionUtils.requiredBlePermissions()
                if (perms.isEmpty()) beginScan() else permsLauncher.launch(perms)
            }
            else -> beginScan()
        }
    }

    private fun beginScan() {
        savedByMac = DoorConfigStore.get(this).list().associateBy { it.mac.uppercase() }
        selectedMac = null
        lastDevices = emptyList()
        renderLoading()
        scanner.start(object : BleScanner.Callback {
            override fun onResult(devices: List<BleScanner.ScannedDevice>) {
                lastDevices = devices
                binding.scanCount.text = getString(R.string.scan_found_count, devices.size)
            }

            override fun onFinished(devices: List<BleScanner.ScannedDevice>) {
                lastDevices = devices
                showResults(devices)
            }

            override fun onFailed(errorCode: Int) {
                renderEmpty()
            }
        })
    }

    private fun toItem(d: BleScanner.ScannedDevice): ScanItem {
        val saved = savedByMac[d.mac.uppercase()]
        val name = saved?.doorName ?: d.name ?: getString(R.string.app_name)
        return ScanItem(displayName = name, mac = d.mac, matched = saved != null)
    }

    private fun onPrimary() {
        when (step) {
            Step.LOADING -> {
                scanner.stop()
                showResults(lastDevices)
            }
            Step.LIST -> connectSelected()
            Step.EMPTY -> beginScan()
        }
    }

    private fun showResults(devices: List<BleScanner.ScannedDevice>) {
        val items = devices.map { toItem(it) }.sortedByDescending { it.matched }
        if (items.isEmpty()) renderEmpty() else renderList(items)
    }

    private fun connectSelected() {
        val mac = selectedMac ?: run {
            Toast.makeText(this, R.string.scan_unsaved_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val config = savedByMac[mac.uppercase()]
        if (config == null) {
            Toast.makeText(this, R.string.scan_unsaved_hint, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(UnlockActivity.unlockIntent(this, config, fromNfc = false))
        finish()
    }

    // ---------------- 渲染 ----------------

    private fun renderLoading() {
        step = Step.LOADING
        setSub(R.string.scan_sub)
        binding.scanLoadingSection.visibility = View.VISIBLE
        binding.scanList.visibility = View.GONE
        binding.scanEmptySection.visibility = View.GONE
        binding.scanListHint.visibility = View.GONE
        binding.scanCount.text = getString(R.string.scan_found_count, 0)
        binding.scanBtnPrimary.setText(R.string.scan_stop)
        binding.scanBtnPrimary.setBackgroundResource(R.drawable.bg_button_bt)
        binding.scanBtnPrimary.visibility = View.VISIBLE
        binding.scanBtnSecondary.visibility = View.GONE
    }

    private fun renderList(items: List<ScanItem>) {
        step = Step.LIST
        binding.appbar.appbarTitle.setText(R.string.scan_pick_title)
        setSub(null)
        selectedMac = items.firstOrNull { it.matched }?.mac ?: items.firstOrNull()?.mac
        adapter.submit(items, selectedMac)
        binding.scanLoadingSection.visibility = View.GONE
        binding.scanEmptySection.visibility = View.GONE
        binding.scanList.visibility = View.VISIBLE
        binding.scanListHint.visibility = View.VISIBLE
        binding.scanBtnPrimary.setText(R.string.scan_connect_open)
        binding.scanBtnPrimary.setBackgroundResource(R.drawable.bg_button_door)
        binding.scanBtnPrimary.visibility = View.VISIBLE
        binding.scanBtnSecondary.setText(R.string.scan_back_tap)
        binding.scanBtnSecondary.visibility = View.VISIBLE
    }

    private fun renderEmpty() {
        step = Step.EMPTY
        binding.appbar.appbarTitle.setText(R.string.scan_pick_title)
        setSub(null)
        binding.scanLoadingSection.visibility = View.GONE
        binding.scanList.visibility = View.GONE
        binding.scanListHint.visibility = View.GONE
        binding.scanEmptySection.visibility = View.VISIBLE
        binding.scanBtnPrimary.setText(R.string.scan_rescan)
        binding.scanBtnPrimary.setBackgroundResource(R.drawable.bg_button_bt)
        binding.scanBtnPrimary.visibility = View.VISIBLE
        binding.scanBtnSecondary.setText(R.string.scan_back_tap)
        binding.scanBtnSecondary.visibility = View.VISIBLE
    }

    private fun setSub(res: Int?) {
        if (res == null) {
            binding.appbar.appbarSub.visibility = View.GONE
        } else {
            binding.appbar.appbarSub.setText(res)
            binding.appbar.appbarSub.visibility = View.VISIBLE
        }
    }

    private fun finishWithToast() {
        Toast.makeText(this, R.string.unlock_bt_needed, Toast.LENGTH_LONG).show()
        finish()
    }
}
