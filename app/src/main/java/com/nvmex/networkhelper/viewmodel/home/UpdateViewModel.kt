package com.nvmex.networkhelper.viewmodel.home


import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.model.home.UpdateUiState
import com.nvmex.networkhelper.network.base.ApiResult
import com.nvmex.networkhelper.network.repo.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject


@HiltViewModel
class UpdateViewModel @Inject constructor(
    app: Application,
    private val repo: UpdateRepository
) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val ui: StateFlow<UpdateUiState> = _ui.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> get() = _downloadProgress

    private val _downloadSpeed = MutableStateFlow(0) // 下载速度 (KB/s)
    val downloadSpeed: StateFlow<Int> get() = _downloadSpeed

    private val sharedPreferences by lazy {
        app.getSharedPreferences("MyAppPrefs", Context.MODE_PRIVATE)
    }

    // 跳过当前版本
    private fun setSkippedVersion(versionCode: Int) {
        sharedPreferences.edit().putInt("skipped_version", versionCode).apply()
    }

    private fun getSkippedVersion(): Int {
        return sharedPreferences.getInt("skipped_version", -1)
    }

    // 获取当前版本号
    private fun getLocalVersionCode(): Int {
        val pm = getApplication<Application>().packageManager
        val pkg = getApplication<Application>().packageName
        val pi = pm.getPackageInfo(pkg, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        return code.toInt()
    }

    fun checkOnce() {
        when (_ui.value) {
            UpdateUiState.Idle,
            is UpdateUiState.NoUpdate,
            is UpdateUiState.Error -> Unit
            UpdateUiState.Checking,
            is UpdateUiState.Available -> return
        }

        viewModelScope.launch {
            _ui.value = UpdateUiState.Checking

            when (val r = repo.fetchLatest()) {
                is ApiResult.Ok -> {
                    val remote = r.data
                    val localCode = getLocalVersionCode()
                    val skippedVersion = getSkippedVersion()

                    // 有新版本
                    if (remote.versionCode > skippedVersion && remote.versionCode > localCode) {
                        _ui.value = UpdateUiState.Available(
                            info = remote,
                            mandatory = remote.mandatory // 使用 computed 属性
                        )
                    } else {
                        _ui.value = UpdateUiState.NoUpdate()
                    }
                }

                is ApiResult.HttpError -> {
                    _ui.value = UpdateUiState.Error("HTTP ${r.code} ${r.body.orEmpty()}")
                }

                is ApiResult.NetworkError -> {
                    _ui.value = UpdateUiState.Error(r.message)
                }
            }
        }
    }

    fun dismissIfAllowed() {
        val cur = _ui.value
        if (cur is UpdateUiState.Available && cur.mandatory) return
        _ui.value = UpdateUiState.NoUpdate("dismissed")
    }

    fun skipCurrentVersion() {
        val cur = _ui.value as? UpdateUiState.Available ?: return
        if (cur.mandatory) return
        setSkippedVersion(cur.info.versionCode)
        _ui.value = UpdateUiState.NoUpdate("skipped_${cur.info.versionCode}")
    }

    // 下载和安装 APK
    fun downloadAndInstallApk(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updateInfo = (_ui.value as? UpdateUiState.Available)?.info ?: return@launch

                // 添加更详细的日志
                Log.d("UpdateViewModel", "准备下载版本: ${updateInfo.versionCode}, 版本名: ${updateInfo.versionName}")

                val url = updateInfo.downloadUrl

                if (url.isNullOrEmpty()) {
                    Log.e("UpdateViewModel", "下载链接为空")
                    _ui.emit(UpdateUiState.Error("下载链接为空"))
                    return@launch
                }

                Log.d("UpdateViewModel", "开始下载 APK: $url")

                // 添加文件名详细日志
                val apkFileName = "update_v${updateInfo.versionCode}_${updateInfo.versionName}.apk"
                Log.d("UpdateViewModel", "APK文件名: $apkFileName")

                val apkFile = File(context.externalCacheDir, apkFileName)

                // 检查已存在文件时添加版本验证
                if (apkFile.exists() && apkFile.length() > 0) {
                    Log.d("UpdateViewModel", "发现已下载的APK，文件大小: ${apkFile.length()} bytes")

                    // 可以在这里添加APK文件完整性检查
                    try {
                        val packageInfo = context.packageManager.getPackageArchiveInfo(
                            apkFile.absolutePath,
                            0
                        )
                        if (packageInfo != null) {
                            Log.d("UpdateViewModel", "已存在APK版本信息: versionCode=${packageInfo.versionCode}, versionName=${packageInfo.versionName}")

                            // 验证版本是否匹配
                            if (packageInfo.versionCode == updateInfo.versionCode) {
                                Log.d("UpdateViewModel", "版本匹配，直接安装")
                                installApk(context, apkFile)
                                return@launch
                            } else {
                                Log.w("UpdateViewModel", "文件版本不匹配，重新下载")
                                apkFile.delete()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("UpdateViewModel", "检查APK文件失败", e)
                        apkFile.delete()
                    }
                }

                // 清理旧版本APK（增强日志）
                cleanOldApkFiles(context, updateInfo.versionCode)

                // 创建临时下载文件
                val tempFile = File(context.externalCacheDir, "$apkFileName.temp")
                if (tempFile.exists()) {
                    val deleted = tempFile.delete()
                    Log.d("UpdateViewModel", "删除临时文件: ${deleted}, ${tempFile.absolutePath}")
                }

                // 开始下载
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS) // 增加读取超时
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "App-Updater")
                    .build()

                Log.d("UpdateViewModel", "开始HTTP请求...")

                val response = client.newCall(request).execute()

                Log.d("UpdateViewModel", "服务器响应: ${response.code}, ${response.message}, Content-Length: ${response.header("Content-Length")}")

                if (response.isSuccessful) {
                    response.body?.let { body ->
                        val totalBytes = body.contentLength()
                        Log.d("UpdateViewModel", "需要下载的总字节数: $totalBytes")

                        if (totalBytes <= 0) {
                            Log.e("UpdateViewModel", "无效的文件大小")
                            _ui.emit(UpdateUiState.Error("无效的文件大小"))
                            return@launch
                        }

                        FileOutputStream(tempFile).use { outputStream ->
                            val inputStream = body.byteStream()
                            val buffer = ByteArray(8192) // 增加缓冲区大小
                            var bytesRead: Int
                            var downloadedBytes = 0L
                            var lastUpdateTime = System.currentTimeMillis()
                            var lastDownloadedBytes = 0L

                            // 进度监控
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                // 计算下载进度
                                val progress = if (totalBytes > 0) {
                                    ((downloadedBytes * 100) / totalBytes).toInt()
                                } else 0

                                _downloadProgress.value = progress

                                // 计算下载速度 (KB/s)
                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastUpdateTime >= 1000) {
                                    val speed = ((downloadedBytes - lastDownloadedBytes) / 1024).toInt()
                                    _downloadSpeed.value = speed
                                    lastDownloadedBytes = downloadedBytes
                                    lastUpdateTime = currentTime
                                }
                            }
                        }

                        // 验证下载文件大小
                        val actualSize = tempFile.length()
                        Log.d("UpdateViewModel", "下载完成，文件大小: $actualSize bytes")

                        if (actualSize < 1024) { // 小于1KB的文件通常是无效的
                            Log.e("UpdateViewModel", "下载文件过小，可能下载失败")
                            tempFile.delete()
                            _ui.emit(UpdateUiState.Error("下载文件损坏"))
                            return@launch
                        }

                        // 下载完成后重命名文件
                        if (tempFile.renameTo(apkFile)) {
                            Log.d("UpdateViewModel", "文件重命名成功: ${apkFile.absolutePath}")

                            // 验证APK文件版本
                            try {
                                val packageInfo = context.packageManager.getPackageArchiveInfo(
                                    apkFile.absolutePath,
                                    0
                                )
                                if (packageInfo != null) {
                                    Log.d("UpdateViewModel", "下载的APK版本信息: versionCode=${packageInfo.versionCode}, versionName=${packageInfo.versionName}")

                                    if (packageInfo.versionCode == updateInfo.versionCode) {
                                        _downloadProgress.value = 100
                                        saveApkInfo(updateInfo.versionCode, apkFile.absolutePath)
                                        Log.d("UpdateViewModel", "APK 下载完成，开始安装")
                                        installApk(context, apkFile, updateInfo.versionCode)
                                    } else {
                                        Log.e("UpdateViewModel", "下载的APK版本不匹配: 期望=${updateInfo.versionCode}, 实际=${packageInfo.versionCode}")
                                        apkFile.delete()
                                        _ui.emit(UpdateUiState.Error("版本不匹配"))
                                    }
                                } else {
                                    Log.e("UpdateViewModel", "无法解析APK文件信息")
                                    apkFile.delete()
                                    _ui.emit(UpdateUiState.Error("文件损坏"))
                                }
                            } catch (e: Exception) {
                                Log.e("UpdateViewModel", "验证APK文件失败", e)
                                apkFile.delete()
                                _ui.emit(UpdateUiState.Error("文件验证失败"))
                            }
                        } else {
                            Log.e("UpdateViewModel", "文件重命名失败")
                            tempFile.delete()
                            _ui.emit(UpdateUiState.Error("保存文件失败"))
                        }
                    }
                } else {
                    Log.e("UpdateViewModel", "下载失败: ${response.code}")
                    tempFile.delete()
                    _ui.emit(UpdateUiState.Error("下载失败: ${response.code}"))
                }
            } catch (e: Exception) {
                Log.e("UpdateViewModel", "下载出错", e)
                _downloadProgress.value = 0
                _downloadSpeed.value = 0
                _ui.emit(UpdateUiState.Error("下载出错: ${e.message}"))
            }
        }
    }




    // 清理旧版本 APK
    private fun cleanOldApkFiles(context: Context, currentVersionCode: Int) {
        context.externalCacheDir?.listFiles()?.forEach { file ->
            when {
                // 删除所有临时文件
                file.name.endsWith(".temp") -> file.delete()

                // 删除旧版本APK
                file.name.startsWith("update_v") && file.name.endsWith(".apk") -> {
                    try {
                        val version = file.name
                            .removePrefix("update_v")
                            .removeSuffix(".apk")
                            .toIntOrNull() ?: 0

                        if (version < currentVersionCode) {
                            file.delete()
                            Log.d("UpdateViewModel", "已清理旧版本APK: ${file.name}")
                        }
                    } catch (e: Exception) {
                        file.delete()
                    }
                }
            }
        }
    }

    // 保存APK文件信息
    private fun saveApkInfo(versionCode: Int, filePath: String) {
        sharedPreferences.edit().apply {
            putInt("last_apk_version", versionCode)
            putString("apk_file_path", filePath)
            apply() // 使用apply()异步提交
        }
    }

    // 安装 APK
    private fun installApk(context: Context, apkFile: File, expectedVersionCode: Int? = null) {
        try {
            // 验证文件完整性
            if (apkFile.length() <= 0) {
                Log.e("UpdateViewModel", "APK文件不完整")
                return
            }

            // 解析 APK 包信息
            val packageInfo = context.packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_ACTIVITIES
            ) ?: run {
                Log.e("UpdateViewModel", "无法解析APK信息")
                return
            }

            // 获取APK的版本信息
            val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                packageInfo.versionCode
            }

            val apkVersionName = packageInfo.versionName

            Log.d("UpdateViewModel", """
            APK版本信息:
            - versionCode: $apkVersionCode
            - versionName: $apkVersionName
            - 本地版本: ${getLocalVersionCode()}
            - 期望版本: $expectedVersionCode
        """.trimIndent())

            // 验证版本（如果提供了期望版本，就与期望版本比较）
            when {
                expectedVersionCode != null -> {
                    // 与期望的更新版本比较
                    if (apkVersionCode != expectedVersionCode) {
                        Log.e("UpdateViewModel", "APK版本与期望版本不匹配: APK=$apkVersionCode, 期望=$expectedVersionCode")
                        apkFile.delete()
                        return
                    }
                    Log.d("UpdateViewModel", "APK版本与期望版本匹配")
                }
                else -> {
                    // 如果没有提供期望版本，检查是否比当前版本新（可选）
                    val localVersionCode = getLocalVersionCode()
                    if (apkVersionCode <= localVersionCode) {
                        Log.e("UpdateViewModel", "APK版本不大于当前版本: APK=$apkVersionCode, 当前=$localVersionCode")
                        apkFile.delete()
                        return
                    }
                    Log.d("UpdateViewModel", "APK版本比当前版本新")
                }
            }

            // 启动安装
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            Log.d("UpdateViewModel", "准备安装APK, URI: $uri")

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // 检查是否有应用可以处理安装请求
            val activities = context.packageManager.queryIntentActivities(installIntent, 0)
            if (activities.isEmpty()) {
                Log.e("UpdateViewModel", "没有应用可以处理安装请求")
                return
            }

            Log.d("UpdateViewModel", "开始启动安装Activity...")
            context.startActivity(installIntent)
            Log.d("UpdateViewModel", "已发送安装请求")

        } catch (e: Exception) {
            Log.e("UpdateViewModel", "安装失败", e)
            apkFile.delete() // 删除无效文件
        }
    }
}



