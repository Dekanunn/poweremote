package pro.freedoom.poweremote.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Поднимает приёмник после перезагрузки плеера, если пользователь это включил. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Prefs.AUTOSTART, true)) {
            try { ReceiverService.start(context) } catch (_: Exception) {}
        }
    }
}

object Prefs {
    const val NAME = "receiver"
    const val AUTOSTART = "autostart"
}
