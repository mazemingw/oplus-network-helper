import java.security.MessageDigest

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val verifyEfsHelper by tasks.registering {
    val sources = fileTree("daemon/src") { include("*.c", "*.h") }
    val binary = file("src/main/assets/nh-efsd")
    val binaryHash = file("src/main/assets/nh-efsd.sha256")
    val sourceHash = file("src/main/assets/nh-efsd.source.sha256")
    inputs.files(sources, binary, binaryHash, sourceHash)
    doLast {
        fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val normalized = sources.files.sortedBy { it.name }.joinToString("") { it.name + '\u0000' + it.readText().replace("\r\n", "\n") }
        check(hash(normalized.toByteArray()) == sourceHash.readText().trim() && hash(binary.readBytes()) == binaryHash.readText().trim()) {
            "EFS helper is stale. Run tools/build_efs.ps1 -NdkPath <Android NDK path>."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyEfsHelper) }

android {
    namespace = "dev.qcom.efs"
    compileSdk = 36
    defaultConfig { minSdk = 29; consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.material3)
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
