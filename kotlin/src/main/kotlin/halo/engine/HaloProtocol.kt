package halo.engine

/**
 * Message and control codes shared by the host, the device-side runtime, and
 * the firmware's BLE Lua service.
 *
 * App message codes are the first payload byte inside a data transfer (after
 * [LUA_CTRL_DATA_MARKER]). Control codes are written directly to the control
 * characteristic as a single byte and are interpreted by the firmware before
 * any REPL or data routing happens. The two namespaces intentionally overlap
 * numerically — the firmware distinguishes them by context (single-byte write
 * vs framed data marker), so always use the correctly-prefixed constant.
 */
object HaloProtocol {

    /** Firmware control codes sent as single-byte writes to the Lua control characteristic. */
    const val LUA_CTRL_DATA_MARKER = 0x01
    const val LUA_CTRL_REBOOT = 0x02
    const val LUA_CTRL_INTERRUPT = 0x03
    const val LUA_CTRL_RESTART = 0x04
    const val LUA_CTRL_RESET = 0x05
    const val LUA_CTRL_EXIT = 0x06
    const val LUA_CTRL_REMOVE_ALL = 0x07

    /** Application message codes, used as the first byte after [LUA_CTRL_DATA_MARKER]. */
    const val AUDIO_CHUNK = 0x05
    const val AUDIO_FINAL = 0x06
    const val PHOTO_JPEG = 0x07
    const val PHOTO_FINAL = 0x08
    const val TAP = 0x09
    const val BUTTON = 0x0B
    const val CAPTURE_PHOTO = 0x20
    const val MICROPHONE_START = 0x30
    const val MICROPHONE_STOP = 0x31
    const val SPEAKER_START = 0x40
    const val SPEAKER_STOP = 0x41
    const val HRP = 0x60
    const val STATUS = 0x70
    const val ERROR = 0x71
    const val DEVICE_STATUS = 0x72
}
