package com.nvmex.networkhelper.viewmodel.home

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.model.home.UpdatePhase
import com.nvmex.networkhelper.model.home.UpdateUiState
import com.nvmex.networkhelper.network.base.ApiResult
import com.nvmex.networkhelper.network.repo.UpdateRepository
import com.nvmex.networkhelper.util.home.ApkDownloadClient
import com.nvmex.networkhelper.util.home.UpdateApkCompatibility
import com.nvmex.networkhelper.util.home.validateUpdateApk
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(app: Application, private val repo: UpdateRepository) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val ui = _ui.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress = _downloadProgress.asStateFlow()
    private val _downloadSpeed = MutableStateFlow(0)
    val downloadSpeed = _downloadSpeed.asStateFlow()
    private val preferences = app.getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE)
    private var updateJob: Job? = null
    @Volatile private var activeCall: Call? = null

    private fun localVersion(): Long = getApplication<Application>().let {
        it.packageManager.getPackageInfo(it.packageName, 0).longVersionCode
    }

    fun checkOnce() {
        if (_ui.value == UpdateUiState.Checking || _ui.value is UpdateUiState.Available) return
        _ui.value = UpdateUiState.Checking
        viewModelScope.launch {
            try {
                _ui.value = when (val result = repo.fetchLatest()) {
                    is ApiResult.Ok -> {
                        val remote = result.data
                        if (remote.versionCode.toLong() > localVersion() &&
                            (remote.mandatory || remote.versionCode > preferences.getInt("skipped_version", -1))
                        ) UpdateUiState.Available(remote, remote.mandatory) else UpdateUiState.NoUpdate()
                    }
                    is ApiResult.HttpError -> UpdateUiState.Error("HTTP ${result.code}")
                    is ApiResult.NetworkError -> UpdateUiState.Error(result.message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _ui.value = UpdateUiState.Error(error.message ?: "检查更新失败")
            }
        }
    }

    fun dismissIfAllowed() {
        val state = _ui.value as? UpdateUiState.Available
        if (state?.mandatory == true || state?.phase?.isBusy == true) return
        _ui.value = UpdateUiState.NoUpdate("dismissed")
    }

    fun skipCurrentVersion() {
        val state = _ui.value as? UpdateUiState.Available ?: return
        if (state.mandatory || state.phase.isBusy) return
        preferences.edit().putInt("skipped_version", state.info.versionCode).apply()
        _ui.value = UpdateUiState.NoUpdate("skipped_${state.info.versionCode}")
    }

    fun downloadAndInstallApk(context: Context) {
        val state = _ui.value as? UpdateUiState.Available ?: return
        if (state.phase.isBusy || updateJob?.isActive == true) return
        // Synchronous state prevents repeated clicks/two screens launching two tasks.
        val signatureMismatchAccepted = state.signatureMismatchAccepted || state.phase == UpdatePhase.SIGNATURE_MISMATCH
        _ui.value = state.copy(
            phase = UpdatePhase.VERIFYING, error = null,
            signatureMismatchAccepted = signatureMismatchAccepted
        )
        _downloadProgress.value = 0
        _downloadSpeed.value = 0
        val appContext = context.applicationContext
        updateJob = viewModelScope.launch(Dispatchers.IO) {
            if (!updateGate.compareAndSet(false, true)) {
                setPhase(UpdatePhase.READY, "另一个更新任务正在进行，请稍后重试")
                return@launch
            }
            var temp: File? = null
            try {
                val directory = File(appContext.cacheDir, "updates")
                check(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存目录" }
                val apk = File(directory, "update_v${state.info.versionCode}.apk")
                val partial = File(directory, "${apk.name}.part")
                temp = partial
                // Only clean files owned by this updater, not other app caches.
                directory.listFiles()?.filter {
                    it.name.matches(Regex("update_v\\d+\\.apk(?:\\.part)?")) && it != apk
                }?.forEach { it.delete() }
                var compatibility = if (apk.isFile) runCatching {
                    validateApk(appContext, apk, state.info.versionCode)
                }.getOrNull() else null
                if (compatibility == null) {
                    apk.delete()
                    require(state.info.hasValidDownloadUrl()) { "下载链接为空或无效" }
                    setPhase(UpdatePhase.DOWNLOADING)
                    ensureActive()
                    ApkDownloadClient().download(
                        state.info.downloadUrl!!.trim(), partial,
                        onCall = {
                            activeCall = it
                            if (!isActive) it.cancel()
                        },
                        onProgress = { progress, speed ->
                            _downloadProgress.value = progress
                            _downloadSpeed.value = speed
                        }
                    )
                    ensureActive()
                    setPhase(UpdatePhase.VERIFYING)
                    compatibility = validateApk(appContext, partial, state.info.versionCode)
                    check(partial.renameTo(apk)) { "保存更新文件失败" }
                }
                ensureActive()
                _downloadProgress.value = 100
                if (compatibility == UpdateApkCompatibility.SIGNATURE_MISMATCH && !signatureMismatchAccepted) {
                    // Keep the complete APK. The explicit next click delegates compatibility
                    // enforcement to the system installer, including patched systems.
                    setPhase(UpdatePhase.SIGNATURE_MISMATCH, "APK 签名与已安装应用不兼容，可选择继续安装，由系统安装器处理")
                    return@launch
                }
                setPhase(UpdatePhase.OPENING_INSTALLER)
                withContext(Dispatchers.Main) { openInstaller(appContext, apk) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("UpdateViewModel", "Update failed", error)
                val message = when (error) {
                    is ActivityNotFoundException -> "找不到可用的安装器或安装权限设置，请检查系统后重试"
                    is SecurityException -> "系统拒绝安装请求，请检查安装未知应用权限"
                    is java.io.InterruptedIOException -> "下载超时或已中断，请重试"
                    else -> error.message ?: "下载或安装失败，请重试"
                }
                setPhase(UpdatePhase.READY, message)
            } finally {
                temp?.delete()
                activeCall = null
                updateGate.set(false)
                _downloadSpeed.value = 0
                val current = _ui.value as? UpdateUiState.Available
                if (current?.phase?.isBusy == true) setPhase(UpdatePhase.READY)
            }
        }
    }

    private fun setPhase(phase: UpdatePhase, error: String? = null) {
        val state = _ui.value as? UpdateUiState.Available ?: return
        _ui.value = state.copy(phase = phase, error = error)
    }

    private fun validateApk(context: Context, file: File, expectedVersion: Int): UpdateApkCompatibility {
        require(file.length() > 0) { "APK 文件为空" }
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: error("无法解析 APK，文件可能损坏")
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = apk.signingInfo ?: error("无法读取 APK 签名")
        val installedSigning = installed.signingInfo ?: error("无法读取已安装应用签名")
        fun fingerprints(signatures: Array<android.content.pm.Signature>?): Set<String> =
            signatures.orEmpty().map {
                MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
            }.toSet()
        return validateUpdateApk(
            apk.packageName, context.packageName, apk.longVersionCode, expectedVersion.toLong(),
            installed.longVersionCode, fingerprints(signing.apkContentsSigners),
            fingerprints(if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory),
            fingerprints(installedSigning.apkContentsSigners)
        )
    }

    private fun openInstaller(context: Context, apk: File) {
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        if (process.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
            setPhase(UpdatePhase.READY_TO_INSTALL)
            return
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            setPhase(UpdatePhase.AWAITING_PERMISSION)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = android.content.ClipData.newRawUri("update_apk", uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // No queryIntentActivities preflight: package visibility can return a false empty list.
        context.startActivity(intent)
        // Handoff is not installation success. A cancelled system installer can be reopened.
        setPhase(UpdatePhase.INSTALLER_LAUNCHED)
    }

    override fun onCleared() {
        activeCall?.cancel()
        super.onCleared()
    }

    companion object {
        // Home and Settings may own separate ViewModels, but share update files.
        private val updateGate = AtomicBoolean(false)
    }
}
