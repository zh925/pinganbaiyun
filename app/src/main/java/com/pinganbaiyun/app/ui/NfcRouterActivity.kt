package com.pinganbaiyun.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.core.nfc.NfcController
import com.pinganbaiyun.app.data.model.DoorConfig

/**
 * 碰卡冷启动落地页（占位）。
 *
 * 阶段 1 只落地「统一 NFC 入口 + 引擎接线」的最小骨架：
 *  - onCreate / onNewIntent 统一走 [NfcController.readFromIntent]，解析出的四要素回填界面。
 *  - onResume/onPause 管理前台调度。
 *
 * 真正的界面（原型 21 屏：权限引导、扫描雷达、握手/开门动效、维护页、写卡页）
 * 由阶段 2 前端工程师基于本页与各 core 模块接口实现。此处仅打印读卡结果，验证链路连通。
 */
class NfcRouterActivity : AppCompatActivity() {

    private lateinit var nfcController: NfcController
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusView = findViewById(R.id.status_text)
        nfcController = NfcController(this)
        renderNfcAvailability()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
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

    private fun renderNfcAvailability() {
        statusView.text = when {
            !nfcController.isSupported -> getString(R.string.nfc_unsupported)
            !nfcController.isEnabled -> getString(R.string.nfc_disabled)
            else -> getString(R.string.nfc_ready)
        }
    }

    private fun handleIntent(intent: Intent?) {
        val config: DoorConfig? = runCatching { nfcController.readFromIntent(intent) }.getOrNull()
        if (config != null) {
            // 阶段 2：此处应转入 BLE 自动连接开门流程（BleUnlockManager.start(config)）。
            statusView.text = getString(
                R.string.nfc_card_read,
                config.doorName,
                config.mac,
                config.maskedKey,
                config.bluetoothName,
            )
        }
    }
}
