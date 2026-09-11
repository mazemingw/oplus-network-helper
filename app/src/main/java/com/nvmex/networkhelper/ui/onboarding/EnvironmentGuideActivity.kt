package com.nvmex.networkhelper.ui.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.MainActivity
import com.nvmex.networkhelper.ui.settings.AppLanguageManager
import com.nvmex.networkhelper.ui.settings.USAGE_TUTORIAL_URL
import com.nvmex.networkhelper.ui.settings.openUrl
import com.nvmex.networkhelper.ui.theme.NetworkHelperTheme
import com.nvmex.networkhelper.util.home.OplusVendor
import com.nvmex.networkhelper.util.home.detectOplusVendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class EnvironmentGuideActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()

        setContent {
            NetworkHelperTheme {
                EnvironmentGuideScreen()
            }
        }
    }
}

@Composable
private fun EnvironmentGuideScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    var lsposedConnected by remember { mutableStateOf(EnvironmentProbeStore.readFrameworkConnected(context)) }
    var lsposedSummary by remember { mutableStateOf(EnvironmentProbeStore.readFrameworkSummary(context)) }
    var tutorialAcknowledged by remember { mutableStateOf(EnvironmentGuidePrefs.isTutorialAcknowledged(context)) }
    val systemSupport = remember(context) { evaluateSystemSupport(context) }

    fun refresh() {
        checking = true
        lsposedConnected = EnvironmentProbeStore.readFrameworkConnected(context)
        lsposedSummary = EnvironmentProbeStore.readFrameworkSummary(context)
        scope.launch {
            rootGranted = checkRootGranted()
            lsposedConnected = EnvironmentProbeStore.readFrameworkConnected(context)
            lsposedSummary = EnvironmentProbeStore.readFrameworkSummary(context)
            checking = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val noPrivilegedEnvironment = rootGranted == false && !lsposedConnected
    val privilegedReady = rootGranted == true && lsposedConnected
    val canStart = tutorialAcknowledged

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.env_guide_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.env_guide_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            EnvironmentStatusCard(
                title = stringResource(R.string.env_root_title),
                ok = rootGranted,
                detail = when (rootGranted) {
                    true -> stringResource(R.string.env_root_granted)
                    false -> stringResource(R.string.env_root_unavailable)
                    null -> stringResource(R.string.env_checking)
                }
            )

            EnvironmentStatusCard(
                title = stringResource(R.string.env_lsposed_title),
                ok = lsposedConnected,
                detail = if (lsposedConnected) {
                    lsposedSummary ?: stringResource(R.string.env_lsposed_connected_default)
                } else {
                    stringResource(R.string.env_lsposed_activate)
                }
            )

            EnvironmentStatusCard(
                title = stringResource(R.string.env_system_title),
                ok = systemSupport.ok,
                detail = systemSupport.detail,
                nullAsWarning = true
            )

            EnvironmentActionCard(
                title = stringResource(R.string.env_tutorial_title),
                ok = tutorialAcknowledged,
                detail = if (tutorialAcknowledged) {
                    stringResource(R.string.env_tutorial_done)
                } else {
                    stringResource(R.string.env_tutorial_required)
                },
                onClick = {
                    openUrl(context, USAGE_TUTORIAL_URL)
                    if (!tutorialAcknowledged) {
                        tutorialAcknowledged = true
                        EnvironmentGuidePrefs.setTutorialAcknowledged(context, true)
                    }
                }
            )

            if (noPrivilegedEnvironment) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = stringResource(R.string.env_basic_only),
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { refresh() },
                    enabled = !checking,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (checking) stringResource(R.string.env_checking) else stringResource(R.string.env_recheck))
                }
                Button(
                    onClick = { continueToApp(context) },
                    enabled = canStart,
                    colors = if (privilegedReady) {
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                            contentColor = Color.White
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (privilegedReady) stringResource(R.string.env_start_use) else stringResource(R.string.env_continue_use))
                }
            }
        }
    }
}

@Composable
private fun EnvironmentStatusCard(
    title: String,
    ok: Boolean?,
    detail: String,
    nullAsWarning: Boolean = false
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusMark(ok = ok, nullAsWarning = nullAsWarning)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EnvironmentActionCard(
    title: String,
    ok: Boolean,
    detail: String,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusMark(ok = ok)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusMark(ok: Boolean?, nullAsWarning: Boolean = false) {
    val color = when (ok) {
        true -> Color(0xFF2E7D32)
        false -> MaterialTheme.colorScheme.error
        null -> if (nullAsWarning) Color(0xFFB26A00) else MaterialTheme.colorScheme.outline
    }
    val imageVector = when (ok) {
        true -> Icons.Filled.CheckCircle
        false -> Icons.Filled.Cancel
        null -> if (nullAsWarning) Icons.Filled.ErrorOutline else Icons.AutoMirrored.Filled.HelpOutline
    }

    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
    }
}

private data class SystemSupportState(
    val ok: Boolean?,
    val detail: String
)

private fun evaluateSystemSupport(context: Context): SystemSupportState {
    val vendor = detectOplusVendor()
    val isSupportedBrand = vendor == OplusVendor.OPPO ||
            vendor == OplusVendor.ONEPLUS ||
            vendor == OplusVendor.REALME
    if (!isSupportedBrand) {
        return SystemSupportState(
            ok = null,
            detail = context.getString(R.string.env_non_oplus_skip)
        )
    }

    val major = parseColorOsMajor()
    if (major == null) {
        return SystemSupportState(
            ok = null,
            detail = context.getString(R.string.env_coloros_unrecognized)
        )
    }

    return if (major >= 15) {
        SystemSupportState(
            ok = true,
            detail = context.getString(R.string.env_coloros_supported, major)
        )
    } else {
        SystemSupportState(
            ok = null,
            detail = context.getString(R.string.env_coloros_maybe_unsupported, major)
        )
    }
}

private fun parseColorOsMajor(): Int? {
    val candidates = listOf(Build.DISPLAY, Build.VERSION.INCREMENTAL)
        .mapNotNull { it?.trim() }
        .filter { it.isNotBlank() }
    if (candidates.isEmpty()) return null

    val patternFromBuild = Regex("_(\\d+)\\.\\d+\\.\\d+\\.\\d+\\(", RegexOption.IGNORE_CASE)
    val patternColorOsText = Regex("coloros\\s*(\\d+)", RegexOption.IGNORE_CASE)

    candidates.forEach { text ->
        patternFromBuild.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        patternColorOsText.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    }
    return null
}

private fun continueToApp(context: Context) {
    EnvironmentGuidePrefs.setCompleted(context, true)
    context.startActivity(
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
    (context as? Activity)?.finish()
}

private suspend fun checkRootGranted(): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val process = ProcessBuilder("su", "-c", "id").start()
        val finished = process.waitFor(1500, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            return@withContext false
        }
        val out = BufferedReader(InputStreamReader(process.inputStream)).readText()
        val err = BufferedReader(InputStreamReader(process.errorStream)).readText()
        process.exitValue() == 0 && (out + err).contains("uid=", ignoreCase = true)
    }.getOrDefault(false)
}
