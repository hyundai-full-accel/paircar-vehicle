# 페어카 (PairCar)

> **운전자는 조작과 기름값 이야기에서, 동승자는 기다림과 미안함에서 벗어나는 Pleos Connect 차량 연동 출퇴근 카풀 앱**

현대자동차그룹 차량용 앱 개발 공모전 출품작 (팀 풀악셀). **Pleos Connect (Android Automotive OS)** 인포테인먼트에서 직접 실행되는 운전자용 차량 앱이며, 동승자는 앱 설치 없이 QR 링크로 여는 모바일 웹을 쓴다.

- **크루**: 차량 화면에서 크루 이름·출발 시각·반복 요일·출발지/도착지(지도)를 정하면 동승자 초대 QR 이 만들어진다.
- **픽업**: 오늘의 카풀에 경유지별 픽업 예정 시각과 경로가 자동으로 만들어지고, 주행 시작 한 번(5초 카운트다운 자동 출발)으로 승차 지점이 경유지로 담긴 경로가 Pleos 내비게이션에 전달된다.
- **탑승**: 동승자가 타면 그 경유지만 내비게이션에서 지우고(`removeWaypoint`) "○○님 탑승했어요" 음성(TTS)으로 알린다.
- **정산**: 실제로 함께 탄 구간만 탄 사람 수로 나누는 실비 정산(전비 × 충전 단가, 통행료, 우회 거리, 100원 올림). 매달 1일 지난달 영수증이 발행된다.
- 주행 중에는 **CarUxRestrictions** 에 따라 간소화된 화면만 보여주고, 내비게이션과 **나란히(분할 화면)** 쓸 수 있다.

## 공모전 제출 정보

| 항목 | 내용 |
|---|---|
| 앱 이름 | 페어카 (PairCar) · 패키지 `com.paircar` · 버전 0.1.0 |
| 한 줄 소개 | 운전자는 조작과 기름값 이야기에서, 동승자는 기다림과 미안함에서 벗어나는 Pleos Connect 차량 연동 출퇴근 카풀 앱 |
| APK | [`apk/풀악셀_페어카_release.apk`](apk/) (release, 서명됨, 약 23MB) |
| 에뮬레이터 | **Pleos Connect v2.1.0 APIs** (Android 14 / API 34, `system-images;android-34;pleos_car_emulator;x86_64`, 기기 프로필 `Pleos_Connect_v2_0`) |
| 화면 규격 | **2560 × 1440 px, 160 dpi (mdpi)**, 가로. 앱 영역은 왼쪽 차량 패널을 뺀 오른쪽(약 1707 × 1210 dp)이며, 내비와 나란히 띄운 좁은 창(약 830 dp)도 지원 |
| 지원 ABI | `x86_64`(에뮬레이터), `arm64-v8a`(차량) |
| 시연 영상 | 크루 만들기 → QR 초대 → 동승자 2명 모바일 웹 참여 → 주행·자동 탑승 → 운행 기록·정산 (별도 제출) |

### 실행 방법 (release APK)

