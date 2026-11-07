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

        // QR 초대가 여는 모바일웹 가입 주소. 서버 구축 전이라 예약 도메인(.example) 자리표시자.
        buildConfigField("String", "INVITE_BASE_URL", "\"https://paircar.example/join\"")
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
        debug {
            // 페어카 크루 서버. 에뮬레이터에서 10.0.2.2 = 개발 PC 의 localhost (nginx :80)
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2\"")
            // 개발용: 앱의 예시 동승자를 서버 크루 멤버로 대신 가입시킨다 (원래는 초대 링크로 직접 가입)
            buildConfigField("boolean", "SEED_SERVER_PASSENGERS", "true")
        }
        release {
            isMinifyEnabled = false
            // 배포 서버 주소가 정해지기 전까지 서버 없이 Mock 만 쓴다
            buildConfigField("String", "API_BASE_URL", "\"\"")
            buildConfigField("boolean", "SEED_SERVER_PASSENGERS", "false")
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
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.tts)
    implementation(libs.maplibre.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
}

tasks.withType<Test>().configureEach {
    // 서버 연동 테스트: ./gradlew :app:testDebugUnitTest -PpaircarServer=http://localhost
    (project.findProperty("paircarServer") as String?)?.let { systemProperty("paircar.server", it) }
}
