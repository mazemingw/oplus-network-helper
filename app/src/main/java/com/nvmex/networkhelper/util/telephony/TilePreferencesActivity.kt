package com.nvmex.networkhelper.util.telephony

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.nvmex.networkhelper.ui.theme.NetworkHelperTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A single QS preferences route avoids resolving the 5G tile to the hotspot activity. */
class TilePreferencesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val component = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
        if (component?.packageName == packageName && component.className.startsWith("be.mygod.vpnhotspot.")) {
            startActivity(Intent(intent).setClass(this, be.mygod.vpnhotspot.MainActivity::class.java))
            finish(); return
        }
        setContent { NetworkHelperTheme { FiveGSettings(onBack = { finish() }) } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun FiveGSettings(onBack: () -> Unit) {
        val scope = rememberCoroutineScope()
        var allowed by remember { mutableStateOf<Boolean?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        fun update(target: Boolean? = null) {
            if (busy) return
            busy = true; error = null
            scope.launch {
                try {
                    allowed = if (target == null) FiveGNetwork.read(this@TilePreferencesActivity)
                        else FiveGNetwork.set(this@TilePreferencesActivity, target)
                    TileService.requestListeningState(this@TilePreferencesActivity,
                        ComponentName(this@TilePreferencesActivity, FiveGTileService::class.java))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "系统不支持此设置" }
                finally { busy = false }
            }
        }
        LaunchedEffect(Unit) { update() } // Read only until the user explicitly changes the switch.
        Scaffold(topBar = { TopAppBar(title = { Text("5G 网络") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("允许 5G", style = MaterialTheme.typography.titleMedium)
                            Text("作用于默认数据卡", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = allowed == true, onCheckedChange = { update(it) }, enabled = !busy && allowed != null)
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                OutlinedButton(onClick = { update() }, enabled = !busy, shape = RoundedCornerShape(4.dp)) { Text("重新读取") }
            }
        }
    }
}
