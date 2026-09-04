package halo.engine.transport

import kotlinx.coroutines.delay
import halo.engine.HaloMessage

/**
 * Deterministic transport fault injection for testing.
 *
 * Wraps a [FaultableTransport] and applies queued [FaultAction]s to
 * simulate real-world BLE failure modes: dropped, delayed, duplicate,
 * malformed, and rejected packets. This lets tests verify that the host
 * surfaces errors immediately, clears partial stream state, and starts
 * the next operation cleanly.
 *
 * Usage:
 * ```
 * val transport = FaultInjectingTransport()
 * transport.queueFault(FaultAction.DropNextPacket())
 * transport.sendPacket(bytes)  // dropped silently
 * ```
 */

/** A transport that supports fault injection. */
interface FaultableTransport {
    /** Send a raw packet (after the 0x01 data marker). */
    suspend fun sendPacket(packet: ByteArray): ByteArray

    /** Inject a device-to-host message (simulated notification). */
    fun injectMessage(code: Int, payload: ByteArray)

    /** True if the transport is currently connected. */
    val isConnected: Boolean

    /** Disconnect the transport. */
    fun disconnect()
}

/** A fault action to apply to the next packet or operation. */
sealed interface FaultAction {
    /** Drop the next packet silently (no ACK, no error). */
    data class DropNextPacket(val count: Int = 1) : FaultAction

    /** Delay the next packet's ACK by [delayMs] milliseconds. */
    data class DelayNextPacket(val delayMs: Long) : FaultAction

    /** Duplicate the next packet (send it twice). */
    data object DuplicateNextPacket : FaultAction

    /** Malform the next packet's payload (flip bits). */
    data class MalformNextPacket(val bitFlipMask: Int = 0xFF) : FaultAction

    /** Reject the next packet with ACK_FAILURE. */
    data object RejectNextPacket : FaultAction

    /** Force a disconnect on the next packet. */
    data object DisconnectOnNextPacket : FaultAction

    /** Return a custom ACK instead of the normal success. */
    data class CustomAck(val ack: ByteArray) : FaultAction
}

/**
 * Fault-injecting transport for deterministic failure testing.
 *
 * By default, acts as a loopback: [sendPacket] passes the packet to a
 * [MessageReassembler] and returns [ACK_SUCCESS]. When faults are queued
 * via [queueFault], the next packet(s) are affected.
 */
class FaultInjectingTransport(
    private val reassembler: MessageReassembler = MessageReassembler(),
) : FaultableTransport {

    private val faultQueue = ArrayDeque<FaultAction>()
    private val injectedMessages = mutableListOf<HaloMessage>()
    private var connected = true
    private var packetCount = 0

    /** Queue a fault action for the next packet. */
    fun queueFault(action: FaultAction) {
        faultQueue.addLast(action)
    }

    /** Queue multiple fault actions. */
    fun queueFaults(vararg actions: FaultAction) {
        for (action in actions) faultQueue.addLast(action)
    }

    override var isConnected: Boolean = true
        private set

    override fun disconnect() {
        connected = false
        isConnected = false
        reassembler.reset()
    }

    override fun injectMessage(code: Int, payload: ByteArray) {
        injectedMessages.add(HaloMessage(code, payload))
    }

    /** Drain and return injected device-to-host messages. */
    fun drainInjectedMessages(): List<HaloMessage> {
        val result = injectedMessages.toList()
        injectedMessages.clear()
        return result
    }

    /** Drain and return reassembled messages. */
    fun drainReassembledMessages(): List<MessageReassembler.Message> {
        return reassembler.drainCompleted()
    }

    /** Drain and return framing errors. */
    fun drainErrors(): List<MessageReassembler.FramingError> {
        return reassembler.drainErrors()
    }

    override suspend fun sendPacket(packet: ByteArray): ByteArray {
        if (!connected) throw IllegalStateException("transport is disconnected")

        val fault = faultQueue.removeFirstOrNull()
        packetCount++

        return when (fault) {
            is FaultAction.DropNextPacket -> {
                // Drop silently: still feed to reassembler but return no ACK
                // (in reality, the packet would vanish; the host would time out)
                repeat(fault.count - 1) {
                    faultQueue.addFirst(FaultAction.DropNextPacket(1))
                }
                reassembler.receive(packet)  // still process on "device" side
                // Return a "no ACK" sentinel — the caller should treat this as a timeout
                byteArrayOf()
            }

            is FaultAction.DelayNextPacket -> {
                delay(fault.delayMs)
                reassembler.receive(packet)
                ACK_SUCCESS
            }

            is FaultAction.DuplicateNextPacket -> {
                reassembler.receive(packet)
                reassembler.receive(packet)  // duplicate
                ACK_SUCCESS
            }

            is FaultAction.MalformNextPacket -> {
                val malformed = packet.copyOf()
                if (malformed.isNotEmpty()) {
                    val lastIdx = malformed.lastIndex
                    malformed[lastIdx] = (malformed[lastIdx].toInt() xor fault.bitFlipMask).toByte()
                }
                reassembler.receive(malformed)
                ACK_SUCCESS
            }

            is FaultAction.RejectNextPacket -> {
                reassembler.receive(packet)  // device still processes it
                ACK_FAILURE
            }

            is FaultAction.DisconnectOnNextPacket -> {
                disconnect()
                throw IllegalStateException("disconnected during send")
            }

            is FaultAction.CustomAck -> {
                reassembler.receive(packet)
                fault.ack
            }

            null -> {
                reassembler.receive(packet)
                ACK_SUCCESS
            }
        }
    }

    /** Total number of packets sent (including faulted ones). */
    fun packetCount(): Int = packetCount

    /** True if no faults are queued. */
    fun hasNoQueuedFaults(): Boolean = faultQueue.isEmpty()

    /** Reset all state (faults, messages, reassembler). */
    fun reset() {
        faultQueue.clear()
        injectedMessages.clear()
        reassembler.reset()
        packetCount = 0
        connected = true
        isConnected = true
    }
}
