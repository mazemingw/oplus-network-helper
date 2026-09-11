package com.nvmex.networkhelper.ui.menu

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.menu.LteCellParamRow
import com.nvmex.networkhelper.model.menu.LteCellQueryBody
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import com.nvmex.networkhelper.network.model.NrCellParamItem
import com.nvmex.networkhelper.network.model.NrQueryReq
import com.nvmex.networkhelper.ui.base.toast
import com.nvmex.networkhelper.ui.map.AmapMapActivity
import com.nvmex.networkhelper.util.base.formatDateTimeUtc8
import com.nvmex.networkhelper.util.map.Gcj02
import com.nvmex.networkhelper.util.network.cellMatchLevelText
import com.nvmex.networkhelper.util.windows.WindowUtils
import com.nvmex.networkhelper.viewmodel.menu.CellQueryUiState
import com.nvmex.networkhelper.viewmodel.menu.MenuCellQueryViewModel
import kotlin.math.roundToInt

private enum class QueryRat {
    LTE, NR
}

@Composable
fun CellQueryScreen(
    panel: NetworkPanelUiState,
    vm: MenuCellQueryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val statusBarHeight = WindowUtils.getStatusBarHeight(context)
    val scroll = rememberScrollState()
    val ui by vm.ui.collectAsState()

    val defaultRat = when (panel.cellType.uppercase()) {
        "NR", "5G" -> QueryRat.NR
        else -> QueryRat.LTE
    }

    var rat by rememberSaveable { mutableStateOf(defaultRat) }

    var tacText by rememberSaveable { mutableStateOf("") }
    var arfcnText by rememberSaveable { mutableStateOf("") }
    var longIdText by rememberSaveable { mutableStateOf("") }
    var pciText by rememberSaveable { mutableStateOf("") }
    var shortCellIdText by rememberSaveable { mutableStateOf("") }

    fun autoFillFromPanel() {
        val tac = panel.tac.takeIf { it != "-" } ?: ""
        val arfcn = panel.arfcn.takeIf { it != "-" } ?: ""
        val ci = panel.ci.takeIf { it != "-" } ?: ""
        val pci = panel.pci.takeIf { it != "-" } ?: ""

        tacText = tac
        arfcnText = arfcn
        longIdText = ci
        pciText = pci

        if (rat == QueryRat.LTE) {
            val derived = ci.trim().toLongOrNull()?.let { (it and 0xFF).toInt() }
            shortCellIdText = derived?.toString() ?: ""
        } else {
            shortCellIdText = ""
        }
    }

    val tacVal = tacText.trim().toIntOrNull()
    val arfcnVal = arfcnText.trim().toIntOrNull()
    val longIdVal = longIdText.trim().takeIf { it.isNotBlank() }
    val pciVal = pciText.trim().takeIf { it.isNotBlank() }?.toIntOrNull()
    val shortCellIdVal = shortCellIdText.trim().takeIf { it.isNotBlank() }?.toIntOrNull()

    val tacError = tacText.isNotBlank() && tacVal == null
    val arfcnError = arfcnText.isNotBlank() && arfcnVal == null
    val longIdError = longIdText.isNotBlank() && longIdVal == null
    val pciError = pciText.isNotBlank() && pciVal == null
    val shortCellIdError = shortCellIdText.isNotBlank() && shortCellIdVal == null

    val canQuery = when (rat) {
        QueryRat.LTE -> {
            val eciVal = longIdVal?.toLongOrNull()
            val eciError = longIdText.isNotBlank() && eciVal == null
            val hasRequiredBase = tacVal != null && arfcnVal != null
            val hasPrimary = eciVal != null
            val hasShort = (pciVal != null && shortCellIdVal != null)

            hasRequiredBase &&
                    (hasPrimary || hasShort) &&
                    !tacError && !arfcnError && !eciError && !pciError && !shortCellIdError
        }

        QueryRat.NR -> {
            val hasGcellId = !longIdVal.isNullOrBlank()
            val hasFallback = tacVal != null || arfcnVal != null || pciVal != null

            (hasGcellId || hasFallback) &&
                    !tacError && !arfcnError && !longIdError && !pciError
        }
    }

    fun buildLteBodyOrNull(): LteCellQueryBody? {
        val tac = tacVal ?: return null
        val earfcn = arfcnVal ?: return null
        val eci = longIdVal?.toLongOrNull()

        return if (eci != null) {
            LteCellQueryBody(
                tac = tac,
                earfcn = earfcn,
                eci = eci
            )
        } else if (pciVal != null && shortCellIdVal != null) {
            LteCellQueryBody(
                tac = tac,
                earfcn = earfcn,
                pci = pciVal,
                cell_id = shortCellIdVal
            )
        } else {
            null
        }
    }

    fun buildNrBodyOrNull(): NrQueryReq? {
        val gcellId = longIdVal?.takeIf { it.isNotBlank() }
        val nrTac = tacVal
        val nrArfcn = arfcnVal
        val nrPci = pciVal

        if (gcellId.isNullOrBlank() && nrTac == null && nrArfcn == null && nrPci == null) {
            return null
        }

        return NrQueryReq(
            gcellId = gcellId,
            nrTac = nrTac,
            nrArfcn = nrArfcn,
            nrPci = nrPci
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp)
                .padding(top = statusBarHeight)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.cell_query_title), style = MaterialTheme.typography.titleLarge)

            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(stringResource(R.string.cell_query_params), style = MaterialTheme.typography.titleSmall)

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { rat = QueryRat.LTE }
                        ) {
                            Text(if (rat == QueryRat.LTE) "LTE ✓" else "LTE")
                        }

                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { rat = QueryRat.NR }
                        ) {
                            Text(if (rat == QueryRat.NR) "NR ✓" else "NR")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumField(
                            modifier = Modifier.weight(1f),
                            label = if (rat == QueryRat.LTE) "TAC (${stringResource(R.string.cell_query_required)})" else "NR TAC (${stringResource(R.string.cell_query_optional)})",
                            value = tacText,
                            onValueChange = { tacText = it },
                            isError = tacError,
                            supportingText = if (tacError) stringResource(R.string.cell_query_int_error) else null
                        )
                        NumField(
                            modifier = Modifier.weight(1f),
                            label = if (rat == QueryRat.LTE) "EARFCN (${stringResource(R.string.cell_query_required)})" else "NR ARFCN (${stringResource(R.string.cell_query_optional)})",
                            value = arfcnText,
                            onValueChange = { arfcnText = it },
                            isError = arfcnError,
                            supportingText = if (arfcnError) stringResource(R.string.cell_query_int_error) else null
                        )
                    }

                    NumField(
                        modifier = Modifier.fillMaxWidth(),
                        label = if (rat == QueryRat.LTE) {
                            stringResource(R.string.cell_query_eci_label)
                        } else {
                            stringResource(R.string.cell_query_gcell_label)
                        },
                        value = longIdText,
                        onValueChange = { longIdText = it },
                        isError = longIdError,
                        supportingText = if (rat == QueryRat.LTE) {
                            stringResource(R.string.cell_query_lte_fallback)
                        } else {
                            stringResource(R.string.cell_query_nr_fallback)
                        }
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumField(
                            modifier = Modifier.weight(1f),
                            label = if (rat == QueryRat.LTE) "PCI (${stringResource(R.string.cell_query_optional)})" else "NR PCI (${stringResource(R.string.cell_query_optional)})",
                            value = pciText,
                            onValueChange = { pciText = it },
                            isError = pciError,
                            supportingText = if (pciError) stringResource(R.string.cell_query_int_error) else null
                        )

                        if (rat == QueryRat.LTE) {
                            NumField(
                                modifier = Modifier.weight(1f),
                                label = stringResource(R.string.cell_query_cell_id_label),
                                value = shortCellIdText,
                                onValueChange = { shortCellIdText = it },
                                isError = shortCellIdError,
                                supportingText = if (shortCellIdError) stringResource(R.string.cell_query_int_error) else null
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }

                    if (rat == QueryRat.LTE) {
                        val eciVal = longIdVal?.toLongOrNull()
                        val hasRequiredBase = tacVal != null && arfcnVal != null
                        val hasPrimary = eciVal != null
                        val hasShort = (pciVal != null && shortCellIdVal != null)

                        if (hasRequiredBase && !hasPrimary && !hasShort) {
                            Text(
                                stringResource(R.string.cell_query_lte_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    } else {
                        if (longIdVal.isNullOrBlank() && tacVal == null && arfcnVal == null && pciVal == null) {
                            Text(
                                stringResource(R.string.cell_query_nr_hint),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { autoFillFromPanel() }) {
                            Text(stringResource(R.string.cell_query_auto_fill))
                        }

                        Button(
                            enabled = canQuery && ui !is CellQueryUiState.Loading,
                            onClick = {
                                when (rat) {
                                    QueryRat.LTE -> {
                                        val body = buildLteBodyOrNull()
                                        if (body == null) {
                                            toast(context, context.getString(R.string.cell_query_lte_incomplete))
                                        } else {
                                            vm.query(body)
                                        }
                                    }

                                    QueryRat.NR -> {
                                        val body = buildNrBodyOrNull()
                                        if (body == null) {
                                            toast(context, context.getString(R.string.cell_query_nr_incomplete))
                                        } else {
                                            vm.queryNr(body)
                                        }
                                    }
                                }
                            }
                        ) {
                            Text(stringResource(R.string.cell_query_action))
                        }

                        TextButton(
                            enabled = ui !is CellQueryUiState.Idle,
                            onClick = { vm.reset() }
                        ) {
                            Text(stringResource(R.string.cell_query_clear_results))
                        }
                    }
                }
            }

            when (val s = ui) {
                CellQueryUiState.Idle -> {
                    Text(stringResource(R.string.cell_query_idle), style = MaterialTheme.typography.bodySmall)
                }

                CellQueryUiState.Loading -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.cell_query_loading), style = MaterialTheme.typography.bodySmall)
                }

                is CellQueryUiState.Error -> {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                stringResource(R.string.cell_query_failed),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(s.msg, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                is CellQueryUiState.LteSuccess -> {
                    val resp = s.resp

                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(stringResource(R.string.cell_query_lte_results), style = MaterialTheme.typography.titleSmall)
                            KeyValueRow(stringResource(R.string.cell_query_match_level), cellMatchLevelText(resp.match_level))
                            KeyValueRow(stringResource(R.string.cell_query_hit_count), resp.count.toString())
                        }
                    }

                    if (resp.data.isEmpty()) {
                        Text(stringResource(R.string.cell_query_no_lte), style = MaterialTheme.typography.bodySmall)
                    } else {
                        resp.data.forEach { row ->
                            CellResultCard(row)
                        }
                    }
                }

                is CellQueryUiState.NrSuccess -> {
                    val resp = s.resp

                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(stringResource(R.string.cell_query_nr_results), style = MaterialTheme.typography.titleSmall)
                            KeyValueRow(stringResource(R.string.cell_query_match_level), cellMatchLevelText(resp.match_level))
                            KeyValueRow(stringResource(R.string.cell_query_hit_count), resp.count.toString())
                            KeyValueRow(stringResource(R.string.cell_query_total_count), resp.total_count.toString())
                            KeyValueRow(stringResource(R.string.cell_query_truncated), if (resp.truncated) stringResource(R.string.state_yes) else stringResource(R.string.state_no))
                            if (!resp.reason.isNullOrBlank()) {
                                KeyValueRow(stringResource(R.string.cell_query_reason), resp.reason)
                            }
                        }
                    }

                    if (resp.data.isEmpty()) {
                        Text(stringResource(R.string.cell_query_no_nr), style = MaterialTheme.typography.bodySmall)
                    } else {
                        resp.data.forEach { row ->
                            NrCellResultCard(row)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumField(
    modifier: Modifier,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    supportingText: String?
) {
    OutlinedTextField(
        modifier = modifier,
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = supportingText?.let { { Text(it) } }
    )
}

@Composable
private fun CellResultCard(row: LteCellParamRow) {
    val context = LocalContext.current

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.cell_query_cell_name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(row.cell_name ?: stringResource(R.string.cell_query_unnamed), style = MaterialTheme.typography.titleMedium)
            }

            KeyValueRow("ECI", row.eci?.toString() ?: "-")
            KeyValueRow("eNodeB ID", row.enodeb_id?.toString() ?: "-")
            KeyValueRow("cell_id", row.cell_id?.toString() ?: "-")

            Divider()

            KeyValueRow("TAC", row.tac?.toString() ?: "-")
            KeyValueRow("PCI", row.pci?.toString() ?: "-")
            KeyValueRow("EARFCN", row.earfcn?.toString() ?: "-")
            KeyValueRow(stringResource(R.string.cell_query_azimuth), row.azimuth?.toString() ?: "-")

            Divider()

            val lonRaw = row.longitude
            val latRaw = row.latitude
            val hasCoord = lonRaw != null && latRaw != null

            val (gcjLat, gcjLon) = if (hasCoord) {
                Gcj02.wgs84ToGcj02(latRaw!!, lonRaw!!)
            } else {
                null to null
            }

            val lonText = gcjLon?.let { format6(it) } ?: "-"
            val latText = gcjLat?.let { format6(it) } ?: "-"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.cell_query_map_coord), color = MaterialTheme.colorScheme.onSurfaceVariant)

                Text(
                    text = "$lonText, $latText",
                    color = if (hasCoord) Color(0xFF448AFF) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(enabled = hasCoord) {
                        val url = "https://uri.amap.com/marker?position=${gcjLon},${gcjLat}"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                )
            }

            InnerMapOpenButtons(
                hasCoord = hasCoord,
                onOpenWgs84 = {
                    val lat = latRaw ?: return@InnerMapOpenButtons
                    val lon = lonRaw ?: return@InnerMapOpenButtons
                    context.startActivity(
                        AmapMapActivity.createIntentForWgs84(
                            context = context,
                            lat = lat,
                            lon = lon,
                            title = row.cell_name ?: context.getString(R.string.cell_query_lte_site)
                        )
                    )
                },
                onOpenGcj02 = {
                    val lat = latRaw ?: return@InnerMapOpenButtons
                    val lon = lonRaw ?: return@InnerMapOpenButtons
                    context.startActivity(
                        AmapMapActivity.createIntentForGcj02(
                            context = context,
                            lat = lat,
                            lon = lon,
                            title = row.cell_name ?: context.getString(R.string.cell_query_lte_site)
                        )
                    )
                }
            )

            KeyValueRow(stringResource(R.string.cell_query_site_type), row.site_type ?: "-")
            KeyValueRow(stringResource(R.string.cell_query_source), row.source ?: "-")
        }
    }
}

