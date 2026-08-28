package halo.engine

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompilerTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val scene = """
        {
          "version": "1.0",
          "device": "halo",
          "mode": "repl",
          "scene": {
            "width": 256,
            "height": 256,
            "bg": "#000000",
            "children": [
              { "type": "circle", "cx": 128, "cy": 128, "r": 125, "color": "#0050A0", "filled": false },
              { "type": "text", "x": 78, "y": 90, "text": "5:30", "font": 1, "size": 32, "color": "#FFFFFF" }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun rowOffsetsAreAppliedOnce() {
        val row = json.parseToJsonElement(
            """{"scene":{"children":[{"type":"row","x":10,"y":20,"children":[{"type":"point","x":1,"y":2,"color":"#FFFFFF"}]}]}}"""
        )
        assertContains(HaloCompiler().compile(row), "frame.display.set_pixel(12,23,0xFFFFFF)")
    }

    @Test
    fun spriteBytesUseTwoDigitLuaEscapes() {
        val packer = object : SpritePacker {
            override fun pack(src: String, width: Int?, height: Int?, bpp: Int) = SpritePacker.Sprite(
                width = 1,
                height = 1,
                bpp = 4,
                numColors = 1,
                paletteData = byteArrayOf(0xff.toByte(), 0x80.toByte(), 0x00),
                pixelData = byteArrayOf(0x0f),
            )
        }
        val sprite = json.parseToJsonElement("""{"scene":{"children":[{"type":"sprite","src":"test"}]}}""")
        val lua = HaloCompiler(packer).compile(sprite)
        assertContains(lua, "\\xFF\\x80\\x00")
    }

    @Test
    fun compileCircleAndText() {
        val compiler = HaloCompiler()
        val lua = compiler.compile(json.parseToJsonElement(scene))
        assertContains(lua, "frame.display.clear(0x000000)")
        assertContains(lua, "frame.display.power_save(false)")
        assertContains(lua, "frame.display.circle(129,129,125,0x0050A0,false)")
        assertContains(lua, "frame.display.set_font(1,32,1)")
        assertContains(lua, "frame.display.text('5:30',79,91,0xFFFFFF)")
        assertContains(lua, "print('ok')")
    }
}
