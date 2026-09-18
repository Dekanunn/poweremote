package pro.freedoom.poweremote.shared

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
    /** 0..5, -1 если плеер рейтинг не отдаёт. */
    val rating: Int = -1,
    /** Ключ обложки: сменился — значит придёт новый кадр с картинкой. */
    val artKey: String = "",
    val canSeek: Boolean = true,
    val canRate: Boolean = false,
    /** Умеет ли плеер вообще переключать shuffle/repeat. */
    val canMode: Boolean = false
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
            canMode = o.optBoolean("cm", false)
        )
    }
}

/** Команды пульта. Строки, чтобы протокол оставался читаемым при отладке. */
object Cmd {
    const val KEY = "c"
    const val VALUE = "v"

    const val HELLO = "hello"
    const val SYNC = "sync"
    const val PING = "ping"
    const val TOGGLE = "toggle"
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val NEXT = "next"
    const val PREV = "prev"
    const val SEEK = "seek"
    const val VOLUME = "vol"
    const val VOLUME_DELTA = "voldelta"
    const val SHUFFLE = "shuffle"
    const val REPEAT = "repeat"
    const val RATING = "rating"

    fun of(cmd: String, value: Long? = null): JSONObject = JSONObject().apply {
        put(KEY, cmd)
        if (value != null) put(VALUE, value)
    }
}
