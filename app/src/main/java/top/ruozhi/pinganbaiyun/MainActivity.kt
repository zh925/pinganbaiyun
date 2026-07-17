package top.ruozhi.pinganbaiyun

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import top.ruozhi.pinganbaiyun.model.DoorConfig
import top.ruozhi.pinganbaiyun.model.DoorSnapshot
import top.ruozhi.pinganbaiyun.model.DoorValidation
import top.ruozhi.pinganbaiyun.model.LoadResult
import top.ruozhi.pinganbaiyun.unlock.CancelHandle
import top.ruozhi.pinganbaiyun.unlock.UnlockOrigin
import top.ruozhi.pinganbaiyun.unlock.UnlockTask
import java.util.UUID

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private val app by lazy { application as PingAnBaiYunApp }
    private lateinit var content: LinearLayout
    private var observer: CancelHandle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        observer = app.coordinator.observe { runOnUiThread { render(it) } }
        render(app.coordinator.task)

        val launcherColdStart = savedInstanceState == null && intent.action == Intent.ACTION_MAIN &&
            intent.hasCategory(Intent.CATEGORY_LAUNCHER) && app.consumeColdStart()
        if (launcherColdStart) {
            ((app.repository.load() as? LoadResult.Success)?.snapshot?.defaultDoor)?.let {
                requestUnlock(it, UnlockOrigin.COLD_START)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (app.pendingUnlock != null && hasConnectPermission() && bluetoothEnabled()) continuePendingUnlock()
    }

    override fun onDestroy() {
        observer?.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQUEST_CONNECT) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) ensureBluetoothThenContinue()
        else {
            app.pendingUnlock = null
            AlertDialog.Builder(this)
                .setTitle("需要附近设备权限")
                .setMessage("未授权时不会连接或发送指令。若已永久拒绝，请前往系统设置授权。")
                .setNegativeButton("取消", null)
                .setPositiveButton("系统设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }.show()
        }
    }

    @Deprecated("Bluetooth enable result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE) {
            if (bluetoothEnabled()) continuePendingUnlock()
            else {
                app.pendingUnlock = null
                toast("蓝牙未开启，已终止本次操作")
            }
        }
    }

    private fun render(task: UnlockTask?) {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = "平安白云"
            textSize = 28f
            setTextColor(Color.rgb(11, 61, 58))
        })
        content.addView(TextView(this).apply {
            text = "门禁凭据仅在本机加密保存，不使用网络"
            textSize = 14f
            setPadding(0, dp(4), 0, dp(16))
        })

        when (val loaded = app.repository.load()) {
            is LoadResult.Corrupt -> renderCorrupt(loaded.reason)
            is LoadResult.Success -> renderSnapshot(loaded.snapshot, task)
        }
    }

    private fun renderCorrupt(reason: String) {
        content.addView(TextView(this).apply { text = reason; setTextColor(Color.rgb(160, 35, 35)) })
        content.addView(Button(this).apply {
            text = "安全重置本地配置"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("确认重置？")
                    .setMessage("无法读取的加密数据将被删除且不可由 APP 恢复。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确认重置") { _, _ ->
                        app.repository.reset().onSuccess { render(app.coordinator.task) }.onFailure { toast("重置失败") }
                    }.show()
            }
        })
    }

    private fun renderSnapshot(snapshot: DoorSnapshot, task: UnlockTask?) {
        if (task != null) renderTask(task)
        content.addView(Button(this).apply {
            text = "新增门禁"
            setOnClickListener { showDoorEditor(null) }
        })
        if (snapshot.doors.isEmpty()) {
            content.addView(TextView(this).apply {
                text = "尚未添加门禁\n添加后可在离线状态手动开门。"
                gravity = Gravity.CENTER
                setPadding(0, dp(48), 0, dp(48))
            })
            return
        }
        snapshot.doors.sortedBy { it.doorName }.forEach { door -> content.addView(doorCard(door, snapshot.defaultId == door.id)) }
    }

    private fun renderTask(task: UnlockTask) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(226, 241, 238))
        }
        box.addView(TextView(this).apply { text = "${task.door.doorName} · ${task.detail}"; textSize = 17f })
        if (!task.stage.terminal) box.addView(Button(this).apply {
            text = "取消"
            setOnClickListener { app.coordinator.cancel() }
        }) else if (task.stage != top.ruozhi.pinganbaiyun.unlock.UnlockStage.SENT) box.addView(Button(this).apply {
            text = "重试"
            setOnClickListener {
                val latest = (app.repository.load() as? LoadResult.Success)?.snapshot?.doors?.firstOrNull { it.id == task.door.id }
                if (latest == null) toast("门禁已删除，请返回列表") else requestUnlock(latest, UnlockOrigin.RETRY)
            }
        })
        content.addView(box, marginParams(bottom = 16))
    }

    private fun doorCard(door: DoorConfig, isDefault: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setBackgroundColor(Color.rgb(244, 247, 246))
        }
        card.addView(TextView(this).apply {
            text = door.doorName + if (isDefault) "  [默认]" else ""
            textSize = 19f
        })
        card.addView(TextView(this).apply { text = "MAC ${DoorValidation.maskedMac(door.mac)}  ·  密钥 ••••••••" })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, block: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { block() }
            actions.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        action("开门") { requestUnlock(door, UnlockOrigin.MANUAL) }
        action("编辑") { showDoorEditor(door) }
        action(if (isDefault) "取消默认" else "设为默认") {
            app.repository.setDefault(if (isDefault) null else door.id)
                .onSuccess { render(app.coordinator.task); toast(if (isDefault) "已取消默认门禁" else "已设置默认门禁") }
                .onFailure { toast(it.message ?: "操作失败") }
        }
        action("删除") { confirmDelete(door, isDefault) }
        card.addView(actions)
        return card.apply { layoutParams = marginParams(top = 12) }
    }

    private fun showDoorEditor(existing: DoorConfig?) {
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0) }
        val name = EditText(this).apply { hint = "门禁名称"; setText(existing?.doorName.orEmpty()) }
        val mac = EditText(this).apply { hint = "MAC（XX:XX:XX:XX:XX:XX）"; setText(existing?.mac.orEmpty()) }
        val key = EditText(this).apply {
            hint = "16 位十六进制密钥"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(existing?.key.orEmpty())
            setTextIsSelectable(false)
        }
        fields.addView(name); fields.addView(mac); fields.addView(key)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "新增门禁" else "编辑门禁")
            .setView(fields)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                DoorValidation.validate(name.text.toString(), mac.text.toString(), key.text.toString())
                    .onSuccess { value ->
                        val door = DoorConfig(existing?.id ?: UUID.randomUUID().toString(), value.doorName, value.mac, value.key)
                        app.repository.upsert(door).onSuccess {
                            dialog.dismiss(); render(app.coordinator.task)
                        }.onFailure { toast(it.message ?: "保存失败") }
                    }.onFailure { toast(it.message ?: "输入无效") }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(door: DoorConfig, isDefault: Boolean) {
        AlertDialog.Builder(this)
            .setTitle("删除“${door.doorName}”？")
            .setMessage(if (isDefault) "删除后将同时取消默认门禁，不会自动改选其他门禁。" else "删除后本机将不再保存此门禁凭据。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                app.repository.delete(door.id).onSuccess { render(app.coordinator.task) }.onFailure { toast("删除失败") }
            }.show()
    }

    private fun requestUnlock(door: DoorConfig, origin: UnlockOrigin) {
        val active = app.coordinator.task?.takeUnless { it.stage.terminal }
        if (active != null || app.pendingUnlock != null) {
            toast("${active?.door?.doorName ?: app.pendingUnlock?.door?.doorName}正在进行开门操作")
            return
        }
        app.pendingUnlock = PingAnBaiYunApp.PendingUnlock(door.copy(), origin)
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            app.pendingUnlock = null
            toast("此设备不支持低功耗蓝牙")
            return
        }
        if (!hasConnectPermission()) {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CONNECT)
            return
        }
        ensureBluetoothThenContinue()
    }

    @SuppressLint("MissingPermission")
    private fun ensureBluetoothThenContinue() {
        if (bluetoothEnabled()) continuePendingUnlock()
        else {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE)
            } catch (_: SecurityException) {
                app.pendingUnlock = null
                toast("附近设备权限已撤回")
            }
        }
    }

    private fun continuePendingUnlock() {
        val pending = app.pendingUnlock ?: return
        app.pendingUnlock = null
        app.coordinator.start(pending.door, pending.origin)
            .onFailure { toast(it.message ?: "无法开始开门") }
    }

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothEnabled(): Boolean = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: SecurityException) { false }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun marginParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    companion object {
        private const val REQUEST_CONNECT = 41
        private const val REQUEST_ENABLE = 42
    }
}
