package com.pinganbaiyun.app.ui.write

import android.content.Context
import android.content.Intent
import android.nfc.Tag
import android.os.Bundle
import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.core.nfc.NfcController
import com.pinganbaiyun.app.core.nfc.NfcWriteResult
import com.pinganbaiyun.app.core.storage.DoorConfigStore
import com.pinganbaiyun.app.data.model.DoorConfig
import com.pinganbaiyun.app.databinding.ActivityWriteCardBinding
import com.pinganbaiyun.app.databinding.ItemKvBinding
import com.pinganbaiyun.app.ui.common.EnableSheets

/**
 * NFC 写卡流程（原型 W-01 选设备 → W-02 贴卡写入 → W-03 成功 / W-04 失败）。
 * 写入调用引擎层 [NfcController.writeConfig]（含写后回读校验），在后台线程执行避免卡 UI。
 */
class WriteCardActivity : AppCompatActivity() {

    private enum class Step { SELECT, TAP, SUCCESS, FAIL }

    private lateinit var binding: ActivityWriteCardBinding
    private lateinit var nfcController: NfcController
    private lateinit var store: DoorConfigStore
    private lateinit var adapter: WriteDeviceAdapter

    private var step = Step.SELECT
    private var selected: DoorConfig? = null
    private var writing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWriteCardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        nfcController = NfcController(this)
        store = DoorConfigStore.get(this)

        binding.appbar.appbarBack.setOnClickListener { onBackFromStep() }

        adapter = WriteDeviceAdapter(onSelect = { selected = it })
        binding.writeDeviceList.layoutManager = LinearLayoutManager(this)
        binding.writeDeviceList.adapter = adapter

        val list = store.list()
        val preselectId = intent.getStringExtra(EXTRA_ID)
        selected = list.firstOrNull { it.id == preselectId } ?: list.firstOrNull()
        adapter.submit(list, selected?.id)

        renderSelect()

        binding.writeBtnPrimary.setOnClickListener { onPrimary() }
        binding.writeBtnSecondary.setOnClickListener { onSecondary() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (step == Step.TAP && !writing) {
            val tag = nfcController.extractTag(intent)
            if (tag != null) performWrite(tag)
        }
    }

    override fun onResume() {
        super.onResume()
        if (step == Step.TAP && nfcController.isEnabled) {
            runCatching { nfcController.enableForegroundDispatch() }
        }
    }

    override fun onPause() {
        super.onPause()
        runCatching { nfcController.disableForegroundDispatch() }
    }

    private fun onPrimary() {
        when (step) {
            Step.SELECT -> {
                if (selected == null) return
                if (!nfcController.isEnabled) {
                    EnableSheets.showNfcDisabled(this) { /* 用户开启后回到本页再点下一步 */ }
                    return
                }
                enterTap()
            }
            Step.SUCCESS -> finish()
            Step.FAIL -> enterTap()
            Step.TAP -> Unit
        }
    }

    private fun onSecondary() {
        when (step) {
            Step.FAIL -> enterTap() // 更换卡片：回到贴卡等待
            else -> Unit
        }
    }

    private fun onBackFromStep() {
        when (step) {
            Step.TAP -> renderSelect()
            Step.SUCCESS -> finish()
            else -> finish()
        }
    }

    // ---------------- 写入 ----------------

    private fun enterTap() {
        step = Step.TAP
        renderTap()
        if (nfcController.isEnabled) {
            runCatching { nfcController.enableForegroundDispatch() }
        }
    }

    private fun performWrite(tag: Tag) {
        val config = selected ?: return
        writing = true
        renderWriting()
        Thread {
            val result = nfcController.writeConfig(tag, config)
            runOnUiThread {
                writing = false
                when (result) {
                    is NfcWriteResult.Success -> renderSuccess(result.config)
                    is NfcWriteResult.Failure -> renderFail(result.message)
                }
            }
        }.start()
    }

    // ---------------- 渲染 ----------------

    private fun renderSelect() {
        step = Step.SELECT
        binding.appbar.appbarTitle.setText(R.string.write_pick_title)
        setSub(getString(R.string.write_pick_sub))
        binding.writeSelectSection.visibility = View.VISIBLE
        binding.writeStatusSection.visibility = View.GONE
        binding.writeSelectHint.visibility = View.VISIBLE
        binding.writeBtnPrimary.setText(R.string.write_next)
        binding.writeBtnPrimary.setBackgroundResource(R.drawable.bg_button_door)
        binding.writeBtnPrimary.visibility = View.VISIBLE
        binding.writeBtnSecondary.visibility = View.GONE
    }

    private fun renderTap() {
        binding.appbar.appbarTitle.setText(R.string.write_tap_title)
        setSub(getString(R.string.write_tap_sub))
        binding.writeSelectSection.visibility = View.GONE
        binding.writeSelectHint.visibility = View.GONE
        binding.writeStatusSection.visibility = View.VISIBLE
        showOrb()
        binding.writeLead.setText(R.string.write_tap_lead)
        binding.writeDesc.text = getString(R.string.write_tap_desc, selected?.doorName.orEmpty())
        hideBadge(); hideCard()
        setAlert(getString(R.string.write_tap_warn), AlertStyle.WARN)
        binding.writeBtnPrimary.visibility = View.GONE
        binding.writeBtnSecondary.visibility = View.GONE
    }

