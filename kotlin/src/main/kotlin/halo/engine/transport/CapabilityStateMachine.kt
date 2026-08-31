package halo.engine.transport

import halo.engine.HaloProtocol
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Device-side capability state machines for the virtual Halo endpoint.
 *
 * Each state machine mirrors the behavior of `he_runtime.lua`'s
 * `handle_message()` function and the associated streaming helpers
 * (`send_mic_chunks`, `send_photo`, `send_battery`). The virtual endpoint
 * uses these to produce realistic device-to-host notifications in response
 * to host commands, without requiring real hardware.
 *
 * All state machines are driven by [MessageReassembler.Message] instances
 * (the output of [MessageReassembler.drainCompleted]). They emit
 * [DeviceEvent]s that the transport layer sends back to the host.
 */

/** A device-to-host event: a message code + payload, or a text print. */
sealed interface DeviceEvent {
    data class Message(val code: Int, val payload: ByteArray) : DeviceEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Message) return false
            return code == other.code && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * code + payload.contentHashCode()
    }
    data class Text(val value: String) : DeviceEvent
}

/**
 * Configuration for simulated capabilities.
 *
 * Values are configurable so tests can inject deterministic battery levels,
 * photo payloads, microphone audio, etc.
 */
data class CapabilityConfig(
    val batteryLevel: Int = 75,
    val batteryVoltage: Int = 3700,
    val batteryCharging: Boolean = false,
    /** Microphone: bytes of PCM audio to emit per chunk. */
    val micChunkBytes: ByteArray = ByteArray(320) { 0 },
    /** Microphone: number of chunks before emitting AUDIO_FINAL. */
    val micChunkCount: Int = 5,
    /** Photo: complete JPEG bytes to emit in chunks. */
    val photoData: ByteArray = ByteArray(0),
    /** Photo: chunk size in bytes. */
    val photoChunkSize: Int = 200,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapabilityConfig) return false
        return batteryLevel == other.batteryLevel &&
            batteryVoltage == other.batteryVoltage &&
            batteryCharging == other.batteryCharging &&
            micChunkCount == other.micChunkCount &&
            micChunkBytes.contentEquals(other.micChunkBytes) &&
            photoData.contentEquals(other.photoData) &&
            photoChunkSize == other.photoChunkSize
    }
    override fun hashCode(): Int {
        var result = batteryLevel
        result = 31 * result + batteryVoltage
        result = 31 * result + batteryCharging.hashCode()
        result = 31 * result + micChunkBytes.contentHashCode()
        result = 31 * result + micChunkCount
        result = 31 * result + photoData.contentHashCode()
        result = 31 * result + photoChunkSize
        return result
    }
}

/**
 * Virtual Halo capability state machine.
 *
 * Feed reassembled messages via [handleMessage]. The machine accumulates
 * [DeviceEvent]s in an internal queue; drain them with [drainEvents].
 * Call [tick] to advance time-dependent streaming (microphone chunks,
 * photo chunks).
 */
