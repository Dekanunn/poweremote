package pro.freedoom.poweremote.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.Rating
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import pro.freedoom.poweremote.shared.Cmd
import pro.freedoom.poweremote.shared.PlayerState

/**
 * Медиа-сессия на телефоне, зеркалящая плеер.
 *
 * Благодаря ей система считает, что музыка играет «здесь»: Samsung показывает
 * Now Playing / Now Bar и плеер на экране блокировки, кнопки наушников и часов
 * идут в плеер, а качелька громкости и ползунок в шторке двигают громкость M33
 * через VolumeProvider — телефон о своей громкости при этом не думает.
 */
class PhoneSession(
    private val ctx: Context,
    private val send: (cmd: String, value: Long?) -> Unit
) {
    companion object {
        const val CHANNEL = "nowplaying"
        const val NOTIF_ID = 7

        private const val ACT_SHUFFLE = "shuffle"
        private const val ACT_REPEAT = "repeat"
        private const val ACT_LIKE = "like"
        private const val ACT_UNLIKE = "unlike"
        private const val ACT_BACK10 = "back10"
        private const val ACT_FWD10 = "fwd10"
    }

    val session: MediaSession = MediaSession(ctx, "PoweRemote")
    private var volume: RemoteVolume? = null
    private var lastMax = -1

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = send(Cmd.PLAY, null)
            override fun onPause() = send(Cmd.PAUSE, null)
            override fun onStop() = send(Cmd.PAUSE, null)
            override fun onSkipToNext() = send(Cmd.NEXT, null)
            override fun onSkipToPrevious() = send(Cmd.PREV, null)
            override fun onSeekTo(pos: Long) = send(Cmd.SEEK, pos)
            override fun onFastForward() = send(Cmd.SEEK_REL, 10_000L)
            override fun onRewind() = send(Cmd.SEEK_REL, -10_000L)
            override fun onSetRating(rating: Rating) {
                val stars = when {
                    !rating.isRated -> 0
                    rating.ratingStyle == Rating.RATING_5_STARS -> rating.starRating.toInt()
                    rating.ratingStyle == Rating.RATING_HEART -> if (rating.hasHeart()) 5 else 0
                    rating.ratingStyle == Rating.RATING_THUMB_UP_DOWN -> if (rating.isThumbUp) 5 else 1
                    else -> 0
                }
                send(Cmd.RATING, stars.toLong())
            }

            override fun onCustomAction(action: String, extras: Bundle?) {
                when (action) {
                    ACT_SHUFFLE -> send(Cmd.SHUFFLE, null)
                    ACT_REPEAT -> send(Cmd.REPEAT, null)
                    ACT_LIKE -> send(Cmd.LIKE, null)
                    ACT_UNLIKE -> send(Cmd.UNLIKE, null)
                    ACT_BACK10 -> send(Cmd.SEEK_REL, -10_000L)
                    ACT_FWD10 -> send(Cmd.SEEK_REL, 10_000L)
                }
            }
        })
        @Suppress("DEPRECATION")
        session.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        session.setSessionActivity(ControlReceiver.openApp(ctx))
        ensureChannel()
    }

    /** Громкость плеера как «удалённая» громкость сессии. */
    private inner class RemoteVolume(max: Int, current: Int) :
        VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, max, current) {
        override fun onSetVolumeTo(volume: Int) {
            currentVolume = volume.coerceIn(0, maxVolume)
            LinkBus.pendingVolumeValue = currentVolume
            LinkBus.pendingVolumeUntil = System.currentTimeMillis() + 1200
            send(Cmd.VOLUME, currentVolume.toLong())
        }

        override fun onAdjustVolume(direction: Int) {
            if (direction == 0) return
            currentVolume = (currentVolume + direction).coerceIn(0, maxVolume)
            LinkBus.pendingVolumeValue = currentVolume
            LinkBus.pendingVolumeUntil = System.currentTimeMillis() + 1200
            send(Cmd.VOLUME_DELTA, direction.toLong())
        }
    }

    fun setActive(active: Boolean) {
        try { session.isActive = active } catch (_: Exception) {}
    }

    fun release() {
        try { session.isActive = false } catch (_: Exception) {}
        try { session.release() } catch (_: Exception) {}
    }

    // ----------------------------------------------------------- обновление

    fun update(s: PlayerState?, art: Bitmap?, connected: Boolean) {
        // Громкость: VolumeProvider не умеет менять максимум — пересоздаём при смене.
        val max = (s?.volumeMax ?: 15).coerceAtLeast(1)
        if (max != lastMax || volume == null) {
            lastMax = max
            val v = RemoteVolume(max, (s?.volume ?: 0).coerceIn(0, max))
            volume = v
            try { session.setPlaybackToRemote(v) } catch (_: Exception) {}
        } else {
            volume?.currentVolume = (s?.volume ?: 0).coerceIn(0, max)
        }

        val md = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, s?.title?.ifEmpty { null }
                ?: if (connected) "Ничего не играет" else "Нет связи с плеером")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s?.artist ?: "")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, s?.album ?: "")
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, s?.artist ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, s?.duration ?: 0L)
        if (art != null) {
            md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            md.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
        }
        if (s != null && s.rating >= 0) {
            md.putRating(
                MediaMetadata.METADATA_KEY_USER_RATING,
                Rating.newStarRating(Rating.RATING_5_STARS, s.rating.toFloat())
            )
        }
        try { session.setMetadata(md.build()) } catch (_: Exception) {}

        val state = when {
            !connected || s == null -> PlaybackState.STATE_STOPPED
            !s.hasSession -> PlaybackState.STATE_NONE
            s.playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SET_RATING or
                PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND

        val pb = PlaybackState.Builder()
            .setState(
                state,
                if (s == null) 0L else LinkBus.positionNow(),
                if (s?.playing == true) s.speed else 0f,
                SystemClock.elapsedRealtime()
            )
            .setActions(actions)
            .addCustomAction(custom(ACT_BACK10, "−10 с", R.drawable.ic_replay10))
            .addCustomAction(custom(ACT_FWD10, "+10 с", R.drawable.ic_forward10))
        if (s?.canMode == true) {
            pb.addCustomAction(custom(ACT_SHUFFLE, "Перемешать", R.drawable.ic_shuffle))
            pb.addCustomAction(custom(ACT_REPEAT, "Повтор", R.drawable.ic_repeat))
        }
        if (s?.canRate == true) {
            pb.addCustomAction(
                custom(ACT_LIKE, "Нравится", if (s.rating == 5) R.drawable.ic_like_on else R.drawable.ic_like)
            )
        }
        try { session.setPlaybackState(pb.build()) } catch (_: Exception) {}
        setActive(connected)
    }

    private fun custom(action: String, name: String, icon: Int) =
        PlaybackState.CustomAction.Builder(action, name, icon).build()

    // ---------------------------------------------------------- уведомление

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Сейчас играет", NotificationManager.IMPORTANCE_LOW).also {
                    it.setShowBadge(false)
                    it.setSound(null, null)
                    it.enableVibration(false)
                }
            )
        }
    }

    /**
     * MediaStyle-уведомление — то самое, из которого система собирает
     * плеер в шторке, на экране блокировки и в Now Bar.
     */
    fun notification(s: PlayerState?, art: Bitmap?, statusText: String): Notification {
        val playing = s?.playing == true
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(
                s?.title?.ifEmpty { null } ?: statusText
            )
            .setContentText(
                listOfNotNull(s?.artist?.ifEmpty { null }, s?.album?.ifEmpty { null })
                    .joinToString(" · ").ifEmpty { statusText }
            )
            .setSubText(if (s?.title.isNullOrEmpty()) null else statusText)
            .setContentIntent(ControlReceiver.openApp(ctx))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(action(R.drawable.ic_prev, "Предыдущий", Cmd.PREV))
            .addAction(
                if (playing) action(R.drawable.ic_pause, "Пауза", Cmd.PAUSE)
                else action(R.drawable.ic_play, "Играть", Cmd.PLAY)
            )
            .addAction(action(R.drawable.ic_next, "Следующий", Cmd.NEXT))
        if (art != null) b.setLargeIcon(art)
        return b.build()
    }

    private fun action(icon: Int, title: String, cmd: String): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(ctx, icon), title, ControlReceiver.pending(ctx, cmd)
        ).build()
}
