import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// release 서명 정보는 git 에 올라가지 않는 local.properties 에서 읽는다 (paircar.release.*). 없으면 서명 없이 빌드된다
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseStoreFile = localProps.getProperty("paircar.release.storeFile")

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

    // 네이티브 라이브러리(지도 엔진 등)를 압축해 넣어 APK 파일을 작게 한다 (설치 때 기기에 풀린다)
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProps.getProperty("paircar.release.storePassword")
                keyAlias = localProps.getProperty("paircar.release.keyAlias")
                keyPassword = localProps.getProperty("paircar.release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 페어카 크루 서버. 에뮬레이터에서 10.0.2.2 = 개발 PC 의 localhost (nginx :80)
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2\"")
            // 예시 동승자를 서버 크루에 대신 가입시키지 않는다. 동승자는 초대 링크(동승자 웹)로 들어온다
            buildConfigField("boolean", "SEED_SERVER_PASSENGERS", "false")
            // 시연용: 주행을 시작하면 차가 경로를 따라 움직이는 것처럼 만든다 (에뮬레이터 내비는 차가 움직이지 않는다)
            buildConfigField("boolean", "SIMULATE_DRIVE", "true")
        }
        release {
            // 코드·리소스 축소 (Pleos SDK 는 proguard-rules.pro 에서 그대로 둔다)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 차량(arm64-v8a)과 Pleos Connect 에뮬레이터(x86_64)용 네이티브 라이브러리만 넣는다
            ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // 배포 서버 주소가 정해지기 전까지 서버 없이 Mock 만 쓴다
            buildConfigField("String", "API_BASE_URL", "\"\"")
            buildConfigField("boolean", "SEED_SERVER_PASSENGERS", "false")
            buildConfigField("boolean", "SIMULATE_DRIVE", "false")
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
