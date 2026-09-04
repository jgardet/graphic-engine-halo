package halo.engine.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BoundedAudioWriterTest {

    /** A sink that records all frames and optionally delays writes. */
    private class RecordingSink(
        private val writeDelayMs: Long = 0,
    ) : AudioFrameSink {
        val written = mutableListOf<ByteArray>()
        var releaseCalled = false
        var writeCount = 0

        override suspend fun writeFrame(frame: ByteArray): Boolean {
            if (writeDelayMs > 0) delay(writeDelayMs)
            written.add(frame.copyOf())
            writeCount++
            return true
        }

        override fun onRelease() {
            releaseCalled = true
        }
    }

    /** A sink that rejects writes after [maxWrites] frames. */
    private class RejectingSink(private val maxWrites: Int) : AudioFrameSink {
        var writes = 0
        var releaseCalled = false

        override suspend fun writeFrame(frame: ByteArray): Boolean {
            writes++
            return writes <= maxWrites
        }

        override fun onRelease() {
            releaseCalled = true
        }
    }

    /** Create a writer that uses the test scope's dispatcher (virtual time). */
    private fun testWriter(
        sink: AudioFrameSink,
        queueCapacity: Int = 4,
        pacingMode: PacingMode = PacingMode.DelayPaced,
        frameDelay: Duration = 1.milliseconds,
        scope: CoroutineScope,
    ) = BoundedAudioWriter(sink, queueCapacity, pacingMode, frameDelay, scope)

    @Test
    fun playFramesWritesAllFramesInOrder() = runTest {
        val sink = RecordingSink()
        val writer = testWriter(sink, queueCapacity = 4, pacingMode = PacingMode.DelayPaced, frameDelay = 1.milliseconds, scope = this)
        val frames = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
        writer.playFrames(frames.asSequence(), deadline = 5.seconds)
        assertEquals(3, sink.written.size)
        assertEquals(1, sink.written[0][0].toInt())
        assertEquals(2, sink.written[1][0].toInt())
        assertEquals(3, sink.written[2][0].toInt())
        assertFalse(writer.isActive)
    }

    @Test
    fun backpressureSuspendsWhenQueueIsFull() = runTest {
        // Queue capacity = 2, write delay = 50ms per frame.
        // Send 4 frames; the caller should suspend when the queue is full.
        val sink = RecordingSink(writeDelayMs = 50)
        val writer = testWriter(sink, queueCapacity = 2, pacingMode = PacingMode.DelayPaced, frameDelay = 1.milliseconds, scope = this)
        writer.start()

        // Send 2 frames — should fill the queue immediately
        writer.trySendFrame(byteArrayOf(1))
        writer.trySendFrame(byteArrayOf(2))
        // Queue is now full; trySend should fail
        assertFalse(writer.trySendFrame(byteArrayOf(3)))

        // sendFrame should suspend until the drain loop frees a slot
        writer.sendFrame(byteArrayOf(3))
        writer.sendFrame(byteArrayOf(4))
        writer.signalCompletion()
        writer.awaitCompletion(5.seconds)

        assertEquals(4, sink.written.size)
        assertFalse(writer.isActive)
    }

    @Test
    fun deadlineExceededExceptionIsThrown() = runTest {
        // Sink that takes 100ms per write, but deadline is 10ms
        val sink = RecordingSink(writeDelayMs = 100)
        val writer = testWriter(sink, queueCapacity = 4, pacingMode = PacingMode.DelayPaced, frameDelay = 1.milliseconds, scope = this)
        assertFailsWith<AudioWriterTimeout> {
            writer.playFrames(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)).asSequence(), deadline = 10.milliseconds)
        }
        assertTrue(sink.releaseCalled, "sink should be released on timeout")
    }

    @Test
    fun rejectedWriteAbortsPlayback() = runTest {
        val sink = RejectingSink(maxWrites = 2)
        val writer = testWriter(sink, queueCapacity = 4, pacingMode = PacingMode.DelayPaced, frameDelay = 1.milliseconds, scope = this)
        writer.playFrames(listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3), byteArrayOf(4)).asSequence(), deadline = 5.seconds)
        // Only 2 frames should have been attempted (3rd was rejected)
        assertEquals(3, sink.writes)  // 2 accepted + 1 rejected
        assertFalse(writer.isActive)
    }

    @Test
    fun releaseCleansUpAndNotifiesSink() = runTest {
        val sink = RecordingSink()
        val writer = testWriter(sink, queueCapacity = 4, scope = this)
        writer.start()
        assertTrue(writer.isActive)
        writer.release()
        assertFalse(writer.isActive)
        assertTrue(sink.releaseCalled)
    }

    @Test
    fun doubleStartThrows() = runTest {
        val sink = RecordingSink()
        val writer = testWriter(sink, scope = this)
        writer.start()
        assertFailsWith<IllegalStateException> { writer.start() }
        writer.release()
    }

    @Test
    fun sendFrameBeforeStartThrows() = runTest {
        val sink = RecordingSink()
        val writer = testWriter(sink, scope = this)
        assertFailsWith<IllegalStateException> {
            writer.sendFrame(byteArrayOf(1))
        }
    }

    @Test
    fun signalCompletionDrainsRemainingFrames() = runTest {
        val sink = RecordingSink()
        val writer = testWriter(sink, queueCapacity = 4, pacingMode = PacingMode.DelayPaced, frameDelay = 1.milliseconds, scope = this)
        writer.start()
        writer.sendFrame(byteArrayOf(1))
        writer.sendFrame(byteArrayOf(2))
        writer.signalCompletion()
        writer.awaitCompletion(5.seconds)
        assertEquals(2, sink.written.size)
        assertFalse(writer.isActive)
    }

    @Test
    fun callbackPacedModeWaitsForWriteFrame() = runTest {
        // In callback-paced mode, writeFrame should not return until the
        // callback fires. The sink simulates this by delaying inside writeFrame.
        val sink = RecordingSink(writeDelayMs = 10)
        val writer = testWriter(
            sink,
            queueCapacity = 4,
            pacingMode = PacingMode.CallbackPaced,
            frameDelay = 1.milliseconds,  // not used in callback mode
            scope = this,
        )
        writer.playFrames(listOf(byteArrayOf(1), byteArrayOf(2)).asSequence(), deadline = 5.seconds)
        assertEquals(2, sink.written.size)
    }

    @Test
    fun emptyFrameSequenceCompletesImmediately() = runTest {
        val sink = RecordingSink()
        val writer = testWriter(sink, scope = this)
        writer.playFrames(emptySequence(), deadline = 5.seconds)
        assertEquals(0, sink.written.size)
        assertFalse(writer.isActive)
    }

    @Test
    fun queueCapacityIsRespected() = runTest {
        val sink = RecordingSink(writeDelayMs = 50)
        val writer = testWriter(sink, queueCapacity = 3, pacingMode = PacingMode.DelayPaced, frameDelay = 1.milliseconds, scope = this)
        writer.start()
        // Fill the queue
        for (i in 1..3) writer.trySendFrame(byteArrayOf(i.toByte()))
        // 4th should fail (queue full)
        assertFalse(writer.trySendFrame(byteArrayOf(4)))
        writer.signalCompletion()
        writer.awaitCompletion(10.seconds)
        assertEquals(3, sink.written.size)
    }
}
