package com.pinganbaiyun.app.core.protocol

/**
 * 开门回执解析结果。
 *
 * code 取自解密后的状态字节：`00`=开门成功、`02`=门已打开、其它=失败（密码无效），
 * 另有 `FF`=帧长度不足 / 解密失败。
 */
data class OpenResult(
    val code: String,
    val message: String,
) {
    val isSuccess: Boolean get() = code == "00" || code == "02"

    companion object {
        const val CODE_SUCCESS = "00"
        const val CODE_ALREADY_OPEN = "02"
        const val CODE_INVALID = "FF"
    }
}
