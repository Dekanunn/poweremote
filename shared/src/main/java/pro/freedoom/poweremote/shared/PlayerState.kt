package pro.freedoom.poweremote.shared

import org.json.JSONArray
import org.json.JSONObject

/**
 * Снимок состояния плеера, который приёмник отправляет пульту.
 *
 * [position] и [updatedAt] идут в паре: пульт достраивает позицию локально,
 * поэтому полоса прогресса идёт плавно, а по Bluetooth летит один кадр в 2 секунды.
 */
data class PlayerState(
    val hasSession: Boolean = false,
    val appName: String = "",
    val pkg: String = "",
    val playing: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val position: Long = 0L,
    val duration: Long = 0L,
    val speed: Float = 1f,
    /** Время снимка по часам приёмника (SystemClock.elapsedRealtime). */
    val updatedAt: Long = 0L,
    val volume: Int = 0,
    val volumeMax: Int = 15,
    /** 0 — выключено, 1 — включено, -1 — плеер состояние не отдаёт. */
    val shuffle: Int = -1,
    /** 0 — нет, 1 — весь список, 2 — один трек, -1 — неизвестно. */
    val repeat: Int = -1,
    /** 0..5, -1 если плеер рейтинг не отдаёт. В Poweramp 1 = дизлайк, 5 = лайк. */
    val rating: Int = -1,
    /** Ключ обложки: сменился — значит придёт новый кадр с картинкой. */
    val artKey: String = "",
    val canSeek: Boolean = true,
    val canRate: Boolean = false,
    /** Умеет ли плеер вообще переключать shuffle/repeat. */
    val canMode: Boolean = false,
    /** Poweramp: доступны «следующая/предыдущая категория» и обзор папок. */
    val isPoweramp: Boolean = false,
    /** Обзор папок возможен (Poweramp выдал доступ к данным). */
    val canBrowse: Boolean = false,
    /** Заряд плеера в процентах, -1 если неизвестен. */
    val battery: Int = -1,
    val charging: Boolean = false,
    /** Секунд до срабатывания таймера сна, -1 если таймер не стоит. */
    val sleepLeft: Long = -1L,
    /** Таймер сна ждёт конца трека (после истечения времени). */
    val sleepToEnd: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("t", "state")
        put("has", hasSession)
        put("app", appName)
        put("pkg", pkg)
        put("play", playing)
        put("ti", title)
        put("ar", artist)
        put("al", album)
        put("pos", position)
        put("dur", duration)
        put("sp", speed.toDouble())
        put("up", updatedAt)
        put("vol", volume)
        put("volm", volumeMax)
        put("sh", shuffle)
        put("rp", repeat)
        put("rt", rating)
        put("art", artKey)
        put("cs", canSeek)
        put("cr", canRate)
        put("cm", canMode)
        put("pa", isPoweramp)
        put("cb", canBrowse)
        put("bat", battery)
        put("chg", charging)
        put("sl", sleepLeft)
        put("sle", sleepToEnd)
    }

    companion object {
        fun fromJson(o: JSONObject): PlayerState = PlayerState(
            hasSession = o.optBoolean("has", false),
            appName = o.optString("app", ""),
            pkg = o.optString("pkg", ""),
            playing = o.optBoolean("play", false),
            title = o.optString("ti", ""),
            artist = o.optString("ar", ""),
            album = o.optString("al", ""),
            position = o.optLong("pos", 0L),
            duration = o.optLong("dur", 0L),
            speed = o.optDouble("sp", 1.0).toFloat(),
            updatedAt = o.optLong("up", 0L),
            volume = o.optInt("vol", 0),
            volumeMax = o.optInt("volm", 15),
            shuffle = o.optInt("sh", -1),
            repeat = o.optInt("rp", -1),
            rating = o.optInt("rt", -1),
            artKey = o.optString("art", ""),
            canSeek = o.optBoolean("cs", true),
            canRate = o.optBoolean("cr", false),
            canMode = o.optBoolean("cm", false),
            isPoweramp = o.optBoolean("pa", false),
            canBrowse = o.optBoolean("cb", false),
            battery = o.optInt("bat", -1),
            charging = o.optBoolean("chg", false),
            sleepLeft = o.optLong("sl", -1L),
            sleepToEnd = o.optBoolean("sle", false)
        )
    }
}

