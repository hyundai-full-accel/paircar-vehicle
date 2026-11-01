# 페어카 (PairCar)

출퇴근 경로 기반 **카풀 매칭 차량용 앱**. 현대자동차그룹 차량용 앱 개발 공모전 출품작으로,
**Pleos Connect (Android Automotive OS)** 인포테인먼트에서 직접 실행된다. Android Auto 프로젝션 앱이 아니다.

- 운전자는 오늘의 카풀 일정·동승자·픽업 지점을 확인하고, **한 번의 탭으로 차량 내비게이션에 픽업 지점(경유지)과 목적지를 설정**한다.
- 주행 중에는 **CarUxRestrictions** 에 따라 간소화된 주행 화면만 보여준다.
- 내비게이션과 **나란히(분할 화면)** 표시되도록 portrait/landscape 적응형 레이아웃을 쓴다.

> 화면 시안 PDF (에뮬레이터 캡처): [docs/PairCar_Split_Screen_Mockup.pdf](docs/PairCar_Split_Screen_Mockup.pdf)
> 화면 구현 가능성 검증 결과: [docs/PLEOS_FEASIBILITY.md](docs/PLEOS_FEASIBILITY.md)
> 팀 공유용 요약: [docs/TEAM_REPORT.md](docs/TEAM_REPORT.md)

## 기술 스택

| 항목 | 내용 |
|---|---|
| 언어 | Kotlin 2.4.0 (AGP built-in Kotlin) |
| UI | Jetpack Compose (BOM 2026.08.00), Material 3 |
| 아키텍처 | MVVM + Repository, 단일 모듈, 수동 DI(`AppContainer`) |
| 비동기 | Kotlin Coroutines + StateFlow / SharedFlow |
| 빌드 | Gradle 9.4.1 (Kotlin DSL, version catalog), AGP 9.2.0 |
| SDK | minSdk 28 / targetSdk 34 / compileSdk 37 |
| 차량 SDK | Pleos `ai.pleos.playground:NaviHelper:3.0.5`, AAOS `android.car` (CarUxRestrictions) |
| 지도 | MapLibre `org.maplibre.gl:android-sdk:13.6.1` + OpenFreeMap Positron 타일 (API 키·Google Play 서비스 불필요) |
| 경로 미리보기 | OSRM 공개 데모 서버 (프로토타입 전용, 실패 시 직선) — 서비스 전 상용 길찾기 API로 교체 |
| 테스트 | JUnit4, kotlinx-coroutines-test |

## 프로젝트 구조

```
app/src/main/java/com/paircar/
├── MainActivity.kt            # NaviHelper/UXR 연결·해제 (onCreate / onDestroy)
├── PairCarApplication.kt    # AppContainer 생성, 내비 이벤트 → 카풀 상태 반영
├── PairCarRoot.kt           # 화면 전환 + 주행 중 화면 제한
├── core/
│   ├── common/AppContainer.kt # 수동 DI (Mock ↔ 실제 구현 선택 지점)
│   ├── model/                 # GeoPoint
│   ├── navigation/Screen.kt   # 화면 ↔ 주행 중 허용 조작 매핑
│   ├── notification/          # 픽업 알림 (내비 위 팝업의 대안 PoC)
│   └── ui/                    # 테마(고대비, 큰 글자), 공용 컴포넌트, 적응형 레이아웃
├── data/
│   ├── mock/                  # MockCarpoolData, MockCarpoolRepository
│   └── remote/                # REST API 연동 지점(인터페이스만)
├── domain/
│   ├── model/                 # Carpool, Navigation, DrivingState + DrivingPolicy
│   ├── repository/            # CarpoolRepository, NaviRepository, DrivingStateRepository
│   └── usecase/               # 경로 계획(경유지 ≤2), 안내 시작, 내비 이벤트 반영
├── feature/
│   ├── home/                  # 화면 1. 홈
│   ├── matching/              # 화면 2. 카풀 매칭 (정차 중 전용)
│   ├── carpool/               # 화면 3. 카풀 상세 + 공유 CarpoolViewModel
│   ├── driving/               # 화면 4. 주행 중 간소화 화면
│   └── navipoc/               # NaviHelper API 검증용 개발 화면
└── vehicle/
    ├── navi/                  # PleosNaviRepository(SDK), MockNaviRepository, Factory
    └── state/                 # CarDrivingStateRepository (CarUxRestrictionsManager)
```

