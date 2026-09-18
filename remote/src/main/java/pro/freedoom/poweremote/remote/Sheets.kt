package pro.freedoom.poweremote.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pro.freedoom.poweremote.shared.Cmd
import pro.freedoom.poweremote.shared.PlayerState

// ------------------------------------------------------------ таймер сна

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSheet(state: PlayerState?, live: Boolean, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var toEnd by remember { mutableStateOf(state?.sleepToEnd ?: true) }
    val active = (state?.sleepLeft ?: -1L) >= 0L

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Таймер сна", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                "Плеер сам поставит паузу — телефон можно выключить.",
                fontSize = 13.sp, color = cs.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 45, 60).forEach { min ->
                    FilledTonalButton(
                        onClick = {
                            LinkService.send(Cmd.SLEEP, min * 60L, if (toEnd) 1L else 0L)
                            onDismiss()
                        },
                        enabled = live,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)
                    ) { Text("$min мин") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(90, 120).forEach { min ->
                    FilledTonalButton(
                        onClick = {
                            LinkService.send(Cmd.SLEEP, min * 60L, if (toEnd) 1L else 0L)
                            onDismiss()
                        },
                        enabled = live,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)
                    ) { Text("$min мин") }
                }
                FilledTonalButton(
                    onClick = {
                        // «После этого трека»: минимальное время + доиграть до конца.
                        LinkService.send(Cmd.SLEEP, 1L, 1L)
                        onDismiss()
                    },
                    enabled = live,
                    modifier = Modifier.weight(1.4f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp)
                ) { Text("После трека") }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clickable { toEnd = !toEnd },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Доиграть трек до конца", fontWeight = FontWeight.Medium)
                    Text("Пауза не оборвёт песню на середине", fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
                Switch(checked = toEnd, onCheckedChange = { toEnd = it })
            }

            if (active) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { LinkService.send(Cmd.SLEEP, 0L, 0L); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Отменить таймер") }
            }
        }
    }
}

// ------------------------------------------------------------- настройки

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    prefs: Prefs,
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    onKeepScreenOnChanged: () -> Unit,
    onSwitchDevice: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    var keep by remember { mutableStateOf(prefs.keepScreenOn) }
    var pip by remember { mutableStateOf(prefs.autoPip) }
    var haptics by remember { mutableStateOf(prefs.haptics) }
    var stars by remember { mutableStateOf(prefs.showStars) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Оформление", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))

            ThemeRow(
                "Material You", "Цвета из обоев телефона, светлая и тёмная вслед за системой",
                themeMode == ThemeMode.MATERIAL_YOU,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) { onThemeChange(ThemeMode.MATERIAL_YOU) }
            ThemeRow(
                "Цвет обложки", "Фон и акцент подстраиваются под обложку трека",
                themeMode == ThemeMode.COVER
            ) { onThemeChange(ThemeMode.COVER) }
            ThemeRow(
                "Классическая тёмная", "Нейтральный тёмный фон, сиреневый акцент",
                themeMode == ThemeMode.DARK
            ) { onThemeChange(ThemeMode.DARK) }

            Spacer(Modifier.height(18.dp))
            Text("Поведение", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))

            SwitchRow("Не гасить экран", "Пока пульт открыт", keep) {
                keep = it; prefs.keepScreenOn = it; onKeepScreenOnChanged()
            }
            SwitchRow("Окно поверх других приложений", "Сворачивать в маленькое окно при уходе с экрана", pip) {
                pip = it; prefs.autoPip = it
            }
            SwitchRow("Виброотклик", "Лёгкая вибрация на кнопках", haptics) {
                haptics = it; prefs.haptics = it
            }
            SwitchRow("Показывать звёзды рейтинга", "Кроме лайка и дизлайка", stars) {
                stars = it; prefs.showStars = it
            }

            Spacer(Modifier.height(18.dp))
            Text("Плеер", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSwitchDevice, modifier = Modifier.weight(1f)) { Text("Сменить плеер") }
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) { Text("Отключиться") }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Кнопки наушников, часов и плеер на экране блокировки работают, пока " +
                        "пульт на связи — даже если приложение закрыто.",
                fontSize = 12.sp, color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeRow(title: String, hint: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, fontWeight = FontWeight.Medium, color = if (enabled) cs.onSurface else cs.outline)
            Text(
                if (enabled) hint else "Нужен Android 12 или новее",
                fontSize = 12.sp, color = cs.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, hint: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(hint, fontSize = 12.sp, color = cs.onSurfaceVariant)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

// ------------------------------------------------------------ выбор плеера

@SuppressLint("MissingPermission")
@Composable
fun DevicePicker(
    ctx: Context,
    onCancel: (() -> Unit)?,
    onPick: (BluetoothDevice) -> Unit
) {
    val cs = MaterialTheme.colorScheme
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
        try { adapter?.bondedDevices?.toList().orEmpty() } catch (_: SecurityException) { emptyList() }
    }
    val btEnabled = remember(refresh) { adapter?.isEnabled == true }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Выберите плеер", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Список спаренных устройств. Если плеера тут нет — спарьте его в системных " +
                    "настройках Bluetooth, затем вернитесь.",
            fontSize = 13.sp, color = cs.onSurfaceVariant
        )

        if (!btEnabled) {
            Card(colors = CardDefaults.cardColors(containerColor = cs.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Bluetooth выключен", fontWeight = FontWeight.SemiBold, color = cs.onErrorContainer)
                    OutlinedButton(onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }, modifier = Modifier.padding(top = 8.dp)) { Text("Включить") }
                }
            }
        }

        bonded.forEach { d ->
            val name = try { d.name ?: d.address } catch (_: SecurityException) { d.address }
            Card(
                colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant),
                modifier = Modifier.fillMaxWidth().clickable { onPick(d) }
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(name, fontWeight = FontWeight.Medium)
                    Text(d.address, fontSize = 12.sp, color = cs.onSurfaceVariant)
                }
            }
        }

        if (bonded.isEmpty()) {
            Text("Спаренных устройств не найдено", color = cs.onSurfaceVariant, fontSize = 13.sp)
        }

        OutlinedButton(onClick = {
            ctx.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Открыть настройки Bluetooth") }

        if (onCancel != null) {
            TextButton(onClick = onCancel) { Text("Отмена") }
        }
    }
}
