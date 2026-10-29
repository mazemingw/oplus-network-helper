package dev.ririxi.bunnylock.shizuku

import android.content.Context
import dev.ririxi.bunnylock.modem.R
import dev.ririxi.bunnylock.data.RootClient
import dev.ririxi.bunnylock.data.RootException
import dev.ririxi.bunnylock.data.reasonText
import dev.ririxi.bunnylock.modem.Access
import dev.ririxi.bunnylock.modem.HelperStarter
import dev.ririxi.bunnylock.modem.R as ModemR
import dev.ririxi.bunnylock.modem.StartedHelper
import dev.ririxi.bunnylock.modem.Text
import kotlinx.coroutines.CancellationException

class RootOrShizuku(
    private val context: Context,
    private val onShizukuLost: () -> Unit,
) : HelperStarter {
    override suspend fun start(showStep: (String) -> Unit): StartedHelper {
        showStep(context.getString(R.string.system_shizuku_step_root))
        val rootClient = RootClient(context)
        val rootProblem =
            try {
                return StartedHelper(rootClient, rootClient.start(), Access.ROOT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.reasonText()?.let { Text(R.string.system_shizuku_root_failed, it) } ?: Text(R.string.system_shizuku_root_denied)
            }
        showStep(context.getString(R.string.system_shizuku_step_looking))
        if (!ShizukuClient.awaitBinder()) {
            val installed = ShizukuClient.installedVersion(context) !== null
            val shizukuProblem = Text(if (installed) R.string.system_shizuku_not_running else R.string.system_shizuku_not_installed)
            throw RootException(context, Text(ModemR.string.modem_sentences, rootProblem, shizukuProblem))
        }
        showStep(context.getString(R.string.system_shizuku_step_connecting))
        val shizukuClient = ShizukuClient(context, onShizukuLost)
        val hello =
            try {
                shizukuClient.start()
            } catch (e: CancellationException) {
                shizukuClient.stop()
                throw e
            } catch (e: Exception) {
                shizukuClient.stop()
                val shizukuProblem =
                    e.reasonText()?.let { Text(R.string.system_shizuku_failed, it) } ?: Text(R.string.system_shizuku_failed_no_reason)
                throw RootException(context, Text(ModemR.string.modem_sentences, rootProblem, shizukuProblem))
            }
        val access = if (hello.optInt("uid", -1) == 0) Access.SHIZUKU_ROOT else Access.SHIZUKU_ADB
        val note = if (shizukuClient.inShell) "Shizuku's service for bunnylock doesn't start on this phone, so the helper runs in a Shizuku shell." else null
        return StartedHelper(shizukuClient, hello, access, note)
    }
}
