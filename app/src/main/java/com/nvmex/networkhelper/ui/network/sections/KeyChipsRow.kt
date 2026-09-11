package com.nvmex.networkhelper.ui.network.sections


import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.model.network.NrCaCarrier


data class CaChip(
    val text: String,
    val active: Boolean
)

 fun isCarrierActivated(c: NrCaCarrier): Boolean {
    // 2 -> "Configured Activated"
    return c.dlStateRaw == 2 || c.ulStateRaw == 2
}


@Composable
fun KeyChipsRow(
    k: String,
    chips: List<CaChip>,
    activeColor: Color = Color(0xFF448AFF),
    inactiveColor: Color = Color(0xFFFF5722),
    placeholder: String = "-",
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(k, style = MaterialTheme.typography.bodyMedium)

        if (chips.isEmpty()) {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        } else {
            val scrollState = rememberScrollState()

            Row(
                modifier = Modifier.horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                chips.forEach { chip ->
                    val bg = if (chip.active) activeColor else inactiveColor
                    NrCaBadge(text = chip.text, backgroundColor = bg)
                }
            }
        }
    }
}



