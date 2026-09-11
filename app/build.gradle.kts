
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)

    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.kapt)
}





android {
    namespace = "com.nvmex.networkhelper"
    compileSdk = 36

    //明确指定 NDK 版本（与你本机安装保持一致）
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.nvmex.networkhelper"
        minSdk = 29
        targetSdk = 35
        versionCode = 43
        versionName = "1.4.3"

        // Inject the AMap key locally; never commit API credentials.
        manifestPlaceholders["AMAP_API_KEY"] = project.findProperty("AMAP_API_KEY") ?: ""

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 开启 NDK 构建：先只编 arm64-v8a，调通后再加别的 ABI
        ndk {
            abiFilters += setOf("arm64-v8a")
            // 需要兼容 32 位再打开：
//            abiFilters += setOf("armeabi-v7a")
        }

        //  告诉 Gradle：用 CMake 构建 native 代码
        externalNativeBuild {
            cmake {
                // 这些是常用基础参数：先别贪
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                cFlags += listOf("-std=c11")

                // 可选：只要你后面需要传宏，就用 arguments（先空着也行）
                // arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    //  指定 CMakeLists.txt 的路径
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true //r8
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose BOM（统一版本）
    implementation(platform(libs.androidx.compose.bom))

    // Compose 基础
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
//    implementation("androidx.compose.ui:ui-tooling-preview")

    // Material3（按钮/对话框/主题）
//    implementation("androidx.compose.material3:material3")

//    implementation("androidx.compose.material3:material3:1.0.0") // 适配 Material3
    // AndroidX Material3 (Material Design 3 的支持库)
    implementation(libs.androidx.material3)
    // Material Components Library (Google 的 Material 设计组件库)
//    implementation(libs.material)

    implementation(libs.androidx.navigation.compose)

    // debug 预览工具（可选）
//    debugImplementation("androidx.compose.ui:ui-tooling")

//    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation(libs.androidx.preference.ktx)

//    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.foundation)

    // LSPosed Modern API 101
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation("io.github.libxposed:service:101.0.0")

    // Hidden API bypass（按需）
//    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // =========================
    // Retrofit / OkHttp
    // =========================
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)

    // =========================
    //  Hilt（核心）
    // =========================
    implementation(libs.hilt.android)
    kapt(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)


    // Compose 用 Hilt 注入 ViewModel
    implementation(libs.hilt.navigation.compose)


    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.core)
    //是否释放对外依赖
    implementation(project(":vpnhotspot-mobile"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    //高德地图SDK
    implementation("com.amap.api:3dmap:10.0.600")
//    implementation("com.amap.api:location:11.1.000")

    val room_version = "2.8.4"
    implementation("androidx.room:room-runtime:$room_version")
    kapt("androidx.room:room-compiler:$room_version")
    implementation("androidx.room:room-ktx:${room_version}")

    //easyexcel
    implementation("com.alibaba:easyexcel:3.3.4")
//    implementation("com.opencsv:opencsv:5.8")

    implementation("com.github.doyaaaaaken:kotlin-csv-jvm:1.10.0")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
//    implementation("com.google.android.gms:play-services-location:21.3.0")
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}

kapt {
    correctErrorTypes = true
}
