package halo.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Recursive size estimation for HSD layout containers.
 *
 * `row` and `column` rely on these estimates to position children. Primitive
 * elements return a best-effort bounding box; containers return the bounding box
 * of their children. A child may override its natural size by providing `w` or
 * `h` directly.
 */
object HsdLayout {

    fun estimateWidth(element: JsonObject): Int =
        element["w"]?.jsonPrimitive?.intOrNull ?: computeWidth(element)

    fun estimateHeight(element: JsonObject): Int =
        element["h"]?.jsonPrimitive?.intOrNull ?: computeHeight(element)

    private fun computeWidth(element: JsonObject): Int {
        val children = element["children"]?.jsonArray ?: JsonArray(emptyList())
        val spacing = element["spacing"]?.jsonPrimitive?.intOrNull ?: 0
        return when (element["type"]?.jsonPrimitive?.content?.lowercase()) {
            "row" -> {
                if (children.isEmpty()) 0
                else children.sumOf { estimateWidth(it.jsonObject) } + spacing * (children.size - 1)
            }
            "column" -> {
                if (children.isEmpty()) 0
                else children.maxOf { estimateWidth(it.jsonObject) }
            }
            "group" -> {
                if (children.isEmpty()) 0
                else children.maxOf {
                    val child = it.jsonObject
                    (child["x"]?.jsonPrimitive?.intOrNull ?: 0) + estimateWidth(child)
                }
            }
            "text" -> {
                val text = element["text"]?.jsonPrimitive?.content ?: ""
                val size = element["size"]?.jsonPrimitive?.intOrNull ?: 8
                text.length * size / 2
            }
            "circle" -> (element["r"]?.jsonPrimitive?.intOrNull ?: 0) * 2
            "rect" -> element["w"]?.jsonPrimitive?.intOrNull ?: 0
            "line" -> {
                val x0 = element["x0"]?.jsonPrimitive?.intOrNull ?: 0
                val x1 = element["x1"]?.jsonPrimitive?.intOrNull ?: 0
                abs(x1 - x0) + 1
            }
            "polygon" -> polygonBox(element).let { (minX, maxX, _, _) -> maxX - minX + 1 }
            "point", "pixel" -> 1
            "sprite" -> element["w"]?.jsonPrimitive?.intOrNull ?: 16
            else -> 0
        }
    }

    private fun computeHeight(element: JsonObject): Int {
        val children = element["children"]?.jsonArray ?: JsonArray(emptyList())
        val spacing = element["spacing"]?.jsonPrimitive?.intOrNull ?: 0
        return when (element["type"]?.jsonPrimitive?.content?.lowercase()) {
            "row" -> {
                if (children.isEmpty()) 0
                else children.maxOf { estimateHeight(it.jsonObject) }
            }
            "column" -> {
                if (children.isEmpty()) 0
                else children.sumOf { estimateHeight(it.jsonObject) } + spacing * (children.size - 1)
            }
            "group" -> {
                if (children.isEmpty()) 0
                else children.maxOf {
                    val child = it.jsonObject
                    (child["y"]?.jsonPrimitive?.intOrNull ?: 0) + estimateHeight(child)
                }
            }
            "text" -> element["size"]?.jsonPrimitive?.intOrNull ?: 8
            "circle" -> (element["r"]?.jsonPrimitive?.intOrNull ?: 0) * 2
            "rect" -> element["h"]?.jsonPrimitive?.intOrNull ?: 0
            "line" -> {
                val y0 = element["y0"]?.jsonPrimitive?.intOrNull ?: 0
                val y1 = element["y1"]?.jsonPrimitive?.intOrNull ?: 0
                abs(y1 - y0) + 1
            }
            "polygon" -> polygonBox(element).let { (_, _, minY, maxY) -> maxY - minY + 1 }
            "point", "pixel" -> 1
            "sprite" -> element["h"]?.jsonPrimitive?.intOrNull ?: 16
            else -> 0
        }
    }

    private fun polygonBox(element: JsonObject): PolygonBox {
        val points = element["points"]?.jsonArray ?: JsonArray(emptyList())
        if (points.isEmpty()) return PolygonBox(0, 0, 0, 0)
        val xs = points.map { (it.jsonArray[0].jsonPrimitive.intOrNull ?: 0) }
        val ys = points.map { (it.jsonArray[1].jsonPrimitive.intOrNull ?: 0) }
        return PolygonBox(xs.min(), xs.max(), ys.min(), ys.max())
    }

    private data class PolygonBox(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int)
}
