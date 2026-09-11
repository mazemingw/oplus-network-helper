package com.nvmex.networkhelper.util.network

import android.content.Context
import android.telephony.SubscriptionManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

 fun observeDefaultDataSubId(ctx: Context): Flow<Int> = flow {
    var last = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    while (currentCoroutineContext().isActive) {
        val cur = SubscriptionManager.getDefaultDataSubscriptionId()
        if (cur != last) {
            emit(cur)
            last = cur
        }
        delay(1000) // 1s 足够了
    }
}.distinctUntilChanged()
