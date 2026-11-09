package com.nvmex.networkhelper.ui.menu

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.nvmex.networkhelper.network.base.NetworkConfig
import dev.qcom.efs.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** Debug-only diagnostics. Never enables writes, toggles features/5G, sends SPC or executes recovery. */
class EfsReadOnlyProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = TextView(this).apply { text = "EFS read-only verification"; textSize = 18f; setPadding(32, 64, 32, 32) }
        setContentView(label)
        val vm = ViewModelProvider(this)[MainViewModel::class.java]
        vm.configureCloud(NetworkConfig.BASE_URL + "networkhelp/efs-backups/")
        var readRequested = false
        var recoveryRequested = false
        var cloudProbeRequested = false
        var fiveGReadOk: Boolean? = null
        var cloudFallbackPackage: String? = null
        lifecycleScope.launch {
            vm.state.collect { s ->
                val ready = s.features as? FeaturesState.Ready
                val report = JSONObject().put("phase", s.phase.name).put("readOnly", s.readOnly)
                    .put("localComplete", s.initialBackupComplete).put("cloudComplete", s.cloudBackupComplete)
                    .put("busy", s.busy).put("message", s.backupMessage ?: s.cloudBackupMessage)
                    .put("error", s.initialBackupError ?: s.error).put("lastBackup", s.lastBackupPath)
                    .put("featureReadCount", ready?.statuses?.size ?: 0).put("recoveryPackage", s.recoveryPackagePath)
                    .put("fiveGReadOk", fiveGReadOk).put("cloudFallbackPackage", cloudFallbackPackage)
                File(filesDir, "efs-readonly-probe.json").writeText(report.toString(2))
                label.text = report.toString(2)
                check(s.readOnly) { "Probe must remain read-only" }
                if (s.phase == Phase.READY && !s.busy && s.initialBackupComplete && s.cloudBackupComplete && !readRequested) {
                    readRequested = true; vm.openFeatures()
                }
                if (ready != null && !s.busy && !recoveryRequested && intent.getBooleanExtra("prepare_recovery", false)) {
                    recoveryRequested = true; vm.prepareFullRecovery()
                }
                if (s.recoveryPackagePath != null && !s.busy && !cloudProbeRequested && intent.getBooleanExtra("cloud_fallback", false)) {
                    cloudProbeRequested = true
                    fiveGReadOk = runCatching { dev.qcom.efs.platform.RootPhone.read(this@EfsReadOnlyProbeActivity, "read5g").getBoolean("ok") }.getOrDefault(false)
                    cloudFallbackPackage = CloudRecoveryProbe.run(this@EfsReadOnlyProbeActivity,
                        NetworkConfig.BASE_URL + "networkhelp/efs-backups/")
                    report.put("fiveGReadOk", fiveGReadOk).put("cloudFallbackPackage", cloudFallbackPackage)
                    File(filesDir, "efs-readonly-probe.json").writeText(report.toString(2))
                    label.text = report.toString(2)
                }
            }
        }
        vm.enterModule()
    }
}
