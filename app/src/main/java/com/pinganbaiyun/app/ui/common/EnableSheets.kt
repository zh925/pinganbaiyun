package com.pinganbaiyun.app.ui.common

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.databinding.SheetEnableBinding

/**
 * NFC / 蓝牙未开启的底部弹层（原型 P-02 / P-03）。
 * 用 [BottomSheetDialog] 直接承载 [SheetEnableBinding]，便于内联按钮回调。
 */
object EnableSheets {

    /** P-02：NFC 未开启。 */
    fun showNfcDisabled(activity: Activity, onRetry: () -> Unit) {
        val binding = SheetEnableBinding.inflate(activity.layoutInflater)
        binding.sheetIconBox.setBackgroundResource(R.drawable.bg_circle_warn)
        binding.sheetIcon.setImageResource(R.drawable.ic_warning)
        binding.sheetTitle.setText(R.string.nfc_off_title)
        binding.sheetDesc.setText(R.string.nfc_off_desc)
        binding.sheetBtnPrimary.setText(R.string.nfc_go_settings)
        binding.sheetBtnPrimary.setBackgroundResource(R.drawable.bg_button_brand)
        binding.sheetBtnSecondary.setText(R.string.nfc_already_on)

        val dialog = build(activity, binding)
        binding.sheetBtnPrimary.setOnClickListener {
            runCatching { activity.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                .onFailure { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            dialog.dismiss()
        }
        binding.sheetBtnSecondary.setOnClickListener {
            dialog.dismiss()
            onRetry()
        }
        dialog.show()
    }

    /** P-03：蓝牙未开启。 */
    fun showBluetoothDisabled(activity: Activity, onEnable: () -> Unit) {
        val binding = SheetEnableBinding.inflate(activity.layoutInflater)
        binding.sheetIconBox.setBackgroundResource(R.drawable.bg_circle_bt)
        binding.sheetIcon.setImageResource(R.drawable.ic_bluetooth)
        binding.sheetTitle.setText(R.string.bt_off_title)
        binding.sheetDesc.setText(R.string.bt_off_desc)
        binding.sheetBtnPrimary.setText(R.string.bt_turn_on)
        binding.sheetBtnPrimary.setBackgroundResource(R.drawable.bg_button_bt)
        binding.sheetBtnSecondary.setText(R.string.action_cancel)

        val dialog = build(activity, binding)
        binding.sheetBtnPrimary.setOnClickListener {
            dialog.dismiss()
            onEnable()
        }
        binding.sheetBtnSecondary.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun build(activity: Activity, binding: SheetEnableBinding): BottomSheetDialog {
        val dialog = BottomSheetDialog(activity, R.style.Theme_PinganBaiyun_BottomSheet)
        dialog.setContentView(binding.root)
        return dialog
    }
}
