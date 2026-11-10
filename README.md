# 진주 버스 운전자 앱

운행 방향, 노선번호, 차량을 선택하고 정류장별 승차 대기와 하차 요청 인원을 확인하는 Android 앱.

## 기능

- 로그인 없이 상행·하행, 노선번호, 차량 선택
- 현재·다음 정류장 및 노선 표시
- 정류장별 승차 대기·하차 요청·예정 하차 인원 조회
- 화면이 켜져 있는 동안 5초마다 갱신
- 운행 시작, 다음 정류장 이동, 운행 종료 수동 처리

## 실행

Android Studio에서 저장소 폴더를 열고 Gradle Sync 후 `app`을 실행한다.
Android 8.0 이상을 지원하며 compileSdk 36, targetSdk 35, Gradle 9.6.0, JDK 25를 사용한다.

앱의 **서버 설정**에 백엔드 주소를 입력한다. 에뮬레이터에서 개발 PC의 서버에 연결할 때는 `http://10.0.2.2:3000`을 사용한다. 실기기의 로컬 HTTP 주소는 debug 네트워크 설정에 추가해야 하며 릴리스는 HTTPS를 사용한다.

진주시 API 키와 DB 접속 정보는 백엔드 `.env`에 설정한다. 앱에 키를 넣지 않는다.

## 파일

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | 노선·차량 선택, 화면 갱신, 운행 조작 |
| `Api.kt` | 서버 요청 및 응답 처리 |
| `Models.kt` | 정류장·예약 집계 데이터 |
| `RouteTimeline.kt` | 정류장 연결선과 현재 위치 표시 |
| `app/src/main/res` | 화면 배치, 색상, 아이콘 |
| `backend` | 기존 팀 서버에 적용할 예약·운전자 API |

백엔드 적용 방법과 요청 형식은 [API 문서](docs/API.md)를 참고한다. `backend`는 단독 실행 서버가 아니라 기존 서버에 적용하는 연동 코드다.

## 테스트

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
cd backend
npm test
```

## 남은 작업

- 팀 서버에 연동 코드와 예약 테이블 적용
- 실제 API 키, DB, 상하행 코드 설정 후 양쪽 앱 연동 테스트
- 추천 경로 예약의 노선·정류장 ID 저장 방식 통일
- GPS에 따른 현재 정류장 자동 이동

현재 집계 대상은 한국 시간 기준 당일 생성 예약이다. 인원은 승객 앱의 예약·탑승·하차 요청 상태를 기준으로 하며 센서 측정값은 아니다. 실제 팀 DB와 공공 API 연결 검증은 아직 진행하지 않았다.

## 연동 대상

- 승객 앱: [hveve6651/bususer](https://github.com/hveve6651/bususer)
- 백엔드: [kim7899/bus-route-finder](https://github.com/kim7899/bus-route-finder)
