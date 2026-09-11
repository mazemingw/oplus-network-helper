package com.nvmex.networkhelper.util.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.telephony.CellInfo
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.nvmex.networkhelper.model.network.NetworkPanelUiState
import java.util.concurrent.ConcurrentHashMap

class TelephonySnapshotterMultiSim(private val context: Context) {

    private val sm = context.getSystemService(SubscriptionManager::class.java)
    private val tm = context.getSystemService(TelephonyManager::class.java)

    // ✅ 每个 subId 复用一个 TelephonySnapshotter
    private val perSubSnapshotter = ConcurrentHashMap<Int, TelephonySnapshotter>()


    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(LocationManager::class.java)
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun hasPhonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun getActiveSims() = sm.activeSubscriptionInfoList ?: emptyList()

    private fun snapshotterFor(subId: Int): TelephonySnapshotter {
        return perSubSnapshotter.getOrPut(subId) {
            val tmSub = tm.createForSubscriptionId(subId)
            TelephonySnapshotter(context, tmSub)
        }
    }
    /**
     * ✅ 关键点：不管底层数据变不变，每次都强制 updatedAt 刷新
     *
     * 另外：这里先“每卡各自 serving cell 优先”，抓不到就让它 unknown
     */
    fun snapshotForSubId(
        subId: Int,
        cellInfosOverride: List<CellInfo>? = null,
        serviceStateOverride: ServiceState? = null,
        signalStrengthOverride: SignalStrength? = null,
        linkRateOverride: CellularLinkRate? = null,
        displayInfoOverride: DisplayInfoSnapshot? = null,
    ): NetworkPanelUiState {

        val hasPhone = hasPhonePermission()
        val hasLoc = hasLocationPermission()
        val locEnabled = isLocationEnabled()

        if (!hasPhone) {
            return NetworkPanelUiState(
                hasPhonePermission = false,
                hasLocationPermission = hasLoc,
                isLocationEnabled = locEnabled,
                lastError = "缺少电话权限：无法读取 SIM 信息",
                updatedAt = System.currentTimeMillis(),
                subId = subId
            )
        }

        val snap = snapshotterFor(subId).snapshot(
            cellInfosOverride = cellInfosOverride,
            serviceStateOverride = serviceStateOverride,
            signalStrengthOverride = signalStrengthOverride,
            displayInfoOverride = displayInfoOverride
        )

        val snapWithLinkRate = snap.copy(
            linkDownstreamKbps = linkRateOverride?.downstreamKbps,
            linkUpstreamKbps = linkRateOverride?.upstreamKbps,
            nsaLteAnchor = snap.nsaLteAnchor?.copy(
                linkDownstreamKbps = linkRateOverride?.downstreamKbps,
                linkUpstreamKbps = linkRateOverride?.upstreamKbps,
            )
        )

        return snapWithLinkRate.copy(
            subId = subId,
            updatedAt = System.currentTimeMillis(),
            hasPhonePermission = true,
            hasLocationPermission = hasLoc,
            isLocationEnabled = locEnabled,
        )
    }
}
