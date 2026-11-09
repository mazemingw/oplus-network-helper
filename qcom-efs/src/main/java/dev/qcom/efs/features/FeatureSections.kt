package dev.qcom.efs.features

enum class FeatureSection(val title: String) { COMMON("常用功能"), NSA("NSA"), UNSUPPORTED("不支持") }

/** A modem refusal takes precedence over the feature family; connection failures remain retryable. */
fun featureSection(feature: FeatureDef, status: FeatureStatus?): FeatureSection {
    val error = when (status) {
        is FeatureStatus.ReadError -> status.message
        is FeatureStatus.WriteError -> status.message
        else -> ""
    }.lowercase()
    if (listOf("bad command", "not supported", "unsupported", "target rejected", "拒绝", "不支持").any { it in error } ||
        Regex("efs errno=(1|13|38|95)([^0-9]|$)").containsMatchIn(error)) return FeatureSection.UNSUPPORTED
    return if (feature.id.startsWith("nsa_")) FeatureSection.NSA else FeatureSection.COMMON
}
