package halo.engine.transport

import halo.engine.HaloProtocol

/**
 * Exact host-to-device packet framing and reassembly, matching the
 * `he_runtime.lua` `receive_data()` function (lines 34-70).
 *
 * Wire format:
 * - First packet:    `[code(1)] [sizeHi(1)] [sizeLo(1)] [payload_chunk...]`
 * - Continuation:    `[code(1)] [payload_chunk...]`
 * - ACK (success):   `\x01\x00\x00` (sent by device after each packet)
 * - ACK (failure):   `\x01\x00\x01`
 * - Error:           `[ERROR_CODE(1)] [message_string...]`
 *
 * Constraints:
 * - One pending message per code (a new first packet for the same code
 *   replaces any in-progress reassembly).
 * - Maximum reassembled message size: 32 KiB ([MAX_MESSAGE_BYTES]).
 * - Message length is big-endian 16-bit.
 *
 * This is platform-neutral: the host side fragments, the device side
 * reassembles. Both directions share these types so canonical framing
 * vectors can be tested in pure JVM.
 */

/** Maximum reassembled message size (he_runtime.lua `MAX_DATA_BYTES`). */
const val MAX_MESSAGE_BYTES = 32_768

/** ACK payload sent by the device after each successfully received packet. */
val ACK_SUCCESS = byteArrayOf(0x01, 0x00, 0x00)

/** ACK payload sent by the device after a rejected packet. */
val ACK_FAILURE = byteArrayOf(0x01, 0x00, 0x01)

/**
 * Fragment a message into packets suitable for writing to the LUA RX
 * characteristic (after the `0x01` data marker is prepended by the transport).
 *
 * Each packet is sized to fit within [maxPayload] bytes (the usable ATT
 * payload, i.e. MTU - 3 - 1 for the data marker).
 *
 * Returns a list of packets where:
 * - Packet 0: `[code] [sizeHi] [sizeLo] [payload[0..chunk]]`
 * - Packet N: `[code] [payload[offset..chunk]]`
 */
fun fragmentMessage(code: Int, payload: ByteArray, maxPayload: Int): List<ByteArray> {
    require(code in 0..255) { "message code must be a single byte" }
    require(payload.size <= 65535) { "payload exceeds 16-bit length field" }
    require(maxPayload >= 4) { "maxPayload must be at least 4 bytes (code + size + 1 data byte)" }

    val firstDataBytes = maxPayload - 3  // code + 2 size bytes
    if (payload.isEmpty()) {
        return listOf(byteArrayOf(code.toByte(), 0, 0))
    }
    if (payload.size <= firstDataBytes) {
        return listOf(
            byteArrayOf(
                code.toByte(),
                (payload.size ushr 8).toByte(),
                payload.size.toByte(),
            ) + payload
        )
    }

    val packets = mutableListOf<ByteArray>()
    // First packet
    packets.add(
        byteArrayOf(
            code.toByte(),
            (payload.size ushr 8).toByte(),
            payload.size.toByte(),
        ) + payload.copyOfRange(0, firstDataBytes)
    )
    var offset = firstDataBytes
    // Continuation packets
    val contDataBytes = maxPayload - 1  // code only
    while (offset < payload.size) {
        val chunkLen = minOf(payload.size - offset, contDataBytes)
        packets.add(
            byteArrayOf(code.toByte()) + payload.copyOfRange(offset, offset + chunkLen)
        )
        offset += chunkLen
    }
    return packets
}

/**
 * Device-side reassembly state machine, mirroring `he_runtime.lua`'s
 * `pending` table and `receive_data()` function.
 *
 * Feed raw packet bytes (after stripping the `0x01` data marker) via
 * [receive]. When a message is complete, it appears in [completed].
 * If a protocol error occurs, [errors] receives an [HrpFramingError].
 */
class MessageReassembler {

    /** A fully reassembled message: code + payload. */
    data class Message(val code: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Message) return false
            return code == other.code && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * code + payload.contentHashCode()
    }

    /** A protocol error detected during reassembly. */
    data class FramingError(val code: Int, val message: String)

    private data class PendingEntry(
        var size: Int,
        var received: Int,
        val chunks: MutableList<ByteArray>,
    )

    private val pending = mutableMapOf<Int, PendingEntry?>()

    /** Completed messages, in completion order. Cleared by [drainCompleted]. */
    private val completed = mutableListOf<Message>()

    /** Framing errors, in detection order. Cleared by [drainErrors]. */
    private val errors = mutableListOf<FramingError>()

    /**
     * Process one raw packet (after the `0x01` data marker has been stripped).
     *
     * Returns the ACK bytes that should be sent back to the host:
     * [ACK_SUCCESS] on normal receipt, [ACK_FAILURE] on a protocol error.
     * If an error occurs, an [FramingError] is also queued in [errors].
     */
    fun receive(packet: ByteArray): ByteArray {
        if (packet.isEmpty()) return ACK_SUCCESS

        val code = packet[0].toInt() and 0xFF
        var entry = pending[code]
        if (entry == null) {
            entry = PendingEntry(0, 0, mutableListOf())
            pending[code] = entry
        }

        if (entry.received == 0) {
            // First packet: [code] [sizeHi] [sizeLo] [data...]
            if (packet.size < 3) {
                pending[code] = null
                val err = FramingError(HaloProtocol.ERROR, "invalid first packet")
                errors.add(err)
                return ACK_FAILURE
            }
            val declaredSize = ((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF)
            if (declaredSize > MAX_MESSAGE_BYTES) {
                pending[code] = null
                val err = FramingError(HaloProtocol.ERROR, "message exceeds runtime limit")
                errors.add(err)
                return ACK_FAILURE
            }
            entry.size = declaredSize
            val data = packet.copyOfRange(3, packet.size)
            entry.chunks.add(data)
            entry.received = data.size
        } else {
            // Continuation: [code] [data...]
            val data = packet.copyOfRange(1, packet.size)
            entry.chunks.add(data)
            entry.received += data.size
        }

        if (entry.received == entry.size) {
            val payload = entry.chunks.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
            completed.add(Message(code, payload))
            pending[code] = null
        } else if (entry.received > entry.size) {
            pending[code] = null
            val err = FramingError(HaloProtocol.ERROR, "message length overflow")
            errors.add(err)
            return ACK_FAILURE
        }

        return ACK_SUCCESS
    }

    /** Drain and return all completed messages. */
    fun drainCompleted(): List<Message> {
        val result = completed.toList()
        completed.clear()
        return result
    }

    /** Drain and return all framing errors. */
    fun drainErrors(): List<FramingError> {
        val result = errors.toList()
        errors.clear()
        return result
    }

    /** True if a message for [code] is currently being reassembled. */
    fun isPending(code: Int): Boolean = pending[code] != null

    /** Clear all pending state (e.g. on disconnect). */
    fun reset() {
        pending.clear()
        completed.clear()
        errors.clear()
    }
}
