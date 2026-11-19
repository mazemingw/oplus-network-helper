package dev.ririxi.bunnylock.backend

import dev.ririxi.bunnylock.core.Bands
import dev.ririxi.bunnylock.core.Ran
import dev.ririxi.bunnylock.core.intList
import dev.ririxi.bunnylock.core.objects
import dev.ririxi.bunnylock.data.Carriers
import dev.ririxi.bunnylock.data.Cell
import dev.ririxi.bunnylock.data.Rat
import dev.ririxi.bunnylock.data.RootException
import dev.ririxi.bunnylock.modem.Chipset
import dev.ririxi.bunnylock.modem.DeviceInfo
import dev.ririxi.bunnylock.modem.Group
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.modem.Text
import dev.ririxi.bunnylock.modem.asClause
import dev.ririxi.bunnylock.modem.followedBy
import dev.ririxi.bunnylock.modem.orList
import dev.ririxi.bunnylock.modem.reasonOf
import dev.ririxi.bunnylock.modem.runSuspendCatching
import dev.ririxi.bunnylock.root.Aosp
import dev.ririxi.bunnylock.root.Failure
import dev.ririxi.bunnylock.root.fail
import org.json.JSONArray
import org.json.JSONObject

private const val MAX_HAL_CHANNELS = 32

// The highest LTE band Android 11 knows.
private const val NEWEST_OLD_HAL_LTE_BAND = 88

// LTE bands even old Qualcomm modems know.
private val WIDELY_DEFINED_LTE_BANDS =
    buildList {
        addAll(1..14)
        addAll(17..21)
        addAll(23..43)
        addAll(listOf(46, 47, 48, 66, 71))
    }
private val WIDELY_DEFINED_NR_BANDS = listOf(1, 2, 3, 5, 7, 8, 12, 20, 25, 28, 38, 40, 41, 48, 66, 71, 77, 78, 79)
private val EUROPE_MCCS = 200..299
private val NORTH_AMERICA_MCCS = 302..316
private val COMMON_NR_BANDS_EUROPE = listOf(1, 3, 7, 8, 20, 28, 38, 78)
private val COMMON_NR_BANDS_NORTH_AMERICA = listOf(2, 5, 25, 41, 66, 71, 77, 78)
private val COMMON_NR_BANDS_ELSEWHERE = listOf(1, 3, 5, 8, 28, 40, 41, 78)

// GSM 850, 900 (P, E and R), 1800 and 1900, as AccessNetworkConstants.GeranBand numbers.
private val GERAN_BANDS = mapOf(850 to listOf(8), 900 to listOf(9, 10, 11), 1800 to listOf(12), 1900 to listOf(13))
private val GERAN_RESTORE_BANDS = GERAN_BANDS.values.flatten()

private val UTRAN_RESTORE_BANDS = listOf(1, 2, 3, 4, 5, 8, 9, 19)

private class LockUnanswered : Failure(Text(R.string.modem_lock_unanswered))

// The radio service's own refusals come with its details. A timeout or a stopped helper says nothing about the bands.
private val RootException.isRefusal get() = text?.id == R.string.aosp_with_details

