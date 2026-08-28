package halo.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A fake [HaloBleTransport] for unit tests. Captures whatever is "sent" and
 * lets the test inject [HaloMessage] notifications.
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

    private val _messages = MutableSharedFlow<HaloMessage>(extraBufferCapacity = 64)
    override val messages: Flow<HaloMessage> = _messages.asSharedFlow()

    fun emitMessage(code: Int, payload: ByteArray) {
        _messages.tryEmit(HaloMessage(code, payload))
    }

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
