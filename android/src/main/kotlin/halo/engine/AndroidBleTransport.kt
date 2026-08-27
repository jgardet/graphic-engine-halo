package halo.engine

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Production Android transport for the Halo BLE service.
 *
 * [BluetoothGattChannel] converts callback-based BluetoothGatt operations into
 * suspendable operations. [AndroidBleTransport] serializes all writes and waits
 * for the data-channel ACK emitted by the official Halo data library.
 */
class AndroidBleTransport(
    private val channel: GattChannel,
    private val ackTimeoutMs: Long = 5_000,
) : HaloBleTransport {
    private val writeMutex = Mutex()

    override val maxLuaPayload: Int
        get() = channel.mtu.coerceAtMost(512) - 3
    override val maxDataPayload: Int
        get() = channel.mtu.coerceAtMost(512) - 4

    override suspend fun connect(name: String?) {
        channel.discoverAndEnableNotifications()
        channel.requestMtu(512)
    }

    override suspend fun disconnect() = channel.close()

    override suspend fun sendLua(lua: String) {
        val bytes = lua.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxLuaPayload) { "Lua command exceeds negotiated BLE payload" }
        writeMutex.withLock { channel.write(bytes) }
    }

    override suspend fun sendMessage(code: Int, payload: ByteArray) {
        require(code in 0..255)
        require(payload.size <= 65535)
        writeMutex.withLock {
            var offset = 0
            val firstCount = minOf(payload.size, maxDataPayload - 3)
            sendDataPacket(byteArrayOf(code.toByte(), (payload.size ushr 8).toByte(), payload.size.toByte()) + payload.copyOfRange(0, firstCount))
            offset = firstCount
            while (offset < payload.size) {
                val count = minOf(payload.size - offset, maxDataPayload - 1)
                sendDataPacket(byteArrayOf(code.toByte()) + payload.copyOfRange(offset, offset + count))
                offset += count
            }
        }
    }

    override suspend fun sendData(bytes: ByteArray) {
        require(bytes.size <= maxDataPayload)
        writeMutex.withLock { sendDataPacket(bytes) }
    }

    private suspend fun sendDataPacket(packet: ByteArray) {
        require(packet.size <= maxDataPayload)
        channel.write(byteArrayOf(DATA_MARKER) + packet)
        withTimeout(ackTimeoutMs) {
            while (true) {
                if (channel.notifications.receive().isAck()) return@withTimeout
            }
        }
    }

    private fun ByteArray.isAck(): Boolean = contentEquals(byteArrayOf(DATA_MARKER, 0x00, 0x00)) ||
        contentEquals(byteArrayOf(0x00, 0x00))

    companion object {
        const val DATA_MARKER: Byte = 0x01
    }
}

/** Callback-neutral interface used by AndroidBleTransport and instrumentation fakes. */
interface GattChannel {
    val mtu: Int
    val notifications: Channel<ByteArray>
    suspend fun discoverAndEnableNotifications()
    suspend fun requestMtu(desired: Int)
    suspend fun write(bytes: ByteArray)
    suspend fun close()
}

/** BluetoothGatt implementation of [GattChannel]. */
class BluetoothGattChannel(
    private val gatt: BluetoothGatt,
    private val callbacks: Callbacks,
) : GattChannel {
    private val writeResults = Channel<Int>(Channel.UNLIMITED)
    override val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    override var mtu: Int = 23
        private set

    init {
        callbacks.channel = this
    }

    class Callbacks : BluetoothGattCallback() {
        var channel: BluetoothGattChannel? = null

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED && channel == null) {
                channel = BluetoothGattChannel(gatt, this)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            channel?.serviceResults?.complete(status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            channel?.mtuResults?.complete(mtu to status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            channel?.writeResults?.trySend(status)
        }

        @Deprecated("Use the API 33 callback on Android 13+")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            channel?.notifications?.trySend(characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            channel?.notifications?.trySend(value.copyOf())
        }
    }

    private var serviceResults = CompletableDeferred<Int>()
    internal var mtuResults = CompletableDeferred<Pair<Int, Int>>()
    private val tx: BluetoothGattCharacteristic by lazy { requireCharacteristic(TX_UUID) }
    private val rx: BluetoothGattCharacteristic by lazy { requireCharacteristic(RX_UUID) }

    override suspend fun discoverAndEnableNotifications() {
        serviceResults = CompletableDeferred()
        check(gatt.discoverServices()) { "BluetoothGatt rejected service discovery" }
        check(withTimeout(5_000) { serviceResults.await() } == BluetoothGatt.GATT_SUCCESS) {
            "Halo service discovery failed"
        }
        val descriptor = rx.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            check(gatt.setCharacteristicNotification(rx, true)) { "Unable to enable Halo notifications" }
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            check(gatt.writeDescriptor(descriptor)) { "BluetoothGatt rejected notification descriptor write" }
            check(withTimeout(5_000) { writeResults.receive() } == BluetoothGatt.GATT_SUCCESS) {
                "Halo notification descriptor write failed"
            }
        }
    }

    override suspend fun requestMtu(desired: Int) {
        mtuResults = CompletableDeferred()
        if (Build.VERSION.SDK_INT >= 21) check(gatt.requestMtu(desired)) { "BluetoothGatt rejected MTU request" }
        val (negotiated, status) = withTimeout(5_000) { mtuResults.await() }
        check(status == BluetoothGatt.GATT_SUCCESS) { "Halo MTU negotiation failed: $status" }
        mtu = negotiated
    }

    override suspend fun write(bytes: ByteArray) {
        tx.value = bytes
        check(gatt.writeCharacteristic(tx)) { "BluetoothGatt rejected write" }
        val status = withTimeout(5_000) { writeResults.receive() }
        check(status == BluetoothGatt.GATT_SUCCESS) { "Halo write failed: $status" }
    }

    override suspend fun close() {
        gatt.disconnect()
        gatt.close()
    }

    private fun requireCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        val service: BluetoothGattService = requireNotNull(gatt.getService(SERVICE_UUID)) { "Halo service not discovered" }
        return requireNotNull(service.getCharacteristic(uuid)) { "Halo characteristic not discovered: $uuid" }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7a230001-5475-a6a4-654c-8431f6ad49c4")
        val TX_UUID: UUID = UUID.fromString("7a230002-5475-a6a4-654c-8431f6ad49c4")
        val RX_UUID: UUID = UUID.fromString("7a230003-5475-a6a4-654c-8431f6ad49c4")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
