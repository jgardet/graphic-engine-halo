package halo.engine

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HsdHrpCompilerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun compilesGenericSceneToHrp() {
        val scene = json.parseToJsonElement(
            """{"version":"1.0","device":"halo","mode":"runtime","scene":{"width":256,"height":256,"bg":"#010203","brightness":25,"children":[{"type":"line","x0":1,"y0":2,"x1":3,"y1":4,"color":"#112233"},{"type":"text","x":5,"y":6,"text":"BTC","font":1,"size":8,"color":"#FFFFFF"}]}}"""
        )
        val payload = HsdHrpCompiler(StubSpritePacker()).compile(scene)
        val reference = "485250310000060100030102030200011904000b000100020003000411223308000301080109000c00050006ffffff00034254430e0000"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        assertContentEquals(reference, payload)
    }

    @Test
    fun rowPositionsChildrenOnce() {
        val scene = json.parseToJsonElement(
            """{"scene":{"children":[{"type":"row","x":10,"y":20,"spacing":2,"children":[{"type":"point","x":1,"y":2,"color":"#FFFFFF"},{"type":"point","x":0,"y":0,"color":"#FFFFFF"}]}]}}"""
        )
        val payload = HsdHrpCompiler(StubSpritePacker()).compile(scene)
        val pixelCommands = commands(payload).filter { it.first == 0x03 }

        assertContentEquals(byteArrayOf(0, 11, 0, 22, -1, -1, -1), pixelCommands[0].second)
        assertContentEquals(byteArrayOf(0, 13, 0, 20, -1, -1, -1), pixelCommands[1].second)
    }

    @Test
    fun rejectsUnknownAndOversizedScenes() {
        assertFailsWith<IllegalArgumentException> {
            HsdHrpCompiler(StubSpritePacker()).compile(json.parseToJsonElement("""{"scene":{"children":[{"type":"video"}]}}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            HsdHrpCompiler(StubSpritePacker()).compile(json.parseToJsonElement("""{"scene":{"children":[{"type":"point","x":256,"y":0}]}}"""))
        }
    }

    private fun commands(payload: ByteArray): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        var offset = 7
        while (offset < payload.size) {
            val opcode = payload[offset].toInt() and 0xff
            val size = ((payload[offset + 1].toInt() and 0xff) shl 8) or (payload[offset + 2].toInt() and 0xff)
            result += opcode to payload.copyOfRange(offset + 3, offset + 3 + size)
            offset += 3 + size
        }
        return result
    }
}
