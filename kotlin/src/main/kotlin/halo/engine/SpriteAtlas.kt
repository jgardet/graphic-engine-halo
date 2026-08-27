package halo.engine

/** Retained, deterministic sprite resource table for HRP. */
class SpriteAtlas(private val maxRetainedBytes: Int = StockHaloLimits.maxRetainedAssetBytes) {
    data class Entry(val id: Int, val name: String, val sprite: SpritePacker.Sprite, val packedSize: Int)

    private val entriesByName = linkedMapOf<String, Entry>()

    fun add(name: String, sprite: SpritePacker.Sprite): Entry {
        require(name.isNotEmpty())
        return entriesByName[name] ?: run {
            val packedSize = HaloHost.packSpriteAsset(sprite).size
            require(totalBytes + packedSize <= maxRetainedBytes) { "sprite atlas exceeds retained asset budget" }
            Entry(entriesByName.size + 1, name, sprite, packedSize).also { entriesByName[name] = it }
        }
    }

    fun get(name: String): Entry = requireNotNull(entriesByName[name])
    fun release(name: String): Entry = requireNotNull(entriesByName.remove(name))
    fun entries(): List<Entry> = entriesByName.values.toList()
    val totalBytes: Int get() = entriesByName.values.sumOf { it.packedSize }
}
