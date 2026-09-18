package pro.freedoom.poweremote.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import pro.freedoom.poweremote.shared.Cmd
import pro.freedoom.poweremote.shared.FrameReader
import pro.freedoom.poweremote.shared.FrameWriter
import pro.freedoom.poweremote.shared.Link
import pro.freedoom.poweremote.shared.PlayerState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Сервис-приёмник на плеере.
 *
 * Держит Bluetooth-сокет в режиме сервера, ждёт подключения пульта,
 * исполняет присланные команды и отдаёт состояние плеера обратно.
 */
class ReceiverService : Service() {

    companion object {
        private const val TAG = "ReceiverService"
        private const val CHANNEL = "link"
        private const val NOTIF_ID = 42

        const val ACTION_START = "pro.freedoom.poweremote.player.START"
        const val ACTION_STOP = "pro.freedoom.poweremote.player.STOP"

        @Volatile var running = false
            private set

        @Volatile var clientName: String? = null
            private set

        /** Кому сообщать об изменении статуса — экрану настроек. */
        @Volatile var onStatus: (() -> Unit)? = null

        fun start(ctx: Context) {
            val i = Intent(ctx, ReceiverService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ReceiverService::class.java).setAction(ACTION_STOP))
        }
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Номер поколения. Каждый запуск увеличивает его, старые потоки видят
     * несовпадение и завершаются сами — иначе быстрый «стоп-старт» оставил бы
     * два сервера на одном UUID.
     */
    private val generation = AtomicInteger(0)

    private lateinit var hub: MediaHub
    private var adapter: BluetoothAdapter? = null

    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var writer: FrameWriter? = null
    @Volatile private var sentArtKey: String? = null
    @Volatile private var dirty = true

    private var wake: PowerManager.WakeLock? = null
    private var lastPush = 0L
    private var modeReceiverRegistered = false

    /** Чтение библиотеки Poweramp — в своём потоке, это запросы к БД. */
    private val library = Executors.newSingleThreadExecutor()

