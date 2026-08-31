package halo.engine.android

import android.content.Context
import halo.engine.HaloRuntimeSource
import halo.engine.HaloRuntimeSourceException

/**
 * [HaloRuntimeSource] backed by an Android asset.
 *
 * Reads the device-side Lua runtime from [assetName] (default
 * `he_runtime.lua`) using the application's [AssetManager]. The
 * `lua/` directory is packaged into the AAR by the engine's
 * `android/build.gradle.kts` asset source set.
 */
class AssetHaloRuntimeSource(
    context: Context,
    private val assetName: String = DEFAULT_ASSET_NAME,
) : HaloRuntimeSource {

    private val appContext = context.applicationContext

    override fun load(): String = try {
        appContext.assets.open(assetName).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        throw HaloRuntimeSourceException(
            "Failed to load Halo runtime asset '$assetName': ${e.message}", e,
        )
    }

    companion object {
        /** Default asset name for the device-side Lua runtime. */
        const val DEFAULT_ASSET_NAME = "he_runtime.lua"
    }
}
