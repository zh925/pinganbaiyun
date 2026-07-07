package com.pinganbaiyun.app.core.ble

import java.util.UUID

/**
 * BLE 门禁相关常量，取自参考仓库 `pages/index/index.js` 顶部定义。
 */
object BleConstants {

    /** Service UUID 候选表（命中其一即为目标门禁服务），扫描按此过滤。 */
    val SERVICE_CANDIDATES: List<UUID> = listOf(
        UUID.fromString("0734594a-a8e7-4b1a-a6b1-cd5243059a57"),
        UUID.fromString("14839ac4-7d7e-415c-9a42-167340cf2339"),
    )

    /** 客户端特征配置描述符（开启 notify/indicate 用）。 */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // ---- 超时（毫秒），与协议规格一致 ----
    const val DISCOVERY_TIMEOUT_MS = 10_000L   // 扫描
    const val CONNECT_TIMEOUT_MS = 8_000L      // 连接单步
    const val READ_SEED_TIMEOUT_MS = 5_000L    // 读随机数种子
    const val USER_ACK_TIMEOUT_MS = 3_500L     // 门锁 ACK
    const val FLOW_TIMEOUT_MS = 45_000L        // 全流程
}
