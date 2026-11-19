package dev.ririxi.bunnylock.backend

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.telephony.SubscriptionManager
import dev.ririxi.bunnylock.data.Carrier
import dev.ririxi.bunnylock.data.Carriers
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.PrivilegedClient
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.data.RootClient
import dev.ririxi.bunnylock.data.RootException
import dev.ririxi.bunnylock.data.Signal
import dev.ririxi.bunnylock.modem.BackendFeatures
import dev.ririxi.bunnylock.modem.BunnyDiagnostics
import dev.ririxi.bunnylock.modem.ModemException
import dev.ririxi.bunnylock.modem.ModemState
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.asClause
import dev.ririxi.bunnylock.modem.optText
import dev.ririxi.bunnylock.modem.reasonOf
import dev.ririxi.bunnylock.modem.runSuspendCatching
import dev.ririxi.bunnylock.modem.string
import dev.ririxi.bunnylock.root.Failure
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val MAX_LOG_LINES = 80

internal class ModemSession(
    context: Context,
    private val featuresOf: (ModemState) -> BackendFeatures,
) {
    val context: Context = context.applicationContext
    val englishContext: Context =
        this.context.createConfigurationContext(Configuration(this.context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
    val prefs: SharedPreferences = this.context.getSharedPreferences("bunnylock", Context.MODE_PRIVATE)
    var helper: PrivilegedClient = RootClient(this.context)

    private val _state =
        MutableStateFlow(
            ModemState(
                persist = prefs.getBoolean("persist", true),
                atNode = prefs.getString("atNode", "") ?: "",
                sim = prefs.getInt("sim", 1),
            ).let { it.copy(features = featuresOf(it)) },
        )
    val state: StateFlow<ModemState> = _state.asStateFlow()
    val current: ModemState get() = _state.value

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    var backendCells: List<Cell> = emptyList()
    var backendCarriers: List<Carrier> = emptyList()

    var physicalChannelReport: JSONArray? = null
        private set

    fun update(transform: ModemState.() -> ModemState) =
        _state.update {
            val next = it.transform()
            next.copy(features = featuresOf(next))
        }

    private fun knownSubId(sim: Int): Int? = current.sims.firstOrNull { it.slot == sim - 1 }?.subId

    fun subId(sim: Int = current.sim): Int = knownSubId(sim) ?: SubscriptionManager.getDefaultSubscriptionId()

    // Every request names its SIM. Without a subscription the app knows for it, the helper looks one up by the SIM's slot.
    suspend fun call(
        command: String,
        timeout: Duration = 20.seconds,
        sim: Int = current.sim,
        build: JSONObject.() -> Unit = {},
    ): JSONObject =
        helper.call(command, timeout) {
            put("sim", sim)
            knownSubId(sim)?.let { put("subId", it) }
            build()
        }

    suspend fun aospState(sim: Int = current.sim): JSONObject? = runSuspendCatching { call("aosp.state", sim = sim) }.getOrNull()

    fun setAtNode(node: String) {
        prefs.edit().putString("atNode", node).apply()
        update { copy(atNode = node) }
    }

    fun log(message: String) {
        BunnyDiagnostics.record("modem", false, message)
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        update { copy(log = (listOf("$time  $message") + log).take(MAX_LOG_LINES)) }
    }

    fun inEnglish(text: Text): String = englishContext.string(text)

    fun englishReason(e: Throwable): String = inEnglish(reasonOf(e))

    fun englishMessage(e: Throwable): String? {
        val text =
            when (e) {
                is Failure -> e.text
                is RootException -> e.text
                is ModemException -> e.text
                else -> null
            }
        return text?.let(::inEnglish) ?: e.message
    }

    fun logAndLocalize(text: Text): String {
        log(inEnglish(text))
        return context.string(text)
    }

    suspend fun report(message: Text) {
        _events.emit(logAndLocalize(message))
    }

    suspend fun restartRadio() = call("radio.cycle", 30.seconds)

    suspend fun restartSimRadio() {
        val result =
            try {
                call("radio.cycleSim", 60.seconds)
            } catch (e: RootException) {
                log("Couldn't restart only SIM ${current.sim}'s radio (${englishReason(e).asClause()}), so airplane mode was toggled instead.")
                restartRadio()
                return
            }
        if (result.optBoolean("airplaneMode")) log("SIM ${current.sim}'s radio didn't restart on its own, so airplane mode was toggled instead.")
    }

    suspend fun rebootModemThroughAndroid(): Text? {
        val result = call("aosp.rebootModem", 30.seconds)
        if (result.optString("method") != "radio") return null
        val rebootError = result.optText("rebootError")?.let(::inEnglish).orEmpty()
        log("Modem reboot fallback: $rebootError; api=${result.optString("api")}")
        return Text(R.string.modem_note_reboot_radio)
    }

    suspend fun connectionCarriers(
        sim: Int,
        cells: List<Cell>,
        signals: List<Signal>,
    ): List<Carrier> {
        physicalChannelReport = runSuspendCatching { call("aosp.carriers", sim = sim).optJSONArray("carriers") }.getOrNull()
        val reported = Carriers.fromReport(physicalChannelReport)
        val carriers =
            Rat.entries.flatMap { rat ->
                val android = reported.filter { it.rat == rat }
                val modem = backendCarriers.filter { it.rat == rat }
                // Some radio HALs leave out the channel and PCI on some carriers. For those networks the modem's own list is used instead.
                val identified = android.isNotEmpty() && android.all { it.arfcn !== null || it.pci !== null }
                if (identified || modem.isEmpty()) android else modem
            }
        return Carriers.complete(carriers, cells, signals)
    }
}
