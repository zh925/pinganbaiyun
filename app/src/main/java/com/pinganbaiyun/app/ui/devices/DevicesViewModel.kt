package com.pinganbaiyun.app.ui.devices

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.pinganbaiyun.app.core.storage.DoorConfigStore
import com.pinganbaiyun.app.data.model.DoorConfig

/** 蓝牙信息维护页数据源——薄封装本地存储，UI 只读列表 / 删除。 */
class DevicesViewModel(app: Application) : AndroidViewModel(app) {

    private val store = DoorConfigStore.get(app)

    fun list(): List<DoorConfig> = store.list()

    fun delete(id: String) = store.delete(id)
}
