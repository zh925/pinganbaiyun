package com.pinganbaiyun.app.core.ble

import com.pinganbaiyun.app.core.protocol.OpenResult

/**
 * 开门流程状态机的对外状态。UI 层订阅 [BleUnlockManager.listener] 即可驱动界面（对应原型 C-01..C-05）。
 */
sealed class UnlockState {
    /** 空闲 / 未开始。 */
    data object Idle : UnlockState()

    /** 正在按 Service UUID 过滤扫描目标门禁。 */
    data object Scanning : UnlockState()

    /** 已发现设备，正在建立 GATT 连接。 */
    data class Connecting(val deviceLabel: String) : UnlockState()

    /** 已连接，正在发现服务 / 选择特征通道。 */
    data object PreparingChannel : UnlockState()

    /** 正在读取随机数种子。 */
    data object ReadingSeed : UnlockState()

    /** 已下发握手 / 开门指令，等待门锁回执。 */
    data object Opening : UnlockState()

    /** 终态：成功（含回执 00/02）。 */
    data class Success(val result: OpenResult) : UnlockState()

    /** 终态：失败。 */
    data class Failed(val reason: String, val error: UnlockError) : UnlockState()
}

enum class UnlockError {
    PERMISSION_MISSING,
    BLUETOOTH_DISABLED,
    SCAN_TIMEOUT,
    CONNECT_FAILED,
    SERVICE_NOT_FOUND,
    CHARACTERISTIC_NOT_FOUND,
    READ_SEED_TIMEOUT,
    ACK_TIMEOUT,
    FLOW_TIMEOUT,
    PROTOCOL_ERROR,
    UNKNOWN,
}

/** 状态回调接口。 */
fun interface UnlockStateListener {
    fun onStateChanged(state: UnlockState)
}
