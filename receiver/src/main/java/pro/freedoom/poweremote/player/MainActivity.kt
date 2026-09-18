package pro.freedoom.poweremote.player

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Экран настройки приёмника: три шага до рабочего состояния
 * (доступ к сессиям → Bluetooth → запуск сервиса) и текущий статус.
 */
class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh() }

    private var tick by mutableIntStateOf(0)

    private fun refresh() {
        tick++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReceiverService.onStatus = { refresh() }
        askPermissions()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7AA2F7))) {
                Surface(color = Color(0xFF0B0B0F)) {
                    // tick передаём внутрь, чтобы Compose перерисовывал экран
                    // по сигналу сервиса и после возврата из системных настроек.
                    ReceiverScreen(
                        ctx = this,
                        tick = tick,
                        onRefresh = { refresh() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        if (ReceiverService.onStatus != null) ReceiverService.onStatus = null
        super.onDestroy()
    }

    private fun askPermissions() {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            need += Manifest.permission.BLUETOOTH_CONNECT
            need += Manifest.permission.BLUETOOTH_ADVERTISE
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
private fun ReceiverScreen(ctx: Context, @Suppress("UNUSED_PARAMETER") tick: Int, onRefresh: () -> Unit) {
    val prefs = remember { ctx.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE) }
    val hub = remember { MediaHub(ctx) {} }
    var autostart by remember { mutableStateOf(prefs.getBoolean(Prefs.AUTOSTART, true)) }

    val adapter: BluetoothAdapter? = remember {
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
    val access = hub.hasAccess()
    val btOn = adapter?.isEnabled == true
    val ready = access && btOn && ReceiverService.running

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Приёмник M33", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Это приложение живёт на плеере. Телефон подключается к нему по Bluetooth " +
                    "и управляет тем плеером, который сейчас играет.",
            fontSize = 14.sp, color = Color(0xFF9AA0AC)
        )

        StatusCard(ready, ReceiverService.clientName)

        Step(
            n = 1,
            title = "Доступ к медиа-сессиям",
            done = access,
            hint = "Без него приложение не видит, что играет. Найдите «Приёмник M33» в списке.",
            action = "Открыть настройки"
        ) {
            ctx.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        Step(
            n = 2,
            title = "Bluetooth включён и плеер виден",
            done = btOn,
            hint = "Телефон нужно один раз спарить с плеером. Нажмите кнопку и подтвердите " +
                    "видимость, затем найдите плеер в Bluetooth телефона.",
            action = "Сделать видимым на 5 минут"
        ) {
            try {
                ctx.startActivity(
                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }

        Step(
            n = 3,
            title = "Сервис приёма запущен",
            done = ReceiverService.running,
            hint = "Сервис висит в уведомлениях и ждёт пульт. Экран плеера можно гасить.",
            action = if (ReceiverService.running) "Остановить" else "Запустить"
        ) {
            if (ReceiverService.running) ReceiverService.stop(ctx) else ReceiverService.start(ctx)
            onRefresh()
        }

        val powerampHere = remember { Poweramp.isInstalled(ctx) }
        val canBrowse = remember(tick) { if (powerampHere) Poweramp.canBrowse(ctx) else false }
        if (powerampHere) {
            Step(
                n = 4,
                title = "Библиотека Poweramp (необязательно)",
                done = canBrowse,
                hint = "Нужно для просмотра папок с телефона. Poweramp покажет запрос на этом " +
                        "экране — подтвердите его.",
                action = "Запросить доступ"
            ) {
                Poweramp.askDataPermission(ctx, fromForeground = true)
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF16171D))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Запускать после перезагрузки", fontWeight = FontWeight.Medium)
                    Text(
                        "Плеер включится — приёмник поднимется сам",
                        fontSize = 12.sp, color = Color(0xFF9AA0AC)
                    )
                }
                Switch(checked = autostart, onCheckedChange = {
                    autostart = it
                    prefs.edit().putBoolean(Prefs.AUTOSTART, it).apply()
                })
            }
        }

        Text(
            "Совет: в настройках плеера снимите ограничение батареи для этого приложения, " +
                    "иначе система может убить сервис при спящем экране.",
            fontSize = 12.sp, color = Color(0xFF6E7480)
        )
    }
}

@Composable
private fun StatusCard(ready: Boolean, client: String?) {
    val color = if (ready) Color(0xFF1E3A2A) else Color(0xFF34221F)
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                if (ready) "Готов к работе" else "Настройка не закончена",
                fontWeight = FontWeight.Bold, fontSize = 18.sp
            )
            Text(
                when {
                    !ready -> "Пройдите шаги ниже"
                    client != null -> "Пульт подключён: $client"
                    else -> "Жду подключения пульта"
                },
                fontSize = 13.sp, color = Color(0xFFBFC5D0)
            )
        }
    }
}

@Composable
private fun Step(
    n: Int,
    title: String,
    done: Boolean,
    hint: String,
    action: String,
    onClick: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF16171D))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (done) "✓" else "$n",
                    color = if (done) Color(0xFF6FD08C) else Color(0xFFE0A458),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(end = 10.dp)
                )
                Text(title, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            }
            Text(hint, fontSize = 12.sp, color = Color(0xFF9AA0AC))
            OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 4.dp)) {
                Text(action)
            }
        }
    }
}
