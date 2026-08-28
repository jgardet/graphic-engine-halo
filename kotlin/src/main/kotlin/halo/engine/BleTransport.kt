package halo.engine

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the host-to-Halo BLE link.
 *
 * Concrete implementations:
 * - `PythonBleTransport` delegates to the Python `brilliant-ble` CLI during
 *   development.
 * - `AndroidBleTransport` uses the Android BluetoothGatt API in the mobile app.
 */
interface HaloBleTransport {
    suspend fun connect(name: String? = null)
    suspend fun disconnect()
    suspend fun sendLua(lua: String)
    suspend fun sendMessage(code: Int, payload: ByteArray)
    suspend fun sendData(bytes: ByteArray)
    suspend fun sendAudioFrame(frame: ByteArray)
    val messages: Flow<HaloMessage>
    val supportsAudio: Boolean
    val maxLuaPayload: Int
    val maxDataPayload: Int
}
