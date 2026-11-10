import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';

export const segmentFingerprint = segment => createHash('sha256').update(JSON.stringify(segment)).digest('hex');
const validId = id => /^[1-9]\d*$/.test(String(id)) && Number.isSafeInteger(Number(id));
const assert = (ok, message) => { if (!ok) throw new Error(message); };
export function validateConfig(c) {
  assert(c?.enabled === true, '검증된 시연 설정을 활성화하세요.');
  for (const key of ['simulationId', 'routeId', 'routeLabel', 'directionKey']) assert(typeof c[key] === 'string' && c[key].trim(), `${key} 설정 필요`);
  assert(Array.isArray(c.stops) && c.stops.length >= 2, '정류장이 2개 이상 필요합니다.');
  c.stops.forEach((s, i) => assert(s.sequence === i && typeof s.name === 'string' && s.name.trim(), '정류장 방문 순서는 0부터 연속이어야 합니다.'));
  assert(Array.isArray(c.mappings) && c.mappings.length <= 5000, '매핑 목록은 최대 5000개입니다. 범위를 분리하세요.');
  const seen = new Set();
  for (const m of c.mappings) {
    assert(validId(m.reservationId) && Number.isInteger(m.segmentIndex) && m.segmentIndex >= 0, '예약 ID / 원본 구간 인덱스 오류');
    assert(Number.isInteger(m.boardingSequence) && Number.isInteger(m.alightingSequence) && m.boardingSequence >= 0 && m.alightingSequence < c.stops.length && m.boardingSequence < m.alightingSequence, '승하차 위치 오류');
    assert(/^[a-f0-9]{64}$/.test(m.segmentSha256), '확인한 BUS 구간의 SHA-256이 필요합니다.');
    const key = `${m.reservationId}:${m.segmentIndex}`;
    assert(!seen.has(key), '중복 매핑'); seen.add(key);
  }
  return c;
}
export function normalize(rows, config) {
  const c = validateConfig(config);
  const byId = new Map(rows.map(r => [String(r.id), r]));
  const output = new Map(); const exclusions = [];
  for (const m of c.mappings) {
    const row = byId.get(String(m.reservationId));
    let reason;
    let segment;
    try {
      if (!row) throw new Error('RESERVATION_MISSING');
      if (!['RESERVED', 'CANCELLED'].includes(row.status)) throw new Error('UNKNOWN_STATUS');
      const route = typeof row.route_data === 'string' ? JSON.parse(row.route_data) : row.route_data;
      segment = route?.segments?.[m.segmentIndex];
      if (segment?.type !== 'BUS' || String(segment.routeId) !== c.routeId) throw new Error('ROUTE_MISMATCH');
      if (segmentFingerprint(segment) !== m.segmentSha256) throw new Error('SEGMENT_CHANGED');
      // 관리자가 좌표/방향/반복 방문을 확인한 명시적 매핑만 사용합니다.
      if (segment.from !== c.stops[m.boardingSequence].name || segment.to !== c.stops[m.alightingSequence].name) throw new Error('STOP_MISMATCH');
    } catch (e) { reason = e instanceof SyntaxError ? 'INVALID_JSON' : e.message; }
    if (reason) { exclusions.push({ reservationId: String(m.reservationId), segmentIndex: m.segmentIndex, reason }); continue; }
    const key = String(row.id);
    if (!output.has(key)) {
      const { route_data, ...legacy } = row;
      output.set(key, { ...legacy, id: key, busSegments: [] });
    }
    output.get(key).busSegments.push({ segmentIndex: m.segmentIndex, boardingSequence: m.boardingSequence, alightingSequence: m.alightingSequence });
  }
  // 예약 등록/취소는 운행 위치를 초기화하지 않습니다. 노선 정의 변경만 버전에 반영합니다.
  const configVersion = segmentFingerprint({ simulationId: c.simulationId, routeId: c.routeId, directionKey: c.directionKey, stops: c.stops });
  return { simulationId: c.simulationId, configVersion, routeId: c.routeId, routeLabel: c.routeLabel,
    directionKey: c.directionKey, stops: c.stops, reservations: [...output.values()],
    complete: true, excludedCount: exclusions.length, exclusions };
}
export function createDriverHandler({ db, requireUser, loadConfig, environment = () => process.env.NODE_ENV }) {
  const send = (res, status, body) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(body)); };
  return async (req, res) => {
    const user = await requireUser(req, res);
    if (!user) return;
    if (user.role !== 'DRIVER') return send(res, 403, { error: { code: 'DRIVER_REQUIRED', message: '운전자 계정이 필요합니다.' } });
    if (environment() !== 'development') return send(res, 403, { error: { code: 'DEVELOPMENT_ONLY', message: '시연 API는 development 환경에서만 사용할 수 있습니다.' } });
    let config;
    try { config = validateConfig(await loadConfig()); }
    catch { return send(res, 503, { error: { code: 'CONFIG_REQUIRED', message: '검증된 driver-config.json 시연 설정이 필요합니다.' } }); }
    try {
      const ids = [...new Set(config.mappings.map(m => String(m.reservationId)))];
      // 시연 ID 목록으로 제한한 전체 조회. LIMIT 50으로 인원을 잘라내지 않습니다.
      const rows = [];
      for (let i = 0; i < ids.length; i += 500) {
        const batch = ids.slice(i, i + 500);
        const [part] = await db.execute(`SELECT id, start_name, end_name, boarding_stop, dropoff_stop, departure_time, status, route_data FROM reservations WHERE id IN (${batch.map(() => '?').join(',')})`, batch);
        rows.push(...part);
      }
      return send(res, 200, normalize(rows, config));
    } catch { return send(res, 500, { error: { code: 'DRIVER_QUERY_FAILED', message: '예약 조회 실패. 마지막 정상 데이터를 유지하세요.' } }); }
  };
}
export const readDriverConfig = async () => JSON.parse(await readFile(new URL('./driver-config.json', import.meta.url), 'utf8'));
