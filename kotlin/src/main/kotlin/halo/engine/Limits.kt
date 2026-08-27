package halo.engine

/** Conservative safety budget; values are configurable per measured firmware profile. */
data class HaloLimits(
    val displayWidth: Int = 256,
    val displayHeight: Int = 256,
    val maxMessageBytes: Int = 65535,
    val maxLuaSourceBytes: Int = 4096,
    val maxHrpMessageBytes: Int = 32768,
    val maxAssetBytes: Int = 24576,
    val maxRetainedAssetBytes: Int = 49152,
    val maxPeakWorkingSetBytes: Int = 65536,
    val maxPolygonPoints: Int = 64,
)

val StockHaloLimits = HaloLimits()

fun validateLuaSource(lua: String, limits: HaloLimits = StockHaloLimits) {
    val size = lua.toByteArray(Charsets.UTF_8).size
    require(size <= limits.maxLuaSourceBytes) {
        "Lua source is $size bytes; use HRP/data-channel mode"
    }
}

fun validateHrpMessage(payload: ByteArray, limits: HaloLimits = StockHaloLimits) {
    require(payload.size <= limits.maxMessageBytes)
    require(payload.size <= limits.maxHrpMessageBytes)
}
