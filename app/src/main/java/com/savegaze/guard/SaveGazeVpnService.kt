package com.savegaze.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Local DNS-only VPN service replicating ContentGuard's hosts-file & website blocking across all apps and browsers.
 */
class SaveGazeVpnService : VpnService() {

    companion object {
        private const val TAG = "SaveGazeVpnService"
        private const val CHANNEL_ID = "savegaze_vpn_channel"
        private const val NOTIFICATION_ID = 1002
        private const val VPN_ADDR = "10.10.10.2"
        private const val VPN_DNS = "10.10.10.1"
        private const val UPSTREAM_PRIMARY = "1.1.1.1"
        private const val UPSTREAM_SECONDARY = "8.8.8.8"
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

    private var vpnScope: CoroutineScope? = null
    private var tunInterface: ParcelFileDescriptor? = null
    private var tunOutLock = Any()
    private lateinit var preferencesManager: PreferencesManager

    @Volatile
    private var activeDomains: Set<String> = ContentGuardLists.DEFAULT_ADULT_DOMAINS
    private val recentBlockedDomainHits = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
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

        val builder = Builder()
            .setSession("SaveGaze DNS Shield")
            .setMtu(1500)
            .addAddress(VPN_ADDR, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)

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

        val dnsResp: ByteArray? = if (matchedDomain != null) {
            val now = System.currentTimeMillis()
            val last = recentBlockedDomainHits[matchedDomain] ?: 0L
            if (now - last > 5_000L) {
                recentBlockedDomainHits[matchedDomain] = now
                preferencesManager.recordCrossBlockAndMaybeLockout(now)
            }
            DnsPacketCodec.buildNxDomainResponse(parsed.dnsPayload)
        } else {
            forwardDnsUpstream(parsed.dnsPayload)
        }

        if (dnsResp != null) {
            val ipReply = DnsPacketCodec.wrapUdpResponseIpv4(parsed, dnsResp)
            synchronized(tunOutLock) {
                try {
                    output.write(ipReply)
                    output.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed writing DNS reply to TUN", e)
                }
            }
        }
    }

    private fun forwardDnsUpstream(query: ByteArray): ByteArray? {
        for (upstream in arrayOf(UPSTREAM_PRIMARY, UPSTREAM_SECONDARY)) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                protect(socket)
                socket.soTimeout = 2500
                val addr = InetAddress.getByName(upstream)
                socket.send(DatagramPacket(query, query.size, addr, 53))
                val respBuf = ByteArray(2048)
                val respPkt = DatagramPacket(respBuf, respBuf.size)
                socket.receive(respPkt)
                return respBuf.copyOf(respPkt.length)
            } catch (_: Exception) {
                // Retry next upstream DNS
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun stopVpn() {
        _isRunning.value = false
        vpnScope?.cancel()
        vpnScope = null
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
