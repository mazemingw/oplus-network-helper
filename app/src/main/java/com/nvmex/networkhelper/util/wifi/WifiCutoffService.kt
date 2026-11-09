package com.nvmex.networkhelper.util.wifi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nvmex.networkhelper.BuildConfig
import com.nvmex.networkhelper.MainActivity
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.shell.SuShellRunner
import dev.ririxi.bunnylock.data.PrivilegedClient
import dev.ririxi.bunnylock.data.RootClient
import dev.ririxi.bunnylock.shizuku.ShizukuClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class WifiCutoffService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wifi by lazy { applicationContext.getSystemService(WifiManager::class.java) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networks = MutableStateFlow(emptySet<Network>())
    private var monitor: Job? = null
    private var registered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = networks.update { it + network }
        override fun onLost(network: Network) = networks.update { it - network }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || !settings(this).getBoolean(KEY_ENABLED, false)) {
            settings(this).edit().putBoolean(KEY_ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (monitor != null) return START_STICKY
        try {
            showNotification()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.registerNetworkCallback(request, networkCallback)
            registered = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not start Wi-Fi monitoring", e)
            fail(R.string.wifi_cutoff_start_failed)
            return START_NOT_STICKY
        }
        monitor = scope.launch { monitorSignal() }
        return START_STICKY
    }

    private suspend fun monitorSignal() {
        val prefs = settings(this)
        var previousNetwork: Network? = null
        var previousBssid: String? = null
        var previousThreshold = DEFAULT_THRESHOLD
        var weakSamples = 0
        var retryAfter = 0L
        while (true) {
            val network = networks.first { it.isNotEmpty() }.singleOrNull()
            val threshold = threshold(this)
            val sample = network?.let(::currentSample)
            val bssid = sample?.bssid
            if (network != previousNetwork || bssid != previousBssid || threshold != previousThreshold) weakSamples = 0
            previousNetwork = network
            previousBssid = bssid
            previousThreshold = threshold

            val rssi = sample?.rssi
            weakSamples = if (rssi != null && rssi <= threshold) weakSamples + 1 else 0
            if (BuildConfig.DEBUG) Log.d(TAG, "RSSI=$rssi threshold=$threshold weakSamples=$weakSamples")
            if (network != null && weakSamples >= 2 && SystemClock.elapsedRealtime() >= retryAfter) {
                val stillWeak = {
                    networks.value.singleOrNull() == network && prefs.getBoolean(KEY_ENABLED, false) &&
                        threshold(this) == threshold && currentSample(network)?.let { it.bssid == bssid && it.rssi <= threshold } == true
                }
                if (disconnectAp(stillWeak)) {
                    retryAfter = SystemClock.elapsedRealtime() + RECONNECT_COOLDOWN_SECONDS * 1000L
                    val lost = withTimeoutOrNull(5.seconds) { networks.first { network !in it } }
                    if (lost != null) Log.i(TAG, "AP disconnected at $rssi dBm (threshold $threshold dBm)")
                    else Log.w(TAG, "Android accepted the AP disconnect, but the network loss was not observed")
                } else if (stillWeak()) {
                    fail(R.string.wifi_cutoff_disconnect_failed)
                    return
                }
                weakSamples = 0
            }
            delay(CHECK_SECONDS * 1000L)
        }
    }

    private data class ApSample(val rssi: Int, val bssid: String?)

    @Suppress("DEPRECATION") // The legacy API supplies primary-station data on older Android releases.
    private fun currentSample(network: Network): ApSample? = runCatching {
        val info = if (Build.VERSION.SDK_INT >= 31) {
            connectivity.getNetworkCapabilities(network)?.transportInfo as? WifiInfo
        } else null
        val primary = wifi.connectionInfo
        val rssi = info?.rssi?.takeIf { it in -126..-1 } ?: primary?.rssi?.takeIf { it in -126..-1 }
        val bssid = primary?.bssid?.takeUnless { it == "02:00:00:00:00:00" }
        rssi?.let { ApSample(it, bssid) }
    }.getOrNull()

    @Suppress("DEPRECATION") // Device/profile owners and system apps can still disassociate via the SDK.
    private suspend fun disconnectAp(stillWeak: () -> Boolean): Boolean {
        if (!stillWeak()) return false
        if (runCatching { wifi.disconnect() }.getOrDefault(false)) return true
        val helpers = buildList<PrivilegedClient> {
            if (ShizukuClient.granted()) add(ShizukuClient(applicationContext) {})
            add(RootClient(applicationContext))
        }
        for (helper in helpers) {
            if (!stillWeak()) return false
            try {
                val accepted = withTimeoutOrNull(8.seconds) {
                    helper.start()
                    stillWeak() && helper.call("host.disconnectWifi", 3.seconds).optBoolean("accepted")
                }
                if (accepted == true) return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Privileged AP disconnect failed", e)
            } finally {
                withContext(Dispatchers.IO + NonCancellable) { helper.stop() }
            }
        }
        return false
    }

    private fun showNotification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.wifi_cutoff_title), NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 0, Intent(this, WifiCutoffService::class.java).setAction(ACTION_STOP), flags)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(getString(R.string.wifi_cutoff_title))
            .setContentText(getString(R.string.wifi_cutoff_monitoring))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.wifi_cutoff_stop), stop)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun fail(message: Int) {
        error.value = message
        settings(this).edit().putBoolean(KEY_ENABLED, false).apply()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        if (registered) connectivity.unregisterNetworkCallback(networkCallback)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WifiCutoff"
        private const val CHANNEL = "wifi_cutoff"
        private const val NOTIFICATION_ID = 2003
        private const val ACTION_STOP = "wifi_cutoff.stop"
        const val KEY_ENABLED = "enabled"
        const val KEY_THRESHOLD = "threshold_dbm"
        const val DEFAULT_THRESHOLD = -80
        const val MIN_THRESHOLD = -100
        const val MAX_THRESHOLD = -20
        const val CHECK_SECONDS = 7
        const val RECONNECT_COOLDOWN_SECONDS = 60
        private val error = MutableStateFlow<Int?>(null)
        val errorMessage = error.asStateFlow()

        fun settings(context: Context) = context.getSharedPreferences(CHANNEL, Context.MODE_PRIVATE)

        fun threshold(context: Context) = settings(context).getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
            .coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

        suspend fun canDisconnectWifi(context: Context): Boolean {
            val policy = context.getSystemService(DevicePolicyManager::class.java)
            val pkg = context.packageName
            return policy.isDeviceOwnerApp(pkg) || policy.isProfileOwnerApp(pkg) ||
                context.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 || ShizukuClient.granted() ||
                withContext(Dispatchers.IO) { SuShellRunner().hasSu(timeoutMs = 8000) }
        }

        fun startIfEnabled(context: Context) {
            val prefs = settings(context)
            if (!prefs.getBoolean(KEY_ENABLED, false)) return
            error.value = null
            try {
                ContextCompat.startForegroundService(context, Intent(context, WifiCutoffService::class.java))
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not launch Wi-Fi monitoring", e)
                error.value = R.string.wifi_cutoff_start_failed
                prefs.edit().putBoolean(KEY_ENABLED, false).apply()
            }
        }
    }
}
