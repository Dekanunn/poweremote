package pro.freedoom.poweremote.remote

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import pro.freedoom.poweremote.shared.Cmd
import pro.freedoom.poweremote.shared.PlayerState
import java.io.File
import java.io.FileOutputStream

/**
 * Виджеты рабочего стола. Два провайдера, чтобы в списке виджетов были оба
 * размера, но раскладка у обоих адаптивная: растянул — получил большой.
 *
 * Последнее состояние держим в SharedPreferences и файле обложки: виджет
 * должен рисоваться и после того, как система убила процесс сервиса.
 */
open class NowPlayingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, build(context, manager, it)) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        manager.updateAppWidget(id, build(context, manager, id))
    }

    companion object {
        private const val PREFS = "widget"
        private const val ART_FILE = "widget_art.jpg"
        private const val ART_PX = 192

        @Volatile private var lastSignature = ""
        @Volatile private var lastArtKey: String? = null

        /** Вызывается сервисом при каждом изменении состояния. */
        fun push(ctx: Context, s: PlayerState?, art: Bitmap?, connected: Boolean) {
            val sig = listOf(
                connected, s?.title, s?.artist, s?.album, s?.playing, s?.artKey,
                s?.volume, s?.hasSession, LinkBus.deviceName.value
            ).joinToString("|")
            if (sig == lastSignature) return
            lastSignature = sig

            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            p.putBoolean("connected", connected)
            p.putBoolean("playing", s?.playing == true)
            p.putBoolean("has", s?.hasSession == true)
            p.putString("title", s?.title ?: "")
            p.putString("artist", s?.artist ?: "")
            p.putString("album", s?.album ?: "")
            p.putString("device", LinkBus.deviceName.value)
            p.putInt("volume", s?.volume ?: -1)
            p.putInt("volumeMax", s?.volumeMax ?: 15)
            p.putInt("battery", s?.battery ?: -1)
            val artKey = if (art == null) "" else s?.artKey ?: ""
            p.putString("artKey", artKey)
            p.apply()

            if (artKey != lastArtKey) {
                lastArtKey = artKey
                saveArt(ctx, art)
            }
            updateAll(ctx)
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            for (cls in listOf(WidgetSmall::class.java, WidgetLarge::class.java)) {
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
                ids.forEach { id ->
                    try { mgr.updateAppWidget(id, build(ctx, mgr, id)) } catch (_: Exception) {}
                }
            }
        }

        private fun saveArt(ctx: Context, art: Bitmap?) {
            val f = File(ctx.cacheDir, ART_FILE)
            if (art == null) {
                f.delete()
                return
            }
            try {
                val side = maxOf(art.width, art.height)
                val bmp = if (side > ART_PX) {
                    val k = ART_PX.toFloat() / side
                    Bitmap.createScaledBitmap(
                        art, (art.width * k).toInt().coerceAtLeast(1),
                        (art.height * k).toInt().coerceAtLeast(1), true
                    )
                } else art
                FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            } catch (_: Exception) {
                f.delete()
            }
        }

        private fun loadArt(ctx: Context): Bitmap? = try {
            val f = File(ctx.cacheDir, ART_FILE)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
        } catch (_: Exception) {
            null
        }

        /** Собирает RemoteViews под текущий размер виджета. */
        fun build(ctx: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
            val small = fill(ctx, RemoteViews(ctx.packageName, R.layout.widget_small), large = false)
            val large = fill(ctx, RemoteViews(ctx.packageName, R.layout.widget_large), large = true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Система сама выберет раскладку по фактическому размеру.
                return RemoteViews(
                    mapOf(
                        SizeF(180f, 48f) to small,
                        SizeF(180f, 110f) to large
                    )
                )
            }
            val opts = try { mgr.getAppWidgetOptions(id) } catch (_: Exception) { null }
            val minH = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            return if (minH >= 100) large else small
        }

        private fun fill(ctx: Context, rv: RemoteViews, large: Boolean): RemoteViews {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val connected = p.getBoolean("connected", false)
            val has = p.getBoolean("has", false)
            val playing = p.getBoolean("playing", false)
            val title = p.getString("title", "") ?: ""
            val artist = p.getString("artist", "") ?: ""
            val album = p.getString("album", "") ?: ""
            val device = p.getString("device", "") ?: ""
            val battery = p.getInt("battery", -1)

            rv.setTextViewText(
                R.id.title,
                when {
                    !connected -> "Пульт M33"
                    !has || title.isEmpty() -> "Ничего не играет"
                    else -> title
                }
            )
            rv.setTextViewText(
                R.id.subtitle,
                when {
                    !connected -> "Нет связи — нажмите, чтобы подключиться"
                    title.isEmpty() -> device
                    else -> listOf(artist, album).filter { it.isNotEmpty() }.joinToString(" · ")
                }
            )
            if (large) {
                val bat = if (battery >= 0) " · 🔋 $battery%" else ""
                rv.setTextViewText(R.id.status, if (connected) device + bat else "")
            }

            val art = if (connected) loadArt(ctx) else null
            if (art != null) rv.setImageViewBitmap(R.id.art, art)
            else rv.setImageViewResource(R.id.art, R.drawable.ic_music)

            rv.setImageViewResource(R.id.play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)

            rv.setOnClickPendingIntent(R.id.prev, ControlReceiver.pending(ctx, Cmd.PREV))
            rv.setOnClickPendingIntent(R.id.play, ControlReceiver.pending(ctx, Cmd.TOGGLE))
            rv.setOnClickPendingIntent(R.id.next, ControlReceiver.pending(ctx, Cmd.NEXT))
            rv.setOnClickPendingIntent(R.id.art, ControlReceiver.openApp(ctx))
            rv.setOnClickPendingIntent(R.id.title, ControlReceiver.openApp(ctx))
            rv.setOnClickPendingIntent(R.id.subtitle, ControlReceiver.openApp(ctx))
            if (large) {
                rv.setOnClickPendingIntent(R.id.vol_down, ControlReceiver.pending(ctx, Cmd.VOLUME_DELTA, -1L))
                rv.setOnClickPendingIntent(R.id.vol_up, ControlReceiver.pending(ctx, Cmd.VOLUME_DELTA, 1L))
            }
            return rv
        }
    }
}

/** Компактный виджет 4×1. */
class WidgetSmall : NowPlayingWidget()

/** Крупный виджет 4×2. */
class WidgetLarge : NowPlayingWidget()
