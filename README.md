# 진주 버스 운전자 앱

앱을 실행하면 바로 운전자 화면이 열립니다. 승객 앱과 패키지가 달라 함께 설치할 수 있습니다.
디자인 프로토타입 18·19페이지의 현재/다음 정류장, 승하차 인원, 정차 안내를 구현했습니다.

## 확인 방법

Android Studio에서 이 프로젝트 폴더를 Open하고 Gradle Sync 후 Run을 누르세요.
`MainActivity.kt`의 `DriverScreenPreview`는 세로/가로 Preview를 제공합니다.
첫 화면은 첨부 디자인처럼 대시보드가 바로 보입니다. 예약 인원 카드를 누르면 정차 안내가 열립니다.
상단 150번 배지를 누르면 예시 데이터 안내와 정류장 이동 메뉴를 열 수 있습니다.

## 구조와 이유

- Kotlin + Jetpack Compose + Material 3: 새 프로젝트의 기존 기술을 유지했습니다. XML UI 라이브러리를 추가하지 않았습니다.
- `MainActivity.kt`: 화면 구성과 사용자 동작. 넓이 600dp 이상에서는 두 카드를 나란히 배치하고 작은 화면에서는 세로로 배치합니다.
- `DriverTrip.kt`: 정류장/예약 데이터와 이동·확인 로직을 화면에서 분리합니다. 서버 데이터 연결 시 이 경계에서 교체할 수 있습니다.
- `rememberSaveable`: 회전과 Activity 재생성 때 현재 정류장·확인 상태를 유지합니다.
- 승차 파랑/하차 빨강과 큰 글자를 사용하며 상태는 글자로도 표시합니다.
- 정차 안내 확인은 예약을 삭제하거나 승하차 완료로 처리하지 않습니다.

현재는 예시 데이터입니다. 로그인/운전자 권한, GPS, 예약 서버, 연결 끊김 처리는 아직 없습니다.
150번 배지의 ‘예시 정류장 이동’은 예시 위치만 이동합니다. 종점에서는 이동이 비활성화됩니다.

## 검증 명령

Windows: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`

클라우드:
`python3 /workspace/.onboarding/run-java.py bash gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --no-daemon`

Java 21, Android SDK 37이 필요합니다. 로컬 SDK 위치는 Android Studio에서 생성하며
클라우드의 경로를 Windows의 local.properties에 복사하지 마세요.
