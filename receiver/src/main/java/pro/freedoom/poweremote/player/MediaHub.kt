package pro.freedoom.poweremote.player

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import pro.freedoom.poweremote.shared.PlayerState
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Мост к плееру: читает активную медиа-сессию и выполняет команды.
 *
 * Работает универсально — Poweramp, Neutron, UAPP, любой плеер с медиа-сессией.
 *
 * Оговорка про режимы: системный android.media.session (в отличие от
 * androidx-совместимого) не умеет ни читать, ни переключать shuffle/repeat.
 * Поэтому:
 *   • Poweramp — через его открытый API, включая чтение текущих режимов
 *     из sticky-интента PLAYING_MODE_CHANGED;
 *   • остальные плееры — через custom actions в PlaybackState, если плеер их даёт;
 *   • если ни того, ни другого нет, пульт показывает режимы как «неизвестно».
 */
class MediaHub(
    private val ctx: Context,
    private val onChanged: () -> Unit
) {
    companion object {
        private const val TAG = "MediaHub"
        private val PREFERRED = listOf(Poweramp.PKG)
        private const val ART_MAX_PX = 420
    }

    private val handler = Handler(Looper.getMainLooper())
    private var art = Executors.newSingleThreadExecutor()
    private val sessions: MediaSessionManager? =
        ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val audio: AudioManager =
        ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listenerComponent = ComponentName(ctx, MediaAccessListener::class.java)

    private var controller: MediaController? = null
    private var started = false

    /** Режимы Poweramp, обновляются из широковещалки. -1 — пока неизвестно. */
    @Volatile var powerampShuffle = -1
    @Volatile var powerampRepeat = -1

    @Volatile
    var artKey: String = ""
        private set

    @Volatile
    var artJpeg: ByteArray? = null
        private set

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            scheduleArt()
            onChanged()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) = onChanged()

        override fun onSessionDestroyed() {
            detach()
            rescan()
            onChanged()
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            choose(list ?: emptyList())
            onChanged()
        }

    /** Выдан ли доступ к чтению медиа-сессий (через «Доступ к уведомлениям»). */
    fun hasAccess(): Boolean {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.split(":").any {
            ComponentName.unflattenFromString(it)?.packageName == ctx.packageName
        }
    }

    fun start() {
        if (started) return
        // После stop() пул закрыт — для повторного запуска нужен новый.
        if (art.isShutdown) art = Executors.newSingleThreadExecutor()
        val sm = sessions ?: return
        try {
            sm.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            choose(sm.getActiveSessions(listenerComponent))
            started = true
        } catch (e: SecurityException) {
            Log.w(TAG, "Нет доступа к медиа-сессиям: ${e.message}")
        }
        Poweramp.currentModes(ctx)?.let { (sh, rp) ->
            powerampShuffle = sh
            powerampRepeat = rp
        }
    }

    fun stop() {
        try { sessions?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        detach()
        art.shutdownNow()
        started = false
    }

    private fun rescan() {
        try {
            sessions?.getActiveSessions(listenerComponent)?.let { choose(it) }
        } catch (_: SecurityException) {
        }
    }

    private fun detach() {
        controller?.let { try { it.unregisterCallback(callback) } catch (_: Exception) {} }
        controller = null
    }

    private fun choose(list: List<MediaController>) {
        if (list.isEmpty()) {
            detach()
            artKey = ""
            artJpeg = null
            return
        }
        val playing = list.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val pick = PREFERRED.firstNotNullOfOrNull { pkg ->
            playing.firstOrNull { it.packageName == pkg } ?: list.firstOrNull { it.packageName == pkg }
        } ?: playing.firstOrNull() ?: list.first()

        if (pick.sessionToken == controller?.sessionToken) return
        detach()
        controller = pick
        try { pick.registerCallback(callback, handler) } catch (_: Exception) {}
        scheduleArt()
    }

    // ---------------------------------------------------------------- состояние

    fun snapshot(): PlayerState {
        val c = controller ?: return PlayerState(
            hasSession = false,
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            updatedAt = SystemClock.elapsedRealtime(),
            shuffle = -1,
            repeat = -1
        )

        val md = c.metadata
        val pb = c.playbackState
        val playing = pb?.state == PlaybackState.STATE_PLAYING
        val actions = pb?.actions ?: 0L

        // Позиция в PlaybackState зафиксирована на момент lastPositionUpdateTime,
        // доводим её до «сейчас».
        val base = pb?.position ?: 0L
        val speed = pb?.playbackSpeed ?: 1f
        val since = if (pb != null && pb.lastPositionUpdateTime > 0)
            SystemClock.elapsedRealtime() - pb.lastPositionUpdateTime else 0L
        val pos = if (playing) base + (since * speed).toLong() else base

        val rating = md?.getRating(MediaMetadata.METADATA_KEY_RATING)?.let { r ->
            when {
                !r.isRated -> 0
                r.ratingStyle == Rating.RATING_5_STARS -> r.starRating.toInt()
                r.ratingStyle == Rating.RATING_HEART -> if (r.hasHeart()) 5 else 0
                r.ratingStyle == Rating.RATING_THUMB_UP_DOWN -> if (r.isThumbUp) 5 else 0
                else -> 0
            }
        } ?: -1

        val poweramp = c.packageName == Poweramp.PKG
        val shuffle = when {
            poweramp && powerampShuffle >= 0 -> Poweramp.shuffleToUi(powerampShuffle)
            customAction("shuffle") != null -> -1   // переключать можем, состояние — нет
            else -> -1
        }
        val repeat = when {
            poweramp && powerampRepeat >= 0 -> Poweramp.repeatToUi(powerampRepeat)
            customAction("repeat") != null -> -1
            else -> -1
        }

        return PlayerState(
            hasSession = true,
            appName = appLabel(c.packageName),
            pkg = c.packageName ?: "",
            playing = playing,
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty(),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            position = pos.coerceAtLeast(0L),
            duration = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L),
            speed = speed,
            updatedAt = SystemClock.elapsedRealtime(),
            volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            shuffle = shuffle,
            repeat = repeat,
            rating = if (poweramp && rating < 0) 0 else rating,
            artKey = artKey,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L || poweramp,
            canRate = rating >= 0 || poweramp,
            canMode = poweramp || customAction("shuffle") != null || customAction("repeat") != null
        )
    }

    /** Ищет в PlaybackState действие плеера по куску имени: "shuffle", "repeat". */
    private fun customAction(needle: String): String? =
        controller?.playbackState?.customActions
            ?.firstOrNull { it.action.contains(needle, ignoreCase = true) }
            ?.action

    private fun appLabel(pkg: String?): String {
        if (pkg.isNullOrEmpty()) return ""
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    // ----------------------------------------------------------------- обложка

    /** Декодирование и сжатие идут в своём потоке: на M33 это сотни миллисекунд. */
    private fun scheduleArt() {
        val md = controller?.metadata
        if (md == null) {
            artKey = ""
            artJpeg = null
            return
        }
        val key = listOf(
            md.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        ).joinToString("|")
        if (key == artKey && artJpeg != null) return

        val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)

        artKey = key
        artJpeg = null
        if (art.isShutdown) return
        try {
            art.execute {
                val src = bmp ?: uri?.let { loadUri(it) }
                val data = src?.let { encode(it) }
                if (artKey == key) {
                    artJpeg = data
                    onChanged()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun loadUri(uri: String): Bitmap? = try {
        ctx.contentResolver.openInputStream(Uri.parse(uri)).use {
            BitmapFactory.decodeStream(it)
        }
    } catch (_: Exception) {
        null
    }

    private fun encode(src: Bitmap): ByteArray? = try {
        val side = maxOf(src.width, src.height)
        val bmp = if (side > ART_MAX_PX) {
            val k = ART_MAX_PX.toFloat() / side
            Bitmap.createScaledBitmap(
                src,
                (src.width * k).toInt().coerceAtLeast(1),
                (src.height * k).toInt().coerceAtLeast(1),
                true
            )
        } else src
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            out.toByteArray()
        }
    } catch (_: Throwable) {
        null
    }

    // ----------------------------------------------------------------- команды

    private fun tc() = controller?.transportControls
    private fun isPoweramp() = controller?.packageName == Poweramp.PKG
    private fun noSession() = controller == null

    fun toggle() {
        val c = controller
        if (c == null) {
            Poweramp.send(ctx, Poweramp.TOGGLE_PLAY_PAUSE)
            return
        }
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun play() {
        if (noSession()) Poweramp.send(ctx, Poweramp.RESUME) else tc()?.play()
    }

    fun pause() {
        if (noSession()) Poweramp.send(ctx, Poweramp.PAUSE) else tc()?.pause()
    }

    fun next() {
        if (noSession()) Poweramp.send(ctx, Poweramp.NEXT) else tc()?.skipToNext()
    }

    fun prev() {
        if (noSession()) Poweramp.send(ctx, Poweramp.PREVIOUS) else tc()?.skipToPrevious()
    }

    fun seek(ms: Long) {
        val v = ms.coerceAtLeast(0L)
        val c = controller
        val seekable = (c?.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L
        if (c != null && seekable) c.transportControls.seekTo(v)
        else if (c == null || isPoweramp()) Poweramp.seekSeconds(ctx, (v / 1000).toInt())
    }

    fun setVolume(v: Int) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, max), 0)
    }

    fun nudgeVolume(delta: Int) {
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (delta >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0
        )
    }

    fun toggleShuffle() {
        if (isPoweramp() || noSession()) {
            Poweramp.send(ctx, Poweramp.SHUFFLE)
            return
        }
        customAction("shuffle")?.let { tc()?.sendCustomAction(it, null) }
    }

    fun cycleRepeat() {
        if (isPoweramp() || noSession()) {
            Poweramp.send(ctx, Poweramp.REPEAT)
            return
        }
        customAction("repeat")?.let { tc()?.sendCustomAction(it, null) }
    }

    fun setRating(stars: Int) {
        val v = stars.coerceIn(0, 5)
        if (isPoweramp() || noSession()) {
            Poweramp.rate(ctx, v)
            return
        }
        try {
            tc()?.setRating(Rating.newStarRating(Rating.RATING_5_STARS, v.toFloat()))
        } catch (_: Exception) {
        }
    }
}
