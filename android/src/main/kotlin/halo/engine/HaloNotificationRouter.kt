package halo.engine

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface HaloNotification {
    data class Message(val code: Int, val payload: ByteArray) : HaloNotification
    data class Text(val value: String) : HaloNotification
    data class Unknown(val bytes: ByteArray) : HaloNotification
    data object Disconnected : HaloNotification
}

internal enum class HaloAck { SUCCESS, FAILURE }

class HaloNotificationRouter {
    internal val acknowledgements = Channel<HaloAck>(Channel.BUFFERED)
    private val _notifications = MutableSharedFlow<HaloNotification>(extraBufferCapacity = 64)
    val notifications: SharedFlow<HaloNotification> = _notifications.asSharedFlow()

    fun disconnected() {
        _notifications.tryEmit(HaloNotification.Disconnected)
    }

    fun route(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val data = if (bytes[0] == 0x01.toByte()) bytes.copyOfRange(1, bytes.size) else null
        when {
            data?.contentEquals(byteArrayOf(0x01, 0x00, 0x00)) == true ||
                data?.contentEquals(byteArrayOf(0x00, 0x00)) == true ||
                bytes.contentEquals(byteArrayOf(0x00, 0x00)) -> acknowledgements.trySend(HaloAck.SUCCESS)
            data?.contentEquals(byteArrayOf(0x01, 0x00, 0x01)) == true ||
                data?.contentEquals(byteArrayOf(0x00, 0x01)) == true ||
                bytes.contentEquals(byteArrayOf(0x00, 0x01)) -> acknowledgements.trySend(HaloAck.FAILURE)
            data != null && data.isNotEmpty() ->
                _notifications.tryEmit(HaloNotification.Message(data[0].toInt() and 0xff, data.copyOfRange(1, data.size)))
            (bytes[0].toInt() and 0xff) in APP_MESSAGE_CODES ->
                _notifications.tryEmit(HaloNotification.Message(bytes[0].toInt() and 0xff, bytes.copyOfRange(1, bytes.size)))
            bytes[0].toInt() in 0x20..0x7e ->
                _notifications.tryEmit(HaloNotification.Text(bytes.toString(Charsets.UTF_8)))
            else -> _notifications.tryEmit(HaloNotification.Unknown(bytes.copyOf()))
        }
    }

    private companion object {
        val APP_MESSAGE_CODES = setOf(
            HaloProtocol.AUDIO_CHUNK,
            HaloProtocol.AUDIO_FINAL,
            HaloProtocol.PHOTO_JPEG,
            HaloProtocol.PHOTO_FINAL,
            HaloProtocol.TAP,
            HaloProtocol.BUTTON,
            HaloProtocol.CAPTURE_PHOTO,
            HaloProtocol.MICROPHONE_START,
            HaloProtocol.MICROPHONE_STOP,
            HaloProtocol.SPEAKER_START,
            HaloProtocol.SPEAKER_STOP,
            HaloProtocol.HRP,
            HaloProtocol.STATUS,
            HaloProtocol.ERROR,
            HaloProtocol.DEVICE_STATUS,
        )
    }
}
