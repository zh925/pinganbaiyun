package top.ruozhi.pinganbaiyun

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import top.ruozhi.pinganbaiyun.model.DoorConfig
import top.ruozhi.pinganbaiyun.model.DoorSnapshot
import top.ruozhi.pinganbaiyun.model.DoorValidation
import top.ruozhi.pinganbaiyun.model.LoadResult
import top.ruozhi.pinganbaiyun.ui.UiScene
import top.ruozhi.pinganbaiyun.ui.UiStateMapper
import top.ruozhi.pinganbaiyun.unlock.CancelHandle
import top.ruozhi.pinganbaiyun.unlock.UnlockFlowAction
import top.ruozhi.pinganbaiyun.unlock.UnlockOrigin
import top.ruozhi.pinganbaiyun.unlock.UnlockPrecondition
import top.ruozhi.pinganbaiyun.unlock.UnlockStage
import top.ruozhi.pinganbaiyun.unlock.UnlockTask
import java.util.UUID

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private val app by lazy { application as PingAnBaiYunApp }
    private var observer: CancelHandle? = null
    private var loading = true
    private var interruption: UiScene? = null
    private var dismissedTaskId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PAPER
        window.navigationBarColor = if (Build.VERSION.SDK_INT >= 27) PAPER else INK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        render(null)
        observer = app.coordinator.observe { task -> runOnUiThread { render(task) } }
        window.decorView.post {
            loading = false
            render(app.coordinator.task)
            val launcherColdStart = savedInstanceState == null && intent.action == Intent.ACTION_MAIN &&
                intent.hasCategory(Intent.CATEGORY_LAUNCHER)
            val defaultDoor = (app.repository.load() as? LoadResult.Success)?.snapshot?.defaultDoor
            val action = app.unlockFlow.coldStart(defaultDoor, launcherColdStart, precondition())
            handleFlow(action)
            if (action == UnlockFlowAction.NoAction && app.unlockFlow.pending != null) {
                handleFlow(app.unlockFlow.resume(precondition()))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!loading && app.unlockFlow.pending != null && precondition() == UnlockPrecondition.READY) {
            handleFlow(app.unlockFlow.resume(UnlockPrecondition.READY))
        }
    }

    override fun onDestroy() {
        observer?.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQUEST_CONNECT) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            interruption = null
            handleFlow(app.unlockFlow.resume(precondition()))
        } else {
            app.unlockFlow.cancelPending()
            interruption = UiScene.PERMISSION_REQUIRED
            render(app.coordinator.task)
        }
    }

    @Deprecated("Bluetooth enable result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ENABLE) return
        if (bluetoothEnabled() && app.unlockFlow.pending != null) {
            interruption = null
            handleFlow(app.unlockFlow.resume(precondition()))
        } else {
            app.unlockFlow.cancelPending()
            interruption = UiScene.BLUETOOTH_DISABLED
            render(app.coordinator.task)
        }
    }

    private fun render(task: UnlockTask?) {
        val effectiveTask = task?.takeUnless { it.taskId == dismissedTaskId }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAPER)
        }
        root.addView(header(), linearParams(match, wrap))
        root.addView(privacyStrip(), linearParams(match, wrap).apply {
            leftMargin = dp(18); rightMargin = dp(18); bottomMargin = dp(2)
        })

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(body, ViewGroup.LayoutParams(match, wrap))
        }
        root.addView(scroll, linearParams(match, 0, 1f))

        when {
            loading -> renderLoading(body)
            interruption == UiScene.PERMISSION_REQUIRED -> renderPermission(body)
            interruption == UiScene.BLUETOOTH_DISABLED -> renderBluetooth(body)
            else -> when (val loaded = app.repository.load()) {
                is LoadResult.Corrupt -> renderCorrupt(body, loaded.reason)
                is LoadResult.Success -> {
                    if (effectiveTask != null) renderTask(body, effectiveTask)
                    else if (loaded.snapshot.doors.isEmpty()) renderEmpty(body)
                    else renderDoorList(body, loaded.snapshot)
                }
            }
        }
        root.addView(bottomNavigation(), linearParams(match, dp(64)))
        setContentView(root)
    }

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(22), dp(18), dp(14))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("本机 · 离线可用", 10f, CORAL, Typeface.BOLD).apply { letterSpacing = .2f })
            addView(label("我的门禁", 32f, INK, Typeface.BOLD, serif = true).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }, linearParams(0, wrap, 1f))
        addView(label("＋", 25f, Color.WHITE, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            contentDescription = "新增门禁"
            isClickable = true
            isFocusable = true
            minWidth = dp(48); minHeight = dp(48)
            background = rounded(INK, 999f)
            elevation = dp(8).toFloat()
            setOnClickListener { showDoorEditor(null) }
        }, linearParams(dp(48), dp(48)))
    }

    private fun privacyStrip(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Color.TRANSPARENT, 12f, LINE, 1)
        addView(View(this@MainActivity).apply { background = rounded(GREEN, 999f) }, linearParams(dp(8), dp(8)).apply {
            rightMargin = dp(9)
        })
        addView(label("门禁凭据已加密保存在本机", 11f, MUTED), linearParams(0, wrap, 1f))
    }

    private fun renderDoorList(parent: LinearLayout, snapshot: DoorSnapshot) {
        snapshot.doors.sortedBy { it.doorName }.forEach { door ->
            parent.addView(doorCard(door, snapshot.defaultId == door.id), linearParams(match, wrap).apply {
                bottomMargin = dp(12)
            })
        }
    }

    private fun doorCard(door: DoorConfig, isDefault: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(if (isDefault) INK else Color.WHITE, 22f, if (isDefault) INK else SOFT_LINE, 1)
        elevation = dp(if (isDefault) 10 else 4).toFloat()
        val foreground = if (isDefault) Color.WHITE else INK
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(DoorGlyphView(this@MainActivity, foreground), linearParams(dp(42), dp(50)).apply { rightMargin = dp(12) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(door.doorName, 16f, foreground, Typeface.BOLD))
                addView(label(DoorValidation.maskedMac(door.mac), 11f, if (isDefault) WHITE_MUTED else MUTED).apply {
                    typeface = Typeface.MONOSPACE
                    letterSpacing = .06f
                    setPadding(0, dp(5), 0, 0)
                })
            }, linearParams(0, wrap, 1f))
            if (isDefault) addView(label("默认", 10f, Color.WHITE, Typeface.BOLD).apply {
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = rounded(CORAL, 999f)
            })
        })
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(actionButton("开门", if (isDefault) MINT else PAPER_2, INK) {
                requestUnlock(door, UnlockOrigin.MANUAL)
            }, linearParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
            addView(actionButton("•••", Color.TRANSPARENT, foreground) { showDoorMenu(door, isDefault) }.apply {
                contentDescription = "管理 ${door.doorName}"
                background = rounded(Color.TRANSPARENT, 12f, if (isDefault) Color.argb(80,255,255,255) else LINE, 1)
            }, linearParams(dp(48), dp(48)))
        }, linearParams(match, wrap).apply { topMargin = dp(15) })
    }

    private fun renderEmpty(parent: LinearLayout) {
        val box = stateContainer()
        val circle = FrameLayout(this).apply {
            background = rounded(PAPER_2, 999f)
            addView(DoorGlyphView(this@MainActivity, INK), FrameLayout.LayoutParams(dp(48), dp(58), Gravity.CENTER))
        }
        box.addView(circle, linearParams(dp(112), dp(112)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(22) })
        box.addView(stateTitle("还没有本地门禁"))
        box.addView(stateBody("添加第一处门禁后，即使没有网络也能发起开门。"))
        box.addView(actionButton("新增门禁", CORAL, Color.WHITE) { showDoorEditor(null) }, linearParams(match, dp(50)))
        parent.addView(box, linearParams(match, wrap))
    }

    private fun renderLoading(parent: LinearLayout) {
        parent.addView(stateContainer().apply {
            addView(ProgressBar(this@MainActivity).apply {
                indeterminateTintList = android.content.res.ColorStateList.valueOf(CORAL)
            }, linearParams(dp(54), dp(54)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(24) })
            addView(stateTitle("正在读取本机门禁"))
            addView(stateBody("解密与检查默认门禁关系，不会发起网络请求。"))
        }, linearParams(match, wrap))
    }

    private fun renderTask(parent: LinearLayout, task: UnlockTask) {
        when (UiStateMapper.sceneFor(task)) {
            UiScene.PROGRESS -> renderProgress(parent, task)
            UiScene.SENT -> renderResult(parent, "✓", "GATT WRITE · SUCCESS", "开门指令已发送",
                "传输已完成，请确认门禁的实际状态。", false, "返回门禁", { dismissTask(task) }, "再次发送", { retry(task) })
            UiScene.TIMED_OUT -> renderResult(parent, "⌛", "任务已安全停止", "连接超时",
                "设备在限定时间内没有响应，任务已停止且不会自动重试。", true, "重试", { retry(task) }, "返回列表", { dismissTask(task) })
            UiScene.PROTOCOL_FAILED -> renderResult(parent, "!", "任务已安全停止", "门禁协议数据异常",
                "${task.detail}。没有发送空帧或部分指令。", true, "重试", { retry(task) }, "检查配置", { showDoorEditor(task.door) })
            UiScene.CANCELLED -> renderResult(parent, "×", "任务已安全停止", "已取消开门",
                "任务资源已清理，迟到的设备回调不会改变此结果。", true, "返回列表", { dismissTask(task) }, "重新发起", { retry(task) })
            else -> renderResult(parent, "!", "任务已安全停止", "无法连接门禁设备",
                "${task.detail}。请靠近门禁，并检查保存的 MAC 地址后重试。", true, "重试", { retry(task) }, "检查配置", { showDoorEditor(task.door) })
        }
    }

    private fun renderProgress(parent: LinearLayout, task: UnlockTask) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), dp(4), dp(2), dp(8)) }
        box.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(this@MainActivity).apply {
                background = rounded(MINT, 999f)
                addView(DoorGlyphView(this@MainActivity, INK), FrameLayout.LayoutParams(dp(30), dp(38), Gravity.CENTER))
            }, linearParams(dp(62), dp(62)).apply { rightMargin = dp(14) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(if (task.origin == UnlockOrigin.COLD_START) "冷启动 · 自动一次" else "手动开门", 10f, CORAL, Typeface.BOLD).apply { letterSpacing = .16f })
                addView(label(task.door.doorName, 17f, INK, Typeface.BOLD).apply { setPadding(0, dp(4), 0, 0) })
                addView(label(DoorValidation.maskedMac(task.door.mac), 10f, MUTED).apply { typeface = Typeface.MONOSPACE; setPadding(0, dp(3), 0, 0) })
            }, linearParams(0, wrap, 1f))
        }, linearParams(match, wrap).apply { bottomMargin = dp(18) })

        val active = UiStateMapper.progressStep(task.stage)
        val labels = listOf("连接设备" to "正在连接", "读取 seed" to "正在读取", "发送指令" to "正在发送", "确认写入" to "等待写入回调")
        labels.forEachIndexed { index, (title, running) ->
            box.addView(progressRow(index, title, when {
                index < active -> "完成"
                index == active -> if (index == 0) task.detail else running
                else -> "等待"
            }, index < active, index == active), linearParams(match, dp(54)))
        }
        box.addView(label("正在执行的门禁任务全局唯一，并使用启动时的配置快照。回到前台或连续点击不会创建第二个任务。", 11f, Color.rgb(114, 92, 45)).apply {
            setPadding(dp(12), dp(11), dp(12), dp(11)); background = rounded(Color.rgb(255, 248, 231), 12f)
        }, linearParams(match, wrap).apply { topMargin = dp(12); bottomMargin = dp(12) })
        box.addView(actionButton("取消开门", Color.rgb(255, 232, 225), ERROR) { app.coordinator.cancel() }, linearParams(match, dp(50)))
        parent.addView(box, linearParams(match, wrap))
    }

    private fun progressRow(index: Int, title: String, status: String, done: Boolean, active: Boolean): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(Color.TRANSPARENT, 0f, LINE, 0, bottomBorderOnly = true)
        addView(label(if (done) "✓" else "${index + 1}", 10f, if (done || active) Color.WHITE else MUTED, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            background = rounded(if (active) CORAL else if (done) INK else Color.TRANSPARENT, 999f, if (done || active) Color.TRANSPARENT else LINE, 1)
        }, linearParams(dp(24), dp(24)).apply { rightMargin = dp(11) })
        addView(label(title, 13f, INK, Typeface.BOLD), linearParams(0, wrap, 1f))
        addView(label(status, 10f, MUTED))
    }

    private fun renderResult(
        parent: LinearLayout,
        symbol: String,
        eyebrow: String,
        title: String,
        body: String,
        error: Boolean,
        primary: String,
        primaryAction: () -> Unit,
        secondary: String,
        secondaryAction: () -> Unit,
    ) {
        parent.addView(stateContainer().apply {
            addView(label(symbol, 40f, if (error) ERROR else INK, Typeface.NORMAL).apply {
                gravity = Gravity.CENTER
                background = rounded(if (error) Color.rgb(255, 230, 223) else MINT, 999f)
            }, linearParams(dp(94), dp(94)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(22) })
            addView(label(eyebrow, 10f, CORAL, Typeface.BOLD).apply { gravity = Gravity.CENTER; letterSpacing = .16f })
            addView(stateTitle(title).apply { setPadding(0, dp(9), 0, 0) })
            addView(stateBody(body))
            addView(actionButton(primary, CORAL, Color.WHITE, primaryAction), linearParams(match, dp(50)))
            addView(actionButton(secondary, PAPER_2, INK, secondaryAction), linearParams(match, dp(50)).apply { topMargin = dp(8) })
        }, linearParams(match, wrap))
    }

    private fun renderPermission(parent: LinearLayout) = renderResult(parent, "◇", "任务等待继续", "需要“附近设备”权限",
        "Android 12 及以上需要此权限连接已保存的门禁。拒绝后不会循环询问。", false,
        "去授权", {
            if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CONNECT)
            else openAppSettings()
        }, "暂不授权", { app.unlockFlow.cancelPending(); interruption = null; render(app.coordinator.task) })

    private fun renderBluetooth(parent: LinearLayout) = renderResult(parent, "ᛒ", "任务等待继续", "蓝牙已关闭",
        "开启蓝牙后可继续当前任务，不会因返回页面再次自动开门。", false,
        "开启蓝牙", { ensureBluetoothThenContinue() }, "取消任务", { app.unlockFlow.cancelPending(); interruption = null; render(app.coordinator.task) })

    private fun renderCorrupt(parent: LinearLayout, reason: String) = renderResult(parent, "⚠", "本机数据保护", "门禁数据无法读取",
        "$reason。原始加密数据会被保留；确认重置前，不会静默覆盖或删除。", true,
        "安全重置", { showResetConfirmation() }, "稍后处理", { toast("未修改本机加密数据") })

    private fun bottomNavigation(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(7), dp(18), dp(7))
        background = rounded(Color.TRANSPARENT, 0f, LINE, 1)
        addView(navItem("▥", "门禁", true) { interruption = null; dismissedTaskId = app.coordinator.task?.taskId; render(app.coordinator.task) }, linearParams(0, match, 1f))
        addView(navItem("?", "说明", false) { showHelpSheet() }, linearParams(0, match, 1f))
    }

    private fun navItem(icon: String, text: String, active: Boolean, click: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
        contentDescription = text
        addView(label(icon, 18f, if (active) INK else MUTED, if (active) Typeface.BOLD else Typeface.NORMAL))
        addView(label(text, 10f, if (active) INK else MUTED, if (active) Typeface.BOLD else Typeface.NORMAL))
        setOnClickListener { click() }
    }

    private fun showDoorEditor(existing: DoorConfig?) = showSheet { dialog, sheet ->
        sheet.addView(sheetTitle(if (existing == null) "新增门禁" else "编辑门禁"))
        sheet.addView(sheetHelper("凭据仅用于本机直连，保存时会规范化并加密。密钥不会直接显示或复制。"))
        val name = sheetField(sheet, "门禁名称", "例如：北门 · 云庭", existing?.doorName.orEmpty())
        val mac = sheetField(sheet, "蓝牙 MAC 地址", "A4:C1:38:2F:7B:10", existing?.mac.orEmpty())
        val key = sheetField(sheet, "门禁密钥 · 16 位 HEX", "••••••••••••••••", existing?.key.orEmpty(), password = true)
        sheet.addView(sheetActions("取消", { dialog.dismiss() }, "保存") {
            DoorValidation.validate(name.text.toString(), mac.text.toString(), key.text.toString())
                .onSuccess { value ->
                    val door = DoorConfig(existing?.id ?: UUID.randomUUID().toString(), value.doorName, value.mac, value.key)
                    app.repository.upsert(door).onSuccess {
                        dialog.dismiss(); render(app.coordinator.task)
                        toast(if (existing == null) "门禁已加密保存到本机" else "门禁资料已更新")
                    }.onFailure { mac.error = it.message ?: "保存失败" }
                }.onFailure { error ->
                    val message = error.message ?: "输入无效"
                    when {
                        message.contains("名称") -> name.error = message
                        message.contains("MAC", true) || message.contains("地址") -> mac.error = message
                        else -> key.error = message
                    }
                }
        })
    }

    private fun showDoorMenu(door: DoorConfig, isDefault: Boolean) = showSheet { dialog, sheet ->
        sheet.addView(sheetTitle("管理门禁"))
        sheet.addView(sheetHelper("${door.doorName}\n${DoorValidation.maskedMac(door.mac)}"))
        sheet.addView(menuButton("编辑资料") { dialog.dismiss(); showDoorEditor(door) })
        sheet.addView(menuButton(if (isDefault) "取消默认门禁" else "设为默认门禁") {
            app.repository.setDefault(if (isDefault) null else door.id).onSuccess {
                dialog.dismiss(); render(app.coordinator.task); toast(if (isDefault) "已取消默认门禁" else "已设置默认门禁")
            }.onFailure { toast(it.message ?: "操作失败") }
        })
        sheet.addView(menuButton("删除门禁", destructive = true) { dialog.dismiss(); showDeleteConfirmation(door, isDefault) })
        sheet.addView(menuButton("取消") { dialog.dismiss() })
    }

    private fun showDeleteConfirmation(door: DoorConfig, isDefault: Boolean) = showSheet { dialog, sheet ->
        sheet.addView(sheetTitle("删除“${door.doorName}”？"))
        sheet.addView(sheetHelper(if (isDefault) "这也是当前默认门禁。删除后会同时取消默认，且不会自动选择其他门禁。" else "删除后，本机将无法再使用这项配置开门。"))
        sheet.addView(sheetActions("取消", { dialog.dismiss() }, "确认删除", destructive = true) {
            app.repository.delete(door.id).onSuccess { dialog.dismiss(); render(app.coordinator.task); toast("门禁已从本机删除") }
                .onFailure { toast("删除失败") }
        })
    }

    private fun showResetConfirmation() = showSheet { dialog, sheet ->
        sheet.addView(sheetTitle("确认安全重置？"))
        sheet.addView(sheetHelper("无法读取的加密数据将被删除，且不能由本应用恢复。此操作不会自动创建任何门禁。"))
        sheet.addView(sheetActions("取消", { dialog.dismiss() }, "确认重置", destructive = true) {
            app.repository.reset().onSuccess { dialog.dismiss(); interruption = null; render(app.coordinator.task) }
                .onFailure { toast("重置失败") }
        })
    }

    private fun showHelpSheet() = showSheet { dialog, sheet ->
        sheet.addView(sheetTitle("离线门禁说明"))
        sheet.addView(sheetHelper("“指令已发送”只代表 GATT 写入完成，不代表物理门已经打开。\n\n自动开门仅在进程级 Launcher 冷启动时尝试一次；回到前台、旋转屏幕及系统设置返回均不会重复触发。\n\n门禁名称、MAC 与密钥只加密保存在本机，应用不申请网络、扫描或定位权限。"))
        sheet.addView(actionButton("我知道了", CORAL, Color.WHITE) { dialog.dismiss() }, linearParams(match, dp(50)))
    }

    private fun showSheet(build: (Dialog, LinearLayout) -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(24))
            background = rounded(PAPER, 26f)
            addView(View(this@MainActivity).apply { background = rounded(LINE, 999f) }, linearParams(dp(42), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(18)
            })
        }
        build(dialog, sheet)
        dialog.setContentView(ScrollView(this).apply { isFillViewport = true; addView(sheet) })
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(.55f)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.BOTTOM)
            setLayout(match, wrap)
            attributes = attributes.apply { width = match; height = wrap; gravity = Gravity.BOTTOM }
        }
    }

    private fun sheetTitle(text: String) = label(text, 25f, INK, Typeface.BOLD, serif = true).apply { setPadding(0, 0, 0, dp(6)) }
    private fun sheetHelper(text: String) = label(text, 12f, MUTED).apply { setLineSpacing(0f, 1.5f); setPadding(0, 0, 0, dp(18)) }

    private fun sheetField(parent: LinearLayout, title: String, hint: String, value: String, password: Boolean = false): EditText {
        parent.addView(label(title, 12f, INK, Typeface.BOLD), linearParams(match, wrap).apply { bottomMargin = dp(6) })
        val field = EditText(this).apply {
            setText(value); this.hint = hint; textSize = 14f; setTextColor(INK); setHintTextColor(Color.rgb(150, 155, 152))
            setPadding(dp(13), dp(10), dp(13), dp(10)); background = rounded(Color.WHITE, 11f, LINE, 1)
            minHeight = dp(50); setSingleLine(true)
            if (password) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextIsSelectable(false)
            }
        }
        parent.addView(field, linearParams(match, dp(52)).apply { bottomMargin = dp(14) })
        return field
    }

    private fun sheetActions(left: String, leftAction: () -> Unit, right: String, destructive: Boolean = false, rightAction: () -> Unit): View =
        LinearLayout(this).apply {
            addView(actionButton(left, PAPER_2, INK, leftAction), linearParams(0, dp(50), 1f).apply { rightMargin = dp(8) })
            addView(actionButton(right, if (destructive) Color.rgb(255,232,225) else CORAL, if (destructive) ERROR else Color.WHITE, rightAction), linearParams(0, dp(50), 1f))
        }

    private fun menuButton(text: String, destructive: Boolean = false, action: () -> Unit) = actionButton(text, Color.WHITE, if (destructive) ERROR else INK, action).apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        layoutParams = linearParams(match, dp(52)).apply { bottomMargin = dp(7) }
    }

    private fun requestUnlock(door: DoorConfig, origin: UnlockOrigin) {
        val active = app.coordinator.task?.takeUnless { it.stage.terminal }
        if (active != null || app.unlockFlow.pending != null) {
            toast("${active?.door?.doorName ?: app.unlockFlow.pending?.door?.doorName}正在进行开门操作")
            return
        }
        dismissedTaskId = null
        handleFlow(app.unlockFlow.request(door, origin, precondition()))
    }

    private fun retry(task: UnlockTask) {
        dismissedTaskId = null
        handleFlow(app.unlockFlow.retry(task.door.id, precondition()))
    }

    private fun dismissTask(task: UnlockTask) {
        dismissedTaskId = task.taskId
        interruption = null
        render(app.coordinator.task)
    }

    @SuppressLint("MissingPermission")
    private fun ensureBluetoothThenContinue() {
        if (bluetoothEnabled() && app.unlockFlow.pending != null) continuePendingUnlock()
        else try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE)
        } catch (_: SecurityException) {
            app.unlockFlow.cancelPending(); interruption = UiScene.PERMISSION_REQUIRED; render(app.coordinator.task)
        }
    }

    private fun continuePendingUnlock() = handleFlow(app.unlockFlow.resume(precondition()))

    private fun handleFlow(action: UnlockFlowAction) {
        when (action) {
            UnlockFlowAction.NoAction -> Unit
            is UnlockFlowAction.Started -> { interruption = null; dismissedTaskId = null; render(app.coordinator.task) }
            UnlockFlowAction.NeedPermission -> {
                interruption = UiScene.PERMISSION_REQUIRED; render(app.coordinator.task)
                if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_CONNECT)
            }
            UnlockFlowAction.NeedBluetooth -> {
                interruption = UiScene.BLUETOOTH_DISABLED; render(app.coordinator.task); ensureBluetoothThenContinue()
            }
            UnlockFlowAction.Unsupported -> renderStandaloneError("此设备不支持低功耗蓝牙")
            UnlockFlowAction.MissingDoor -> { dismissedTaskId = app.coordinator.task?.taskId; toast("门禁已删除，请返回列表"); render(app.coordinator.task) }
            is UnlockFlowAction.Rejected -> toast(action.message)
        }
    }

    private fun renderStandaloneError(message: String) {
        interruption = null
        toast(message)
        render(app.coordinator.task)
    }

    private fun precondition(): UnlockPrecondition = when {
        !packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> UnlockPrecondition.UNSUPPORTED
        !hasConnectPermission() -> UnlockPrecondition.PERMISSION_REQUIRED
        !bluetoothEnabled() -> UnlockPrecondition.BLUETOOTH_REQUIRED
        else -> UnlockPrecondition.READY
    }

    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun bluetoothEnabled(): Boolean = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: SecurityException) { false }

    private fun openAppSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun stateContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(38), dp(24), dp(38)); minimumHeight = dp(430)
    }

    private fun stateTitle(text: String) = label(text, 23f, INK, Typeface.BOLD, serif = true).apply {
        gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(9))
    }

    private fun stateBody(text: String) = label(text, 13f, MUTED).apply {
        gravity = Gravity.CENTER; setLineSpacing(0f, 1.5f); setPadding(0, 0, 0, dp(21))
    }

    private fun label(text: String, size: Float, color: Int, style: Int = Typeface.NORMAL, serif: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (serif) Typeface.SERIF else Typeface.DEFAULT, style)
        includeFontPadding = false
    }

    private fun actionButton(text: String, fill: Int, color: Int, action: () -> Unit) = label(text, 13f, color, Typeface.BOLD).apply {
        gravity = Gravity.CENTER; isClickable = true; isFocusable = true; minHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10)); background = rounded(fill, 12f)
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeWidth: Int = 0, bottomBorderOnly: Boolean = false): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; setColor(fill)
            cornerRadius = if (bottomBorderOnly) 0f else dp(radius).toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
        }

    private fun linearParams(width: Int, height: Int, weight: Float = 0f) = LinearLayout.LayoutParams(width, height, weight)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = value * resources.displayMetrics.density

    private class DoorGlyphView(context: android.content.Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 2f
        }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = paint.strokeWidth
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, dp(8f), dp(8f), paint)
            canvas.drawCircle(width * .76f, height * .55f, dp(2.2f), knob)
        }
        private fun dp(value: Float) = value * resources.displayMetrics.density
    }

    companion object {
        private const val REQUEST_CONNECT = 41
        private const val REQUEST_ENABLE = 42
        private const val match = ViewGroup.LayoutParams.MATCH_PARENT
        private const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val PAPER = 0xFFF4F1E9.toInt()
        private const val PAPER_2 = 0xFFEBE6DA.toInt()
        private const val INK = 0xFF142D27.toInt()
        private const val CORAL = 0xFFEE6D4A.toInt()
        private const val MINT = 0xFFB8D9C9.toInt()
        private const val MUTED = 0xFF6E7974.toInt()
        private const val GREEN = 0xFF4E9A75.toInt()
        private const val ERROR = 0xFFA13D24.toInt()
        private const val LINE = 0x26142D27
        private const val SOFT_LINE = 0x14142D27
        private const val WHITE_MUTED = 0x8CFFFFFF.toInt()
    }
}
