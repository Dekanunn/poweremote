package pro.freedoom.poweremote.remote

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pro.freedoom.poweremote.shared.Cmd
import pro.freedoom.poweremote.shared.Link
import pro.freedoom.poweremote.shared.PlayerState

/** Какой экран открыт поверх пульта. */
private enum class Screen { REMOTE, LIBRARY }

/**
 * Корень приложения: тема, выбор устройства, пульт, библиотека, PiP.
 */
@Composable
fun RemoteApp(
    activity: MainActivity,
    prefs: Prefs,
    inPip: Boolean,
    onEnterPip: () -> Unit,
    onKeepScreenOnChanged: () -> Unit
) {
    var themeMode by remember { mutableStateOf(prefs.theme) }
    val art by LinkBus.art.collectAsStateWithLifecycle()

    PoweRemoteTheme(mode = themeMode, art = art) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxSize()
        ) {
            if (inPip) {
                PipContent(art)
            } else {
                RemoteRoot(
                    activity = activity,
                    prefs = prefs,
                    themeMode = themeMode,
                    onThemeChange = { themeMode = it; prefs.theme = it },
                    onEnterPip = onEnterPip,
                    onKeepScreenOnChanged = onKeepScreenOnChanged
                )
            }
        }
    }
}

// ------------------------------------------------------------------ PiP

/** Содержимое плавающего окна: обложка, поверх — название. Кнопки даёт система. */
@Composable
private fun PipContent(art: Bitmap?) {
    val state by LinkBus.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Filled.MusicNote, null,
                tint = Color(0xFF3A3D46),
                modifier = Modifier.size(56.dp).align(Alignment.Center)
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                state?.title?.ifEmpty { null } ?: "—",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                state?.artist.orEmpty(),
                color = Color(0xFFBFC5D0), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ----------------------------------------------------------------- корень

@Composable
private fun RemoteRoot(
    activity: MainActivity,
    prefs: Prefs,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onEnterPip: () -> Unit,
    onKeepScreenOnChanged: () -> Unit
) {
    val ctx = activity
    var mac by remember { mutableStateOf(prefs.mac) }
    var savedName by remember { mutableStateOf(prefs.deviceName) }
    var picking by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(Screen.REMOTE) }
    var showSleep by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val status by LinkBus.status.collectAsStateWithLifecycle()
    val state by LinkBus.state.collectAsStateWithLifecycle()
    val art by LinkBus.art.collectAsStateWithLifecycle()
    val error by LinkBus.error.collectAsStateWithLifecycle()

    // Автоподключение при запуске, если устройство выбрано и разрешение есть.
    LaunchedEffect(mac) {
        val m = mac
        val allowed = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.BLUETOOTH_CONNECT
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (m != null && allowed) LinkService.connect(ctx, m)
    }

    if (picking || mac == null) {
        val cancel: (() -> Unit)? = if (mac != null) {
            { picking = false }
        } else null
        DevicePicker(
            ctx = ctx,
            onCancel = cancel,
            onPick = { d ->
                val name = try { d.name ?: d.address } catch (_: SecurityException) { d.address }
                prefs.mac = d.address
                prefs.deviceName = name
                mac = d.address
                savedName = name
                picking = false
                LinkService.connect(ctx, d.address)
            }
        )
        return
    }

    // Сокет может остаться открытым, когда приёмник уже умер: кадры перестают
    // идти, а статус остаётся CONNECTED. Считаем связь протухшей по таймауту.
    var stale by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        while (true) {
            stale = status == LinkStatus.CONNECTED &&
                    LinkBus.stateAt > 0L &&
                    SystemClock.elapsedRealtime() - LinkBus.stateAt > Link.STALE_MS
            delay(1500)
        }
    }
    val live = status == LinkStatus.CONNECTED && !stale

    val haptics = rememberHaptics(prefs)

    if (screen == Screen.LIBRARY) {
        LibraryScreen(
            state = state,
            live = live,
            haptic = haptics,
            onBack = { screen = Screen.REMOTE }
        )
        return
    }

    val wide = LocalConfiguration.current.screenWidthDp >= 600

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(
            status = if (stale) LinkStatus.LOST else status,
            device = savedName,
            error = if (stale) "Плеер молчит" else error,
            state = state,
            live = live,
            onReconnect = { mac?.let { LinkService.connect(ctx, it) } },
            onPip = onEnterPip,
            onLibrary = { screen = Screen.LIBRARY },
            onSleep = { showSleep = true },
            onSettings = { showSettings = true }
        )

        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ArtBox(art, Modifier.fillMaxWidth(0.9f))
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center
                ) {
                    Controls(state, live, haptics, prefs)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ArtBox(art, Modifier.fillMaxWidth(0.78f).padding(top = 4.dp))
                Controls(state, live, haptics, prefs)
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showSleep) {
        SleepSheet(state = state, live = live, onDismiss = { showSleep = false })
    }
    if (showSettings) {
        SettingsSheet(
            prefs = prefs,
            themeMode = themeMode,
            onThemeChange = onThemeChange,
            onKeepScreenOnChanged = onKeepScreenOnChanged,
            onSwitchDevice = { showSettings = false; picking = true },
            onDisconnect = { showSettings = false; LinkService.disconnect(ctx) },
            onDismiss = { showSettings = false }
        )
    }
}

