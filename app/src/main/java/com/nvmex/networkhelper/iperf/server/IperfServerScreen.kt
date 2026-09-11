package com.nvmex.networkhelper.iperf.server

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.windows.WindowUtils

@Composable
fun IperfServerScreen() {
    val ctx = LocalContext.current
    val running by IperfServerService.running.collectAsState()
    val log by IperfServerService.log.collectAsState()

    val statusBarHeight = WindowUtils.getStatusBarHeight(ctx)

    var portText by rememberSaveable { mutableStateOf("5201") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = statusBarHeight),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = portText,
            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.iperf_listen_port)) },
            singleLine = true
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val port = portText.toIntOrNull() ?: 5201
                val i = Intent(ctx, IperfServerService::class.java).apply {
                    action = IperfServerService.ACTION_START
                    putExtra(IperfServerService.EXTRA_PORT, port)
                }
                ContextCompat.startForegroundService(ctx, i)
            },
            enabled = !running
        ) {
            Text(stringResource(R.string.iperf_start_server))
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val i = Intent(ctx, IperfServerService::class.java).apply {
                    action = IperfServerService.ACTION_STOP
                }
                ctx.startService(i)
            },
            enabled = running
        ) {
            Text(stringResource(R.string.iperf_stop))
        }

        Divider(modifier = Modifier.fillMaxWidth())

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val url = "http://cjjd.zggso.com/NetworkHelperUpdate/%E6%B5%8B%E9%80%9F%E6%95%99%E7%A8%8B.pdf"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { ctx.startActivity(intent) }
                    .onFailure {
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.iperf_open_link_failed, it.message ?: "unknown"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }
        ) {
            Text(stringResource(R.string.settings_usage_tutorial_title))
        }


        Text(
            text = stringResource(
                R.string.iperf_status,
                if (running) stringResource(R.string.iperf_running) else stringResource(R.string.iperf_not_running)
            ),
            style = MaterialTheme.typography.bodyMedium
        )

        // 日志区域建议给 weight，让它自然吃剩余高度
        SelectionContainer(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Text(
                text = log,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

