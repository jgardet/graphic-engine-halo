package halo.engine

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val router: HaloNotificationRouter = HaloNotificationRouter(),
) : HaloBleTransport {
    private val writeMutex = Mutex()
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val notifications: SharedFlow<HaloNotification> = router.notifications
    override val messages: Flow<HaloMessage> =
        notifications
            .filterIsInstance<HaloNotification.Message>()
            .map { HaloMessage(it.code, it.payload) }

    init {
        notificationScope.launch {
            for (bytes in channel.notifications) router.route(bytes)
        }
        notificationScope.launch {
            for (connected in channel.connectionEvents) if (!connected) router.disconnected()
        }
    }

    override val maxLuaPayload: Int
        get() = channel.mtu.coerceAtMost(512) - 3
    override val maxDataPayload: Int
        get() = channel.mtu.coerceAtMost(512) - 4
    override val supportsAudio: Boolean
        get() = channel.supportsAudio

    override suspend fun connect(name: String?) {
        channel.discoverAndEnableNotifications()
        channel.requestMtu(512)
        channel.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    override suspend fun disconnect() {
        channel.close()
        notificationScope.cancel()
    }

    override suspend fun sendLua(lua: String) {
        val bytes = lua.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxLuaPayload) { "Lua command exceeds negotiated BLE payload" }
        writeMutex.withLock { channel.write(bytes) }
    }

    suspend fun sendControl(signal: Byte) {
        writeMutex.withLock { channel.write(byteArrayOf(signal)) }
    }

    suspend fun sendLuaAwaitResponse(lua: String, expected: String? = null, timeoutMs: Long = 5_000): String = coroutineScope {
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            notifications.filterIsInstance<HaloNotification.Text>().first {
                expected == null || it.value.trim() == expected
            }.value.trim()
        }
        sendLua(lua)
        withTimeout(timeoutMs) { response.await() }
    }

    /**
     * Sends a Lua command and waits for a STATUS [HaloNotification.Message] response.
     *
     * The device-side runtime uses `frame.bluetooth.send(string.char(STATUS) .. payload)`
     * for flow-control acknowledgements during file uploads and startup. The firmware
     * prefixes `frame.bluetooth.send()` output with `0x01` on LUA RX, so the router
     * delivers it as [HaloNotification.Message] with [HaloProtocol.STATUS] code.
     */
    suspend fun sendLuaAwaitStatus(
        lua: String,
        expectedPayload: String? = null,
        timeoutMs: Long = 5_000,
    ): String = coroutineScope {
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            notifications.filterIsInstance<HaloNotification.Message>().first {
                it.code == HaloProtocol.STATUS &&
                    (expectedPayload == null || it.payload.toString(Charsets.UTF_8).trim() == expectedPayload)
            }.payload.toString(Charsets.UTF_8).trim()
        }
        sendLua(lua)
        withTimeout(timeoutMs) { response.await() }
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
                currentCoroutineContext().ensureActive()
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

    override suspend fun sendAudioFrame(frame: ByteArray) {
        require(frame.size <= maxDataPayload) { "Audio frame ${frame.size} exceeds max payload $maxDataPayload" }
        writeMutex.withLock { channel.writeAudio(frame) }
    }

    private suspend fun sendDataPacket(packet: ByteArray) {
        require(packet.size <= maxDataPayload)
        while (router.acknowledgements.tryReceive().isSuccess) Unit
        channel.write(byteArrayOf(HaloProtocol.LUA_CTRL_DATA_MARKER.toByte()) + packet)
        val ack = try {
            withTimeout(ackTimeoutMs) { router.acknowledgements.receive() }
        } catch (timeout: TimeoutCancellationException) {
            channel.close()
            throw timeout
        }
        check(ack == HaloAck.SUCCESS) { "Halo rejected data packet" }
    }
}

/** Callback-neutral interface used by AndroidBleTransport and instrumentation fakes. */
interface GattChannel {
    val mtu: Int
    val notifications: Channel<ByteArray>
    val connectionEvents: Channel<Boolean>
    val supportsAudio: Boolean
    suspend fun discoverAndEnableNotifications()
    suspend fun requestMtu(desired: Int)
    suspend fun requestConnectionPriority(priority: Int): Boolean
    suspend fun write(bytes: ByteArray)
    suspend fun writeAudio(bytes: ByteArray)
    suspend fun close()
}

