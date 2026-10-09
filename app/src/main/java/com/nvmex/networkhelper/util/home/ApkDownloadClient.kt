package com.nvmex.networkhelper.util.home

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Bounded download; unknown Content-Length is legal, empty/truncated responses are not. */
internal class ApkDownloadClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
) {
    fun download(url: String, target: File, onCall: (Call) -> Unit, onProgress: (Int, Int) -> Unit) {
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "App-Updater").build())
        onCall(call)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("下载失败：HTTP ${response.code}")
                val body = response.body ?: throw IOException("下载响应为空")
                val total = body.contentLength()
                var downloaded = 0L
                var lastBytes = 0L
                var lastTime = System.nanoTime()
                onProgress(if (total > 0) 0 else -1, 0)
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            if (call.isCanceled()) throw IOException("下载已取消")
                            val count = input.read(buffer)
                            if (count == -1) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            val now = System.nanoTime()
                            val elapsed = now - lastTime
                            if (elapsed >= 250_000_000) {
                                val speed = ((downloaded - lastBytes) * 1_000_000_000.0 / elapsed / 1024)
                                    .toInt().coerceAtLeast(0)
                                val progress = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 99) else -1
                                onProgress(progress, speed)
                                lastBytes = downloaded
                                lastTime = now
                            }
                        }
                    }
                }
                if (downloaded == 0L || (total >= 0 && downloaded != total)) throw IOException("下载文件为空或不完整")
                onProgress(100, 0)
            }
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }
}
