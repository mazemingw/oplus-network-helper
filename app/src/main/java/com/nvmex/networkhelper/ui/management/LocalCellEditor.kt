package com.nvmex.networkhelper.ui.management

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.nvmex.networkhelper.db.entity.ManagedCell
import com.nvmex.networkhelper.repository.CellParamLocalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LocalCellViewModel @Inject constructor(val repository: CellParamLocalRepository) : ViewModel()

@Composable
fun LocalCellEditor(initial: ManagedCell, repository: CellParamLocalRepository, onDismiss: () -> Unit, onSaved: () -> Unit,
    replaceLegacy: Boolean = false) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var lat by remember(initial) { mutableStateOf(initial.latitude?.toString().orEmpty()) }
    var lng by remember(initial) { mutableStateOf(initial.longitude?.toString().orEmpty()) }
    var azimuth by remember(initial) { mutableStateOf(initial.azimuth?.toString().orEmpty()) }
    var tac by remember(initial) { mutableStateOf(initial.tac?.toString().orEmpty()) }
    var arfcn by remember(initial) { mutableStateOf(initial.arfcn?.toString().orEmpty()) }
    var pci by remember(initial) { mutableStateOf(initial.pci?.toString().orEmpty()) }
    var siteType by remember(initial) { mutableStateOf(initial.siteType.orEmpty()) }
    var antennaHeight by remember(initial) { mutableStateOf(initial.antennaHeight?.toString().orEmpty()) }
    var sectorId by remember(initial) { mutableStateOf(initial.sectorId?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun optionalInt(value: String): Int? = if (value.isBlank()) null else value.toIntOrNull() ?: throw IllegalArgumentException("整数格式无效")
    fun optionalDouble(value: String): Double? = if (value.isBlank()) null else value.toDoubleOrNull() ?: throw IllegalArgumentException("坐标格式无效")
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("编辑本地工参") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${initial.rat} · ${initial.ci}")
            OutlinedTextField(name, { name = it }, label = { Text("基站名称") })
            OutlinedTextField(lat, { lat = it }, label = { Text("纬度（WGS84）") })
            OutlinedTextField(lng, { lng = it }, label = { Text("经度（WGS84）") })
            OutlinedTextField(azimuth, { azimuth = it }, label = { Text("方位角 0–359°（可留空）") })
            OutlinedTextField(tac, { tac = it }, label = { Text("TAC") })
            OutlinedTextField(arfcn, { arfcn = it }, label = { Text("频点") })
            OutlinedTextField(pci, { pci = it }, label = { Text("PCI") })
            OutlinedTextField(siteType, { siteType = it }, label = { Text("站型（可留空）") })
            OutlinedTextField(antennaHeight, { antennaHeight = it }, label = { Text("天线挂高（米，可留空）") })
            if (initial.rat == "LTE") OutlinedTextField(sectorId, { sectorId = it }, label = { Text("扇区号（可留空）") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                runCatching {
                    val row = initial.copy(name = name.trim(), latitude = optionalDouble(lat), longitude = optionalDouble(lng),
                        azimuth = optionalInt(azimuth), tac = optionalInt(tac), arfcn = optionalInt(arfcn), pci = optionalInt(pci),
                        siteType = siteType.takeIf { it.isNotBlank() }, antennaHeight = optionalDouble(antennaHeight), sectorId = optionalInt(sectorId))
                    withContext(Dispatchers.IO) { repository.saveManaged(row, replaceLegacy) }
                }.onSuccess { onSaved(); onDismiss() }.onFailure { error = it.message ?: "本地保存失败" }
                busy = false
            }
        }) { Text(if (busy) "保存中…" else "保存到本地") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable
fun LocalCellActions(initial: ManagedCell, onChanged: () -> Unit, legacyLocal: Boolean = false, vm: LocalCellViewModel = hiltViewModel()) {
    var owned by remember(initial.rat, initial.ci) { mutableStateOf<ManagedCell?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(initial.rat, initial.ci, revision) { owned = withContext(Dispatchers.IO) { vm.repository.findManaged(initial.rat, initial.ci) } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { edit = true }) { Text(if (owned == null && !legacyLocal) "保存为本地副本" else "编辑本地") }
        if (owned != null || legacyLocal) TextButton(onClick = { delete = true }) { Text("删除本地") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (edit) LocalCellEditor(owned ?: initial, vm.repository, { edit = false }, { revision++; onChanged() }, replaceLegacy = legacyLocal)
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除本地工参？") },
        text = { Text(initial.name.ifBlank { initial.ci }) }, confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) {
                        vm.repository.deleteManaged(initial.rat, initial.ci)
                        if (legacyLocal) vm.repository.deleteLegacy(initial.rat, initial.ci)
                    } }
                        .onSuccess { revision++; delete = false; onChanged() }.onFailure { error = it.message }
                }
            }) { Text("删除") }
        }, dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
}
