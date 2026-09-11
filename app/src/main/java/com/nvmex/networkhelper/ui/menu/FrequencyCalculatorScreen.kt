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

private data class LteBandDef(
    val band: Int,
    val earfcnStart: Int,
    val earfcnEnd: Int,
    val dlLowMhz: Double,
    val earfcnOffset: Int,
    val ulLowMhz: Double?,
    val ulHighMhz: Double? = null,
    val duplex: String,
    val note: String? = null
)

private data class NrBandDef(
    val band: Int,
    val dlLowMhz: Double,
    val dlHighMhz: Double,
    val ulLowMhz: Double?,
    val ulHighMhz: Double?,
    val duplex: String,
    val note: String? = null
)

private fun nrArfcnToMhzGlobal(n: Int): Double? {
    val mhz = when (n) {
        in 0..599_999 -> 0.005 * n
        in 600_000..2_016_666 -> 3000.0 + 0.015 * (n - 600_000)
        in 2_016_667..3_279_165 -> 24250.08 + 0.06 * (n - 2_016_667)
        else -> return null
    }
    return mhz.takeIf { it > 0.0 }
}

private val LTE_GLOBAL_BANDS = listOf(
    LteBandDef(1, 0, 599, 2110.0, 0, 1920.0, 1980.0, "FDD"),
    LteBandDef(2, 600, 1199, 1930.0, 600, 1850.0, 1910.0, "FDD"),
    LteBandDef(3, 1200, 1949, 1805.0, 1200, 1710.0, 1785.0, "FDD"),
    LteBandDef(4, 1950, 2399, 2110.0, 1950, 1710.0, 1755.0, "FDD"),
    LteBandDef(5, 2400, 2649, 869.0, 2400, 824.0, 849.0, "FDD"),
    LteBandDef(6, 2650, 2749, 875.0, 2650, 830.0, 840.0, "FDD"),
    LteBandDef(7, 2750, 3449, 2620.0, 2750, 2500.0, 2570.0, "FDD"),
    LteBandDef(8, 3450, 3799, 925.0, 3450, 880.0, 915.0, "FDD"),
    LteBandDef(9, 3800, 4149, 1844.9, 3800, 1749.9, 1784.9, "FDD"),
    LteBandDef(10, 4150, 4749, 2110.0, 4150, 1710.0, 1770.0, "FDD"),
    LteBandDef(11, 4750, 4949, 1475.9, 4750, 1427.9, 1447.9, "FDD"),
    LteBandDef(12, 5010, 5179, 729.0, 5010, 699.0, 716.0, "FDD"),
    LteBandDef(13, 5180, 5279, 746.0, 5180, 777.0, 787.0, "FDD"),
    LteBandDef(14, 5280, 5379, 758.0, 5280, 788.0, 798.0, "FDD"),
    LteBandDef(17, 5730, 5849, 734.0, 5730, 704.0, 716.0, "FDD"),
    LteBandDef(18, 5850, 5999, 860.0, 5850, 815.0, 830.0, "FDD"),
    LteBandDef(19, 6000, 6149, 875.0, 6000, 830.0, 845.0, "FDD"),
    LteBandDef(20, 6150, 6449, 791.0, 6150, 832.0, 862.0, "FDD"),
    LteBandDef(21, 6450, 6599, 1495.9, 6450, 1447.9, 1462.9, "FDD"),
    LteBandDef(22, 6600, 7399, 3510.0, 6600, 3410.0, 3490.0, "FDD"),
    LteBandDef(23, 7500, 7699, 2180.0, 7500, 2000.0, 2020.0, "FDD"),
    LteBandDef(24, 7700, 8039, 1525.0, 7700, 1626.5, 1660.5, "FDD"),
    LteBandDef(25, 8040, 8689, 1930.0, 8040, 1850.0, 1915.0, "FDD"),
    LteBandDef(26, 8690, 9039, 859.0, 8690, 814.0, 849.0, "FDD"),
    LteBandDef(27, 9040, 9209, 852.0, 9040, 807.0, 824.0, "FDD"),
    LteBandDef(28, 9210, 9659, 758.0, 9210, 703.0, 748.0, "FDD"),
    LteBandDef(29, 9660, 9769, 717.0, 9660, null, null, "SDL", "downlink only"),
    LteBandDef(30, 9770, 9869, 2350.0, 9770, 2305.0, 2315.0, "FDD"),
    LteBandDef(31, 9870, 9919, 462.5, 9870, 452.5, 457.5, "FDD"),
    LteBandDef(32, 9920, 10359, 1452.0, 9920, null, null, "SDL", "downlink only"),
    LteBandDef(33, 36000, 36199, 1900.0, 36000, null, null, "TDD"),
    LteBandDef(34, 36200, 36349, 2010.0, 36200, null, null, "TDD"),
    LteBandDef(35, 36350, 36949, 1850.0, 36350, null, null, "TDD"),
    LteBandDef(36, 36950, 37549, 1930.0, 36950, null, null, "TDD"),
    LteBandDef(37, 37550, 37749, 1910.0, 37550, null, null, "TDD"),
    LteBandDef(38, 37750, 38249, 2570.0, 37750, null, null, "TDD"),
    LteBandDef(39, 38250, 38649, 1880.0, 38250, null, null, "TDD"),
    LteBandDef(40, 38650, 39649, 2300.0, 38650, null, null, "TDD"),
    LteBandDef(41, 39650, 41589, 2496.0, 39650, null, null, "TDD"),
    LteBandDef(42, 41590, 43589, 3400.0, 41590, null, null, "TDD"),
    LteBandDef(43, 43590, 45589, 3600.0, 43590, null, null, "TDD"),
    LteBandDef(44, 45590, 46589, 703.0, 45590, null, null, "TDD"),
    LteBandDef(46, 46790, 54539, 5150.0, 46790, null, null, "TDD"),
    LteBandDef(47, 54540, 55239, 5855.0, 54540, null, null, "TDD"),
    LteBandDef(48, 55240, 56739, 3550.0, 55240, null, null, "TDD"),
    LteBandDef(49, 56740, 58239, 3550.0, 56740, null, null, "TDD"),
    LteBandDef(50, 58240, 59089, 1432.0, 58240, null, null, "TDD"),
    LteBandDef(51, 59090, 59139, 1427.0, 59090, null, null, "TDD"),
    LteBandDef(52, 59140, 60139, 3300.0, 59140, null, null, "TDD"),
    LteBandDef(53, 60140, 60254, 2483.5, 60140, null, null, "TDD"),
    LteBandDef(65, 65536, 66435, 2110.0, 65536, 1920.0, 2010.0, "FDD"),
    LteBandDef(66, 66436, 67335, 2110.0, 66436, 1710.0, 1780.0, "FDD"),
    LteBandDef(67, 67336, 67535, 738.0, 67336, null, null, "SDL", "downlink only"),
    LteBandDef(68, 67536, 67835, 753.0, 67536, 698.0, 728.0, "FDD"),
    LteBandDef(69, 67836, 68335, 2570.0, 67836, null, null, "SDL", "downlink only"),
    LteBandDef(70, 68336, 68585, 1995.0, 68336, 1695.0, 1710.0, "FDD"),
    LteBandDef(71, 68586, 68935, 617.0, 68586, 663.0, 698.0, "FDD"),
    LteBandDef(72, 68936, 68985, 461.0, 68936, 451.0, 456.0, "FDD"),
    LteBandDef(73, 68986, 69035, 460.0, 68986, 450.0, 455.0, "FDD"),
    LteBandDef(74, 69036, 69465, 1475.0, 69036, 1427.0, 1470.0, "FDD"),
    LteBandDef(85, 70366, 70545, 728.0, 70366, 698.0, 716.0, "FDD"),
    LteBandDef(87, 70546, 70595, 420.0, 70546, 410.0, 415.0, "FDD"),
    LteBandDef(88, 70596, 70645, 422.0, 70596, 412.0, 417.0, "FDD")
)

