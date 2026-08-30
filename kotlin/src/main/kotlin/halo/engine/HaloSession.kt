package halo.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import java.io.ByteArrayOutputStream
import kotlin.time.Duration

/**
 * Raised when a streaming session would exceed an explicit byte ceiling.
 * This is a recoverable policy violation, not a BLE protocol or transport error.
 */
class HaloLimitException(message: String) : RuntimeException(message)

/**
 * Generic request/response and streaming session over a [HaloBleTransport].
 *
 * This consolidates the chunk-collection logic used by microphone, camera,
 * battery, and other streaming capabilities. It is cancellation-aware, uses a
 * single [ByteArrayOutputStream] to avoid per-chunk allocations, and ensures
 * the stop command is sent even when the operation times out or is cancelled.
 */
class HaloSession(
    private val messages: Flow<HaloMessage>,
    private val send: suspend (Int, ByteArray) -> Unit,
) {

    constructor(transport: HaloBleTransport) : this(transport.messages, transport::sendMessage)

    /**
     * Send [requestCode] with [requestPayload], wait up to [timeout] for a single
     * [responseCode] message, and return its payload.
     */
    suspend fun requestResponse(
        requestCode: Int,
        requestPayload: ByteArray,
        responseCode: Int,
        timeout: Duration,
    ): ByteArray = coroutineScope {
        currentCoroutineContext().ensureActive()
        val response = CompletableDeferred<ByteArray>()
        val waiter = async {
            messages
                .filter { it.code == responseCode }
                .first()
                .let { response.complete(it.payload) }
        }
        try {
            send(requestCode, requestPayload)
            withTimeout(timeout) { response.await() }
        } finally {
            waiter.cancel()
        }
    }

    /**
     * Start a streaming session by sending [startCode]/[startPayload], collect
     * [chunkCode] payloads into a single [ByteArray], and finish when [finalCode]
     * is received. If [stopCode] is provided, it is sent in a `finally` block so
     * the device is always released on timeout or cancellation.
     *
     * If [maxBytes] is exceeded while collecting chunks, the session is aborted
     * and [HaloLimitException] is thrown so the device can be released.
     */
    suspend fun collect(
        startCode: Int,
        startPayload: ByteArray,
        stopCode: Int? = null,
        stopPayload: ByteArray = byteArrayOf(),
        chunkCode: Int,
        finalCode: Int,
        timeout: Duration,
        maxBytes: Long = Long.MAX_VALUE,
    ): ByteArray = coroutineScope {
        currentCoroutineContext().ensureActive()
        val finalSignal = CompletableDeferred<Unit>()
        val output = ByteArrayOutputStream()
        var written: Long = 0

        val collector = async {
            messages
                .filter { it.code == chunkCode || it.code == finalCode }
                .collect { message ->
                    currentCoroutineContext().ensureActive()
                    when (message.code) {
                        chunkCode -> {
                            val chunk = message.payload
                            val newSize = written + chunk.size
                            if (newSize > maxBytes) {
                                throw HaloLimitException("collected payload exceeds $maxBytes bytes")
                            }
                            output.write(chunk)
                            written += chunk.size
                        }
                        finalCode -> finalSignal.complete(Unit)
                    }
                }
        }

        try {
            send(startCode, startPayload)
            withTimeout(timeout) { finalSignal.await() }
            output.toByteArray()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            stopCode?.let { runCatching { send(it, stopPayload) } }
            collector.cancel()
        }
    }
}
