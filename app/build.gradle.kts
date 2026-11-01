plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.paircar"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.paircar"
        // Pleos Connect 요구사항: API 28 이상 (사이닝 키 로테이션/리니지)
        minSdk = 28
        // Pleos Connect Emulator v2.1.0 시스템 이미지 = API 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 내비 연동 구현 선택: "auto" | "pleos" | "mock"
        // auto = 차량(AAOS) 기기면 Pleos NaviHelper, 아니면 Mock
        buildConfigField("String", "NAVI_MODE", "\"auto\"")
    }

    // CarUxRestrictions(주행 규제 상태) 조회용 AAOS Car API. 런타임에는 차량 시스템이 제공한다.
    useLibrary("android.car")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.pleos.navihelper)
    implementation(libs.maplibre.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
