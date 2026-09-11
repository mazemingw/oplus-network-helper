package com.nvmex.networkhelper.ui.network.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.nvmex.networkhelper.R
import com.nvmex.networkhelper.model.network.QualityLevel
import com.nvmex.networkhelper.util.network.sections.NetworkQualityAssessment

@Composable
fun localizedNetworkQualityText(assessment: NetworkQualityAssessment): String {
    val qualityLabel = when (assessment.level) {
        QualityLevel.EXCELLENT -> stringResource(R.string.network_quality_excellent)
        QualityLevel.GOOD -> stringResource(R.string.network_quality_good)
        QualityLevel.FAIR -> stringResource(R.string.network_quality_fair)
        QualityLevel.POOR -> stringResource(R.string.network_quality_poor)
        QualityLevel.VERY_POOR -> stringResource(R.string.network_quality_very_poor)
        QualityLevel.INTERFERENCE -> stringResource(R.string.network_quality_interference)
        QualityLevel.WEAK_COVERAGE -> stringResource(R.string.network_quality_weak_coverage)
        QualityLevel.FAR_AWAY -> stringResource(R.string.network_quality_far_away)
        QualityLevel.CLEAN_WEAK -> stringResource(R.string.network_quality_clean_weak)
        QualityLevel.UNKNOWN -> {
            if (assessment.text == "数据不足") {
                stringResource(R.string.network_quality_insufficient)
            } else {
                stringResource(R.string.state_unknown)
            }
        }
    }
    return if (assessment.level == QualityLevel.UNKNOWN) {
        qualityLabel
    } else {
        stringResource(R.string.network_quality_score, qualityLabel, assessment.score)
    }
}

// 网络质量徽章组件
@Composable
fun NetworkQualityBadge(
    qualityText: String,
    qualityLevel: QualityLevel,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (qualityLevel) {
        QualityLevel.EXCELLENT -> Color(0xFF4CAF50) // 绿色
        QualityLevel.GOOD -> Color(0xFF8BC34A)      // 浅绿色
        QualityLevel.CLEAN_WEAK -> Color(0xFF2196F3) // 蓝色
        QualityLevel.FAIR -> Color(0xFFFF9800)      // 橙色
        QualityLevel.POOR -> Color(0xFFFF5722)      // 深橙色
        QualityLevel.VERY_POOR -> Color(0xFFF44336) // 红色
        QualityLevel.INTERFERENCE -> Color(0xFF9C27B0) // 紫色
        QualityLevel.WEAK_COVERAGE -> Color(0xFF673AB7) // 深紫色
        QualityLevel.FAR_AWAY -> Color(0xFF3F51B5)  // 靛蓝色
        QualityLevel.UNKNOWN -> Color(0xFF757575)   // 灰色
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .height(28.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = qualityText,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
