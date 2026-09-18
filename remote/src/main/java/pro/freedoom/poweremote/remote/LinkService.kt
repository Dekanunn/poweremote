package pro.freedoom.poweremote.remote

import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import pro.freedoom.poweremote.shared.Cmd
import pro.freedoom.poweremote.shared.FrameReader
import pro.freedoom.poweremote.shared.FrameWriter
import pro.freedoom.poweremote.shared.LibListing
import pro.freedoom.poweremote.shared.Link
import pro.freedoom.poweremote.shared.PlayerState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Держит Bluetooth-соединение с плеером: подключается, переподключается
 * при обрыве, разбирает входящие кадры и зеркалит состояние в медиа-сессию
 * телефона, уведомление и виджеты.
 */
class LinkService : Service() {

    companion object {
        private const val TAG = "LinkService"

        const val EXTRA_MAC = "mac"
        const val ACTION_CONNECT = "connect"
        const val ACTION_DISCONNECT = "disconnect"

        @Volatile private var instance: LinkService? = null

        /** Команда, которую надо отправить сразу после подключения (кнопка виджета без связи). */
        @Volatile private var queued: JSONObject? = null

        fun connect(ctx: Context, mac: String) {
            val i = Intent(ctx, LinkService::class.java)
                .setAction(ACTION_CONNECT)
                .putExtra(EXTRA_MAC, mac)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (e: Exception) {
                Log.w(TAG, "Не удалось запустить сервис: ${e.message}")
            }
        }

        fun disconnect(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, LinkService::class.java).setAction(ACTION_DISCONNECT))
            } catch (_: Exception) {
            }
        }

        /** Отправка команды из UI. Без соединения просто молча игнорируется. */
        fun send(cmd: String, value: Long? = null, extra: Long? = null) {
            instance?.post(Cmd.of(cmd, value, extra))
        }

        fun queueAfterConnect(o: JSONObject) {
            queued = o
        }

        /** Запросить содержимое папки библиотеки. */
        fun browse(folderId: Long) {
            LinkBus.setLibLoading(true)
            send(Cmd.BROWSE, folderId)
        }
    }

    /** Флаг живёт вместе с потоком: остановленный поток нельзя «оживить». */
    private var stopping = AtomicBoolean(true)
    private var worker: Thread? = null

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var writer: FrameWriter? = null
    private var mac: String? = null

    private val main = Handler(Looper.getMainLooper())
    private lateinit var phone: PhoneSession
    private var lastNotifText = ""

    /** Одна очередь на отправку: команды уходят в том порядке, в каком нажаты. */
    private val sender = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        phone = PhoneSession(this) { cmd, value -> post(Cmd.of(cmd, value)) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                val target = intent.getStringExtra(EXTRA_MAC) ?: return START_NOT_STICKY
                instance = this
                try {
                    startForeground(PhoneSession.NOTIF_ID, phone.notification(null, null, "Подключение…"))
                } catch (e: Exception) {
                    // Android 14+ не даёт поднять сервис типа connectedDevice
                    // без разрешения BLUETOOTH_CONNECT.
                    LinkBus.setError("Нет разрешения на Bluetooth")
                    LinkBus.setStatus(LinkStatus.LOST)
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (target != mac || worker?.isAlive != true) {
                    stopWorker()
                    mac = target
                    val stop = AtomicBoolean(false)
                    stopping = stop
                    worker = Thread({ loop(target, stop) }, "bt-link").apply {
                        isDaemon = true
                        start()
                    }
                } else {
                    // Уже подключаемся: не плодим второй поток, а сбиваем паузу
                    // между попытками, чтобы кнопка «обновить» срабатывала сразу.
                    worker?.interrupt()
                }
            }
            null -> {
                // Перезапуск системой после убийства процесса — восстанавливаемся.
                val m = mac ?: Prefs(this).mac
                if (m != null) return onStartCommand(
                    Intent(this, LinkService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_MAC, m),
                    flags, startId
                )
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        shutdown()
        try { sender.shutdownNow() } catch (_: Exception) {}
        phone.release()
        super.onDestroy()
    }

    private fun shutdown() {
        stopWorker()
        instance = null
        LinkBus.setStatus(LinkStatus.IDLE)
        LinkBus.setState(null)
        LinkBus.setArt(null)
        LinkBus.setListing(null)
        phone.setActive(false)
        NowPlayingWidget.push(this, null, null, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopWorker() {
        stopping.set(true)
        closeSocket()
        worker?.interrupt()
        worker = null
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
    }

    fun post(o: JSONObject) {
        val w = writer ?: return
        if (sender.isShutdown) return
        try {
            sender.execute {
                try {
                    w.writeText(Link.TYPE_JSON, o.toString())
                } catch (e: Exception) {
                    Log.i(TAG, "Команда не ушла: ${e.message}")
                    closeSocket()
                }
            }
        } catch (_: Exception) {
            // очередь уже закрыта — сервис останавливается
        }
    }

    // ------------------------------------------------------------------ цикл

    @android.annotation.SuppressLint("MissingPermission")
    private fun loop(target: String, stop: AtomicBoolean) {
        var backoff = 1500L
        while (!stop.get()) {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                LinkBus.setError("Bluetooth выключен")
                LinkBus.setStatus(LinkStatus.LOST)
                mirror("Bluetooth выключен")
                sleep(2500)
                continue
            }

            val device: BluetoothDevice = try {
                adapter.getRemoteDevice(target)
            } catch (e: Exception) {
                LinkBus.setError("Неверный адрес устройства")
                return
            }

            LinkBus.setStatus(LinkStatus.CONNECTING)
            mirror("Подключение к ${safeName(device)}…")
            try {
                try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
                val s = device.createInsecureRfcommSocketToServiceRecord(Link.SERVICE_UUID)
                s.connect()
                socket = s
                writer = FrameWriter(s.outputStream)
                LinkBus.setDevice(safeName(device))
                LinkBus.setStatus(LinkStatus.CONNECTED)
                LinkBus.setError("")
                mirror("Подключено: ${safeName(device)}")
                backoff = 1500L
                post(Cmd.of(Cmd.HELLO))
                queued?.let { post(it); queued = null }
                pump(FrameReader(s.inputStream), stop)
            } catch (e: SecurityException) {
                LinkBus.setError("Нет разрешения на Bluetooth")
                LinkBus.setStatus(LinkStatus.LOST)
                return
            } catch (e: Exception) {
                LinkBus.setError(shortReason(e))
                LinkBus.setStatus(LinkStatus.LOST)
            } finally {
                closeSocket()
            }

            if (stop.get()) break
            mirror("Нет связи, повтор…")
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(15000L)
        }
    }

    private fun pump(reader: FrameReader, stop: AtomicBoolean) {
        while (!stop.get()) {
            val frame = reader.read()
            when (frame.type) {
                Link.TYPE_JSON -> {
                    val o = JSONObject(String(frame.payload, Charsets.UTF_8))
                    when (o.optString("t")) {
                        "state" -> onState(PlayerState.fromJson(o))
                        "list" -> LinkBus.setListing(LibListing.fromJson(o))
                    }
                }
                Link.TYPE_ART -> {
                    val bmp = if (frame.payload.isEmpty()) null else try {
                        BitmapFactory.decodeByteArray(frame.payload, 0, frame.payload.size)
                    } catch (_: Throwable) {
                        null
                    }
                    LinkBus.setArt(bmp)
                    mirrorState()
                }
            }
        }
    }

    private fun onState(s: PlayerState) {
        val now = System.currentTimeMillis()
        // Пока пользователь тянет ползунок, свежие значения плеера
        // не перетирают то, что он сейчас выставляет.
        val merged = s.copy(
            position = if (now < LinkBus.pendingSeekUntil) LinkBus.pendingSeekValue else s.position,
            volume = if (now < LinkBus.pendingVolumeUntil) LinkBus.pendingVolumeValue else s.volume
        )
        LinkBus.setState(merged)
        LinkBus.setStatus(LinkStatus.CONNECTED)
        mirrorState()
    }

    // --------------------------------------------- сессия, уведомление, виджет

    /** Зеркалим состояние в медиа-сессию, уведомление и виджеты (в главном потоке). */
    private fun mirrorState() {
        main.post {
            val s = LinkBus.state.value
            val art = LinkBus.art.value
            val connected = LinkBus.status.value == LinkStatus.CONNECTED
            phone.update(s, art, connected)
            val text = if (connected) "Плеер: ${LinkBus.deviceName.value}" else lastNotifText
            notify(s, art, text)
            NowPlayingWidget.push(this, s, art, connected)
        }
    }

    /** Только текст статуса, когда состояния плеера ещё нет. */
    private fun mirror(text: String) {
        lastNotifText = text
        main.post {
            val connected = LinkBus.status.value == LinkStatus.CONNECTED
            val s = if (connected) LinkBus.state.value else null
            val art: Bitmap? = if (connected) LinkBus.art.value else null
            phone.update(s, art, connected)
            notify(s, art, text)
            NowPlayingWidget.push(this, s, art, connected)
        }
    }

    private fun notify(s: PlayerState?, art: Bitmap?, text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(PhoneSession.NOTIF_ID, phone.notification(s, art, text))
        } catch (_: Exception) {
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun safeName(d: BluetoothDevice): String = try {
        d.name ?: d.address
    } catch (_: SecurityException) {
        d.address
    }

    private fun shortReason(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("closed", true) -> "Соединение закрыто"
            m.contains("refused", true) || m.contains("read failed", true) ->
                "Плеер не отвечает — запущен ли приёмник?"
            m.contains("timeout", true) -> "Таймаут подключения"
            else -> "Ошибка связи"
        }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}
}
