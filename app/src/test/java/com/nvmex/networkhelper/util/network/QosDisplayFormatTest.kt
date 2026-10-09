package com.nvmex.networkhelper.util.network

import org.junit.Assert.assertEquals
import org.junit.Test

class QosDisplayFormatTest {
    @Test fun detailsAlwaysUseMbpsWithoutRoundingOrUnitPromotion() {
        assertEquals("1000 Mbps", formatQosRateMbps(1_000_000))
        assertEquals("2000 Mbps", formatQosRateMbps(2_000_000))
        assertEquals("10.24 Mbps", formatQosRateMbps(10_240))
        assertEquals("0.064 Mbps", formatQosRateMbps(64))
        assertEquals("0 Mbps", formatQosRateMbps(0))
    }

    @Test fun formatsMbpsWithoutRoundingAwayCapturedImsRateAndUplinkComesFirst() {
        assertEquals("▲10.2 ▼10.2 Mbps", formatQosAmbrMbps(10_200, 10_200))
        assertEquals("▲100 ▼1000 Mbps", formatQosAmbrMbps(100_000, 1_000_000))
        assertEquals("▲150 ▼2000 Mbps", formatQosAmbrMbps(150_000, 2_000_000))
        assertEquals("▲0.064 ▼- Mbps", formatQosAmbrMbps(64, null))
        assertEquals("-", formatQosAmbrMbps(null, null))
    }
}
