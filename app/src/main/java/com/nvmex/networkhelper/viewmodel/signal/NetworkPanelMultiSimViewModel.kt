package com.nvmex.networkhelper.viewmodel.signal

import android.app.Application
import android.os.SystemClock
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.network.NetworkPanelRepositoryMultiSim
import com.nvmex.networkhelper.util.network.TelephonySnapshotterMultiSim
import com.nvmex.networkhelper.util.network.observeDefaultDataSubId
import com.nvmex.networkhelper.xposed.translator.NrcaTranslator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class NetworkPanelsFrame(
    val ts: Long,
    val data: Map<Int, NetworkPanelUiState>
)

class NetworkPanelMultiSimViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        // NRCA HAL may only force re-emit every ~6s when signature is unchanged.
        // Keep this above the slow poll interval (10s) to avoid transient CA blinking.
        private const val NRCA_STALE_MS = 12_000L
        private const val LTECA_STALE_MS = 12_000L
        private const val NRCA_HOLD_MS = 3_000L
    }

    private val snapshotter = TelephonySnapshotterMultiSim(app.applicationContext)
    private val repo = NetworkPanelRepositoryMultiSim(app.applicationContext, snapshotter)

    private val nrCaTracker = BroadcastNrCaTracker(app)
    private val lteCaTracker = BroadcastLteCaTracker(app)
    private val nrCaHoldBySubId = mutableMapOf<Int, HeldNrCa>()

    val sims: StateFlow<List<SubscriptionInfo>> = repo.observeSims()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val dataSubId: StateFlow<Int> =
        observeDefaultDataSubId(app.applicationContext)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            )

    val frame: StateFlow<NetworkPanelsFrame> =
        combine(
            repo.observeStates(),
            sims,
            dataSubId,
            nrCaTracker.bySlot,
            lteCaTracker.bySlot
        ) { states, simsList, _, bySlot, lteBySlot ->
            val subIdToSlot = simsList.associate { it.subscriptionId to it.simSlotIndex }
            val now = SystemClock.elapsedRealtime()
            val wallNow = System.currentTimeMillis()

            val patched = states.mapValues { (subId, s) ->
                val slot = subIdToSlot[subId]?.takeIf { it >= 0 }
                val caInfo = slot?.let { bySlot[it] }
                val lteCaInfo = slot?.let { lteBySlot[it] }

                // Keep only short-lived and structurally valid NRCA for this sub panel.
                val freshCa = caInfo?.takeIf {
                    val age = wallNow - it.updatedAt
                    age in 0..NRCA_STALE_MS
                }

                val freshLteCa = lteCaInfo?.takeIf {
                    val age = wallNow - it.updatedAt
                    age in 0..LTECA_STALE_MS
                }

                val validatedNrCa = validateNrCaForState(s, freshCa)
                val nrCaForUi = pickNrCaWithHold(
                    subId = subId,
                    state = s,
                    candidate = validatedNrCa,
                    wallNow = wallNow
                )

                s.copy(nrCaInfo = nrCaForUi, lteCaInfo = freshLteCa)
            }

            NetworkPanelsFrame(now, patched)
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, NetworkPanelsFrame(0L, emptyMap()))

    private fun validateNrCaForState(
        state: NetworkPanelUiState,
        info: com.nvmex.networkhelper.model.network.NrCaInfo?
    ): com.nvmex.networkhelper.model.network.NrCaInfo? {
        if (state.dataNetworkType != "NR") return null
        val carriers = info?.carriers.orEmpty()
        if (carriers.isEmpty()) return null

        val hasConfiguredCarrier = carriers.any {
            it.dlStateRaw == 1 || it.dlStateRaw == 2 || it.ulStateRaw == 1 || it.ulStateRaw == 2
        }
        if (!hasConfiguredCarrier) return null

        val hasPositiveDlBw = carriers.any {
            (NrcaTranslator.bandwidthMhzOrNull(it.dlBwRaw) ?: 0) > 0
        }
        val hasAnyActiveCarrier = carriers.any { it.dlStateRaw == 2 || it.ulStateRaw == 2 }
        if (!hasPositiveDlBw && !hasAnyActiveCarrier) return null

        val servingBand = state.band.toIntOrNull()
        if (servingBand != null && servingBand > 0) {
            val hasBandMatch = carriers.any { carrier ->
                val normalized = NrcaTranslator.bandShort(carrier.bandRaw).toIntOrNull()
                normalized == servingBand || carrier.bandRaw == servingBand
            }
            if (!hasBandMatch) return null
        }

        return info
    }

    private fun pickNrCaWithHold(
        subId: Int,
        state: NetworkPanelUiState,
        candidate: com.nvmex.networkhelper.model.network.NrCaInfo?,
        wallNow: Long
    ): com.nvmex.networkhelper.model.network.NrCaInfo? {
        // Switch away from NR: clear immediately, do not hold stale NRCA.
        if (state.dataNetworkType != "NR") {
            nrCaHoldBySubId.remove(subId)
            return null
        }

        // Fresh valid data should always win immediately.
        if (candidate != null) {
            nrCaHoldBySubId[subId] = HeldNrCa(
                info = candidate,
                holdUntilMs = wallNow + NRCA_HOLD_MS
            )
            return candidate
        }

        val held = nrCaHoldBySubId[subId] ?: return null
        if (wallNow <= held.holdUntilMs) return held.info

        nrCaHoldBySubId.remove(subId)
        return null
    }

    override fun onCleared() {
        nrCaHoldBySubId.clear()
        nrCaTracker.stop()
        lteCaTracker.stop()
        super.onCleared()
    }
}

private data class HeldNrCa(
    val info: com.nvmex.networkhelper.model.network.NrCaInfo,
    val holdUntilMs: Long
)



