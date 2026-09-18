package pro.freedoom.poweremote.remote

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import pro.freedoom.poweremote.shared.PlayerState

enum class LinkStatus { IDLE, CONNECTING, CONNECTED, LOST }

/**
 * Единая точка обмена между сервисом связи и экраном.
 * Сервис пишет, Compose читает — состояние переживает поворот и складывание экрана.
 */
object LinkBus {
    private val _status = MutableStateFlow(LinkStatus.IDLE)
    val status: StateFlow<LinkStatus> = _status

    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state

    private val _art = MutableStateFlow<Bitmap?>(null)
    val art: StateFlow<Bitmap?> = _art

    private val _deviceName = MutableStateFlow("")
    val deviceName: StateFlow<String> = _deviceName

    private val _error = MutableStateFlow("")
    val error: StateFlow<String> = _error

    /**
     * Пока не пришёл ответ плеера, показываем то, что выставил пользователь:
     * иначе ползунок на секунду отпрыгивает назад.
     */
    @Volatile var pendingSeekUntil = 0L
    @Volatile var pendingSeekValue = 0L
    @Volatile var pendingVolumeUntil = 0L
    @Volatile var pendingVolumeValue = 0

    /** Момент по локальным часам, когда пришёл последний снимок состояния. */
    @Volatile var stateAt: Long = 0L

    fun setStatus(s: LinkStatus) { _status.value = s }

    fun setState(s: PlayerState?) {
        stateAt = android.os.SystemClock.elapsedRealtime()
        _state.value = s
    }
    fun setArt(b: Bitmap?) { _art.value = b }
    fun setDevice(name: String) { _deviceName.value = name }
    fun setError(e: String) { _error.value = e }
}
