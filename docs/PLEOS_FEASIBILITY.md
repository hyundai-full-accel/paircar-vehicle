# Pleos Connect 화면 구현 가능성 검증

- 작성일: 2026-10-29
- 검증 환경: **Pleos Connect Emulator v2.1.0** (`system-images;android-34;pleos_car_emulator;x86_64`, 2560×1440, mdpi), user 빌드
- SDK: `ai.pleos.playground:NaviHelper:3.0.5` (에뮬레이터 내비 서비스 버전 `3.0.4`, logcat `getServiceVersion = 3.0.4`)
- PC: Windows 11, Intel Arc 내장 GPU (공식 최소 사양 미달이지만 약 90초 만에 부팅, 동작 확인)
- 차량 신호: Pleos **Virtual Console** (`adb forward tcp:8088 tcp:8088` → `http://127.0.0.1:8088/`)로 기어 P/D 전환

판정 기호

| 기호 | 의미 |
|---|---|
| ✅ 공식 | 공식 문서에 지원이 명시됨 |
| 🧪 검증 | 이 PC의 Pleos 에뮬레이터에서 직접 동작 확인 (실차 미확인) |
| ❓ 미확인 | 문서에 없고 직접 검증도 못 함 |
| ⛔ 제한 | 정책상 제한되거나, 플랫폼에서 막혀 있음을 확인 |

## 1. 요약 비교표

| 구현 방식 | 공식 지원 여부 | SDK/API | 구현 방법 | 테스트 결과 | 제한 사항 |
|---|---|---|---|---|---|
| **내비 위 팝업** | ⛔ 서드파티 오버레이 지원 문서 없음. Driving view는 "앱이 임의로 가리거나 종료할 수 없음" | 없음 (NaviHelper에 오버레이 API 없음). 대안: 표준 Notification | 구현하지 않음. 대안으로 `PickupNotifier`(IMPORTANCE_HIGH 알림) PoC | 🧪 오버레이 권한 설정 화면 **없음**(`MANAGE_OVERLAY_PERMISSION` → `No activity found`). 🧪 알림은 알림 센터에 게시되지만 **Heads-up 미표시** (Pleos HUN 서비스가 `privileged=false, platformSigned=false` 검사) | 사용자가 오버레이 권한을 허용할 경로가 없음. 보안 우회 금지·권한 최소화 심사 기준. 알림은 운전 관련·간결·광고 금지 |
| **앱 자체 화면** | ✅ 일반 AAOS Activity + CarUxRestrictions 대응이 공식 요구사항 | Jetpack Compose, `CarUxRestrictionsManager`(android.car), `distractionOptimized` 메타데이터 | 홈/매칭/상세/주행 4화면, 주행 중 허용 화면만 노출(`DrivingPolicy`) | 🧪 실행·표시 정상. 🧪 기어 D → `DO: true, UxR: 0x50` 수신 → **주행 화면으로 자동 전환**, 탑승 확인 버튼 비활성. 🧪 시스템이 `distractionOptimized: true` 인식 | DO 선언 방식은 Pleos 문서에 미기재(AAOS 표준 사용). 주행 규제 내용은 지역·차종별로 다름 |
| **분할 화면** | ✅ 앱 영역에 최대 2개 앱, 사용자가 window bar/제스처로 전환 | 앱이 분할을 요청하는 API 없음 (문서·SDK 모두) | `resizeableActivity` + `configChanges` 로 재생성 없이 크기 대응, 폭 1000dp 기준 1단/2단 Compose 레이아웃 | 🧪 window bar 드래그로 **Driving view + 내비 + 페어카 3분할** 표시, 페어카는 841px 폭 1단 레이아웃으로 자동 전환. 🧪 **주행 중(D)에도 분할 유지** | 분할 진입은 사용자 조작만 가능. 앱이 내비를 띄우면(requestNavigationScreen 등) 단일 window 상태에선 페어카를 덮음 |
| **내비 경로 연동** | ✅ NaviHelper SDK 3.0.5 (신청 없이 사용 가능) | `NaviHelper.initialize / addListener / requestRouteWithWaypoints / addWaypoint / cancelRoute / get*Info / requestNavigationScreen / release` | `vehicle/navi/PleosNaviRepository.kt` (UI와 분리, Mock 대체 가능) | 🧪 연결·경유지 2개 경로·자동 안내 시작·상태 콜백·내비 화면 요청·경로 개요·취소 **실동작 확인**. 남은 거리/시간 단위 = **m / 초** 확인 | 경유지 최대 2개. 경유지/목적지 도착 콜백은 미검증(주행 시뮬레이션 필요). `addWaypoint`는 요청 전달까지만 확인 |
| **탑승 상태에 따른 경로 재요청** | ✅ NaviHelper | `requestRoute` / `requestRouteWithWaypoints` 를 남은 픽업(도착 전)으로 다시 호출 | `domain/usecase/CarpoolUseCases.kt` `CarpoolRouteSync`. 미탑승·도착 전 탑승 완료면 경유지를 빼고, 미뤄둔 3번째 동승자는 앞 경유지 도착 후 넣는다. 남은 경유지가 같으면 다시 요청하지 않음 | 🧪 미탑승 → 내비가 기존 경로 취소(`onRouteCancelled`) 후 회사 직행 경로 제시(1시간 6분·63km·경유지 1개 → 57분·61km) | 재요청하면 내비에 경로 선택 화면이 다시 뜨고(몇 초 뒤 자동 안내), 페어카가 넓게 떠 있으면 내비가 앞으로 나와 덮음. 경유지 도착 이벤트는 에뮬레이터에서 미검증 |
| **정산 전비·주행거리** | ✅ Vehicle SDK 2.3.2 (EvBattery·Fuel·Odometer 도메인). 퍼미션 `CAR_ENERGY`·`CAR_DRIVE`는 **App Market 사전 협의·승인 범위에서만** 사용 가능 | `Vehicle(context).initialize()` → `getEvBattery().getCumulativeEvEfficiency / getSinceChargeEvEfficiency`, `getOdometer().getCurrentDistanceDriven` (Vehicle-2.3.2.aar javap 확인) | `vehicle/energy/PleosVehicleEnergyRepository.kt`. 차량 전비가 있으면 정산에 사용, 없으면 입력값 | 🧪 서비스 연결·퍼미션 부여(`prot=normal`, `granted=true`). 🧪 `PERF_ODOMETER` 조회 성공(값 0.0). 🧪 전비 2종은 에뮬레이터가 **미지원** (`property ID is not in carPropertyConfig list` 0x21600b15, 0x21600b14) | 실차 지원 여부 미확인. 전비 단위(km/kWh) 문서 미기재. 충전 단가는 차량에서 안 나옴 → 입력값 |
| 앱 안 지도 (와이어프레임) | ⛔ Pleos 내비 지도 임베드 API 없음 / ❓ 자체 지도 주행 중 허용 여부 | 독립 지도 SDK (MapLibre 등). Google Maps는 Play 서비스 없어 불가 | `core/ui/map/CarpoolMap.kt` (홈·주행 화면에 적용) | 🧪 MapLibre 지도·마커·선이 분할 화면 페어카 창에서 렌더링 | 내비와 같은 경로선은 SDK로 못 받음. 주행 중 지도는 심사 확인 필요 |

