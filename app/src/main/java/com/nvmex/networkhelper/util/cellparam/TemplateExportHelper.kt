package com.nvmex.networkhelper.util.cellparam

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.nvmex.networkhelper.viewmodel.cellparam.ImportType

object TemplateExportHelper {

    private const val DIR_NAME = "NetworkHelper"

    data class ExportResult(
        val success: Boolean,
        val fileName: String,
        val message: String
    )

    data class BatchExportResult(
        val successCount: Int,
        val failedCount: Int,
        val messages: List<String>
    )

    fun exportAllTemplates(context: Context): BatchExportResult {
        val chinese = isChinese(context)
        val tasks = listOf(
            Triple(if (chinese) "NR工参导入模板.csv" else "NR_Cell_Params_Template.csv", "text/csv", buildTemplateCsv(ImportType.NR)),
            Triple(if (chinese) "LTE工参导入模板.csv" else "LTE_Cell_Params_Template.csv", "text/csv", buildTemplateCsv(ImportType.LTE)),
            Triple(if (chinese) "NR工参字段说明.txt" else "NR_Field_Guide.txt", "text/plain", buildFieldDescription(ImportType.NR, chinese)),
            Triple(if (chinese) "LTE工参字段说明.txt" else "LTE_Field_Guide.txt", "text/plain", buildFieldDescription(ImportType.LTE, chinese))
        )

        val results = tasks.map { (fileName, mimeType, content) ->
            writeToDownloads(context, fileName, mimeType, content)
        }

        return BatchExportResult(
            successCount = results.count { it.success },
            failedCount = results.count { !it.success },
            messages = results.map { it.message }
        )
    }

    fun getFieldDescription(context: Context, type: ImportType): String {
        return buildFieldDescription(type, isChinese(context))
    }

    private fun isChinese(context: Context): Boolean {
        return context.resources.configuration.locales[0].language.equals("zh", ignoreCase = true)
    }

