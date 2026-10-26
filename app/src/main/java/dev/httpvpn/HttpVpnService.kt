package dev.httpvpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.httpvpn.net.HttpCodec
import dev.httpvpn.net.ProxyEndpoint
import dev.httpvpn.net.Routes
import dev.httpvpn.net.TcpDial
import dev.httpvpn.net.TunEngine
import dev.httpvpn.net.Tunnel
import dev.httpvpn.net.TunnelFactory
import java.io.FileDescriptor
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.concurrent.thread

class HttpVpnService : VpnService() {
    @Volatile private var stopRequested = false
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var engine: TunEngine? = null
    @Volatile private var lastError: String? = null
    @Volatile private var underlying: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var appBypassesVpn = false
    private var worker: Thread? = null
    private var stats: Thread? = null
    private var wake: PowerManager.WakeLock? = null
    private val wakeGuard = Any()
    @Volatile private var wakePinnedBySetup = true
    private var sessionsNeedWake = false
    private var wakeUntilNs = 0L
    private var lastNotifAt = 0L
    private var lastNotifText: String? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = getString(R.string.channel_desc)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                requestStop()
                if (worker?.isAlive != true) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                if (worker?.isAlive == true) return START_NOT_STICKY
                stopRequested = false
                lastError = null
                acquireWake()
                try {
                    foreground("Подключение…")
                } catch (e: Exception) {
                    releaseWake()
                    VpnRuntime.publish(VpnState(false, 0, 0, e.message ?: "Не удалось запустить службу"))
                    stopSelf()
                    return START_NOT_STICKY
                }
                worker = thread(name = "http-vpn") { runVpn() }
                return START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        requestStop()
        super.onRevoke()
    }

    override fun onDestroy() {
        requestStop()
        super.onDestroy()
    }

    private fun runVpn() {
        var endMessage = "VPN выключен"
        var tx = 0L
        var rx = 0L
        try {
            val config = Prefs.load(this)
            VpnRuntime.publish(VpnState(true, 0, 0, "Подключение к ${config.host}:${config.port}…"))
            val address = try {
                InetAddress.getByName(config.host)
            } catch (_: Exception) {
                throw IOException("Не удалось найти адрес прокси")
            }
            val endpoint = ProxyEndpoint(address, config.port, config.username, config.password)
            startNetworkWatch()
            val pfd = establishTun(config) ?: throw IOException("Разрешение на VPN не получено")
            tun = pfd
            val fd = pfd.fileDescriptor
            val writeLock = Any()
            val localEngine = TunEngine(
                proxy = endpoint,
                tunnels = TunnelFactory { host, port, register ->
                    val socket = openProxySocket(endpoint.address)
                    if (!register(socket)) throw IOException("stopped")
                    HttpCodec.establish(socket, endpoint, host, port)
                    Tunnel(
                        input = socket.getInputStream(),
                        output = socket.getOutputStream(),
                        shutdownOutput = { try { socket.shutdownOutput() } catch (_: Exception) {} },
                        close = { socket.close() },
                    )
                },
                dial = TcpDial { host, port ->
                    val socket = openProxySocket(endpoint.address)
                    try {
                        HttpCodec.establish(socket, endpoint, host, port, 8_000)
                    } catch (e: Exception) {
                        socket.close()
                        throw e
                    }
                    socket
                },
                writeTun = write@{ packet ->
                    if (stopRequested) return@write
                    noteActivity()
                    synchronized(writeLock) {
                        val wrote = Os.write(fd, ByteBuffer.wrap(packet))
                        if (wrote != packet.size) Log.w(TAG, "short tun write $wrote/${packet.size}")
                    }
                },
                onError = { message -> lastError = "Последняя ошибка: $message" },
                trace = { Log.d(TAG, it) },
                onKeepAwake = ::onSessionsNeedWake,
            )
            engine = localEngine
            stats = thread(isDaemon = true, name = "httpvpn-stats") {
                try {
                    while (!stopRequested) {
                        publishStats(config, localEngine)
                        Thread.sleep(1000)
                    }
                } catch (_: InterruptedException) {
                }
            }
            beginIdleWake()
            val buffer = ByteBuffer.allocate(32767)
            while (!stopRequested) {
                buffer.clear()
                val n = Os.read(fd, buffer)
                if (n <= 0) break
                noteActivity()
                localEngine.onPacket(buffer.array(), n)
            }
        } catch (e: Exception) {
            if (!stopRequested) {
                endMessage = if (e is ErrnoException && stopRequested) {
                    "VPN выключен"
                } else {
                    e.message ?: "Ошибка VPN"
                }
                Log.e(TAG, "vpn stopped", e)
            }
        } finally {
            stopRequested = true
            tx = engine?.tx?.get() ?: 0
            rx = engine?.rx?.get() ?: 0
            stats?.interrupt()
            try { stats?.join(2000) } catch (_: Exception) {}
            try { engine?.close() } catch (_: Exception) {}
            engine = null
            try { tun?.close() } catch (_: Exception) {}
            tun = null
            releaseWake()
            stopNetworkWatch()
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            VpnRuntime.publish(VpnState(false, tx, rx, endMessage))
            stopSelf()
        }
    }

    private fun establishTun(config: ProxyConfig): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("HTTP VPN")
            .setMtu(1500)
            .setBlocking(true)
            .addAddress("10.10.0.2", 32)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setConfigureIntent(contentIntent())
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        try {
            builder.addDisallowedApplication(packageName)
            appBypassesVpn = true
        } catch (e: Exception) {
            appBypassesVpn = false
            Log.w(TAG, "exclude self from vpn", e)
        }
        if (config.bypassLan) {
            for ((addr, prefix) in Routes.publicInternet) builder.addRoute(addr, prefix)
        } else {
            builder.addRoute("0.0.0.0", 0)
        }
        return builder.establish()
    }

    private fun openProxySocket(proxyAddress: InetAddress): Socket {
        val socket = Socket()
        try {
            val any = if (proxyAddress is Inet6Address) {
                InetAddress.getByName("::")
            } else {
                InetAddress.getByName("0.0.0.0")
            }
            socket.bind(InetSocketAddress(any, 0))
            val marked = protect(socket) || protectByFd(socket)
            val bound = bindOutsideVpn(socket)
            if (marked || bound || appBypassesVpn) return socket
            throw IOException("Не удалось пустить сокет прокси мимо VPN")
        } catch (e: IOException) {
            try { socket.close() } catch (_: Exception) {}
            throw e
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw IOException("Не удалось пустить сокет прокси мимо VPN", e)
        }
    }

    private fun protectByFd(socket: Socket): Boolean {
        return try {
            val getter = Socket::class.java.getDeclaredMethod("getFileDescriptor\$")
            getter.isAccessible = true
            val fd = getter.invoke(socket) as? FileDescriptor ?: return false
            val field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            val raw = field.getInt(fd)
            raw >= 0 && protect(raw)
        } catch (e: Exception) {
            Log.w(TAG, "protect fd", e)
            false
        }
    }

    private fun bindOutsideVpn(socket: Socket): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = underlying ?: pickUnderlying(cm)?.also { underlying = it } ?: return false
        return try {
            network.bindSocket(socket)
            true
        } catch (e: Exception) {
            Log.w(TAG, "bindSocket", e)
            false
        }
    }

    private fun startNetworkWatch() {
        val cm = getSystemService(ConnectivityManager::class.java)
        underlying = pickUnderlying(cm)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= 28) {
            request.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                underlying = network
            }

            override fun onLost(network: Network) {
                if (underlying == network) underlying = pickUnderlying(cm)
            }
        }
        try {
            cm.registerNetworkCallback(request.build(), callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "network callback", e)
        }
    }

    private fun stopNetworkWatch() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    private fun pickUnderlying(cm: ConnectivityManager): Network? {
        return cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private fun publishStats(config: ProxyConfig, localEngine: TunEngine) {
        val tx = localEngine.tx.get()
        val rx = localEngine.rx.get()
        val text = buildString {
            append("Подключено к ")
            append(config.host)
            append(':')
            append(config.port)
            append("\n↑ ")
            append(formatBytes(tx))
            append("    ↓ ")
            append(formatBytes(rx))
            val err = lastError
            if (!err.isNullOrBlank()) {
                append('\n')
                append(err)
            }
        }
        VpnRuntime.publish(VpnState(true, tx, rx, text))
        val notif = "↑ ${formatBytes(tx)}   ↓ ${formatBytes(rx)}"
        val now = System.currentTimeMillis()
        val changed = notif != lastNotifText
        val due = lastNotifAt == 0L || now - lastNotifAt >= NOTIF_INTERVAL_MS
        if (changed && due) {
            lastNotifAt = now
            lastNotifText = notif
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIF_ID, buildNotification(notif))
        }
        releaseWakeIfIdle()
    }

    private fun requestStop() {
        stopRequested = true
        stats?.interrupt()
        try { tun?.close() } catch (_: Exception) {}
        try { engine?.close() } catch (_: Exception) {}
    }

    private fun foreground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .addAction(0, getString(R.string.disconnect), stopIntent())
            .build()
    }

    private fun contentIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
    }

    private fun stopIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getService(
            this,
            1,
            Intent(this, HttpVpnService::class.java).setAction(ACTION_STOP),
            flags,
        )
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWake() {
        synchronized(wakeGuard) { acquireWakeLocked() }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLocked() {
        if (stopRequested) return
        val existing = wake
        if (existing != null) {
            if (!existing.isHeld) existing.acquire()
            return
        }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "httpvpn:tun").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun beginIdleWake() {
        synchronized(wakeGuard) {
            wakePinnedBySetup = false
            wakeUntilNs = System.nanoTime() + WAKE_HOLD_NS
            acquireWakeLocked()
        }
    }

    private fun noteActivity() {
        synchronized(wakeGuard) {
            wakeUntilNs = System.nanoTime() + WAKE_HOLD_NS
            acquireWakeLocked()
        }
    }

    private fun onSessionsNeedWake(needed: Boolean) {
        synchronized(wakeGuard) {
            sessionsNeedWake = needed
            if (needed) acquireWakeLocked()
        }
    }

    private fun releaseWakeIfIdle() {
        synchronized(wakeGuard) {
            if (wakePinnedBySetup || sessionsNeedWake) return
            if (System.nanoTime() < wakeUntilNs) return
            releaseWakeLocked()
        }
    }

    private fun releaseWake() {
        synchronized(wakeGuard) {
            wakePinnedBySetup = false
            sessionsNeedWake = false
            wakeUntilNs = 0L
            releaseWakeLocked()
        }
    }

    private fun releaseWakeLocked() {
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (_: Exception) {
        }
    }

    private fun formatBytes(value: Long): String {
        val v = value.coerceAtLeast(0)
        if (v < 1024) return "$v Б"
        val kb = v / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f КБ", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f МБ", mb)
        return String.format(Locale.US, "%.2f ГБ", mb / 1024.0)
    }

    companion object {
        const val ACTION_START = "dev.httpvpn.START"
        const val ACTION_STOP = "dev.httpvpn.STOP"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1
        private const val TAG = "HttpVpn"
        private const val NOTIF_INTERVAL_MS = 15_000L
        private const val WAKE_HOLD_NS = 10_000_000_000L
    }
}
