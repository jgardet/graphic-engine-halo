package halo.engine

/**
 * Raised when the BLE transport disconnects or otherwise fails while a
 * [HaloSession] operation is in flight.
 */
class HaloTransportException(message: String) : RuntimeException(message)