/** Папка библиотеки плеера (обзор папок Poweramp). */
data class LibFolder(val id: Long, val name: String, val subfolders: Int, val files: Int)

/** Файл в папке библиотеки. */
data class LibFile(val id: Long, val name: String, val artist: String, val durationSec: Int)

/** Ответ приёмника на команду BROWSE: содержимое одной папки. */
data class LibListing(
    val folderId: Long,
    val name: String,
    val parentId: Long,
    val folders: List<LibFolder>,
    val files: List<LibFile>,
    /** Непустое — доступ к данным не выдан или произошла ошибка. */
    val error: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("t", "list")
        put("id", folderId)
        put("name", name)
        put("parent", parentId)
        put("err", error)
        put("folders", JSONArray().also { a ->
            folders.forEach { f ->
                a.put(JSONObject().put("id", f.id).put("n", f.name).put("sf", f.subfolders).put("nf", f.files))
            }
        })
        put("files", JSONArray().also { a ->
            files.forEach { f ->
                a.put(JSONObject().put("id", f.id).put("n", f.name).put("ar", f.artist).put("d", f.durationSec))
            }
        })
    }

    companion object {
        const val ROOT_ID = 0L

        fun fromJson(o: JSONObject): LibListing {
            val folders = ArrayList<LibFolder>()
            o.optJSONArray("folders")?.let { a ->
                for (i in 0 until a.length()) {
                    val f = a.getJSONObject(i)
                    folders += LibFolder(f.optLong("id"), f.optString("n"), f.optInt("sf"), f.optInt("nf"))
                }
            }
            val files = ArrayList<LibFile>()
            o.optJSONArray("files")?.let { a ->
                for (i in 0 until a.length()) {
                    val f = a.getJSONObject(i)
                    files += LibFile(f.optLong("id"), f.optString("n"), f.optString("ar"), f.optInt("d"))
                }
            }
            return LibListing(
                folderId = o.optLong("id", ROOT_ID),
                name = o.optString("name", ""),
                parentId = o.optLong("parent", ROOT_ID),
                folders = folders,
                files = files,
                error = o.optString("err", "")
            )
        }
    }
}

/** Команды пульта. Строки, чтобы протокол оставался читаемым при отладке. */
object Cmd {
    const val KEY = "c"
    const val VALUE = "v"
    /** Второй параметр (например, id папки для файла). */
    const val EXTRA = "x"

    const val HELLO = "hello"
    const val SYNC = "sync"
    const val PING = "ping"
    const val TOGGLE = "toggle"
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val NEXT = "next"
    const val PREV = "prev"
    const val SEEK = "seek"
    /** Относительная перемотка, v — смещение в миллисекундах (может быть < 0). */
    const val SEEK_REL = "seekrel"
    const val VOLUME = "vol"
    const val VOLUME_DELTA = "voldelta"
    const val SHUFFLE = "shuffle"
    const val REPEAT = "repeat"
    const val RATING = "rating"
    const val LIKE = "like"
    const val UNLIKE = "unlike"
    /** Poweramp: следующая/предыдущая категория (папка, альбом…). */
    const val NEXT_CAT = "nextcat"
    const val PREV_CAT = "prevcat"
    /** Таймер сна: v — секунды (0 — отменить), x = 1 — доиграть трек до конца. */
    const val SLEEP = "sleep"
    /** Обзор папок: v — id папки (0 — корень). Ответ — сообщение "list". */
    const val BROWSE = "browse"
    /** Играть папку целиком: v — id папки. */
    const val PLAY_FOLDER = "playfolder"
    /** Играть файл: v — id файла, x — id папки. */
    const val PLAY_FILE = "playfile"
    /** Попросить Poweramp выдать доступ к данным (диалог на плеере). */
    const val ASK_DATA = "askdata"

    fun of(cmd: String, value: Long? = null, extra: Long? = null): JSONObject = JSONObject().apply {
        put(KEY, cmd)
        if (value != null) put(VALUE, value)
        if (extra != null) put(EXTRA, extra)
    }
}
