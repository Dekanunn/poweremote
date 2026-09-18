package pro.freedoom.poweremote.remote

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import pro.freedoom.poweremote.shared.Cmd

/**
 * Одна точка входа для кнопок вне приложения: уведомление, окно
 * картинка-в-картинке, виджеты. Все они шлют сюда broadcast с именем команды.
 */
class ControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "pro.freedoom.poweremote.remote.CONTROL"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_VALUE = "value"

        /** Уникальный requestCode на команду, иначе PendingIntent'ы склеятся. */
        private fun code(cmd: String, value: Long?): Int =
            (cmd.hashCode() * 31 + (value?.toInt() ?: 0)) and 0x7fffffff

        fun pending(ctx: Context, cmd: String, value: Long? = null): PendingIntent {
            val i = Intent(ctx, ControlReceiver::class.java)
                .setAction(ACTION)
                .putExtra(EXTRA_CMD, cmd)
            if (value != null) i.putExtra(EXTRA_VALUE, value)
            return PendingIntent.getBroadcast(
                ctx, code(cmd, value), i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        /** Открыть главный экран пульта. */
        fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val cmd = intent.getStringExtra(EXTRA_CMD) ?: return
        val value = if (intent.hasExtra(EXTRA_VALUE)) intent.getLongExtra(EXTRA_VALUE, 0L) else null

        if (LinkBus.status.value == LinkStatus.CONNECTED) {
            LinkService.send(cmd, value)
            return
        }
        // Сервис не запущен (например, после перезагрузки) — поднимаем связь.
        // Команду в этом случае не теряем: сервис отправит её сразу после подключения.
        val mac = Prefs(context).mac ?: return
        LinkService.queueAfterConnect(Cmd.of(cmd, value))
        LinkService.connect(context, mac)
    }
}