UI·도메인 코드는 Pleos SDK 타입을 직접 참조하지 않는다. SDK 의존은 `vehicle/navi/PleosNaviRepository.kt` 한 파일에만 있다.

## 개발 환경 구축

### 1. 필수 도구

- Android Studio (최신 버전, 이 프로젝트는 2026.1.3에서 구성)
- JDK 17 이상 (Android Studio 내장 JBR 사용 가능)
- Android SDK Platform 37 (compileSdk), Platform-Tools, Emulator
- Pleos Connect Emulator 시스템 이미지 (아래 참고)

### 2. 프로젝트 열기

1. Android Studio → **Open** → 이 폴더 선택
2. `local.properties` 의 `sdk.dir` 이 본인 SDK 경로인지 확인 (Studio가 자동 생성)
3. Gradle Sync. Pleos SDK는 공개 Maven 저장소에서 받는다(인증 불필요):
   ```kotlin
   // settings.gradle.kts
   maven {
       url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
       content { includeGroup("ai.pleos.playground") }
   }
   ```

### 3. 명령줄 빌드 / 테스트

```bash
./gradlew assembleDebug testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Pleos Connect Emulator 설정

공식 문서: <https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator>

1. Android Studio → **SDK Manager → SDK Update Sites → +**
   - Name: `Pleos Connect System Image`
   - URL (Windows/Linux): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - URL (macOS): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
2. **SDK Platforms → Show Package Details → Android API 34 → Pleos Connect v2.1.0 APIs** 설치 (약 4.1GB, 42dot SDK License 동의 필요) → Studio 재시작
3. **Device Manager → Create device → Automotive → Pleos Connect v2.1.0 → API 34 Pleos Connect v2.1.0 APIs**
4. Advanced Settings: **Default boot = Cold** (quick boot 이면 VHAL 사용 불가), **RAM = 4GB**
5. 에뮬레이터는 별도 창으로 실행 (폼 팩터 테스트 시 Running Devices 내장/headless 금지)

> 공식 최소 사양: 외장 GPU (NVIDIA RTX 3060 Laptop 6GB / AMD RX 6600M 8GB 이상). 내장 그래픽에서는 부팅되지 않거나 느릴 수 있다.

명령줄로 만들 때:

```bash
avdmanager create avd -n Pleos_Connect_v2_1 -k "system-images;android-34;pleos_car_emulator;x86_64" -d Pleos_Connect_v2_0
```

그 다음 `~/.android/avd/Pleos_Connect_v2_1.avd/config.ini` 에서 `fastboot.forceColdBoot=yes`, `fastboot.forceFastBoot=no`, `hw.ramSize=4096`, `hw.gpu.enabled=yes`, `hw.gpu.mode=host` 로 바꾼다.

## 앱 실행

- Android Studio: 실행 대상으로 **Pleos Connect v2.1.0** AVD 선택 → Run `app`
- 명령줄:
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
  ```bash
  adb shell am start -n com.paircar/.MainActivity
  ```

### 내비 연동 구현 선택 (`app/build.gradle.kts`)

```kotlin
buildConfigField("String", "NAVI_MODE", "\"auto\"") // auto | pleos | mock
```

- `auto` (기본): 차량(AAOS) 기기면 NaviHelper SDK, 일반 폰/태블릿 에뮬레이터면 Mock 시뮬레이터
- Pleos 연결 실패 시 Mock으로 몰래 바꾸지 않고 상단에 "내비 연결 실패"를 표시하며, 경로 요청 시 "내비에서 직접 목적지를 설정"하도록 안내한다.

### 주행 상태 테스트

- 실제 CarUxRestrictions: **Pleos Virtual Console** 에서 기어를 바꾼다 (에뮬레이터에서 확인).
  ```bash
  adb forward tcp:8088 tcp:8088
  ```
  브라우저에서 `http://127.0.0.1:8088/` → Emulation Controls → **Gear Selection D** (주행 규제 ON) / **P** (해제)
