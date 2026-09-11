package com.nvmex.networkhelper.ui.onboarding

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

data class EnvironmentScopeStatus(
    val packageName: String,
    val label: String,
    val processName: String? = null,
    val lastSeenMs: Long = 0L
) {
    val active: Boolean get() = lastSeenMs > 0L
}

data class SupportScopeStatus(
    val packageName: String,
    val label: String,
    val configured: Boolean,
    val active: Boolean,
    val processName: String? = null,
    val lastSeenMs: Long = 0L
)

object EnvironmentGuidePrefs {
    private const val PREFS_NAME = "environment_guide"
    private const val KEY_COMPLETED = "completed"
    private const val KEY_TUTORIAL_ACK = "tutorial_ack"

    fun isCompleted(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)
    }

    fun setCompleted(context: Context, completed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_COMPLETED, completed) }
    }

    fun isTutorialAcknowledged(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TUTORIAL_ACK, false)
    }

    fun setTutorialAcknowledged(context: Context, acknowledged: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_TUTORIAL_ACK, acknowledged) }
    }
}

object EnvironmentProbeStore {
    const val AUTHORITY = "com.nvmex.networkhelper.xposed.settings"
    const val PATH_ENVIRONMENT_PROBE = "environment_probe"
    const val COL_PACKAGE = "package_name"
    const val COL_LABEL = "label"
    const val COL_PROCESS = "process_name"
    const val COL_LAST_SEEN_MS = "last_seen_ms"
    const val COL_ACTIVE = "active"

    private const val PREFS_NAME = "environment_probe"
    private const val KEY_PROCESS_SUFFIX = "_process"
    private const val KEY_TS_SUFFIX = "_last_seen_ms"
    private const val MODULE_PACKAGE = "com.nvmex.networkhelper"
    private const val KEY_FRAMEWORK_CONNECTED = "framework_connected"
    private const val KEY_FRAMEWORK_NAME = "framework_name"
    private const val KEY_FRAMEWORK_VERSION = "framework_version"
    private const val KEY_FRAMEWORK_API = "framework_api"
    private const val KEY_FRAMEWORK_LAST_BIND_MS = "framework_last_bind_ms"
    private const val KEY_SCOPE_CONFIGURED_CSV = "scope_configured_csv"
    private const val LOG_TAG = "EnvironmentProbe"

    @Volatile
    private var frameworkListenerRegistered = false

    @Volatile
    private var boundService: XposedService? = null

    val supportScopeTargets = listOf(
        EnvironmentScopeStatus("com.android.phone", "电话服务"),
        EnvironmentScopeStatus("com.oplus.engineernetwork", "工程模式"),
        EnvironmentScopeStatus("com.oplus.subsys", "子系统"),
        EnvironmentScopeStatus("android", "系统服务")
    )

    val requiredScopes = listOf(
        EnvironmentScopeStatus(MODULE_PACKAGE, "LSPosed 模块")
    )

    private val probeTargetsByPackage: Map<String, EnvironmentScopeStatus> by lazy {
        (requiredScopes + supportScopeTargets).associateBy { it.packageName }
    }

