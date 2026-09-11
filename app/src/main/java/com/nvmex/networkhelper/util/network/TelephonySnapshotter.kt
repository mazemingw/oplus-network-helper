package com.nvmex.networkhelper.util.network


import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.util.network.telephony.DisplayInfoTracker
import com.nvmex.networkhelper.util.network.telephony.NetworkTypeResolver
import com.nvmex.networkhelper.util.network.telephony.ServingCellPicker
import com.nvmex.networkhelper.util.network.telephony.SignalStrengthFallback
import com.nvmex.networkhelper.util.network.telephony.TelephonyEnv
import com.nvmex.networkhelper.util.network.telephony.buildNsaAnchorComboText
import com.nvmex.networkhelper.util.network.telephony.parser.CellParser
import com.nvmex.networkhelper.util.network.telephony.parser.GsmParser
import com.nvmex.networkhelper.util.network.telephony.parser.LteParser
import com.nvmex.networkhelper.util.network.telephony.parser.NrParser
import com.nvmex.networkhelper.util.network.telephony.parser.WcdmaParser
import com.nvmex.networkhelper.util.network.telephony.withMccMnc


class TelephonySnapshotter(
    private val context: Context,
    private val tm: TelephonyManager = context.getSystemService(TelephonyManager::class.java),

    private val env: TelephonyEnv = TelephonyEnv(context),
    private val displayTracker: DisplayInfoTracker = DisplayInfoTracker(context, tm),
    private val typeResolver: NetworkTypeResolver = NetworkTypeResolver(),
    private val picker: ServingCellPicker = ServingCellPicker(),
    private val nrFallback: SignalStrengthFallback = SignalStrengthFallback(),
    private val parsers: List<CellParser> = listOf(
        NrParser(),
        LteParser(),
        WcdmaParser(),
        GsmParser()
    )
) {

    // 缓存检测结果，避免每次重复判断
    private var detectionModeCache: Boolean? = null
    private var detectionTime: Long = 0L
    private val DETECTION_CACHE_MS = 1000L // 每分钟重新检测一次
    /**
     * 智能判断是否需要使用宽松的 NSA 检测模式
     *
     * 检测条件：
     * 1. 存在 NR 小区但 dataNetworkType 不是 NR（ROM 可能不报告 NR 类型）
     * 2. NR 小区状态不是 ACTIVE 但存在（可能是 ColorOS 的特殊行为）
     * 3. 厂商特征检测（OPPO/OnePlus + ColorOS）
     */
    private fun shouldUseLooseMode(cellInfos: List<CellInfo>, dataTypeNow: Int?): Boolean {
        val now = System.currentTimeMillis()

        // 如果缓存有效且 cellInfos 不为空（避免空数据时误判），直接返回
        if (detectionModeCache != null &&
            cellInfos.isNotEmpty() &&
            (now - detectionTime) < DETECTION_CACHE_MS) {
//            Logger.log("使用缓存的检测结果: useLoose=$detectionModeCache")
            return detectionModeCache!!
        }

        // 执行智能检测
        val hasNrCell = cellInfos.any { it is CellInfoNr }
        val isDataTypeNr = dataTypeNow == TelephonyManager.NETWORK_TYPE_NR

        // 条件1: 存在 NR 小区但系统报告的不是 NR（典型 ColorOS/OnePlus 特征）
        val condition1 = hasNrCell && !isDataTypeNr

        // 条件2: 检查 NR 小区状态，如果都不是 ACTIVE 但存在，也可能是 ColorOS
        val nrCellStatus = cellInfos.filterIsInstance<CellInfoNr>()
            .map { it.cellConnectionStatus }
            .toSet()
        val hasActiveNr = nrCellStatus.contains(CellInfo.CONNECTION_SECONDARY_SERVING) ||
                nrCellStatus.contains(CellInfo.CONNECTION_PRIMARY_SERVING)
        val condition2 = hasNrCell && !hasActiveNr

        // 条件3: 厂商和 ROM 特征检测（放宽条件）
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val isOppoOrOneplus = manufacturer.contains("oppo", ignoreCase = true) ||
                manufacturer.contains("oneplus", ignoreCase = true) ||
                manufacturer.contains("realme", ignoreCase = true)

        val displayProp = Build.DISPLAY ?: ""
        val isColorOS = displayProp.contains("ColorOS", ignoreCase = true) ||
                displayProp.contains("OxygenOS", ignoreCase = true)  // OnePlus 的 OxygenOS


        // 条件3：只要是一加/OPPO 设备，或者运行 ColorOS/OxygenOS，就认为可能需要宽松模式
        val condition3 = isOppoOrOneplus || isColorOS

        // 综合判断：如果条件3成立，并且（条件1或条件2成立，或者 hasNrCell 为 true），则使用宽松模式
        // 对于 OnePlus 设备，只要有 NR 小区存在就启用宽松模式（因为 OnePlus 经常不报告正确的状态）
        val useLoose = if (isOppoOrOneplus && hasNrCell) {
            // OnePlus 设备只要检测到 NR 小区就启用宽松模式
            true
        } else {
            // 其他情况按原逻辑
            condition3 && (condition1 || condition2)
        }

        // 记录检测结果
        detectionModeCache = useLoose
        detectionTime = now

//        Logger.log("智能检测结果: useLoose=$useLoose")
//        Logger.log("  条件1=$condition1, 条件2=$condition2, 条件3=$condition3")
//        Logger.log("  厂商=$manufacturer, ROM=$displayProp")
//        Logger.log("  hasNrCell=$hasNrCell, isDataTypeNr=$isDataTypeNr, hasActiveNr=$hasActiveNr")
//        Logger.log("  宽松模式触发原因: ${if (useLoose) {
//            if (isOppoOrOneplus && hasNrCell) "OnePlus设备+存在NR小区"
//            else if (condition1) "存在NR小区但数据类型不是NR"
//            else if (condition2) "存在NR小区但无活跃状态"
//            else "厂商特征匹配"
//        } else "条件不满足"}")

        return useLoose
    }


    // ====== 关键：去“残影”窗口（ms）=====
    // 2~3 秒是比较稳的默认值：能过滤切网残留/邻区陈旧记录，又不至于让 list 过空。
    private val CELLINFO_FRESH_MS = 2500L

    // ====== CellInfo 时间工具 ======
    private fun CellInfo.ageMs(): Long {
        val ts = this.timeStamp
        if (ts <= 0L) return 0L   // ✅ 没时间戳：当作新鲜，避免断流
        val now = SystemClock.elapsedRealtimeNanos()
        val d = now - ts
        if (d < 0L) return 0L
        return d / 1_000_000L
    }


    private fun List<CellInfo>.freshWithin(ms: Long): List<CellInfo> {
        if (isEmpty()) return emptyList()
        return filter { it.ageMs() in 0..ms }
    }

    private fun List<CellInfo>.freshWithinOrServing(ms: Long): List<CellInfo> {
        if (isEmpty()) return emptyList()

        val isServing: (CellInfo) -> Boolean = {
            it.isRegistered ||
                    it.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING ||
                    it.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING
        }

        val serving = filter(isServing)

        val othersFresh = filterNot(isServing)
            .filter { it.ageMs() in 0..ms }

        return (serving + othersFresh).distinctBy { System.identityHashCode(it) }
    }

    /**
     * ✅ 关键：cellInfosOverride 来自每卡各自 TelephonyCallback.onCellInfoChanged
     * 这样就不会出现 “卡2面板拿到卡1 allCellInfo” 的 ROM 坑。
     */
    @SuppressLint("MissingPermission")
    fun snapshot(
        cellInfosOverride: List<CellInfo>? = null,
        serviceStateOverride: ServiceState? = null,
        signalStrengthOverride: SignalStrength? = null,
        displayInfoOverride: DisplayInfoSnapshot? = null
    ): NetworkPanelUiState {

        // ========== 方法入口日志 ==========
//        Logger.log("snapshot: cellInfosOverride=${cellInfosOverride?.size}, ssOverride=${serviceStateOverride != null}, sigOverride=${signalStrengthOverride != null}")

        val hasPhone = env.hasPhonePermission()
        val hasLoc = env.hasLocationPermission()
        val locEnabled = env.isLocationEnabled()

        if (!hasPhone) {
//            Logger.log("snapshot: no phone permission")
            return NetworkPanelUiState(
                hasPhonePermission = false,
                hasLocationPermission = hasLoc,
                isLocationEnabled = locEnabled,
                lastError = "缺少电话权限：无法读取 SIM 信息",
                updatedAt = System.currentTimeMillis()
            )
        }

        // NSA 判定依赖 DisplayInfo（Android 12+）
        // 但 DisplayInfo 在多卡/部分 ROM 下可能“串味”，所以后面会做一次 CellInfo 事实纠正。
        displayTracker.ensureRegisteredOnce()

        val ss = serviceStateOverride ?: runCatching { tm.serviceState }.getOrNull()
        val sig = signalStrengthOverride ?: runCatching { tm.signalStrength }.getOrNull()

        val displayInfoSnapshot = displayInfoOverride
        val resolved = typeResolver.resolve(
            tm = tm,
            ss = ss,
            overrideType = displayInfoSnapshot?.overrideNetworkType ?: displayTracker.lastOverrideType,
            networkTypeOverride = displayInfoSnapshot?.networkType
        )

        var state = NetworkPanelUiState(
            hasPhonePermission = true,
            hasLocationPermission = hasLoc,
            isLocationEnabled = locEnabled,
            operatorName = displayOperatorName(tm),
            dataNetworkType = resolved.displayTypeName, // resolver 可能默认 NR（尤其切网瞬间）
            dataNetworkTypeRaw = resolved.dataType,
            dataNetworkTypeRawName = resolved.dataTypeName,
            overrideNetworkTypeRaw = resolved.overrideType,
            overrideNetworkTypeName = resolved.overrideTypeName,
            nrMode = resolved.nrMode,
            updatedAt = System.currentTimeMillis()
        ).withMccMnc(tm.networkOperator)

        // 小区信息通常需要定位权限 + 开启定位
        if (!hasLoc || !locEnabled) {
//            Logger.log("snapshot: no location permission or location disabled")
            return state.copy(
                lastError = if (!hasLoc) "缺少定位权限：无法读取小区信息"
                else "定位服务未开启：小区信息可能为空"
            )
        }

        // ========== 获取 CellInfo 列表 ==========
        val rawCellInfos = cellInfosOverride
            ?: runCatching { tm.allCellInfo }.getOrNull().orEmpty()

//        Logger.log("rawCellInfos.size=${rawCellInfos.size}")
        rawCellInfos.forEachIndexed { idx, ci ->
//            Logger.log("  ci[$idx]: ${ci.javaClass.simpleName}, age=${ci.ageMs()}ms, isRegistered=${ci.isRegistered}, status=${ci.cellConnectionStatus}")
        }

        // ✅ 去“残影”：只信近期数据
        val filtered = rawCellInfos.freshWithinOrServing(CELLINFO_FRESH_MS)
//        Logger.log("filtered.size=${filtered.size}, CELLINFO_FRESH_MS=$CELLINFO_FRESH_MS")
        val cellInfos = if (filtered.isNotEmpty()) filtered else rawCellInfos
//        Logger.log("cellInfos.size=${cellInfos.size} (after fallback)")


        // ========== NSA 判定（智能选择严格/宽松模式） ==========
        val hasLteAnchorCell = cellInfos
            .filterIsInstance<CellInfoLte>()
            .any {
                it.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING || it.isRegistered
            }

        val hasNrActiveCell = cellInfos
            .filterIsInstance<CellInfoNr>()
            .any {
                it.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING || it.isRegistered
            }

        val hasNrCell = cellInfos.any { it is CellInfoNr }

        val dataTypeNow = runCatching { tm.dataNetworkType }.getOrNull()
        val isNrDataNowOriginal = (dataTypeNow == TelephonyManager.NETWORK_TYPE_NR) ||
                (resolved.dataType == TelephonyManager.NETWORK_TYPE_NR)

// 智能判断使用哪种模式
        val useLooseMode = shouldUseLooseMode(cellInfos, dataTypeNow)

        val (isNsaFixed, isNrDataNow) = if (useLooseMode) {
            // 宽松模式：针对 ColorOS 等特殊 ROM
            val isNsa = resolved.isNsa && hasLteAnchorCell && hasNrCell
            val isNr = isNsa || isNrDataNowOriginal
//            Logger.log("使用宽松 NSA 检测模式")
            Pair(isNsa, isNr)
        } else {
            // 严格模式：标准 Android 逻辑
            val isNsa = resolved.isNsa && isNrDataNowOriginal && hasNrActiveCell && hasLteAnchorCell
            val isNr = isNrDataNowOriginal
//            Logger.log("使用严格 NSA 检测模式")
            Pair(isNsa, isNr)
        }

//        Logger.log("hasLteAnchorCell=$hasLteAnchorCell, hasNrActiveCell=$hasNrActiveCell, hasNrCell=$hasNrCell")
//        Logger.log("dataTypeNow=$dataTypeNow, isNrDataNow=$isNrDataNow, resolved.isNsa=${resolved.isNsa}")
//        Logger.log("isNsaFixed=$isNsaFixed, useLooseMode=$useLooseMode")

// 纠正后的 nrMode：只有数据面仍 NR 才显示 NSA/SA，否则显示 "-"
        val nrModeFixed = if (isNrDataNow) {
            if (isNsaFixed) "NSA" else "SA"
        } else "-"

// 立即把 nrMode 修正到 state，避免顶部仍显示 NSA
        state = state.copy(nrMode = nrModeFixed)

// NSA 下组合显示：B? + N?
        val nsaCombo = if (isNsaFixed && cellInfos.isNotEmpty()) {
            val combo = buildNsaAnchorComboText(cellInfos)
//            Logger.log("nsaCombo generated: $combo")
            combo
        } else "-"

// 冷启动/ROM 限制导致 CellInfo 为空（注意：我们做了 fresh 过滤，可能导致“暂时为空”）
        if (cellInfos.isEmpty()) {
//            Logger.log("cellInfos.isEmpty() == true")
            // 只有真的 NSA 才尝试用 SignalStrength 去兜 NR SS 三件套
            val nrFromSig = if (isNsaFixed) {
                nrFallback.fromSignalStrength(
                    base = state,
                    sig = sig,
                    lastErrorMessage = context.getString(R.string.network_nr_cellinfo_fallback_hint)
                )
            } else null
            if (nrFromSig != null) {
//                Logger.log("nrFallback used: rsrp=${nrFromSig.ssRsrp}, rsrq=${nrFromSig.ssRsrq}, sinr=${nrFromSig.ssSinr}")
            }
            return (nrFromSig ?: state).copy(
                anchorBandCombo = nsaCombo,
                nsaLteAnchor = null,
                lastError = nrFromSig?.lastError ?: context.getString(R.string.network_cellinfo_empty_hint),
                updatedAt = System.currentTimeMillis()
            )
        }

        // ========== 选择 serving（用于主视图：NSA 默认选 NR secondary，否则按常规） ==========
        val serving = picker.pick(cellInfos, isNsaFixed, resolved.dataType)
//        Logger.log("serving selected: ${serving?.javaClass?.simpleName}")

        // ========== 顶部类型纠正 ==========
        state = state.copy(
            dataNetworkType = picker.derivedTypeName(isNsaFixed, serving, state.dataNetworkType)
        )

        // ========== 解析主视图 serving ==========
        var parsed: NetworkPanelUiState =
            parsers.firstOrNull { it.canParse(serving) }
                ?.parse(state, serving, sig)
                ?: state.copy(cellType = serving.javaClass.simpleName, lastError = null)

        // ========== NSA：解析 LTE Anchor 视图（用于“切到4G”） ==========
        val lteAnchor: CellInfoLte? = if (isNsaFixed) {
            val lteList = cellInfos.filterIsInstance<CellInfoLte>()
            lteList.firstOrNull { it.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING }
                ?: lteList.firstOrNull { it.isRegistered }
                ?: lteList.firstOrNull()
        } else null

        val lteAnchorParsed: NetworkPanelUiState? = lteAnchor?.let { lte ->
            parsers.firstOrNull { it.canParse(lte) }
                ?.parse(
                    // LTE 视图：强制展示 LTE，但保留 nrMode=NSA（表示真实网络仍是 NSA）
                    state.copy(dataNetworkType = "LTE", nrMode = nrModeFixed),
                    lte,
                    sig
                )
                ?.copy(
                    dataNetworkType = "LTE",
                    nrMode = nrModeFixed,
                    lastError = null
                )
        }

        // ========== NSA：NR SS 三件套兜底（CellInfoNr 信号被 ROM 屏蔽时，用 SignalStrength 补齐） ==========
        if (isNsaFixed) {
            val nrFromSig = nrFallback.fromSignalStrength(
                base = state.copy(dataNetworkType = "NR"),
                sig = sig,
                lastErrorMessage = context.getString(R.string.network_nr_cellinfo_fallback_hint)
            )
//            Logger.log("NSA: nrFromSig != null: ${nrFromSig != null}")

            val nrSsInvalid = (parsed.cellType == "NR") && (
                    parsed.ssRsrp == "-" || parsed.ssRsrp.isBlank() ||
                            parsed.ssRsrq == "-" || parsed.ssRsrq.isBlank() ||
                            parsed.ssSinr == "-" || parsed.ssSinr.isBlank()
                    )

            val noNrCellInfo = cellInfos.none { it is CellInfoNr }
//            Logger.log("NSA: nrSsInvalid=$nrSsInvalid, noNrCellInfo=$noNrCellInfo")

            if ((noNrCellInfo || nrSsInvalid) && nrFromSig != null) {
//                Logger.log("Applying nrFallback data: rsrp=${nrFromSig.ssRsrp}, rsrq=${nrFromSig.ssRsrq}, sinr=${nrFromSig.ssSinr}")
                // ✅ 只覆盖 SS/RS* 信号，不动 NR 小区参数（tac/pci/arfcn/band 等）
                parsed = parsed.copy(
                    dataNetworkType = "NR",
                    nrMode = nrModeFixed,

                    rsrp = nrFromSig.ssRsrp,
                    rsrq = nrFromSig.ssRsrq,
                    sinr = nrFromSig.ssSinr,

                    ssRsrp = nrFromSig.ssRsrp,
                    ssRsrq = nrFromSig.ssRsrq,
                    ssSinr = nrFromSig.ssSinr,

                    lastError = context.getString(R.string.network_nsa_inferred_hint)
                )
            }
        }

        // ========== 收尾：挂上组合频段 + LTE Anchor 视图 ==========
        parsed = parsed.copy(
            anchorBandCombo = nsaCombo,
            nsaLteAnchor = lteAnchorParsed,
            updatedAt = System.currentTimeMillis()
        )

//        Logger.log("final state: nrMode=${parsed.nrMode}, anchorBandCombo=${parsed.anchorBandCombo}, cellType=${parsed.cellType}, ssRsrp=${parsed.ssRsrp}, ssRsrq=${parsed.ssRsrq}, ssSinr=${parsed.ssSinr}")

        return parsed
    }

    private fun displayOperatorName(tm: TelephonyManager): String {
        val networkName = runCatching { tm.networkOperatorName }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "-" }
        val simName = runCatching { tm.simOperatorName }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "-" }
        val roaming = runCatching { tm.isNetworkRoaming }.getOrDefault(false)

        return if (
            roaming &&
            networkName != null &&
            simName != null &&
            !networkName.equals(simName, ignoreCase = true)
        ) {
            "$networkName - $simName"
        } else {
            networkName ?: simName ?: "-"
        }
    }

}
