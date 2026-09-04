package halo.engine

/**
 * Provides the source text of the device-side Lua runtime
 * (`lua/he_runtime.lua`).
 *
 * This is the canonical API for obtaining the runtime source. Concrete
 * implementations supply the source from an asset, classpath resource,
 * or in-memory string. Callers (e.g. `HaloRuntimeInstaller`) receive
 * the source via [load] and upload it to the device.
 *
 * Implementations must be safe to call from a coroutine context; they
 * perform blocking I/O and should be invoked on `Dispatchers.IO`.
 */
fun interface HaloRuntimeSource {

    /**
     * Returns the Lua source text of the device-side runtime.
     *
     * @throws HaloRuntimeSourceException if the source cannot be loaded
     *   (missing asset, I/O error, etc.).
     */
    fun load(): String

    companion object
}

/** Thrown when [HaloRuntimeSource.load] fails. */
class HaloRuntimeSourceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
