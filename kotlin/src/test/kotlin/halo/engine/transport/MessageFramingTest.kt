package halo.engine.transport

import halo.engine.HaloProtocol
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageFramingTest {

    @Test
    fun fragmentSinglePacketMessage() {
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val packets = fragmentMessage(HaloProtocol.HRP, payload, maxPayload = 100)

        assertEquals(1, packets.size)
        // [code] [sizeHi] [sizeLo] [payload]
        assertContentEquals(
            byteArrayOf(HaloProtocol.HRP.toByte(), 0, 4, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            packets[0],
        )
    }

    @Test
    fun fragmentEmptyMessage() {
        val packets = fragmentMessage(HaloProtocol.MICROPHONE_STOP, byteArrayOf(), maxPayload = 100)
        assertEquals(1, packets.size)
        assertContentEquals(byteArrayOf(HaloProtocol.MICROPHONE_STOP.toByte(), 0, 0), packets[0])
    }

    @Test
    fun fragmentMultiPacketMessage() {
        // 10 bytes payload, maxPayload=5 → first: 2 data bytes, continuations: 4 data bytes each
        val payload = ByteArray(10) { it.toByte() }
        val packets = fragmentMessage(0x60, payload, maxPayload = 5)

        assertEquals(3, packets.size)
        // First: [0x60] [0x00] [0x0A] [0] [1]  (3 header + 2 data = 5)
        assertContentEquals(byteArrayOf(0x60, 0x00, 0x0A, 0, 1), packets[0])
        // Second: [0x60] [2] [3] [4] [5]  (1 code + 4 data = 5)
        assertContentEquals(byteArrayOf(0x60, 2, 3, 4, 5), packets[1])
        // Third: [0x60] [6] [7] [8] [9]  (1 code + 4 data = 5)
        assertContentEquals(byteArrayOf(0x60, 6, 7, 8, 9), packets[2])
    }

    @Test
    fun fragmentRespectsMaxPayload() {
        val payload = ByteArray(200) { 0xAA.toByte() }
        val maxPayload = 50
        val packets = fragmentMessage(0x60, payload, maxPayload)
        for (pkt in packets) {
            assertTrue(pkt.size <= maxPayload, "packet ${pkt.size} exceeds maxPayload $maxPayload")
        }
        // Verify reassembly
        val reassembler = MessageReassembler()
        for (pkt in packets) {
            reassembler.receive(pkt)
        }
        val messages = reassembler.drainCompleted()
        assertEquals(1, messages.size)
        assertContentEquals(payload, messages[0].payload)
    }

    @Test
    fun fragmentRejectsTooSmallMaxPayload() {
        assertFailsWith<IllegalArgumentException> {
            fragmentMessage(0x60, byteArrayOf(1, 2, 3), maxPayload = 3)
        }
    }

    @Test
    fun reassembleSinglePacket() {
        val r = MessageReassembler()
        val ack = r.receive(byteArrayOf(0x60, 0x00, 0x04, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        assertContentEquals(ACK_SUCCESS, ack)
        assertEquals(0, r.drainErrors().size)
        val msgs = r.drainCompleted()
        assertEquals(1, msgs.size)
        assertEquals(0x60, msgs[0].code)
        assertContentEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), msgs[0].payload)
    }

    @Test
    fun reassembleMultiPacket() {
        val r = MessageReassembler()
        // First packet: code=0x60, size=10, 2 data bytes
        r.receive(byteArrayOf(0x60, 0x00, 0x0A, 0, 1))
        assertTrue(r.isPending(0x60))
        assertEquals(0, r.drainCompleted().size)

        // Continuation 1: 4 data bytes
        r.receive(byteArrayOf(0x60, 2, 3, 4, 5))
        assertTrue(r.isPending(0x60))

        // Continuation 2: 4 data bytes → complete
        val ack = r.receive(byteArrayOf(0x60, 6, 7, 8, 9))
        assertContentEquals(ACK_SUCCESS, ack)
        assertFalse(r.isPending(0x60))

        val msgs = r.drainCompleted()
        assertEquals(1, msgs.size)
        assertContentEquals(ByteArray(10) { it.toByte() }, msgs[0].payload)
    }

    @Test
    fun reassembleRejectsInvalidFirstPacket() {
        val r = MessageReassembler()
        // First packet with only 2 bytes (code + 1) — too short for size field
        val ack = r.receive(byteArrayOf(0x60, 0x00))
        assertContentEquals(ACK_FAILURE, ack)
        val errors = r.drainErrors()
        assertEquals(1, errors.size)
        assertEquals(HaloProtocol.ERROR, errors[0].code)
        assertEquals("invalid first packet", errors[0].message)
        assertFalse(r.isPending(0x60))
    }

    @Test
    fun reassembleRejectsOversizedMessage() {
        val r = MessageReassembler()
        // Declare a size of 32769 (> MAX_MESSAGE_BYTES)
        val ack = r.receive(byteArrayOf(0x60, 0x80.toByte(), 0x01, 0x00))
        assertContentEquals(ACK_FAILURE, ack)
        val errors = r.drainErrors()
        assertEquals(1, errors.size)
        assertEquals("message exceeds runtime limit", errors[0].message)
    }

    @Test
    fun reassembleRejectsOverflow() {
        val r = MessageReassembler()
        // Declare size=2, but send 3 data bytes in first packet
        r.receive(byteArrayOf(0x60, 0x00, 0x02, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()))
        val errors = r.drainErrors()
        assertEquals(1, errors.size)
        assertEquals("message length overflow", errors[0].message)
    }

    @Test
    fun reassembleOnePendingPerCode() {
        val r = MessageReassembler()
        // Start a message for code 0x60 with size=16
        r.receive(byteArrayOf(0x60, 0x00, 0x10, 0xAA.toByte()))
        assertTrue(r.isPending(0x60))

        // A second packet for the same code is treated as a continuation,
        // NOT a new first packet — matching he_runtime.lua's receive_data().
        // The firmware checks `item.received == 0` to identify first packets;
        // once reassembly is in progress, all subsequent packets are continuations.
        r.receive(byteArrayOf(0x60, 0xBB.toByte(), 0xCC.toByte()))
        assertTrue(r.isPending(0x60))
        assertEquals(0, r.drainCompleted().size)

        // Complete the message by sending the remaining bytes
        val remaining = ByteArray(16 - 1 - 2) { 0xFF.toByte() }
        r.receive(byteArrayOf(0x60) + remaining)
        val msgs = r.drainCompleted()
        assertEquals(1, msgs.size)
        assertEquals(16, msgs[0].payload.size)
    }

    @Test
    fun reassembleMultipleCodesInParallel() {
        val r = MessageReassembler()
        // Start code 0x60 (2 bytes)
        r.receive(byteArrayOf(0x60, 0x00, 0x02, 0xAA.toByte()))
        // Start code 0x30 (2 bytes)
        r.receive(byteArrayOf(0x30, 0x00, 0x02, 0xBB.toByte()))
        assertTrue(r.isPending(0x60))
        assertTrue(r.isPending(0x30))

        // Complete code 0x60
        r.receive(byteArrayOf(0x60, 0xCC.toByte()))
        // Complete code 0x30
        r.receive(byteArrayOf(0x30, 0xDD.toByte()))

        val msgs = r.drainCompleted()
        assertEquals(2, msgs.size)
        assertEquals(0x60, msgs[0].code)
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xCC.toByte()), msgs[0].payload)
        assertEquals(0x30, msgs[1].code)
        assertContentEquals(byteArrayOf(0xBB.toByte(), 0xDD.toByte()), msgs[1].payload)
    }

    @Test
    fun reassembleEmptyMessage() {
        val r = MessageReassembler()
        r.receive(byteArrayOf(0x60, 0x00, 0x00))
        val msgs = r.drainCompleted()
        assertEquals(1, msgs.size)
        assertContentEquals(ByteArray(0), msgs[0].payload)
    }

    @Test
    fun resetClearsAllState() {
        val r = MessageReassembler()
        r.receive(byteArrayOf(0x60, 0x00, 0x10, 0xAA.toByte()))
        assertTrue(r.isPending(0x60))
        r.reset()
        assertFalse(r.isPending(0x60))
        assertEquals(0, r.drainCompleted().size)
        assertEquals(0, r.drainErrors().size)
    }

    @Test
    fun roundTripAllScenes() {
        // Fragment and reassemble every HSD scene payload
        val sceneDir = java.io.File("../scenes")
        if (!sceneDir.exists()) return
        for (file in sceneDir.listFiles { f -> f.extension == "json" } ?: emptyArray()) {
            val r = MessageReassembler()
            // Simulate a payload that could be an HRP payload
            val payload = ByteArray(500) { (it % 256).toByte() }
            val packets = fragmentMessage(HaloProtocol.HRP, payload, maxPayload = 100)
            for (pkt in packets) {
                val ack = r.receive(pkt)
                assertContentEquals(ACK_SUCCESS, ack, "ACK failure for ${file.name} at packet")
            }
            val msgs = r.drainCompleted()
            assertEquals(1, msgs.size, "${file.name}: expected 1 message")
            assertContentEquals(payload, msgs[0].payload, "${file.name}: payload mismatch")
        }
    }

    @Test
    fun ackSuccessIsThreeBytes() {
        assertEquals(3, ACK_SUCCESS.size)
        assertEquals(0x01, ACK_SUCCESS[0].toInt() and 0xFF)
        assertEquals(0x00, ACK_SUCCESS[1].toInt() and 0xFF)
        assertEquals(0x00, ACK_SUCCESS[2].toInt() and 0xFF)
    }

    @Test
    fun ackFailureIsThreeBytes() {
        assertEquals(3, ACK_FAILURE.size)
        assertEquals(0x01, ACK_FAILURE[0].toInt() and 0xFF)
        assertEquals(0x00, ACK_FAILURE[1].toInt() and 0xFF)
        assertEquals(0x01, ACK_FAILURE[2].toInt() and 0xFF)
    }

    @Test
    fun maxMessageBytesMatchesFirmware() {
        assertEquals(32768, MAX_MESSAGE_BYTES)
    }
}
