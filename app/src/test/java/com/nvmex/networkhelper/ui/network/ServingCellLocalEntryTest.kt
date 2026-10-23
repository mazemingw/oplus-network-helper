package com.nvmex.networkhelper.ui.network

import com.nvmex.networkhelper.model.menu.LteCellQueryResp
import com.nvmex.networkhelper.ui.network.sections.*
import com.nvmex.networkhelper.viewmodel.menu.CellQueryUiState
import org.junit.Assert.*
import org.junit.Test

class ServingCellLocalEntryTest {
    @Test fun ordinaryUsersKeepTheUnknownLabelAndCanOpenLocalFollowRecording() {
        val unknown = CellQueryUiState.LteSuccess(LteCellQueryResp(match_level = "none", count = 0, data = emptyList()))
        assertTrue(unknown.isServingCellUnknown())
        assertEquals("未知", unknown.toServingCellDisplayText(devEnabled = false))
        assertTrue(unknown.canClickServingCell(devEnabled = false))
        assertEquals("随行随录", unknown.toServingCellDisplayText(devEnabled = true))
    }
    @Test fun aFailedQueryDoesNotPretendToBeAnUnknownMatchedCell() {
        assertFalse(CellQueryUiState.Error("offline").isServingCellUnknown())
        assertFalse(CellQueryUiState.Error("offline").canClickServingCell(devEnabled = false))
    }
}
