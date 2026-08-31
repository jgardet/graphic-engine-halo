package halo.engine.transport

import halo.engine.HaloProtocol
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityStateMachineTest {

    private fun messages(events: List<DeviceEvent>): List<DeviceEvent.Message> =
        events.filterIsInstance<DeviceEvent.Message>()

    @Test
    fun bootEmitsStatusAndReadyText() {
        val machine = CapabilityStateMachine()
        machine.boot()
        val events = machine.drainEvents()
        assertEquals(2, events.size)
        val status = events[0] as DeviceEvent.Message
        assertEquals(HaloProtocol.STATUS, status.code)
        assertEquals("HRP1;primitives,sprites,click,tap,mic,speaker,photo,battery", status.payload.toString(Charsets.UTF_8))
        val text = events[1] as DeviceEvent.Text
        assertEquals("Halo Engine v2 ready", text.value)
    }

    @Test
    fun microphoneStartEmitsChunksThenFinal() {
        val config = CapabilityConfig(
            micChunkBytes = ByteArray(4) { it.toByte() },
            micChunkCount = 3,
        )
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.MICROPHONE_START, ByteArray(0))
        assertTrue(machine.isMicStreaming())

        machine.tick()
        assertFalse(machine.isMicStreaming())

        val msgs = messages(machine.drainEvents())
        assertEquals(3, msgs.count { it.code == HaloProtocol.AUDIO_CHUNK })
        assertEquals(1, msgs.count { it.code == HaloProtocol.AUDIO_FINAL })
        val chunks = msgs.filter { it.code == HaloProtocol.AUDIO_CHUNK }
        assertContentEquals(ByteArray(4) { it.toByte() }, chunks[0].payload)
    }

    @Test
    fun microphoneStopEmitsFinalImmediately() {
        val config = CapabilityConfig(micChunkCount = 100)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.MICROPHONE_START, ByteArray(0))
        assertTrue(machine.isMicStreaming())

        machine.handleMessage(HaloProtocol.MICROPHONE_STOP, ByteArray(0))
        assertFalse(machine.isMicStreaming())

        val msgs = messages(machine.drainEvents())
        // After stop, the most recent event should be AUDIO_FINAL
        assertEquals(HaloProtocol.AUDIO_FINAL, msgs.last().code)
    }

    @Test
    fun microphoneStopAfterPartialChunks() {
        val config = CapabilityConfig(
            micChunkBytes = ByteArray(2) { 0xAA.toByte() },
            micChunkCount = 100,
        )
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.MICROPHONE_START, ByteArray(0))
        machine.tick()
        assertTrue(machine.isMicStreaming())

        machine.handleMessage(HaloProtocol.MICROPHONE_STOP, ByteArray(0))
        assertFalse(machine.isMicStreaming())

        // After stop, tick should not emit more chunks
        val beforeCount = messages(machine.drainEvents()).size
        machine.tick()
        val afterCount = messages(machine.drainEvents()).size
        assertEquals(0, afterCount)  // no new events after stop+tick
    }

    @Test
    fun photoCaptureEmitsChunksThenFinal() {
        val photoData = ByteArray(500) { (it % 256).toByte() }
        val config = CapabilityConfig(photoData = photoData, photoChunkSize = 200)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.CAPTURE_PHOTO, ByteArray(0))
        assertTrue(machine.isPhotoPending())

        machine.tick()
        assertFalse(machine.isPhotoPending())

        val msgs = messages(machine.drainEvents())
        // 500 bytes / 200 chunk = 3 chunks (200, 200, 100) + final
        assertEquals(3, msgs.count { it.code == HaloProtocol.PHOTO_JPEG })
        assertEquals(1, msgs.count { it.code == HaloProtocol.PHOTO_FINAL })

        val reassembled = msgs.filter { it.code == HaloProtocol.PHOTO_JPEG }
            .fold(ByteArray(0)) { acc, msg -> acc + msg.payload }
        assertContentEquals(photoData, reassembled)
    }

    @Test
    fun photoCaptureWhileBusyIsIgnored() {
        val config = CapabilityConfig(photoData = ByteArray(1000), photoChunkSize = 100)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.CAPTURE_PHOTO, ByteArray(0))
        assertTrue(machine.isPhotoPending())

        machine.handleMessage(HaloProtocol.CAPTURE_PHOTO, ByteArray(0))
        assertTrue(machine.isPhotoPending())
    }

    @Test
    fun photoEmptyDataEmitsFinalImmediately() {
        val config = CapabilityConfig(photoData = ByteArray(0))
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.CAPTURE_PHOTO, ByteArray(0))
        machine.tick()
        assertFalse(machine.isPhotoPending())

        val msgs = messages(machine.drainEvents())
        assertEquals(HaloProtocol.PHOTO_FINAL, msgs[0].code)
    }

    @Test
    fun batteryResponseHasExactPayload() {
        val config = CapabilityConfig(batteryLevel = 85, batteryVoltage = 4200, batteryCharging = true)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.DEVICE_STATUS, ByteArray(0))

        val msgs = messages(machine.drainEvents())
        assertEquals(HaloProtocol.DEVICE_STATUS, msgs[0].code)
        // [level(1)] [voltageHi(1)] [voltageLo(1)] [charging(1)]
        assertContentEquals(byteArrayOf(85, 0x10.toByte(), 0x68, 1), msgs[0].payload)
    }

    @Test
    fun batteryNotChargingPayload() {
        val config = CapabilityConfig(batteryLevel = 50, batteryVoltage = 3500, batteryCharging = false)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.DEVICE_STATUS, ByteArray(0))

        val msgs = messages(machine.drainEvents())
        assertContentEquals(byteArrayOf(50, 0x0D.toByte(), 0xAC.toByte(), 0), msgs[0].payload)
    }

    @Test
    fun buttonEventEmitsCorrectCode() {
        val machine = CapabilityStateMachine()
        machine.buttonEvent(1)
        val msgs = messages(machine.drainEvents())
        assertEquals(HaloProtocol.BUTTON, msgs[0].code)
        assertContentEquals(byteArrayOf(1), msgs[0].payload)
    }

    @Test
    fun tapEventEmitsCorrectCode() {
        val machine = CapabilityStateMachine()
        machine.tapEvent(2)
        val msgs = messages(machine.drainEvents())
        assertEquals(HaloProtocol.TAP, msgs[0].code)
        assertContentEquals(byteArrayOf(2), msgs[0].payload)
    }

    @Test
    fun speakerStartAndStop() {
        val machine = CapabilityStateMachine()
        assertFalse(machine.isSpeakerActive())
        machine.handleMessage(HaloProtocol.SPEAKER_START, ByteArray(0))
        assertTrue(machine.isSpeakerActive())
        machine.handleMessage(HaloProtocol.SPEAKER_STOP, ByteArray(0))
        assertFalse(machine.isSpeakerActive())
    }

    @Test
    fun unknownMessageReturnsFalse() {
        val machine = CapabilityStateMachine()
        assertFalse(machine.handleMessage(0xFF, ByteArray(0)))
    }

    @Test
    fun resetClearsAllState() {
        val machine = CapabilityStateMachine(CapabilityConfig(micChunkCount = 100))
        machine.handleMessage(HaloProtocol.MICROPHONE_START, ByteArray(0))
        machine.handleMessage(HaloProtocol.SPEAKER_START, ByteArray(0))
        machine.handleMessage(HaloProtocol.CAPTURE_PHOTO, ByteArray(0))
        assertTrue(machine.isMicStreaming())
        assertTrue(machine.isSpeakerActive())
        assertTrue(machine.isPhotoPending())

        machine.reset()
        assertFalse(machine.isMicStreaming())
        assertFalse(machine.isSpeakerActive())
        assertFalse(machine.isPhotoPending())
    }

    @Test
    fun microphoneChunksAreBoundedPerTick() {
        // he_runtime.lua sends at most 10 chunks per tick
        val config = CapabilityConfig(micChunkBytes = ByteArray(1), micChunkCount = 25)
        val machine = CapabilityStateMachine(config)
        machine.handleMessage(HaloProtocol.MICROPHONE_START, ByteArray(0))
        machine.tick()
        assertTrue(machine.isMicStreaming())  // 15 more to go
        machine.tick()
        assertTrue(machine.isMicStreaming())  // 5 more
        machine.tick()
        assertFalse(machine.isMicStreaming())  // done
    }
}
