package com.nvmex.networkhelper.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.util.network.LTE_GLOBAL_BANDS
import com.nvmex.networkhelper.util.network.NR_GLOBAL_BANDS
import java.util.Locale

private enum class RatMode {
    AUTO, NR, LTE
}

private data class FrequencyCalcResult(
    val rat: String,
    val band: String,
    val duplex: String,
    val dlMhz: Double,
    val ulMhz: Double? = null,
    val note: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrequencyCalculatorScreen(
    onBack: () -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(RatMode.AUTO) }

    val arfcn = input.trim().toIntOrNull()
    val results = remember(arfcn, mode) {
        calculateFrequencyResults(arfcn, mode)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.freq_calc_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = input,
                    onValueChange = { value -> input = value.filter { it.isDigit() }.take(8) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.freq_calc_input_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == RatMode.AUTO,
                        onClick = { mode = RatMode.AUTO },
                        label = { Text(stringResource(R.string.freq_calc_mode_auto)) }
                    )
                    FilterChip(
                        selected = mode == RatMode.NR,
                        onClick = { mode = RatMode.NR },
                        label = { Text("NR") }
                    )
                    FilterChip(
                        selected = mode == RatMode.LTE,
                        onClick = { mode = RatMode.LTE },
                        label = { Text("LTE") }
                    )
                }
            }

            when {
                input.isBlank() -> {
                    item {
                        HintCard(text = stringResource(R.string.freq_calc_empty_hint))
                    }
                }

                arfcn == null -> {
                    item {
                        HintCard(text = stringResource(R.string.freq_calc_invalid))
                    }
                }

                results.isEmpty() -> {
                    item {
                        HintCard(text = stringResource(R.string.freq_calc_no_match))
                    }
                }

                else -> {
                    items(results) { result ->
                        FrequencyResultCard(result = result)
                    }
                }
            }
        }
    }
}

@Composable
private fun FrequencyResultCard(result: FrequencyCalcResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${result.rat} ${result.band}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            FrequencyRow(stringResource(R.string.freq_calc_duplex), result.duplex)
            FrequencyRow(stringResource(R.string.freq_calc_downlink), formatMhz(result.dlMhz))
            result.ulMhz?.let {
                FrequencyRow(stringResource(R.string.freq_calc_uplink), formatMhz(it))
            }
            result.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FrequencyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HintCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.freq_calc_scope_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun calculateFrequencyResults(arfcn: Int?, mode: RatMode): List<FrequencyCalcResult> {
    if (arfcn == null) return emptyList()
    val out = mutableListOf<FrequencyCalcResult>()

    if (mode == RatMode.AUTO || mode == RatMode.NR) {
        val mhz = nrArfcnToMhzGlobal(arfcn)
        if (mhz != null) {
            NR_GLOBAL_BANDS
                .filter { mhz in it.dlLowMhz..it.dlHighMhz }
                .forEach { band ->
                    out += FrequencyCalcResult(
                        rat = "NR",
                        band = "N${band.band}",
                        duplex = band.duplex,
                        dlMhz = mhz,
                        ulMhz = band.ulLowMhz?.let { ulLow ->
                            band.ulHighMhz?.let {
                                val ul = ulLow + (mhz - band.dlLowMhz)
                                ul.takeIf { value -> value in ulLow..it }
                            }
                        },
                        note = band.note
                    )
                }
        }
    }

    if (mode == RatMode.AUTO || mode == RatMode.LTE) {
        LTE_GLOBAL_BANDS
            .filter { arfcn in it.earfcnStart..it.earfcnEnd }
            .forEach { band ->
                val dlMhz = band.dlLowMhz + 0.1 * (arfcn - band.earfcnOffset)
                val ulMhz = band.ulLowMhz?.let { ulLow ->
                    val ul = ulLow + 0.1 * (arfcn - band.earfcnOffset)
                    ul.takeIf { value -> value in ulLow..(band.ulHighMhz ?: ulLow) }
                }
                out += FrequencyCalcResult(
                    rat = "LTE",
                    band = "B${band.band}",
                    duplex = band.duplex,
                    dlMhz = dlMhz,
                    ulMhz = ulMhz,
                    note = band.note
                )
            }
    }

    return out.sortedWith(compareBy<FrequencyCalcResult> { it.rat }.thenBy { it.band.filter(Char::isDigit).toIntOrNull() ?: 0 })
}


private fun nrArfcnToMhzGlobal(n: Int): Double? {
    val mhz = when (n) {
        in 0..599_999 -> 0.005 * n
        in 600_000..2_016_666 -> 3000.0 + 0.015 * (n - 600_000)
        in 2_016_667..3_279_165 -> 24250.08 + 0.06 * (n - 2_016_667)
        else -> return null
    }
    return mhz.takeIf { it > 0.0 }
}


private fun formatMhz(value: Double): String {
    return String.format(Locale.US, "%.2f MHz", value)
}
