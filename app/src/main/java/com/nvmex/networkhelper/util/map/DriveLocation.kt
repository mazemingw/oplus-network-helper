package com.nvmex.networkhelper.util.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle

//路测采样器 1S/次
class DriveLocation(private val context: Context) {

    private val lm = context.getSystemService(LocationManager::class.java)
    @Volatile private var last: Location? = null

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) { last = location }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        // GPS + Network 都订阅，谁快用谁
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
        }
        runCatching {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener)
        }
        // 先塞一个 lastKnown，减少“第一秒空”
        last = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
            ?: runCatching { lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
    }

    fun stop() {
        runCatching { lm.removeUpdates(listener) }
    }

    fun latest(): Location? = last
}