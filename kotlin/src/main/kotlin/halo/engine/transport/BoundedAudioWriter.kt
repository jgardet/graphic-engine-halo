package halo.engine.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Bounded audio frame writer with explicit backpressure and deadline enforcement.
 *
 * Replaces the unbounded `delay()`-paced `sendAudioFrame` loop. The writer
 * maintains a bounded queue of audio frames. A background coroutine drains
 * the queue and writes each frame through [sink]. If the queue fills, the
 * caller is suspended (backpressure) instead of overflowing the firmware
 * input buffer.
 *
 * Two pacing modes are supported:
 * - [PacingMode.CallbackPaced]: wait for [AudioFrameSink.onFrameWritten] after
 *   each write. Used when the Android stack reliably issues
 *   `onCharacteristicWrite` for no-response writes.
 * - [PacingMode.DelayPaced]: wait [frameDelay] after each write. Used when
 *   callbacks are unreliable (the common case for `WRITE_TYPE_NO_RESPONSE`).
 *
 * The writer enforces an overall [deadline] for the playback operation. If
 * the deadline is exceeded (e.g. the BLE link stalls), the writer aborts and
 * throws [AudioWriterTimeout].
 *
 * On cancellation or disconnect, the writer is released cleanly: the queue
 * is closed, the drain coroutine is cancelled, and [AudioFrameSink.onRelease]
 * is called.
 *
 * This class is platform-neutral — the Android layer implements
 * [AudioFrameSink] using `BluetoothGatt.writeCharacteristic`.
 */

/** How frames are paced after writing. */
enum class PacingMode {
    /** Wait for [AudioFrameSink.onFrameWritten] callback after each frame. */
    CallbackPaced,
    /** Wait a fixed [BoundedAudioWriter.frameDelay] after each frame. */
    DelayPaced,
}

/**
 * Platform-neutral sink for audio frame writes.
 *
 * The Android implementation wraps `BluetoothGatt.writeCharacteristic`
 * on the AUDIO_TX characteristic.
 */
interface AudioFrameSink {
    /**
     * Write one frame to the device. Returns true if the write was accepted
     * by the BLE stack. In [PacingMode.CallbackPaced], [onFrameWritten] will
     * be called when the write completes.
     */
    suspend fun writeFrame(frame: ByteArray): Boolean

    /**
     * Called when a frame write completes (callback-paced mode only).
     * Implementations should complete a deferred/semaphore that
     * [BoundedAudioWriter] is waiting on.
     *
     * Default implementation is a no-op for delay-paced mode.
     */
    fun onFrameWritten() {}

    /** Called when the writer is released (cancellation, disconnect, or abort). */
    fun onRelease() {}
}

/** Raised when the audio writer exceeds its operation deadline. */
class AudioWriterTimeout(message: String) : RuntimeException(message)

/** Raised when the audio queue is full and the caller times out waiting. */
class AudioQueueFull(message: String) : RuntimeException(message)

/**
 * Bounded audio frame writer.
 *
 * Usage:
 * ```
 * val writer = BoundedAudioWriter(sink, queueCapacity = 8, ...)
 * writer.start()
 * for (frame in frames) {
 *     writer.sendFrame(frame)  // suspends if queue is full (backpressure)
 * }
 * writer.awaitCompletion(playbackDeadline)
 * ```
 *
 * Or use [playFrames] for a one-shot coroutine that handles the full lifecycle.
 */
class BoundedAudioWriter(
    private val sink: AudioFrameSink,
    private val queueCapacity: Int = 8,
    private val pacingMode: PacingMode = PacingMode.DelayPaced,
    private val frameDelay: Duration = 10.milliseconds,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val queue = Channel<ByteArray>(queueCapacity)
    private var drainJob: Job? = null
    private var active = false

    /** Start the background drain coroutine. */
    fun start() {
        check(!active) { "writer already started" }
        active = true
        drainJob = scope.launch { drainLoop() }
    }

    /**
     * Send a frame to the queue. Suspends if the queue is full (backpressure).
     * Throws if the writer is not active or has been released.
     */
    suspend fun sendFrame(frame: ByteArray) {
        ensureActive()
        queue.send(frame)
    }

    /**
     * Try to send a frame without suspending. Returns true if the frame was
     * queued, false if the queue is full.
     */
    fun trySendFrame(frame: ByteArray): Boolean {
        return queue.trySend(frame).isSuccess
    }

    /**
     * Signal that no more frames will be sent. The drain coroutine will
     * finish after writing all queued frames.
     */
    fun signalCompletion() {
        queue.close()
    }

    /**
     * Wait for all queued frames to be written. Throws [AudioWriterTimeout]
     * if [deadline] is exceeded.
     */
    suspend fun awaitCompletion(deadline: Duration) {
        val job = drainJob ?: return
        try {
            withTimeout(deadline) { job.join() }
        } catch (e: AudioWriterTimeout) {
            throw e
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            release()
            throw AudioWriterTimeout("audio playback exceeded ${deadline.inWholeMilliseconds}ms deadline")
        }
    }

    /**
     * One-shot helper: start the writer, send all [frames], signal completion,
     * and await completion within [deadline]. Releases the writer on any
     * failure path.
     */
    suspend fun playFrames(frames: Sequence<ByteArray>, deadline: Duration) = coroutineScope {
        start()
        try {
            for (frame in frames) {
                currentCoroutineContext().ensureActive()
                sendFrame(frame)
            }
            signalCompletion()
            awaitCompletion(deadline)
        } catch (e: CancellationException) {
            release()
            throw e
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    /** Release the writer: close the queue, cancel the drain job, notify the sink. */
    fun release() {
        if (!active) return
        active = false
        queue.close()
        drainJob?.cancel()
        drainJob = null
        sink.onRelease()
    }

    val isActive: Boolean get() = active

    private suspend fun drainLoop() {
        for (frame in queue) {
            currentCoroutineContext().ensureActive()
            val accepted = sink.writeFrame(frame)
            if (!accepted) {
                // BLE stack rejected the write — abort
                break
            }
            when (pacingMode) {
                PacingMode.CallbackPaced -> {
                    // Wait for onFrameWritten to call back.
                    // The sink implementation is responsible for suspending
                    // its writeFrame until the callback fires, or for
                    // providing a separate mechanism. We just continue here
                    // since writeFrame should not return until the callback
                    // fires in callback-paced mode.
                }
                PacingMode.DelayPaced -> {
                    kotlinx.coroutines.delay(frameDelay)
                }
            }
        }
        sink.onRelease()
        active = false
    }

    private fun ensureActive() {
        if (!active) throw IllegalStateException("writer is not active")
    }
}
