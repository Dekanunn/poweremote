package pro.freedoom.poweremote.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * Открытый API Poweramp.
 *
 * Значения взяты из PowerampAPI.java (maxmpz/powerampapi). Команда передаётся
 * целым числом в extra "cmd" — строковые имена понимают только сборки 867+.
 */
object Poweramp {
    private const val TAG = "Poweramp"

    const val PKG = "com.maxmpz.audioplayer"
    private const val API_RECEIVER = "com.maxmpz.audioplayer.player.PowerampAPIReceiver"
    private const val ACTION_COMMAND = "com.maxmpz.audioplayer.API_COMMAND"

    const val ACTION_MODE_CHANGED = "com.maxmpz.audioplayer.PLAYING_MODE_CHANGED"

    private const val EXTRA_COMMAND = "cmd"
    private const val EXTRA_POSITION = "pos"     // ВНИМАНИЕ: в секундах
    private const val EXTRA_RATING = "rating"
    const val EXTRA_SHUFFLE = "shuffle"
    const val EXTRA_REPEAT = "repeat"

    const val TOGGLE_PLAY_PAUSE = 1
    const val PAUSE = 2
    const val RESUME = 3
    const val NEXT = 4
    const val PREVIOUS = 5
    const val REPEAT = 8
    const val SHUFFLE = 9
    const val SEEK = 15
    const val SET_RATING = 24

    /** Poweramp: 0 — без повтора, 3 и 4 — один трек, остальное — список. */
    fun repeatToUi(mode: Int): Int = when (mode) {
        0 -> 0
        3, 4 -> 2
        else -> 1
    }

    /** Poweramp: 0 — без перемешивания. */
    fun shuffleToUi(mode: Int): Int = if (mode == 0) 0 else 1

    fun send(ctx: Context, command: Int, extras: (Intent) -> Unit = {}) {
        try {
            val i = Intent(ACTION_COMMAND)
                .setComponent(ComponentName(PKG, API_RECEIVER))
                .putExtra(EXTRA_COMMAND, command)
            extras(i)
            ctx.sendBroadcast(i)
        } catch (e: Exception) {
            Log.w(TAG, "Команда не доставлена: ${e.message}")
        }
    }

    fun seekSeconds(ctx: Context, seconds: Int) =
        send(ctx, SEEK) { it.putExtra(EXTRA_POSITION, seconds) }

    fun rate(ctx: Context, stars: Int) =
        send(ctx, SET_RATING) { it.putExtra(EXTRA_RATING, stars.coerceIn(0, 5)) }

    /**
     * PLAYING_MODE_CHANGED — sticky-интент, поэтому текущие режимы можно
     * прочитать сразу, не дожидаясь следующего переключения.
     */
    fun currentModes(ctx: Context): Pair<Int, Int>? = try {
        @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
        val sticky = ctx.registerReceiver(null, IntentFilter(ACTION_MODE_CHANGED))
        if (sticky == null) null
        else sticky.getIntExtra(EXTRA_SHUFFLE, 0) to sticky.getIntExtra(EXTRA_REPEAT, 0)
    } catch (_: Exception) {
        null
    }
}
