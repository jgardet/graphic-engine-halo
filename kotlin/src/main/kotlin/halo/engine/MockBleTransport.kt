package halo.engine

/**
 * A fake [HaloBleTransport] for unit tests. Captures whatever is "sent".
 */
class MockBleTransport(
    override val maxLuaPayload: Int = 182,
    override val maxDataPayload: Int = 512,
    override val supportsAudio: Boolean = false,
) : HaloBleTransport {
    val luaChunks = mutableListOf<String>()
    val dataChunks = mutableListOf<ByteArray>()
    val audioChunks = mutableListOf<ByteArray>()
    var connected = false

    override suspend fun connect(name: String?) {
        connected = true
    }

    override suspend fun disconnect() {
        connected = false
    }

    override suspend fun sendLua(lua: String) {
        luaChunks.add(lua)
    }

    override suspend fun sendMessage(code: Int, payload: ByteArray) {
        require(code in 0..255)
        require(payload.size <= 65535)
        dataChunks.add(byteArrayOf(code.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload)
    }

    override suspend fun sendData(bytes: ByteArray) {
        dataChunks.add(bytes)
    }

    override suspend fun sendAudioFrame(frame: ByteArray) {
        audioChunks.add(frame)
    }
}
