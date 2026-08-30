package halo.engine

import kotlinx.serialization.json.*

/**
 * Compile a Halo Scene Description (HSD) JsonObject to Lua.
 */
class HaloCompiler(private val packer: SpritePacker = StubSpritePacker()) {

    private val lua = StringBuilder()

    fun compile(scene: JsonElement): String {
        val root = scene.jsonObject["scene"]?.jsonObject ?: JsonObject(mapOf())
        val mode = scene.jsonObject["mode"]?.jsonPrimitive?.content ?: "repl"
        if (mode != "repl") {
            throw NotImplementedError("Only 'repl' mode is implemented in this Kotlin reference")
        }

        lua.clear()

        // Background
        val bg = root["bg"] ?: "#000000"
        append("frame.display.clear(${HaloColor.toHex(bg)})")

        // Power save
        if (root["power_save"]?.jsonPrimitive?.booleanOrNull != true) {
            append("frame.display.power_save(false)")
        }

        // Brightness
        root["brightness"]?.jsonPrimitive?.intOrNull?.let {
            append("frame.display.brightness($it)")
        }

        // Pan
        root["pan"]?.jsonArray?.let { pan ->
            if (pan.size >= 2) {
                append("frame.display.set_pan(${pan[0].jsonPrimitive.int},${pan[1].jsonPrimitive.int})")
            }
        }

        // Children
        root["children"]?.jsonArray?.forEach { child ->
            compileElement(child.jsonObject, 0, 0)
        }

        append("print('ok')")
        return lua.toString().trim()
    }

    private fun append(cmd: String) {
        if (lua.isNotEmpty()) lua.append(' ')
        lua.append(cmd)
    }

    private fun compileElement(el: JsonObject, dx: Int, dy: Int) {
        if (el["visible"]?.jsonPrimitive?.boolean == false) return

        when (el["type"]?.jsonPrimitive?.content?.lowercase()) {
            "group" -> compileGroup(el, dx, dy)
            "row" -> compileRow(el, dx, dy)
            "column" -> compileColumn(el, dx, dy)
            "text" -> compileText(el, dx, dy)
            "rect" -> compileRect(el, dx, dy)
            "circle" -> compileCircle(el, dx, dy)
            "line" -> compileLine(el, dx, dy)
            "polygon" -> compilePolygon(el, dx, dy)
            "point", "pixel" -> compilePoint(el, dx, dy)
            "sprite" -> compileSprite(el, dx, dy)
            else -> throw IllegalArgumentException("Unknown element type: ${el["type"]}")
        }
    }

    private fun compileText(el: JsonObject, dx: Int, dy: Int) {
        val x = int(el, "x") + dx + 1
        val y = int(el, "y") + dy + 1
        val font = int(el, "font", 0)
        val size = int(el, "size", 8)
        val scale = int(el, "scale", 1)
        val text = string(el, "text", "")
        val color = HaloColor.toHex(el["color"] ?: "#FFFFFF")

        append("frame.display.set_font($font,$size,$scale)")
        append("frame.display.text(${luaStringLiteral(text)},$x,$y,$color)")
    }

    private fun compileRect(el: JsonObject, dx: Int, dy: Int) {
        val x = int(el, "x") + dx + 1
        val y = int(el, "y") + dy + 1
        val w = int(el, "w")
        val h = int(el, "h")
        val color = HaloColor.toHex(el["color"] ?: "#FFFFFF")
        val filled = el["filled"]?.jsonPrimitive?.boolean ?: false
        append("frame.display.rect($x,$y,$w,$h,$color,${luaBool(filled)})")
    }

    private fun compileCircle(el: JsonObject, dx: Int, dy: Int) {
        val cx = int(el, "cx") + dx + 1
        val cy = int(el, "cy") + dy + 1
        val r = int(el, "r")
        val color = HaloColor.toHex(el["color"] ?: "#FFFFFF")
        val filled = el["filled"]?.jsonPrimitive?.boolean ?: false
        append("frame.display.circle($cx,$cy,$r,$color,${luaBool(filled)})")
    }

