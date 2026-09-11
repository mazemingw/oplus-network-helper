package com.nvmex.networkhelper.viewmodel.signal

import android.annotation.SuppressLint
import android.content.Context
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class AmapLocationResult(
    val latitude: Double,
    val longitude: Double,
    val coordType: String?,
    val poiName: String?,
    val address: String?,
    val description: String?,
    val city: String?,
    val district: String?
)

class AmapLocator(
    private val context: Context
) {

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Result<AmapLocationResult> {
        return suspendCancellableCoroutine { cont ->
            val client = AMapLocationClient(context.applicationContext)
            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy

                // 单次定位
                isOnceLocation = true
                isOnceLocationLatest = true

                // 需要地址信息，不然 poiName/address 很可能不给你
                isNeedAddress = true

                // 合理超时
                httpTimeOut = 15000

                // 允许使用缓存可更快；你若追求绝对实时可关掉
                isLocationCacheEnable = true
            }

            client.setLocationOption(option)

            client.setLocationListener { loc: AMapLocation? ->
                if (loc == null) {
                    if (cont.isActive) {
                        cont.resume(Result.failure(IllegalStateException("高德定位返回为空")))
                    }
                    client.stopLocation()
                    client.onDestroy()
                    return@setLocationListener
                }

                if (loc.errorCode == 0) {
                    val result = AmapLocationResult(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        coordType = runCatching { loc.coordType }.getOrNull(),
                        poiName = runCatching { loc.poiName }.getOrNull(),
                        address = runCatching { loc.address }.getOrNull(),
                        description = runCatching { loc.description }.getOrNull(),
                        city = runCatching { loc.city }.getOrNull(),
                        district = runCatching { loc.district }.getOrNull()
                    )
                    if (cont.isActive) {
                        cont.resume(Result.success(result))
                    }
                } else {
                    val msg = buildString {
                        append("高德定位失败")
                        append("，code=").append(loc.errorCode)
                        append("，msg=").append(loc.errorInfo ?: "unknown")
                        loc.locationDetail?.takeIf { it.isNotBlank() }?.let {
                            append("，detail=").append(it)
                        }
                    }
                    if (cont.isActive) {
                        cont.resume(Result.failure(IllegalStateException(msg)))
                    }
                }

                client.stopLocation()
                client.onDestroy()
            }

            client.startLocation()

            cont.invokeOnCancellation {
                runCatching {
                    client.stopLocation()
                    client.onDestroy()
                }
            }
        }
    }
}