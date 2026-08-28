package halo.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HaloSessionTest {

    private val transport = MockBleTransport()
    private val session = HaloSession(transport)

    @Test
    fun collectChunksAndFinal() = runTest {
        val result = async {
            session.collect(
                startCode = HaloProtocol.MICROPHONE_START,
                startPayload = byteArrayOf(10, 1, 0),
                stopCode = HaloProtocol.MICROPHONE_STOP,
                chunkCode = HaloProtocol.AUDIO_CHUNK,
                finalCode = HaloProtocol.AUDIO_FINAL,
                timeout = 5.seconds,
            )
        }

        launch {
            delay(10)
            transport.emitMessage(HaloProtocol.AUDIO_CHUNK, byteArrayOf(1, 2, 3))
            transport.emitMessage(HaloProtocol.AUDIO_CHUNK, byteArrayOf(4, 5))
            transport.emitMessage(HaloProtocol.AUDIO_FINAL, byteArrayOf())
        }

        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), result.await())
        assertEquals(HaloProtocol.MICROPHONE_START, transport.dataChunks.first()[0].toInt() and 0xff)
    }

    @Test
    fun requestResponseReturnsFirstMatchingMessage() = runTest {
        val result = async {
            session.requestResponse(
                requestCode = HaloProtocol.DEVICE_STATUS,
                requestPayload = byteArrayOf(),
                responseCode = HaloProtocol.DEVICE_STATUS,
                timeout = 5.seconds,
            )
        }

        launch {
            delay(10)
            transport.emitMessage(HaloProtocol.AUDIO_CHUNK, byteArrayOf(1, 2))
            transport.emitMessage(HaloProtocol.DEVICE_STATUS, byteArrayOf(80, 16, 40, 1))
        }

        assertContentEquals(byteArrayOf(80, 16, 40, 1), result.await())
        assertEquals(HaloProtocol.DEVICE_STATUS, transport.dataChunks.first()[0].toInt() and 0xff)
    }

    @Test
    fun sendsStopOnTimeout() = runTest {
        val result = runCatching {
            session.collect(
                startCode = HaloProtocol.MICROPHONE_START,
                startPayload = byteArrayOf(),
                stopCode = HaloProtocol.MICROPHONE_STOP,
                chunkCode = HaloProtocol.AUDIO_CHUNK,
                finalCode = HaloProtocol.AUDIO_FINAL,
                timeout = 50.milliseconds,
            )
        }

        assertTrue(result.isFailure)
        assertEquals(HaloProtocol.MICROPHONE_START, transport.dataChunks[0][0].toInt() and 0xff)
        assertEquals(HaloProtocol.MICROPHONE_STOP, transport.dataChunks[1][0].toInt() and 0xff)
    }

    @Test
    fun noStopCodeWhenNotProvided() = runTest {
        val result = async {
            session.collect(
                startCode = HaloProtocol.CAPTURE_PHOTO,
                startPayload = byteArrayOf(4, 0, 1, 0, 0x5c, 0, 0),
                chunkCode = HaloProtocol.PHOTO_JPEG,
                finalCode = HaloProtocol.PHOTO_FINAL,
                timeout = 5.seconds,
            )
        }

        launch {
            delay(10)
            transport.emitMessage(HaloProtocol.PHOTO_JPEG, byteArrayOf(0xff.toByte(), 0xd8.toByte()))
            transport.emitMessage(HaloProtocol.PHOTO_FINAL, byteArrayOf())
        }

        assertContentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte()), result.await())
        assertEquals(1, transport.dataChunks.size)
    }
}