    private fun compileLine(el: JsonObject, dx: Int, dy: Int) {
        val x0 = int(el, "x0") + dx + 1
        val y0 = int(el, "y0") + dy + 1
        val x1 = int(el, "x1") + dx + 1
        val y1 = int(el, "y1") + dy + 1
        val color = HaloColor.toHex(el["color"] ?: "#FFFFFF")
        append("frame.display.line($x0,$y0,$x1,$y1,$color)")
    }

    private fun compilePolygon(el: JsonObject, dx: Int, dy: Int) {
        val points = el["points"]?.jsonArray ?: return
        val flat = points.flatMap { p ->
            val arr = p.jsonArray
            listOf(arr[0].jsonPrimitive.int + dx + 1, arr[1].jsonPrimitive.int + dy + 1)
        }
        val color = HaloColor.toHex(el["color"] ?: "#FFFFFF")
        val coords = flat.joinToString(",")
        append("frame.display.polygon({$coords},$color)")
    }

    private fun compilePoint(el: JsonObject, dx: Int, dy: Int) {
        val x = int(el, "x") + dx + 1
        val y = int(el, "y") + dy + 1
        val color = HaloColor.toHex(el["color"] ?: "#FFFFFF")
        append("frame.display.set_pixel($x,$y,$color)")
    }

    private fun compileSprite(el: JsonObject, dx: Int, dy: Int) {
        val src = string(el, "src", "")
        val x = int(el, "x") + dx
        val y = int(el, "y") + dy
        val w = el["w"]?.jsonPrimitive?.intOrNull
        val h = el["h"]?.jsonPrimitive?.intOrNull
        val bpp = int(el, "bpp", 4)
        val paletteOffset = int(el, "palette_offset", 0)
        val xScale = int(el, "scale_x", 1)
        val yScale = int(el, "scale_y", 1)

        val sprite = packer.pack(src, w, h, bpp)
        val packed = packIndexedPixels(sprite.pixelData, sprite.bpp)
        val pixelStr = hexEscape(packed)
        val paletteStr = hexEscape(sprite.paletteData)
        val fmt = sprite.colorFormat

        append(
            "frame.display.bitmap(${x + 1},${y + 1},${sprite.width},$fmt,$paletteOffset,\"$pixelStr\",{palette_data=\"$paletteStr\",x_scale=$xScale,y_scale=$yScale})"
        )
    }

    private fun compileGroup(el: JsonObject, dx: Int, dy: Int) {
        val gdx = dx + int(el, "x", 0)
        val gdy = dy + int(el, "y", 0)
        el["children"]?.jsonArray?.forEach { compileElement(it.jsonObject, gdx, gdy) }
    }

    private fun compileRow(el: JsonObject, dx: Int, dy: Int) {
        val startX = dx + int(el, "x", 0)
        val y = dy + int(el, "y", 0)
        val spacing = int(el, "spacing", 0)
        var currentX = startX
        el["children"]?.jsonArray?.forEach { child ->
            val c = child.jsonObject
            compileElement(c, currentX, y)
            currentX += HsdLayout.estimateWidth(c) + spacing
        }
    }

    private fun compileColumn(el: JsonObject, dx: Int, dy: Int) {
        val x = dx + int(el, "x", 0)
        val startY = dy + int(el, "y", 0)
        val spacing = int(el, "spacing", 0)
        var currentY = startY
        el["children"]?.jsonArray?.forEach { child ->
            val c = child.jsonObject
            compileElement(c, x, currentY)
            currentY += HsdLayout.estimateHeight(c) + spacing
        }
    }

    // Helpers -----------------------------------------------------------------

    private fun int(el: JsonObject, key: String, default: Int = 0): Int {
        return el[key]?.jsonPrimitive?.intOrNull ?: default
    }

    private fun string(el: JsonObject, key: String, default: String = ""): String {
        return el[key]?.jsonPrimitive?.content ?: default
    }

    private fun luaBool(b: Boolean) = if (b) "true" else "false"

    private fun luaStringLiteral(s: String): String {
        val escaped = s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\t", "\\t")
        return "'$escaped'"
    }

    private fun hexEscape(data: ByteArray): String = data.joinToString("") { "\\x%02X".format(it.toInt() and 0xff) }
}