private val NR_GLOBAL_BANDS = listOf(
    NrBandDef(1, 2110.0, 2170.0, 1920.0, 1980.0, "FDD"),
    NrBandDef(2, 1930.0, 1990.0, 1850.0, 1910.0, "FDD"),
    NrBandDef(3, 1805.0, 1880.0, 1710.0, 1785.0, "FDD"),
    NrBandDef(5, 869.0, 894.0, 824.0, 849.0, "FDD"),
    NrBandDef(7, 2620.0, 2690.0, 2500.0, 2570.0, "FDD"),
    NrBandDef(8, 925.0, 960.0, 880.0, 915.0, "FDD"),
    NrBandDef(12, 729.0, 746.0, 699.0, 716.0, "FDD"),
    NrBandDef(13, 746.0, 756.0, 777.0, 787.0, "FDD"),
    NrBandDef(14, 758.0, 768.0, 788.0, 798.0, "FDD"),
    NrBandDef(18, 860.0, 875.0, 815.0, 830.0, "FDD"),
    NrBandDef(20, 791.0, 821.0, 832.0, 862.0, "FDD"),
    NrBandDef(24, 1525.0, 1559.0, 1626.5, 1660.5, "FDD"),
    NrBandDef(25, 1930.0, 1995.0, 1850.0, 1915.0, "FDD"),
    NrBandDef(26, 859.0, 894.0, 814.0, 849.0, "FDD"),
    NrBandDef(28, 758.0, 803.0, 703.0, 748.0, "FDD"),
    NrBandDef(29, 717.0, 728.0, null, null, "SDL", "downlink only"),
    NrBandDef(30, 2350.0, 2360.0, 2305.0, 2315.0, "FDD"),
    NrBandDef(34, 2010.0, 2025.0, null, null, "TDD"),
    NrBandDef(38, 2570.0, 2620.0, null, null, "TDD"),
    NrBandDef(39, 1880.0, 1920.0, null, null, "TDD"),
    NrBandDef(40, 2300.0, 2400.0, null, null, "TDD"),
    NrBandDef(41, 2496.0, 2690.0, null, null, "TDD"),
    NrBandDef(46, 5150.0, 5925.0, null, null, "TDD"),
    NrBandDef(48, 3550.0, 3700.0, null, null, "TDD"),
    NrBandDef(50, 1432.0, 1517.0, null, null, "TDD"),
    NrBandDef(51, 1427.0, 1432.0, null, null, "TDD"),
    NrBandDef(53, 2483.5, 2495.0, null, null, "TDD"),
    NrBandDef(65, 2110.0, 2200.0, 1920.0, 2010.0, "FDD"),
    NrBandDef(66, 2110.0, 2200.0, 1710.0, 1780.0, "FDD"),
    NrBandDef(67, 738.0, 758.0, null, null, "SDL", "downlink only"),
    NrBandDef(70, 1995.0, 2020.0, 1695.0, 1710.0, "FDD"),
    NrBandDef(71, 617.0, 652.0, 663.0, 698.0, "FDD"),
    NrBandDef(74, 1475.0, 1518.0, 1427.0, 1470.0, "FDD"),
    NrBandDef(75, 1432.0, 1517.0, null, null, "SDL", "downlink only"),
    NrBandDef(76, 1427.0, 1432.0, null, null, "SDL", "downlink only"),
    NrBandDef(77, 3300.0, 4200.0, null, null, "TDD"),
    NrBandDef(78, 3300.0, 3800.0, null, null, "TDD"),
    NrBandDef(79, 4400.0, 5000.0, null, null, "TDD"),
    NrBandDef(80, 1710.0, 1785.0, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(81, 880.0, 915.0, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(82, 832.0, 862.0, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(83, 703.0, 748.0, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(84, 1920.0, 1980.0, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(85, 728.0, 746.0, 698.0, 716.0, "FDD"),
    NrBandDef(86, 1710.0, 1780.0, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(89, 824.0, 849.0, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(90, 2496.0, 2690.0, null, null, "TDD"),
    NrBandDef(91, 1427.0, 1432.0, 832.0, 862.0, "FDD"),
    NrBandDef(92, 1432.0, 1517.0, 832.0, 862.0, "FDD"),
    NrBandDef(93, 1427.0, 1432.0, 880.0, 915.0, "FDD"),
    NrBandDef(94, 1432.0, 1517.0, 880.0, 915.0, "FDD"),
    NrBandDef(95, 2010.0, 2025.0, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(96, 5925.0, 7125.0, null, null, "TDD"),
    NrBandDef(97, 2300.0, 2400.0, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(98, 1880.0, 1920.0, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(99, 1626.5, 1660.5, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(100, 874.4, 880.0, 919.4, 925.0, "FDD"),
    NrBandDef(101, 1900.0, 1910.0, null, null, "TDD"),
    NrBandDef(102, 5925.0, 6425.0, null, null, "TDD"),
    NrBandDef(104, 6425.0, 7125.0, null, null, "TDD"),
    NrBandDef(105, 663.0, 703.0, null, null, "SUL", "uplink supplementary band"),
    NrBandDef(257, 26500.0, 29500.0, null, null, "TDD", "FR2"),
    NrBandDef(258, 24250.0, 27500.0, null, null, "TDD", "FR2"),
    NrBandDef(259, 39500.0, 43500.0, null, null, "TDD", "FR2"),
    NrBandDef(260, 37000.0, 40000.0, null, null, "TDD", "FR2"),
    NrBandDef(261, 27500.0, 28350.0, null, null, "TDD", "FR2"),
    NrBandDef(262, 47200.0, 48200.0, null, null, "TDD", "FR2")
)

private fun formatMhz(value: Double): String {
    return String.format(Locale.US, "%.2f MHz", value)
}
