package com.nvmex.networkhelper.viewmodel.cellparam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nvmex.networkhelper.model.cellparam.LteCellParam
import com.nvmex.networkhelper.model.cellparam.NrCellParam
import com.nvmex.networkhelper.network.api.ApiService
import com.nvmex.networkhelper.network.base.ApiResult
import com.nvmex.networkhelper.network.base.safeApiCall
import com.nvmex.networkhelper.xposed.logger.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.nvmex.networkhelper.network.model.BatchUpsertResp
import com.nvmex.networkhelper.network.model.LteBatchUpsertReq
import com.nvmex.networkhelper.network.model.LteCellParamUploadItem
import com.nvmex.networkhelper.network.model.NrBatchUpsertReq
import com.nvmex.networkhelper.network.model.NrCellParamUploadItem
import com.nvmex.networkhelper.repository.CellParamLocalRepository

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val apiService: ApiService,
    private val localRepository: CellParamLocalRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ImportViewModel"
        private const val LOG_PREFIX = "[Import]"
        private const val DEFAULT_SOURCE = "android_import"
    }

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private var parsedNrData: List<NrCellParam> = emptyList()
    private var parsedLteData: List<LteCellParam> = emptyList()

    fun setImportType(type: ImportType) {
        Logger.log("$LOG_PREFIX $TAG - 设置导入类型: $type")
        _uiState.update {
            it.copy(
                importType = type,
                parsedDataCount = 0,
                uploadSuccess = false,
                uploadError = null,
                uploadMessage = null
            )
        }
    }

    fun setSelectedFile(uri: String, fileName: String) {
        Logger.log("$LOG_PREFIX $TAG - 设置文件URI: $uri")
        Logger.log("$LOG_PREFIX $TAG - 设置文件名: $fileName")
        _uiState.update {
            it.copy(
                fileUri = uri,
                fileName = fileName,
                uploadSuccess = false,
                uploadError = null,
                uploadMessage = null
            )
        }
    }

    fun setParsedData(data: List<Any>, type: ImportType) {
        when (type) {
            ImportType.NR -> {
                parsedNrData = data.filterIsInstance<NrCellParam>()
                parsedLteData = emptyList()
                Logger.log("$LOG_PREFIX $TAG - 解析完成: 5G NR 数据 ${parsedNrData.size} 条")
                _uiState.update {
                    it.copy(
                        parsedDataCount = parsedNrData.size,
                        uploadSuccess = false,
                        uploadError = null,
                        uploadMessage = null
                    )
                }
            }

            ImportType.LTE -> {
                parsedLteData = data.filterIsInstance<LteCellParam>()
                parsedNrData = emptyList()
                Logger.log("$LOG_PREFIX $TAG - 解析完成: 4G LTE 数据 ${parsedLteData.size} 条")
                _uiState.update {
                    it.copy(
                        parsedDataCount = parsedLteData.size,
                        uploadSuccess = false,
                        uploadError = null,
                        uploadMessage = null
                    )
                }
            }
        }
    }

    fun getParsedDataForCurrentType(): List<*> {
        return when (_uiState.value.importType) {
            ImportType.NR -> parsedNrData
            ImportType.LTE -> parsedLteData
        }
    }

    //上传到服务器
    fun uploadToServer() {
        viewModelScope.launch {
            val type = _uiState.value.importType
            val currentCount = _uiState.value.parsedDataCount

            if (currentCount <= 0) {
                _uiState.update {
                    it.copy(
                        uploadSuccess = false,
                        uploadError = "没有可上传的数据"
                    )
                }
                return@launch
            }

            Logger.log("$LOG_PREFIX $TAG - ========== 开始真实上传 ==========")
            Logger.log("$LOG_PREFIX $TAG - 数据类型: $type, 数量: $currentCount")

            _uiState.update {
                it.copy(
                    isUploading = true,
                    uploadSuccess = false,
                    uploadError = null,
                    uploadMessage = null
                )
            }

            try {
                val result = when (type) {
                    ImportType.NR -> uploadNrData(parsedNrData)
                    ImportType.LTE -> uploadLteData(parsedLteData)
                }

                if (result.isSuccess) {
                    _uiState.update {
                        it.copy(
                            isUploading = false,
                            uploadSuccess = true,
                            uploadError = null,
                            uploadMessage = result.message
                        )
                    }
                    Logger.log("$LOG_PREFIX $TAG - 上传成功: ${result.message}")
                } else {
                    _uiState.update {
                        it.copy(
                            isUploading = false,
                            uploadSuccess = false,
                            uploadError = result.error ?: "上传失败",
                            uploadMessage = null
                        )
                    }
                    Logger.logE("$LOG_PREFIX $TAG - 上传失败: ${result.error}")
                }
            } catch (e: Exception) {
                Logger.logE("$LOG_PREFIX $TAG - 上传异常: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isUploading = false,
                        uploadSuccess = false,
                        uploadError = e.message ?: "上传异常",
                        uploadMessage = null
                    )
                }
            }
        }
    }

    //上传NR Data
    private suspend fun uploadNrData(data: List<NrCellParam>): UploadResult {
        if (data.isEmpty()) {
            return UploadResult(isSuccess = false, error = "NR 数据为空")
        }

        val req = NrBatchUpsertReq(
            source = DEFAULT_SOURCE,
            items = data.map { it.toUploadItem() }
        )

        Logger.log("$LOG_PREFIX $TAG - NR 开始上传，条数: ${req.items.size}")

        return when (val result = safeApiCall { apiService.batchUpsertNrCellParams(req) }) {
            is ApiResult.Ok -> {
                val body = result.data
                val msg = buildUploadMessage(body)
                UploadResult(
                    isSuccess = true,
                    message = msg
                )
            }

            is ApiResult.HttpError -> {
                UploadResult(
                    isSuccess = false,
                    error = "HTTP ${result.code}${result.body?.let { " - $it" } ?: ""}"
                )
            }

            is ApiResult.NetworkError -> {
                UploadResult(
                    isSuccess = false,
                    error = result.message
                )
            }
        }
    }

    //上传LTE data
    private suspend fun uploadLteData(data: List<LteCellParam>): UploadResult {
        if (data.isEmpty()) {
            return UploadResult(isSuccess = false, error = "LTE 数据为空")
        }

        val req = LteBatchUpsertReq(
            source = DEFAULT_SOURCE,
            items = data.map { it.toUploadItem() }
        )

        Logger.log("$LOG_PREFIX $TAG - LTE 开始上传，条数: ${req.items.size}")

        return when (val result = safeApiCall { apiService.batchUpsertLteCellParams(req) }) {
            is ApiResult.Ok -> {
                val body = result.data
                val msg = buildUploadMessage(body)
                UploadResult(
                    isSuccess = true,
                    message = msg
                )
            }

            is ApiResult.HttpError -> {
                UploadResult(
                    isSuccess = false,
                    error = "HTTP ${result.code}${result.body?.let { " - $it" } ?: ""}"
                )
            }

            is ApiResult.NetworkError -> {
                UploadResult(
                    isSuccess = false,
                    error = result.message
                )
            }
        }
    }

    //build上传MSG
    private fun buildUploadMessage(resp: BatchUpsertResp): String {
        return "服务器已接收 ${resp.received} 条，成功 ${resp.success} 条，跳过 ${resp.skipped} 条" +
                (resp.affectedRows?.let { "，影响行数 $it" } ?: "")
    }

    //上传成功了reset
    fun reset() {
        Logger.log("$LOG_PREFIX $TAG - 重置状态")
        _uiState.value = ImportUiState()
        parsedNrData = emptyList()
        parsedLteData = emptyList()
        
    }

    //保存本地
    fun saveToLocalDatabase() {
        viewModelScope.launch {
            val type = _uiState.value.importType
            val currentCount = _uiState.value.parsedDataCount

            if (currentCount <= 0) {
                _uiState.update {
                    it.copy(
                        localSaveSuccess = false,
                        localSaveError = "没有可写入的数据",
                        localSaveMessage = null
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isSavingLocal = true,
                    localSaveSuccess = false,
                    localSaveError = null,
                    localSaveMessage = null
                )
            }

            try {
                val result = when (type) {
                    ImportType.NR -> localRepository.saveNr(parsedNrData)
                    ImportType.LTE -> localRepository.saveLte(parsedLteData)
                }

                _uiState.update {
                    it.copy(
                        isSavingLocal = false,
                        localSaveSuccess = result.success,
                        localSaveError = if (result.success) null else result.message,
                        localSaveMessage = if (result.success) result.message else null
                    )
                }
            } catch (e: Exception) {
                Logger.logE("$LOG_PREFIX $TAG - 本地写入异常: ${e.message}", e)
                _uiState.update {
                    it.copy(
                        isSavingLocal = false,
                        localSaveSuccess = false,
                        localSaveError = e.message ?: "本地写入异常",
                        localSaveMessage = null
                    )
                }
            }
        }
    }
}

