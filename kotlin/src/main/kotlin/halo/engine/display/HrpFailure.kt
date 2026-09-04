package halo.engine.display

/**
 * Phase 1 E1-04: Typed HRP parse/render failures with offsets and operation metadata.
 *
 * Replaces the old string-only [HrpRenderer.HrpRenderException] with a
 * structured failure type that carries:
 * - a stable [category] for programmatic switching,
 * - the byte [offset] where the failure was detected,
 * - the [commandIndex] (0-based) being processed, or -1 for header-level failures,
 * - the [opcode] of the command being processed, or -1 if unknown,
 * - a human-readable [message].
 *
 * Callers can catch [HrpFailure] and map it to [com.nyooran.agent.senses.FailureCategory.PROTOCOL]
 * when bridging to the Phase 2 sense contract.
 */
sealed class HrpFailure(
    val category: Category,
    val offset: Int,
    val commandIndex: Int,
    val opcode: Int,
    message: String,
) : RuntimeException(message) {

    enum class Category {
        /** Header magic, reserved byte, or command count is invalid. */
        INVALID_HEADER,
        /** Command header or payload is truncated. */
        TRUNCATED,
        /** Bytes remain after all declared commands have been processed. */
        TRAILING_BYTES,
        /** Opcode is not recognized by this interpreter. */
        UNSUPPORTED_OPCODE,
        /** Command payload size does not match the opcode's expectation. */
        PAYLOAD_SIZE,
        /** Command payload content is invalid (out-of-range values, bad counts). */
        PAYLOAD_CONTENT,
        /** A referenced resource (e.g. sprite ID) does not exist. */
        MISSING_RESOURCE,
        /** A display operation failed (out of bounds, bad color, etc.). */
        DISPLAY_ERROR,
    }

    /** Header-level failure (before any command is parsed). */
    class Header(
        category: Category,
        offset: Int,
        message: String,
    ) : HrpFailure(category, offset, -1, -1, message)

    /** Failure while parsing or executing a specific command. */
    class Command(
        category: Category,
        offset: Int,
        commandIndex: Int,
        opcode: Int,
        message: String,
    ) : HrpFailure(category, offset, commandIndex, opcode, message)

    override fun toString(): String {
        val opStr = if (opcode >= 0) "0x${opcode.toString(16)}" else "—"
        return "HrpFailure(category=$category, offset=$offset, command=$commandIndex, opcode=$opStr): $message"
    }
}
