import org.jetbrains.kotlin.gradle.dsl.JvmTarget

//plugins {
//    alias(vpnhs.plugins.aboutLibraries)
//    alias(vpnhs.plugins.android.application)
//    alias(vpnhs.plugins.crashlytics)
//    alias(vpnhs.plugins.kotlin.compose)
//    alias(vpnhs.plugins.ksp)
//    alias(vpnhs.plugins.google.services)
//    kotlin("android")
//    kotlin("kapt")
//    id("kotlin-parcelize")
//}

plugins {
//    id("com.android.application")
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-parcelize")
    id("kotlin-kapt")
    alias(libs.plugins.ksp)
}

kapt {
    correctErrorTypes = true
}
val javaVersion = 11
android {
    namespace = "be.mygod.vpnhotspot"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility(javaVersion)
        targetCompatibility(javaVersion)
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
    }
    compileSdk = 36
    compileSdkMinor = 1
//    defaultConfig {
//        applicationId = "be.mygod.vpnhotspot"
//        minSdk = 28
//        targetSdk = 36
//        versionCode = 1035
//        versionName = "2.19.1"
//        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
//        androidResources.localeFilters += listOf("es", "it", "ja", "pt-rBR", "ru", "zh-rCN", "zh-rTW")
//        externalNativeBuild.cmake.arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
//    }
    defaultConfig {
        minSdk = 28
        // targetSdk 对 library 可留可删；留着也行
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
//        androidResources.localeFilters += listOf("es", "it", "ja", "pt-rBR", "ru", "zh-rCN", "zh-rTW")
        externalNativeBuild.cmake.arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
    }

    buildFeatures {
        buildConfig = true
        dataBinding = true
        viewBinding = true
        compose = true
    }
    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        release {
            // library 不能使用资源压缩
            isShrinkResources = false

            // 整合阶段建议关闭混淆，避免 R8 影响定位/反射/隐藏 API 等逻辑
            isMinifyEnabled = false

            // library 不需要这些应用打包信息/规则（保留也没意义，且可能引发额外问题）
            // vcsInfo.include = true
            // proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }

    packagingOptions.resources.excludes.addAll(listOf(
        "**/*.kotlin_*",
        "META-INF/versions/**",
    ))
    lint.warning += "FullBackupContent"
    lint.warning += "UnsafeOptInUsageError"
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    externalNativeBuild.cmake.path = file("src/main/cpp/CMakeLists.txt")
}
ksp {
    arg("room.expandProjection", "true")
    arg("room.incremental", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}
kotlin.compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))

dependencies {
    coreLibraryDesugaring(vpnhs.desugar.jdk.libs)
    ksp(vpnhs.room.compiler)
    implementation(vpnhs.aboutlibraries.compose.m3)
    implementation(vpnhs.activity.compose)
    implementation(vpnhs.browser)
    implementation(vpnhs.core.i18n)
    implementation(vpnhs.core.ktx)
    implementation(vpnhs.dexmaker)
    implementation(vpnhs.dnsjava)
//    implementation(vpnhs.firebase.analytics)
//    implementation(vpnhs.firebase.crashlytics)
    implementation(vpnhs.foundation.layout)
    implementation(vpnhs.fragment.ktx)
    implementation(vpnhs.hiddenapibypass)
    implementation(vpnhs.ktor.network.jvm)
    implementation(vpnhs.kotlinx.collections.immutable)
    implementation(vpnhs.kotlinx.coroutines.android)
//    implementation(vpnhs.librootkotlinx)
    api(vpnhs.librootkotlinx)

    implementation(vpnhs.lifecycle.livedata.ktx)
    implementation(vpnhs.lifecycle.runtime.ktx)
    implementation(vpnhs.material)
    implementation(vpnhs.material3.android)
    implementation(vpnhs.play.services.oss.licenses)
    implementation(vpnhs.preference)
    implementation(vpnhs.preferencex.simplemenu)
    implementation(vpnhs.room.ktx)
    implementation(vpnhs.swiperefreshlayout)
    implementation(vpnhs.taskerpluginlibrary)
    implementation(vpnhs.timber)
    implementation(vpnhs.zxing.core)
    testImplementation(vpnhs.junit)
    androidTestImplementation(vpnhs.espresso.core)
    androidTestImplementation(vpnhs.junit.ktx)
    androidTestImplementation(vpnhs.room.testing)
    androidTestImplementation(vpnhs.test.runner)
}