@Composable
private fun NrCellResultCard(row: NrCellParamItem) {
    val context = LocalContext.current

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.cell_query_cell_name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(row.cell_name ?: stringResource(R.string.cell_query_unnamed), style = MaterialTheme.typography.titleMedium)
            }

            KeyValueRow("GCell ID", row.gcell_id ?: "-")
            KeyValueRow("NR TAC", row.nr_tac?.toString() ?: "-")
            KeyValueRow("NR PCI", row.nr_pci?.toString() ?: "-")
            KeyValueRow("NR ARFCN", row.nr_arfcn?.toString() ?: "-")
            KeyValueRow(stringResource(R.string.cell_query_azimuth), row.azimuth?.toString() ?: "-")

            Divider()

            val lonRaw = row.longitude
            val latRaw = row.latitude
            val hasCoord = lonRaw != null && latRaw != null

            val (gcjLat, gcjLon) = if (hasCoord) {
                Gcj02.wgs84ToGcj02(latRaw!!, lonRaw!!)
            } else {
                null to null
            }

            val lonText = gcjLon?.let { format6(it) } ?: "-"
            val latText = gcjLat?.let { format6(it) } ?: "-"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.cell_query_map_coord), color = MaterialTheme.colorScheme.onSurfaceVariant)

                Text(
                    text = "$lonText, $latText",
                    color = if (hasCoord) Color(0xFF448AFF) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(enabled = hasCoord) {
                        val url = "https://uri.amap.com/marker?position=${gcjLon},${gcjLat}"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                )
            }

            InnerMapOpenButtons(
                hasCoord = hasCoord,
                onOpenWgs84 = {
                    val lat = latRaw ?: return@InnerMapOpenButtons
                    val lon = lonRaw ?: return@InnerMapOpenButtons
                    context.startActivity(
                        AmapMapActivity.createIntentForWgs84(
                            context = context,
                            lat = lat,
                            lon = lon,
                            title = row.cell_name ?: context.getString(R.string.cell_query_nr_site)
                        )
                    )
                },
                onOpenGcj02 = {
                    val lat = latRaw ?: return@InnerMapOpenButtons
                    val lon = lonRaw ?: return@InnerMapOpenButtons
                    context.startActivity(
                        AmapMapActivity.createIntentForGcj02(
                            context = context,
                            lat = lat,
                            lon = lon,
                            title = row.cell_name ?: context.getString(R.string.cell_query_nr_site)
                        )
                    )
                }
            )

            KeyValueRow(stringResource(R.string.cell_query_site_type), row.site_type ?: "-")
            KeyValueRow(stringResource(R.string.cell_query_antenna_height), row.antenna_height?.toString() ?: "-")
            KeyValueRow(stringResource(R.string.cell_query_source), row.source ?: "-")
            KeyValueRow(stringResource(R.string.cell_query_created_at), formatDateTimeUtc8(row.created_at))
            KeyValueRow(stringResource(R.string.cell_query_updated_at), formatDateTimeUtc8(row.updated_at))
        }
    }
}

@Composable
private fun InnerMapOpenButtons(
    hasCoord: Boolean,
    onOpenWgs84: () -> Unit,
    onOpenGcj02: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.cell_query_builtin_map),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TextButton(
            onClick = onOpenWgs84,
            enabled = hasCoord
        ) {
            Text(stringResource(R.string.cell_query_open_wgs84))
        }

        TextButton(
            onClick = onOpenGcj02,
            enabled = hasCoord
        ) {
            Text(stringResource(R.string.cell_query_open_gcj02))
        }
    }
}

@Composable
private fun KeyValueRow(k: String, v: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v)
    }
}

private fun format6(d: Double): String {
    val scaled = (d * 1_000_000.0).roundToInt() / 1_000_000.0
    return scaled.toString()
}

