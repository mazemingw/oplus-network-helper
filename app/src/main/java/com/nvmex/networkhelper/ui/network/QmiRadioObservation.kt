package com.nvmex.networkhelper.ui.network

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.nvmex.networkhelper.util.network.SocVendorDetector
import com.nvmex.networkhelper.viewmodel.signal.NetworkPanelMultiSimViewModel

@Composable
fun QmiRadioObservation(vm: NetworkPanelMultiSimViewModel, sim: Int?, enabled: Boolean = true, subscriptionId: Int? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val supported = remember { SocVendorDetector.isQualcomm() }
    val retry by vm.qmiRetry.collectAsState()
    LaunchedEffect(vm, sim, enabled, lifecycle, retry, subscriptionId) {
        if (enabled && supported && sim in 1..2) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.monitorQmi(sim!!)
        }
    }
}
