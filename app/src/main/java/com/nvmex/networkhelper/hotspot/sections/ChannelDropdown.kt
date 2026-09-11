package com.nvmex.networkhelper.hotspot.sections

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.hotspot.ChannelOption
import kotlin.collections.forEach

@OptIn(ExperimentalMaterial3Api::class)
@Composable
 fun ChannelDropdown(
    options: List<ChannelOption>,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val selectedText = options.firstOrNull { it.channel == value }?.display
        ?: if (value == 0) "Auto" else "CH $value"

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedText,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.hotspot_channel_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt.display) },
                    enabled = !opt.isDfs, // ✅ DFS 不让点
                    onClick = {
                        onValueChange(opt.channel)
                        expanded = false
                    }
                )
            }
        }
    }
}
