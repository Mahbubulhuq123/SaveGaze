package com.savegaze.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.savegaze.MainActivity
import com.savegaze.R
import com.savegaze.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection

/**
 * Local DNS-only VPN service replicating ContentGuard's hosts-file & website blocking across all apps and browsers.
 * Binds upstream sockets to the active physical network, prioritizes ISP/router DNS, falls back to public DNS
 * and DNS-over-HTTPS (port 443), and sends fast TCP RST for Private DNS (:853) probes so all browsers & apps work.
 */
class SaveGazeVpnService : VpnService() {

    companion object {
        private const val TAG = "SaveGazeVpnService"
        private const val CHANNEL_ID = "savegaze_vpn_channel"
        private const val NOTIFICATION_ID = 1002
        private const val VPN_ADDR = "10.10.10.2"
        private const val VPN_DNS = "10.10.10.1"
        private const val DNS_CACHE_TTL_MS = 45_000L
        private const val MAX_CACHE_ENTRIES = 512
        private val FALLBACK_UPSTREAMS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
        private val DOH_ENDPOINTS = listOf(
            "https://1.1.1.1/dns-query",
            "https://8.8.8.8/dns-query"
        )
        const val ACTION_START = "com.savegaze.guard.START_VPN"
        const val ACTION_STOP = "com.savegaze.guard.STOP_VPN"

        private val _isRunning = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, SaveGazeVpnService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SaveGazeVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private data class CachedDnsEntry(
        val responseBytes: ByteArray,
        val expiresAtMs: Long
    )

    private var vpnScope: CoroutineScope? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private val tunOutLock = Any()
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var activeDomains: Set<String> = ContentGuardLists.DEFAULT_ADULT_DOMAINS

    @Volatile
    private var underlyingPhysicalNetwork: Network? = null

    @Volatile
    private var physicalDnsServers: List<InetAddress> = emptyList()

    private val recentBlockedDomainHits = ConcurrentHashMap<String, Long>()
    private val dnsCache = ConcurrentHashMap<String, CachedDnsEntry>()

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(applicationContext)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun refreshPhysicalNetwork() {
        try {
            val candidates = connectivityManager.allNetworks.mapNotNull { net ->
                val caps = connectivityManager.getNetworkCapabilities(net) ?: return@mapNotNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
                val score = (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 4 else 0) +
                    (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else 1)
                net to score
            }
            val bestNet = candidates.maxByOrNull { it.second }?.first
            underlyingPhysicalNetwork = bestNet

            val linkDns = bestNet?.let { net ->
                connectivityManager.getLinkProperties(net)?.dnsServers
                    ?.filterIsInstance<Inet4Address>()
                    ?.filter { addr ->
                        !addr.isLoopbackAddress && !addr.isAnyLocalAddress && addr.hostAddress != VPN_DNS && addr.hostAddress != VPN_ADDR
                    }
            } ?: emptyList()
            physicalDnsServers = linkDns

            if (_isRunning.value && tunInterface != null) {
                setUnderlyingNetworks(if (bestNet != null) arrayOf(bestNet) else null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshPhysicalNetwork failed: ${e.message}")
        }
    }

    private fun registerPhysicalNetworkCallback() {
        unregisterPhysicalNetworkCallback()
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = refreshPhysicalNetwork()
                override fun onLost(network: Network) = refreshPhysicalNetwork()
                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                    refreshPhysicalNetwork()
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    refreshPhysicalNetwork()
                }
            }
            connectivityManager.registerNetworkCallback(req, cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Failed registering network callback", e)
        }
    }

    private fun unregisterPhysicalNetworkCallback() {
        networkCallback?.let { cb ->
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
    }

    private fun startVpn() {
        if (_isRunning.value && tunInterface != null) return

        createChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Foreground start fallback", e)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }

        refreshPhysicalNetwork()

        val builder = Builder()
            .setSession("SaveGaze DNS Shield")
            .setMtu(1500)
            .addAddress(VPN_ADDR, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        underlyingPhysicalNetwork?.let { physicalNet ->
            builder.setUnderlyingNetworks(arrayOf(physicalNet))
        }

        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) {
            Log.w(TAG, "addDisallowedApplication failed", e)
        }

        tunInterface = builder.establish()
        if (tunInterface == null) {
            Log.e(TAG, "VpnService permission missing; cannot establish TUN")
            stopSelf()
            return
        }

        _isRunning.value = true
        registerPhysicalNetworkCallback()
        vpnScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        vpnScope = scope

        scope.launch {
            preferencesManager.customWebsitesFlow.collect { custom ->
                val social = preferencesManager.getBlockSocialDistractions()
                activeDomains = ContentGuardRules.buildActiveDomains(custom, social)
            }
        }
        scope.launch {
            preferencesManager.blockSocialDistractionsFlow.collect { social ->
                val custom = preferencesManager.getCustomWebsites()
                activeDomains = ContentGuardRules.buildActiveDomains(custom, social)
            }
        }

        scope.launch {
            runTunLoop()
        }
    }

    private fun runTunLoop() {
        val pfd = tunInterface ?: return
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(4096)

        try {
            while (_isRunning.value) {
                val len = input.read(buffer)
                if (len <= 0) break

                // Fast-reject Private DNS (TCP :853) or DNS-over-TCP (:53) SYN so Android immediately uses UDP :53
                val tcpSyn = DnsPacketCodec.parseIpv4TcpSyn(buffer, len)
                if (tcpSyn != null) {
                    val rst = DnsPacketCodec.buildTcpRstResponseIpv4(tcpSyn)
                    writeTunReply(output, rst)
                    continue
                }

                val parsed = DnsPacketCodec.parseIpv4UdpDns(buffer, len) ?: continue
                vpnScope?.launch {
                    handleDnsPacket(parsed, output)
                }
            }
        } catch (e: Exception) {
            if (_isRunning.value) {
                Log.w(TAG, "VPN TUN loop terminated: ${e.message}")
            }
        }
    }

    private fun writeTunReply(output: FileOutputStream, ipReply: ByteArray) {
        synchronized(tunOutLock) {
            try {
                output.write(ipReply)
                output.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing DNS reply to TUN: ${e.message}")
            }
        }
    }

    private suspend fun handleDnsPacket(
        parsed: DnsPacketCodec.ParsedDnsPacket,
        output: FileOutputStream
    ) {
        val domain = parsed.queryDomain
        val blockerOn = preferencesManager.getWebsiteBlockerEnabled()
        val matchedDomain = if (blockerOn && domain != null) {
            ContentGuardRules.isDomainBlocked(domain, activeDomains)
        } else {
            null
        }

        val dnsResp: ByteArray = if (matchedDomain != null) {
            val now = System.currentTimeMillis()
            val last = recentBlockedDomainHits[matchedDomain] ?: 0L
            if (now - last > 5_000L) {
                recentBlockedDomainHits[matchedDomain] = now
                preferencesManager.recordCrossBlockAndMaybeLockout(now)
            }
            DnsPacketCodec.buildNxDomainResponse(parsed.dnsPayload)
        } else {
            resolveWithCacheAndFallbacks(domain, parsed.queryType, parsed.dnsPayload)
        }

        val ipReply = DnsPacketCodec.wrapUdpResponseIpv4(parsed, dnsResp)
        writeTunReply(output, ipReply)
    }

    private fun resolveWithCacheAndFallbacks(
        domain: String?,
        queryType: Int,
        query: ByteArray
    ): ByteArray {
        val now = System.currentTimeMillis()
        val cacheKey = if (!domain.isNullOrBlank()) "$domain#$queryType" else null
        if (cacheKey != null) {
            val cached = dnsCache[cacheKey]
            if (cached != null && now < cached.expiresAtMs) {
                return DnsPacketCodec.copyWithTransactionId(cached.responseBytes, query)
            }
        }

        val resp = forwardDnsUdp(query) ?: forwardDnsOverHttps(query)
        if (resp != null) {
            if (cacheKey != null) {
                if (dnsCache.size >= MAX_CACHE_ENTRIES) {
                    dnsCache.clear()
                }
                dnsCache[cacheKey] = CachedDnsEntry(resp, now + DNS_CACHE_TTL_MS)
            }
            return resp
        }

        // Refresh physical network once on miss and fail cleanly with SERVFAIL instead of blackholing
        refreshPhysicalNetwork()
        val retry = forwardDnsUdp(query) ?: forwardDnsOverHttps(query)
        return retry ?: DnsPacketCodec.buildServFailResponse(query)
    }

    private fun forwardDnsUdp(query: ByteArray): ByteArray? {
        val net = underlyingPhysicalNetwork
        val servers = ArrayList<InetAddress>(physicalDnsServers.size + FALLBACK_UPSTREAMS.size)
        servers.addAll(physicalDnsServers)
        for (ip in FALLBACK_UPSTREAMS) {
            try {
                val addr = InetAddress.getByName(ip)
                if (servers.none { it.hostAddress == addr.hostAddress }) {
                    servers.add(addr)
                }
            } catch (_: Exception) {
            }
        }

        for (addr in servers) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                net?.let {
                    try { it.bindSocket(socket) } catch (_: Exception) {}
                }
                protect(socket)
                socket.soTimeout = 1300
                socket.send(DatagramPacket(query, query.size, addr, 53))
                val respBuf = ByteArray(4096)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                socket.receive(respPkt)
                if (respPkt.length >= 12) {
                    return respBuf.copyOf(respPkt.length)
                }
            } catch (_: Exception) {
                // Try next upstream DNS
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun forwardDnsOverHttps(query: ByteArray): ByteArray? {
        val net = underlyingPhysicalNetwork
        for (endpoint in DOH_ENDPOINTS) {
            var conn: HttpsURLConnection? = null
            try {
                val url = URL(endpoint)
                conn = (if (net != null) net.openConnection(url) else url.openConnection()) as HttpsURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/dns-message")
                conn.setRequestProperty("Accept", "application/dns-message")
                conn.outputStream.use { it.write(query) }
                if (conn.responseCode == 200) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size >= 12) return bytes
                }
            } catch (_: Exception) {
                // Try next DoH resolver
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun stopVpn() {
        _isRunning.value = false
        unregisterPhysicalNetworkCallback()
        vpnScope?.cancel()
        vpnScope = null
        dnsCache.clear()
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SaveGaze DNS Blocker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SaveGaze Website Blocker Active")
            .setContentText("Filtering adult & blocked domains system-wide")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
