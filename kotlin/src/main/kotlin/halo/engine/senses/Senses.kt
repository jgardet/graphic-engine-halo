package halo.engine.senses

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pure Kotlin contracts for a device-neutral sense layer.
 *
 * The interface and its DTOs are free of Android, BLE, Ktor, Node, Python,
 * firmware, and model types. Implementations are provided by a concrete
 * backend (e.g. a Halo BLE adapter or a scripted simulator).
 */
interface SensesDevice {
    val state: StateFlow<DeviceState>
    val events: Flow<InputEvent>

    suspend fun connect(target: DeviceTarget? = null)
    suspend fun disconnect()

    suspend fun captureAudio(request: AudioCaptureRequest): AudioCapture
    suspend fun captureImage(request: ImageCaptureRequest): ImageCapture
    suspend fun awaitInput(request: AwaitInputRequest): InputEvent
    suspend fun playAudio(request: AudioPlaybackRequest)
    suspend fun present(request: DevicePresentation)
    suspend fun clearDisplay()
    suspend fun battery(): BatteryState
}

enum class DeviceFeature {
    AUDIO_CAPTURE,
    IMAGE_CAPTURE,
    PLAYBACK,
    PRESENTATION,
    INPUT,
    BATTERY,
    CONNECT,
}

data class DeviceState(
    val isConnected: Boolean = false,
    val isReady: Boolean = false,
    val target: DeviceTarget? = null,
    val supportedFeatures: Set<DeviceFeature> = emptySet(),
    val backendName: String = "unknown",
)

data class DeviceTarget(
    val name: String? = null,
    val address: String? = null,
)

sealed interface InputEvent {
    val source: String
    val gesture: String
    val timestamp: Long
}

data class TapEvent(
    override val source: String,
    override val gesture: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : InputEvent

data class ButtonEvent(
    override val source: String,
    override val gesture: String,
    override val timestamp: Long = System.currentTimeMillis(),
) : InputEvent

data object DisconnectedEvent : InputEvent {
    override val source: String = "device"
    override val gesture: String = "disconnected"
    override val timestamp: Long = 0
}

data class AudioFormat(
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int,
    val encoding: String,
    val mime: String,
)

data class ImageFormat(
    val encoding: String,
    val mime: String,
    val width: Int,
    val height: Int,
)

data class AudioCaptureRequest(
    val maxDurationMillis: Long,
    val gain: Int = 0,
    val aec: Boolean = true,
    val voice: Boolean = true,
    val maxBytes: Int,
)

data class AudioCapture(
    val audio: ByteArray,
    val format: AudioFormat,
    val durationMillis: Long,
)

data class ImageCaptureRequest(
    val resolution: Int,
    val qualityIndex: Int,
    val pan: Int = 0,
    val raw: Boolean = false,
    val maxBytes: Int,
)

data class ImageCapture(
    val image: ByteArray,
    val format: ImageFormat,
    val isRaw: Boolean,
)

data class AwaitInputRequest(
    val acceptedSources: Set<String>,
    val acceptedGestures: Set<String>,
    val timeoutMillis: Long,
)

data class AudioPlaybackRequest(
    val audio: ByteArray,
    val format: AudioFormat,
    val volume: Int,
)

enum class PresentationFormat {
    HSD,
    HRP,
    LUA,
    CLEAR,
}

data class DevicePresentation(
    val format: PresentationFormat,
    val payload: ByteArray = ByteArray(0),
    val replaceCurrent: Boolean = true,
)

data class BatteryState(
    val level: Int,
    val voltage: Int,
    val charging: Boolean,
)

sealed class SensesError(
    val category: Category,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    enum class Category {
        Unavailable,
        Disconnected,
        Timeout,
        Cancelled,
        Rejected,
        LimitExceeded,
        Protocol,
        PermissionDenied,
        ModelUnavailable,
        Internal,
    }

    class Unavailable(message: String, cause: Throwable? = null) : SensesError(Category.Unavailable, message, cause)
    class Disconnected(message: String, cause: Throwable? = null) : SensesError(Category.Disconnected, message, cause)
    class Timeout(message: String, cause: Throwable? = null) : SensesError(Category.Timeout, message, cause)
    class Cancelled(message: String, cause: Throwable? = null) : SensesError(Category.Cancelled, message, cause)
    class Rejected(message: String, cause: Throwable? = null) : SensesError(Category.Rejected, message, cause)
    class LimitExceeded(message: String, cause: Throwable? = null) : SensesError(Category.LimitExceeded, message, cause)
    class Protocol(message: String, cause: Throwable? = null) : SensesError(Category.Protocol, message, cause)
    class PermissionDenied(message: String, cause: Throwable? = null) : SensesError(Category.PermissionDenied, message, cause)
    class ModelUnavailable(message: String, cause: Throwable? = null) : SensesError(Category.ModelUnavailable, message, cause)
    class Internal(message: String, cause: Throwable? = null) : SensesError(Category.Internal, message, cause)
}
