package dev.qcom.efs.features

import org.junit.Assert.*
import org.junit.Test

class FeaturePresentationTest {
    @Test fun `default enabled configuration is checked and disabled is unchecked`() {
        assertTrue(featureSwitchChecked(FeatureStatus.CanDisable))
        assertFalse(featureSwitchChecked(FeatureStatus.AlreadyDisabled))
    }
    @Test fun `locked disconnected and unbacked interactions always have feedback`() {
        assertNotNull(featureEditBlockReason(false, true, false))
        assertNotNull(featureEditBlockReason(true, false, false))
        assertTrue(featureEditBlockReason(true, true, true)!!.contains("允许修改"))
        assertNull(featureEditBlockReason(true, true, false))
    }
    @Test fun `every feature has its own description and exact disabled value rows`() {
        ALL_FEATURES.forEach { feature ->
            assertTrue(featureRisk(feature.id).contains("可能"))
            val rows = featureValueReferences(feature, 0, emptyList())
            assertEquals(feature.writes.map { it.path }, rows.map { it.path })
            rows.zip(feature.writes).forEach { (row, write) ->
                assertEquals(write.bytes.joinToString(" ") { "%02X".format(it) }, row.disabled)
                assertTrue(row.enabled.contains("原值"))
            }
        }
    }
    @Test fun `SIM 2 reference uses subscription suffix and correct original bytes`() {
        val feature = ALL_FEATURES.first { it.id == "dss" }
        val path = slotPath(feature.writes.single().path, 1)
        val row = featureValueReferences(feature, 1, listOf(OriginalItem(path, "12aaff"))).single()
        assertTrue(row.path.endsWith("_Subscription01"))
        assertEquals("12 AA FF", row.enabled)
        assertEquals("00 00", row.disabled)
    }
    @Test fun `absent and empty original files have different recovery actions`() {
        val feature = ALL_FEATURES.first { it.id == "segmentation" }
        val path = feature.writes.single().path
        assertTrue(featureValueReferences(feature, 0, listOf(OriginalItem(path, null))).single().enabled.contains("删除"))
        assertTrue(featureValueReferences(feature, 0, listOf(OriginalItem(path, ""))).single().enabled.contains("空文件"))
    }
    @Test fun `other SIM original is not used as a recovery value`() {
        val feature = ALL_FEATURES.first { it.id == "dss" }
        val wrong = OriginalItem(feature.writes.single().path, "ffff")
        assertTrue(featureValueReferences(feature, 1, listOf(wrong)).single().enabled.contains("暂无"))
    }
    @Test fun `bitmap merge template warns against overwriting other capabilities`() {
        val feature = ALL_FEATURES.first { it.id == "nr_1024qam_tdd" }
        val row = featureValueReferences(feature, 0, emptyList()).single()
        assertTrue(row.note!!.contains("第 30 字节"))
        assertTrue(row.note.contains("不能整段覆盖"))
    }
}
