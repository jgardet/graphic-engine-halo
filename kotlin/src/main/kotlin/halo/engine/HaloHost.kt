package halo.engine

import kotlinx.serialization.json.JsonElement

/** High-level host runtime using official message framing for binary rendering. */
class HaloHost(
    private val transport: HaloBleTransport,
    private val compiler: HaloCompiler = HaloCompiler(),
    private val limits: HaloLimits = StockHaloLimits,
) {
    suspend fun show(scene: JsonElement) {
        val lua = compiler.compile(scene)
        sendLua(lua)
    }

    suspend fun showHrp(payload: ByteArray, code: Int = 0x60) {
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
    suspend fun sendSprite(sprite: SpritePacker.Sprite) {
        val packed = packSpriteAsset(sprite)
        require(packed.size <= limits.maxAssetBytes) { "Sprite exceeds conservative stock asset budget" }
        transport.sendMessage(0x20, packed)
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

        fun packIndexedPixels(indices: ByteArray, bpp: Int): ByteArray {
            require(bpp == 1 || bpp == 2 || bpp == 4)
            val out = java.io.ByteArrayOutputStream()
            val perByte = 8 / bpp
            for (start in indices.indices step perByte) {
                var value = 0
                for (j in 0 until perByte) {
                    if (start + j < indices.size) {
                        value = value or ((indices[start + j].toInt() and ((1 shl bpp) - 1)) shl ((perByte - j - 1) * bpp))
                    }
                }
                out.write(value)
            }
            return out.toByteArray()
        }
    }
}
