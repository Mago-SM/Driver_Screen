import http from 'node:http';
import db from './db.js';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {parseRoutes} from './routes.js';
import { WebSocketServer, WebSocket } from 'ws';
import {
  authHandler,
  requireUser
} from './auth-server.js';
const root = path.dirname(fileURLToPath(import.meta.url));
try { const env = await readFile(path.join(root, '.env'), 'utf8'); for (const line of env.split(/\r?\n/)) { const match = line.match(/^([A-Z_]+)=(.*)$/); if (match && !process.env[match[1]]) process.env[match[1]] = match[2].trim().replace(/^['"]|['"]$/g, ''); } } catch (error) { if (error.code !== 'ENOENT') throw error; }
const key = process.env.TMAP_API_KEY;
const json = (res, status, body) => { res.writeHead(status, {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'}); res.end(JSON.stringify(body)); };
const fail = (res,status,code,message,details={}) => json(res,status,{error:{code,message,...details}});
const validLat = v => v !== null && v !== '' && Number.isFinite(Number(v)) && Math.abs(Number(v)) <= 90;
const validLng = v => v !== null && v !== '' && Number.isFinite(Number(v)) && Math.abs(Number(v)) <= 180;
const validSearchDttm = value => {
  if (!/^\d{12}$/.test(value)) return false;
  const year=+value.slice(0,4), month=+value.slice(4,6), day=+value.slice(6,8), hour=+value.slice(8,10), minute=+value.slice(10,12);
  const date=new Date(Date.UTC(year,month-1,day,hour,minute));
  return year>=1900 && year<=9999 && date.getUTCFullYear()===year && date.getUTCMonth()===month-1 && date.getUTCDate()===day && hour<=23 && minute<=59;
};
async function upstream(url, options = {}) {
  const response = await fetch(url, { ...options, headers: {'appKey': key, 'Accept':'application/json', ...options.headers}, signal: AbortSignal.timeout(10000)});
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    const detail = [data?.error?.message, data?.error?.detail, data?.message, data?.msg, data?.errorMessage, data?.errorMsg].find(value => typeof value === 'string' && value.trim());
    const error = new Error(`TMAP HTTP ${response.status}${detail ? `: ${detail.slice(0, 240)}` : ' 응답 오류'}`);
    error.status = response.status === 429 ? 429 : 502;
    error.upstreamStatus = response.status;
    throw error;
  }
  if (!data) { const error = new Error('TMAP 응답을 읽을 수 없습니다.'); error.status = 502; throw error; }
  return data;
}
async function readJsonBody(req) {
  let text = '';

  for await (const chunk of req) {
    text += chunk.toString();

    if (text.length > 200000) {
      const error = new Error('예약 데이터가 너무 큽니다.');
      error.status = 413;
      throw error;
    }
  }

  try {
    return JSON.parse(text);
  } catch {
    const error = new Error('올바른 JSON 데이터가 아닙니다.');
    error.status = 400;
    throw error;
  }
}
async function createReservation(req, res) {
  try {
    const body = await readJsonBody(req);

    const {
      startName, startLat, startLng,
      endName, endLat, endLng,
      departureTime, route
    } = body;

    if (
      typeof startName !== 'string' ||
      typeof endName !== 'string' ||
      !startName.trim() || !endName.trim() ||
      startName.length > 255 || endName.length > 255 ||
      !validLat(startLat) || !validLng(startLng) ||
      !validLat(endLat) || !validLng(endLng) ||
      !route || !Array.isArray(route.segments) ||
      !route.segments.some(s => s.type === 'BUS')
    ) {
      return fail(res, 400, 'INVALID_RESERVATION', '예약 정보가 올바르지 않습니다.');
    }

    if (
      typeof departureTime !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(departureTime) ||
      !validSearchDttm(departureTime.replace(/\D/g, ''))
    ) {
      return fail(res, 400, 'INVALID_TIME', '출발 시각이 올바르지 않습니다.');
    }

    const busSegments = route.segments.filter(s => s.type === 'BUS');
    const boardingStop = busSegments[0]?.from || null;
    const dropoffStop = busSegments.at(-1)?.to || null;

    const routeJson = JSON.stringify(route);

    if (routeJson.length > 100000) {
      return fail(res, 413, 'ROUTE_TOO_LARGE', '경로 정보가 너무 큽니다.');
    }

    const user = await requireUser(req, res);
    if (!user) return;

const userId = user.id;
    const [result] = await db.execute(
      `INSERT INTO reservations
       (user_id, start_name, start_lat, start_lng,
        end_name, end_lat, end_lng,
        boarding_stop, dropoff_stop,
        departure_time, route_data, status)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED')`,
      [
        userId,
        startName,
        Number(startLat),
        Number(startLng),
        endName,
        Number(endLat),
        Number(endLng),
        boardingStop,
        dropoffStop,
        departureTime.replace('T', ' ') + ':00',
        routeJson
      ]
    );

    return json(res, 201, {
      success: true,
      reservationId: result.insertId
    });

  } catch (error) {
    console.error('예약 저장 실패:', error);
    return fail(
      res,
      error.status || 500,
      'RESERVATION_ERROR',
      error.status ? error.message : '예약 저장 중 서버 오류가 발생했습니다.'
    );
  }
}
export async function handler(req,res) {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname.startsWith('/api/auth/')) {
    return authHandler(req, res, url.pathname);
  }
  if (url.pathname.startsWith('/api/')) {
    if (
  url.pathname === '/api/driver/reservations' &&
  req.method === 'GET'
) {
  if (process.env.NODE_ENV !== 'development') {
    return fail(res, 403, 'FORBIDDEN', '개발 환경에서만 사용할 수 있습니다.');
  }

  const user = await requireUser(req, res);
  if (!user) return;

  const [rows] = await db.execute(`
    SELECT
      id,
      start_name,
      end_name,
      boarding_stop,
      dropoff_stop,
      departure_time,
      status
    FROM reservations
    ORDER BY id DESC
    LIMIT 50
  `);

  return json(res, 200, { reservations: rows });
}
    const cancelMatch = url.pathname.match(
  /^\/api\/reservations\/([1-9]\d*)\/cancel$/
);

if (cancelMatch) {
  if (req.method !== 'PATCH') {
    return fail(res, 405, 'METHOD_NOT_ALLOWED', 'PATCH 요청만 지원합니다.');
  }

  const user = await requireUser(req, res);
  if (!user) return;

  const reservationId = Number(cancelMatch[1]);

  if (!Number.isSafeInteger(reservationId)) {
    return fail(res, 400, 'INVALID_ID', '예약 번호가 올바르지 않습니다.');
  }

  const [result] = await db.execute(
    `UPDATE reservations
     SET status = 'CANCELLED'
     WHERE id = ?
       AND user_id = ?
       AND status = 'RESERVED'`,
    [reservationId, user.id]
  );

  if (result.affectedRows === 0) {
    return fail(
      res,
      409,
      'CANCEL_FAILED',
      '취소 가능한 본인 예약을 찾을 수 없습니다.'
    );
  }

  return json(res, 200, {
    success: true,
    reservationId,
    status: 'CANCELLED'
  });
}

  if (url.pathname === '/api/reservations') {
     const user = await requireUser(req, res);
  if (!user) return;

  if (req.method === 'POST') {
    return createReservation(req, res);
  }

  if (req.method === 'GET') {
    const [rows] = await db.execute(
      `SELECT id, start_name, end_name,
        departure_time, status, created_at, route_data
 FROM reservations
 WHERE user_id = ?
 ORDER BY id DESC`,
      [user.id]
    );

    return json(res, 200, {
      reservations: rows
    });
  }

  return fail(
    res, 405, 'METHOD_NOT_ALLOWED',
    'GET 또는 POST만 지원합니다.'
  );
}

  if (req.method !== 'GET') {
    return fail(res, 405, 'METHOD_NOT_ALLOWED', 'GET 요청만 지원합니다.');
  }
    if (!key) return fail(res,503,'KEY_MISSING','서버에 TMAP_API_KEY를 설정해 주세요.');
    try {
      if (url.pathname === '/api/routes') {
        const user = await requireUser(req, res);
        if (!user) return;
        const p = url.searchParams;
        if (!validLat(p.get('startLat')) || !validLng(p.get('startLng')) || !validLat(p.get('endLat')) || !validLng(p.get('endLng'))) return fail(res,400,'INVALID_COORDINATES','올바른 위도와 경도를 입력해 주세요.');
        const searchDttm = p.get('searchDttm');
        if (searchDttm && !validSearchDttm(searchDttm)) return fail(res,400,'INVALID_DEPARTURE_TIME','출발 시각은 YYYYMMDDHHmm 형식으로 입력해 주세요.');
        const body = {startX:p.get('startLng'),startY:p.get('startLat'),endX:p.get('endLng'),endY:p.get('endLat'),count:10,lang:0,format:'json'};
        if (searchDttm) body.searchDttm = searchDttm;
        const data = await upstream('https://apis.openapi.sk.com/transit/routes',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
        return json(res,200,{routes:parseRoutes(data)});
      }
      return fail(res,404,'NOT_FOUND','API를 찾을 수 없습니다.');
    } catch (error) {
      if (error.name === 'TimeoutError' || error.name === 'AbortError') return fail(res,504,'UPSTREAM_TIMEOUT','TMAP 응답 시간이 초과됐습니다. 다시 시도해 주세요.');
      return fail(res,error.status || 502,'UPSTREAM_ERROR',error.message || '외부 API 연결에 실패했습니다.',error.upstreamStatus ? {upstreamStatus:error.upstreamStatus} : {});
    }
  }
  const files =  {
  '/': 'index.html',
  '/app.js': 'app.js',
  '/auth-client.js': 'auth-client.js',
  '/destination.js': 'destination.js',
  '/style.css': 'style.css',
  '/driver.html': 'driver.html'
};
  if (req.method !== 'GET' || !files[url.pathname]) return fail(res,404,'NOT_FOUND','페이지를 찾을 수 없습니다.');
  const name = files[url.pathname];
  const mime = name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html';
  const content = await readFile(path.join(root,'public',name));
  res.writeHead(200,{'Content-Type':`${mime}; charset=utf-8`,'Cache-Control':'no-store'}); res.end(content);
}
if (process.env.NODE_ENV !== 'test') {
  const server = http.createServer((req, res) => {
    handler(req, res).catch(error => {
      console.error('서버 오류:', error);
      fail(res, 500, 'SERVER_ERROR', '서버 오류가 발생했습니다.');
    });
  });

  const wss = new WebSocketServer({
    server,
    path: '/ws'
  });

  wss.on('connection', ws => {
    console.log('WebSocket 클라이언트 연결');

    ws.send(JSON.stringify({
      type: 'connected',
      message: 'WebSocket 연결 성공'
    }));

    ws.on('message', data => {
      console.log('WebSocket 메시지 수신:', data.toString());
    });

    ws.on('close', () => {
      console.log('WebSocket 클라이언트 연결 종료');
    });
  });

  const port = Number(process.env.PORT) || 3000;

  server.listen(port, () => {
    console.log(`HTTP 서버: http://localhost:${port}`);
    console.log(`WebSocket 서버: ws://localhost:${port}/ws`);
  });
}