class CapabilityStateMachine(
    private val config: CapabilityConfig = CapabilityConfig(),
) {
    private val eventQueue = ConcurrentLinkedQueue<DeviceEvent>()

    // Microphone state
    private var micStreaming = false
    private var micChunksSent = 0

    // Photo state
    private var photoPending = false
    private var photoOffset = 0

    // Speaker state
    private var speakerActive = false

    /** Boot sequence: emit STATUS with capability string. */
    fun boot() {
        emit(DeviceEvent.Message(HaloProtocol.STATUS, "HRP1;primitives,sprites,click,tap,mic,speaker,photo,battery".toByteArray()))
        emit(DeviceEvent.Text("Halo Engine v2 ready"))
    }

    /**
     * Handle a reassembled message from the host.
     *
     * Returns true if the message was recognized and handled.
     */
    fun handleMessage(code: Int, payload: ByteArray): Boolean {
        when (code) {
            HaloProtocol.HRP -> {
                // HRP is handled by HrpRenderer, not here.
                return true
            }
            0x10 -> { // CLEAR_DISPLAY
                // Handled by display layer
                return true
            }
            0x11 -> { // PLAIN_TEXT
                return true
            }
            HaloProtocol.MICROPHONE_START -> {
                startMicrophone(payload)
                return true
            }
            HaloProtocol.MICROPHONE_STOP -> {
                stopMicrophone()
                return true
            }
            HaloProtocol.SPEAKER_START -> {
                startSpeaker(payload)
                return true
            }
            HaloProtocol.SPEAKER_STOP -> {
                stopSpeaker()
                return true
            }
            HaloProtocol.CAPTURE_PHOTO -> {
                capturePhoto(payload)
                return true
            }
            HaloProtocol.DEVICE_STATUS -> { // BATTERY_CODE in he_runtime.lua is 0x72
                sendBattery()
                return true
            }
            else -> return false
        }
    }

    /**
     * Advance streaming state. Called periodically by the transport.
     *
     * Emits microphone chunks or photo chunks as needed.
     */
    fun tick() {
        if (micStreaming) {
            sendMicChunks()
        }
        if (photoPending) {
            sendPhotoChunks()
        }
    }

    // ------------------------------------------------------------------ microphone

    private fun startMicrophone(payload: ByteArray) {
        micStreaming = true
        micChunksSent = 0
    }

    private fun stopMicrophone() {
        // The device stops recording; remaining buffered audio is drained
        // by subsequent tick() calls. AUDIO_FINAL is emitted when the
        // microphone read returns nil (after the stop).
        micStreaming = false
        // In he_runtime.lua, stop just calls frame.microphone.stop().
        // The send_mic_chunks loop in the main while loop will emit AUDIO_FINAL
        // when frame.microphone.read() returns nil.
        // For the virtual endpoint, we emit AUDIO_FINAL immediately after stop
        // since there's no real buffer to drain.
        emit(DeviceEvent.Message(HaloProtocol.AUDIO_FINAL, ByteArray(0)))
    }

    private fun sendMicChunks() {
        if (!micStreaming) return
        repeat(10) {
            if (micChunksSent >= config.micChunkCount) {
                emit(DeviceEvent.Message(HaloProtocol.AUDIO_FINAL, ByteArray(0)))
                micStreaming = false
                return
            }
            emit(DeviceEvent.Message(HaloProtocol.AUDIO_CHUNK, config.micChunkBytes))
            micChunksSent++
        }
    }

    // ------------------------------------------------------------------ photo

    private fun capturePhoto(payload: ByteArray) {
        if (photoPending) return  // busy
        photoPending = true
        photoOffset = 0
    }

    private fun sendPhotoChunks() {
        if (!photoPending) return
        if (config.photoData.isEmpty()) {
            emit(DeviceEvent.Message(HaloProtocol.PHOTO_FINAL, ByteArray(0)))
            photoPending = false
            return
        }
        while (photoOffset < config.photoData.size) {
            val chunkLen = minOf(config.photoData.size - photoOffset, config.photoChunkSize)
            val chunk = config.photoData.copyOfRange(photoOffset, photoOffset + chunkLen)
            emit(DeviceEvent.Message(HaloProtocol.PHOTO_JPEG, chunk))
            photoOffset += chunkLen
        }
        emit(DeviceEvent.Message(HaloProtocol.PHOTO_FINAL, ByteArray(0)))
        photoPending = false
    }

    // ------------------------------------------------------------------ speaker

    private fun startSpeaker(payload: ByteArray) {
        speakerActive = true
    }

    private fun stopSpeaker() {
        speakerActive = false
    }

    // ------------------------------------------------------------------ battery

    private fun sendBattery() {
        val payload = byteArrayOf(
            config.batteryLevel.toByte(),
            (config.batteryVoltage ushr 8).toByte(),
            (config.batteryVoltage and 0xFF).toByte(),
            if (config.batteryCharging) 1 else 0,
        )
        emit(DeviceEvent.Message(HaloProtocol.DEVICE_STATUS, payload))
    }

    // ------------------------------------------------------------------ input

    /** Inject a button event (single=1, double=2, long=3). */
    fun buttonEvent(gesture: Int) {
        emit(DeviceEvent.Message(HaloProtocol.BUTTON, byteArrayOf(gesture.toByte())))
    }

    /** Inject a tap event (single=1, double=2, triple=3). */
    fun tapEvent(kind: Int) {
        emit(DeviceEvent.Message(HaloProtocol.TAP, byteArrayOf(kind.toByte())))
    }

    // ------------------------------------------------------------------ state queries

    fun isMicStreaming(): Boolean = micStreaming
    fun isPhotoPending(): Boolean = photoPending
    fun isSpeakerActive(): Boolean = speakerActive

    /** Reset all state (e.g. on disconnect). */
    fun reset() {
        micStreaming = false
        micChunksSent = 0
        photoPending = false
        photoOffset = 0
        speakerActive = false
    }

    /** Drain and return all queued events in emission order. */
    fun drainEvents(): List<DeviceEvent> {
        val result = mutableListOf<DeviceEvent>()
        while (true) {
            val e = eventQueue.poll() ?: break
            result.add(e)
        }
        return result
    }

    private fun emit(event: DeviceEvent) {
        eventQueue.add(event)
    }
}
