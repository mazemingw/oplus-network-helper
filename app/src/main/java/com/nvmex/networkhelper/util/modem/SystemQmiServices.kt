package com.nvmex.networkhelper.util.modem

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.nvmex.networkhelper.util.qmi.QmiModemClient
import com.nvmex.networkhelper.util.shell.SuShellRunner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class QmiSystemComponent(
    val service: String, val title: String, val role: String, val impact: String,
    val reportedNas: Int, val suggest: Boolean = false,
)

/** Counts are evidence from PLK110_17.0.0.105, not socket counts or a universal capacity. */
val qmiSystemComponents = listOf(
    QmiSystemComponent("qti-modem-daemon-1", "欧加无线扩展 · 实例 1",
        "小区、NRCA、频段/小区控制、QoS 等厂商扩展接口宿主。",
        "可能失去该实例的工程模式 NRCA、QoS 与厂商网络优化；实例编号不保证对应物理 SIM。", 1, true),
    QmiSystemComponent("qti-modem-daemon-0", "欧加无线扩展 · 实例 0",
        "小区、NRCA、频段/小区控制、QoS 等厂商扩展接口宿主。",
        "可能失去该实例的工程模式 NRCA、QoS 与厂商网络优化；实例编号不保证对应物理 SIM。", 1, true),
    QmiSystemComponent("vendor.qms", "高通无线 / 数据服务",
        "包含 Smart Transmit、SAR/无线共存、数据与 NTN 等模块。",
        "可能影响射频功率协同、无线共存、数据及厂商功能；不自动选择。", 2),
    QmiSystemComponent("vendor.qcrild2", "核心电话接口 · 实例 2",
        "Android 电话框架与基带之间的 QCRIL 通路。",
        "可能中断信号注册、通话、短信、移动数据及 IMS；不自动选择。", 1),
    QmiSystemComponent("vendor.qcrild", "核心电话接口 · 主实例",
        "Android 电话框架与基带之间的 QCRIL 通路。",
        "可能中断信号注册、通话、短信、移动数据及 IMS；不自动选择。", 1),
)

data class QmiSystemServiceState(
    val component: QmiSystemComponent, val state: String, val pid: String,
    val verified: Boolean, val pausedByUs: Boolean,
) {
    val canPause get() = verified && state == "running" && !pausedByUs
    val canResume get() = verified && (state == "stopped" || pausedByUs)
}

data class QmiSystemRecoveryState(
    val autoRestore: Boolean = false, val decideForMe: Boolean = true,
    val services: List<QmiSystemServiceState> = emptyList(), val diagnostics: String = "",
)

/** Explicit system controls are separate from ordinary cleanup, which never kills system services. */
object SystemQmiServices {
    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(QmiSystemRecoveryState())
    val state = mutable.asStateFlow()
    private val blocked = MutableStateFlow(false)
    val channelsBlocked = blocked.asStateFlow()
    private val disconnectors = ConcurrentHashMap<Any, suspend () -> Unit>()
    private var initialized = false
    private var exitGeneration = 0
    private val prefs get() = app.getSharedPreferences("system_qmi_recovery", 0)

    fun initialize(application: Application) {
        if (initialized) return
        initialized = true
        app = application
        mutable.value = mutable.value.copy(autoRestore = prefs.getBoolean("autoRestore", false),
            decideForMe = prefs.getBoolean("decideForMe", true))
        var activities = 0
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { activities++; exitGeneration++ }
            override fun onActivityDestroyed(activity: Activity) {
                activities--
                // Home/background and configuration changes are not application exit.
                if (activities == 0 && activity.isFinishing && !activity.isChangingConfigurations && state.value.autoRestore) {
                    val generation = ++exitGeneration
                    scope.launch {
                        // Finishing an Activity while launching another one is not leaving the app.
                        delay(350)
                        if (activities != 0 || generation != exitGeneration) return@launch
                        runCatching { releaseAll(); command("exit"); inspect() }
                            .onFailure { Log.e("QmiSystemRecovery", "Exit restoration failed; journal retained", it) }
                        allowChannels()
                    }
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        // Re-arm only an explicitly enabled guardian; a fresh installation never launches su here.
        if (state.value.autoRestore) {
            blocked.value = true
            scope.launch {
                // Recover a journal left by an interrupted previous process before re-arming.
                runCatching { command("restore"); command("configure", "1"); inspect() }
                    .onFailure { Log.e("QmiSystemRecovery", "Pending restoration/guardian unavailable", it) }
                allowChannels()
            }
        }
    }

    fun registerDisconnector(owner: Any, action: suspend () -> Unit) { disconnectors[owner] = action }
    fun unregisterDisconnector(owner: Any) { disconnectors.remove(owner) }
    fun setDecideForMe(value: Boolean) {
        prefs.edit().putBoolean("decideForMe", value).apply()
        mutable.value = mutable.value.copy(decideForMe = value)
    }

    suspend fun setAutoRestore(value: Boolean) {
        // Root-side state must be acknowledged before presenting the setting as enabled/disabled.
        command("configure", if (value) "1" else "0")
        prefs.edit().putBoolean("autoRestore", value).commit()
        mutable.value = mutable.value.copy(autoRestore = value)
    }

    suspend fun releaseAll() {
        blocked.value = true
        disconnectors.values.toList().forEach { it() }
        QmiModemClient.closeAll()
    }

    fun allowChannels() { blocked.value = false }

    suspend fun inspect() {
        val result = command("inspect")
        val rows = result.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            val definition = qmiSystemComponents.firstOrNull { it.service == parts.firstOrNull() } ?: return@mapNotNull null
            if (parts.size < 5) return@mapNotNull null
            QmiSystemServiceState(definition, parts[1].ifBlank { "未发现" }, parts[2], parts[3] == "1", parts[4] == "1")
        }.toList()
        mutable.value = mutable.value.copy(services = rows, diagnostics = result)
        Log.i("QmiSystemRecovery", result)
    }

    suspend fun pause(service: String) {
        require(qmiSystemComponents.any { it.service == service })
        releaseAll()
        try { command("pause", service) } finally { inspect() }
    }

    suspend fun resume(service: String? = null) {
        require(service == null || qmiSystemComponents.any { it.service == service })
        releaseAll()
        try { command(if (service == null) "restore" else "resume", service.orEmpty()) } finally { inspect() }
    }

    private suspend fun command(operation: String, argument: String = ""): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val directory = File(app.noBackupFilesDir, "qmi-system-services").apply { mkdirs() }
            val script = File(directory, "system-services.sh")
            val source = app.assets.open("qmi/system-services.sh").use { it.readBytes() }
            if (!script.isFile || !script.readBytes().contentEquals(source)) {
                val temporary = File(directory, "system-services.new")
                temporary.writeBytes(source)
                check(temporary.renameTo(script)) { "无法准备系统组件控制器" }
            }
            val pid = Process.myPid()
            val birth = File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ')[19]
            val command = listOf("/system/bin/sh", script.path, directory.path, File(app.filesDir, "qmi").path,
                app.applicationInfo.sourceDir, Process.myUid().toString(), pid.toString(), birth, operation, argument)
                .joinToString(" ") { "'" + it.replace("'", "'\"'\"'") + "'" }
            val result = SuShellRunner().execSu(command, 30_000)
            Log.i("QmiSystemRecovery", "$operation $argument: ${result.code}\n${result.out}${result.err}")
            check(result.code == 0) { result.err.ifBlank { result.out }.ifBlank { "系统组件操作失败 (${result.code})" } }
            result.out
        }
    }
}