/** Лёгкий тактильный отклик на кнопках, если включён в настройках. */
@Composable
fun rememberHaptics(prefs: Prefs): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            if (prefs.haptics) {
                try { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Exception) {}
            }
        }
    }
}

// ------------------------------------------------------------------ шапка

@Composable
private fun TopBar(
    status: LinkStatus,
    device: String,
    error: String,
    state: PlayerState?,
    live: Boolean,
    onReconnect: () -> Unit,
    onPip: () -> Unit,
    onLibrary: () -> Unit,
    onSleep: () -> Unit,
    onSettings: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val (dot, label) = when (status) {
        LinkStatus.CONNECTED -> Color(0xFF6FD08C) to "На связи"
        LinkStatus.CONNECTING -> Color(0xFFE0A458) to "Подключение…"
        LinkStatus.LOST -> Color(0xFFE06C75) to (error.ifEmpty { "Связь потеряна" })
        LinkStatus.IDLE -> cs.onSurfaceVariant to "Не подключено"
    }

    // Таймер сна тикает локально между снимками.
    var sleepLeft by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(state?.sleepLeft, state?.sleepToEnd) {
        while (true) {
            sleepLeft = LinkBus.sleepLeftNow()
            delay(1000)
        }
    }

    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.ifEmpty { "Плеер" }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (live && state != null && state.battery >= 0) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (state.charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                            null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp)
                        )
                        Text("${state.battery}%", fontSize = 12.sp, color = cs.onSurfaceVariant)
                    }
                }
                Text(
                    if (live && state?.appName?.isNotEmpty() == true) "$label · ${state.appName}" else label,
                    fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (status == LinkStatus.LOST || status == LinkStatus.IDLE) {
                IconButton(onClick = onReconnect) {
                    Icon(Icons.Filled.Refresh, "Переподключиться", tint = cs.onSurfaceVariant)
                }
            }
            IconButton(onClick = onSleep, enabled = live) {
                Icon(
                    Icons.Filled.Bedtime, "Таймер сна",
                    tint = if (sleepLeft >= 0L) cs.primary else cs.onSurfaceVariant
                )
            }
            if (state?.isPoweramp == true) {
                IconButton(onClick = onLibrary, enabled = live) {
                    Icon(Icons.Filled.FolderOpen, "Папки", tint = cs.onSurfaceVariant)
                }
            }
            IconButton(onClick = onPip, enabled = live) {
                Icon(Icons.Filled.PictureInPictureAlt, "В окно", tint = cs.onSurfaceVariant)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, "Настройки", tint = cs.onSurfaceVariant)
            }
        }
        AnimatedVisibility(visible = sleepLeft >= 0L) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Bedtime, null, tint = cs.primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        sleepLeft == 0L && state?.sleepToEnd == true -> "Пауза после этого трека"
                        sleepLeft <= 0L -> "Таймер сна срабатывает…"
                        else -> "Пауза через " + fmt(sleepLeft * 1000) +
                                if (state?.sleepToEnd == true) " и доиграть трек" else ""
                    },
                    fontSize = 12.sp, color = cs.primary
                )
            }
        }
    }
}

