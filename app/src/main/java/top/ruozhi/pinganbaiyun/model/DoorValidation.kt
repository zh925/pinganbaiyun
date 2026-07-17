package top.ruozhi.pinganbaiyun.model

object DoorValidation {
    private val macPattern = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    private val keyPattern = Regex("^[0-9A-F]{16}$")

    data class Validated(val doorName: String, val mac: String, val key: String)

    fun validate(doorName: String, mac: String, key: String): Result<Validated> {
        val normalizedName = doorName.trim()
        val normalizedMac = mac.trim().uppercase().replace('-', ':')
        val normalizedKey = key.trim().uppercase()
        return when {
            normalizedName.isEmpty() -> Result.failure(IllegalArgumentException("门禁名称不能为空"))
            normalizedName.length > 80 -> Result.failure(IllegalArgumentException("门禁名称最多 80 个字符"))
            !macPattern.matches(normalizedMac) -> Result.failure(IllegalArgumentException("MAC 格式应为 XX:XX:XX:XX:XX:XX"))
            !keyPattern.matches(normalizedKey) -> Result.failure(IllegalArgumentException("密钥必须是恰好 16 位十六进制字符"))
            else -> Result.success(Validated(normalizedName, normalizedMac, normalizedKey))
        }
    }

    fun maskedMac(mac: String): String = if (mac.length == 17) "**:**:**:${mac.takeLast(8)}" else "**:**:**:**:**:**"
}
