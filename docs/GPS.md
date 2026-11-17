# GPS 전송·노선 진행·도착 알림

## 기기 GPS → 서버

앱이 화면에 표시되는 동안 위치 권한을 요청하고 GPS_PROVIDER의 위치를 수신합니다.
선택한 노선·차량이 있으면 5초 주기로 다음 요청을 전송합니다.

```http
POST /api/driver/location
Content-Type: application/json
```

```json
{"routeId":"jinju:150:1","vehicleId":"차량번호","latitude":35.0,"longitude":128.0,"accuracy":10.0}
```

15초 이상 지난 위치 또는 정확도 반경이 100m를 초과하는 위치는 전송하지 않습니다. 전송 중에는 새 전송을 추가하지 않으며 예약 조회와 별도 작업 스레드를 사용합니다. 화면이 비활성화되면 위치 수신과 주기 작업을 중단합니다.

이 저장소의 backend 예제는 `/api/driver/location` 수신·저장을 구현하지 않습니다. 실제 서버에 해당 POST API를 구현해야 합니다. 아래 노선 진행용 position 응답은 진주시 API 기반이며, 기기 GPS를 사용하려면 서버가 저장한 위치를 같은 응답 계약으로 제공해야 합니다.

## 서버 → 노선 진행

선택한 노선/방향/차량의 route-snapshot을 5초마다 조회합니다. 중복 요청은 실행하지 않으므로 느린 응답 중에는 해당 주기를 건너뜁니다.

- 정류장 좌표: getRouteBsta의 lttd/lgtd → stops.latitude/longitude.
- 차량 좌표: getBusLoc의 lttd/lgtd. 통과 정류장: pasgBstaSrvcId. 갱신 시각: altrDtm.
- 차량 캐시는 5초이며 선택한 vehicleId의 위치만 반환합니다.
- position: `{status:"LIVE", latitude, longitude, observedAt, passedSequence}`. observedAt은 epoch milliseconds, passedSequence는 선택 방향 내 0 기반 순서입니다.
- 원본 시각 없는 값, 30초 초과 값, 미래 5초 초과 값은 STALE. 차량/좌표 누락은 UNAVAILABLE, 방향 불일치는 WRONG_DIRECTION, 통과 정류장 중복/매칭 실패는 UNMATCHED. 해당 상태에서는 위치를 추측하지 않습니다.
- GPS 조회 실패에도 예약 집계는 유지합니다. GPS 유효 시 수동 진행 메뉴 대신 예약 상세를 표시합니다.

## 승하차 인원 표시 기준

메인 화면과 도착 팝업은 같은 정류장의 최신 승하차 인원을 표시합니다. 주행 중에는 다음 정류장 기준이며, 도착하면 팝업만 숨기고 해당 정류장 인원을 유지합니다. 정류장 좌표에서 25m를 벗어나면 출발로 판정해 다음 정류장 인원으로 전환합니다. GPS 정차 흔들림으로 바로 전환되지 않도록 도착 반경(5m)과 출발 반경을 구분합니다.

인원 카드 제목에 대상 정류장 이름을 함께 표시합니다. GPS가 끊기면 마지막 인원 표시 대상을 유지하며, 종점에서는 종점 인원을 계속 표시합니다. 실제 출발 이벤트가 없는 GPS 거리 기반 판정이므로 현장 GPS 오차에 맞춰 반경 조정이 필요할 수 있습니다.

## 도착 알림

다음 정류장까지 Haversine 직선거리 20m 이내에서 중앙 카드에 정류장명, 거리, 승차·하차 인원을 표시합니다. 승차는 파랑, 하차는 빨강으로 구분합니다. 첫 표시 때만 1.6초 주기로 3번 흐려졌다 밝아집니다. 5초 데이터 갱신마다 효과를 다시 재생하지 않습니다.

도착은 5m 이내 또는 서버 통과 정류장이 다음 순서로 바뀌었을 때 판정합니다. 팝업을 해제하고 현재 정류장을 이동합니다. 작은 GPS 흔들림은 알림을 유지하고 40m 밖으로 멀어지면 해제합니다.

앱 비활성화/연결 실패/오래된 위치에서는 팝업을 숨깁니다. 같은 운행의 진행은 뒤로 돌리지 않습니다. 새 운행/회차는 노선·차량 재선택이 필요합니다.

5초 샘플 사이에 20m 구간을 통과하면 팝업이 생략될 수 있습니다. 실차 GPS 정확도와 공공 API 갱신 간격에 따라 정확한 20m 시점을 보장하지 않습니다.

## 빌드·검증

JDK 17 이상과 Android SDK를 준비하고 Android Studio의 Gradle JDK를 설정합니다. OS별 절대 Java 경로는 공용 gradle.properties에 넣지 않습니다.

- Android: `./gradlew testDebugUnitTest assembleDebug` — 단위 테스트 17개 및 APK 빌드.
- 백엔드: `cd backend && npm test` — 23개 회귀 테스트.
- 에뮬레이터: 가상 GPS 위치의 POST 수신, 중앙 카드 표시, 도착 시 카드 해제와 노선 진행을 확인합니다.
- 운영 서버 API/실차 검증은 별도로 필요합니다. 서버의 JINJU_BUS_API_KEY / JINJU_UP_DIRECTION / JINJU_DOWN_DIRECTION을 설정하고 앱에서 서버 URL·노선·방향·차량을 선택합니다.
