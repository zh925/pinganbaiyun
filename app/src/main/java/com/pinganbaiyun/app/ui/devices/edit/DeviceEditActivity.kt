package com.pinganbaiyun.app.ui.devices.edit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputLayout
import com.pinganbaiyun.app.R
import com.pinganbaiyun.app.core.protocol.DoorProtocol
import com.pinganbaiyun.app.core.storage.DoorConfigStore
import com.pinganbaiyun.app.data.model.DoorConfig
import com.pinganbaiyun.app.databinding.ActivityDeviceEditBinding
import com.pinganbaiyun.app.ui.write.WriteCardActivity

/** 新增 / 编辑门禁四要素（原型 M-02），实时校验；保存到本地加密存储。 */
class DeviceEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceEditBinding
    private lateinit var store: DoorConfigStore
    private var editingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = DoorConfigStore.get(this)

        editingId = intent.getStringExtra(EXTRA_ID)
        val existing = editingId?.let { store.get(it) }

        binding.appbar.appbarTitle.setText(
            if (existing != null) R.string.edit_title_edit else R.string.edit_title_new,
        )
        binding.appbar.appbarSub.setText(R.string.edit_subtitle)
        binding.appbar.appbarSub.visibility = android.view.View.VISIBLE
        binding.appbar.appbarBack.setOnClickListener { finish() }

        if (existing != null) {
            binding.etDoorName.setText(existing.doorName)
            binding.etBtName.setText(existing.bluetoothName)
            binding.etMac.setText(existing.mac)
            binding.etKey.setText(existing.key)
        }

        attachLiveValidation()
        binding.btnSaveOnly.setOnClickListener { save(thenWrite = false) }
        binding.btnSaveWrite.setOnClickListener { save(thenWrite = true) }
    }

    private fun attachLiveValidation() {
        watch(binding.etDoorName) { validateName(showEmpty = false) }
        watch(binding.etBtName) { validateBtName(showEmpty = false) }
        watch(binding.etMac) { validateMac(showEmpty = false) }
        watch(binding.etKey) { validateKey(showEmpty = false) }
    }

    private inline fun watch(view: com.google.android.material.textfield.TextInputEditText, crossinline onChange: () -> Unit) {
        view.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = onChange()
        })
    }

    private fun text(view: com.google.android.material.textfield.TextInputEditText): String =
        view.text?.toString()?.trim().orEmpty()

    private fun setError(til: TextInputLayout, error: String?) {
        til.error = error
        til.isErrorEnabled = error != null
    }

    private fun validateName(showEmpty: Boolean): Boolean {
        val v = text(binding.etDoorName)
        return when {
            v.isEmpty() -> { if (showEmpty) setError(binding.tilDoorName, getString(R.string.hint_door_name)); false }
            else -> { setError(binding.tilDoorName, null); true }
        }
    }

    private fun validateBtName(showEmpty: Boolean): Boolean {
        val v = text(binding.etBtName)
        return when {
            v.isEmpty() -> { if (showEmpty) setError(binding.tilBtName, getString(R.string.hint_bt_name)); false }
            !DoorConfig.isValidBluetoothName(v) -> { setError(binding.tilBtName, getString(R.string.hint_bt_name)); false }
            else -> { setError(binding.tilBtName, null); true }
        }
    }

    private fun validateMac(showEmpty: Boolean): Boolean {
        val v = text(binding.etMac).uppercase()
        return when {
            v.isEmpty() -> { if (showEmpty) setError(binding.tilMac, getString(R.string.hint_mac)); false }
            !DoorProtocol.isValidMac(v) -> { setError(binding.tilMac, getString(R.string.hint_mac)); false }
            else -> { setError(binding.tilMac, null); true }
        }
    }

    private fun validateKey(showEmpty: Boolean): Boolean {
        val v = DoorProtocol.sanitizeKey(text(binding.etKey))
        return when {
            v.isEmpty() -> { if (showEmpty) setError(binding.tilKey, getString(R.string.hint_key)); false }
            !DoorProtocol.isValidKey(v) -> { setError(binding.tilKey, getString(R.string.hint_key)); false }
            else -> { setError(binding.tilKey, null); true }
        }
    }

    private fun save(thenWrite: Boolean) {
        val ok = validateName(true) and validateBtName(true) and validateMac(true) and validateKey(true)
        if (!ok) return
        val config = DoorConfig.normalized(
            id = editingId.orEmpty(),
            doorName = text(binding.etDoorName),
            mac = text(binding.etMac),
            key = text(binding.etKey),
            bluetoothName = text(binding.etBtName),
        )
        val saved = store.upsert(config)
        if (thenWrite) {
            startActivity(WriteCardActivity.intent(this, saved.id))
        } else {
            Toast.makeText(this, R.string.edit_saved, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    companion object {
        private const val EXTRA_ID = "extra_id"

        fun editIntent(context: Context, id: String): Intent =
            Intent(context, DeviceEditActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