1. 아래 [Pleos Connect Emulator 설정](#pleos-connect-emulator-설정)대로 **Pleos Connect v2.1.0** 에뮬레이터를 콜드 부팅한다.
2. APK 를 설치한다.
   ```bash
   adb install -r "apk/풀악셀_페어카_release.apk"
   ```
3. 에뮬레이터 하단 독(dock)의 **페어카(P) 아이콘**을 누르거나 명령줄로 실행한다. Pleos Connect 는 사용자 10 에서 앱이 실행된다.
   ```bash
   adb shell am start --user 10 -n com.paircar/.MainActivity
   ```
4. (선택) 동승자 알림·음성 안내 알림을 보려면 알림 권한을 허용한다.
   ```bash
   adb shell pm grant --user 10 com.paircar android.permission.POST_NOTIFICATIONS
   ```

> 디버그 빌드를 설치한 적이 있으면 서명 키가 달라 덮어쓰기가 안 된다. 먼저 `adb uninstall com.paircar` 로 지운다.

### 테스트 환경 및 필요한 설정

- **release APK 는 서버 없이 동작한다.** 앱에 들어 있는 예시 크루(판교 출근 크루 · 동승자 2명)와 지난 운행·정산 예시 데이터로 모든 화면을 확인할 수 있다. 네트워크는 지도 타일(OpenFreeMap)·경로 미리보기(OSRM)에만 쓴다.
- 실행하면 시작 화면에서 차량 연결을 확인한 뒤 오늘의 카풀로 이동하고, **5초 카운트다운 뒤 자동으로 주행을 시작**한다(화면을 누르면 취소). 주행을 시작하면 Pleos 내비에 경유지가 담긴 경로가 전달된다.
- 에뮬레이터에서는 차가 움직이지 않으므로 경유지 도착·탑승은 주행 화면의 **탑승 완료 / 미탑승** 버튼으로 진행한다.
- 주행 중 화면 제한은 Pleos Virtual Console 에서 기어를 **D** 로 바꿔 확인한다 ([주행 상태 테스트](#주행-상태-테스트)).
- 내비와 나란히 보기: 앱 영역 오른쪽 끝 `⋮` 막대를 왼쪽으로 끈다 ([분할 화면 테스트](#분할-화면-테스트)).
- 서버 연동(크루 만들기·QR 초대·동승자 웹·실시간 알림·주행 시뮬레이션)은 **debug 빌드 + 로컬 크루 서버**에서 동작한다. 아래 [서버 연동 (debug)](#서버-연동-debug) 참고.

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
| 차량 SDK | Pleos `ai.pleos.playground:NaviHelper:3.0.5`, Pleos `ai.pleos.playground:Vehicle:2.3.2` (정산 전비·주행거리), Pleos `TextToSpeech:3.3.1` (온디바이스 한국어 음성 안내), AAOS `android.car` (CarUxRestrictions) |
| 서버 연동 | 크루 서버 REST + 실시간 이벤트(SSE), 끊겨도 순서대로 다시 보내는 전송 대기열 (`data/server`) |
| 지도 | MapLibre `org.maplibre.gl:android-sdk:13.6.1` + OpenFreeMap Positron 타일 (API 키·Google Play 서비스 불필요) |
| 경로 미리보기 | OSRM 공개 데모 서버 (프로토타입 전용, 실패 시 직선) — 서비스 전 상용 길찾기 API로 교체 |
| 폰트 | Pretendard 1.3.9 (`res/font`, SIL OFL 1.1 — [docs/licenses/Pretendard-OFL.txt](docs/licenses/Pretendard-OFL.txt)) |
| QR | ZXing core `com.google.zxing:core:3.5.3` (크루 초대 QR 생성) |
| 테스트 | JUnit4, kotlinx-coroutines-test |
| CI | GitHub Actions: `main` 푸시·PR 시 빌드+단위 테스트, 이슈 생성 시 브랜치 자동 생성 |

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
│   └── ui/                    # 테마(Pretendard, 큰 글자), 시안 공용 컴포넌트, 지도, QR
├── data/
│   ├── local/                 # 운행 기록 JSON 저장, 진행 중 운행 상태 저장(창을 닫아도 이어지게)
│   ├── mock/                  # 서버 없는 release 용 예시 데이터 (크루·지난 운행·정산)
│   ├── remote/                # 경로 미리보기(OSRM) + 캐시
│   └── server/                # 크루 서버 API·SSE 실시간 알림·운행 기록 전송 대기열·월 영수증
├── domain/
│   ├── model/                 # Carpool, Navigation, DrivingState + DrivingPolicy
│   ├── repository/            # CarpoolRepository, NaviRepository, DrivingStateRepository
│   └── usecase/               # 경로 계획(경유지 ≤2), 안내 시작·운행 종료, 내비 이벤트 반영, 운행 기록(TripRecorder)
├── feature/
│   ├── startup/               # 시작 화면: 차량 연결·변경사항·경유지·지원 기능 확인 후 오늘의 카풀로
│   ├── home/                  # 탭 1. 오늘의 카풀 (V-02)
│   ├── crewsetup/             # 크루 만들기 (이름·시각·요일·지도에서 출발지/도착지) → 초대 QR
│   ├── crew/                  # 탭 2. 내 크루 + 경로 상세 + QR 초대 + 탑승·픽업 관리 + 정산 기준 관리
│   ├── history/               # 탭 3. 운행 기록: 월 요약, 주간·하루 타임라인, 운행 상세
│   ├── carpool/               # 홈·주행·운행 종료가 함께 쓰는 CarpoolViewModel
│   ├── settlement/            # 탭 4. 월별 정산: 멤버별 정산, 분담 내역, 영수증, 정산 진행, 계좌·비용 기준(은행 선택)
│   ├── matching/              # 새 동승 요청 수락/거절 (정차 중 전용)
│   ├── driving/               # 주행 중 화면 (V-06), 3분할 좁은 창용 주행 패널, 운행 종료 화면
│   ├── settings/              # 설정 + 연결된 차량(내비 연결 재확인/해제)
│   └── navipoc/               # NaviHelper API 검증용 개발 화면 (설정 > 지원 기능 확인)
└── vehicle/
    ├── navi/                  # PleosNaviRepository(SDK), MockNaviRepository, SimulatedDriveNavi(시연용 주행 시뮬레이션)
    ├── energy/                # PleosVehicleEnergyRepository (Vehicle SDK 전비·주행거리)
    ├── tts/                   # Pleos TextToSpeech 음성 안내 (없으면 안드로이드 기본 TTS)
    └── state/                 # CarDrivingStateRepository (CarUxRestrictionsManager)
```

UI·도메인 코드는 Pleos SDK 타입을 직접 참조하지 않는다. SDK 의존은 `vehicle/` 아래 구현에만 있다.

## 협업 흐름 (GitHub)

1. **이슈 만들기**: Issues → New issue → `작업` 또는 `버그` 양식. "영어 제목 (브랜치 이름)" 칸에 영문으로 짧게 적는다.
2. **브랜치 자동 생성**: 이슈가 열리면 `.github/workflows/issue-branch.yml` 이 `main` 에서 `이슈번호-영어-제목` 브랜치(예: `12-add-qr-invite`)를 만들고 이슈에 댓글을 단다. 저장소 쓰기 권한이 있는 사람의 이슈만 처리한다(공개 저장소 스팸 방지).
3. **작업**: `git fetch origin` → `git switch 12-add-qr-invite` → 커밋 → 푸시 → `main` 으로 PR (본문에 `Closes #12`).
4. **자동 검사**: `main` 으로의 PR과 `main` 푸시마다 `.github/workflows/ci.yml` 이 `assembleDebug testDebugUnitTest` 와 브랜치 스크립트 테스트를 돌린다.

브랜치 이름 규칙은 `.github/scripts/issue-branch.js` 에 있고 `node --test .github/scripts/issue-branch.test.js` 로 확인한다.

## 크루 초대 (QR)

내 크루 → **QR로 초대하기** 또는 크루 이름 옆 **+** → QR 표시.

- **debug + 서버**: 크루 서버가 초대 코드를 발급하고, QR 에는 서버가 정한 동승자 웹 주소(`PAIRCAR_INVITE_BASE_URL/코드`)가 들어간다. 동승자는 웹에서 이름·탈 곳을 등록하고 정산 기준에 동의해 참여한다.
- **release (서버 없음)**: 앱이 예시 초대 코드를 만들고, 주소는 예약 도메인 `https://paircar.example/join` 자리표시자다.

## 서버 연동 (debug)

debug 빌드는 개발 PC 의 크루 서버(`http://10.0.2.2` = PC 의 localhost)에 붙는다. 함께 쓰는 저장소:

- 크루 서버: <https://github.com/hyundai-full-accel/paircar-server> (`docker compose -f infra/compose.yaml up -d --build --wait`)
- 동승자 모바일 웹: <https://github.com/hyundai-full-accel/paircar-mobile>

| BuildConfig | debug | release |
|---|---|---|
| `API_BASE_URL` | `http://10.0.2.2` | `""` (서버 없이 예시 데이터) |
| `SIMULATE_DRIVE` | `true` (주행 시작 시 경로를 따라 차가 움직이는 것처럼 시뮬레이션) | `false` |
| `SEED_SERVER_PASSENGERS` | `false` | `false` |

서버 연동 테스트: `./gradlew :app:testDebugUnitTest -PpaircarServer=http://localhost` (로컬 DB 에 테스트 크루가 생긴다)

## release 빌드

```bash
./gradlew :app:assembleRelease
```

- 결과: `app/build/outputs/apk/release/app-release.apk`
- 코드·리소스 축소(R8, `app/proguard-rules.pro` — Pleos SDK 는 이름을 그대로 둔다), ABI `arm64-v8a`·`x86_64` 만 포함
- 서명 정보는 git 에 올리지 않는 `local.properties` 에서 읽는다. 없으면 서명 없이 빌드된다.
  ```properties
  paircar.release.storeFile=키스토어 경로
  paircar.release.storePassword=…
  paircar.release.keyAlias=paircar
  paircar.release.keyPassword=…
  ```

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

APK: `app/build/outputs/apk/debug/app-debug.apk` (제출용 release 는 [release 빌드](#release-빌드) 참고)

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
  adb shell am start --user 10 -n com.paircar/.MainActivity
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
- 앱 내 **설정 → 지원 기능 확인(내비 연동 PoC) → 주행/정차 시뮬레이션** 버튼으로도 강제할 수 있다 (차량 API 없는 폰 에뮬레이터용).
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

## 추후 확장

| 기능 | 비고 |
|---|---|
| 실차 탑승 자동 감지 | Vehicle SDK Seat·Door 신호 상태 머신 (지금은 주행 화면의 탑승 완료/미탑승 버튼, 시연은 시뮬레이션) |
| 주행 중 음성 명령 | Pleos SpeechToText · Gleo App Schema |
| 교대 운전 상계, "이번 달은 내가 낼게" 0원 영수증 | 정산 서버와 함께 |
| 실제 결제 처리 | 차량 내 결제 정책 확인 필요 (지금은 금액·계좌 안내 후 은행 앱 송금) |
| 경유지 삭제 시 내비 경로 확인 화면 | 남은 경유지가 있으면 Pleos 내비가 경로를 다시 계산하며 확인 화면(자동 진행)을 띄운다. SDK 에 끄는 옵션이 없어 Pleos 문의 예정 |
