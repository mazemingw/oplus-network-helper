package com.nvmex.networkhelper.xposed.woker

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Parcel
import com.nvmex.networkhelper.xposed.config.Config
import com.nvmex.networkhelper.xposed.logger.Logger
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

//操作锁频 先驱者
class EngineerBandLockObserverEntry : IXposedHookLoadPackage {

    companion object {
        private const val ENGINEER_PKG = "com.oplus.engineernetwork"
        private const val PHONE_PKG = "com.android.phone"
        private const val EXPECT_WINDOW_MS = 15_000L
        private const val MSG_SET_BAND_PREFER = 2188
        private const val MSG_GET_BAND_PREFER = 2189
        private const val MSG_SET_BAND_MODE = 2030
        private const val MSG_RESTORE_NV_BACKUP_ALLOWED = 2051
        private const val MSG_GET_AVAILABLE_BAND_MODES = 2031
        private const val MSG_REQUIRE_MODEM_REBOOT = 2085
        private const val BUNDLE_WHAT_SET_BAND_PREFER = 1003
        private const val BUNDLE_WHAT_GET_BAND_PREFER = 1004
        private const val BUNDLE_WHAT_SET_BAND_MODE = 1001
        private const val BUNDLE_WHAT_RESTORE_NV_BACKUP_ALLOWED = 1009
        private const val BUNDLE_WHAT_GET_AVAILABLE_BAND_MODES = 1000
        private const val BUNDLE_WHAT_REQUIRE_MODEM_REBOOT = 1007
        private const val BAND_LOCK_TRACE_REV = "band-lock-trace-r7:phone-subsys-messenger"
        private const val TRACE_ALL_MESSENGER_MESSAGES = true
        private const val TRACE_STACK_FOR_BAND_PROTOCOL = true
    }
    private val commandReceiverInstalled = AtomicBoolean(false)
    @Volatile
    private var currentProcessName: String = ""
    @Volatile
    private var controlMessenger: Messenger? = null
    @Volatile
    private var controlTemplateMsg: Message? = null
    @Volatile
    private var registeredContext: Context? = null
    @Volatile
    private var lastObservedSubId: Int = -1
    @Volatile
    private var lastObservedSlotId: Int = -1
    @Volatile
    private var lastObservedKeyInt: Int = -1
    private val pendingReplyHandlers = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Handler, Boolean>()
    )

    private fun prettyBands(arr: ByteArray?): String {
        if (arr == null || arr.isEmpty()) return "[]"
        return parseNrBandsFromBytes(arr).joinToString(prefix = "[", postfix = "]")
    }

    private fun getIntSafe(b: android.os.Bundle?, key: String, def: Int = -1): Int =
        runCatching { b?.getInt(key, def) ?: def }.getOrElse { def }

    private fun getByteArraySafe(b: android.os.Bundle?, key: String): ByteArray? =
        runCatching { b?.getByteArray(key) }.getOrNull()

    // 可调：set 后多长时间内我们认为“读回应该匹配期望”



    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val isEngineerProc = lpparam.packageName == ENGINEER_PKG
        // 工程模式经常有 :plugin / :remote 之类的进程，建议都观察
        val isPhoneProc = lpparam.packageName == PHONE_PKG
        if (!isEngineerProc && !isPhoneProc) return
        currentProcessName = lpparam.processName

        // 你自己的总开关
        if (!Logger.LOG_ENABLED) {
            hookAttachAndRegisterCommandReceiver(lpparam)
            hookMessengerSend(lpparam)
            return
        }

        Logger.log("[OBS] injected pkg=${lpparam.packageName} proc=${lpparam.processName} cl=${Logger.clTag(lpparam.classLoader)} rev=$BAND_LOCK_TRACE_REV")
        hookAttachAndRegisterCommandReceiver(lpparam)
        if (isPhoneProc) {
            Logger.log("[OBS] phone process bridge enabled (command + band protocol trace)")
            hookServiceManagerSubsysRadio(lpparam)
            hookBinderProxyTransactOnlyTargets(lpparam)
            hookMessengerSend(lpparam)
            hookHandlerInbound(lpparam)
            return
        }

        hookRuntimeExec(lpparam)
        hookProcessBuilder(lpparam)
        hookSystemProperties(lpparam)
        hookServiceManagerSubsysRadio(lpparam)
        hookBinderProxyTransactOnlyTargets(lpparam)
        hookMessengerSend(lpparam)
        hookHandlerInbound(lpparam)
    }

    private fun hookAttachAndRegisterCommandReceiver(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedBridge.hookAllMethodsNative(Application::class.java, "attach") { chain ->
                val result = chain.proceed()
                val ctx = chain.args.getOrNull(0) as? Context
                val shouldRegisterCommandReceiver = ctx != null && (
                    ctx.packageName == PHONE_PKG ||
                        (ctx.packageName == ENGINEER_PKG && currentProcessName.contains(":plugin"))
                    )
                if (shouldRegisterCommandReceiver) {
                    if (commandReceiverInstalled.compareAndSet(false, true)) {
                        registerBandLockCommandReceiver(ctx!!.applicationContext ?: ctx)
                    }
                }
                result
            }
            Logger.log("[OBS] hooked Application.attach for band-lock command receiver")
        }.onFailure {
            Logger.logE("[OBS] hook attach for band-lock receiver failed", it)
        }
    }

    private fun registerBandLockCommandReceiver(ctx: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Config.ACTION_ENGINEER_BAND_LOCK_COMMAND -> handleBandLockCommand(ctx, intent)
                    Config.ACTION_ENGINEER_BAND_LOCK_QUERY -> handleBandLockQuery(ctx, intent)
                    Config.ACTION_ENGINEER_BAND_MODE_COMMAND -> handleBandModeCommand(ctx, intent)
                }
            }
        }
        registeredContext = ctx
        val filter = IntentFilter().apply {
            addAction(Config.ACTION_ENGINEER_BAND_LOCK_COMMAND)
            addAction(Config.ACTION_ENGINEER_BAND_LOCK_QUERY)
            addAction(Config.ACTION_ENGINEER_BAND_MODE_COMMAND)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            ctx.registerReceiver(receiver, filter)
        }
        Logger.log("[OBS] band-lock command receiver registered in proc=$currentProcessName")
    }

    private fun handleBandLockCommand(ctx: Context, intent: Intent) {
        Logger.log("[OBS][cmd] received in proc=$currentProcessName extras=${intent.extras?.keySet()}")
        val unlock = intent.getBooleanExtra(Config.EXTRA_BAND_LOCK_UNLOCK, false)
        val rawBands = intent.getIntArrayExtra(Config.EXTRA_BAND_LOCK_BANDS) ?: intArrayOf()
        val normalized = rawBands.asSequence()
            .mapNotNull { normalizeBandForProtocol(it) }
            .distinctBy { it.encoded }
            .sortedBy { it.display }
            .toList()
        val bandBytes = if (unlock) {
            byteArrayOf()
        } else {
            normalized.map { it.encoded.toByte() }.toByteArray()
        }

        val subId = intent.takeIf { it.hasExtra(Config.EXTRA_BAND_LOCK_SUB_ID) }
            ?.getIntExtra(Config.EXTRA_BAND_LOCK_SUB_ID, -1)
        val slotId = intent.takeIf { it.hasExtra(Config.EXTRA_BAND_LOCK_SLOT_ID) }
            ?.getIntExtra(Config.EXTRA_BAND_LOCK_SLOT_ID, -1)
        val keyInt = intent.takeIf { it.hasExtra(Config.EXTRA_BAND_LOCK_KEY_INT) }
            ?.getIntExtra(Config.EXTRA_BAND_LOCK_KEY_INT, -1)

        Logger.log(
            "[OBS][cmd] parsed unlock=$unlock rawBands=${rawBands.joinToString(prefix = "[", postfix = "]")} " +
                "normalized=${normalized.joinToString(prefix = "[", postfix = "]")} bandHex=${bandBytes.toHex()} " +
                "subIdExtra=${subId ?: "auto"} slotIdExtra=${slotId ?: "auto"} keyIntExtra=${keyInt ?: "auto"}"
        )

        val (ok, message) = dispatchBandLockCommand(
            ctx = ctx,
            bytes = bandBytes,
            subIdOverride = subId?.takeIf { it >= 0 },
            slotIdOverride = slotId?.takeIf { it >= -1 },
            keyIntOverride = keyInt?.takeIf { it >= 0 }
        )

        val showBands = if (unlock) "UNLOCK" else normalized.joinToString(prefix = "[", postfix = "]") { "N${it.display}" }
        Logger.log("[OBS][cmd] band-lock unlock=$unlock bands=$showBands ok=$ok msg=$message")
        emitBandLockResult(ctx = ctx, ok = ok, message = message, bandsHex = bandBytes.toHex())
    }

    private data class EncodedBand(val display: Int, val encoded: Int)

    private fun normalizeBandForProtocol(band: Int): EncodedBand? {
        if (band <= 0) return null
        val encoded = band and 0xff
        if (encoded == 0) return null
        return EncodedBand(display = band, encoded = encoded)
    }

    private fun handleBandLockQuery(ctx: Context, intent: Intent) {
        Logger.log("[OBS][query] received in proc=$currentProcessName extras=${intent.extras?.keySet()}")
        val subId = intent.takeIf { it.hasExtra(Config.EXTRA_BAND_LOCK_SUB_ID) }
            ?.getIntExtra(Config.EXTRA_BAND_LOCK_SUB_ID, -1)
        val slotId = intent.takeIf { it.hasExtra(Config.EXTRA_BAND_LOCK_SLOT_ID) }
            ?.getIntExtra(Config.EXTRA_BAND_LOCK_SLOT_ID, -1)
        val keyInt = intent.takeIf { it.hasExtra(Config.EXTRA_BAND_LOCK_KEY_INT) }
            ?.getIntExtra(Config.EXTRA_BAND_LOCK_KEY_INT, -1)
        val args = resolveBandLockArgs(
            subIdOverride = subId?.takeIf { it >= 0 },
            slotIdOverride = slotId?.takeIf { it >= -1 },
            keyIntOverride = keyInt?.takeIf { it >= 0 }
        )
        val result = dispatchBandLockQuery(ctx, args)
        if (!result.first) {
            emitBandLockStateResult(
                ctx = ctx,
                ok = false,
                resultCode = -1,
                bytes = null,
                args = args,
                message = result.second
            )
        }
    }

    private data class BandLockArgs(
        val subId: Int,
        val slotId: Int,
        val keyInt: Int
    )

    private fun handleBandModeCommand(ctx: Context, intent: Intent) {
        val modeKeyInt = intent.getIntExtra(Config.EXTRA_BAND_MODE_KEY_INT, -1)
        Logger.log("[OBS][mode] received in proc=$currentProcessName modeKeyInt=$modeKeyInt extras=${intent.extras?.keySet()}")
        if (modeKeyInt < 0) {
            emitBandLockResult(ctx, false, "invalid band mode keyInt=$modeKeyInt", "")
            return
        }
        val subId = intent.takeIf { it.hasExtra(Config.EXTRA_BAND_LOCK_SUB_ID) }
            ?.getIntExtra(Config.EXTRA_BAND_LOCK_SUB_ID, -1)
        val slotId = intent.takeIf { it.hasExtra(Config.EXTRA_BAND_LOCK_SLOT_ID) }
            ?.getIntExtra(Config.EXTRA_BAND_LOCK_SLOT_ID, -1)
        val args = resolveBandLockArgs(
            subIdOverride = subId?.takeIf { it >= 0 },
            slotIdOverride = slotId?.takeIf { it >= -1 },
            keyIntOverride = modeKeyInt
        )
        val result = dispatchBandModeCommand(ctx, args.copy(keyInt = modeKeyInt))
        Logger.log("[OBS][mode] band-mode keyInt=$modeKeyInt ok=${result.first} msg=${result.second}")
        emitBandLockResult(ctx, result.first, result.second, "")
    }

    private fun dispatchBandLockCommand(
        ctx: Context,
        bytes: ByteArray,
        subIdOverride: Int?,
        slotIdOverride: Int?,
        keyIntOverride: Int?
    ): Pair<Boolean, String> {
        val args = resolveBandLockArgs(subIdOverride, slotIdOverride, keyIntOverride)
        Logger.log(
            "[OBS][dispatch] resolved subId=${args.subId} slotId=${args.slotId} keyInt=${args.keyInt} " +
                "bandHex=${bytes.toHex()} bands=${prettyBands(bytes)}"
        )

        // Preferred path: directly call target API, avoid depending on engineer UI flow.
        val direct = dispatchBandLockViaRadioManager(ctx, args, bytes)
        if (direct.first) {
            rememberExpected(args.subId, bytes)
            Logger.log("[OBS][dispatch] direct path success: ${direct.second}")
            return direct
        }
        Logger.log("[OBS][dispatch] direct path failed: ${direct.second}; trying messenger fallback")

        val fallback = dispatchBandLockViaMessenger(args, bytes)
        if (fallback.first) {
            Logger.log("[OBS][dispatch] messenger fallback success: ${fallback.second}")
            return fallback
        }
        Logger.log("[OBS][dispatch] messenger fallback failed: ${fallback.second}")

        return false to "direct failed: ${direct.second}; fallback failed: ${fallback.second}"
    }

    private fun dispatchBandLockQuery(
        ctx: Context,
        args: BandLockArgs
    ): Pair<Boolean, String> {
        Logger.log("[OBS][query] resolved subId=${args.subId} slotId=${args.slotId} keyInt=${args.keyInt}")
        val subsys = dispatchBandLockQueryViaSubsysRadioMessenger(ctx, args)
        if (subsys.first) {
            Logger.log("[OBS][query] subsys messenger path sent: ${subsys.second}")
            return subsys
        }
        Logger.log("[OBS][query] subsys messenger path failed: ${subsys.second}; trying RadioManager")

        val direct = dispatchBandLockQueryViaRadioManager(ctx, args)
        if (direct.first) {
            Logger.log("[OBS][query] direct path sent: ${direct.second}")
            return direct
        }
        Logger.log("[OBS][query] direct path failed: ${direct.second}; trying messenger fallback")

        val fallback = dispatchBandLockQueryViaMessenger(args)
        if (fallback.first) {
            Logger.log("[OBS][query] messenger fallback sent: ${fallback.second}")
            return fallback
        }
        Logger.log("[OBS][query] messenger fallback failed: ${fallback.second}")
        return false to "direct failed: ${direct.second}; fallback failed: ${fallback.second}"
    }

    private fun dispatchBandModeCommand(
        ctx: Context,
        args: BandLockArgs
    ): Pair<Boolean, String> {
        val direct = dispatchBandModeViaRadioManager(ctx, args)
        if (direct.first) {
            Logger.log("[OBS][mode] direct path success: ${direct.second}")
            return direct
        }
        Logger.log("[OBS][mode] direct path failed: ${direct.second}; trying messenger fallback")

        val fallback = dispatchBandModeViaMessenger(args)
        if (fallback.first) {
            Logger.log("[OBS][mode] messenger fallback success: ${fallback.second}")
            return fallback
        }
        return false to "direct failed: ${direct.second}; fallback failed: ${fallback.second}"
    }

    private fun resolveBandLockArgs(
        subIdOverride: Int?,
        slotIdOverride: Int?,
        keyIntOverride: Int?
    ): BandLockArgs {
        val templateData = controlTemplateMsg?.data
        val subIdSource = when {
            subIdOverride != null -> "extra"
            templateData?.getInt("subId", -1)?.takeIf { it >= 0 } != null -> "template"
            lastObservedSubId >= 0 -> "observed"
            else -> "default"
        }
        val slotIdSource = when {
            slotIdOverride != null -> "extra"
            templateData?.getInt("slotId", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE } != null -> "template"
            lastObservedSlotId != -1 -> "observed"
            else -> "default"
        }
        val keyIntSource = when {
            keyIntOverride != null -> "extra"
            templateData?.getInt("keyInt", -1)?.takeIf { it >= 0 } != null -> "template"
            lastObservedKeyInt >= 0 -> "observed"
            else -> "default"
        }
        val subId = subIdOverride
            ?: templateData?.getInt("subId", -1)?.takeIf { it >= 0 }
            ?: lastObservedSubId.takeIf { it >= 0 }
            ?: 1
        val slotId = slotIdOverride
            ?: templateData?.getInt("slotId", Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
            ?: if (lastObservedSlotId != -1) lastObservedSlotId else -1
        val keyInt = keyIntOverride
            ?: templateData?.getInt("keyInt", -1)?.takeIf { it >= 0 }
            ?: lastObservedKeyInt.takeIf { it >= 0 }
            ?: 5
        Logger.log(
            "[OBS][args] subId=$subId($subIdSource) slotId=$slotId($slotIdSource) keyInt=$keyInt($keyIntSource) " +
                "templateKeys=${templateData?.keySet()} observedSubId=$lastObservedSubId observedSlotId=$lastObservedSlotId observedKeyInt=$lastObservedKeyInt"
        )
        return BandLockArgs(subId = subId, slotId = slotId, keyInt = keyInt)
    }

    private fun dispatchBandLockViaRadioManager(
        ctx: Context,
        args: BandLockArgs,
        bytes: ByteArray
    ): Pair<Boolean, String> {
        val cl = ctx.classLoader ?: return false to "no classLoader"
        val rmClass = runCatching {
            Class.forName("com.oplus.telephony.RadioManager", false, cl)
        }.getOrNull() ?: return false to "RadioManager class not found"
        Logger.log("[OBS][direct] RadioManager found classLoader=${Logger.clTag(rmClass.classLoader)}")

        val rmInstance = obtainRadioManagerInstance(rmClass, ctx)
            ?: return false to "RadioManager instance unavailable"
        Logger.log("[OBS][direct] RadioManager instance=${rmInstance.javaClass.name}@${Integer.toHexString(System.identityHashCode(rmInstance))}")

        val method = radioManagerMethods(rmClass).firstOrNull { m ->
            m.name == "setBandPrefer" && isSupportedSetBandPreferSignature(m.parameterTypes)
        } ?: return false to "supported setBandPrefer signature not found; candidates=${describeRadioManagerMethods(rmClass, "setBandPrefer")}"
        Logger.log(
            "[OBS][direct] method=${method.declaringClass.name}.${method.name}(" +
                method.parameterTypes.joinToString { it.simpleName } +
                ") return=${method.returnType.simpleName}"
        )

        val invokeArgs = buildSetBandPreferInvokeArgs(method.parameterTypes, args, bytes)
            ?: return false to "unsupported setBandPrefer signature: ${method.parameterTypes.joinToString { it.name }}"
        Logger.log(
            "[OBS][direct] invoke setBandPrefer args=" +
                invokeArgs.joinToString(prefix = "[", postfix = "]") { arg ->
                    when (arg) {
                        is ByteArray -> "bytes(len=${arg.size},hex=${arg.toHex()},bands=${prettyBands(arg)})"
                        is Message -> "Message(what=${arg.what},arg1=${arg.arg1},arg2=${arg.arg2},data=${describeBundle(arg.data)})"
                        else -> arg.toString()
                    }
                }
        )

        return runCatching {
            method.isAccessible = true
            val ret = method.invoke(rmInstance, *invokeArgs)
            val reboot = dispatchRequireModemRebootViaRadioManager(rmClass, rmInstance, args)
            scheduleBandLockFactReadback(ctx, args)
            true to "direct setBandPrefer invoked (subId=${args.subId}, slotId=${args.slotId}, keyInt=${args.keyInt}, ret=${ret ?: "void"}, reboot=${reboot.second}, readback=scheduled)"
        }.getOrElse {
            Logger.logE("[OBS][direct] setBandPrefer invoke failed", it)
            false to "direct invoke failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun dispatchBandModeViaRadioManager(
        ctx: Context,
        args: BandLockArgs
    ): Pair<Boolean, String> {
        val cl = ctx.classLoader ?: return false to "no classLoader"
        val rmClass = runCatching {
            Class.forName("com.oplus.telephony.RadioManager", false, cl)
        }.getOrNull() ?: return false to "RadioManager class not found"
        val rmInstance = obtainRadioManagerInstance(rmClass, ctx)
            ?: return false to "RadioManager instance unavailable"
        val method = radioManagerMethods(rmClass).firstOrNull { m ->
            if (m.name != "setBandMode") return@firstOrNull false
            val intCount = m.parameterTypes.count { it == Int::class.javaPrimitiveType || it == Int::class.javaObjectType }
            val messageCount = m.parameterTypes.count { Message::class.java.isAssignableFrom(it) }
            intCount >= 1 && messageCount == 1 &&
                m.parameterTypes.all {
                    it == Int::class.javaPrimitiveType ||
                        it == Int::class.javaObjectType ||
                        Message::class.java.isAssignableFrom(it)
                }
        } ?: return false to "setBandMode(int,Message) not found; candidates=${describeRadioManagerMethods(rmClass, "setBandMode")}"

        return runCatching {
            method.isAccessible = true
            val invokeArgs = buildSetBandModeInvokeArgs(method.parameterTypes, args)
            Logger.log(
                "[OBS][direct] invoke setBandMode args=" +
                    invokeArgs.joinToString(prefix = "[", postfix = "]") { arg ->
                        when (arg) {
                            is Message -> "Message(what=${arg.what},arg1=${arg.arg1},arg2=${arg.arg2},data=${describeBundle(arg.data)})"
                            else -> arg.toString()
                        }
                    }
            )
            val ret = method.invoke(rmInstance, *invokeArgs)
            true to "direct setBandMode invoked (modeKeyInt=${args.keyInt}, subId=${args.subId}, slotId=${args.slotId}, ret=${ret ?: "void"})"
        }.getOrElse {
            Logger.logE("[OBS][direct] setBandMode invoke failed", it)
            false to "setBandMode failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun dispatchBandLockQueryViaRadioManager(
        ctx: Context,
        args: BandLockArgs
    ): Pair<Boolean, String> {
        val cl = ctx.classLoader ?: return false to "no classLoader"
        val rmClass = runCatching {
            Class.forName("com.oplus.telephony.RadioManager", false, cl)
        }.getOrNull() ?: return false to "RadioManager class not found"

        val rmInstance = obtainRadioManagerInstance(rmClass, ctx)
            ?: return false to "RadioManager instance unavailable"
        val method = radioManagerMethods(rmClass).firstOrNull { m ->
            if (m.name != "getBandPrefer") return@firstOrNull false
            val intCount = m.parameterTypes.count { it == Int::class.javaPrimitiveType || it == Int::class.javaObjectType }
            val messageCount = m.parameterTypes.count { Message::class.java.isAssignableFrom(it) }
            intCount >= 1 && messageCount == 1 &&
                m.parameterTypes.all {
                    it == Int::class.javaPrimitiveType ||
                        it == Int::class.javaObjectType ||
                        Message::class.java.isAssignableFrom(it)
                }
        } ?: return false to "getBandPrefer(int,Message) not found; candidates=${describeRadioManagerMethods(rmClass, "getBandPrefer")}"

        return runCatching {
            method.isAccessible = true
            val invokeArgs = buildGetBandPreferInvokeArgs(method.parameterTypes, args)
            Logger.log(
                "[OBS][direct] invoke getBandPrefer args=" +
                    invokeArgs.joinToString(prefix = "[", postfix = "]") { arg ->
                        when (arg) {
                            is Message -> "Message(what=${arg.what},arg1=${arg.arg1},arg2=${arg.arg2},data=${describeBundle(arg.data)})"
                            else -> arg.toString()
                        }
                    }
            )
            val ret = method.invoke(rmInstance, *invokeArgs)
            true to "direct getBandPrefer invoked (subId=${args.subId}, slotId=${args.slotId}, keyInt=${args.keyInt}, ret=${ret ?: "void"})"
        }.getOrElse {
            Logger.logE("[OBS][direct] getBandPrefer invoke failed", it)
            false to "getBandPrefer failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun dispatchBandLockQueryViaSubsysRadioMessenger(
        ctx: Context,
        args: BandLockArgs
    ): Pair<Boolean, String> {
        val messenger = obtainSubsysRadioMessenger(ctx)
            ?: return false to "ISubsysRadio messenger unavailable"
        return runCatching {
            val replyTo = buildFactReplyMessenger(ctx, args, "subsys")
            val restore = buildSimpleSubsysMessage(
                what = MSG_RESTORE_NV_BACKUP_ALLOWED,
                bundleWhat = BUNDLE_WHAT_RESTORE_NV_BACKUP_ALLOWED,
                subId = -1,
                slotId = -1,
                replyTo = replyTo
            )
            val available = buildSimpleSubsysMessage(
                what = MSG_GET_AVAILABLE_BAND_MODES,
                bundleWhat = BUNDLE_WHAT_GET_AVAILABLE_BAND_MODES,
                subId = -1,
                slotId = -1,
                replyTo = replyTo
            )
            val query = buildGetBandPreferMessage(template = null, args = args).apply {
                this.replyTo = replyTo
            }
            Logger.log("[OBS][subsys] send warmup restore data=${describeBundle(restore.data)}")
            messenger.send(restore)
            Logger.log("[OBS][subsys] send warmup available data=${describeBundle(available.data)}")
            messenger.send(available)
            Logger.log("[OBS][subsys] send getBandPrefer data=${describeBundle(query.data)} replyTo=${query.replyTo != null}")
            messenger.send(query)
            true to "ISubsysRadio messenger sent warmup+getBandPrefer"
        }.getOrElse {
            Logger.logE("[OBS][subsys] getBandPrefer send failed", it)
            false to "ISubsysRadio messenger send failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun obtainSubsysRadioMessenger(ctx: Context): Messenger? {
        val binder = runCatching {
            val sm = Class.forName("android.os.ServiceManager")
            val method = sm.getDeclaredMethod("getService", String::class.java).apply { isAccessible = true }
            method.invoke(null, "ISubsysRadio") as? IBinder
        }.onFailure {
            Logger.logE("[OBS][subsys] ServiceManager.getService failed", it)
        }.getOrNull() ?: return null

        targetBinders.add(System.identityHashCode(binder))
        Logger.log(
            "[OBS][subsys] binder=${binder.javaClass.name}@${Integer.toHexString(System.identityHashCode(binder))} " +
                "desc=${runCatching { binder.interfaceDescriptor }.getOrNull()}"
        )

        obtainSubsysMessengerViaStub(ctx, binder)?.let { return it }
        return obtainSubsysMessengerViaRawTransact(binder)
    }

    private fun obtainSubsysMessengerViaStub(ctx: Context, binder: IBinder): Messenger? {
        val loaders = listOfNotNull(ctx.classLoader, ClassLoader.getSystemClassLoader())
        for (loader in loaders) {
            val stubClass = runCatching {
                Class.forName("com.oplus.telephony.ISubsysRadio\$Stub", false, loader)
            }.getOrNull() ?: continue
            val iface = runCatching {
                val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java).apply { isAccessible = true }
                asInterface.invoke(null, binder)
            }.onFailure {
                Logger.logE("[OBS][subsys] ISubsysRadio.Stub.asInterface failed", it)
            }.getOrNull() ?: continue

            val methods = (iface.javaClass.methods.asList() + iface.javaClass.declaredMethods.asList()).distinctBy {
                it.name + "#" + it.parameterTypes.joinToString(",") { pt -> pt.name }
            }
            Logger.log(
                "[OBS][subsys] ISubsysRadio iface=${iface.javaClass.name} methods=" +
                    methods.filter { it.parameterTypes.isEmpty() }
                        .joinToString(prefix = "[", postfix = "]") { "${it.name}():${it.returnType.simpleName}" }
            )
            val method = methods.firstOrNull {
                it.parameterTypes.isEmpty() &&
                    (Messenger::class.java.isAssignableFrom(it.returnType) ||
                        IBinder::class.java.isAssignableFrom(it.returnType) ||
                        it.name.contains("messenger", ignoreCase = true))
            } ?: continue
            val value = runCatching {
                method.isAccessible = true
                method.invoke(iface)
            }.onFailure {
                Logger.logE("[OBS][subsys] invoke ${method.name} failed", it)
            }.getOrNull()
            when (value) {
                is Messenger -> {
                    Logger.log("[OBS][subsys] messenger obtained via Stub.${method.name}(): Messenger")
                    return value
                }
                is IBinder -> {
                    Logger.log("[OBS][subsys] messenger obtained via Stub.${method.name}(): IBinder")
                    return Messenger(value)
                }
            }
        }
        Logger.log("[OBS][subsys] ISubsysRadio Stub path unavailable")
        return null
    }

    private fun obtainSubsysMessengerViaRawTransact(binder: IBinder): Messenger? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return runCatching {
            data.writeInterfaceToken("com.oplus.telephony.ISubsysRadio")
            val ok = binder.transact(1, data, reply, 0)
            val replyLen = runCatching { reply.dataSize() }.getOrDefault(-1)
            Logger.log("[OBS][subsys] raw transact getMessenger ok=$ok replyLen=$replyLen")
            if (!ok || replyLen <= 0) return@runCatching null
            reply.setDataPosition(0)
            runCatching { reply.readException() }
            val afterException = reply.dataPosition()
            runCatching {
                val present = reply.readInt()
                if (present != 0) Messenger.CREATOR.createFromParcel(reply) else null
            }.getOrNull()
                ?: run {
                    reply.setDataPosition(afterException)
                    runCatching { reply.readStrongBinder()?.let { Messenger(it) } }.getOrNull()
                }
        }.onFailure {
            Logger.logE("[OBS][subsys] raw transact getMessenger failed", it)
        }.getOrNull().also {
            data.recycle()
            reply.recycle()
        }
    }

    private fun isSupportedSetBandPreferSignature(types: Array<Class<*>>): Boolean {
        val intCount = types.count { it == Int::class.javaPrimitiveType || it == Int::class.javaObjectType }
        val byteArrayCount = types.count { it == ByteArray::class.java }
        val messageCount = types.count { Message::class.java.isAssignableFrom(it) }
        return (types.size == 4 && intCount == 3 && byteArrayCount == 1) ||
            (types.size == 3 && intCount == 1 && byteArrayCount == 1 && messageCount == 1)
    }

    private fun buildSetBandPreferInvokeArgs(
        types: Array<Class<*>>,
        args: BandLockArgs,
        bytes: ByteArray
    ): Array<Any?>? {
        val intCount = types.count { it == Int::class.javaPrimitiveType || it == Int::class.javaObjectType }
        val ints = if (intCount == 3) {
            intArrayOf(args.subId, args.slotId, args.keyInt)
        } else {
            intArrayOf(args.keyInt)
        }
        var intIdx = 0
        val callback = buildRadioManagerCallbackMessage(
            what = BUNDLE_WHAT_SET_BAND_PREFER,
            args = args,
            includeKey = true,
            bytes = bytes,
            includeKeyByteArray = true
        )
        return Array(types.size) { idx ->
            val pt = types[idx]
            when {
                pt == Int::class.javaPrimitiveType || pt == Int::class.javaObjectType -> ints.getOrNull(intIdx++) ?: return null
                pt == ByteArray::class.java -> bytes
                Message::class.java.isAssignableFrom(pt) -> callback
                else -> return null
            }
        }
    }

    private fun dispatchRequireModemRebootViaRadioManager(
        rmClass: Class<*>,
        rmInstance: Any,
        args: BandLockArgs
    ): Pair<Boolean, String> {
        val method = radioManagerMethods(rmClass).firstOrNull { m ->
            if (m.name != "requireModemReboot") return@firstOrNull false
            m.parameterTypes.all {
                it == Int::class.javaPrimitiveType ||
                    it == Int::class.javaObjectType ||
                    Message::class.java.isAssignableFrom(it)
            }
        } ?: return false to "requireModemReboot(int,int) not found; candidates=${describeRadioManagerMethods(rmClass, "requireModemReboot")}"

        return runCatching {
            method.isAccessible = true
            val invokeArgs = buildRequireModemRebootInvokeArgs(method.parameterTypes, args)
            val ret = method.invoke(rmInstance, *invokeArgs)
            true to "requireModemReboot invoked (subId=${args.subId}, slotId=${args.slotId}, ret=${ret ?: "void"})"
        }.getOrElse {
            Logger.logE("[OBS][direct] requireModemReboot invoke failed", it)
            false to "requireModemReboot failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun buildRequireModemRebootInvokeArgs(
        types: Array<Class<*>>,
        args: BandLockArgs
    ): Array<Any?> {
        val intValues = if (types.count { it == Int::class.javaPrimitiveType || it == Int::class.javaObjectType } >= 2) {
            intArrayOf(args.subId, args.slotId)
        } else {
            intArrayOf(args.subId)
        }
        var intIdx = 0
        val callback = buildRadioManagerCallbackMessage(
            what = BUNDLE_WHAT_REQUIRE_MODEM_REBOOT,
            args = args,
            includeKey = false,
            bytes = null
        )
        return Array(types.size) { idx ->
            val pt = types[idx]
            when {
                pt == Int::class.javaPrimitiveType || pt == Int::class.javaObjectType -> intValues.getOrElse(intIdx++) { args.subId }
                Message::class.java.isAssignableFrom(pt) -> callback
                else -> null
            }
        }
    }

    private fun buildRadioManagerCallbackMessage(
        what: Int,
        args: BandLockArgs,
        includeKey: Boolean,
        bytes: ByteArray?,
        includeKeyByteArray: Boolean = false
    ): Message {
        val callbackHandler = android.os.Handler(android.os.Looper.getMainLooper())
        return Message.obtain(callbackHandler).apply {
            this.what = what
            this.arg1 = android.os.Process.myPid()
            this.arg2 = 0
            data = Bundle().apply {
                putInt("subId", args.subId)
                putInt("slotId", args.slotId)
                putInt("what", what)
                if (includeKey) {
                    putInt("keyInt", args.keyInt)
                    if (includeKeyByteArray) {
                        putByteArray("keyByteArray", bytes ?: byteArrayOf())
                    }
                }
            }
        }
    }

    private fun buildGetBandPreferInvokeArgs(
        types: Array<Class<*>>,
        args: BandLockArgs
    ): Array<Any?> {
        val intCount = types.count { it == Int::class.javaPrimitiveType || it == Int::class.javaObjectType }
        val ints = if (intCount >= 3) {
            intArrayOf(args.subId, args.slotId, args.keyInt)
        } else {
            intArrayOf(args.keyInt)
        }
        var intIdx = 0
        val callback = buildRadioManagerCallbackMessage(
            what = bundleWhatGetBandPrefer(args.keyInt),
            args = args,
            includeKey = true,
            bytes = null
        )
        return Array(types.size) { idx ->
            val pt = types[idx]
            when {
                pt == Int::class.javaPrimitiveType || pt == Int::class.javaObjectType -> ints.getOrElse(intIdx++) { args.keyInt }
                Message::class.java.isAssignableFrom(pt) -> callback
                else -> null
            }
        }
    }

    private fun buildSetBandModeInvokeArgs(
        types: Array<Class<*>>,
        args: BandLockArgs
    ): Array<Any?> {
        val intCount = types.count { it == Int::class.javaPrimitiveType || it == Int::class.javaObjectType }
        val ints = if (intCount >= 3) {
            intArrayOf(args.subId, args.slotId, args.keyInt)
        } else {
            intArrayOf(args.keyInt)
        }
        var intIdx = 0
        val callback = buildRadioManagerCallbackMessage(
            what = BUNDLE_WHAT_SET_BAND_MODE,
            args = args,
            includeKey = true,
            bytes = null
        )
        return Array(types.size) { idx ->
            val pt = types[idx]
            when {
                pt == Int::class.javaPrimitiveType || pt == Int::class.javaObjectType -> ints.getOrElse(intIdx++) { args.keyInt }
                Message::class.java.isAssignableFrom(pt) -> callback
                else -> null
            }
        }
    }

    private fun radioManagerMethods(rmClass: Class<*>): List<java.lang.reflect.Method> =
        (rmClass.methods.asList() + rmClass.declaredMethods.asList()).distinctBy {
            it.name + "#" + it.parameterTypes.joinToString(",") { pt -> pt.name }
        }

    private fun describeRadioManagerMethods(rmClass: Class<*>, name: String): String =
        radioManagerMethods(rmClass)
            .filter { it.name == name }
            .joinToString(prefix = "[", postfix = "]") { m ->
                m.parameterTypes.joinToString(prefix = "${m.name}(", postfix = ")") { it.simpleName }
            }
            .ifBlank { "[]" }

    private fun obtainRadioManagerInstance(rmClass: Class<*>, ctx: Context): Any? {
        fun invokeStatic(m: java.lang.reflect.Method): Any? = runCatching {
            m.isAccessible = true
            m.invoke(null, *if (m.parameterTypes.isEmpty()) emptyArray() else arrayOf(ctx))
        }.getOrNull()

        val preferredNames = listOf("getInstance", "getDefault", "getDefaultInstance", "from")
        for (name in preferredNames) {
            val method = rmClass.methods.firstOrNull { m ->
                m.name == name &&
                    Modifier.isStatic(m.modifiers) &&
                    rmClass.isAssignableFrom(m.returnType) &&
                    m.parameterTypes.size == 1 &&
                    Context::class.java.isAssignableFrom(m.parameterTypes[0])
            }
            if (method != null) {
                val inst = invokeStatic(method)
                if (inst != null) {
                    Logger.log("[OBS][direct] RadioManager obtained via static ${method.name}(Context)")
                    return inst
                }
            }
        }

        val anyStaticWithContext = rmClass.methods.firstOrNull { m ->
            Modifier.isStatic(m.modifiers) &&
                rmClass.isAssignableFrom(m.returnType) &&
                m.parameterTypes.size == 1 &&
                Context::class.java.isAssignableFrom(m.parameterTypes[0])
        }
        if (anyStaticWithContext != null) {
            val inst = invokeStatic(anyStaticWithContext)
            if (inst != null) {
                Logger.log("[OBS][direct] RadioManager obtained via static ${anyStaticWithContext.name}(Context)")
                return inst
            }
        }

        val anyStaticNoArg = rmClass.methods.firstOrNull { m ->
            Modifier.isStatic(m.modifiers) &&
                rmClass.isAssignableFrom(m.returnType) &&
                m.parameterTypes.isEmpty()
        }
        if (anyStaticNoArg != null) {
            val inst = runCatching {
                anyStaticNoArg.isAccessible = true
                anyStaticNoArg.invoke(null)
            }.getOrNull()
            if (inst != null) {
                Logger.log("[OBS][direct] RadioManager obtained via static ${anyStaticNoArg.name}()")
                return inst
            }
        }

        val ctor = rmClass.declaredConstructors.firstOrNull { c ->
            c.parameterTypes.size == 1 &&
                Context::class.java.isAssignableFrom(c.parameterTypes[0])
        }
        if (ctor != null) {
            val inst = runCatching {
                ctor.isAccessible = true
                ctor.newInstance(ctx)
            }.getOrNull()
            if (inst != null) {
                Logger.log("[OBS][direct] RadioManager obtained via constructor(Context)")
                return inst
            }
        }
        return null
    }

    private fun dispatchBandLockViaMessenger(
        args: BandLockArgs,
        bytes: ByteArray
    ): Pair<Boolean, String> {
        val messenger = controlMessenger ?: return false to "no captured control messenger"
        val template = controlTemplateMsg
        return runCatching {
            val setMsg = buildSetBandPreferMessage(template, bytes, args)
            Logger.log(
                "[OBS][fallback] Messenger.send what=${setMsg.what} arg1=${setMsg.arg1} arg2=${setMsg.arg2} " +
                    "replyTo=${setMsg.replyTo != null} data=${describeBundle(setMsg.data)} " +
                    "messenger=${messenger.javaClass.name}@${Integer.toHexString(System.identityHashCode(messenger))}"
            )
            messenger.send(setMsg)
            val resetMsg = buildRequireModemRebootMessage(template, args)
            Logger.log(
                "[OBS][fallback] Messenger.send reset what=${resetMsg.what} arg1=${resetMsg.arg1} arg2=${resetMsg.arg2} " +
                    "replyTo=${resetMsg.replyTo != null} data=${describeBundle(resetMsg.data)}"
            )
            messenger.send(resetMsg)
            rememberExpected(args.subId, bytes)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                dispatchBandLockQueryViaMessenger(args)
            }, 500L)
            true to "fallback messenger sent setBandPrefer+requireModemReboot (subId=${args.subId}, slotId=${args.slotId}, keyInt=${args.keyInt})"
        }.getOrElse {
            Logger.logE("[OBS][fallback] messenger send failed", it)
            false to "messenger send failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun dispatchBandLockQueryViaMessenger(
        args: BandLockArgs
    ): Pair<Boolean, String> {
        val messenger = controlMessenger ?: return false to "no captured control messenger"
        val template = controlTemplateMsg
        return runCatching {
            val queryMsg = buildGetBandPreferMessage(template, args)
            Logger.log(
                "[OBS][fallback] Messenger.send query what=${queryMsg.what} arg1=${queryMsg.arg1} arg2=${queryMsg.arg2} " +
                    "replyTo=${queryMsg.replyTo != null} data=${describeBundle(queryMsg.data)}"
            )
            messenger.send(queryMsg)
            true to "fallback messenger sent getBandPrefer (subId=${args.subId}, slotId=${args.slotId}, keyInt=${args.keyInt})"
        }.getOrElse {
            Logger.logE("[OBS][fallback] getBandPrefer messenger send failed", it)
            false to "getBandPrefer messenger send failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun dispatchBandModeViaMessenger(
        args: BandLockArgs
    ): Pair<Boolean, String> {
        val messenger = controlMessenger ?: return false to "no captured control messenger"
        val template = controlTemplateMsg
        return runCatching {
            val msg = buildSetBandModeMessage(template, args)
            Logger.log(
                "[OBS][fallback] Messenger.send bandMode what=${msg.what} arg1=${msg.arg1} arg2=${msg.arg2} " +
                    "replyTo=${msg.replyTo != null} data=${describeBundle(msg.data)}"
            )
            messenger.send(msg)
            true to "fallback messenger sent setBandMode (modeKeyInt=${args.keyInt}, subId=${args.subId}, slotId=${args.slotId})"
        }.getOrElse {
            Logger.logE("[OBS][fallback] setBandMode messenger send failed", it)
            false to "setBandMode messenger send failed: ${it.message ?: it.javaClass.simpleName}"
        }
    }

    private fun scheduleBandLockFactReadback(ctx: Context, args: BandLockArgs) {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            dispatchBandLockQuery(ctx, args)
        }, 500L)
    }

    private fun rememberExpected(subId: Int, bytes: ByteArray) {
        if (subId < 0) return
        lastSetExpectHexBySubId[subId] = bytes.toHex()
        lastSetAtMsBySubId[subId] = android.os.SystemClock.uptimeMillis()
    }

    private fun buildSetBandPreferMessage(
        template: Message?,
        bytes: ByteArray,
        args: BandLockArgs
    ): Message {
        val msg = Message.obtain()
        msg.what = MSG_SET_BAND_PREFER
        msg.arg1 = template?.arg1 ?: android.os.Process.myPid()
        msg.arg2 = template?.arg2 ?: 0
        msg.replyTo = template?.replyTo

        val data = Bundle(template?.data ?: Bundle())
        data.putInt("what", BUNDLE_WHAT_SET_BAND_PREFER)
        data.putInt("subId", args.subId)
        data.putInt("slotId", args.slotId)
        data.putInt("keyInt", args.keyInt)
        data.putByteArray("keyByteArray", bytes)
        msg.data = data
        return msg
    }

    private fun buildRequireModemRebootMessage(
        template: Message?,
        args: BandLockArgs
    ): Message {
        val msg = Message.obtain()
        msg.what = MSG_REQUIRE_MODEM_REBOOT
        msg.arg1 = template?.arg1 ?: android.os.Process.myPid()
        msg.arg2 = template?.arg2 ?: 0
        msg.replyTo = template?.replyTo

        val data = Bundle(template?.data ?: Bundle())
        data.remove("keyByteArray")
        data.remove("keyInt")
        data.putInt("what", BUNDLE_WHAT_REQUIRE_MODEM_REBOOT)
        data.putInt("subId", args.subId)
        data.putInt("slotId", args.slotId)
        msg.data = data
        return msg
    }

    private fun buildGetBandPreferMessage(
        template: Message?,
        args: BandLockArgs
    ): Message {
        val msg = Message.obtain()
        msg.what = MSG_GET_BAND_PREFER
        msg.arg1 = template?.arg1 ?: android.os.Process.myPid()
        msg.arg2 = template?.arg2 ?: 0
        msg.replyTo = template?.replyTo

        val data = Bundle(template?.data ?: Bundle())
        data.remove("keyByteArray")
        data.putInt("what", bundleWhatGetBandPrefer(args.keyInt))
        data.putInt("subId", args.subId)
        data.putInt("slotId", args.slotId)
        data.putInt("keyInt", args.keyInt)
        msg.data = data
        return msg
    }

    private fun buildSetBandModeMessage(
        template: Message?,
        args: BandLockArgs
    ): Message {
        val msg = Message.obtain()
        msg.what = MSG_SET_BAND_MODE
        msg.arg1 = template?.arg1 ?: android.os.Process.myPid()
        msg.arg2 = template?.arg2 ?: 0
        msg.replyTo = template?.replyTo

        val data = Bundle(template?.data ?: Bundle())
        data.remove("keyByteArray")
        data.putInt("what", BUNDLE_WHAT_SET_BAND_MODE)
        data.putInt("subId", args.subId)
        data.putInt("slotId", args.slotId)
        data.putInt("keyInt", args.keyInt)
        msg.data = data
        return msg
    }

    private fun bundleWhatGetBandPrefer(keyInt: Int): Int =
        if (keyInt == 4) 1002 else BUNDLE_WHAT_GET_BAND_PREFER

    private fun buildSimpleSubsysMessage(
        what: Int,
        bundleWhat: Int,
        subId: Int,
        slotId: Int,
        replyTo: Messenger
    ): Message {
        return Message.obtain().apply {
            this.what = what
            this.arg1 = android.os.Process.myPid()
            this.arg2 = 0
            this.replyTo = replyTo
            data = Bundle().apply {
                putInt("what", bundleWhat)
                putInt("subId", subId)
                putInt("slotId", slotId)
            }
        }
    }

    private fun buildFactReplyMessenger(
        ctx: Context,
        args: BandLockArgs,
        source: String
    ): Messenger {
        lateinit var handler: Handler
        handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                try {
                    val data = msg.data
                    val keySet = runCatching { data.keySet() }.getOrDefault(emptySet())
                    Logger.log(
                        "[OBS][subsys][reply] source=$source what=${msg.what} arg1=${msg.arg1} arg2=${msg.arg2} " +
                            "data=${describeBundle(data)}"
                    )
                    if (msg.what == BUNDLE_WHAT_GET_BAND_PREFER || msg.what == 1002 || keySet.contains("keyByteArray")) {
                        val result = runCatching { data.getInt("result", -1) }.getOrDefault(-1)
                        val bytes = getByteArraySafe(data, "keyByteArray")
                        emitBandLockStateResult(
                            ctx = ctx,
                            ok = result == 0,
                            resultCode = result,
                            bytes = bytes,
                            args = args,
                            message = "ISubsysRadio $source readback"
                        )
                    }
                } finally {
                    pendingReplyHandlers.remove(this)
                }
            }
        }
        pendingReplyHandlers.add(handler)
        return Messenger(handler)
    }

    private fun captureControlChannel(messenger: Messenger, msg: Message) {
        controlMessenger = messenger
        controlTemplateMsg = Message.obtain().also { copy ->
            copy.what = msg.what
            copy.arg1 = msg.arg1
            copy.arg2 = msg.arg2
            copy.replyTo = msg.replyTo
            copy.data = Bundle(msg.data ?: Bundle())
        }
        val data = msg.data
        lastObservedSubId = getIntSafe(data, "subId", lastObservedSubId)
        lastObservedSlotId = getIntSafe(data, "slotId", lastObservedSlotId)
        lastObservedKeyInt = runCatching { data?.getInt("keyInt", lastObservedKeyInt) ?: lastObservedKeyInt }
            .getOrDefault(lastObservedKeyInt)
        Logger.log(
            "[OBS][capture] control channel what=${msg.what} arg1=${msg.arg1} arg2=${msg.arg2} " +
                "replyTo=${msg.replyTo != null} data=${describeBundle(data)} " +
                "messenger=${messenger.javaClass.name}@${Integer.toHexString(System.identityHashCode(messenger))}"
        )
    }

    private fun emitBandLockResult(ctx: Context, ok: Boolean, message: String, bandsHex: String) {
        runCatching {
            val intent = Intent(Config.ACTION_ENGINEER_BAND_LOCK_RESULT).apply {
                setPackage(Config.TARGET_PKG)
                putExtra(Config.EXTRA_BAND_LOCK_OK, ok)
                putExtra(Config.EXTRA_BAND_LOCK_MESSAGE, message)
                putExtra(Config.EXTRA_BAND_LOCK_PROCESS, currentProcessName)
                putExtra(Config.EXTRA_BAND_LOCK_BANDS_HEX, bandsHex)
            }
            ctx.sendBroadcast(intent)
            Logger.log("[OBS][cmd] result broadcast ok=$ok process=$currentProcessName bandsHex=$bandsHex msg=$message")
        }.onFailure { Logger.logE("[OBS] emit band-lock result failed", it) }
    }

    private fun emitBandLockStateResult(
        ctx: Context,
        ok: Boolean,
        resultCode: Int,
        bytes: ByteArray?,
        args: BandLockArgs,
        message: String
    ) {
        runCatching {
            val bands = bytes?.map { it.toInt() and 0xff }
                ?.filter { it in 1..255 }
                ?.distinct()
                ?.sorted()
                ?.toIntArray() ?: intArrayOf()
            val hex = bytes?.toHex().orEmpty()
            val intent = Intent(Config.ACTION_ENGINEER_BAND_LOCK_STATE).apply {
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                putExtra(Config.EXTRA_BAND_LOCK_STATE_OK, ok)
                putExtra(Config.EXTRA_BAND_LOCK_STATE_RESULT, resultCode)
                putExtra(Config.EXTRA_BAND_LOCK_STATE_BANDS, bands)
                putExtra(Config.EXTRA_BAND_LOCK_STATE_BANDS_HEX, hex)
                putExtra(Config.EXTRA_BAND_LOCK_STATE_KEY_INT, args.keyInt)
                putExtra(Config.EXTRA_BAND_LOCK_STATE_SUB_ID, args.subId)
                putExtra(Config.EXTRA_BAND_LOCK_MESSAGE, message)
                putExtra(Config.EXTRA_BAND_LOCK_PROCESS, currentProcessName)
            }
            ctx.sendBroadcast(Intent(intent).setPackage(Config.TARGET_PKG))
            ctx.sendBroadcast(Intent(intent))
            Logger.log(
                "[OBS][state] result broadcast ok=$ok result=$resultCode process=$currentProcessName " +
                    "bandsHex=$hex bands=${bands.joinToString(prefix = "[", postfix = "]") { "N$it" }} msg=$message"
            )
        }.onFailure { Logger.logE("[OBS] emit band-lock state result failed", it) }
    }

    private fun hookRuntimeExec(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedBridge.hookAllMethodsNative(Runtime::class.java, "exec") { chain ->
                val cmd = when (val a0 = chain.args.getOrNull(0)) {
                    is String -> a0
                    is Array<*> -> a0.joinToString(" ")
                    else -> a0?.toString() ?: "null"
                }
                if (hitCmd(cmd)) {
                    Logger.log("[OBS][exec] $cmd")
                    Logger.logE("[OBS][exec][stack]", Throwable())
                }
                chain.proceed()
            }
            Logger.log("[OBS] hooked Runtime.exec")
        }.onFailure { Logger.logE("[OBS] hook Runtime.exec failed", it) }
    }

    private fun hookProcessBuilder(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val startMethod = ProcessBuilder::class.java.getDeclaredMethod("start").apply { isAccessible = true }
            XposedBridge.hookMethodNative(startMethod) { chain ->
                val pb = chain.thisObject as ProcessBuilder
                val cmd = pb.command().joinToString(" ")
                if (hitCmd(cmd)) {
                    Logger.log("[OBS][pb.start] $cmd")
                    Logger.logE("[OBS][pb.start][stack]", Throwable())
                }
                chain.proceed()
            }
            Logger.log("[OBS] hooked ProcessBuilder.start")
        }.onFailure { Logger.logE("[OBS] hook ProcessBuilder.start failed", it) }
    }

    private fun hookSystemProperties(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val sp = XposedHelpers.findClass("android.os.SystemProperties", lpparam.classLoader)
            val setMethod = sp.getDeclaredMethod("set", String::class.java, String::class.java).apply {
                isAccessible = true
            }
            XposedBridge.hookMethodNative(setMethod) { chain ->
                val k = chain.args.getOrNull(0) as? String
                val v = chain.args.getOrNull(1) as? String
                if (k != null && v != null && hitProp(k, v)) {
                    Logger.log("[OBS][setprop] $k=$v")
                    Logger.logE("[OBS][setprop][stack]", Throwable())
                }
                chain.proceed()
            }
            Logger.log("[OBS] hooked SystemProperties.set")
        }.onFailure { Logger.logE("[OBS] hook SystemProperties.set failed", it) }
    }

    private val targetBinders = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    )

    private var lastSubsysBinderId: Int? = null
    private fun hookServiceManagerSubsysRadio(lpparam: XC_LoadPackage.LoadPackageParam) {
        val sm = XposedHelpers.findClass("android.os.ServiceManager", lpparam.classLoader)

        XposedBridge.hookAllMethodsNative(sm, "getService") { chain ->
            val result = chain.proceed()
            val name = chain.args.getOrNull(0) as? String
            if (name == "ISubsysRadio") {
                val binder = result as? android.os.IBinder
                if (binder == null) {
                    if (!throttle("subsys_null", 1000)) Logger.log("[OBS][SM.getService] ISubsysRadio -> null")
                    return@hookAllMethodsNative result
                }

                val id = System.identityHashCode(binder)
                targetBinders.add(id)

                // 只在变化时打印
                val old = lastSubsysBinderId
                if (old == id) return@hookAllMethodsNative result
                lastSubsysBinderId = id

                val desc = runCatching { binder.interfaceDescriptor }.getOrNull()
                Logger.log("[OBS][SM.getService] ISubsysRadio binderId=$id desc=$desc class=${binder.javaClass.name}")
            }
            result
        }
    }

    private fun hookBinderProxyTransactOnlyTargets(lpparam: XC_LoadPackage.LoadPackageParam) {
        val bp = XposedHelpers.findClass("android.os.BinderProxy", lpparam.classLoader)
        val tl = ThreadLocal<TransactCtx?>()

        XposedBridge.hookAllMethodsNative(bp, "transact") { chain ->
            val self = chain.thisObject as? android.os.IBinder ?: return@hookAllMethodsNative chain.proceed()
            val id = System.identityHashCode(self)
            if (!targetBinders.contains(id)) return@hookAllMethodsNative chain.proceed()

            val code = chain.args.getOrNull(0) as? Int ?: return@hookAllMethodsNative chain.proceed()
            val data = chain.args.getOrNull(1) as? Parcel ?: return@hookAllMethodsNative chain.proceed()
            val reply = chain.args.getOrNull(2) as? Parcel
            val flags = chain.args.getOrNull(3) as? Int ?: 0

            // code=1 基本是 getMessenger，做节流
            if (code == 1 && throttle("transact_code1", 1500)) return@hookAllMethodsNative chain.proceed()

            val desc = runCatching { self.interfaceDescriptor }.getOrNull()

            val head48 = runCatching {
                val p0 = data.dataPosition()
                val raw = data.marshall()
                data.setDataPosition(p0)
                raw.take(48).joinToString("") { "%02x".format(it) }
            }.getOrNull()

            // 把上下文塞进 ThreadLocal，给 after 用
            tl.set(
                TransactCtx(
                    binderId = id,
                    desc = desc,
                    code = code,
                    flags = flags,
                    reqHead = head48,
                    hasReply = (reply != null)
                )
            )

            Logger.log("[OBS][transact][ISubsysRadio][REQ] binderId=$id desc=$desc code=$code flags=$flags head48=$head48")

            if (code != 1) {
                Logger.logE("[OBS][transact][stack]", Throwable())
            }

            val result = chain.proceed()
            val ctx = tl.get()
            tl.set(null)
            if (ctx == null) return@hookAllMethodsNative result

            // transact 返回值：大部分情况是 boolean（Java 层），有些 ROM 可能是 int
            val retStr = runCatching { result?.toString() }.getOrNull() ?: "null"

            if (reply == null) {
                // ONE_WAY / 没有 reply
                Logger.log("[OBS][transact][ISubsysRadio][RSP] code=${ctx.code} ret=$retStr reply=null")
                return@hookAllMethodsNative result
            }

            // reply dump：不破坏 position
            val replyHexHead = runCatching {
                val p0 = reply.dataPosition()
                val raw = reply.marshall()
                reply.setDataPosition(p0)
                raw.take(128).joinToString("") { "%02x".format(it) } // 先抓 128 bytes 够定位结构
            }.getOrNull()

            val replyLen = runCatching {
                val p0 = reply.dataPosition()
                val raw = reply.marshall()
                reply.setDataPosition(p0)
                raw.size
            }.getOrNull()

            // 降噪：reply 为空内容时，不打印 hex
            if (replyLen == null || replyLen == 0) {
                Logger.log("[OBS][transact][ISubsysRadio][RSP] code=${ctx.code} ret=$retStr replyLen=0")
                return@hookAllMethodsNative result
            }

            // 再加一道节流：同 code+len 的重复回包别刷屏
            val tk = "reply:${ctx.code}:$replyLen"
            if (throttle(tk, 200)) return@hookAllMethodsNative result

            Logger.log(
                "[OBS][transact][ISubsysRadio][RSP] binderId=${ctx.binderId} code=${ctx.code} ret=$retStr replyLen=$replyLen replyHead128=$replyHexHead"
            )
            result
        }
    }

    private data class TransactCtx(
        val binderId: Int,
        val desc: String?,
        val code: Int,
        val flags: Int,
        val reqHead: String?,
        val hasReply: Boolean
    )

    // ====== 改 hookMessengerSend：记录 set(2188) 期望值 ======

    private fun hookMessengerSend(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val messengerClass = XposedHelpers.findClass("android.os.Messenger", lpparam.classLoader)
            val sendMethod = messengerClass.getDeclaredMethod("send", android.os.Message::class.java).apply {
                isAccessible = true
            }
            XposedBridge.hookMethodNative(sendMethod) { chain ->
                val msg = chain.args.getOrNull(0) as? android.os.Message
                if (msg != null) {
                    val data = msg.data
                    val messenger = chain.thisObject as? Messenger

                    // 读回请求（可选，低噪）
                    if (msg.what == MSG_GET_BAND_PREFER) {
                        messenger?.let { captureControlChannel(it, msg) }
                        val subId = getIntSafe(data, "subId", -1)
                        val slotId = getIntSafe(data, "slotId", -1)
                        val keyInt = runCatching { data?.getInt("keyInt") }.getOrNull()
                        if (!throttle("getBandPrefer:req:$subId:$slotId:$keyInt", 500)) {
                            Logger.log(
                                "[OBS][getBandPrefer][REQ] what=${msg.what} arg1=${msg.arg1} arg2=${msg.arg2} " +
                                    "subId=$subId slotId=$slotId keyInt=$keyInt data=${describeBundle(data)}"
                            )
                            if (TRACE_STACK_FOR_BAND_PROTOCOL) {
                                Logger.logE("[OBS][getBandPrefer][REQ][stack]", Throwable())
                            }
                        }
                    }

                    // 写入：setBandPrefer
                    if (msg.what == MSG_SET_BAND_PREFER) {
                        messenger?.let { captureControlChannel(it, msg) }
                        val slotId = getIntSafe(data, "slotId", -1)
                        val subId = getIntSafe(data, "subId", -1)
                        val keyInt = runCatching { data?.getInt("keyInt") }.getOrNull()
                        val arr = getByteArraySafe(data, "keyByteArray")

                        val hex = arr?.toHex().orEmpty()
                        lastSetExpectHexBySubId[subId] = hex
                        lastSetAtMsBySubId[subId] = android.os.SystemClock.uptimeMillis()

                        Logger.log(
                            "[OBS][setBandPrefer][REQ] subId=$subId slotId=$slotId keyInt=$keyInt " +
                                "len=${arr?.size ?: 0} raw=$hex bands=${prettyBands(arr)}"
                        )

                        // 只有写入才打栈（你要的“异常才打栈”，这里算关键动作，也值得）
                        Logger.logE("[OBS][setBandPrefer][stack]", Throwable())
                    }

                    if (TRACE_ALL_MESSENGER_MESSAGES && msg.what != MSG_GET_BAND_PREFER && msg.what != MSG_SET_BAND_PREFER) {
                        val keySet = runCatching { data?.keySet() }.getOrNull().orEmpty()
                        if (keySet.isNotEmpty() && looksBandRelated(data) && !throttle("messenger:other:${msg.what}:${keySet.joinToString(",")}", 500)) {
                            Logger.log(
                                "[OBS][Messenger.send][OTHER] what=${msg.what} arg1=${msg.arg1} arg2=${msg.arg2} " +
                                    "replyTo=${msg.replyTo != null} data=${describeBundle(data)}"
                            )
                            Logger.logE("[OBS][Messenger.send][OTHER][stack]", Throwable())
                        }
                    }
                }
                chain.proceed()
            }
            Logger.log("[OBS] hooked Messenger.send")
        }.onFailure { Logger.logE("[OBS] hook Messenger.send failed", it) }
    }

    // ====== 改 hookHandlerInbound：吃回包(1004)并对比 expected ======

    private fun hookHandlerInbound(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedBridge.hookAllMethodsNative(android.os.Handler::class.java, "dispatchMessage") { chain ->
                val msg = chain.args.getOrNull(0) as? android.os.Message
                if (msg != null) {
                    val w = msg.what
                    val data = msg.data
                    val keySet = runCatching { data?.keySet() }.getOrNull() ?: emptySet()

                    // 1) 空回包：只把 2006 当“有回执”指示灯
                    if (keySet.isEmpty()) {
                        if (w == 2006 && !throttle("in_empty_2006", 2000)) {
                            Logger.log("[OBS][IN] what=2006 (empty ack) arg1=${msg.arg1} arg2=${msg.arg2}")
                        }
                    } else if (w == 1004 || w == 1002) {
                        // 2) 重点：把 1004 当作 getBandPrefer 回包入口
                        val result = runCatching { data?.getInt("result", -1) }.getOrNull() ?: -1
                        val arr = getByteArraySafe(data, "keyByteArray")
                        val raw = arr?.toHex().orEmpty()
                        val bands = prettyBands(arr)

                        // 注意：回包里你当前拿不到 subId/slotId（基本都是 -1），所以我们用“最近一次 set”来匹配
                        // 优先找最近窗口内的 subId（两个卡都可能有）
                        val now = android.os.SystemClock.uptimeMillis()
                        val recentSubIds = lastSetAtMsBySubId.entries
                            .filter { now - it.value <= EXPECT_WINDOW_MS }
                            .sortedByDescending { it.value }
                            .map { it.key }

                        // 尝试逐个 subId 匹配 expected
                        var matchedSubId: Int? = null
                        var expected: String? = null
                        for (sid in recentSubIds) {
                            val exp = lastSetExpectHexBySubId[sid] ?: continue
                            // exp 可能是 ""（解锁）
                            expected = exp
                            matchedSubId = sid
                            break
                        }

                        // 没有 recent set：当作“状态读取”
                        if (matchedSubId == null) {
                            if (!throttle("rsp1004_state:$result:$raw", 500)) {
                                Logger.log("[OBS][getBandPrefer][RSP][STATE] result=$result bands=$bands raw=$raw len=${arr?.size ?: 0} keys=$keySet")
                            }
                            emitObservedBandLockState(result, arr, null)
                            return@hookAllMethodsNative chain.proceed()
                        }

                        // 有 recent set：做严格对比
                        val exp = expected ?: ""
                        val ok = (result == 0 && raw.equals(exp, ignoreCase = true))

                        if (ok) {
                            // ✅ 成功：只打一行
                            Logger.log("[OBS][getBandPrefer][RSP][OK] subId=$matchedSubId result=0 bands=$bands raw=$raw len=${arr?.size ?: 0}")
                            emitObservedBandLockState(result, arr, matchedSubId)
                        } else {
                            // ❌ 异常：打详细 + 栈
                            Logger.log(
                                "[OBS][getBandPrefer][RSP][BAD] subId=$matchedSubId result=$result " +
                                    "expect=$exp actual=$raw bands=$bands len=${arr?.size ?: 0}"
                            )
                            Logger.log("[OBS][getBandPrefer][RSP][BAD] keys=$keySet")
                            Logger.logE("[OBS][getBandPrefer][RSP][BAD][stack]", Throwable())
                            emitObservedBandLockState(result, arr, matchedSubId)
                        }
                    }

                    // 3) 其它非空回包：手动复盘工程模式协议时打开，避免漏掉真实 ACK / 状态码。
                    if (TRACE_ALL_MESSENGER_MESSAGES && w != 1004 && w != 1002 && keySet.isNotEmpty() && looksBandRelated(data)) {
                        if (!throttle("in:$w:${keySet.joinToString(",")}:${describeBundle(data)}", 500)) {
                            Logger.log(
                                "[OBS][IN][OTHER] what=$w arg1=${msg.arg1} arg2=${msg.arg2} " +
                                    "data=${describeBundle(data)}"
                            )
                        }
                    }
                }
                chain.proceed()
            }
            Logger.log("[OBS] hooked Handler.dispatchMessage (inbound)")
        }.onFailure { Logger.logE("[OBS] hook Handler.dispatchMessage failed", it) }
    }

    private fun emitObservedBandLockState(
        resultCode: Int,
        bytes: ByteArray?,
        matchedSubId: Int?
    ) {
        val ctx = registeredContext ?: return
        val args = BandLockArgs(
            subId = matchedSubId ?: lastObservedSubId.takeIf { it >= 0 } ?: 1,
            slotId = lastObservedSlotId,
            keyInt = lastObservedKeyInt.takeIf { it >= 0 } ?: 5
        )
        emitBandLockStateResult(
            ctx = ctx,
            ok = resultCode == 0,
            resultCode = resultCode,
            bytes = bytes,
            args = args,
            message = if (resultCode == 0) "getBandPrefer readback" else "getBandPrefer result=$resultCode"
        )
    }
    // =======================
    // Filters（尽量少误报）
    // =======================

    private fun hitProp(k: String, v: String): Boolean {
        val key = k.lowercase()
        if (!(key.startsWith("persist.") || key.startsWith("vendor.") || key.startsWith("radio.") || key.startsWith("gsm."))) return false
        // 锁频段相关一般会带 band / lock / rat / nr / lte / mode 等
        val vv = v.lowercase()
        return (key.contains("band") || key.contains("lock") || key.contains("rat") || key.contains("nr") || key.contains("lte") || key.contains("mode")
                || vv.contains("band") || vv.contains("lock") || vv.contains("nr") || vv.contains("lte"))
    }

    private fun hitCmd(cmd: String): Boolean {
        val s = cmd.lowercase()
        // 常见可疑命令特征（命中就打栈）
        return (s.contains("setprop") || s.contains("service call") || s.contains("cmd ")
                || s.contains("radio") || s.contains("telephony") || s.contains("ril")
                || s.contains("diag") || s.contains("qmi") || s.contains("nv") || s.contains("band") || s.contains("lock"))
    }

    private fun looksBandRelated(bundle: Bundle?): Boolean {
        val keys = runCatching { bundle?.keySet().orEmpty() }.getOrDefault(emptySet())
        if (keys.any { key ->
                key.equals("subId", ignoreCase = true) ||
                    key.equals("slotId", ignoreCase = true) ||
                    key.equals("keyInt", ignoreCase = true) ||
                    key.equals("keyByteArray", ignoreCase = true) ||
                    key.contains("band", ignoreCase = true) ||
                    key.contains("lock", ignoreCase = true)
            }
        ) {
            return true
        }
        return keys.any { key ->
            val value = runCatching { bundle?.get(key) }.getOrNull()
            value is ByteArray || value?.toString()?.contains("band", ignoreCase = true) == true
        }
    }

    private fun describeBundle(bundle: Bundle?): String {
        if (bundle == null) return "null"
        val keys = runCatching { bundle.keySet().sorted() }.getOrNull() ?: return "unreadable"
        return keys.joinToString(prefix = "{", postfix = "}") { key ->
            val value = runCatching { bundle.get(key) }.getOrNull()
            val printable = when (value) {
                is ByteArray -> "ByteArray(len=${value.size},hex=${value.toHex()},bands=${prettyBands(value)})"
                is IntArray -> value.joinToString(prefix = "IntArray[", postfix = "]")
                is Array<*> -> value.joinToString(prefix = "Array[", postfix = "]")
                else -> value?.toString() ?: "null"
            }
            "$key=$printable"
        }
    }

    private fun ByteArray.toHex(maxBytes: Int = 256): String {
        if (isEmpty()) return ""
        val takeN = minOf(size, maxBytes)
        val head = take(takeN).joinToString("") { "%02x".format(it) }
        return if (size > maxBytes) head + "..." else head
    }

    /**
     * 将 byteArray 解析为 NR 频段号列表：每个 byte 视为一个 band id (1..255)
     * 例：01 03 08 -> [N1, N3, N8]
     *     1c 4f    -> [N28, N79]
     */
    private fun decodeNrBandsFromBytes(arr: ByteArray?): List<String> {
        if (arr == null || arr.isEmpty()) return emptyList()
        // Kotlin Byte 是有符号的，必须 & 0xFF 还原 0..255
        return arr.map { b -> (b.toInt() and 0xFF) }
            .filter { it != 0 } // 通常 0 没意义
            .distinct()
            .sorted()
            .map { "N$it" }
    }
    ////////////////////////////////////日志降噪

    private fun now() = android.os.SystemClock.elapsedRealtime()

    private val lastLogAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun throttle(key: String, intervalMs: Long): Boolean {
        val t = now()
        val last = lastLogAt[key] ?: 0L
        if (t - last < intervalMs) return true
        lastLogAt[key] = t
        return false
    }

    private val once = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private fun logOnce(key: String, msg: () -> String) {
        if (once.add(key)) Logger.log(msg())
    }
    ////////////////////////////////////日志降噪

    // ====== 在类里加这些字段/工具 ======

    private val lastSetExpectHexBySubId = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val lastSetAtMsBySubId = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun parseNrBandsFromBytes(arr: ByteArray): List<String> {
        // 已经验证过：每个字节代表一个 NR band index：0x1c->N28, 0x29->N41, 0x4f->N79
        return arr.map { b ->
            val v = b.toInt() and 0xff
            "N$v"
        }
    }


}