- 확인: `adb shell dumpsys car_service --services CarUxRestrictionsManagerService` 의 `UXR: DO: true`
- 앱 내 **내비 연동 PoC → 주행/정차 시뮬레이션** 버튼으로도 강제할 수 있다 (차량 API 없는 폰 에뮬레이터용).
- 참고: Pleos ADB 가이드의 `cmd hmg_car_service … driving_regulation on` 은 에뮬레이터에 서비스가 없어 동작하지 않았다.

### 분할 화면 테스트

앱 영역 오른쪽 끝의 window bar(`⋮`)를 왼쪽으로 끌면 [Driving view | 내비 | 페어카] 로 나뉜다. 앱이 직접 분할을 요청하는 API는 없다.

## 검증 결과 요약

에뮬레이터에서 확인한 내용과 미확인 항목은 [docs/PLEOS_FEASIBILITY.md](docs/PLEOS_FEASIBILITY.md), 스크린샷은 [docs/screenshots](docs/screenshots/) 참고.

## NaviHelper SDK 연동 방법

공식 문서: <https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro>

```kotlin
// app/build.gradle.kts
implementation("ai.pleos.playground:NaviHelper:3.0.5")
```

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="pleos.car.permission.NAVI_ROUTE" />        <!-- get*Info -->
<uses-permission android:name="pleos.car.permission.NAVI_ROUTE_SEARCH" /> <!-- requestRoute, addWaypoint ... -->
<uses-permission android:name="pleos.car.permission.NAVI_CUSTOM_MAP" />   <!-- requestNavigationScreen -->
```

| 기능 | NaviHelper API (v3.0.5, AAR에서 확인) | 구현 위치 |
|---|---|---|
| 초기화 | `NaviHelper(context)` → `initialize(onConnected, onFailed)` | `PleosNaviRepository.connect()` |
| 리스너 | `addListener(NaviHelperEventListener)` (onConnected 이후) | 〃 |
| 목적지 | `requestRoute(RouteInfo)` | `requestRoute()` |
| 경유지 포함 경로 | `requestRouteWithWaypoints(RouteWithWaypointsRequest)` | `requestRoute()` |
| 경유지 추가 | `addWaypoint(RequestWaypointInfo)` — `WaypointIndex.FIRST/SECOND` (최대 2개) | `addWaypoint()` |
| 상태 수신 | `onRouteStarted`, `onRoutingCompleted`, `onDestinationInfo`, `onWayPointInfo`, `onRouteStateInfo`, `onNavigationStatus`, `onWayPointArrived`, `onDestinationArrived`, `onRouteCancelled`, `onError` | listener |
| 상태 조회 | `getRouteStateInfo()`, `getDestinationInfo()`, `getWaypointInfo()` (결과는 콜백) | `refreshRouteState()` |
| 내비 화면 | `requestNavigationScreen(NavigationScreenRequest.Foreground / OverviewRoute)` | `showNavigationScreen()` |
| 해제 | `removeListener()` → `release()` (재사용 시 새 객체) | `disconnect()` ← `MainActivity.onDestroy()` |

## 이번 MVP에서 제외한 기능 (추후 확장)

| 기능 | 비고 |
|---|---|
| 주행 중 음성 명령 | Pleos SpeechToText SDK (`준비 중`, 신청 필요) |
| TTS 안내 | Pleos TextToSpeech SDK. 픽업 도착 음성 안내 후보 |
| 1회 운행별 정산 / 일별 정산 | 서버 API 확정 후 |
| 실제 결제 처리 | 차량 내 결제 정책 확인 필요 |
| iOS 앱 (동승자용) | 별도 프로젝트 |
| 서버 REST 연동 | `data/remote/CarpoolRemoteDataSource` 구현 후 `AppContainer` 에서 교체 |
