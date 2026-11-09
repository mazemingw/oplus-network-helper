package com.nvmex.networkhelper.util.diagnostics

import android.app.job.JobParameters
import android.app.job.JobService
import kotlinx.coroutines.*

class DiagnosticUploadJob : JobService() {
    private var task: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val complete = try { DiagnosticReporter.uploadPending() }
                catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            jobFinished(params, !complete)
            DiagnosticReporter.resumePendingAfterJob()
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); DiagnosticReporter.cancelUpload(automaticOnly = true); return DiagnosticReporter.enabled.value }
    override fun onNetworkChanged(params: JobParameters) {
        // OkHttp uses the current default network. Failed in-flight calls use the existing job retry.
    }
    override fun onDestroy() { task?.cancel(); DiagnosticReporter.cancelUpload(automaticOnly = true); super.onDestroy() }
}
