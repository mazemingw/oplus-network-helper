package com.nvmex.networkhelper.viewmodel.signal

import android.app.Application
import android.os.SystemClock
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.model.network.CellTimelinePoint
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.model.network.upsertCellTimeline
import com.nvmex.networkhelper.util.network.NetworkPanelRepositoryMultiSim
import com.nvmex.networkhelper.util.network.TelephonySnapshotterMultiSim
import com.nvmex.networkhelper.util.network.observeDefaultDataSubId
import com.nvmex.networkhelper.xposed.translator.NrcaTranslator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import com.nvmex.networkhelper.util.qmi.*
import kotlinx.coroutines.*

data class NetworkPanelsFrame(
    val ts: Long,
    val data: Map<Int, NetworkPanelUiState>
)

class NetworkPanelMultiSimViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        // NRCA HAL may only force re-emit every ~6s when signature is unchanged.
        // Keep this above the slow poll interval (10s) to avoid transient CA blinking.
        private const val NRCA_STALE_MS = 12_000L
    }

    private val snapshotter = TelephonySnapshotterMultiSim(app.applicationContext)
    private val repo = NetworkPanelRepositoryMultiSim(app.applicationContext, snapshotter)

    private val nrCaTracker = BroadcastNrCaTracker(app)
    private val _qmiBySlot = MutableStateFlow<Map<Int, QmiRadioSnapshot>>(emptyMap())
    val qmiBySlot = _qmiBySlot.asStateFlow()
    private val _qmiRetry = MutableStateFlow(0)
    val qmiRetry = _qmiRetry.asStateFlow()
    private val _qmiLoading = MutableStateFlow<Map<Int, Boolean>>(emptyMap())
    val qmiLoading = _qmiLoading.asStateFlow()
    private val _qmiErrors = MutableStateFlow<Map<Int, String>>(emptyMap())
    val qmiErrors = _qmiErrors.asStateFlow()
    private var qmiGeneration = 0L
    private val qmiOwners = mutableMapOf<Int, Long>()
    fun retryQmiMonitoring() { _qmiRetry.value++ }
    private val _cellTimelineBySubId = MutableStateFlow<Map<Int, List<CellTimelinePoint>>>(emptyMap())

    /**
     * UI-independent recent-cell history. Keeping it in the navigation-scoped ViewModel means
     * switching bottom-bar destinations cannot erase it when HOME leaves composition.
     */
    val cellTimelineBySubId: StateFlow<Map<Int, List<CellTimelinePoint>>> =
        _cellTimelineBySubId.asStateFlow()

    fun recordCellTimeline(subId: Int, point: CellTimelinePoint) {
        val current = _cellTimelineBySubId.value
        val updated = upsertCellTimeline(current[subId].orEmpty(), point)
        if (updated == current[subId]) return
        _cellTimelineBySubId.value = current + (subId to updated)
    }

    /** Called only by a visible foreground surface; no background Root polling. */
    suspend fun monitorQmi(sim: Int) {
        val generation = ++qmiGeneration
        qmiOwners[sim - 1] = generation
        _qmiBySlot.value = _qmiBySlot.value - (sim - 1)
        _qmiLoading.value = _qmiLoading.value + (sim - 1 to true)
        _qmiErrors.value = _qmiErrors.value - (sim - 1)
        val client = QmiModemClient(getApplication())
        try {
            while (currentCoroutineContext().isActive) {
                val snapshot = client.radio(sim)
                if (qmiOwners[sim - 1] == generation) {
                    _qmiBySlot.value = _qmiBySlot.value + (snapshot.slot to snapshot)
                    _qmiLoading.value = _qmiLoading.value + (sim - 1 to false)
                }
                delay(2000)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (qmiOwners[sim - 1] == generation) {
                _qmiBySlot.value = _qmiBySlot.value - (sim - 1)
                _qmiErrors.value = _qmiErrors.value + (sim - 1 to e.message.orEmpty())
            }
        }
        finally {
            if (qmiOwners[sim - 1] == generation) {
                _qmiLoading.value = _qmiLoading.value + (sim - 1 to false)
                qmiOwners.remove(sim - 1)
            }
            withContext(NonCancellable) { client.close() }
        }
    }

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
            _qmiBySlot
        ) { states, simsList, _, bySlot, lteBySlot ->
            val subIdToSlot = simsList.associate { it.subscriptionId to it.simSlotIndex }
            val now = SystemClock.elapsedRealtime()
            val wallNow = System.currentTimeMillis()

            val patched = states.mapValues { (subId, s) ->
                listOfNotNull(s, s.nsaLteAnchor).filter { it.cellType in listOf("LTE", "NR") && it.arfcn.toIntOrNull() != null }.forEach { serving ->
                    recordCellTimeline(subId, CellTimelinePoint(serving.updatedAt, serving.cellType, serving.tac, serving.pci,
                        serving.ci, serving.band, if (serving.cellType == "NR") serving.ssRsrp else serving.rsrp,
                        if (serving.cellType == "NR") serving.ssRsrq else serving.rsrq,
                        if (serving.cellType == "NR") serving.ssSinr else serving.sinr, serving.arfcn, serving.mcc, serving.mnc))
                }
                val slot = subIdToSlot[subId]?.takeIf { it >= 0 }
                val caInfo = slot?.let { bySlot[it] }
                val radio = slot?.let { lteBySlot[it] }

                // Keep only short-lived and structurally valid NRCA for this sub panel.
                val freshCa = caInfo?.takeIf {
                    val age = wallNow - it.updatedAt
                    age in 0..NRCA_STALE_MS
                }

                val freshLteCa = qmiLteCaForState(radio, s, slot, wallNow)

                val validatedNrCa = validateNrCaForState(s, freshCa)
                val nrCaForUi = qmiChooseNrCa(validatedNrCa, qmiNrFrequencyFallback(radio, s, wallNow), slot, wallNow)

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
        if (info == null) return null
        val carriers = info.carriers
        if (carriers.isEmpty()) return null

        val hasConfiguredCarrier = carriers.any {
            it.dlStateRaw == 1 || it.dlStateRaw == 2 || it.ulStateRaw == 1 || it.ulStateRaw == 2
        }
        if (!hasConfiguredCarrier) return null

        val hasPositiveDlBw = carriers.any {
            (NrcaTranslator.bandwidthMhzOrNull(it.dlBwRaw, info.bandwidthEncoding) ?: 0) > 0
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

    override fun onCleared() {
        nrCaTracker.stop()
        super.onCleared()
    }
}