// --------------------------------------------------------------- обложка

@Composable
private fun ArtBox(art: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(), contentDescription = "Обложка",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Filled.MusicNote, null,
                tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(84.dp)
            )
        }
    }
}

// -------------------------------------------------------------- элементы

@Composable
private fun Controls(state: PlayerState?, live: Boolean, haptic: () -> Unit, prefs: Prefs) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Text(
            state?.title?.ifEmpty { null } ?: if (live) "Ничего не играет" else "—",
            fontSize = 21.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        Text(
            listOfNotNull(state?.artist?.ifEmpty { null }, state?.album?.ifEmpty { null })
                .joinToString(" · ").ifEmpty { state?.appName.orEmpty() },
            fontSize = 14.sp, color = cs.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(14.dp))
        Progress(state, live)

        Spacer(Modifier.height(6.dp))
        Transport(state, live, haptic)

        Spacer(Modifier.height(14.dp))
        VolumeRow(state, live, haptic)

        Spacer(Modifier.height(8.dp))
        SecondaryRow(state, live, haptic)

        if (state?.canRate == true && prefs.showStars) {
            Spacer(Modifier.height(4.dp))
            Stars(state.rating.coerceAtLeast(0), live, haptic)
        }
    }
}

@Composable
private fun Progress(state: PlayerState?, live: Boolean) {
    val cs = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableLongStateOf(0L) }

    // По Bluetooth состояние прилетает раз в 2 с, полоса должна идти ровно.
    LaunchedEffect(state, live) {
        while (true) {
            shown = LinkBus.positionNow()
            delay(300)
        }
    }

    val dur = (state?.duration ?: 0L).coerceAtLeast(0L)
    val pos = if (dragging) dragValue.toLong() else shown.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)

    Slider(
        value = if (dur > 0) pos.toFloat() else 0f,
        onValueChange = { dragging = true; dragValue = it },
        onValueChangeFinished = {
            dragging = false
            LinkBus.pendingSeekValue = dragValue.toLong()
            LinkBus.pendingSeekUntil = System.currentTimeMillis() + 1500
            LinkService.send(Cmd.SEEK, dragValue.toLong())
        },
        valueRange = 0f..(if (dur > 0) dur.toFloat() else 1f),
        enabled = live && dur > 0 && state?.canSeek != false,
        modifier = Modifier.fillMaxWidth()
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(fmt(pos), fontSize = 12.sp, color = cs.onSurfaceVariant)
        Text(
            if (dur > 0) "−" + fmt((dur - pos).coerceAtLeast(0L)) else "--:--",
            fontSize = 12.sp, color = cs.onSurfaceVariant
        )
    }
}

@Composable
private fun Transport(state: PlayerState?, live: Boolean, haptic: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        IconButton(onClick = { haptic(); LinkService.send(Cmd.SEEK_REL, -10_000L) }, enabled = live) {
            Icon(Icons.Filled.Replay10, "−10 секунд", tint = if (live) cs.onBackground else cs.outline)
        }
        RoundBtn(Icons.Filled.SkipPrevious, "Предыдущий", 62.dp, live) {
            haptic(); LinkService.send(Cmd.PREV)
        }
        RoundBtn(
            if (state?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            "Пуск/пауза", 84.dp, live, filled = true
        ) { haptic(); LinkService.send(Cmd.TOGGLE) }
        RoundBtn(Icons.Filled.SkipNext, "Следующий", 62.dp, live) {
            haptic(); LinkService.send(Cmd.NEXT)
        }
        IconButton(onClick = { haptic(); LinkService.send(Cmd.SEEK_REL, 10_000L) }, enabled = live) {
            Icon(Icons.Filled.Forward10, "+10 секунд", tint = if (live) cs.onBackground else cs.outline)
        }
    }
}