    private fun renderWriting() {
        binding.writeLead.setText(R.string.write_writing_lead)
    }

    private fun renderSuccess(config: DoorConfig) {
        step = Step.SUCCESS
        binding.appbar.appbarTitle.setText(R.string.write_ok_title)
        setSub(null)
        binding.appbar.appbarBack.visibility = View.GONE
        binding.writeStatusSection.visibility = View.VISIBLE
        showIcon(R.drawable.bg_circle_ok, R.drawable.ic_check)
        binding.writeLead.setText(R.string.write_ok_lead)
        binding.writeDesc.visibility = View.GONE
        setBadge(getString(R.string.write_ok_badge, config.doorName), R.drawable.bg_pill_ok, R.color.ok)
        beginCard()
        addRow(getString(R.string.kv_door_name), config.doorName)
        addRow(getString(R.string.kv_bt_name), config.bluetoothName)
        addRow(getString(R.string.write_kv_format), getString(R.string.write_format_value))
        endCard()
        setAlert(getString(R.string.write_ok_hint), AlertStyle.INFO)
        binding.writeBtnPrimary.setText(R.string.action_done)
        binding.writeBtnPrimary.setBackgroundResource(R.drawable.bg_button_brand)
        binding.writeBtnPrimary.visibility = View.VISIBLE
        binding.writeBtnSecondary.visibility = View.GONE
    }

    private fun renderFail(message: String) {
        step = Step.FAIL
        binding.appbar.appbarTitle.setText(R.string.write_fail_title)
        setSub(null)
        binding.writeStatusSection.visibility = View.VISIBLE
        showIcon(R.drawable.bg_circle_err, R.drawable.ic_close)
        binding.writeLead.setText(R.string.write_fail_lead)
        binding.writeDesc.text = message
        binding.writeDesc.visibility = View.VISIBLE
        hideBadge(); hideCard()
        setAlert(getString(R.string.write_fail_hint), AlertStyle.WARN)
        binding.writeBtnPrimary.setText(R.string.write_retry)
        binding.writeBtnPrimary.setBackgroundResource(R.drawable.bg_button_door)
        binding.writeBtnPrimary.visibility = View.VISIBLE
        binding.writeBtnSecondary.setText(R.string.write_change_card)
        binding.writeBtnSecondary.visibility = View.VISIBLE
    }

    // ---------------- 视图工具 ----------------

    private fun setSub(sub: String?) {
        if (sub.isNullOrEmpty()) {
            binding.appbar.appbarSub.visibility = View.GONE
        } else {
            binding.appbar.appbarSub.text = sub
            binding.appbar.appbarSub.visibility = View.VISIBLE
        }
    }

    private fun showOrb() {
        binding.writeOrb.visibility = View.VISIBLE
        binding.writeIconCircle.visibility = View.GONE
    }

    private fun showIcon(@DrawableRes circleBg: Int, @DrawableRes icon: Int) {
        binding.writeOrb.visibility = View.GONE
        binding.writeIconCircle.visibility = View.VISIBLE
        binding.writeIconCircle.setBackgroundResource(circleBg)
        binding.writeIcon.setImageResource(icon)
    }

    private fun setBadge(text: String, @DrawableRes bg: Int, @ColorRes textColor: Int) {
        binding.writeBadge.text = text
        binding.writeBadge.setBackgroundResource(bg)
        binding.writeBadge.setTextColor(ContextCompat.getColor(this, textColor))
        binding.writeBadge.visibility = View.VISIBLE
    }

    private fun hideBadge() { binding.writeBadge.visibility = View.GONE }

    private fun beginCard() { binding.writeRows.removeAllViews() }
    private fun endCard() { binding.writeCard.visibility = View.VISIBLE }
    private fun hideCard() {
        binding.writeRows.removeAllViews()
        binding.writeCard.visibility = View.GONE
    }

    private fun addRow(label: String, value: String) {
        val row = ItemKvBinding.inflate(layoutInflater, binding.writeRows, false)
        row.kvKey.text = label
        row.kvValue.text = value
        binding.writeRows.addView(row.root)
    }

    private enum class AlertStyle(@DrawableRes val bg: Int, @DrawableRes val icon: Int, @ColorRes val fg: Int) {
        INFO(R.drawable.bg_alert_info, R.drawable.ic_info, R.color.alert_info_fg),
        WARN(R.drawable.bg_alert_warn, R.drawable.ic_warning, R.color.alert_warn_fg),
    }

    private fun setAlert(text: String, style: AlertStyle) {
        binding.writeAlert.setBackgroundResource(style.bg)
        binding.writeAlertIcon.setImageResource(style.icon)
        val fg = ContextCompat.getColor(this, style.fg)
        binding.writeAlertIcon.setColorFilter(fg)
        binding.writeAlertText.setTextColor(fg)
        binding.writeAlertText.text = text
        binding.writeAlert.visibility = View.VISIBLE
    }

    companion object {
        private const val EXTRA_ID = "extra_id"

        fun intent(context: Context, preselectId: String): Intent =
            Intent(context, WriteCardActivity::class.java).putExtra(EXTRA_ID, preselectId)
    }
}
