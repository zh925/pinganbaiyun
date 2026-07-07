package com.pinganbaiyun.app.ui.unlock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.pinganbaiyun.app.core.ble.BleUnlockManager
import com.pinganbaiyun.app.core.ble.UnlockState
import com.pinganbaiyun.app.data.model.DoorConfig

/**
 * 开门流程 ViewModel——持有 [BleUnlockManager]，把引擎状态转发给 UI。
 * 状态回调已在主线程，UI 只需订阅 [onState]。
 */
class UnlockViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = BleUnlockManager(app)

    /** 最近一次状态，供订阅注册时立即回放。 */
    var lastState: UnlockState = UnlockState.Idle
        private set

    var onState: ((UnlockState) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(lastState)
        }

    init {
        manager.listener = com.pinganbaiyun.app.core.ble.UnlockStateListener { state ->
            lastState = state
            onState?.invoke(state)
        }
    }

    fun start(config: DoorConfig) {
        manager.start(config)
    }

    fun stop() {
        manager.stop()
    }

    override fun onCleared() {
        super.onCleared()
        manager.listener = null
        manager.stop()
    }
}