    fun registerFrameworkServiceListener(context: Context) {
        if (frameworkListenerRegistered) return
        synchronized(this) {
            if (frameworkListenerRegistered) return
            val appCtx = context.applicationContext ?: context
            runCatching {
                XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(service: XposedService) {
                        boundService = service
                        updateFrameworkConnection(
                            context = appCtx,
                            connected = true,
                            frameworkName = runCatching { service.frameworkName }.getOrNull(),
                            frameworkVersion = runCatching { service.frameworkVersion }.getOrNull(),
                            frameworkApi = runCatching { service.apiVersion }.getOrNull()
                        )
                        syncConfiguredScopesFromService(appCtx, service)
                    }

                    override fun onServiceDied(service: XposedService) {
                        if (boundService === service) boundService = null
                        updateFrameworkConnection(
                            context = appCtx,
                            connected = false,
                            frameworkName = runCatching { service.frameworkName }.getOrNull(),
                            frameworkVersion = runCatching { service.frameworkVersion }.getOrNull(),
                            frameworkApi = runCatching { service.apiVersion }.getOrNull()
                        )
                    }
                })
                frameworkListenerRegistered = true
            }.onFailure { e ->
                Log.w(LOG_TAG, "registerListener failed: ${e.javaClass.simpleName}: ${e.message}")
                frameworkListenerRegistered = true
            }
        }
    }

    fun readFrameworkConnected(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FRAMEWORK_CONNECTED, false)
    }

    fun readFrameworkSummary(context: Context): String? {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = sp.getString(KEY_FRAMEWORK_NAME, null).orEmpty()
        val version = sp.getString(KEY_FRAMEWORK_VERSION, null).orEmpty()
        val api = sp.getInt(KEY_FRAMEWORK_API, -1)

        val parts = buildList {
            if (name.isNotBlank()) add(name)
            if (version.isNotBlank()) add(version)
            if (api >= 0) add("API $api")
        }
        return parts.joinToString(" / ").ifBlank { null }
    }

    fun readModuleActive(context: Context): Boolean {
        return readStatuses(context).firstOrNull { it.packageName == MODULE_PACKAGE }?.active == true
    }

    fun refreshConfiguredScopes(context: Context): Boolean {
        val service = boundService ?: return false
        return syncConfiguredScopesFromService(context.applicationContext ?: context, service)
    }

    fun readSupportScopeStatuses(context: Context): List<SupportScopeStatus> {
        val configuredSet = readConfiguredScopeSet(context)
        return readStatusesFor(context, supportScopeTargets).map { status ->
            SupportScopeStatus(
                packageName = status.packageName,
                label = status.label,
                configured = configuredSet.contains(status.packageName),
                active = status.active,
                processName = status.processName,
                lastSeenMs = status.lastSeenMs
            )
        }
    }

    fun markSeen(context: Context, packageName: String, processName: String?, nowMs: Long = System.currentTimeMillis()) {
        if (!probeTargetsByPackage.containsKey(packageName)) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putLong(tsKey(packageName), nowMs)
            putString(processKey(packageName), processName.orEmpty())
        }
    }

    fun readStatuses(context: Context): List<EnvironmentScopeStatus> {
        return readStatusesFor(context, requiredScopes)
    }

    private fun readStatusesFor(
        context: Context,
        scopes: List<EnvironmentScopeStatus>
    ): List<EnvironmentScopeStatus> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return scopes.map { scope ->
            scope.copy(
                processName = sp.getString(processKey(scope.packageName), null)
                    ?.takeIf { it.isNotBlank() },
                lastSeenMs = sp.getLong(tsKey(scope.packageName), 0L)
            )
        }
    }

    private fun processKey(packageName: String): String = packageName + KEY_PROCESS_SUFFIX
    private fun tsKey(packageName: String): String = packageName + KEY_TS_SUFFIX

    private fun syncConfiguredScopesFromService(context: Context, service: XposedService): Boolean {
        return runCatching {
            val scopeSet = service.scope.mapNotNull { it?.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_SCOPE_CONFIGURED_CSV, scopeSet.sorted().joinToString(","))
            }
            true
        }.getOrDefault(false)
    }

    private fun readConfiguredScopeSet(context: Context): Set<String> {
        val csv = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SCOPE_CONFIGURED_CSV, null)
            .orEmpty()
        if (csv.isBlank()) return emptySet()
        return csv.split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun updateFrameworkConnection(
        context: Context,
        connected: Boolean,
        frameworkName: String?,
        frameworkVersion: String?,
        frameworkApi: Int?
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_FRAMEWORK_CONNECTED, connected)
            putLong(KEY_FRAMEWORK_LAST_BIND_MS, System.currentTimeMillis())
            if (!frameworkName.isNullOrBlank()) putString(KEY_FRAMEWORK_NAME, frameworkName)
            if (!frameworkVersion.isNullOrBlank()) putString(KEY_FRAMEWORK_VERSION, frameworkVersion)
            if (frameworkApi != null) putInt(KEY_FRAMEWORK_API, frameworkApi)
        }
    }
}
