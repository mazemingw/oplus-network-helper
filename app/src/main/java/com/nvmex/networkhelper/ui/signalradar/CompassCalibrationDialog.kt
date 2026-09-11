package com.nvmex.networkhelper.ui.signalradar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R

@Composable
fun CompassCalibrationDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onDontShowAgain: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (showDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(R.string.radar_compass_title))
                }
            },
            text = {
                Column {
                    Text(stringResource(R.string.radar_compass_welcome), fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.radar_compass_intro))
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier.padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("1️⃣")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.radar_compass_step_hold))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("2️⃣")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.radar_compass_step_eight))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("3️⃣")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.radar_compass_step_metal))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("4️⃣")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.radar_compass_step_field))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.radar_compass_ready),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Text(stringResource(R.string.radar_compass_confirm))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onDontShowAgain,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth(1f) // 控制按钮宽度
                ) {
                    Text(stringResource(R.string.radar_dont_remind))
                }
            }
        )
    }
}