// What bunnylock last set is what counts as a lock, so every lock it holds is saved per SIM and sent whole.
internal class HalLocks(
    private val session: ModemSession,
    private val refresh: suspend () -> Unit,
) {
    private val current get() = session.current
    private val prefs get() = session.prefs
    private val quirks = HalQuirks(session)

    // A MediaTek modem takes a HAL lock right away but may only move to it once the radio has restarted.
    private val movesOnlyAfterRestart get() = current.device?.chipset == Chipset.MEDIATEK
    private val settling = HalSettling(session) { movesOnlyAfterRestart }

    // Qualcomm's radio HAL may switch off the networks a lock leaves out, and on old phones an unlock brings the saved lock back.
    private val qualcommHal get() = current.device?.chipset == Chipset.QUALCOMM

    // A band lock is left out of the request while a cell lock on its network stands in for it, so clearing the cell lock brings it back.
    private suspend fun setChannels(specs: JSONArray) {
        sendLock(requestOf(specs.objects()))
        prefs.edit().putString(halSpecsKey(current.sim), specs.toString()).apply()
    }

    // Some radios refuse the remaining locks when they're sent alone, so every band of the freed network goes along with them.
    private suspend fun setChannelsFreeing(
        specs: JSONArray,
        freed: Int,
    ) {
        sendWithEveryBand(requestOf(specs.objects()), setOf(freed))
        prefs.edit().putString(halSpecsKey(current.sim), specs.toString()).apply()
    }

    private fun requestOf(held: List<JSONObject>): List<JSONObject> {
        val cellLockRans = held.filter { it.isCellLock }.map { it.optInt("ran") }
        return held.filter { it.isCellLock || it.optInt("ran") !in cellLockRans }
    }

    // Some phones switch off every network a request leaves out. Once one does, its requests name all of them, unlocks too.
    private suspend fun sendLock(request: List<JSONObject>) {
        if (!qualcommHal) return sendChannels(JSONArray(request))
        val leftOut = leftOutNetworks(request)
        if (quirks.clearsLeftOutNetworks) {
            sendWithEveryBand(request, leftOut)
            return
        }
        if (request.isEmpty()) return sendChannels(JSONArray())
        val before = reportedChannels()
        sendChannels(JSONArray(request))
        val after = reportedChannels() ?: return
        val switchedOff = leftOut.filter { bandsOn(before.orEmpty(), it).isNotEmpty() && bandsOn(after, it).isEmpty() }
        if (switchedOff.isEmpty()) return
        quirks.clearsLeftOutNetworks = true
        session.log("SIM ${current.sim} switched off the networks the lock left out, so bunnylock names every network in its locks from now on.")
        sendWithEveryBand(request, leftOut)
    }

    private fun leftOutNetworks(request: List<JSONObject>): Set<Int> {
        val hasNr = current.groups.any { it.group == Group.NR }
        val networks = setOfNotNull(Ran.GERAN, Ran.UTRAN, Ran.EUTRAN, Ran.NGRAN.takeIf { hasNr })
        return networks - request.map { it.optInt("ran") }.toSet()
    }

    private suspend fun sendChannels(
        specs: JSONArray,
        splitRefused: Boolean = true,
        userSpecCount: Int = specs.length(),
    ) {
        val result =
            session.call("aosp.setChannels") {
                put("specs", specs)
                put("split", splitRefused)
                put("userSpecCount", userSpecCount)
            }
        result.optString("diagnostic").takeIf { it.isNotBlank() }?.let { session.log("HAL channel lock: $it") }
        if (!result.optBoolean("answered", true)) throw LockUnanswered()
    }

    // Old Qualcomm phones that can't report their bands, where an unlock only brings back the saved lock.
    private suspend fun keepsSavedBands(): Boolean = qualcommHal && reportedChannels() === null

    private suspend fun reportedChannels(): List<JSONObject>? = session.aospState()?.optJSONArray("channels")?.objects()

    private suspend fun replaceSavedBands(freedRans: Collection<Int>) {
        val legacy =
            listOfNotNull(
                channelSpec(Ran.GERAN, GERAN_RESTORE_BANDS, emptyList()).takeIf { Ran.GERAN in freedRans },
                channelSpec(Ran.UTRAN, UTRAN_RESTORE_BANDS, emptyList()).takeIf { Ran.UTRAN in freedRans },
            )
        if (Ran.EUTRAN !in freedRans && legacy.isEmpty()) return
        if (!prefs.contains(halSpecsKey(current.sim)) || !keepsSavedBands()) return
        if (Ran.EUTRAN in freedRans) replaceLteBands()
        if (legacy.isEmpty()) return
        try {
            sendChannels(JSONArray(legacy))
        } catch (e: RootException) {
            session.log("Sending every 2G and 3G band didn't work (${session.englishReason(e).asClause()}).")
        }
    }

    private suspend fun replaceLteBands() {
        val lte = supportedBands(Group.LTE) ?: return
        // One unknown band makes the modem reject the whole list, so shorter lists are tried next.
        val bandLists = listOf(lte, lte.filter { it <= NEWEST_OLD_HAL_LTE_BAND }, lte.filter { it in WIDELY_DEFINED_LTE_BANDS })
        for (bands in bandLists.distinct()) {
            val specs = bands.chunked(Aosp.MAX_RIL_BANDS).map { channelSpec(Ran.EUTRAN, it, emptyList()) }
            try {
                sendChannels(JSONArray(specs))
                return
            } catch (e: RootException) {
                session.log("Sending ${bands.size} LTE bands didn't work (${session.englishReason(e).asClause()}).")
            }
        }
    }

    private fun supportedBands(group: Group): List<Int>? = current.groups.firstOrNull { it.group == group }?.supported

    private suspend fun sendWithEveryBand(
        named: List<JSONObject>,
        fill: Set<Int>,
    ): List<Int> {
        val lte = if (Ran.EUTRAN in fill) supportedBands(Group.LTE).orEmpty() else emptyList()
        val nr = if (Ran.NGRAN in fill) supportedBands(Group.NR).orEmpty() else emptyList()
        val bandLists =
            listOf(
                lte to nr,
                lte.filter { it <= NEWEST_OLD_HAL_LTE_BAND } to nr.filter { it in WIDELY_DEFINED_NR_BANDS },
                lte.filter { it in WIDELY_DEFINED_LTE_BANDS } to nr.filter { it in WIDELY_DEFINED_NR_BANDS },
                lte.filter { it in WIDELY_DEFINED_LTE_BANDS } to nr.filter { it in commonNrBands() },
            )
        // Newer phones take long entries, older ones 8 entries of 8 bands. One unknown band fails the whole request.
        val attempts =
            listOf(Int.MAX_VALUE, Aosp.MAX_RIL_BANDS).flatMap { bandsPerEntry ->
                bandLists.map { (lteBands, nrBands) -> Triple(bandsPerEntry, lteBands, nrBands) }
            }
        val firstAttempt = quirks.firstFillAttempt(fill).coerceIn(0, attempts.lastIndex)
        val sent = mutableSetOf<String>()
        var refusal: RootException? = null
        var onlyRefusals = true
        for (index in firstAttempt..attempts.lastIndex) {
            val (bandsPerEntry, lteBands, nrBands) = attempts[index]
            val lteEntries = lteBands.chunked(bandsPerEntry)
            val userSpecs = named.flatMap { splitLteEntry(it, bandsPerEntry) }
            val specs = userSpecs.toMutableList()
            specs += lteEntries.map { channelSpec(Ran.EUTRAN, it, emptyList()) }
            if (nrBands.isNotEmpty()) specs += channelSpec(Ran.NGRAN, nrBands, emptyList())
            if (Ran.GERAN in fill) specs += channelSpec(Ran.GERAN, GERAN_RESTORE_BANDS, emptyList())
            if (Ran.UTRAN in fill) specs += channelSpec(Ran.UTRAN, UTRAN_RESTORE_BANDS, emptyList())
            val request = JSONArray(specs)
            if (!sent.add(request.toString())) continue
            try {
                // Splitting would make several 5G entries, and only the last one counts.
                sendChannels(request, splitRefused = false, userSpecCount = userSpecs.size)
            } catch (e: RootException) {
                refusal = e
                if (!e.isRefusal) onlyRefusals = false
                session.log(
                    "A request with ${specs.size} entries, ${lteBands.size} added LTE and ${nrBands.size} added 5G bands didn't work (${session.englishReason(e).asClause()}).",
                )
                continue
            }
            // The last list leaves out 5G bands the phone may well have, so later requests never start there.
            val startNextTime = if (index % bandLists.size == bandLists.lastIndex) index - 1 else index
            if (onlyRefusals && startNextTime > firstAttempt) {
                quirks.rememberFirstFillAttempt(fill, startNextTime)
                session.log("SIM ${current.sim} refused the requests before this one, so bunnylock skips them from now on.")
            }
            if (named.isEmpty() && lteEntries.size > 1 && keptOnlyLastEntry(lteEntries)) {
                // Such a phone can't have every LTE band named, so a full unlock falls back to the plain one.
                session.log("SIM ${current.sim} kept only the last of ${lteEntries.size} LTE entries, so bunnylock sent a plain unlock instead.")
                sendChannels(JSONArray())
                return emptyList()
            }
            return nrBands
        }
        throw checkNotNull(refusal)
    }

    private suspend fun keptOnlyLastEntry(lteEntries: List<List<Int>>): Boolean {
        val lte = bandsOn(reportedChannels() ?: return false, Ran.EUTRAN)
        return lte.isNotEmpty() && lteEntries.last().containsAll(lte)
    }

    private fun splitLteEntry(
        spec: JSONObject,
        bandsPerEntry: Int,
    ): List<JSONObject> {
        val bands = spec.intList("bands").orEmpty()
        if (spec.optInt("ran") != Ran.EUTRAN || spec.isCellLock || bands.isEmpty()) return listOf(spec)
        return bands.chunked(bandsPerEntry).map { channelSpec(Ran.EUTRAN, it, emptyList()).put("bandLock", true) }
    }

    private fun commonNrBands(): List<Int> =
        when (current.homePlmn?.take(3)?.toIntOrNull()) {
            in EUROPE_MCCS -> COMMON_NR_BANDS_EUROPE
            in NORTH_AMERICA_MCCS -> COMMON_NR_BANDS_NORTH_AMERICA
            else -> COMMON_NR_BANDS_ELSEWHERE
        }

    private suspend fun finishHalUnlock(freed: List<JSONObject>): Text? {
        if (movesOnlyAfterRestart) {
            return try {
                session.restartSimRadio()
                Text(R.string.modem_note_radio_restarted)
            } catch (e: RootException) {
                Text(R.string.modem_note_radio_restart_failed, reasonOf(e))
            }
        }
        if (!qualcommHal || quirks.clearsLeftOutNetworks) return null
        val freedRans = freed.map { it.optInt("ran") }.toSet()
        val state = session.aospState()
        val reported = state?.optJSONArray("channels")?.objects()
        if (reported === null) {
            if (Ran.NGRAN !in freedRans) return null
            return Text(R.string.modem_note_old_nr_bands, Aosp.MAX_RIL_BANDS)
        }
        val remaining = requestOf(savedChannelSpecs())
        val lockedRans = remaining.map { it.optInt("ran") }.toSet()
        // A locked 2G or 3G network still reports its bands, so only the unlocked ones show whether the modem switched them off.
        val unlockedLegacy = listOf(Ran.GERAN, Ran.UTRAN) - lockedRans
        val lostLegacy = unlockedLegacy.isNotEmpty() && unlockedLegacy.all { bandsOn(reported, it).isEmpty() }
        val lostLte = bandsOn(reported, Ran.EUTRAN).isEmpty()
        val lostNr = has5g(state) && bandsOn(reported, Ran.NGRAN).isEmpty()
        val keptRans = freedRans.filter { keptOldBands(freed, reported, it) }.toSet()
        if (!lostLegacy && !lostLte && !lostNr && keptRans.isEmpty()) return null

        session.log("SIM ${current.sim} still had a lock or switched-off networks after the unlock, so every band was named again.")
        if (lostLegacy) quirks.clearsLeftOutNetworks = true
        val fill =
            if (quirks.clearsLeftOutNetworks) {
                leftOutNetworks(remaining)
            } else {
                buildSet {
                    add(Ran.EUTRAN)
                    if (lostNr) add(Ran.NGRAN)
                    addAll(keptRans)
                    removeAll(lockedRans)
                }
            }
        val nrBands =
            try {
                sendWithEveryBand(remaining, fill)
            } catch (e: RootException) {
                return Text(R.string.modem_note_kept_lock_refused)
            }
        // On newer phones a plain unlock brings back every band.
        if (!quirks.clearsLeftOutNetworks && remaining.isEmpty()) {
            try {
                sendChannels(JSONArray())
            } catch (e: RootException) {
                session.log("The unlock after naming every band didn't go through (${session.englishReason(e).asClause()}).")
            }
        }
        val notes = mutableListOf(Text(R.string.modem_note_kept_lock_renamed))
        if (nrBands.size in 1..Aosp.MAX_RIL_BANDS) notes += Text(R.string.modem_note_nr_bands_limit, Aosp.MAX_RIL_BANDS, nrBands.joinToString { "n$it" })
        if (lostLegacy) notes += Text(R.string.modem_note_legacy_back)
        return notes.reduce(Text::followedBy)
    }

    private fun keptOldBands(
        freed: List<JSONObject>,
        reported: List<JSONObject>,
        ran: Int,
    ): Boolean {
        val locked = bandsOn(freed, ran)
        val now = bandsOn(reported, ran)
        return locked.isNotEmpty() && now.isNotEmpty() && locked.containsAll(now)
    }

    private fun bandsOn(
        specs: List<JSONObject>,
        ran: Int,
    ): List<Int> = specs.filter { it.optInt("ran") == ran }.flatMap { it.intList("bands").orEmpty() }

    private fun channelSpec(
        ran: Int,
        bands: List<Int>,
        channels: List<Long>,
    ): JSONObject = JSONObject().put("ran", ran).put("bands", JSONArray(bands)).put("channels", JSONArray(channels))

    // A band lock pins channels too on phones that need them, so bandSpecs marks its entries to tell them from a cell lock.
    private val JSONObject.isCellLock get() = !optBoolean("bandLock") && !intList("channels").isNullOrEmpty()

    private fun ranOf(group: Group) =
        when (group) {
            Group.GSM -> Ran.GERAN
            Group.WCDMA -> Ran.UTRAN
            Group.LTE -> Ran.EUTRAN
            Group.NR, Group.NR_SA, Group.NR_NSA -> Ran.NGRAN
        }

    private val Group.isLegacy get() = this == Group.GSM || this == Group.WCDMA

    // bunnylock names GSM bands by MHz, the radio HAL by GeranBand number.
    private fun halBandsOf(
        group: Group,
        bands: List<Int>,
    ): List<Int> = if (group == Group.GSM) bands.flatMap { GERAN_BANDS[it].orEmpty() } else bands

    fun gsmBandsOf(geranBands: List<Int>): List<Int> = GERAN_BANDS.filterValues { bands -> bands.any { it in geranBands } }.keys.toList()

    // The list the radio HAL reports can't tell a lock apart: Samsung's lists the first eight bands of each network while
    // nothing is locked. Without a request of our own, only reported entries that pin channels are taken as a lock.
    fun channelSpecs(
        aospState: JSONObject?,
        sim: Int,
    ): List<JSONObject> {
        val saved = prefs.getString(halSpecsKey(sim), null)
        if (saved !== null) return runCatching { JSONArray(saved).objects() }.getOrDefault(emptyList())
        return aospState?.optJSONArray("channels")?.objects()?.filter { !it.intList("channels").isNullOrEmpty() } ?: emptyList()
    }

    private fun savedChannelSpecs(): List<JSONObject> = channelSpecs(aospState = null, sim = current.sim)

    private suspend fun strayAfterLock(
        cells: List<Cell>,
        group: Group,
        bands: List<Int>,
    ): Int? {
        val strayCell = strayBand(cells, group, bands)
        if (strayCell !== null || ratOf(group) != Rat.NR) return strayCell
        return strayBand(cells, group, bands, session.connectionCarriers(current.sim, cells, Carriers.signals(session.context, session.subId())))
    }

    fun channelBands(
        specs: List<JSONObject>,
        ran: Int,
    ): List<Int>? = specs.lastOrNull { it.optInt("ran") == ran && !it.isCellLock }?.intList("bands")?.takeIf { it.isNotEmpty() }

    private fun lockedChannels(
        specs: List<JSONObject>,
        ran: Int,
    ): List<Int>? = specs.lastOrNull { it.optInt("ran") == ran && it.isCellLock }?.intList("channels")

    private fun channelName(ran: Int) = if (ran == Ran.EUTRAN) "EARFCN" else "ARFCN"

    fun channelLock(
        specs: List<JSONObject>,
        ran: Int,
    ): String? =
        lockedChannels(specs, ran)?.let {
            session.context.getString(R.string.modem_lock_channel_frequency_only, channelName(ran), session.context.orList(it.map(Int::toString)))
        }

    fun strayChannel(
        cells: List<Cell>,
        specs: List<JSONObject>,
    ): String? =
        listOf(Ran.EUTRAN to Rat.LTE, Ran.NGRAN to Rat.NR).firstNotNullOfOrNull { (ran, rat) ->
            val locked = lockedChannels(specs, ran) ?: return@firstNotNullOfOrNull null
            cells.firstOrNull { it.registered && it.rat == rat && it.arfcn.toInt() !in locked }?.let { "${channelName(ran)} ${it.arfcn}" }
        }

    fun stopWaiting() = settling.stopWaiting()

    fun diagnostics() = quirks.diagnostics()

    fun needsChannels(device: DeviceInfo): Boolean = quirks.needsChannels(device)

    fun forgetNeedsChannels() {
        quirks.needsChannels = false
        session.log("bunnylock forgot that this phone needs visible frequencies in band locks, so it tries bands alone first again.")
    }

    suspend fun setBands(
        group: Group,
        bands: List<Int>,
        liftsLock: Boolean,
    ): Text? = if (liftsLock) liftBands(group) else lockBands(mapOf(group to bands))

    private suspend fun liftBands(group: Group): Text? {
        val ran = ranOf(group)
        val freed = savedChannelSpecs().filter { it.optInt("ran") == ran && !it.isCellLock }
        replaceSavedBands(listOf(ran))
        val specs = bandSpecs(mapOf(group to emptyList()))
        val failure = runSuspendCatching { setChannels(specs) }.exceptionOrNull()
        if (failure !== null) {
            if (failure is LockUnanswered || specs.length() == 0) throw failure
            session.log("HAL band-unlock diagnostic: request=$specs; error=${errorOf(failure)}; retry=every ${group.label} band named.")
            setChannelsFreeing(specs, ran)
        }
        return finishHalUnlock(freed)?.let { Text(R.string.modem_bands_unlocked, group.label).followedBy(it) }
    }

    suspend fun lockBands(locks: Map<Group, List<Int>>): Text? {
        // Old Qualcomm phones keep only part of a longer 5G list.
        val nrListTooLong = locks[Group.NR].orEmpty().size > Aosp.MAX_RIL_BANDS && keepsSavedBands()
        if (nrListTooLong) fail(R.string.modem_nr_list_too_long, Aosp.MAX_RIL_BANDS)
        val lteAndNrLocks = locks.filterKeys { !it.isLegacy }
        val unseen = lteAndNrLocks.filter { (group, bands) -> visibleChannels(ratOf(group), bands).isEmpty() }.keys
        val servingCellKnown = current.cells.any { it.registered }
        if (unseen.isNotEmpty() && !servingCellKnown && quirks.needsChannels) fail(R.string.modem_needs_visible_cell, labelsOf(unseen))
        val blind = unseen.filter { servingCellKnown && !onMatchingNrCarrier(it, locks.getValue(it)) }
        blind.forEach {
            session.log("HAL band-lock diagnostic: ${bandLockProbe(it, locks.getValue(it))}; no matching channel is visible, so the lock goes out with bands only.")
        }
        val savedBefore = prefs.getString(halSpecsKey(current.sim), null)
        val bandsOnlySpecs = bandSpecs(locks)
        val bandsOnlyFailure = runSuspendCatching { setChannels(bandsOnlySpecs) }.exceptionOrNull()
        val shorterLocks =
            if (qualcommHal && bandsOnlyFailure !== null && bandsOnlyFailure !is LockUnanswered) lockWithoutNewerBands(locks) else null
        val failure = bandsOnlyFailure.takeIf { shorterLocks === null }
        val acceptedLocks = shorterLocks ?: locks
        val leftOut = locks.flatMap { (group, bands) -> (bands - acceptedLocks.getValue(group).toSet()).map(group::bandLabel) }
        val bandsLeftOut = leftOut.takeIf { it.isNotEmpty() }?.let { Text(R.string.modem_note_bands_left_out, it) }
        var note: Text? = null

        fun withBandsLeftOut(other: Text?) = listOfNotNull(bandsLeftOut, other).reduceOrNull(Text::followedBy)
        when {
            failure === null -> {}

            failure is LockUnanswered -> throw failure

            blind.isNotEmpty() -> {
                session.log("HAL band-lock diagnostic: request=$bandsOnlySpecs; error=${errorOf(failure)}; retry unavailable.")
                if (quirks.needsChannels) fail(R.string.modem_needs_visible_cell, labelsOf(blind))
                throw failure
            }

            !quirks.needsChannels && lteAndNrLocks.isNotEmpty() -> {
                val retry = if (unseen.isEmpty()) "band+channel" else "unavailable"
                val probes = lteAndNrLocks.map { (group, bands) -> bandLockProbe(group, bands) }.joinToString("; ")
                session.log("HAL band-lock diagnostic: request=$bandsOnlySpecs; error=${errorOf(failure)}; $probes; retry=$retry.")
                if (unseen.isNotEmpty()) fail(R.string.modem_needs_visible_cell, labelsOf(unseen))
                setChannels(bandSpecs(locks, withChannels = true))
                quirks.needsChannels = true
                note = Text(R.string.modem_note_needs_channels)
            }

            locks == mapOf(Group.GSM to listOf(1800)) -> {
                session.log("HAL band-lock diagnostic: request=$bandsOnlySpecs; error=${errorOf(failure)}.")
                fail(R.string.modem_no_gsm_1800)
            }

            else -> throw failure
        }
        if (blind.isNotEmpty()) {
            session.report(Text(R.string.modem_blind_lock_searching, labelsOf(blind)))
            val cells = settling.settledCells(BLIND_LOCK_RECHECKS) { !onLteOrNr(it) || straysAfterLock(it, acceptedLocks).isNotEmpty() }
            if (cells === null) {
                session.log("Whether SIM ${current.sim} found a cell after the lock is unknown, so the lock stays.")
                return bandsLeftOut
            }
            if (!onLteOrNr(cells)) revertBlindLock(locks.keys, savedBefore)
            return withBandsLeftOut(ignoredLockNote(straysAfterLock(cells, acceptedLocks)))
        }
        val cells = settling.settledCells { straysAfterLock(it, acceptedLocks).isNotEmpty() }.orEmpty()
        val registered = cells.filter { it.registered }
        // Some Shannon RILs read a bands-only lock as "no allowed channels", so the fall to 2G/3G is detected here.
        if (lteAndNrLocks.isNotEmpty() && !quirks.needsChannels && registered.isNotEmpty() && registered.none { it.rat == Rat.LTE || it.rat == Rat.NR }) {
            if (unseen.isNotEmpty()) revertBlindLock(locks.keys, savedBefore)
            quirks.needsChannels = true
            setChannels(bandSpecs(acceptedLocks, withChannels = true))
            return withBandsLeftOut(Text(R.string.modem_note_fell_back))
        }
        return withBandsLeftOut(ignoredLockNote(straysAfterLock(cells, acceptedLocks)) ?: note)
    }

    private fun errorOf(failure: Throwable): String = session.englishMessage(failure)?.takeIf(String::isNotBlank) ?: failure.javaClass.simpleName

    private fun labelsOf(groups: Collection<Group>): List<String> = groups.map { it.label }

    private fun onMatchingNrCarrier(
        group: Group,
        bands: List<Int>,
    ): Boolean = ratOf(group) == Rat.NR && current.carriers.any { it.rat == Rat.NR && it.nrCoveringBands().any { band -> band in bands } }

    private suspend fun straysAfterLock(
        cells: List<Cell>,
        locked: Map<Group, List<Int>>,
    ): Map<Group, Int> = locked.mapNotNull { (group, bands) -> strayAfterLock(cells, group, bands)?.let { group to it } }.toMap()

    // One band a Qualcomm modem doesn't know makes it refuse the whole lock, so the selection is sent again without the
    // bands newer Android versions added.
    private suspend fun lockWithoutNewerBands(locks: Map<Group, List<Int>>): Map<Group, List<Int>>? {
        val shorterLists = locks.mapValues { (group, bands) -> shorterBandLists(group, bands) }
        val oneNetworkShorter = shorterLists.flatMap { (group, lists) -> lists.map { locks + (group to it) } }
        val steps = shorterLists.values.maxOfOrNull { it.size } ?: 0
        val everyNetworkShorter =
            List(steps) { step ->
                shorterLists.mapValues { (group, lists) -> lists.getOrNull(step) ?: lists.lastOrNull() ?: locks.getValue(group) }
            }
        for (shorter in (oneNetworkShorter + everyNetworkShorter).distinct()) {
            val failure = runSuspendCatching { setChannels(bandSpecs(shorter)) }.exceptionOrNull() ?: return shorter
            if (failure is LockUnanswered) throw failure
            val shortened =
                shorter
                    .filter { (group, bands) -> bands.size < locks.getValue(group).size }
                    .entries
                    .joinToString(" and ") { (group, bands) -> "${group.label} to ${bands.size} of the ${locks.getValue(group).size} selected bands" }
            session.log("Locking $shortened didn't work (${session.englishReason(failure).asClause()}).")
        }
        return null
    }

    private fun shorterBandLists(
        group: Group,
        bands: List<Int>,
    ): List<List<Int>> {
        val shorterLists =
            when (group) {
                Group.LTE -> listOf(bands.filter { it <= NEWEST_OLD_HAL_LTE_BAND }, bands.filter { it in WIDELY_DEFINED_LTE_BANDS })
                Group.NR -> listOf(bands.filter { it in WIDELY_DEFINED_NR_BANDS })
                else -> emptyList()
            }
        return shorterLists.distinct().filter { it.isNotEmpty() && it.size < bands.size }
    }

    private fun onLteOrNr(cells: List<Cell>) = cells.any { it.registered && (it.rat == Rat.LTE || it.rat == Rat.NR) }

    private suspend fun revertBlindLock(
        groups: Collection<Group>,
        savedBefore: String?,
    ): Nothing {
        session.log("The phone found no ${groups.joinToString(" or ") { it.label }} cell on the selected bands after the lock, so the previous bands are sent again.")
        val previous = savedBefore?.let { runCatching { JSONArray(it).objects() }.getOrNull() }.orEmpty()
        runSuspendCatching {
            replaceSavedBands(groups.map(::ranOf).filter { ran -> previous.none { it.optInt("ran") == ran && !it.isCellLock } })
            sendLock(requestOf(previous))
        }.onFailure {
            session.log("Sending the previous bands again didn't work (${session.englishReason(it).asClause()}).")
            refresh()
            fail(R.string.modem_blind_lock_restore_failed)
        }
        val editor = prefs.edit()
        if (savedBefore === null) editor.remove(halSpecsKey(current.sim)) else editor.putString(halSpecsKey(current.sim), savedBefore)
        editor.apply()
        if (movesOnlyAfterRestart) runSuspendCatching { session.restartSimRadio() }
        fail(R.string.modem_blind_lock_reverted)
    }

    private fun ignoredLockNote(strays: Map<Group, Int>): Text? {
        if (strays.isEmpty()) return null
        val note = Text(R.string.modem_note_band_lock_ignored, labelsOf(strays.keys), strays.map { (group, stray) -> group.bandLabel(stray) })
        return if (current.device?.samsungMenu == true) note.followedBy(Text(R.string.modem_note_samsung_menu_hint)) else note
    }

    private fun halSpecsKey(sim: Int) = "halSpecs.sim$sim"

    private fun visibleChannels(
        rat: Rat,
        bands: List<Int>,
    ): List<Long> =
        current.cells
            .filter { it.rat == rat && it.isOnAnyOf(bands) }
            .map { it.arfcn }
            .distinct()
            .take(MAX_HAL_CHANNELS)

    private fun heldBandChannels(ran: Int): List<Long> =
        savedChannelSpecs().lastOrNull { it.optInt("ran") == ran && !it.isCellLock }?.intList("channels").orEmpty().map(Int::toLong)

    private fun bandLockProbe(
        group: Group,
        bands: List<Int>,
    ): String {
        val rat = ratOf(group)
        val matching = visibleChannels(rat, bands).joinToString().ifEmpty { "none" }
        val visible =
            current.cells
                .filter { it.rat == rat }
                .take(MAX_HAL_CHANNELS)
                .joinToString { cell ->
                    "${cell.bandLabel}@${cell.arfcn}/PCI${cell.pci}${if (cell.registered) "/serving" else ""}"
                }.ifEmpty { "none" }
        return "selected=${group.label}[${bands.joinToString()}], matching channels=$matching, visible cells=$visible"
    }

    private fun bandSpecs(
        override: Map<Group, List<Int>>,
        withChannels: Boolean = quirks.needsChannels,
    ): JSONArray {
        val specs = JSONArray()
        for (group in listOf(Group.LTE, Group.NR, Group.WCDMA, Group.GSM)) {
            val groupState = current.groups.firstOrNull { it.group == group } ?: continue
            val bands = override[group] ?: groupState.current?.sorted().orEmpty()
            if (bands.isEmpty() || bands.containsAll(groupState.supported)) continue
            // Only LTE and 5G locks have needed channels so far, and 2G and 3G cells are rarely visible from LTE.
            val pinsChannels = withChannels && !group.isLegacy
            val channels =
                when {
                    !pinsChannels -> emptyList()
                    group in override -> visibleChannels(ratOf(group), bands)
                    else -> visibleChannels(ratOf(group), bands).ifEmpty { heldBandChannels(ranOf(group)) }
                }
            specs.put(channelSpec(ranOf(group), halBandsOf(group, bands), channels).put("bandLock", true))
        }
        // Applying bands replaces whatever is locked on that network. A cell lock on the other one stays.
        val replacedRans = override.keys.map(::ranOf)
        savedChannelSpecs().filter { it.isCellLock && it.optInt("ran") !in replacedRans }.forEach { specs.put(it) }
        return specs
    }

    suspend fun lockCells(lock: CellLock): Text? {
        val lteBand = if (lock.lte) Bands.lteBandOf(lock.earfcn.toInt()) ?: fail(R.string.modem_unknown_earfcn, lock.earfcn) else null
        val nrLockBand = if (lock.nr) lock.nrBand ?: fail(R.string.modem_unknown_nr_arfcn, lock.arfcn) else null
        val lockedRans = listOfNotNull(Ran.EUTRAN.takeIf { lock.lte }, Ran.NGRAN.takeIf { lock.nr })
        val bandLocks = savedChannelSpecs().filter { !it.isCellLock }

        fun cellSpec(
            ran: Int,
            band: Int,
            channel: Long,
            withBands: Boolean,
            withChannels: Boolean,
        ) = channelSpec(
            ran,
            bands = if (withBands) listOf(band) else emptyList(),
            channels = if (withChannels) listOf(channel) else emptyList(),
        )

        fun specs(
            withBands: Boolean,
            withChannels: Boolean,
        ) = JSONArray().apply {
            lteBand?.let { put(cellSpec(Ran.EUTRAN, it, lock.earfcn, withBands, withChannels)) }
            nrLockBand?.let { put(cellSpec(Ran.NGRAN, it, lock.arfcn, withBands, withChannels)) }
            // Without channels this is a band lock itself, so it replaces the one on its network instead of standing in for it.
            bandLocks.filter { withChannels || it.optInt("ran") !in lockedRans }.forEach { put(it) }
        }
        // Radio HALs differ: some want band + channel, some only the channel, some refuse channels entirely.
        val attempts = listOf(specs(withBands = true, withChannels = true), specs(withBands = false, withChannels = true))
        val channelLocked = attempts.any { runSuspendCatching { setChannels(it) }.isSuccess }
        if (!channelLocked) {
            setChannels(specs(withBands = true, withChannels = false))
            val bands = listOfNotNull(lteBand?.let { "B$it" }, nrLockBand?.let { "n$it" })
            return Text(R.string.modem_note_band_only, bands)
        }
        val heldSpecs = savedChannelSpecs()

        val stray = strayChannel(settling.settledCells { strayChannel(it, heldSpecs) !== null }.orEmpty(), heldSpecs)
        if (stray !== null) return Text(R.string.modem_note_cell_lock_ignored, stray)
        return if (lock.pcisGiven) Text(R.string.modem_note_frequency_only) else null
    }

    suspend fun unlockCells(): Text? {
        val (cellLocks, bandLocks) = savedChannelSpecs().partition { it.isCellLock }
        // A network with a band lock stays locked, so only the others are freed.
        val freedRans = cellLocks.map { it.optInt("ran") } - bandLocks.map { it.optInt("ran") }.toSet()
        replaceSavedBands(freedRans)
        setChannels(JSONArray(bandLocks))
        return finishHalUnlock(cellLocks.filter { it.optInt("ran") in freedRans })?.let { Text(R.string.modem_cell_unlocked).followedBy(it) }
    }

    suspend fun unlockAll(): Text? {
        val held = savedChannelSpecs()
        replaceSavedBands(held.map { it.optInt("ran") } + Ran.EUTRAN)
        setChannels(JSONArray())
        return finishHalUnlock(held)?.let { Text(R.string.modem_all_unlocked).followedBy(it) }
    }

    // For a backend whose own unlock already freed the bands, so a failure to clear the HAL's cell locks is let go.
    suspend fun liftCellLocks(): Text? {
        val held = savedChannelSpecs()
        runSuspendCatching { setChannels(JSONArray()) }
        if (held.isEmpty()) return null
        return finishHalUnlock(held)?.let { Text(R.string.modem_all_unlocked).followedBy(it) }
    }

    suspend fun clear() = setChannels(JSONArray())

    suspend fun resend() {
        val held = savedChannelSpecs()
        if (held.isNotEmpty()) setChannels(JSONArray(held))
    }
}
