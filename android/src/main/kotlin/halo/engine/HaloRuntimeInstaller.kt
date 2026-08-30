package halo.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class HaloRuntimeInstaller(
    private val transport: AndroidBleTransport,
    private val runtimeFileName: String = "halo_engine.lua",
) {
    suspend fun installAndStart(source: String, timeoutMs: Long = 10_000): String = coroutineScope {
        transport.sendControl(HaloProtocol.LUA_CTRL_INTERRUPT.toByte())
        delay(200)
        upload(source)
        val ready = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(timeoutMs) {
                transport.notifications.filterIsInstance<HaloNotification.Message>()
                    .first { it.code == HaloProtocol.STATUS }
                    .payload.toString(Charsets.UTF_8)
            }
        }
        val module = runtimeFileName.removeSuffix(".lua")
        transport.sendLua("package.loaded['$module']=nil require('$module')")
        ready.await()
    }

    private suspend fun upload(source: String) {
        val escaped = source.replace("\r", "")
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
            .replace("\"", "\\\"")
        val ack = "frame.bluetooth.send(string.char(${HaloProtocol.STATUS}) .. 'ok')"
        transport.sendLuaAwaitStatus("f=frame.file.open('$runtimeFileName','w');$ack", expectedPayload = "ok")
        val overhead = "f:write(\"\");$ack".toByteArray(Charsets.UTF_8).size
        val chunkSize = transport.maxLuaPayload - overhead
        require(chunkSize > 0) { "Negotiated MTU is too small for runtime upload" }
        utf8Chunks(escaped, chunkSize).forEach { chunk ->
            currentCoroutineContext().ensureActive()
            transport.sendLuaAwaitStatus("f:write(\"$chunk\");$ack", expectedPayload = "ok")
        }
        transport.sendLuaAwaitStatus("f:close();$ack", expectedPayload = "ok")
    }

    private fun utf8Chunks(value: String, maxBytes: Int): List<String> {
        val chunks = mutableListOf<String>()
        var current = StringBuilder()
        var currentBytes = 0
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            val token = String(Character.toChars(codePoint))
            val tokenBytes = token.toByteArray(Charsets.UTF_8).size
            require(tokenBytes <= maxBytes) { "Negotiated MTU cannot carry one runtime character" }
            if (currentBytes + tokenBytes > maxBytes) {
                chunks += current.toString()
                current = StringBuilder()
                currentBytes = 0
            }
            current.append(token)
            currentBytes += tokenBytes
            offset += Character.charCount(codePoint)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }
}
