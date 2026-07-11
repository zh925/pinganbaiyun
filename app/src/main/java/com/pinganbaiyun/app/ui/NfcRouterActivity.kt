package com.pinganbaiyun.app.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.core.nfc.NfcController
import com.pinganbaiyun.app.data.model.DoorConfig
import com.pinganbaiyun.app.databinding.ActivityMainBinding
import com.pinganbaiyun.app.ui.devices.DevicesFragment
import com.pinganbaiyun.app.ui.home.HomeFragment
import com.pinganbaiyun.app.ui.permission.PermissionActivity
import com.pinganbaiyun.app.ui.settings.SettingsFragment
import com.pinganbaiyun.app.ui.unlock.UnlockActivity
import com.pinganbaiyun.app.util.PermissionUtils

/**
 * 宿主 Activity——底部三 Tab（碰卡 / 蓝牙信息 / 设置）+ NFC 冷启动统一入口。
 *
 * 碰卡（NDEF 自定义 MIME）无论 APP 是否在前台都由本页接收：
 *  - 读出四要素 → 跳 [UnlockActivity] 走「读卡成功 → BLE 自动开门」运行流程（原型 N-03 → C 段）；
 *  - 是 NFC intent 但未解析出本 APP 数据 → 跳 [UnlockActivity] 的读卡失败态（原型 N-04）。
 */
class NfcRouterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nfcController: NfcController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        nfcController = NfcController(this)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_tap -> showFragment(TAG_HOME) { HomeFragment() }
                R.id.nav_devices -> showFragment(TAG_DEVICES) { DevicesFragment() }
                R.id.nav_settings -> showFragment(TAG_SETTINGS) { SettingsFragment() }
                else -> return@setOnItemSelectedListener false
            }
            true
        }

        if (savedInstanceState == null) {
            binding.bottomNav.selectedItemId = R.id.nav_tap
            val handled = handleNfcIntent(intent)
            if (!handled) {
                if (!applySelectTab(intent)) maybePromptPermissions()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleNfcIntent(intent)) applySelectTab(intent)
    }

    /** 处理「返回指定 Tab」的跳转（如开门失败后回到门禁列表）。 */
    private fun applySelectTab(intent: Intent?): Boolean {
        val tabId = intent?.getIntExtra(EXTRA_SELECT_TAB, 0) ?: 0
        if (tabId == 0) return false
        navigateToTab(tabId)
        setIntent(Intent(this, NfcRouterActivity::class.java))
        return true
    }

    override fun onResume() {
        super.onResume()
        if (nfcController.isEnabled) {
            runCatching { nfcController.enableForegroundDispatch() }
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { nfcController.disableForegroundDispatch() }
    }

    fun navigateToTab(itemId: Int) {
        binding.bottomNav.selectedItemId = itemId
    }

    private fun showFragment(tag: String, create: () -> Fragment) {
        val fm = supportFragmentManager
        val current = fm.fragments.firstOrNull { it.isVisible }
        if (current?.tag == tag) return
        val tx = fm.beginTransaction()
        fm.fragments.forEach { tx.hide(it) }
        val existing = fm.findFragmentByTag(tag)
        if (existing == null) {
            tx.add(R.id.fragmentContainer, create(), tag)
        } else {
            tx.show(existing)
        }
        tx.commit()
    }

    /** 处理可能的碰卡 Intent；返回是否为 NFC 触发。 */
    private fun handleNfcIntent(intent: Intent?): Boolean {
        val action = intent?.action ?: return false
        val isNfc = action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            action == NfcAdapter.ACTION_TECH_DISCOVERED
        if (!isNfc) return false

        val config: DoorConfig? = runCatching { nfcController.readFromIntent(intent) }.getOrNull()
        if (config != null && config.isValid) {
            startActivity(UnlockActivity.unlockIntent(this, config, fromNfc = true))
        } else {
            startActivity(UnlockActivity.readFailIntent(this))
        }
        // 避免旋转 / 重建时重复处理同一张卡
        setIntent(Intent(this, NfcRouterActivity::class.java))
        return true
    }

    private fun maybePromptPermissions() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val asked = prefs.getBoolean(KEY_PERM_PROMPTED, false)
        if (!asked && !PermissionUtils.hasBlePermissions(this)) {
            prefs.edit().putBoolean(KEY_PERM_PROMPTED, true).apply()
            startActivity(Intent(this, PermissionActivity::class.java))
        }
    }

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_DEVICES = "devices"
        private const val TAG_SETTINGS = "settings"
        private const val PREFS = "pinganbaiyun_ui"
        private const val KEY_PERM_PROMPTED = "perm_prompted"
        private const val EXTRA_SELECT_TAB = "select_tab"

        /** 打开宿主并切到「蓝牙信息」门禁列表 Tab。 */
        fun devicesIntent(context: android.content.Context): Intent =
            Intent(context, NfcRouterActivity::class.java)
                .putExtra(EXTRA_SELECT_TAB, R.id.nav_devices)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
