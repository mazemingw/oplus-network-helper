package com.nvmex.networkhelper.xposed.woker

import android.os.Looper
import com.nvmex.networkhelper.model.network.GlobalQosEvent
import com.nvmex.networkhelper.model.network.QosData
import com.nvmex.networkhelper.xposed.broad.BroadcastHelper
import com.nvmex.networkhelper.xposed.logger.Logger
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


//钩子：RadioAtomTracker AtomModemKpiInfo OplusScoreQosInfo


class QosTrackerEntry : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[QoSTracker]"
        private const val TARGET_PACKAGE = "com.oplus.subsys"
        private const val RADIO_ATOM_TRACKER_CLASS = "com.oplus.radio.RadioAtomTracker"
    }

    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val parseCount = AtomicLong(0)
    private val mainHandler by lazy { android.os.Handler(Looper.getMainLooper()) }
    private val broadcastHelper = BroadcastHelper()

    /**
     * 全局事件缓存：这些不归属单卡
     */
    @Volatile
    private var latestGlobalEvent = GlobalQosEvent()

    @Volatile
    private var cachedQosProtoClass: Class<*>? = null

    @Volatile
    private var cachedQosParseMethod: java.lang.reflect.Method? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        Logger.log("$TAG 开始初始化，目标包: ${lpparam.packageName}")
        retryHook(lpparam.classLoader, maxRetries = 20, delayMs = 200)
    }

    private fun retryHook(classLoader: ClassLoader, maxRetries: Int, delayMs: Long, currentTry: Int = 1) {
        try {
            val atomTrackerClass = Class.forName(RADIO_ATOM_TRACKER_CLASS, false, classLoader)
            Logger.log("$TAG 第 $currentTry 次尝试成功找到类: $atomTrackerClass")

            performHook(atomTrackerClass)
            hookAtomDataFetch(classLoader)
        } catch (e: ClassNotFoundException) {
            if (currentTry < maxRetries) {
                Logger.log("$TAG 第 $currentTry 次尝试失败，${delayMs}ms 后重试...")
                mainHandler.postDelayed({
                    retryHook(classLoader, maxRetries, delayMs, currentTry + 1)
                }, delayMs)
            } else {
                Logger.logE("$TAG 经过 $maxRetries 次重试后仍然找不到类，放弃 Hook")
                Logger.log("$TAG classLoader: $classLoader")
            }
        }
    }

    private fun performHook(atomTrackerClass: Class<*>) {
        try {
            try {
                val instance = XposedHelpers.callStaticMethod(atomTrackerClass, "getInstance")
                Logger.log("$TAG 获取到 RadioAtomTracker 实例: $instance")
            } catch (_: Throwable) {
                Logger.log("$TAG 获取实例失败（可能不是单例或方法不存在）")
            }

            /**
             * 单卡 QoS period：唯一可信的 per-sub 快照源
             */
            val callSubId = ThreadLocal<Int>()
            XposedBridge.hookAllMethodsNative(atomTrackerClass, "parseQosPeriodData") { chain ->
                val subIdArg = (chain.args.getOrNull(0) as? Int) ?: -1
                callSubId.set(subIdArg)
                try {
                    val result = chain.proceed()
                    val qosInfo = result ?: return@hookAllMethodsNative result
                    parseCount.incrementAndGet()

                    if (parseCount.get() == 1L) {
                        Logger.log("$TAG 首次捕获 QoS 数据，类型: ${qosInfo.javaClass.name}")
                        val fields = qosInfo.javaClass.declaredFields
                        Logger.log("$TAG CellularQosInfo 字段: ${fields.joinToString { it.name }}")
                    }

                    val subId = callSubId.get() ?: -1
                    val data = extractPerSubQosData(qosInfo, subId)

                    // 只发单卡快照
                    broadcastHelper.emitQosToApp(data)

                    // 同时发最近一次全局事件快照
                    broadcastHelper.emitGlobalQosEventToApp(latestGlobalEvent)

                    Logger.log("$TAG QoS #${parseCount.get()}: ${formatSimpleQosData(data)}")
                    result
                } catch (t: Throwable) {
                    Logger.logE("$TAG 处理 QoS 数据失败", t)
                    throw t
                } finally {
                    callSubId.remove()
                }
            }

            /**
             * 全局 event：按原厂逻辑解析
             */
            try {
                XposedBridge.hookAllMethodsNative(atomTrackerClass, "parseQosEventData") { chain ->
                    val result = chain.proceed()
                    try {
                        val trackerObj = chain.thisObject ?: return@hookAllMethodsNative result
                        val raw = chain.args.getOrNull(0) as? ByteArray

                        // 先从 tracker 上拿“原厂已经累计好的计数器”
                        val rlfCount = getIntField(trackerObj, "mRlfCount", 0)
                        val rachWithUlGrantCount = getIntField(trackerObj, "mRachWithULGrantCount", 0)
                        val cellChangeCount = getIntField(trackerObj, "mCellChangeCount", 0)
                        val isRedirectionOccur = getIntField(trackerObj, "mIsRedirectionOccur", 0)

                        var mobilitySysMode = 0
                        var mobilityType = 0
                        var mobilityStatus = 0
                        var mobilitySourceRat = 0
                        var mobilityTargetRat = 0

                        var nasSysMode = 0
                        var emmState = 0
                        var emmSubState = 0
                        var mm5gState = 0
                        var mm5gSubState = 0
                        var plmnId = 0

                        var hasRlfEvent = false
                        var hasRachEvent = false
                        var rachReason = 0

                        if (raw != null && raw.isNotEmpty()) {
                            try {
                                val parserCl = trackerObj.javaClass.classLoader
                                val parser = if (parserCl != null) {
                                    resolveQosProtoParser(parserCl)
                                } else {
                                    Logger.log("$TAG parseQosEventData: tracker classLoader is null")
                                    null
                                }
                                if (parser != null) {
                                    val (_, parseFrom) = parser
                                    val qosEventObj = parseFrom.invoke(null, raw)

                                    if (qosEventObj != null) {
                                        // rlfEvent
                                        hasRlfEvent = getObjectField(qosEventObj, "rlfEvent") != null

                                        // rachEvent
                                        getObjectField(qosEventObj, "rachEvent")?.let { rach ->
                                            hasRachEvent = true
                                            rachReason = getIntField(rach, "rachReason", 0)
                                        }

                                        // mobilityEvent
                                        getObjectField(qosEventObj, "mobilityEvent")?.let { mobility ->
                                            mobilitySysMode = getIntField(mobility, "sysMode", 0)
                                            mobilityType = getIntField(mobility, "mobilityType", 0)
                                            mobilityStatus = getIntField(mobility, "mobilityStatus", 0)
                                            mobilitySourceRat = getIntField(mobility, "sourceRat", 0)
                                            mobilityTargetRat = getIntField(mobility, "targetRat", 0)
                                        }

                                        // nasState
                                        getObjectField(qosEventObj, "nasState")?.let { nas ->
                                            nasSysMode = getIntField(nas, "sysMode", 0)
                                            emmState = getIntField(nas, "emmState", 0)
                                            emmSubState = getIntField(nas, "emmSubState", 0)
                                            mm5gState = getIntField(nas, "mm5GState", 0)
                                            mm5gSubState = getIntField(nas, "mm5GSubState", 0)
                                            plmnId = getIntField(nas, "plmnId", 0)
                                        }

                                        Logger.log(
                                            "$TAG parseQosEventData detail: " +
                                                "hasRlf=$hasRlfEvent, " +
                                                "hasRach=$hasRachEvent, " +
                                                "rachReason=$rachReason, " +
                                                "mobility=[sys=$mobilitySysMode,type=$mobilityType,status=$mobilityStatus," +
                                                "src=$mobilitySourceRat,dst=$mobilityTargetRat], " +
                                                "nas=[sys=$nasSysMode,emm=$emmState/$emmSubState," +
                                                "mm5g=$mm5gState/$mm5gSubState,plmn=$plmnId]"
                                        )
                                    }
                                } else {
                                    Logger.log("$TAG parseQosEventData: 暂未定位到 proto 类，详情字段保持默认值")
                                }
                            } catch (e: Throwable) {
                                Logger.logE("$TAG parseQosEventData proto 解析失败", e)
                            }
                        }

                        val event = GlobalQosEvent(
                            timestamp = System.currentTimeMillis(),

                            rlfCount = rlfCount,
                            rachWithUlGrantCount = rachWithUlGrantCount,
                            cellChangeCount = cellChangeCount,
                            isRedirectionOccur = isRedirectionOccur,

                            mobilitySysMode = mobilitySysMode,
                            mobilityType = mobilityType,
                            mobilityStatus = mobilityStatus,
                            mobilitySourceRat = mobilitySourceRat,
                            mobilityTargetRat = mobilityTargetRat,

                            // 原厂这段没有实际 paging 逻辑，这里先保留 0
                            pagingSysMode = 0,

                            nasSysMode = nasSysMode,
                            emmState = emmState,
                            emmSubState = emmSubState,
                            mm5gState = mm5gState,
                            mm5gSubState = mm5gSubState,
                            plmnId = plmnId
                        )

                        latestGlobalEvent = event

                        // 关键：全局事件一旦更新，立刻推给 App
                        broadcastHelper.emitGlobalQosEventToApp(event)

                        Logger.log(
                            "$TAG parseQosEventData: " +
                                "rlf=${event.rlfCount}, " +
                                "rachWithGrant=${event.rachWithUlGrantCount}, " +
                                "cellChange=${event.cellChangeCount}, " +
                                "redir=${event.isRedirectionOccur}, " +
                                "mobility=[sys=${event.mobilitySysMode},type=${event.mobilityType}," +
                                "status=${event.mobilityStatus},src=${event.mobilitySourceRat},dst=${event.mobilityTargetRat}], " +
                                "nas=[sys=${event.nasSysMode},emm=${event.emmState}/${event.emmSubState}," +
                                "mm5g=${event.mm5gState}/${event.mm5gSubState},plmn=${event.plmnId}]"
                        )
                    } catch (t: Throwable) {
                        Logger.logE("$TAG parseQosEventData 处理失败", t)
                    }
                    result
                }
            } catch (e: Throwable) {
                Logger.log("$TAG parseQosEventData Hook 失败/方法不存在: $e")
            }

            /**
             * latency 单独钩住，便于后续观察 per-sub 时延更新
             */
            try {
                val updateLinkLatencyInfoMethod = atomTrackerClass.getDeclaredMethod(
                    "updateLinkLatencyInfo",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true }
                XposedBridge.hookMethodNative(updateLinkLatencyInfoMethod) { chain ->
                    val result = chain.proceed()
                    val subId = chain.args.getOrNull(0) as? Int ?: -1
                    val latency = chain.args.getOrNull(1) as? Int ?: -1
                    Logger.log("$TAG updateLinkLatencyInfo: subId=$subId latencyRaw=$latency")
                    result
                }
            } catch (e: Throwable) {
                Logger.log("$TAG updateLinkLatencyInfo Hook 失败/方法不存在: $e")
            }

            Logger.log("$TAG 成功 Hook parseQosPeriodData / parseQosEventData / updateLinkLatencyInfo")
        } catch (t: Throwable) {
            Logger.logE("$TAG performHook 失败", t)
        }
    }

    /**
     * 这里只构建“单卡快照”，不把全局 event 计数塞进来
     */
    private fun extractPerSubQosData(qosInfo: Any, subIdArg: Int): QosData {
        val subIdFromResult = getIntField(qosInfo, "mSubId", -1)
        val finalSubId = if (subIdArg != -1) subIdArg else subIdFromResult

        return QosData(
            timestamp = System.currentTimeMillis(),
            subId = finalSubId,

            rat = getIntField(qosInfo, "mRat", -1),
            endcState = getIntField(qosInfo, "mEndcState", -1),
            arfcn = getIntField(qosInfo, "mArfcn", -1),
            pci = getIntField(qosInfo, "mPci", -1),
            band = getIntField(qosInfo, "mBand", -1),
            dlBw = getIntField(qosInfo, "mDLBW", -1),
            rsrp = getIntField(qosInfo, "mRsrp", -1),
            rsrq = getIntField(qosInfo, "mRsrq", -1),
            snr = getIntField(qosInfo, "mSnr", -1),
            svcStatus = getIntField(qosInfo, "mSvcStatus", -1),

            ulTimeStamp = getLongField(qosInfo, "mULTimeStamp", -1L),
            ulPdcpNumDataPdu = getIntField(qosInfo, "mULPDCPNumDataPdu", -1),
            ulPdcpNumDropPdu = getIntField(qosInfo, "mULPDCPNumDropPdu", -1),
            ulPdcpTput = getLongField(qosInfo, "mULPDCPTput", 0L),
            ulRlcNumDataPdu = getIntField(qosInfo, "mULRLCNumDataPdu", -1),
            ulRlcRetx = getIntField(qosInfo, "mULRLCNumRetxPdu", -1),
            ulGrant = getIntField(qosInfo, "mULGrant", -1),
            ulBsr = getIntField(qosInfo, "mULBSR", -1),
            ulBler = getIntField(qosInfo, "mULBler", -1),

            dlTimeStamp = getLongField(qosInfo, "mDLTimeStamp", -1L),
            dlPdcpNumDataPdu = getIntField(qosInfo, "mDLPDCPNumDataPdu", -1),
            dlPdcpTput = getLongField(qosInfo, "mDLPDCPTput", 0L),
            dlRlcNumDataPdu = getIntField(qosInfo, "mDLRLCNumDataPdu", -1),
            dlRlcRetx = getIntField(qosInfo, "mDLRLCNumRetxPdu", -1),
            dlRlcDrop = getIntField(qosInfo, "mDLRLCNumDropPdu", -1),
            dlMacPaddingBytes = getIntField(qosInfo, "mDLMACPaddingBytes", -1),
            dlPdcpNumMissToUppPdu = getIntField(qosInfo, "mDLPdcpNumMissToUppPdu", -1),
            dlBler = getIntField(qosInfo, "mDLBler", -1),

            latency = getIntField(qosInfo, "mLatency", -1),
            cellId = getLongField(qosInfo, "mCellId", -1L),
            nr5gScs = getIntField(qosInfo, "mNr5GScs", -1),
            cellLoad = getIntField(qosInfo, "mCellLoad", -1),

            rachCount = getIntField(qosInfo, "mRachCount", -1),
            rachAbortCount = getIntField(qosInfo, "mRachAbortCount", -1),

            sub1RrcState = getIntField(qosInfo, "mSub1RrcState", -1),
            sub2RrcState = getIntField(qosInfo, "mSub2RrcState", -1),
            isDualSimConflict = getIntField(qosInfo, "mIsDualSimConflict", 0),

            calcPower = getIntField(qosInfo, "mCalcPower", -1),
            mtpl = getIntField(qosInfo, "mMtpl", -1),
            pathLoss = getIntField(qosInfo, "mPathLoss", -1),
            fbrxCount = getIntField(qosInfo, "mFbrxCount", -1),

            linkReport = getBooleanField(qosInfo, "mLinkReport", false),
            limitSpeedFlag = getIntField(qosInfo, "mLimitSpeedFlag", 0),
            limitSpeedRate = getIntField(qosInfo, "mLimitSpeedRate", 0)
        )
    }

    private fun formatSimpleQosData(data: QosData): String {
        return "subId=${data.subId}, rat=${data.rat}, band=${data.band}, " +
                "rsrp=${data.rsrp}, rsrq=${data.rsrq}, dl=${formatSpeed(data.dlPdcpTput)}, " +
                "latency=${data.latency}ms, cellId=${data.cellId}"
    }

    private fun formatSpeed(speed: Long): String {
        return when {
            speed >= 1_000_000 -> "${speed / 1_000_000.0} Mbps"
            speed >= 1000 -> "${speed / 1000.0} Kbps"
            else -> "${speed} bps"
        }
    }

    private fun getIntField(obj: Any, fieldName: String, defaultValue: Int): Int {
        return try {
            val field = fieldCache.computeIfAbsent("${obj.javaClass.name}#$fieldName") {
                obj.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            }
            field.getInt(obj)
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun getLongField(obj: Any, fieldName: String, defaultValue: Long): Long {
        return try {
            val field = fieldCache.computeIfAbsent("${obj.javaClass.name}#$fieldName") {
                obj.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            }
            field.getLong(obj)
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun getBooleanField(obj: Any, fieldName: String, defaultValue: Boolean): Boolean {
        return try {
            val field = fieldCache.computeIfAbsent("${obj.javaClass.name}#$fieldName") {
                obj.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            }
            field.getBoolean(obj)
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun getObjectField(obj: Any, fieldName: String): Any? {
        return try {
            val field = fieldCache.computeIfAbsent("${obj.javaClass.name}#$fieldName") {
                obj.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            }
            field.get(obj)
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveQosProtoParser(classLoader: ClassLoader): Pair<Class<*>, java.lang.reflect.Method>? {
        cachedQosProtoClass?.let { cls ->
            cachedQosParseMethod?.let { method ->
                return cls to method
            }
        }

        val candidates = listOf(
            // 这条是你反编译确认过的真实路径
            "com.oplus.telephony.nano.OplusScoreQosInfo\$qos_info_msg_type",

            // 下面只是兜底，防止其它 ROM 魔改
            "com.oplus.telephony.OplusScoreQosInfo\$qos_info_msg_type",
            "com.oplus.radio.data.OplusScoreQosInfo\$qos_info_msg_type",
            "com.oplus.radio.OplusScoreQosInfo\$qos_info_msg_type",
            "com.oplus.network.OplusScoreQosInfo\$qos_info_msg_type",
            "com.oplus.internal.telephony.OplusScoreQosInfo\$qos_info_msg_type",
            "com.android.internal.telephony.OplusScoreQosInfo\$qos_info_msg_type"
        )

        for (name in candidates) {
            try {
                val cls = Class.forName(name, false, classLoader)
                val method = cls.getDeclaredMethod("parseFrom", ByteArray::class.java).apply {
                    isAccessible = true
                }
                cachedQosProtoClass = cls
                cachedQosParseMethod = method
                Logger.log("$TAG 成功定位 QoS proto 类: $name")
                return cls to method
            } catch (_: Throwable) {
            }
        }

        Logger.log("$TAG 未找到 QoS proto 类，候选全部失败")
        return null
    }

    // ATOM
    private fun hookAtomDataFetch(classLoader: ClassLoader) {
        runCatching {
            val cls = Class.forName("com.oplus.epa.AtomModemKpiInfo", false, classLoader)
            val trackerCls = Class.forName(
                "com.oplus.tracker.nano.AtomTrackerInfo\$tele_atom_info_type",
                false,
                classLoader
            )

            val fetchMethod = cls.getDeclaredMethod(
                "fetch",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                trackerCls,
                android.os.Message::class.java
            ).apply { isAccessible = true }
            XposedBridge.hookMethodNative(fetchMethod) { chain ->
                val atomAppid = chain.args.getOrNull(0) as? Int ?: -1
                val slotId = chain.args.getOrNull(1) as? Int ?: -1
                val atomDataId = chain.args.getOrNull(2) as? Int ?: -1
                val atomMask = chain.args.getOrNull(3) as? Int ?: -1
                Logger.log(
                    "$TAG [ATOM][fetch] ${(chain.thisObject?.javaClass?.name ?: "null")} " +
                        "appid=$atomAppid slot=$slotId dataId=$atomDataId mask=$atomMask"
                )
                val result = chain.proceed()
                dumpTrackerNonNull(chain.args.getOrNull(4))
                result
            }

            Logger.log("$TAG [ATOM] hooked AtomModemKpiInfo#fetch(int,int,int,int,tracker,Message)")
        }.onFailure {
            Logger.log("$TAG [ATOM] hook AtomModemKpiInfo fetch failed: $it")
        }
    }

    private fun dumpTrackerNonNull(tracker: Any?) {
        if (tracker == null) return
        runCatching {
            val cls = tracker.javaClass
            val fields = cls.declaredFields
            val hit = ArrayList<String>()
            for (f in fields) {
                f.isAccessible = true
                val v = f.get(tracker)
                val useful = when (v) {
                    null -> false
                    is Int -> v != 0
                    is Long -> v != 0L
                    is Boolean -> v
                    is ByteArray -> v.isNotEmpty()
                    is IntArray -> v.isNotEmpty()
                    is Array<*> -> v.isNotEmpty()
                    else -> true
                }
                if (useful) hit.add("${f.name}=${v?.javaClass?.simpleName ?: "null"}")
            }
            if (hit.isNotEmpty()) {
                Logger.log("$TAG [ATOM][tracker] nonNull=${hit.joinToString()}")
            }
        }.onFailure {
            Logger.log("$TAG [ATOM][tracker] dump failed: $it")
        }
    }
}
