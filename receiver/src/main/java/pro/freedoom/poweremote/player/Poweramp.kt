package pro.freedoom.poweremote.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import pro.freedoom.poweremote.shared.LibFile
import pro.freedoom.poweremote.shared.LibFolder
import pro.freedoom.poweremote.shared.LibListing

/**
 * Открытый API Poweramp.
 *
 * Значения взяты из PowerampAPI.java и TableDefs.kt (maxmpz/powerampapi).
 * Команда передаётся целым числом в extra "cmd" — строковые имена понимают
 * только сборки 867+.
 */
object Poweramp {
    private const val TAG = "Poweramp"

    const val PKG = "com.maxmpz.audioplayer"
    private const val API_RECEIVER = "com.maxmpz.audioplayer.player.PowerampAPIReceiver"
    private const val ACTION_COMMAND = "com.maxmpz.audioplayer.API_COMMAND"
    private const val ACTION_ASK_DATA = "com.maxmpz.audioplayer.ACTION_ASK_FOR_DATA_PERMISSION"
    private const val API_ACTIVITY = "com.maxmpz.audioplayer.PowerampAPIActivity"

    const val ACTION_MODE_CHANGED = "com.maxmpz.audioplayer.PLAYING_MODE_CHANGED"

    private const val EXTRA_COMMAND = "cmd"
    private const val EXTRA_POSITION = "pos"     // ВНИМАНИЕ: в секундах
    private const val EXTRA_RATING = "rating"
    private const val EXTRA_SECONDS = "seconds"
    private const val EXTRA_PLAY_TO_END = "play_to_end"
    private const val EXTRA_PACKAGE = "pak"
    const val EXTRA_SHUFFLE = "shuffle"
    const val EXTRA_REPEAT = "repeat"

    const val TOGGLE_PLAY_PAUSE = 1
    const val PAUSE = 2
    const val RESUME = 3
    const val NEXT = 4
    const val PREVIOUS = 5
    const val NEXT_IN_CAT = 6
    const val PREVIOUS_IN_CAT = 7
    const val REPEAT = 8
    const val SHUFFLE = 9
    const val SEEK = 15
    const val SLEEP_TIMER = 17
    const val LIKE = 18
    const val UNLIKE = 19
    const val OPEN_TO_PLAY = 20
    const val SET_RATING = 24

    /** Провайдер данных Poweramp (библиотека). */
    private val ROOT: Uri = Uri.parse("content://com.maxmpz.audioplayer.data")

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

    /** 0 секунд — отключить таймер. Poweramp сам плавно гасит громкость. */
    fun sleepTimer(ctx: Context, seconds: Int, playToEnd: Boolean) =
        send(ctx, SLEEP_TIMER) {
            it.putExtra(EXTRA_SECONDS, seconds.coerceAtLeast(0))
            it.putExtra(EXTRA_PLAY_TO_END, playToEnd)
        }

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

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------ библиотека

    /**
     * Просит Poweramp выдать этому приложению доступ к своей базе.
     * Poweramp показывает диалог на экране плеера — подтвердить нужно там.
     */
    fun askDataPermission(ctx: Context, fromForeground: Boolean = false) {
        val i = Intent(ACTION_ASK_DATA)
            .setPackage(PKG)
            .putExtra(EXTRA_PACKAGE, ctx.packageName)
        try {
            if (fromForeground) {
                // С экрана приёмника надёжнее открыть диалог Poweramp напрямую.
                ctx.startActivity(
                    Intent(i).setComponent(ComponentName(PKG, API_ACTIVITY))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                ctx.sendBroadcast(i)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Запрос доступа не ушёл: ${e.message}")
            try { ctx.sendBroadcast(i) } catch (_: Exception) {}
        }
    }

    /** Доступ к базе выдан? Проверяем самым дешёвым запросом. */
    fun canBrowse(ctx: Context): Boolean = try {
        ctx.contentResolver.query(
            ROOT.buildUpon().appendEncodedPath("folders_hier/0/subfolders").build(),
            arrayOf("folders._id"), null, null, null
        )?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    /**
     * Содержимое папки в иерархическом виде. id = 0 — корень (только подпапки).
     * Колонки — из TableDefs.kt; провайдер принимает имена с префиксом таблицы.
     */
    fun browse(ctx: Context, folderId: Long): LibListing {
        val cr = ctx.contentResolver
        val folders = ArrayList<LibFolder>()
        val files = ArrayList<LibFile>()
        var name = ""
        var parent = LibListing.ROOT_ID

        try {
            if (folderId != LibListing.ROOT_ID) {
                cr.query(
                    ROOT.buildUpon().appendEncodedPath("folders/$folderId").build(),
                    arrayOf("folders.name", "folders.parent_id"), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0) ?: ""
                        parent = if (c.isNull(1)) LibListing.ROOT_ID else c.getLong(1)
                    }
                }
            }

            cr.query(
                ROOT.buildUpon().appendEncodedPath("folders_hier/$folderId/subfolders").build(),
                arrayOf("folders._id", "folders.name", "folders.num_subfolders", "folders.num_files"),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    folders += LibFolder(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "",
                        subfolders = if (c.isNull(2)) 0 else c.getInt(2),
                        files = if (c.isNull(3)) 0 else c.getInt(3)
                    )
                }
            }

            if (folderId != LibListing.ROOT_ID) {
                cr.query(
                    ROOT.buildUpon().appendEncodedPath("folders_hier/$folderId/files").build(),
                    arrayOf("folder_files._id", "folder_files.name", "title_tag", "artist_tag", "folder_files.duration"),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val title = c.getString(2)?.takeIf { it.isNotBlank() } ?: c.getString(1) ?: ""
                        files += LibFile(
                            id = c.getLong(0),
                            name = title,
                            artist = c.getString(3) ?: "",
                            durationSec = if (c.isNull(4)) 0 else (c.getInt(4) / 1000)
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            return LibListing(folderId, name, parent, emptyList(), emptyList(), "no_permission")
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка чтения библиотеки: ${e.message}")
            return LibListing(folderId, name, parent, folders, files, "error")
        }
        folders.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return LibListing(folderId, name, parent, folders, files)
    }

    fun playFolder(ctx: Context, folderId: Long) = send(ctx, OPEN_TO_PLAY) {
        it.data = ROOT.buildUpon().appendEncodedPath("folders_hier/$folderId/files").build()
    }

    fun playFile(ctx: Context, folderId: Long, fileId: Long) = send(ctx, OPEN_TO_PLAY) {
        it.data = ROOT.buildUpon().appendEncodedPath("folders_hier/$folderId/files/$fileId").build()
    }
}