    private fun writeToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String
    ): ExportResult {
        val chinese = isChinese(context)
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/$DIR_NAME"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException(if (chinese) "无法创建下载文件" else "Unable to create download file")

            resolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
                os.flush()
            } ?: throw IllegalStateException(if (chinese) "无法打开输出流" else "Unable to open output stream")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            ExportResult(
                success = true,
                fileName = fileName,
                message = if (chinese) {
                    "已生成：Download/$DIR_NAME/$fileName"
                } else {
                    "Generated: Download/$DIR_NAME/$fileName"
                }
            )
        } catch (e: Exception) {
            ExportResult(
                success = false,
                fileName = fileName,
                message = if (chinese) {
                    "$fileName 生成失败：${e.message ?: "未知错误"}"
                } else {
                    "$fileName failed: ${e.message ?: "unknown error"}"
                }
            )
        }
    }

    private fun buildTemplateCsv(type: ImportType): String {
        return when (type) {
            ImportType.NR -> {
                buildString {
                    appendLine("GCellID,CellName,Longitude,Latitude,Azimuth,NR_PCI,NR_ARFCN,NR_TAC,SiteType,AntennaHeight,Source")
                    appendLine("17236832263,广西壮族自治区南宁市某测试站,108.3252,22.8522,30,783,504990,12345,宏站,35.5,manual")
                    appendLine("17236832264,广西壮族自治区南宁市某测试站2,108.3260,22.8530,120,125,635334,12346,室分,18.0,manual")
                }
            }

            ImportType.LTE -> {
                buildString {
                    appendLine("tac,pci,enodeb_id,earfcn,cell_id,eci,local_cell_id,cell_name,sector_id,longitude,latitude,azimuth,site_type,antenna_height,source")
                    appendLine("1001,123,45678,1650,1,11693569,1,南宁测试LTE小区A,1,108.3252,22.8522,30,宏站,35.0,manual")
                    appendLine("1001,124,45678,1650,2,11693570,2,南宁测试LTE小区B,2,108.3260,22.8530,150,宏站,35.0,manual")
                }
            }
        }
    }

    private fun buildFieldDescription(type: ImportType, chinese: Boolean): String {
        if (!chinese) {
            return when (type) {
                ImportType.NR -> {
                    """
                    NR Field Guide

                    Required fields:
                    1. GCellID
                       Global cell identity. Fill in the NCI.

                    2. CellName
                       Cell name.

                    3. Longitude
                       Longitude. Use WGS-84 for upload. GCJ-02 is accepted for local import.

                    4. Latitude
                       Latitude. Use WGS-84 for upload. GCJ-02 is accepted for local import.

                    5. Azimuth
                       Antenna azimuth, 0-359.

                    6. NR_PCI
                       NR PCI.

                    7. NR_ARFCN
                       NR frequency point.

                    Optional fields:
                    8. NR_TAC
                       Tracking area code.

                    9. SiteType
                       Site type, such as macro / indoor / micro.

                    10. AntennaHeight
                        Antenna height in meters.

                    11. Source
                        Data source, such as manual / supplier / fieldtest.

                    Notes:
                    - Use the template headers directly when possible.
                    - For uploads, make sure coordinates are WGS-84.
                    """.trimIndent()
                }

                ImportType.LTE -> {
                    """
                    LTE Field Guide

                    Required fields:
                    1. tac
                       Tracking area code.

                    2. pci
                       Physical cell ID.

                    3. enodeb_id
                       eNodeB ID.

                    4. earfcn
                       LTE frequency point.

                    5. cell_id
                       Cell ID.

                    6. eci
                       ECI.

                    7. local_cell_id
                       Local cell ID.

                    8. cell_name
                       Cell name.

                    9. longitude
                       Longitude. Use WGS-84 for upload. GCJ-02 is accepted for local import.

                    10. latitude
                        Latitude. Use WGS-84 for upload. GCJ-02 is accepted for local import.

                    11. azimuth
                        Antenna azimuth.

                    Optional fields:
                    12. sector_id
                        Sector ID.

                    13. site_type
                        Site type.

                    14. antenna_height
                        Antenna height.

                    15. source
                        Data source.

                    16. created_at
                        Created time. Leave empty if unknown.

                    Notes:
                    - Keep headers exactly the same as the template when possible.
                    - For uploads, make sure coordinates are WGS-84.
                    """.trimIndent()
                }
            }
        }

        return when (type) {
            ImportType.NR -> {
                """
                NR 工参字段说明

                必填字段：
                1. GCellID
                   小区全局标识，填NCI！！

                2. CellName
                   小区名称

                3. Longitude
                   经度-确保WGS84，GCJ02的请导入本地用

                4. Latitude
                   纬度-确保WGS84，GCJ02的请导入本地用

                5. Azimuth
                   方位角，0~359

                6. NR_PCI
                   NR PCI

                7. NR_ARFCN
                   NR 频点号

                可选字段：
                8. NR_TAC
                   跟踪区码

                9. SiteType
                   站型，例如宏站 / 室分 / 微站

                10. AntennaHeight
                    天线挂高，单位米

                11. Source
                    数据来源，例如 manual / supplier / fieldtest

                注意：
                - 表头建议直接使用模板，不要手改成奇怪写法
                - 坐标确保WGS84，GCJ02的请导入本地用
                """.trimIndent()
            }

            ImportType.LTE -> {
                """
                LTE 工参字段说明

                必填字段：
                1. tac
                   跟踪区码

                2. pci
                   物理小区标识

                3. enodeb_id
                   基站 ID

                4. earfcn
                   LTE 频点号

                5. cell_id
                   小区 ID

                6. eci
                   ECI

                7. local_cell_id
                   本地小区 ID

                8. cell_name
                   小区名称

                9. longitude
                   经度-确保WGS84，GCJ02的请导入本地用

                10. latitude
                    纬度-确保WGS84，GCJ02的请导入本地用

                11. azimuth
                    方位角

                可选字段：
                12. sector_id
                    扇区 ID

                13. site_type
                    站型

                14. antenna_height
                    天线挂高

                15. source
                    数据来源

                16. created_at
                    创建时间(不填)

                注意：
                - 表头最好与模板完全一致
                - 不要乱改列名-坐标确保WGS84，GCJ02的请导入本地用
                """.trimIndent()
            }
        }
    }
}
