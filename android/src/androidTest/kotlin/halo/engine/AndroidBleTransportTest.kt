package halo.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBleTransportTest {
    @Test
    fun negotiatesMtuAndWaitsForAck() = runBlocking {
        val fake = FakeGattChannel()
        val transport = AndroidBleTransport(fake, ackTimeoutMs = 500)

        transport.connect()
        assertEquals(512, fake.mtu)
        assertEquals(509, transport.maxLuaPayload)
        assertEquals(508, transport.maxDataPayload)

        transport.sendMessage(0x60, byteArrayOf(1, 2, 3))
        assertEquals(1, fake.writes.size)
        assertEquals(HaloProtocol.LUA_CTRL_DATA_MARKER, fake.writes.single().first().toInt())
        assertTrue(fake.writes.single().size > 4)
    }

    @Test
    fun serializesLargeMessageIntoFramedPackets() = runBlocking {
        val fake = FakeGattChannel(maxMtu = 100)
        val transport = AndroidBleTransport(fake, ackTimeoutMs = 500)
        transport.connect()
        transport.sendMessage(0x60, ByteArray(250))

        assertEquals(3, fake.writes.size)
        assertEquals(0x60, fake.writes[0][1].toInt() and 0xff)
        assertEquals(0x00, fake.writes[0][2].toInt() and 0xff)
        assertEquals(250, fake.writes[0][3].toInt() and 0xff)
        assertTrue(fake.writes.all { it.first().toInt() == HaloProtocol.LUA_CTRL_DATA_MARKER })
    }

    @Test
    fun routesInterleavedMessageWithoutLosingAck() = runBlocking {
        val fake = FakeGattChannel(interleaved = byteArrayOf(HaloProtocol.LUA_CTRL_DATA_MARKER.toByte(), 0x0b, 0x01))
        val transport = AndroidBleTransport(fake, ackTimeoutMs = 500)
        transport.connect()
        val notification = async { transport.notifications.first() }
        yield()

        transport.sendMessage(0x60, byteArrayOf(1, 2, 3))

        val message = notification.await() as HaloNotification.Message
        assertEquals(0x0b, message.code)
        assertEquals(1, message.payload.single().toInt())
    }

    private class FakeGattChannel(
        private val maxMtu: Int = 512,
        private val interleaved: ByteArray? = null,
    ) : GattChannel {
        override var mtu: Int = 23
        override val notifications = Channel<ByteArray>(Channel.UNLIMITED)
        override val connectionEvents = Channel<Boolean>(Channel.CONFLATED)
        override val supportsAudio: Boolean = false
        val writes = mutableListOf<ByteArray>()
        val audioWrites = mutableListOf<ByteArray>()

        override suspend fun discoverAndEnableNotifications() = Unit

        override suspend fun requestMtu(desired: Int) {
            mtu = minOf(desired, maxMtu)
        }

        override suspend fun requestConnectionPriority(priority: Int): Boolean = true

        override suspend fun write(bytes: ByteArray) {
            writes += bytes
            interleaved?.let { notifications.send(it) }
            notifications.send(byteArrayOf(HaloProtocol.LUA_CTRL_DATA_MARKER.toByte(), 1, 0, 0))
        }

        override suspend fun writeAudio(bytes: ByteArray) {
            audioWrites += bytes
        }

        override suspend fun close() = Unit
    }
}
