package com.nvmex.networkhelper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.parseCellExtraInfo

@Composable
fun CellMetadata(extraInfo: String?, stationType: String?) {
    if (!stationType.isNullOrBlank()) {
        val type = when (stationType) {
            "default" -> stringResource(R.string.cell_station_type_default)
            "nb-iot" -> "NB-IoT"
            else -> stationType
        }
        Text("${stringResource(R.string.cell_query_station_type)}：$type")
    }
    if (!extraInfo.isNullOrBlank()) {
        val text = remember(extraInfo) {
            parseCellExtraInfo(extraInfo).joinToString("\n") { (key, value) ->
                if (key.isBlank()) value else "$key：$value"
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.cell_query_extra_info), style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
