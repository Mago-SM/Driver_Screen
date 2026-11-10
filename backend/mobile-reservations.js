// Matches bususer ReservationSync: authenticated GET / POST {id,reservation|null}.
export const send = (res, code, value) => { res.writeHead(code, {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'}); res.end(JSON.stringify(value)); };
export const fail = (res, code, message) => send(res, code, {error:{message}});
export function validateReservation(id, value) {
  if (typeof id !== 'string' || !/^[\w-]{1,128}$/.test(id)) throw Error('예약 ID 오류');
  if (value === null) return;
  if (!value || Array.isArray(value) || value.id !== id) throw Error('예약 데이터 오류');
  if (!['PENDING','RESERVED','BOARDED','COMPLETED'].includes(value.boardingStatus)) throw Error('예약 상태 오류');
  for (const key of ['routeId','routeNumber','boardingStopId','boardingStopName','alightingStopId','alightingStopName']) {
    if (typeof value[key] !== 'string' || value[key].length > 255) throw Error(`${key} 오류`);
  }
  for (const key of ['rideRouteId','rideVehicleId','rideVehicleNumber','rideAlightingStopId','rideAlightingStopName']) {
    if (value[key] !== undefined && (typeof value[key] !== 'string' || value[key].length > 255)) throw Error(`${key} 오류`);
  }
  if (!Number.isSafeInteger(value.createdAt) || value.createdAt <= 0) throw Error('예약 시각 오류');
  if (value.completedRideLegs !== undefined && (!Number.isInteger(value.completedRideLegs) || value.completedRideLegs < 0)) throw Error('환승 구간 오류');
  if (value.rideAlightingStopOrder !== undefined && !Number.isInteger(value.rideAlightingStopOrder)) throw Error('하차 순서 오류');
  if (value.routeSnapshot) {
    if (typeof value.routeSnapshot !== 'string' || value.routeSnapshot.length > 100000) throw Error('경로 크기 오류');
    const route = JSON.parse(value.routeSnapshot);
    if (!Array.isArray(route?.route?.segments)) throw Error('경로 형식 오류');
  }
}
export const serviceDate = (time = Date.now()) => new Intl.DateTimeFormat('en-CA', {timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(time));
export function createMobileHandler({db,requireUser}) {
  return async (req,res) => {
    const user = await requireUser(req,res); if (!user) return;
    try {
      if (req.method === 'GET') {
        const [rows] = await db.execute('SELECT payload FROM mobile_reservations WHERE user_id = ? ORDER BY updated_at DESC', [String(user.id)]);
        return send(res,200,{reservations:rows.map(r=>typeof r.payload==='string'?JSON.parse(r.payload):r.payload)});
      }
      if (req.method !== 'POST') return fail(res,405,'GET 또는 POST만 지원합니다.');
      req.setEncoding?.('utf8');
      let raw=''; for await(const chunk of req) { raw+=chunk.toString(); if(Buffer.byteLength(raw)>256000)return fail(res,413,'예약 데이터가 너무 큽니다.'); }
      let body;
      try { body=JSON.parse(raw);validateReservation(body.id,body.reservation); }
      catch {return fail(res,400,'예약 형식과 상태를 확인하세요.');}
      if(body.reservation===null) {
        await db.execute('DELETE FROM mobile_reservations WHERE user_id = ? AND reservation_id = ?', [String(user.id),body.id]);
      } else {
        const r=body.reservation;
        await db.execute(`INSERT INTO mobile_reservations (user_id,reservation_id,service_date,boarding_status,payload)
          VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE service_date=VALUES(service_date), boarding_status=VALUES(boarding_status), payload=VALUES(payload), updated_at=CURRENT_TIMESTAMP(3)`,
          [String(user.id),body.id,serviceDate(r.createdAt),r.boardingStatus,JSON.stringify(r)]);
      }
      return send(res,200,{success:true});
    } catch {return fail(res,500,'모바일 예약 DB 조회·저장 실패. 서버 설정과 마이그레이션을 확인하세요.');}
  };
}