    /** Poweramp сообщает о смене shuffle/repeat широковещательно. */
    private val modeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != Poweramp.ACTION_MODE_CHANGED) return
            hub.powerampShuffle = i.getIntExtra(Poweramp.EXTRA_SHUFFLE, hub.powerampShuffle)
            hub.powerampRepeat = i.getIntExtra(Poweramp.EXTRA_REPEAT, hub.powerampRepeat)
            dirty = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        hub = MediaHub(this) { dirty = true }
        adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        try {
            startForeground(NOTIF_ID, buildNotification("Ожидание пульта"))
        } catch (e: Exception) {
            // Android 14+ требует разрешение BLUETOOTH_* для сервиса типа
            // connectedDevice. Без него подниматься нечему.
            Log.w(TAG, "Не удалось поднять сервис: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true

        val gen = generation.incrementAndGet()
        main.post { hub.start() }
        registerModeReceiver()

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "poweremote:link")
            .also { it.setReferenceCounted(false); it.acquire() }

        Thread({ acceptLoop(gen) }, "bt-accept-$gen").apply { isDaemon = true; start() }
        Thread({ pushLoop(gen) }, "bt-push-$gen").apply { isDaemon = true; start() }
        notifyStatus()
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        try { library.shutdownNow() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun alive(gen: Int) = running && gen == generation.get()

    private fun shutdown() {
        if (!running && generation.get() == 0) return
        running = false
        generation.incrementAndGet()          // старые потоки увидят это и выйдут
        clientName = null
        unregisterModeReceiver()
        main.post { try { hub.stop() } catch (_: Exception) {} }
        closeClient()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        notifyStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun registerModeReceiver() {
        if (modeReceiverRegistered) return
        try {
            val filter = IntentFilter(Poweramp.ACTION_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(modeReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(modeReceiver, filter)
            }
            modeReceiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterModeReceiver() {
        if (!modeReceiverRegistered) return
        try { unregisterReceiver(modeReceiver) } catch (_: Exception) {}
        modeReceiverRegistered = false
    }

    // --------------------------------------------------------------- bluetooth

    private fun acceptLoop(gen: Int) {
        while (alive(gen)) {
            val bt = adapter
            if (bt == null || !bt.isEnabled) {
                sleep(3000)
                continue
            }
            try {
                val server = bt.listenUsingInsecureRfcommWithServiceRecord(
                    Link.SDP_NAME, Link.SERVICE_UUID
                )
                serverSocket = server
                val s = server.accept()               // ждём подключения пульта
                try { server.close() } catch (_: Exception) {}
                serverSocket = null
                if (!alive(gen)) {
                    try { s.close() } catch (_: Exception) {}
                    return
                }
                handleClient(s, gen)
            } catch (e: SecurityException) {
                Log.w(TAG, "Нет разрешения Bluetooth: ${e.message}")
                sleep(5000)
            } catch (e: Exception) {
                if (alive(gen)) {
                    Log.w(TAG, "Сокет закрыт: ${e.message}")
                    sleep(1500)
                }
            }
        }
    }

    private fun handleClient(s: BluetoothSocket, gen: Int) {
        closeClient()
        socket = s
        writer = FrameWriter(s.outputStream)
        sentArtKey = null
        dirty = true
        clientName = try { s.remoteDevice?.name ?: s.remoteDevice?.address } catch (_: Exception) { null }
        updateNotification("Пульт: ${clientName ?: "подключён"}")
        notifyStatus()

        val reader = FrameReader(s.inputStream)
        try {
            while (alive(gen)) {
                val frame = reader.read()
                if (frame.type != Link.TYPE_JSON) continue
                val o = JSONObject(String(frame.payload, Charsets.UTF_8))
                main.post { execute(o) }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Пульт отключился: ${e.message}")
        } finally {
            closeClient()
            clientName = null
            if (alive(gen)) updateNotification("Ожидание пульта")
            notifyStatus()
        }
    }

    private fun closeClient() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        writer = null
    }

    /** Исполняется в главном потоке: MediaController требует Looper. */
    private fun execute(o: JSONObject) {
        val v = o.optLong(Cmd.VALUE, 0L)
        val x = o.optLong(Cmd.EXTRA, 0L)
        when (o.optString(Cmd.KEY)) {
            Cmd.TOGGLE -> hub.toggle()
            Cmd.PLAY -> hub.play()
            Cmd.PAUSE -> hub.pause()
            Cmd.NEXT -> hub.next()
            Cmd.PREV -> hub.prev()
            Cmd.SEEK -> hub.seek(v)
            Cmd.SEEK_REL -> hub.seekRelative(v)
            Cmd.VOLUME -> hub.setVolume(v.toInt())
            Cmd.VOLUME_DELTA -> hub.nudgeVolume(v.toInt())
            Cmd.SHUFFLE -> hub.toggleShuffle()
            Cmd.REPEAT -> hub.cycleRepeat()
            Cmd.RATING -> hub.setRating(v.toInt())
            Cmd.LIKE -> hub.like()
            Cmd.UNLIKE -> hub.unlike()
            Cmd.NEXT_CAT -> hub.nextCategory()
            Cmd.PREV_CAT -> hub.prevCategory()
            Cmd.SLEEP -> hub.setSleep(v, x == 1L)
            Cmd.ASK_DATA -> {
                Poweramp.askDataPermission(this)
                hub.invalidateBrowse()
            }
            Cmd.BROWSE -> browse(v)
            Cmd.PLAY_FOLDER -> Poweramp.playFolder(this, v)
            Cmd.PLAY_FILE -> Poweramp.playFile(this, x, v)
            Cmd.HELLO, Cmd.SYNC, Cmd.PING -> sentArtKey = null   // заставим переслать обложку
        }
        dirty = true
    }

    /** Список папки читаем в фоне и шлём отдельным сообщением "list". */
    private fun browse(folderId: Long) {
        if (library.isShutdown) return
        try {
            library.execute {
                val listing = Poweramp.browse(this, folderId)
                val w = writer ?: return@execute
                try {
                    w.writeText(Link.TYPE_JSON, listing.toJson().toString())
                } catch (e: Exception) {
                    Log.i(TAG, "Список не ушёл: ${e.message}")
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Отдаёт состояние: сразу при изменении, но не чаще ~4 раз в секунду,
     * и как минимум раз в PUSH_INTERVAL_MS, чтобы пульт знал, что связь жива.
     */
    private fun pushLoop(gen: Int) {
        while (alive(gen)) {
            sleep(250)
            val w = writer ?: continue
            val now = System.currentTimeMillis()
            if (!dirty && now - lastPush < Link.PUSH_INTERVAL_MS) continue

            val state = snapshotOnMain() ?: continue
            try {
                w.writeText(Link.TYPE_JSON, state.toJson().toString())
                if (state.artKey != sentArtKey && hub.artReady) {
                    w.write(Link.TYPE_ART, hub.artJpeg ?: ByteArray(0))
                    sentArtKey = state.artKey
                }
                // Флаги гасим только после успешной отправки, иначе изменение
                // потерялось бы при обрыве.
                dirty = false
                lastPush = now
            } catch (e: Exception) {
                Log.i(TAG, "Обрыв отправки: ${e.message}")
                closeClient()
            }
        }
    }

    private fun snapshotOnMain(): PlayerState? {
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<PlayerState>(1)
        main.post {
            box[0] = try { hub.snapshot() } catch (_: Exception) { null }
            latch.countDown()
        }
        return try {
            if (latch.await(1500, TimeUnit.MILLISECONDS)) box[0] else null
        } catch (_: InterruptedException) {
            null
        }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    private fun notifyStatus() = main.post { onStatus?.invoke() }

    // ------------------------------------------------------------ уведомление

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Связь с пультом", NotificationManager.IMPORTANCE_LOW)
                        .also { it.setShowBadge(false) }
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }
}
