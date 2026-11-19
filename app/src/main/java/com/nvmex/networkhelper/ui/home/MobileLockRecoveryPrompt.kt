package com.nvmex.networkhelper.ui.home

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telephony.AccessNetworkConstants
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.modem.MobileLockStatus
import com.nvmex.networkhelper.util.modem.MobileLockTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val OUTAGE_CONFIRM_MS = 8_000L
private const val RECONNECT_WAIT_MS = 20_000L

private enum class RecoveryStep { OFFER, UNLOCKING, FAILED, RECONNECTING, AIRPLANE_HINT }
private data class RecoveryPrompt(val target: MobileLockTarget, val step: RecoveryStep, val checkAfter: Long = 0)

@Composable
internal fun MobileLockRecoveryPrompt(
    enabled: Boolean,
    busy: Boolean,
    unlock: (MobileLockTarget, (Boolean) -> Unit) -> Boolean
) {
    val context = LocalContext.current
    val app = context.applicationContext
    val status = remember(app) { MobileLockStatus(app) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumed = lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    var prompt by remember { mutableStateOf<RecoveryPrompt?>(null) }
    var dismissed by remember { mutableStateOf<MobileLockTarget?>(null) }

    LaunchedEffect(enabled, lifecycle, status) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var offlineSince: Long? = null
            var observedTarget: MobileLockTarget? = null
            while (isActive) {
                val activePrompt = prompt
                val (target, locked, offline) = withContext(Dispatchers.IO) {
                    val dataTarget = defaultDataTarget(app)
                    val hasLocks = dataTarget != null && status.read(dataTarget).isNotEmpty()
                    Triple(dataTarget, hasLocks, dataTarget?.takeIf { hasLocks || it == activePrompt?.target }
                        ?.let { mobileNetworkOffline(app, it) })
                }
                if (observedTarget != target) {
                    observedTarget = target
                    offlineSince = null
                }
                val now = SystemClock.elapsedRealtime()
                offlineSince = if (offline == true) offlineSince ?: now else null
                // An unlock can itself interrupt registration briefly. Let it finish and wait for the radio to settle.
                if (prompt == activePrompt) {
                    when {
                        activePrompt?.step == RecoveryStep.UNLOCKING -> Unit
                        activePrompt != null && activePrompt.target != target -> prompt = null
                        activePrompt?.step == RecoveryStep.RECONNECTING -> if (now >= activePrompt.checkAfter) {
                            prompt = if (offline == true) activePrompt.copy(step = RecoveryStep.AIRPLANE_HINT) else null
                        }
                        activePrompt?.step == RecoveryStep.OFFER && !locked -> prompt = null
                        offline != true -> {
                            prompt = null
                            dismissed = null
                        }
                        activePrompt == null && locked && target != null && target != dismissed &&
                            offlineSince?.let { now - it >= OUTAGE_CONFIRM_MS } == true -> {
                            prompt = RecoveryPrompt(target, RecoveryStep.OFFER)
                        }
                    }
                }
                delay(2_000)
            }
        }
    }

    val current = prompt ?: return
    if (!enabled || !resumed || current.step == RecoveryStep.RECONNECTING) return
    val running = current.step == RecoveryStep.UNLOCKING
    val airplane = current.step == RecoveryStep.AIRPLANE_HINT
    val failed = current.step == RecoveryStep.FAILED
    val dismiss = {
        if (!running) {
            dismissed = current.target
            prompt = null
        }
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(when (current.step) {
            RecoveryStep.UNLOCKING -> R.string.mobile_lock_unlocking_title
            RecoveryStep.FAILED -> R.string.mobile_lock_failed_title
            RecoveryStep.AIRPLANE_HINT -> R.string.mobile_lock_airplane_title
            else -> R.string.mobile_lock_outage_title
        })) },
        text = { Text(stringResource(when (current.step) {
            RecoveryStep.UNLOCKING -> R.string.mobile_lock_unlocking_text
            RecoveryStep.FAILED -> R.string.mobile_lock_failed_text
            RecoveryStep.AIRPLANE_HINT -> R.string.mobile_lock_airplane_text
            else -> R.string.mobile_lock_outage_text
        }, current.target.sim)) },
        confirmButton = {
            when {
                airplane -> TextButton(onClick = dismiss) { Text(stringResource(R.string.mobile_lock_got_it)) }
                !running -> TextButton(enabled = !busy, onClick = {
                    prompt = current.copy(step = RecoveryStep.UNLOCKING)
                    if (!unlock(current.target) { success ->
                            if (success) {
                                prompt = current.copy(step = RecoveryStep.RECONNECTING,
                                    checkAfter = SystemClock.elapsedRealtime() + RECONNECT_WAIT_MS)
                                Toast.makeText(context, R.string.mobile_lock_reconnecting, Toast.LENGTH_SHORT).show()
                            } else prompt = current.copy(step = RecoveryStep.FAILED)
                        }) prompt = current.copy(step = RecoveryStep.FAILED)
                }) {
                    Text(stringResource(if (failed) R.string.mobile_lock_retry else R.string.mobile_lock_unlock_both))
                }
            }
        },
        dismissButton = if (running || airplane) null else {
            { TextButton(onClick = dismiss) { Text(stringResource(R.string.mobile_lock_later)) } }
        }
    )
}

private fun defaultDataTarget(context: Context): MobileLockTarget? {
    val subId = SubscriptionManager.getDefaultDataSubscriptionId()
    if (!SubscriptionManager.isValidSubscriptionId(subId)) return null
    return MobileLockTarget.forSim(context, SubscriptionManager.getSlotIndex(subId) + 1)?.takeIf { it.subId == subId }
}

/** Null means the radio status is unavailable or the user intentionally turned it off. */
@Suppress("DEPRECATION")
private fun mobileNetworkOffline(context: Context, target: MobileLockTarget): Boolean? = runCatching {
    if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0) return null
    val telephony = context.getSystemService(TelephonyManager::class.java)?.createForSubscriptionId(target.subId) ?: return null
    val service = telephony.serviceState ?: return null
    if (service.state == ServiceState.STATE_POWER_OFF) return null
    val packetRegistrations = if (Build.VERSION.SDK_INT >= 30) service.networkRegistrationInfoList.filter {
        it.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WWAN && it.domain and NetworkRegistrationInfo.DOMAIN_PS != 0
    }.map { it.isRegistered } else emptyList()
    val noVoiceService = service.state == ServiceState.STATE_OUT_OF_SERVICE || service.state == ServiceState.STATE_EMERGENCY_ONLY
    if (noVoiceService && true !in packetRegistrations && telephony.dataState != TelephonyManager.DATA_CONNECTED) return true
    if (!telephony.isDataEnabled) return false
    if (packetRegistrations.isNotEmpty() && true !in packetRegistrations) return true
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
    if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) return false
    telephony.dataState == TelephonyManager.DATA_DISCONNECTED ||
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
}.getOrNull()
