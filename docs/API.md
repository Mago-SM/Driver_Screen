# 예약·운전자 API

기준: hveve6651/bususer의 ReservationSync.kt, ReservationStore.kt, BusAPI.kt 및 kim7899/bus-route-finder의 server.js.
현재 앱/서버 경로에는 수동 예약 ID 매핑이나 예시 노선이 없습니다. 기존 route-selection-service.js/driver-service.js/mock-server.js는 이전 버전 회귀 테스트용이며 새 server.js에서는 호출하지 않습니다.

## 승객 → 서버

기존 bus_session 인증을 사용하는 `/api/mobile/reservations`:

- GET: `{ "reservations": [승객 앱이 저장한 예약 객체] }`, 로그인한 계정만 반환.
- POST: `{ "id": "예약 UUID", "reservation": {예약 객체} }`, 동일 계정+ID로 재전송하면 덮어쓰기.
- POST: `{ "id": "예약 UUID", "reservation": null }`, 해당 계정 예약 삭제. 반복 삭제도 성공.
- user_id는 요청 본문에서 받지 않고 기존 requireUser 결과에서 얻음.
- 승객 앱의 pending 재시도 형식과 일치. PENDING/RESERVED/BOARDED/COMPLETED 지원.
- 예약 상태와 routeSnapshot, rideRouteId, rideVehicleId, rideAlightingStopId/Order, completedRideLegs 등을 보존.
- 잘못된 상태/형식은 400, 미인증 401, DB 오류 500. DB 실패를 저장 성공으로 반환하지 않음.

`migrations/001-mobile-reservations.sql`을 팀 MySQL에 한 번 적용해야 합니다. 기존 users/reservations 테이블은 수정하지 않습니다. 새 mobile_reservations 테이블은 계정+예약 ID를 기본키로 사용합니다. 기존 웹의 reservations와 모바일 예약은 다른 저장 형식이며 자동 병합하지 않습니다.

## 운전자 → 서버 (로그인 없음)

공통 쿼리: `routeNumber=150&direction=상행` (URL 인코딩).

1. GET `/api/driver/routes`: 진주시 API에서 해당 번호의 지원 노선 목록 조회. 응답 `routes: [{routeId,label,routeNumber}]`.
2. GET `/api/driver/vehicles`: 공통 쿼리 + routeId. 응답 `vehicles: [{vehicleId,label,directionName,updatedAt}]`. 운전자가 차량을 선택. 목록에는 해당 노선의 양방향 차량이 포함될 수 있어 방면 표시를 확인해야 함.
3. GET `/api/driver/route-snapshot`: 공통 쿼리 + routeId + vehicleId. 정류장별 집계만 반환. 개인정보/예약 ID는 반환하지 않음.

스냅샷 필드:
- routeId: 승객 앱과 동일한 `jinju:routeNo:suptNo`
- routeNumber, routeLabel, directionKey, vehicleId, serviceDate
- stops: `{id,name,sequence,sourceOrder,apiOrder,direction}`. sequence는 선택 방향 내 0부터, sourceOrder는 승객 앱처럼 전체 방향 정렬 후 1부터.
- counts.boarding: 해당 노선·방향의 RESERVED 대기 인원. 아직 특정 차량 배정이 아니므로 같은 노선의 차량들이 같은 대기 수를 볼 수 있음.
- counts.alighting: 해당 차량의 BOARDED 예약 중 명시적 하차 요청 인원.
- counts.plannedAlighting: 해당 차량 탑승 상태의 예정 하차 인원. 명시적 변경 요청이 있으면 요청 정류장을 우선함.
- onboard: 해당 차량에서 경로/방향 매칭에 성공한 BOARDED 수. 센서 실측치가 아님.
- excludedCount: 매칭에 실패하거나 모호해 제외한 예약 수. 다른 차량/방향/노선 및 PENDING/COMPLETED는 정상 필터링 대상으로 제외 건수에 넣지 않음.
- complete:true: DB 대상 전체 조회 완료. 제외된 예약이 없다는 뜻은 아님.

앱은 승차 대기와 하차 요청을 큰 숫자로 표시하고 상세에서 예정 하차를 별도 표시합니다. 5초 조회, 오류 시 마지막 정상 값 유지. 현재 정류장은 수동으로 이동합니다.

## 매칭과 운행 범위

- 명시적 진주 routeId/승하차 정류장 ID가 있는 예약은 ID와 정류장 방문 순서로 매칭.
- 추천 경로 예약은 routeSnapshot의 BUS 구간 중 completedRideLegs에 해당하는 구간만 처리.
- bususer 추천 경로는 routeId/정류장 ID를 보존하지 않으므로 지원 노선이 하나이고 정류장 이름·진행 순서로 유일하게 매칭될 때만 처리. 여러 지원 노선/동명 반복 방문/방향 모호함은 제외 표시. 승객 앱도 canonical ID와 방향을 저장하도록 보완하면 이 제한을 없앨 수 있음.
- 반대 방향의 순서를 함께 확인하여 반대편 예약이 섞이지 않도록 함.
- BOARDED는 rideRouteId와 rideVehicleId를 모두 확인. 하차 요청의 원본 order도 확인.
- 현재 운행 범위는 예약 createdAt의 한국 날짜가 오늘인 예약. 전날 생성한 예약/심야 연속 운행/미래 배차를 지원하려면 별도의 serviceDate/tripId 계약이 필요. 임의로 모든 과거 예약을 누적하지 않음.
- 계정+예약 ID upsert로 동일 요청 재전송의 중복을 방지. 다중 기기 동시 수정은 마지막 저장이 우선이며 버전 충돌 제어는 미구현.

## 실제 진주시 API

`jinju-provider.js`가 `https://apis.data.go.kr/5310000/jinjubus/`의 getRouteList/getRouteBsta/getBusLoc를 사용합니다. 서버의 JINJU_BUS_API_KEY가 필요합니다.

노선/정류장 캐시 24시간, 차량 캐시 5초. API 실패·키 누락 시 예시 데이터로 대체하지 않습니다.
상·하행 공식 코드의 숫자 의미는 추측하지 않습니다. 실제 getRouteBsta의 drct와 기점·종점을 대조하여 서버 환경 변수 JINJU_UP_DIRECTION, JINJU_DOWN_DIRECTION을 각각 설정해야 합니다. 하행을 상행의 역순으로 만들지 않습니다.

## 적용

1. 실제 bus-route-finder 서버 저장소에 `python3 apply-integration.py <경로>` 실행. 확인한 원본과 이전 생성본만 지원하며 백업 생성. 사용자 수정본은 덮어쓰지 않음.
2. 팀 DB에 migrations/001-mobile-reservations.sql 실행 (스크립트가 DB를 자동 변경하지 않음).
3. 기존 .env의 DB 설정 유지. .env.integration.example의 진주시 API 키·상하행 코드 추가.
4. 기존 서버 재시작. 승객 앱과 운전자 앱에 동일한 서버 URL 설정.
5. 실제 승객 예약 생성 → 서버 동기화 성공 → 운전자 조회 → 차량 선택·탑승 → 하차 요청 → 완료/취소 순서로 검증.

팀 서버 적용과 실제 MySQL·공공 API 연결 테스트는 남아 있습니다. Node 테스트는 메모리 DB와 API 응답 fixture를 사용합니다.
