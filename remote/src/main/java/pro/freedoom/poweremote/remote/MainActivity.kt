package pro.freedoom.poweremote.remote

import android.Manifest
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import pro.freedoom.poweremote.shared.Cmd

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private var inPip by mutableStateOf(false)
    private var lastPipPlaying: Boolean? = null

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        enableEdgeToEdge()
        askPermissions()
        applyKeepScreenOn()

        // Кнопки в окне картинка-в-картинке зависят от play/pause — обновляем их
        // при каждом изменении состояния, а не только при входе в PiP.
        lifecycleScope.launch {
            LinkBus.state.collectLatest { s ->
                val playing = s?.playing == true
                if (playing != lastPipPlaying) {
                    lastPipPlaying = playing
                    try { setPictureInPictureParams(pipParams(playing)) } catch (_: Exception) {}
                }
            }
        }

        setContent {
            RemoteApp(
                activity = this,
                prefs = prefs,
                inPip = inPip,
                onEnterPip = { enterPip() },
                onKeepScreenOnChanged = { applyKeepScreenOn() }
            )
        }
    }

    // ------------------------------------------------------------------ PiP

    private fun pipParams(playing: Boolean): PictureInPictureParams {
        val actions = listOf(
            action(R.drawable.ic_prev, "Предыдущий", Cmd.PREV),
            if (playing) action(R.drawable.ic_pause, "Пауза", Cmd.PAUSE)
            else action(R.drawable.ic_play, "Играть", Cmd.PLAY),
            action(R.drawable.ic_next, "Следующий", Cmd.NEXT)
        )
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(1, 1))
            .setActions(actions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setAutoEnterEnabled(prefs.autoPip && LinkBus.status.value == LinkStatus.CONNECTED)
            b.setSeamlessResizeEnabled(false)
        }
        return b.build()
    }

    private fun action(icon: Int, title: String, cmd: String) = RemoteAction(
        Icon.createWithResource(this, icon), title, title, ControlReceiver.pending(this, cmd)
    )

    fun enterPip() {
        try {
            enterPictureInPictureMode(pipParams(LinkBus.state.value?.playing == true))
        } catch (_: Exception) {
        }
    }

    /** До Android 12 авто-вход в PiP делается вручную по уходу с экрана. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            prefs.autoPip && LinkBus.status.value == LinkStatus.CONNECTED && !inPip
        ) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean, newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onResume() {
        super.onResume()
        // Параметры PiP надо переустановить после возврата: авто-вход зависит от связи.
        try { setPictureInPictureParams(pipParams(LinkBus.state.value?.playing == true)) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ громкость

    /**
     * Качелька громкости телефона управляет громкостью плеера, пока есть связь.
     * Основной путь — VolumeProvider медиа-сессии; этот перехват нужен, когда
     * пульт на экране и система отдаёт клавиши активности, а не сессии.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val connected = LinkBus.status.value == LinkStatus.CONNECTED
        val volumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!connected || !volumeKey) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            LinkService.send(Cmd.VOLUME_DELTA, if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1L else -1L)
        }
        return true
    }

    // ------------------------------------------------------------- прочее

    fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
}
