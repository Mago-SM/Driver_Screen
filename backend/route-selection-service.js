import { readFile } from 'node:fs/promises';
import { normalize, validateConfig } from './driver-service.js';

// No account, reservation identifier, or passenger itinerary is returned by this endpoint.
export function aggregateSnapshot(snapshot, routeNumber) {
  const boarding = snapshot.stops.map(() => 0), alighting = snapshot.stops.map(() => 0);
  for (const row of snapshot.reservations) {
    if (row.status !== 'RESERVED') continue;
    for (const segment of row.busSegments) {
      boarding[segment.boardingSequence]++;
      alighting[segment.alightingSequence]++;
    }
  }
  const { simulationId, configVersion, routeId, routeLabel, directionKey, stops, complete, excludedCount } = snapshot;
  return { simulationId, configVersion, routeId, routeNumber, routeLabel, directionKey, stops,
    complete, excludedCount, counts: { boarding, alighting } };
}
export function validateCatalog(catalog) {
  if (!Array.isArray(catalog?.routes)) throw new Error('노선 목록 설정 필요');
  const keys = new Set();
  for (const route of catalog.routes) {
    validateConfig(route);
    if (typeof route.routeNumber !== 'string' || !/^[0-9]{1,6}(-[0-9]{1,3})?$/.test(route.routeNumber) || !['상행', '하행'].includes(route.directionKey)) throw new Error('노선번호/방향 오류');
    const key = `${route.routeNumber}:${route.directionKey}`;
    if (keys.has(key)) throw new Error('중복 노선/방향');
    keys.add(key);
  }
  return catalog;
}
export function createRouteSelectionHandler({ db, loadCatalog }) {
  return async (req, res) => {
    const send = (status, body) => {
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(JSON.stringify(body));
    };
    const error = (status, message) => send(status, { error: { message } });
    if (req.method !== 'GET') return error(405, 'GET 요청만 지원합니다.');
    const params = new URL(req.url, 'http://localhost').searchParams;
    const number = params.get('routeNumber'), direction = params.get('direction');
    if (params.getAll('routeNumber').length !== 1 || params.getAll('direction').length !== 1 ||
        !/^[0-9]{1,6}(-[0-9]{1,3})?$/.test(number ?? '') || !['상행', '하행'].includes(direction)) {
      return error(400, '노선번호와 상행·하행을 선택하세요.');
    }
    let catalog;
    try { catalog = validateCatalog(await loadCatalog()); }
    catch { return error(503, '서버의 driver-routes.json 노선 설정을 확인하세요.'); }
    const route = catalog.routes.find(r => r.routeNumber === number && r.directionKey === direction);
    if (!route) return error(404, '등록되지 않은 노선 또는 방향입니다.');
    try {
      const ids = [...new Set(route.mappings.map(m => String(m.reservationId)))];
      const rows = [];
      for (let i = 0; i < ids.length; i += 500) {
        const batch = ids.slice(i, i + 500);
        const [part] = await db.execute(`SELECT id, status, route_data FROM reservations WHERE id IN (${batch.map(() => '?').join(',')})`, batch);
        rows.push(...part);
      }
      return send(200, aggregateSnapshot(normalize(rows, route), number));
    } catch { return error(500, '예약 조회 실패. 마지막 정상 데이터를 유지합니다.'); }
  };
}
export const readRouteCatalog = async () => JSON.parse(await readFile(new URL('./driver-routes.json', import.meta.url), 'utf8'));
