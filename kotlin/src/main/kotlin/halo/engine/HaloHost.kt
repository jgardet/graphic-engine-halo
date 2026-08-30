package halo.engine

import kotlinx.serialization.json.JsonElement

/** High-level host runtime using official message framing for binary rendering. */
class HaloHost(
    private val transport: HaloBleTransport,
    private val hrpCompiler: HsdHrpCompiler,
    private val limits: HaloLimits = StockHaloLimits,
) {
    suspend fun showScene(scene: JsonElement, code: Int = HaloProtocol.HRP) {
        showHrp(hrpCompiler.compile(scene), code)
    }

    suspend fun showHrp(payload: ByteArray, code: Int = HaloProtocol.HRP) {
        validateHrpMessage(payload, limits)
        transport.sendMessage(code, payload)
    }

    /** Lua is a single REPL command, not a stream of independently executable chunks. */
    suspend fun sendLua(lua: String) {
        val size = lua.toByteArray(Charsets.UTF_8).size
        require(size <= transport.maxLuaPayload && size <= limits.maxLuaSourceBytes) {
            "Lua command is $size bytes; use HRP/data-channel mode instead"
        }
        transport.sendLua(lua)
    }

    /** Send an already packed asset through the transport's data channel. */
    suspend fun sendSprite(sprite: SpritePacker.Sprite, resourceId: Int = 1) {
        val packed = packSpriteAsset(sprite)
        require(packed.size <= limits.maxAssetBytes) { "Sprite exceeds conservative stock asset budget" }
        showHrp(HrpBuilder(limits.maxHrpMessageBytes).spriteDefine(resourceId, packed).endFrame().build())
    }

    companion object {
        fun packSpriteAsset(sprite: SpritePacker.Sprite): ByteArray {
            val bits = packIndexedPixels(sprite.pixelData, sprite.bpp)
            val header = java.nio.ByteBuffer.allocate(7)
                .putShort(sprite.width.toShort())
                .putShort(sprite.height.toShort())
                .put(0)
                .put(sprite.bpp.toByte())
                .put(sprite.numColors.toByte())
                .array()
            return header + sprite.paletteData + bits
        }
    }
}
