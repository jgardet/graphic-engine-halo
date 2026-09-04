package halo.engine.transport

import halo.engine.HaloProtocol
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaultInjectingTransportTest {

    private fun firstPacket(code: Int, payload: ByteArray): ByteArray {
        val size = payload.size
        return byteArrayOf(code.toByte(), (size ushr 8).toByte(), size.toByte()) + payload
    }

    @Test
    fun normalPacketReturnsAckSuccess() = runTest {
        val transport = FaultInjectingTransport()
        val ack = transport.sendPacket(firstPacket(0x60, byteArrayOf(1, 2, 3)))
        assertContentEquals(ACK_SUCCESS, ack)
        assertEquals(1, transport.packetCount())
    }

    @Test
    fun droppedPacketReturnsEmptyAck() = runTest {
        val transport = FaultInjectingTransport()
        transport.queueFault(FaultAction.DropNextPacket())
        val ack = transport.sendPacket(firstPacket(0x60, byteArrayOf(1, 2, 3)))
        assertContentEquals(ByteArray(0), ack, "dropped packet should return empty ACK")
        // The reassembler should still have the message (device side processed it)
        assertEquals(1, transport.drainReassembledMessages().size)
    }

    @Test
    fun dropMultiplePackets() = runTest {
        val transport = FaultInjectingTransport()
        transport.queueFault(FaultAction.DropNextPacket(count = 2))
        // First packet: dropped
        val ack1 = transport.sendPacket(firstPacket(0x60, byteArrayOf(1)))
        assertContentEquals(ByteArray(0), ack1)
        // Second packet: also dropped (fault re-queued with count-1)
        val ack2 = transport.sendPacket(firstPacket(0x60, byteArrayOf(2)))
        assertContentEquals(ByteArray(0), ack2)
        // Third packet: normal
        val ack3 = transport.sendPacket(firstPacket(0x60, byteArrayOf(3)))
        assertContentEquals(ACK_SUCCESS, ack3)
    }

    @Test
    fun delayedPacketEventuallyReturnsAck() = runTest {
        val transport = FaultInjectingTransport()
        transport.queueFault(FaultAction.DelayNextPacket(delayMs = 50))
        val ack = transport.sendPacket(firstPacket(0x60, byteArrayOf(1, 2)))
        assertContentEquals(ACK_SUCCESS, ack)
        assertEquals(1, transport.drainReassembledMessages().size)
    }

    @Test
    fun duplicatePacketProducesTwoReassembledMessages() = runTest {
        val transport = FaultInjectingTransport()
        transport.queueFault(FaultAction.DuplicateNextPacket)
        transport.sendPacket(firstPacket(0x60, byteArrayOf(1, 2, 3)))
        // The first packet completes the message (size=3, 3 data bytes).
        // The duplicate is a second complete first-packet, producing a
        // second reassembled message with the same payload.
        val msgs = transport.drainReassembledMessages()
        assertEquals(2, msgs.size)
        assertContentEquals(byteArrayOf(1, 2, 3), msgs[0].payload)
        assertContentEquals(byteArrayOf(1, 2, 3), msgs[1].payload)
    }

    @Test
    fun malformedPacketCorruptsPayload() = runTest {
        val transport = FaultInjectingTransport()
        transport.queueFault(FaultAction.MalformNextPacket(bitFlipMask = 0xFF))
        transport.sendPacket(firstPacket(0x60, byteArrayOf(0xAA.toByte(), 0xBB.toByte())))
        // The last byte of the packet should be flipped
        val msgs = transport.drainReassembledMessages()
        assertEquals(1, msgs.size)
        // Original payload was [0xAA, 0xBB], but the last byte of the
        // whole packet (which is 0xBB) gets XORed with 0xFF → 0x44
        assertEquals(0x44, msgs[0].payload[1].toInt() and 0xFF)
    }

    @Test
    fun rejectedPacketReturnsAckFailure() = runTest {
        val transport = FaultInjectingTransport()
        transport.queueFault(FaultAction.RejectNextPacket)
        val ack = transport.sendPacket(firstPacket(0x60, byteArrayOf(1, 2)))
        assertContentEquals(ACK_FAILURE, ack)
        // Device still processed the packet
        assertEquals(1, transport.drainReassembledMessages().size)
    }

    @Test
    fun disconnectOnPacketThrows() = runTest {
        val transport = FaultInjectingTransport()
        transport.queueFault(FaultAction.DisconnectOnNextPacket)
        assertFailsWith<IllegalStateException> {
            transport.sendPacket(firstPacket(0x60, byteArrayOf(1)))
        }
        assertFalse(transport.isConnected)
    }

    @Test
    fun customAckReturned() = runTest {
        val customAck = byteArrayOf(0x02, 0x03, 0x04)
        val transport = FaultInjectingTransport()
        transport.queueFault(FaultAction.CustomAck(customAck))
        val ack = transport.sendPacket(firstPacket(0x60, byteArrayOf(1)))
        assertContentEquals(customAck, ack)
    }

    @Test
    fun noQueuedFaultsAfterProcessing() = runTest {
        val transport = FaultInjectingTransport()
        transport.queueFault(FaultAction.DropNextPacket())
        assertFalse(transport.hasNoQueuedFaults())
        transport.sendPacket(firstPacket(0x60, byteArrayOf(1)))
        assertTrue(transport.hasNoQueuedFaults())
    }

    @Test
    fun partialStreamStateClearedOnReset() = runTest {
        val transport = FaultInjectingTransport()
        // Start a multi-packet message (send first packet only)
        transport.sendPacket(byteArrayOf(0x60, 0x00, 0x10, 0xAA.toByte()))
        // The reassembler should have a pending entry
        val errors = transport.drainErrors()
        assertEquals(0, errors.size)  // no errors yet
        // Reset should clear the pending state
        transport.reset()
        // Next operation should start cleanly
        transport.sendPacket(firstPacket(0x60, byteArrayOf(1, 2, 3)))
        val msgs = transport.drainReassembledMessages()
        assertEquals(1, msgs.size)
        assertContentEquals(byteArrayOf(1, 2, 3), msgs[0].payload)
    }

    @Test
    fun nextOperationStartsCleanlyAfterFault() = runTest {
        val transport = FaultInjectingTransport()
        // First operation: rejected
        transport.queueFault(FaultAction.RejectNextPacket)
        transport.sendPacket(firstPacket(0x60, byteArrayOf(1)))
        assertEquals(1, transport.drainReassembledMessages().size)
        assertTrue(transport.hasNoQueuedFaults())

        // Second operation: should succeed normally
        val ack = transport.sendPacket(firstPacket(0x30, byteArrayOf(2)))
        assertContentEquals(ACK_SUCCESS, ack)
        val msgs = transport.drainReassembledMessages()
        assertEquals(1, msgs.size)
        assertEquals(0x30, msgs[0].code)
    }

    @Test
    fun injectedMessagesAreDrained() {
        val transport = FaultInjectingTransport()
        transport.injectMessage(HaloProtocol.BUTTON, byteArrayOf(1))
        transport.injectMessage(HaloProtocol.TAP, byteArrayOf(2))
        val msgs = transport.drainInjectedMessages()
        assertEquals(2, msgs.size)
        assertEquals(HaloProtocol.BUTTON, msgs[0].code)
        assertEquals(HaloProtocol.TAP, msgs[1].code)
        // Second drain should be empty
        assertEquals(0, transport.drainInjectedMessages().size)
    }

    @Test
    fun sendAfterDisconnectThrows() = runTest {
        val transport = FaultInjectingTransport()
        transport.disconnect()
        assertFailsWith<IllegalStateException> {
            transport.sendPacket(firstPacket(0x60, byteArrayOf(1)))
        }
    }

    @Test
    fun queueMultipleFaults() = runTest {
        val transport = FaultInjectingTransport()
        transport.queueFaults(
            FaultAction.DropNextPacket(),
            FaultAction.RejectNextPacket,
        )
        // First packet: dropped
        val ack1 = transport.sendPacket(firstPacket(0x60, byteArrayOf(1)))
        assertContentEquals(ByteArray(0), ack1)
        // Second packet: rejected
        val ack2 = transport.sendPacket(firstPacket(0x60, byteArrayOf(2)))
        assertContentEquals(ACK_FAILURE, ack2)
        // Third packet: normal
        val ack3 = transport.sendPacket(firstPacket(0x60, byteArrayOf(3)))
        assertContentEquals(ACK_SUCCESS, ack3)
    }
}