> 결론: **"내비 위 팝업"은 서드파티 앱으로는 불가**하다. 대신 **Driving view(내비 TBT) + 내비 + 페어카 분할 화면**과 **NaviHelper 경로 연동**이 공식적으로 지원되며, 에뮬레이터에서 모두 동작했다.

---

## 2. A. 내비게이션 위 팝업 표시

| 검증 항목 | 결과 | 근거 |
|---|---|---|
| NaviHelper SDK에 직접적인 오버레이 기능이 있는가? | ⛔ 없음 | NaviHelper-3.0.5.aar 공개 메서드 22개를 javap로 전수 확인: initialize, release, add/removeListener, requestRoute, requestRouteWithWaypoints, cancelRoute, addWaypoint, removeWaypoint, changeRouteOption, requestReRoute, get*Info 8종, requestNavigationScreen, requestTrip. 오버레이/커스텀 View 관련 API 없음. `NavigationScreenRequest.POIDetailPopup` 은 **내비 앱 자신의** POI 상세 팝업을 여는 요청일 뿐, 우리 View를 그리는 기능이 아님 |
| 서드파티 앱이 Navigation 화면 위에 임의의 View를 그릴 수 있는가? | ⛔ 불가로 판단 | 공식: "Driving view는 운전자와 가까운 고정 영역에 표시되며, **앱이 임의로 가리거나 종료할 수 없습니다**" ([app-structure-screen-model](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)). 시스템 UI(상태바, 공조, 키보드)만 앱 위 레이어에 있음 ([layout](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/layout)) |
| WindowManager / `TYPE_APPLICATION_OVERLAY` 사용이 허용되는가? | ⛔ 사실상 불가 | 🧪 `cmd package resolve-activity -a android.settings.action.MANAGE_OVERLAY_PERMISSION` → **No activity found**. 사용자가 "다른 앱 위에 표시" 권한을 줄 설정 화면 자체가 없음. Pleos 문서 어디에도 오버레이 허용 언급 없음 |
| `SYSTEM_ALERT_WINDOW` 권한이 실제로 사용 가능한가? | ⛔ 불가 (+심사 리스크) | 위와 같이 허용 경로 없음(appop 기본값). 심사 기준: "접근권한을 서비스에 필요한 범위 내로 최소화", "시스템의 보안 보호 기능을 우회…하는 기능은 금지" ([review-user-privacy-security](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-user-privacy-security)). 이 앱은 선언하지 않음 |
| 일반 Notification / Heads-up 을 대안으로 쓸 수 있는가? | 🧪 알림 O / Heads-up ✕ (에뮬레이터) | 🧪 IMPORTANCE_HIGH 채널 알림 게시 성공(`importance=4`, 알림 센터 수신). 그러나 Pleos `HeadUpNotificationListenerService` 로그: `isSystemImportantNotification(com.paircar: privileged=false, platformSigned=false, importance=4)` → **화면에 Heads-up 미표시**. 서드파티 HUN 정책은 공식 문서에 없음 → ❓ 실차/정식 서명 앱에서 재확인 필요 |
| 주행 중 팝업이 UI/UX·심사 정책에 위배되지 않는가? | ⛔ 주의 | "알림은 중요한 정보를 간결하게", "운전자의 필요와 관련된 경우에만 알림", "광고 금지" ([디자인 가이드](https://document.pleos.ai/docs/connect/guide/docs-design/Introduction/intro)). "운전과 관련되지 않은 알림은 사용할 수 없습니다", 반복 알림은 App Market 제외 가능 ([driver-distraction](https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide/review-driver-distraction-elements)). 텍스트는 1~2초 안에 파악 가능해야 함 |
| Pleos Emulator에서 검증할 방법이 있는가? | 🧪 있음 | 앱의 **내비 연동 PoC → 알림 테스트** 버튼 + `adb shell dumpsys notification`, logcat `NOTIFICATION_INJECTOR` 태그 |

**판단**: 서드파티 앱이 내비 위에 팝업을 띄우는 방식은 포기한다. 같은 목적(내비를 보면서 다음 픽업 정보 확인)은 **분할 화면의 페어카 portrait 화면**과 **Driving view의 내비 TBT**로 달성한다. 픽업 도착 같은 1회성 이벤트는 알림 센터 알림으로 보조하되, Heads-up 여부는 추가 확인이 필요하다.

## 3. B. 페어카 앱 자체 화면

| 항목 | 구현 | 검증 |
|---|---|---|
| 카풀 진행 상태 | 홈/상세: 상태 배지(출발 전·운행 중·완료). 주행 화면: "픽업 이동 중 / 픽업 지점 도착 / 목적지 이동 중" | 🧪 |
| 운전자·동승자 정보 | 상세: 운전자·차량·번호판, 동승자별 매칭/탑승 상태 | 🧪 |
| 동승자 픽업 위치 / 목적지 | 홈·상세·주행 화면. 주행 화면은 다음 목표 지점 1개만 크게 | 🧪 |
| 예상 픽업 시간 | 내비 안내 중이면 `현재 시각 + onWayPointInfo.remainTime`, 아니면 일정 시간 | 🧪 내비 표시(7:08 AM)와 앱 표시(07:08) 일치 |
| 탑승 상태 | 대기 → 픽업 지점 도착(내비 경유지 도착 이벤트) → 탑승/미탑승 → 하차 | 단위 테스트 ✅, 실차 도착 이벤트 ❓ |
| 내비게이션 실행 버튼 | "카풀 시작"(경유지 포함 경로 요청), "내비 보기"(`requestNavigationScreen(Foreground)`), "경로 개요" | 🧪 |

### 주행 중 허용 / 정차 중에만 허용 (`domain/model/DrivingState.kt` → `DrivingPolicy`)

| 조작 | 주행 중 | 근거 |
|---|---|---|
| 주행 화면 보기 (다음 픽업, ETA, 탑승 인원) | ✅ 허용 | 한눈에 읽는 정보 3가지, 애니메이션·자동 스크롤 없음 |
| 내비 보기 | ✅ 허용 | 한 번 탭 |
| 카풀 시작(미리 정한 경로로 안내 시작) | ✅ 허용 | "키보드 입력보다 프리셋 선택…을 우선" ([driver-distraction-guideline](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driver-distraction-guideline)) |
| 탑승 확인 / 미탑승 처리 | ⛔ 정차 중만 | 동승자 탑승은 정차 상태에서 일어남. 주행 중에는 "정차 후 탑승을 확인하세요" 표시 |
| 매칭 목록 탐색, 요청 수락/거절 | ⛔ 정차 중만 | 긴 목록·반복 터치 |
| 카풀 상세, 카풀 취소, 개발용 PoC 화면 | ⛔ 정차 중만 | 정보량 많음 / 되돌리기 어려운 조작 |

- 주행 규제 중 허용되지 않은 화면에 있으면 **자동으로 주행 화면**을 보여주고, 정차하면 원래 화면으로 돌아간다(백스택 유지). 🧪 기어 D/P 전환으로 확인.
- 에뮬레이터 UXR 설정(dumpsys): parked `0x0`, idling/moving `DO=true, 0x50`(NO_VIDEO | NO_SETUP). 키보드 제한(`NO_KEYBOARD`)은 이 이미지에선 꺼져 있다. 북미 등 지역별로 키보드·검색이 막힐 수 있으므로 앱은 텍스트 입력 자체를 쓰지 않는다 ([driving-restiction-policy](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy)).
- `distractionOptimized` 메타데이터를 선언하지 않으면 Pleos 정책상 주행 중 앱 사용 불가. 시스템 로그에서 `distractionOptimized: true` 인식 확인.

## 4. C. 내비게이션 + 페어카 분할 화면

| 검증 항목 | 결과 | 근거 |
|---|---|---|
| 분할 화면 공식 지원 | ✅ | "Driving view와 앱 영역을 함께 표시하고, **앱 영역에 최대 2개의 앱**을 배치할 수 있는 차량용 멀티 윈도우 환경" ([app-structure-screen-model](https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model)). 기본: Primary=Navigation ([window policy](https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/window)) |
| 서드파티 앱의 분할 화면 진입 | 🧪 가능 | window bar(앱 영역 오른쪽 `⋮`) 드래그 → 내비 `[853,90][1694,1300]` + 페어카 `[1718,90][2559,1300]` |
| Activity multi-window / resizeable 설정 | 🧪 | 앱은 `WINDOWING_MODE_MULTI_WINDOW` 로 실행됨(logcat `ConnectWindowUtil`). `resizeableActivity=true`, `configChanges`(screenSize 등)로 크기 변경 시 Activity 재생성 없음 |
| 앱이 분할 화면 시작을 직접 요청 | ❓ / ⛔ API 없음 | Pleos 문서·SDK에 창 배치 API 없음. "사용자는 window bar 또는 제스처를 통해 앱의 위치와 크기를 변경". `screenOrientation=landscape` 지정 시 2개 window 사용(=단독 표시)만 문서화됨 |
| 화면 크기 변경에 따른 Compose 대응 | 🧪 | `AdaptivePanes`: 폭 ≥1000dp면 2단, 미만이면 1단. landscape(1682px)=2단, portrait(841px)=1단 확인 |
| Navigation과 페어카 동시 표시 | 🧪 | 위 분할 상태에서 `requestNavigationScreen(OverviewRoute)` 호출 시 **페어카를 가리지 않고 내비 창 안에서** 경로 개요 표시 |
| 주행 중 분할 화면 제한 | 🧪 제한 없음(에뮬레이터) | 기어 D(UXR DO=true) 상태에서도 분할 유지, 페어카는 주행 화면으로 전환 |
| 분할에서 페어카 창 닫기 | 🧪 `finish()` 로 가능 | 닫으면 내비가 앱 영역 전체로 넓어짐. `moveTaskToBack(true)` 는 **무시됨**(창 그대로). 닫으면 Pleos 가 **앱 프로세스를 끝냄**(logcat `Killing … remove task`) → 진행 중 운행 상태를 기기에 저장해 다시 열 때 이어 붙임 |
| 주행 시작 시 3분할 자동 전환 | ⛔ 앱이 요청할 방법 없음 / 🧪 **이미 분할이면 유지** | `FLAG_ACTIVITY_LAUNCH_ADJACENT` 로 내비를 띄워도 무시(내비가 landscape 로 앞에 옴). 분할은 Pleos 홈 화면(`ai.umos.homescreen` `WindowBarDragHandler`)이 처리. Pleos Platform SDK `WindowStateManager` 는 상태 조회·구독만 공개하고 배치 변경은 SystemUI 전용([window-state](https://document.pleos.ai/api-reference/pleos-platform-sdk/domain-guides/window-state)). 🧪 페어카가 분할(portrait)인 상태에서 주행 시작 → 내비는 자기 창에서 경로 선택, 페어카는 주행 패널로 바뀌고 **3분할 유지**. 🧪 내비가 넓게 떠 있을 때 window bar 를 끌면 직전 앱(페어카)이 옆에 열림 → 넓은 주행 화면에 이 방법을 안내 |
| 닫은 뒤 다시 열기 | 🧪 앱 아이콘으로 가능, 단 **landscape(앱 영역 전체)** 로 열림 | 문서: "신규 앱은 이전 앱의 orientation과 동일하게 표시". 내비가 landscape 면 페어카도 landscape. 3분할로 되돌리는 건 사용자가 window bar 를 다시 드래그해야 함(앱이 요청하는 API 없음). window bar 탭으로 좌우 위치 교체 확인 |

관찰된 동작 (문서 정책과 일치)

- 앱 단독 실행 시: 내비가 landscape로 떠 있으면 페어카도 landscape(앱 영역 전체)로 열린다 → "새 앱은 현재 표시 중인 앱의 화면 방향을 기준으로 표시".
- 단독(landscape) 상태에서 `requestRouteWithWaypoints` / `requestNavigationScreen(Foreground)` 를 부르면 **내비가 앱 영역 앞으로 나와 페어카를 덮는다** (Deep link 정책: "App A에서 App B를 실행할 경우 App A를 덮고 실행").
- GNB 아이콘으로 다시 열면 페어카가 primary window로 이동하고 내비와 자리가 바뀐다.
- `screenOrientation="portrait"` 를 지정해도 window 1개로 열리지 않고, 앱 영역 전체(1682×1210)에 **좌우 검은 레터박스(호환 모드)** 로 표시됐다 → 심사 기준("호환성 모드로 실행되어서는 안 됩니다") 위반 소지가 있어 사용하지 않는다 (`screenshots/12_portrait_letterbox.png`).
- `launchMode` 기본값(standard)에서는 GNB 아이콘으로 다시 열 때마다 **MainActivity 인스턴스가 하나씩 쌓였다** → `singleTask` 로 수정, 이후 `wm_new_intent` 로 기존 인스턴스 재사용 확인.

## 5. D. NaviHelper SDK 연동 PoC

공식 문서: [NaviHelper intro](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro) · 시그니처는 `NaviHelper-3.0.5.aar`, `navi-data-3.0.4.aar` 를 javap 로 확인한 값만 사용.

| # | 기능 | API | 결과 | 비고 |
|---|---|---|---|---|
| 1 | SDK 초기화 | `NaviHelper(context)`, `initialize(onConnected, onFailed)` | 🧪 성공 | `onServiceConnected` → `getServiceVersion = 3.0.4` → `registerCallbackV3` |
| 2 | 리스너 등록 | `addListener(NaviHelperEventListener)` | 🧪 성공 | 리스너 메서드는 모두 default 구현 → 필요한 콜백만 override |
| 3 | 목적지 설정 | `requestRoute(RouteInfo)` | 단독 호출 미실행 ❓ | 같은 경로를 쓰는 `requestRouteWithWaypoints` 로 검증 |
| 3' | 경유지 포함 경로 | `requestRouteWithWaypoints(RouteWithWaypointsRequest)` | 🧪 성공 | **POI ID 없이 좌표만으로 동작**. 내비가 "경유지 2개 포함" 경로 3개 제시 → 3초 후 자동 안내 시작 |
| 4 | 경유지 추가 | `addWaypoint(RequestWaypointInfo)` | 🧪 요청 전달 / 반영 ❓ | 내비 로그 `OnAddWaypointRequest` 수신. 화면상 경로 변화는 확인 못 함 |
| 5 | 경로 안내 상태 수신 | `onRouteStarted`, `onRouteEnded`, `onNavigationStatus`, `onRouteStateInfo`, `onDestinationInfo`, `onWayPointInfo`, `onRouteCancelled` | 🧪 수신 | 아래 "실측 동작" 참고 |
| 5' | 도착 이벤트 | `onWayPointArrived`, `onDestinationArrived` | ❓ | 실제 주행 시뮬레이션(Virtual Console ACCEL 유지) 필요 |
| 6 | 내비 화면 요청 | `requestNavigationScreen(NavigationScreenRequest.Foreground / OverviewRoute)` | 🧪 성공 | Foreground: 내비가 앞으로. OverviewRoute: 5초간 경로 개요 |
| 7 | 성공/실패 처리 | `onFailed`, `onError(NaviErrorCode)`, `SecurityException` | 연결 성공 경로만 🧪 | 오류 코드 5종(문서는 4종, AAR엔 `CAUSE_SEARCH_TRIP` 추가)을 도메인 오류로 매핑 |
| 8 | 생명주기 정리 | `removeListener` → `release()` | 코드 반영 | `MainActivity.onDestroy`(구성 변경 제외)에서 해제, 재연결 시 새 객체 생성(공식 가이드) |
| - | 경로 취소 | `cancelRoute()` | 🧪 성공 | `onRouteCancelled` 수신 |
| - | 권한 | `pleos.car.permission.NAVI_ROUTE / NAVI_ROUTE_SEARCH / NAVI_CUSTOM_MAP` | 🧪 `granted=true` | 별도 신청 없이 부여됨 (create-my-project 문서: NaviHelper는 신청 없이 사용 가능) |

### 실측 동작 (문서와 다르거나 문서에 없는 것)

1. **에뮬레이터 내비 서비스(3.0.4)는 `onRoutingCompleted` 대신 deprecated `onRouteEnded` 를 보낸다.** SDK 3.0.5 릴리스 노트는 대체되었다고 하지만 둘 다 처리해야 한다.
2. `RouteStartInfo` 의 `name/distance/duration` 은 **첫 경유지까지의 구간 값**이다(`name=판교역 1번 출구, distance=3100, duration=600`). 전체 목적지 값은 `onDestinationInfo` 를 쓴다.
3. 거리·시간 단위: **미터 / 초** (`remainDistance=3100` ↔ 내비 표시 3.1km, `remainTime=600` ↔ 10분).
4. 안내 중에도 `RouteStateInfo.isRouting=false`, `naviMode=ROUTE_GUIDING` → 안내 여부는 `naviMode` / `onNavigationStatus(ROUTE_DRIVE)` 로 판단.
5. `WaypointInfo` 의 좌표는 `0.0` 으로 돌아온다(이름·남은 거리/시간만 유효).
6. 경로 안내 중 새 경로를 요청하면 `onRouteCancelled` 가 먼저 오고 새 안내가 시작된다.
7. Pleos 시스템 앱(Driving view, 홈 화면 등)도 같은 NaviHelper 서비스에 붙어 있다 → 앱이 설정한 경로가 **Driving view TBT에 그대로 표시**된다.

### NaviHelper API 전수 점검표 (22개 메서드, 2026-10-29 에뮬레이터)

PoC 화면(홈 → 내비 연동 PoC → "기타 NaviHelper API 점검")에서 모두 직접 호출했다.

| 메서드 | 앱 기능에서 사용 | 에뮬레이터 결과 | 받은 데이터 / 비고 |
|---|---|---|---|
| `NaviHelper(context)`, `initialize(onConnected, onFailed)` | ✅ | 🧪 성공 | 서비스 3.0.4 |
| `addListener` / `removeListener` | ✅ | 🧪 성공 | |
| `release()` | ✅ (onDestroy) | 코드 반영 | |
| `requestRoute(RouteInfo)` | ✅ (경유지 없을 때) | 🧪 요청 전달 | 경로 시작 콜백까지는 경유지 포함 경로로 확인 |
| `requestRouteWithWaypoints` | ✅ 카풀 시작 | 🧪 성공 | 좌표만으로 OK, 3초 뒤 자동 안내 |
| `addWaypoint` | ✅ PoC | 🧪 요청 전달 | 경로 반영은 화면상 미확인 |
| `removeWaypoint` | PoC | 🧪 성공 | `onRouteCancelled` → `onWaypointChanged` → `onRouteEnded` → `ROUTE_DRIVE` (재탐색) |
| `cancelRoute` | ✅ 카풀 취소 | 🧪 성공 | `onRouteCancelled` |
| `changeRouteOption` | PoC | 🧪 요청 전달 | `onRouteOptionChanged` 8초 내 미수신 |
| `requestReRoute` | PoC | 🧪 요청 전달 | `onReRouteEnded` 8초 내 미수신 |
| `getRouteStateInfo` | ✅ | 🧪 성공 | `naviMode`, 교통 요약 |
| `getDestinationInfo` | ✅ | 🧪 성공 | 목적지명, 남은 거리(m)·시간(초) |
| `getWaypointInfo` | ✅ | 🧪 성공 | 경유지별 남은 거리·시간 (좌표는 0.0) |
| `getTBTInfo` | PoC | 🧪 성공 | `TBTInfo(type=TURN_LEFT, distance=4.0, description=이름 없는 도로)` 목록 → **앱 자체 회전 안내 UI 가능** |
| `getCurrentLocationInfo` | PoC | 🧪 성공 | 위도·경도·heading·주소 → **앱 자체 지도에 현재 위치 표시 가능** |
| `getRecentDestinationInfo` | PoC | 🧪 성공 | 최근 목적지 목록 |
| `getBookmarkInfo` | PoC | 🧪 성공 | 집/회사 (에뮬레이터는 비어 있음) |
| `getChargerOperatorInfo` | PoC | 🧪 성공 | 충전 사업자 목록 |
| `requestTrip(TripRequest)` | PoC | 🧪 성공 | `onTripInfo(distance=10060, eta=1253, …교통 구간)` → **안내 시작 없이 예상 거리·시간 조회** |
| `requestNavigationScreen(Foreground / OverviewRoute)` | ✅ 내비 보기·경로 개요 | 🧪 성공 | |
| `requestNavigationScreen(SearchPlace / RouteOptionList / EditRoute / RecentDestinations / MoveToCurrentLocationOnMap)` | PoC | 🧪 요청 전달 | SearchPlace("판교역") → 내비 창에 검색 결과 표시(페어카는 그대로) |
| `getInternalApi()` | ✕ | - | 내부용, 사용하지 않음 |

자동으로 계속 들어오는 콜백: `onCurrentRoadSpeedLimit`(약 1초 간격), `onDrivingInfo`(목적지·경유지 남은 거리/시간), `onNavigationStatus`, `onCautionPointInfo`.
**미검증 콜백**: `onWayPointArrived`, `onDestinationArrived`, `onCameraAlert`. Virtual Console에서 기어 D + ACCEL로 속도(25km/h, 주행 상태 2)는 올라가지만, 내비 위치(`mapmatching org(127.094681,37.4127212)`)는 바뀌지 않는다. `adb emu geo fix` 도 내비 위치에 반영되지 않는다. → 실차/Devbox 필요.

### SDK 버전 호환성

- Maven: `https://nexus-playground.pleos.ai/repository/maven-releases/` (인증 불필요), NaviHelper 버전 `2.0.2, 2.0.3, 3.0.5` 공개.
- 3.0.5 의존성: `navi-data:3.0.4`, `navi-aidl:3.0.1`, kotlin-stdlib 1.9.25 → Kotlin 2.4 프로젝트에서 문제 없이 컴파일.
- AAR 매니페스트: `minSdk 23`, 서비스 최소 버전 `2.0.1`, 쿼리 액션 `ai.pleos.playground.intent.action.NAVI_START`.

### Mock 대체

- `BuildConfig.NAVI_MODE = auto | pleos | mock`. `auto` 는 `FEATURE_AUTOMOTIVE` 기기면 Pleos, 아니면 `MockNaviRepository`(경유지·목적지 도착을 시간 경과로 시뮬레이션).
- Pleos 연결 실패 시 Mock으로 몰래 전환하지 않고 "내비 연결 실패" 배지 + "내비에서 목적지를 직접 설정해 주세요" 안내.

## 6. E. 페어카 앱 안에 지도 표시 (와이어프레임 V-02 / V-06 / V-07 / V-08)

| 질문 | 결과 | 근거 |
|---|---|---|
| Pleos 내비 지도를 페어카 화면 안에 넣을 수 있나? | ⛔ 불가 | NaviHelper에 지도 View/Fragment/스냅샷 API 없음(22개 메서드 전수 확인). `NAVI_CUSTOM_MAP` 권한은 `requestNavigationScreen` 용 |
| Google Maps SDK | ⛔ 불가 | 에뮬레이터에 Google Play 서비스(gms) 없음 (`pm list packages` 확인) |
| 독립 지도 SDK로 직접 그리기 | 🧪 **가능** | MapLibre 13.6.1 + OpenFreeMap 타일(API 키 불필요)이 분할 화면의 페어카 창 안에서 정상 렌더링. 마커·선 색·두께·스타일 모두 앱이 정함 |
| 내비와 **같은 경로선** 그리기 | ⛔ SDK로는 불가 | NaviHelper는 경로 좌표열(polyline)을 주지 않음. 길 따라 그리려면 별도 경로 API(예: 지도 회사 길찾기 API)가 필요하고, Pleos 내비 경로와 달라질 수 있음 |
| 지도 위 실시간 정보 | 🧪 가능 | 현재 위치(`getCurrentLocationInfo`), 남은 거리·시간(`onWayPointInfo`, `onDestinationInfo`, `onDrivingInfo`), 회전 안내(`getTBTInfo`)를 받아 직접 표시 |
| 주행 중 앱 지도 표시가 심사에서 허용되나? | ❓ | 지도 화면 허용 예시는 Maps/Navigation 카테고리("간결한 지도")에만 있음. Mobility Services 앱의 주행 중 지도는 문서에 없음 → 사전 문의 필요. 애니메이션·자동 스크롤 금지 규칙은 공통 |

와이어프레임 요소별 데이터 출처

| 와이어프레임 요소 | 가능 여부 | 데이터 |
|---|---|---|
| V-06 왼쪽: 속도 78km/h, "450m 좌회전" | ✅ 이미 됨 | Pleos **Driving view** (시스템 화면, 앱이 그리는 게 아님) |
| "403m 남음", "경유지1 · 4분" | ✅ | `onWayPointInfo` remainDistance(m)/remainTime(초) |
| "도착 08:34 · 82.1km" | ✅ | `onDestinationInfo` / `onDrivingInfo` |
| 지도 + 출발·경유·도착 마커 | ✅ (자체 지도 SDK) | 카풀 데이터 좌표 |
| 지도 위 내 차 위치 | ✅ (자체 지도 SDK) | `getCurrentLocationInfo` (주기적 조회 필요) |
| 지도 위 파란 경로선 | △ | 별도 경로 API 필요 (내비 경로와 동일 보장 없음) |
| V-02/V-08 작은 경로 썸네일 | ✅ | 정차 중 화면이라 부담 적음 |

스크린샷: `screenshots/11_map_sdk_in_app.png`(초기 테스트), `screenshots/24_split_navi_v06.png`(주행 화면 적용)

## 7. 직접 하지 못한 검증

| 항목 | 이유 | 방법 제안 |
|---|---|---|
| 경유지/목적지 도착 콜백 | Virtual Console은 속도만 바꾸고 내비 위치는 고정 | 실차/Devbox, 또는 Pleos에 위치 시뮬레이션 방법 문의 |
| 앱 자체 지도의 주행 중 표시 허용 여부, 상용 지도 SDK(카카오·네이버 등) 동작 | 문서 없음 / 미설치 | Pleos 사전 문의, SDK별 AAOS 지원 확인 |
| 실차·Devbox 동작, 다른 디스플레이(Connect-W, 14.6/17인치) | 장비 없음 | Pleos 폼 팩터 테스트 가이드(가상 디스플레이) |
| 서드파티 Heads-up 정책 | 문서 없음, 에뮬레이터에선 미표시 | Pleos 개발자 지원 문의 |
| `distractionOptimized` 선언 심사 통과 여부 | 심사 필요 | App Market 사전 문의 |
| 주행 규제 ADB 명령(`cmd hmg_car_service … driving_regulation on`) | 에뮬레이터에 서비스 없음 | 실차/Devbox 전용으로 보임. 에뮬레이터는 Virtual Console 사용 |
| 글자 크기 물리 치수(핵심 5mm, 보조 3mm 이상) | 실제 디스플레이 ppi 미확인 | 실차 디스플레이에서 측정 |

## 8. 참고 문서

- 공모전 개발 가이드: https://appdevchallenge.auto-hmg.io/guide/development
- 개발 시작: https://document.pleos.ai/docs/pleos-only/getting-started
- Connect SDK: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/
- NaviHelper: https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/intro
- Emulator 설치: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
- Virtual Console: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/use-vehicle-emulator
- 앱 구조·화면 모델: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-structure-screen-model
- Window 정책: https://document.pleos.ai/docs/connect/guide/docs-design/getting-started/window
- 주행 규제 개발 가이드: https://document.pleos.ai/docs/pleos-only/vehicle-app-developer-guide/driving-restriction-development-guide
- 주행 규제 정책(지역별): https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/driving-restiction-policy
- 앱 카테고리: https://document.pleos.ai/docs/pleos-only/vehicle-app-planning-guide/app-category
- 앱 심사 가이드: https://document.pleos.ai/docs/pleos-only/distribute/app-review-guide

스크린샷: [docs/screenshots](screenshots/)
