package pro.freedoom.poweremote.remote

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pro.freedoom.poweremote.shared.Cmd
import pro.freedoom.poweremote.shared.Link
import pro.freedoom.poweremote.shared.PlayerState

private const val PREFS = "remote"
private const val KEY_MAC = "mac"
private const val KEY_NAME = "name"

private val BG = Color(0xFF0B0B0F)
private val CARD = Color(0xFF16171D)
private val ACCENT = Color(0xFFB794F6)
private val MUTED = Color(0xFF9AA0AC)

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = ACCENT)) {
                Surface(
                    color = BG,
                    contentColor = Color.White,
                    modifier = Modifier.fillMaxSize()
                ) {
                    RemoteRoot(this)
                }
            }
        }
    }

    /**
     * Качелька громкости телефона управляет громкостью плеера, пока есть связь.
     * Это самая частая операция вслепую — в кармане, не доставая телефон.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val connected = LinkBus.status.value == LinkStatus.CONNECTED
        if (connected && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    LinkService.send(Cmd.VOLUME_DELTA, 1); return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    LinkService.send(Cmd.VOLUME_DELTA, -1); return true
                }
            }
        }
        if (connected && event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) return true
        return super.dispatchKeyEvent(event)
    }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())
    }
}

@Composable
private fun RemoteRoot(ctx: Context) {
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var mac by remember { mutableStateOf(prefs.getString(KEY_MAC, null)) }
    var savedName by remember { mutableStateOf(prefs.getString(KEY_NAME, "") ?: "") }
    var picking by remember { mutableStateOf(false) }

    val status by LinkBus.status.collectAsStateWithLifecycle()
    val state by LinkBus.state.collectAsStateWithLifecycle()
    val art by LinkBus.art.collectAsStateWithLifecycle()
    val error by LinkBus.error.collectAsStateWithLifecycle()

    // Автоподключение при запуске, если устройство выбрано и разрешение есть.
    // Без BLUETOOTH_CONNECT запуск foreground-сервиса на Android 14+ падает.
    LaunchedEffect(mac) {
        val m = mac
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
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
                prefs.edit().putString(KEY_MAC, d.address).putString(KEY_NAME, name).apply()
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

    val wide = LocalConfiguration.current.screenWidthDp >= 600

    Column(Modifier.fillMaxSize()) {
        TopBar(
            status = if (stale) LinkStatus.LOST else status,
            device = savedName,
            error = if (stale) "Плеер молчит" else error,
            onSwitch = { picking = true },
            onReconnect = { mac?.let { LinkService.connect(ctx, it) } }
        )

        if (wide) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ArtBox(art, Modifier.fillMaxWidth(0.9f))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center
                ) {
                    Controls(state, live)
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
                ArtBox(art, Modifier.fillMaxWidth(0.82f).padding(top = 8.dp))
                Controls(state, live)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// --------------------------------------------------------------------- шапка

@Composable
private fun TopBar(
    status: LinkStatus,
    device: String,
    error: String,
    onSwitch: () -> Unit,
    onReconnect: () -> Unit
) {
    val (dot, label) = when (status) {
        LinkStatus.CONNECTED -> Color(0xFF6FD08C) to "На связи"
        LinkStatus.CONNECTING -> Color(0xFFE0A458) to "Подключение…"
        LinkStatus.LOST -> Color(0xFFE06C75) to (error.ifEmpty { "Связь потеряна" })
        LinkStatus.IDLE -> MUTED to "Не подключено"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(device.ifEmpty { "Плеер" }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(label, fontSize = 12.sp, color = MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status == LinkStatus.LOST || status == LinkStatus.IDLE) {
            IconButton(onClick = onReconnect) {
                Icon(Icons.Filled.Refresh, "Переподключиться", tint = MUTED)
            }
        }
        IconButton(onClick = onSwitch) {
            Icon(Icons.Filled.Bluetooth, "Сменить устройство", tint = MUTED)
        }
    }
}

// -------------------------------------------------------------------- обложка

@Composable
private fun ArtBox(art: android.graphics.Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(CARD),
        contentAlignment = Alignment.Center
    ) {
        if (art != null) {
            Image(
                bitmap = art.asImageBitmap(),
                contentDescription = "Обложка",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                Icons.Filled.MusicNote, null,
                tint = Color(0xFF2E3038),
                modifier = Modifier.size(84.dp)
            )
        }
    }
}

// ------------------------------------------------------------------ элементы

@Composable
private fun Controls(state: PlayerState?, live: Boolean) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(18.dp))
        Text(
            state?.title?.ifEmpty { null } ?: if (live) "Ничего не играет" else "—",
            fontSize = 21.sp, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            listOfNotNull(
                state?.artist?.ifEmpty { null },
                state?.album?.ifEmpty { null }
            ).joinToString(" · ").ifEmpty { state?.appName.orEmpty() },
            fontSize = 14.sp, color = MUTED,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(18.dp))
        Progress(state, live)

        Spacer(Modifier.height(10.dp))
        Transport(state, live)

        Spacer(Modifier.height(18.dp))
        VolumeRow(state, live)

        Spacer(Modifier.height(10.dp))
        ModesRow(state, live)

        if (state?.canRate == true) {
            Spacer(Modifier.height(8.dp))
            Stars(state.rating.coerceAtLeast(0), live)
        }
    }
}

@Composable
private fun Progress(state: PlayerState?, live: Boolean) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableLongStateOf(0L) }

    // Позиция достраивается локально: по Bluetooth состояние прилетает раз в 2 с,
    // а полоса должна идти ровно.
    LaunchedEffect(state, live) {
        while (true) {
            val s = state
            shown = if (s == null || !s.playing) s?.position ?: 0L
            else s.position + ((SystemClock.elapsedRealtime() - LinkBus.stateAt) * s.speed).toLong()
            delay(300)
        }
    }

    val dur = (state?.duration ?: 0L).coerceAtLeast(0L)
    val pos = if (dragging) dragValue.toLong() else shown.coerceIn(0L, if (dur > 0) dur else Long.MAX_VALUE)

    Slider(
        value = if (dur > 0) pos.toFloat() else 0f,
        onValueChange = {
            dragging = true
            dragValue = it
        },
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
        Text(fmt(pos), fontSize = 12.sp, color = MUTED)
        Text(if (dur > 0) fmt(dur) else "--:--", fontSize = 12.sp, color = MUTED)
    }
}

@Composable
private fun Transport(state: PlayerState?, live: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        RoundBtn(Icons.Filled.SkipPrevious, "Предыдущий", 66.dp, live) {
            LinkService.send(Cmd.PREV)
        }
        RoundBtn(
            if (state?.playing == true) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            "Пуск/пауза", 88.dp, live, filled = true
        ) { LinkService.send(Cmd.TOGGLE) }
        RoundBtn(Icons.Filled.SkipNext, "Следующий", 66.dp, live) {
            LinkService.send(Cmd.NEXT)
        }
    }
}

@Composable
private fun RoundBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) ACCENT else CARD)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, desc,
            tint = if (filled) Color(0xFF14121A) else if (enabled) Color.White else Color(0xFF3A3D46),
            modifier = Modifier.size(size * 0.46f)
        )
    }
}

@Composable
private fun VolumeRow(state: PlayerState?, live: Boolean) {
    val max = (state?.volumeMax ?: 15).coerceAtLeast(1)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val value = if (dragging) dragValue else (state?.volume ?: 0).toFloat()

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { LinkService.send(Cmd.VOLUME_DELTA, -1) }, enabled = live) {
            Icon(Icons.Filled.VolumeDown, "Тише", tint = if (live) Color.White else MUTED)
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
        IconButton(onClick = { LinkService.send(Cmd.VOLUME_DELTA, 1) }, enabled = live) {
            Icon(Icons.Filled.VolumeUp, "Громче", tint = if (live) Color.White else MUTED)
        }
    }
}

@Composable
private fun ModesRow(state: PlayerState?, live: Boolean) {
    // Плеер может уметь переключать режимы, но не сообщать текущий (-1).
    // Тогда кнопка работает, но не подсвечивается — врать о состоянии хуже.
    val canToggle = live && state?.canMode == true
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Toggle(
            icon = Icons.Filled.Shuffle,
            desc = "Перемешивание",
            on = state?.shuffle == 1,
            enabled = canToggle
        ) { LinkService.send(Cmd.SHUFFLE) }

        Toggle(
            icon = if (state?.repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
            desc = "Повтор",
            on = (state?.repeat ?: -1) > 0,
            enabled = canToggle
        ) { LinkService.send(Cmd.REPEAT) }
    }
}

@Composable
private fun Toggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    on: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) ACCENT.copy(alpha = 0.22f) else CARD)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon, desc,
            tint = if (!enabled) Color(0xFF3A3D46) else if (on) ACCENT else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun Stars(rating: Int, live: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..5) {
            IconButton(
                onClick = { LinkService.send(Cmd.RATING, if (rating == i) 0L else i.toLong()) },
                enabled = live,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    if (i <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                    "Оценка $i",
                    tint = if (i <= rating) Color(0xFFE0A458) else Color(0xFF3A3D46)
                )
            }
        }
    }
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ------------------------------------------------------------ выбор плеера

@Composable
@android.annotation.SuppressLint("MissingPermission")
private fun DevicePicker(
    ctx: Context,
    onCancel: (() -> Unit)?,
    onPick: (BluetoothDevice) -> Unit
) {
    val adapter: BluetoothAdapter? = remember {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    // Пользователь уходит парить плеер в системные настройки и возвращается —
    // список должен перечитаться, иначе он останется пустым.
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val bonded = remember(refresh) {
        try {
            adapter?.bondedDevices?.toList().orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
    }
    val btEnabled = remember(refresh) { adapter?.isEnabled == true }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Выберите плеер", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Список спаренных устройств. Если плеера тут нет — спарьте его в системных " +
                    "настройках Bluetooth, затем вернитесь.",
            fontSize = 13.sp, color = MUTED
        )

        if (!btEnabled) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF34221F))) {
                Column(Modifier.padding(16.dp)) {
                    Text("Bluetooth выключен", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = {
                        ctx.startActivity(
                            Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }, modifier = Modifier.padding(top = 8.dp)) { Text("Включить") }
                }
            }
        }

        bonded.forEach { d ->
            val name = try { d.name ?: d.address } catch (_: SecurityException) { d.address }
            Card(
                colors = CardDefaults.cardColors(containerColor = CARD),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(d) }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(name, fontWeight = FontWeight.Medium)
                    Text(d.address, fontSize = 12.sp, color = MUTED)
                }
            }
        }

        if (bonded.isEmpty()) {
            Text("Спаренных устройств не найдено", color = MUTED, fontSize = 13.sp)
        }

        OutlinedButton(onClick = {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Открыть настройки Bluetooth") }

        if (onCancel != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCancel) { Text("Отмена") }
                TextButton(onClick = { LinkService.disconnect(ctx) }) { Text("Отключиться") }
            }
        }
    }
}
