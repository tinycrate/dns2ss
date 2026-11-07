package com.tiny.dns2ss.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tiny.dns2ss.BuildConfig
import com.tiny.dns2ss.MainActivity
import com.tiny.dns2ss.R
import com.tiny.dns2ss.config.HevConfig
import com.tiny.dns2ss.config.HevConfigBuilder
import com.tiny.dns2ss.config.SslocalConfig
import com.tiny.dns2ss.config.VpnMode
import com.tiny.dns2ss.socks.RouterConfig
import com.tiny.dns2ss.socks.Socks5Router
import hev.htproxy.TProxyService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class DnsVpnService : VpnService() {
    companion object {
        const val ACTION_START = "com.tiny.dns2ss.action.START"
        const val ACTION_STOP = "com.tiny.dns2ss.action.STOP"
        const val ACTION_RESTART = "com.tiny.dns2ss.action.RESTART"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1001
        private const val VPN_MTU = 1500
        private const val TUN_V4 = "198.18.0.1"
        private const val DNS_V4 = "198.18.0.2"
        private const val TUN_V6 = "fd00::1"
        private const val DNS_V6 = "fd00::2"

        fun start(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }

        fun restart(context: Context) {
            val intent = Intent(context, DnsVpnService::class.java).setAction(ACTION_RESTART)
            runCatching { context.startService(intent) }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val tproxyLock = Any()
    private val starting = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    @Volatile private var currentMode: VpnMode = VpnMode.default
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var sslocal: SsLocalProcess? = null
    @Volatile private var router: Socks5Router? = null
    @Volatile private var hevStarted = false
    @Volatile private var tproxyTouched = false
    @Volatile private var routedDnsServers: List<String> = emptyList()
    @Volatile private var activeLogVerbosity: com.tiny.dns2ss.config.LogVerbosity =
        com.tiny.dns2ss.config.LogVerbosity.default
    @Volatile private var activeIpv6Enabled: Boolean = true
    @Volatile private var startedAtMs: Long = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var restartJob: Job? = null
    @Volatile private var ignoreNetworkEventsUntil: Long = 0
    @Volatile private var networkSignature: String = ""

    override fun onCreate() {
        super.onCreate()
        createChannel()
        VpnStatus.quickTileRefresh = { QuickTileService.requestUpdate(this) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop()
            ACTION_START -> requestStart()
            ACTION_RESTART -> requestRestart("Restarting")
            else -> {
                if (VpnStatus.data.value.state == VpnRunState.STOPPED) requestStart()
            }
        }
        return START_NOT_STICKY
    }

    private fun requestStart() {
        if (starting.get() || stopping.get()) return
        val state = VpnStatus.data.value.state
        if (state == VpnRunState.RUNNING || state == VpnRunState.STARTING) return
        if (VpnService.prepare(this) != null) {
            startForegroundCompat("VPN permission missing")
            fail("VPN permission is missing; open the app to grant it")
            return
        }
        starting.set(true)
        stopping.set(false)
        startForegroundCompat("Starting…")
        serviceScope.launch { startVpn() }
    }

    private suspend fun startVpn() {
        try {
            val prefs = AppPreferences(this)
            val config = prefs.loadValidated()
            currentMode = config.mode
            activeLogVerbosity = config.logVerbosity
            activeIpv6Enabled = config.ipv6Enabled
            VpnLog.enabled = config.logVerbosity != com.tiny.dns2ss.config.LogVerbosity.OFF
            if (!VpnLog.enabled) VpnLog.clear()
            VpnStatus.set(VpnRunState.STARTING, currentMode, "Starting")
            VpnLog.add("service", "starting mode=${config.mode}")
            updateNotification("Starting…")

            val chosenTunnelPort = FreePort.forTcpAndUdp()
            val chosenSocksPort = if (config.mode == VpnMode.FULL) FreePort.forTcpAndUdp() else null

            val process = SsLocalProcess(
                this,
                SslocalConfig(
                    ssUri = config.ss,
                    mode = config.mode,
                    tunnelPort = chosenTunnelPort,
                    socksPort = chosenSocksPort,
                    dnsHost = config.dnsHost,
                    dnsPort = config.dnsPort,
                    logLevel = 0,
                    rustLog = config.logVerbosity.rustLog,
                ),
            ) { message -> onSsLocalExit(message) }
            process.start()
            sslocal = process
            if (abortIfStopped()) return

            val username = "u" + UUID.randomUUID().toString().replace("-", "").take(12)
            val password = UUID.randomUUID().toString().replace("-", "").take(20)
            val localRouter = Socks5Router(
                RouterConfig(
                    mode = config.mode,
                    tunnelHost = "127.0.0.1",
                    tunnelPort = chosenTunnelPort,
                    socksUpstreamHost = if (config.mode == VpnMode.FULL) "127.0.0.1" else null,
                    socksUpstreamPort = chosenSocksPort,
                    username = username,
                    password = password,
                    logger = { line -> VpnLog.add("router", line) },
                ),
            )
            val routerPort = localRouter.start()
            router = localRouter
            if (abortIfStopped()) return

            val routed = if (config.mode == VpnMode.FAST) {
                underlyingDnsServers().filter { config.ipv6Enabled || it.address.size == 4 }
            } else {
                emptyList()
            }
            routedDnsServers = routed.mapNotNull { it.hostAddress }
            val established = establishTun(config.mode, routed, config.ipv6Enabled)
            tun = established
            runCatching { setUnderlyingNetworks(null) }
            if (abortIfStopped()) return

            val hevConfig = HevConfig(routerPort = routerPort, username = username, password = password)
            val hevFile = File(filesDir, "hev.yaml")
            hevFile.writeText(HevConfigBuilder.build(hevConfig))
            if (!startTproxy(hevFile.absolutePath, established.fd)) {
                VpnLog.add("service", "hev start returned false; resetting native state and retrying")
                stopTproxy()
                delay(150)
                if (!startTproxy(hevFile.absolutePath, established.fd)) {
                    stopTproxy()
                    VpnLog.add("service", "hev start failed after native-state reset")
                    throw IllegalStateException("hev-socks5-tunnel failed to start")
                }
            }
            if (abortIfStopped()) return

            VpnStatus.set(VpnRunState.RUNNING, config.mode, "Running")
            startedAtMs = System.currentTimeMillis()
            DiagnosticsProvider.provider = { buildDiagnostics() }
            registerNetworkCallback()
            updateNotification("Running")
            VpnLog.add("service", "VPN running mode=${config.mode}")
        } catch (t: Throwable) {
            VpnLog.add("service", "start failed: ${t.javaClass.simpleName}: ${t.message}")
            cleanup()
            VpnStatus.set(VpnRunState.ERROR, currentMode, t.message ?: t.javaClass.simpleName)
            updateNotification("Error: ${t.message ?: t.javaClass.simpleName}")
            stopForegroundCompat()
            stopSelf()
        } finally {
            starting.set(false)
        }
    }

    private fun establishTun(
        mode: VpnMode,
        routedDns: List<java.net.InetAddress>,
        ipv6Enabled: Boolean,
    ): ParcelFileDescriptor {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(VPN_MTU)
            .setBlocking(false)
            .addAddress(TUN_V4, 32)
            .addDnsServer(DNS_V4)
        if (ipv6Enabled) {
            builder.addAddress(TUN_V6, 128)
            builder.addDnsServer(DNS_V6)
        }
        builder.setConfigureIntent(
                PendingIntent.getActivity(
                    this,
                    100,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

        if (mode == VpnMode.FAST) {
            builder.addRoute(DNS_V4, 32)
            if (ipv6Enabled) builder.addRoute(DNS_V6, 128)
            // Android considers the VPN network non-default when it has no default route.
            // The system resolver therefore keeps using the underlying network's DNS
            // servers. Route those individual resolver IPs into the tunnel as well.
            for (address in routedDns) {
                val prefix = if (address.address.size == 4) 32 else 128
                runCatching { builder.addRoute(address, prefix) }
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
            if (ipv6Enabled) builder.addRoute("::", 0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Throwable) {
        }
        if (BuildConfig.DEBUG) {
            // Test-only: a local SS test server running under adb shell must not be
            // re-captured by the full tunnel. Production builds do not exclude shell.
            runCatching { builder.addDisallowedApplication("com.android.shell") }
        }

        return builder.establish() ?: throw IllegalStateException("VPN interface could not be established")
    }

    private fun underlyingDnsServers(): List<java.net.InetAddress> {
        return try {
            val manager = getSystemService(android.net.ConnectivityManager::class.java)
            val addresses = linkedSetOf<java.net.InetAddress>()
            for (network in manager.allNetworks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                val link = manager.getLinkProperties(network) ?: continue
                addresses.addAll(link.dnsServers)
            }
            addresses.toList()
        } catch (t: Throwable) {
            VpnLog.add("service", "DNS discovery failed: ${t.message}")
            emptyList()
        }
    }

    private fun startTproxy(path: String, fd: Int): Boolean = synchronized(tproxyLock) {
        tproxyTouched = true
        val started = TProxyService.TProxyStartService(path, fd)
        if (started) hevStarted = true
        started
    }

    private fun stopTproxy() = synchronized(tproxyLock) {
        if (hevStarted || tproxyTouched) {
            hevStarted = false
            tproxyTouched = false
            runCatching { TProxyService.TProxyStopService() }
        }
    }

    private fun abortIfStopped(): Boolean {
        if (!stopping.get()) return false
        cleanup()
        return true
    }

    private fun buildDiagnostics(): AppDiagnostics {
        val currentRouter = router
        val stats = currentRouter?.stats
        val cache = runCatching { currentRouter?.cacheStats() }.getOrNull()
        return AppDiagnostics(
            mode = currentMode,
            logVerbosity = activeLogVerbosity,
            ipv6Enabled = activeIpv6Enabled,
            state = VpnStatus.data.value.state,
            routedDnsServers = routedDnsServers,
            cacheEntries = cache?.entries ?: 0,
            cacheHits = cache?.hits ?: 0,
            cacheMisses = cache?.misses ?: 0,
            dnsQueries = stats?.dnsQueries?.get() ?: 0,
            dnsResponses = stats?.dnsResponses?.get() ?: 0,
            tunnelBytes = stats?.tunnelBytes?.get() ?: 0,
            directBytes = stats?.directBytes?.get() ?: 0,
            fullBytes = stats?.fullBytes?.get() ?: 0,
            errors = stats?.errors?.get() ?: 0,
            pendingDns = currentRouter?.pendingDnsCount() ?: 0,
            uptimeMs = if (startedAtMs > 0) System.currentTimeMillis() - startedAtMs else 0,
        )
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = scheduleNetworkRestart()
            override fun onLost(network: Network) = scheduleNetworkRestart()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
                scheduleNetworkRestart()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                scheduleNetworkRestart()
        }
        networkSignature = currentNetworkSignature()
        ignoreNetworkEventsUntil = System.currentTimeMillis() + 3000
        runCatching { manager.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { VpnLog.add("service", "Network callback failed: ${it.message}") }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
    }

    private fun currentNetworkSignature(): String {
        return try {
            val manager = getSystemService(ConnectivityManager::class.java) ?: return ""
            val parts = ArrayList<String>()
            for (network in manager.allNetworks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val link = manager.getLinkProperties(network) ?: continue
                val dns = link.dnsServers.mapNotNull { it.hostAddress }.sorted().joinToString(",")
                parts += "${network.networkHandle}|${link.interfaceName}|$dns"
            }
            parts.sorted().joinToString(";")
        } catch (_: Throwable) {
            ""
        }
    }

    private fun scheduleNetworkRestart() {
        if (System.currentTimeMillis() < ignoreNetworkEventsUntil) return
        if (starting.get() || stopping.get()) return
        if (VpnStatus.data.value.state != VpnRunState.RUNNING) return
        val current = currentNetworkSignature()
        if (current == networkSignature) return
        networkSignature = current
        restartJob?.cancel()
        restartJob = serviceScope.launch {
            delay(1500)
            if (!stopping.get() && !starting.get()) {
                requestRestart("Network changed")
            }
        }
    }

    private fun requestRestart(reason: String) {
        if (stopping.get()) return
        val state = VpnStatus.data.value.state
        if (state == VpnRunState.STOPPED || state == VpnRunState.ERROR) {
            requestStart()
            return
        }
        if (!starting.compareAndSet(false, true)) return
        stopping.set(false)
        VpnStatus.set(VpnRunState.STARTING, currentMode, reason)
        updateNotification(reason)
        serviceScope.launch {
            cleanup()
            if (stopping.get()) return@launch
            startForegroundCompat(reason)
            startVpn()
        }
    }

    private fun onSsLocalExit(message: String) {
        if (stopping.get()) return
        VpnLog.add("service", message)
        serviceScope.launch {
            if (stopping.get()) return@launch
            fail("Shadowsocks process exited")
        }
    }

    private fun requestStop() {
        if (stopping.get()) return
        stopping.set(true)
        VpnStatus.set(VpnRunState.STOPPING, currentMode, "Stopping")
        updateNotification("Stopping…")
        serviceScope.launch {
            cleanup()
            VpnStatus.set(VpnRunState.STOPPED, currentMode, "")
            stopForegroundCompat()
            stopSelf()
            stopping.set(false)
        }
    }

    private fun fail(message: String) {
        stopping.set(true)
        cleanup()
        VpnStatus.set(VpnRunState.ERROR, currentMode, message)
        updateNotification("Error: $message")
        stopForegroundCompat()
        stopSelf()
        stopping.set(false)
    }

    private fun cleanup() {
        synchronized(stateLock) {
            restartJob?.cancel()
            restartJob = null
            unregisterNetworkCallback()
            DiagnosticsProvider.provider = null
            startedAtMs = 0
            routedDnsServers = emptyList()
            networkSignature = ""
            stopTproxy()
            val currentTun = tun
            tun = null
            runCatching { currentTun?.close() }
            val currentRouter = router
            router = null
            runCatching { currentRouter?.stop() }
            val currentSs = sslocal
            sslocal = null
            runCatching { currentSs?.stop() }
            runCatching { File(filesDir, "hev.yaml").delete() }
        }
    }

    override fun onRevoke() {
        requestStop()
    }

    override fun onDestroy() {
        cleanup()
        runCatching { stopForegroundCompat() }
        serviceScope.cancel()
        if (VpnStatus.data.value.state == VpnRunState.RUNNING) {
            VpnStatus.set(VpnRunState.STOPPED, currentMode, "")
        }
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            101,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.stop), stopIntent)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }
}
