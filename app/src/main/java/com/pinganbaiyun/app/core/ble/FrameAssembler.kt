package com.pinganbaiyun.app.core.ble

/**
 * BLE notify 帧组包的**纯函数**（仿 [DoorMatcher] 抽离，不依赖 Android，便于单测）。
 *
 * 帧格式 `A5 <len> … <cs> 5A`，**len = 总字节数（含 A5 头与 5A 尾）**——三处（本对象、参考小程序
 * `index.js` handleValueChange、`BleUnlockManager` 真机调试）语义一致。
 *
 * 默认 MTU 下长帧会被拆成多个 notify 分包，本对象按帧边界从累积缓冲里提取完整帧，
 * 不完整则把剩余串留待下一包补齐。语义与 `BleUnlockManager.extractFrames` 完全一致，
 * 只是把缓冲区显式作参数传入、不持有实例状态，从而可在 JUnit 下直接断言。
 */
object FrameAssembler {

    /** 1 字节 = 2 个 hex 字符。 */
    const val HEX_PER_BYTE = 2

    /** 帧头。 */
    const val HEAD_A5 = "A5"

    /** len 下限：A5 + len + cs + 5A = 4 字节为最小合法帧。 */
    const val MIN_FRAME_BYTES = 4

    /** len 上限：门禁回执不会超过此长度，超出判为不可信。 */
    const val MAX_FRAME_BYTES = 64

    /**
     * 从 [buffer] 提取所有完整帧。**不修改入参**；返回提取出的帧 hex 列表与剩余（未组包完）的缓冲串。
     * 调用方负责用 [ExtractResult.remaining] 替换自己的累积缓冲（跨分包续接）。
     */
    fun extract(buffer: String): ExtractResult {
        val sb = StringBuilder(buffer)
        val frames = mutableListOf<String>()
        while (sb.length >= HEX_PER_BYTE * 2) {
            // 跳过到首个 A5
            val a5 = sb.indexOf(HEAD_A5)
            if (a5 < 0) {
                sb.setLength(0)
                break
            }
            if (a5 > 0) sb.delete(0, a5)
            // 至少要够读出 len 字节（A5 + len = 2 字节 = 4 hex）
            if (sb.length < HEX_PER_BYTE * 2) break
            val len = sb.substring(HEX_PER_BYTE, HEX_PER_BYTE * 2).toIntOrNull(16) ?: 0
            if (len < MIN_FRAME_BYTES || len > MAX_FRAME_BYTES) {
                // len 不可信，丢弃这个 A5 继续找下一个
                sb.delete(0, HEX_PER_BYTE)
                continue
            }
            val frameHexLen = len * HEX_PER_BYTE
            if (sb.length < frameHexLen) break // 等待后续分包
            val frame = sb.substring(0, frameHexLen)
            sb.delete(0, frameHexLen)
            frames.add(frame)
        }
        return ExtractResult(frames, sb.toString())
    }

    /** [extract] 的结果：已提取的完整帧列表 + 缓冲中尚未组包完的剩余 hex。 */
    data class ExtractResult(val frames: List<String>, val remaining: String)
}