@Composable
private fun RoundBtn(
    icon: ImageVector, desc: String, size: Dp, enabled: Boolean,
    filled: Boolean = false, onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) cs.primary else cs.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, desc,
            tint = if (filled) cs.onPrimary else if (enabled) cs.onBackground else cs.outline,
            modifier = Modifier.size(size * 0.46f)
        )
    }
}

@Composable
private fun VolumeRow(state: PlayerState?, live: Boolean, haptic: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val max = (state?.volumeMax ?: 15).coerceAtLeast(1)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else (state?.volume ?: 0).toFloat()

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { haptic(); LinkService.send(Cmd.VOLUME_DELTA, -1) }, enabled = live) {
            Icon(Icons.Filled.VolumeDown, "Тише", tint = if (live) cs.onBackground else cs.outline)
        }
        Slider(
            value = value.coerceIn(0f, max.toFloat()),
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                dragging = false
                LinkBus.pendingVolumeValue = dragValue.toInt()
                LinkBus.pendingVolumeUntil = System.currentTimeMillis() + 1200
                LinkService.send(Cmd.VOLUME, dragValue.toLong())
            },
            valueRange = 0f..max.toFloat(),
            steps = (max - 1).coerceAtLeast(0),
            enabled = live,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { haptic(); LinkService.send(Cmd.VOLUME_DELTA, 1) }, enabled = live) {
            Icon(Icons.Filled.VolumeUp, "Громче", tint = if (live) cs.onBackground else cs.outline)
        }
    }
}

/** Режимы, лайк/дизлайк и (для Poweramp) переход между категориями. */
@Composable
private fun SecondaryRow(state: PlayerState?, live: Boolean, haptic: () -> Unit) {
    val canToggle = live && state?.canMode == true
    val canRate = live && state?.canRate == true
    val poweramp = live && state?.isPoweramp == true

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Toggle(Icons.Filled.Shuffle, "Перемешивание", state?.shuffle == 1, canToggle) {
            haptic(); LinkService.send(Cmd.SHUFFLE)
        }
        Toggle(
            if (state?.repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            "Повтор", (state?.repeat ?: -1) > 0, canToggle
        ) { haptic(); LinkService.send(Cmd.REPEAT) }

        Spacer(Modifier.width(6.dp))

        Toggle(Icons.Filled.ThumbDown, "Не нравится", state?.rating == 1, canRate) {
            haptic(); LinkService.send(Cmd.UNLIKE)
        }
        Toggle(Icons.Filled.ThumbUp, "Нравится", state?.rating == 5, canRate) {
            haptic(); LinkService.send(Cmd.LIKE)
        }

        if (state?.isPoweramp == true) {
            Spacer(Modifier.width(6.dp))
            Toggle(Icons.Filled.FirstPage, "Предыдущая категория", false, poweramp) {
                haptic(); LinkService.send(Cmd.PREV_CAT)
            }
            Toggle(Icons.Filled.LastPage, "Следующая категория", false, poweramp) {
                haptic(); LinkService.send(Cmd.NEXT_CAT)
            }
        }
    }
}

@Composable
private fun Toggle(icon: ImageVector, desc: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) cs.primary.copy(alpha = 0.22f) else cs.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, desc,
            tint = if (!enabled) cs.outline else if (on) cs.primary else cs.onBackground,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun Stars(rating: Int, live: Boolean, haptic: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) {
            IconButton(
                onClick = { haptic(); LinkService.send(Cmd.RATING, if (rating == i) 0L else i.toLong()) },
                enabled = live,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    if (i <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                    "Оценка $i",
                    tint = if (i <= rating) Color(0xFFE0A458) else cs.outline
                )
            }
        }
    }
}

fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
