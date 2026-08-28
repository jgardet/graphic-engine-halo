package halo.engine

object HaloProtocol {
    const val AUDIO_CHUNK = 0x05
    const val AUDIO_FINAL = 0x06
    const val TAP = 0x09
    const val BUTTON = 0x0B
    const val MICROPHONE_START = 0x30
    const val MICROPHONE_STOP = 0x31
    const val SPEAKER_START = 0x40
    const val SPEAKER_STOP = 0x41
    const val HRP = 0x60
    const val STATUS = 0x70
    const val ERROR = 0x71
    const val DEVICE_STATUS = 0x72
}
