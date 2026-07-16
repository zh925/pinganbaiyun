package com.pinganbaiyun.app.ui.devices

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.pinganbaiyun.app.core.storage.DoorConfigStore
import com.pinganbaiyun.app.data.model.DoorConfig

/** 蓝牙信息维护页数据源——薄封装本地存储，UI 只读列表 / 删除。 */
class DevicesViewModel(app: Application) : AndroidViewModel(app) {

    private val store = DoorConfigStore.get(app)

    fun list(): List<DoorConfig> = store.list()

    fun get(id: String): DoorConfig? = store.get(id)

    fun defaultId(): String? = store.defaultId()

    fun setDefault(id: String?) = store.setDefault(id)

    fun shouldShowDefaultGuide(): Boolean =
        !getApplication<Application>().getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEFAULT_GUIDE_SHOWN, false)

    fun markDefaultGuideShown() {
        getApplication<Application>().getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEFAULT_GUIDE_SHOWN, true).apply()
    }

    fun delete(id: String) = store.delete(id)

    companion object {
        private const val UI_PREFS = "devices_ui"
        private const val KEY_DEFAULT_GUIDE_SHOWN = "default_guide_shown"
    }
}