private fun NrCellParam.toUploadItem(): NrCellParamUploadItem {
    return NrCellParamUploadItem(
        gcellId = gcellId,
        cellName = cellName,
        longitude = longitude,
        latitude = latitude,
        azimuth = azimuth,
        nrPci = nrPci,
        nrArfcn = nrArfcn,
        nrTac = nrTac,
        siteType = siteType,
        antennaHeight = antennaHeight,
        source = source
    )
}

private fun LteCellParam.toUploadItem(): LteCellParamUploadItem {
    return LteCellParamUploadItem(
        tac = tac,
        pci = pci,
        enodebId = enodeb_id,
        earfcn = earfcn,
        cellId = cell_id,
        eci = eci,
        localCellId = local_cell_id,
        cellName = cell_name,
        sectorId = sector_id,
        longitude = longitude,
        latitude = latitude,
        azimuth = azimuth,
        siteType = site_type,
        antennaHeight = antenna_height,
        source = source
    )
}

data class ImportUiState(
    val importType: ImportType = ImportType.NR,
    val fileUri: String? = null,
    val fileName: String? = null,
    val parsedDataCount: Int = 0,

    val isUploading: Boolean = false,
    val uploadSuccess: Boolean = false,
    val uploadError: String? = null,
    val uploadMessage: String? = null,

    val isSavingLocal: Boolean = false,
    val localSaveSuccess: Boolean = false,
    val localSaveError: String? = null,
    val localSaveMessage: String? = null
)

enum class ImportType {
    NR, LTE
}

data class UploadResult(
    val isSuccess: Boolean,
    val error: String? = null,
    val message: String? = null
)