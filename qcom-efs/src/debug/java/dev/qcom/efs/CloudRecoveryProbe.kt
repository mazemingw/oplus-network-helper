package dev.qcom.efs

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Debug-only isolated empty directories simulate complete local loss, leaving real backups untouched. */
object CloudRecoveryProbe {
    suspend fun run(context: Context, endpoint: String): String = withContext(Dispatchers.IO) {
        val root = File(context.cacheDir, "efs-cloud-fallback-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = File(root, "private").apply { mkdirs() }
        }
        val storage = BackupStorage(isolated, File(root, "public").apply { mkdirs() })
        val cloud = EfsCloudBackup(isolated, storage).apply { this.endpoint = endpoint }
        val (backup, identity) = cloud.resolveForRecovery { }
        check(backup.raw.manifest.isFile)
        BackupBundle.recoveryPackage(backup, storage.directory, identity).absolutePath
    }
}