/** BluetoothGatt implementation of [GattChannel]. */
@SuppressLint("MissingPermission")
class BluetoothGattChannel(
    private val gatt: BluetoothGatt,
    private val callbacks: Callbacks,
) : GattChannel {
    private val dataWriteResults = Channel<Int>(Channel.UNLIMITED)
    private val descriptorResults = Channel<Int>(Channel.UNLIMITED)
    override val notifications = Channel<ByteArray>(Channel.UNLIMITED)
    override val connectionEvents = Channel<Boolean>(Channel.CONFLATED)
    override var mtu: Int = 23
        private set

    init {
        callbacks.channel = this
    }

    class Callbacks : BluetoothGattCallback() {
        var channel: BluetoothGattChannel? = null
        private var connectionResult = CompletableDeferred<BluetoothGattChannel>()

        suspend fun awaitChannel(timeoutMs: Long = 10_000): BluetoothGattChannel =
            withTimeout(timeoutMs) { connectionResult.await() }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED && channel == null) {
                val connected = BluetoothGattChannel(gatt, this)
                connectionResult.complete(connected)
            } else if (status != BluetoothGatt.GATT_SUCCESS && !connectionResult.isCompleted) {
                connectionResult.completeExceptionally(IllegalStateException("Halo connection failed: $status"))
            } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED && !connectionResult.isCompleted) {
                connectionResult.completeExceptionally(IllegalStateException("Halo disconnected before setup"))
            }
            if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) channel?.connectionEvents?.trySend(false)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            channel?.serviceResults?.complete(status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            channel?.mtuResults?.complete(mtu to status)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            when (characteristic.uuid) {
                BluetoothGattChannel.TX_UUID -> channel?.dataWriteResults?.trySend(status)
                else -> Unit
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            channel?.descriptorResults?.trySend(status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            channel?.notifications?.trySend(value.copyOf())
        }
    }

    private var serviceResults = CompletableDeferred<Int>()
    internal var mtuResults = CompletableDeferred<Pair<Int, Int>>()
    private val tx: BluetoothGattCharacteristic by lazy { requireCharacteristic(TX_UUID) }
    private val rx: BluetoothGattCharacteristic by lazy { requireCharacteristic(RX_UUID) }
    private val audioTx: BluetoothGattCharacteristic? by lazy { optionalCharacteristic(AUDIO_TX_UUID) }

    override suspend fun discoverAndEnableNotifications() {
        serviceResults = CompletableDeferred()
        check(gatt.discoverServices()) { "BluetoothGatt rejected service discovery" }
        check(withTimeout(5_000) { serviceResults.await() } == BluetoothGatt.GATT_SUCCESS) {
            "Halo service discovery failed"
        }
        val descriptor = requireNotNull(rx.getDescriptor(CCCD_UUID)) { "Halo notification descriptor is missing" }
        check(gatt.setCharacteristicNotification(rx, true)) { "Unable to enable Halo notifications" }
        val accepted = gatt.writeDescriptor(
            descriptor,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
        ) == BluetoothStatusCodes.SUCCESS
        check(accepted) { "BluetoothGatt rejected notification descriptor write" }
        check(withTimeout(5_000) { descriptorResults.receive() } == BluetoothGatt.GATT_SUCCESS) {
            "Halo notification descriptor write failed"
        }
    }

    override val supportsAudio: Boolean
        get() = audioTx != null

    override suspend fun requestMtu(desired: Int) {
        mtuResults = CompletableDeferred()
        check(gatt.requestMtu(desired)) { "BluetoothGatt rejected MTU request" }
        val (negotiated, status) = withTimeout(5_000) { mtuResults.await() }
        check(status == BluetoothGatt.GATT_SUCCESS) { "Halo MTU negotiation failed: $status" }
        mtu = negotiated
    }

    override suspend fun requestConnectionPriority(priority: Int): Boolean {
        return gatt.requestConnectionPriority(priority)
    }

    override suspend fun write(bytes: ByteArray) {
        // Drain any status that belongs to a previous write so it cannot
        // satisfy this waiter's receive() call.
        while (dataWriteResults.tryReceive().isSuccess) Unit
        val accepted = gatt.writeCharacteristic(
            tx,
            bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        ) == BluetoothStatusCodes.SUCCESS
        check(accepted) { "BluetoothGatt rejected write" }
        val status = withTimeout(5_000) { dataWriteResults.receive() }
        check(status == BluetoothGatt.GATT_SUCCESS) { "Halo write failed: $status" }
    }

    override suspend fun writeAudio(bytes: ByteArray) {
        val audio = audioTx ?: throw IllegalStateException("AUDIO_TX is not available on this device")
        require(bytes.size <= mtu - 3) { "Audio frame ${bytes.size} exceeds MTU payload ${mtu - 3}" }

        // Audio frames are sent with WRITE_TYPE_NO_RESPONSE. Android does not
        // reliably issue onCharacteristicWrite for no-response writes, so we
        // cannot use that callback to pace the stream. Pacing is enforced by
        // the caller (e.g., a fixed delay between frames in playAudio), and we
        // only verify that BluetoothGatt accepted the write request.
        val accepted = gatt.writeCharacteristic(
            audio,
            bytes,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
        ) == BluetoothStatusCodes.SUCCESS
        check(accepted) { "BluetoothGatt rejected AUDIO_TX write" }
    }

    override suspend fun close() {
        gatt.disconnect()
        gatt.close()
    }

    private fun requireCharacteristic(uuid: UUID): BluetoothGattCharacteristic {
        val service: BluetoothGattService = requireNotNull(gatt.getService(SERVICE_UUID)) { "Halo service not discovered" }
        return requireNotNull(service.getCharacteristic(uuid)) { "Halo characteristic not discovered: $uuid" }
    }

    private fun optionalCharacteristic(uuid: UUID): BluetoothGattCharacteristic? {
        val service: BluetoothGattService? = gatt.getService(SERVICE_UUID)
        return service?.getCharacteristic(uuid)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7a230001-5475-a6a4-654c-8431f6ad49c4")
        val TX_UUID: UUID = UUID.fromString("7a230002-5475-a6a4-654c-8431f6ad49c4")
        val RX_UUID: UUID = UUID.fromString("7a230003-5475-a6a4-654c-8431f6ad49c4")
        val AUDIO_TX_UUID: UUID = UUID.fromString("7a230005-5475-a6a4-654c-8431f6ad49c4")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
