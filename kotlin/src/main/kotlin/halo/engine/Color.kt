package halo.engine

import kotlinx.serialization.json.*

/**
 * Parse a color string/int to 0xRRGGBB integer.
 */
object HaloColor {
    private val named = mapOf(
        "black" to 0x000000,
        "void" to 0x000000,
        "white" to 0xFFFFFF,
        "grey" to 0x808080,
        "gray" to 0x808080,
        "red" to 0xFF0000,
        "pink" to 0xFFC0CB,
        "darkbrown" to 0x654321,
        "brown" to 0x963200,
        "orange" to 0xFFA500,
        "yellow" to 0xFFFF00,
        "darkgreen" to 0x006400,
        "green" to 0x00FF00,
        "lightgreen" to 0x90EE90,
        "nightblue" to 0x191970,
        "seablue" to 0x0000CD,
        "skyblue" to 0x87CEEB,
        "cloudblue" to 0xF0F8FF,
    )

    fun parse(value: Any?): Int {
        val raw = when (value) {
            is JsonElement -> value.toRawColor()
            is Int -> value
            is String -> parseString(value)
            else -> throw IllegalArgumentException("Color must be a string, int, or JSON primitive: $value")
        }
        if (raw is Int) return raw and 0xFFFFFF
        return parseString(raw as String)
    }

    private fun JsonElement.toRawColor(): Any {
        if (this is JsonPrimitive) {
            intOrNull?.let { return it }
            contentOrNull?.let { return it }
        }
        throw IllegalArgumentException("Color JSON must be a string or int: $this")
    }

    private fun parseString(value: String): Int {
        var s = value.trim().lowercase()
        named[s]?.let { return it }
        if (s.startsWith("#")) s = s.substring(1)
        if (s.startsWith("0x")) s = s.substring(2)
        return when (s.length) {
            6 -> s.toInt(16)
            3 -> s.map { "$it$it" }.joinToString("").toInt(16)
            else -> throw IllegalArgumentException("Unrecognized color: $value")
        }
    }

    fun toHex(value: Any?): String = "0x%06X".format(parse(value))
}
