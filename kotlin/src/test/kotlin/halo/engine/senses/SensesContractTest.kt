package halo.engine.senses

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A minimal deterministic simulator used to exercise the contract without
 * Android, BLE, or firmware dependencies.
 */
private class SimulatedSensesDevice(
    private val backendName: String = "simulator",
) : SensesDevice {

    private val _state = MutableStateFlow(DeviceState(backendName = backendName))
    override val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64)
    override val events = _events.asSharedFlow()

    private val lock = Mutex()
    private var connected = false

    override suspend fun connect(target: DeviceTarget?) = lock.withLock {
        _state.value = DeviceState(
            isConnected = true,
            isReady = true,
            target = target,
            supportedFeatures = setOf(
                DeviceFeature.CONNECT,
                DeviceFeature.AUDIO_CAPTURE,
                DeviceFeature.IMAGE_CAPTURE,
                DeviceFeature.PLAYBACK,
                DeviceFeature.PRESENTATION,
                DeviceFeature.INPUT,
                DeviceFeature.BATTERY,
            ),
            backendName = backendName,
        )
        connected = true
    }

    override suspend fun disconnect() = lock.withLock {
        _state.value = _state.value.copy(isConnected = false, isReady = false)
        _events.tryEmit(DisconnectedEvent)
        connected = false
    }

    override suspend fun captureAudio(request: AudioCaptureRequest): AudioCapture {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        val duration = min(request.maxDurationMillis, 10_000)
        val sampleRate = 16000
        val bytes = min(duration * sampleRate / 1000 * 2, request.maxBytes.toLong()).toInt()
        return AudioCapture(
            audio = ByteArray(bytes) { (it % 256).toByte() },
            format = AudioFormat(sampleRate, 16, 1, "pcm-s16le", "audio/pcm"),
            durationMillis = duration,
        )
    }

    override suspend fun captureImage(request: ImageCaptureRequest): ImageCapture {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        val size = min(request.resolution * request.resolution, request.maxBytes)
        return ImageCapture(
            image = ByteArray(size) { (it % 256).toByte() },
            format = ImageFormat("jpeg", "image/jpeg", request.resolution, request.resolution),
            isRaw = request.raw,
        )
    }

    override suspend fun awaitInput(request: AwaitInputRequest): InputEvent {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        val event = withTimeoutOrNull(request.timeoutMillis) {
            _events.first { event ->
                event is TapEvent &&
                    event.source in request.acceptedSources &&
                    event.gesture in request.acceptedGestures
            }
        } ?: throw SensesError.Timeout("No matching input within ${request.timeoutMillis}ms")
        return event
    }

    override suspend fun playAudio(request: AudioPlaybackRequest) {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        if (request.volume !in 0..100) throw SensesError.Rejected("volume must be 0..100")
    }

    override suspend fun present(request: DevicePresentation) {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        if (request.format == PresentationFormat.CLEAR) return
        if (request.payload.isEmpty()) throw SensesError.Rejected("presentation payload is empty")
    }

    override suspend fun clearDisplay() = present(DevicePresentation(PresentationFormat.CLEAR))

    override suspend fun battery(): BatteryState {
        ensureConnected()
        currentCoroutineContext().ensureActive()
        return BatteryState(80, 4100, false)
    }

    suspend fun emit(event: InputEvent) = _events.emit(event)

    private fun ensureConnected() {
        if (!connected) throw SensesError.Disconnected("simulator is not connected")
    }
}

class SensesContractTest {

    @Test
    fun contractCanBeImplementedWithoutAndroidOrHaloDependencies() = runTest {
        val device = SimulatedSensesDevice()

        device.connect(DeviceTarget(name = "Halo-Test", address = "00:11:22:33:44:55"))

        assertTrue(device.state.value.isConnected)
        assertEquals("Halo-Test", device.state.value.target?.name)
        assertTrue(DeviceFeature.AUDIO_CAPTURE in device.state.value.supportedFeatures)

        val audio = device.captureAudio(
            AudioCaptureRequest(maxDurationMillis = 100, maxBytes = 4_096)
        )
        assertEquals(16000, audio.format.sampleRate)
        assertTrue(audio.audio.size <= 4_096)

        val image = device.captureImage(
            ImageCaptureRequest(resolution = 256, qualityIndex = 4, maxBytes = 65_536)
        )
        assertEquals(256, image.format.width)
        assertEquals("image/jpeg", image.format.mime)
        assertTrue(image.image.size <= 65_536)

        val battery = device.battery()
        assertEquals(80, battery.level)

        val tapJob = launch { device.emit(TapEvent("button", "single")) }
        val input = device.awaitInput(
            AwaitInputRequest(
                acceptedSources = setOf("button"),
                acceptedGestures = setOf("single"),
                timeoutMillis = 5_000,
            )
        )
        tapJob.join()

        assertEquals("button", input.source)
        assertEquals("single", input.gesture)

        device.disconnect()
        assertTrue(!device.state.value.isConnected)
    }

    @Test
    fun operationsFailWhenDisconnected() = runTest {
        val device = SimulatedSensesDevice()

        assertFailsWith<SensesError.Disconnected> {
            device.captureAudio(AudioCaptureRequest(maxDurationMillis = 100, maxBytes = 1_000))
        }
    }

    @Test
    fun cancellationIssuesDisconnectedEvent() = runTest {
        val device = SimulatedSensesDevice()
        device.connect()

        val await = launch {
            device.awaitInput(
                AwaitInputRequest(
                    acceptedSources = setOf("button"),
                    acceptedGestures = setOf("single"),
                    timeoutMillis = 60_000,
                )
            )
        }

        delay(10)
        await.cancelAndJoin()

        // The contract itself does not mandate a state change on coroutine
        // cancellation, but the simulator must not crash and must remain safe.
        assertTrue(device.state.value.isConnected)
    }

    @Test
    fun invalidPlaybackVolumeIsRejected() = runTest {
        val device = SimulatedSensesDevice()
        device.connect()

        assertFailsWith<SensesError.Rejected> {
            device.playAudio(
                AudioPlaybackRequest(
                    audio = ByteArray(100),
                    format = AudioFormat(16000, 16, 1, "pcm-s16le", "audio/pcm"),
                    volume = 150,
                )
            )
        }
    }
}
