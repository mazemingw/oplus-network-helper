package com.nvmex.networkhelper.util

import org.junit.Assert.*
import org.junit.Test

class CellExtraInfoTest {
    @Test fun escapedDelimitersAndBackslashesRemainInTheirOriginalFields() {
        assertEquals(listOf("地址" to "A,B楼", "备注" to "参数=x\\y", "说明" to "第一行\n第二行"),
            parseCellExtraInfo("地址=A\\,B楼,备注=参数\\=x\\\\y,说明=第一行\\n第二行"))
    }
    @Test fun oldPlainTextAndIncompleteFieldsAreStillVisible() {
        assertEquals(listOf("" to "旧数据", "字段" to "", "尾部" to "值\\"), parseCellExtraInfo("旧数据,字段=,尾部=值\\"))
        assertEquals(emptyList<Pair<String,String>>(), parseCellExtraInfo(""))
    }
}
