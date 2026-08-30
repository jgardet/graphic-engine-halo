package halo.engine

/**
 * A normalized, transport-agnostic message received from the Halo device.
 *
 * This type lives in the shared [kotlin] module so that `MockBleTransport`,
 * `AndroidBleTransport`, and any future transport can expose the same
 * message stream to `HaloSession` and other streaming consumers.
 */
data class HaloMessage(
    val code: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || other !is HaloMessage) return false
        return code == other.code && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        return 31 * code + payload.contentHashCode()
    }
}
