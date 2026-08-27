package halo.engine

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
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
        assertEquals(0x01, fake.writes.single().first().toInt())
        assertTrue(fake.writes.single().size > 4)
    }

    @Test
    fun serializesLargeMessageIntoFramedPackets() = runBlocking {
        val fake = FakeGattChannel(maxMtu = 100)
        val transport = AndroidBleTransport(fake, ackTimeoutMs = 500)
        transport.connect()
        transport.sendMessage(0x60, ByteArray(250))

        assertEquals(3, fake.writes.size)
        assertEquals(0x60, fake.writes[0][1].toInt())
        assertEquals(0x00, fake.writes[0][2].toInt())
        assertEquals(250, fake.writes[0][3].toInt())
        assertTrue(fake.writes.all { it.first().toInt() == 1 })
    }

    private class FakeGattChannel(private val maxMtu: Int = 512) : GattChannel {
        override var mtu: Int = 23
        override val notifications = Channel<ByteArray>(Channel.UNLIMITED)
        val writes = mutableListOf<ByteArray>()

        override suspend fun discoverAndEnableNotifications() = Unit

        override suspend fun requestMtu(desired: Int) {
            mtu = minOf(desired, maxMtu)
        }

        override suspend fun write(bytes: ByteArray) {
            writes += bytes
            notifications.send(byteArrayOf(1, 0, 0))
        }

        override suspend fun close() = Unit
    }
}
