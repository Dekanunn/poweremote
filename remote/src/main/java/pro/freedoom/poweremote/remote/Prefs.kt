package pro.freedoom.poweremote.remote

import android.content.Context
import android.content.SharedPreferences

/** Варианты оформления пульта. */
enum class ThemeMode { MATERIAL_YOU, COVER, DARK }

/** Настройки пульта. Простые SharedPreferences — их немного. */
class Prefs(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE)

    var mac: String?
        get() = p.getString("mac", null)
        set(v) = p.edit().putString("mac", v).apply()

    var deviceName: String
        get() = p.getString("name", "") ?: ""
        set(v) = p.edit().putString("name", v).apply()

    var theme: ThemeMode
        get() = try {
            ThemeMode.valueOf(p.getString("theme", ThemeMode.MATERIAL_YOU.name) ?: "")
        } catch (_: Exception) {
            ThemeMode.MATERIAL_YOU
        }
        set(v) = p.edit().putString("theme", v.name).apply()

    var keepScreenOn: Boolean
        get() = p.getBoolean("keep_screen", false)
        set(v) = p.edit().putBoolean("keep_screen", v).apply()

    /** Сворачивать в окно поверх других приложений при уходе с экрана. */
    var autoPip: Boolean
        get() = p.getBoolean("auto_pip", true)
        set(v) = p.edit().putBoolean("auto_pip", v).apply()

    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()

    /** Показывать полный ряд звёзд рейтинга (кроме лайка/дизлайка). */
    var showStars: Boolean
        get() = p.getBoolean("stars", false)
        set(v) = p.edit().putBoolean("stars", v).apply()

    companion object {
        const val NAME = "remote"
    }
}
