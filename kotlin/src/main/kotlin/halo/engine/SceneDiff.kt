package halo.engine

import kotlinx.serialization.json.*

/** Stable-ID changes for retained scene updates. */
data class SceneChange(val id: String, val kind: Kind, val element: JsonObject? = null) {
    enum class Kind { ADDED, CHANGED, REMOVED }
}

object SceneDiff {
    fun changes(previous: JsonObject?, current: JsonObject): List<SceneChange> {
        val old = flatten(previous?.get("scene")?.jsonObject?.get("children")?.jsonArray)
        val new = flatten(current["scene"]?.jsonObject?.get("children")?.jsonArray)
        val result = mutableListOf<SceneChange>()
        (old.keys - new.keys).sorted().forEach { result += SceneChange(it, SceneChange.Kind.REMOVED) }
        (new.keys - old.keys).sorted().forEach { result += SceneChange(it, SceneChange.Kind.ADDED, new[it]) }
        (old.keys intersect new.keys).sorted().forEach { id ->
            if (old[id].toString() != new[id].toString()) result += SceneChange(id, SceneChange.Kind.CHANGED, new[id])
        }
        return result
    }

    private fun flatten(elements: JsonArray?, parent: String = ""): Map<String, JsonObject> {
        if (elements == null) return emptyMap()
        val result = linkedMapOf<String, JsonObject>()
        elements.forEachIndexed { index, value ->
            val element = value.jsonObject
            val id = element["id"]?.jsonPrimitive?.content ?: "$parent/$index"
            result[id] = element
            result.putAll(flatten(element["children"]?.jsonArray, id))
        }
        return result
    }
}
