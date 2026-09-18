package pro.freedoom.poweremote.remote

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette

/**
 * Три оформления:
 *  • Material You — палитра из обоев телефона, светлая/тёмная вслед за системой;
 *  • Цвет обложки — фон и акцент подбираются под обложку текущего трека;
 *  • Классическая тёмная — как в первой версии.
 *
 * Дальше по коду цвета берутся только из MaterialTheme.colorScheme:
 *  background — фон, surfaceVariant — карточки и кнопки, primary — акцент,
 *  onSurfaceVariant — приглушённый текст.
 */
@Composable
fun PoweRemoteTheme(mode: ThemeMode, art: Bitmap?, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when (mode) {
        ThemeMode.MATERIAL_YOU ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else ClassicDark
        ThemeMode.COVER -> {
            // Palette считает в фоне: на большой обложке это десятки миллисекунд,
            // а тема меняется ровно в момент смены трека — дёргать кадр незачем.
            var computed by remember { mutableStateOf(ClassicDark) }
            LaunchedEffect(art) {
                computed = withContext(Dispatchers.Default) { coverScheme(art) }
            }
            computed
        }
        ThemeMode.DARK -> ClassicDark
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

val ClassicDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFB794F6),
    onPrimary = Color(0xFF14121A),
    background = Color(0xFF0B0B0F),
    onBackground = Color.White,
    surface = Color(0xFF0B0B0F),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF16171D),
    onSurfaceVariant = Color(0xFF9AA0AC),
    outline = Color(0xFF3A3D46)
)

/**
 * Палитра из обложки. Фон — доминирующий цвет, затемнённый до читаемости,
 * акцент — самый «живой» цвет. Без обложки — классическая тёмная.
 */
fun coverScheme(art: Bitmap?): ColorScheme {
    if (art == null) return ClassicDark
    val palette = try {
        Palette.from(art).maximumColorCount(24).generate()
    } catch (_: Throwable) {
        return ClassicDark
    }
    val dominant = palette.getDominantColor(0xFF0B0B0F.toInt())
    val accent = palette.getVibrantColor(
        palette.getLightVibrantColor(
            palette.getLightMutedColor(0xFFB794F6.toInt())
        )
    )

    val bg = withLightness(dominant, 0.07f, satMax = 0.45f)
    val card = withLightness(dominant, 0.14f, satMax = 0.40f)
    val outline = withLightness(dominant, 0.28f, satMax = 0.35f)
    val primary = withLightness(accent, 0.72f, satMin = 0.35f)
    val muted = withLightness(accent, 0.80f, satMax = 0.15f)

    return darkColorScheme(
        primary = Color(primary),
        onPrimary = Color(bg),
        secondary = Color(primary),
        background = Color(bg),
        onBackground = Color.White,
        surface = Color(bg),
        onSurface = Color.White,
        surfaceVariant = Color(card),
        onSurfaceVariant = Color(muted),
        outline = Color(outline)
    )
}

/** Тот же тон, но заданная светлота (HSL) и ограниченная насыщенность. */
private fun withLightness(color: Int, l: Float, satMin: Float = 0f, satMax: Float = 1f): Int {
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
    hsl[1] = hsl[1].coerceIn(satMin, satMax)
    hsl[2] = l
    return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
}
