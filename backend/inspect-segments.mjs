// 개인정보를 제거한 route_data JSON 파일을 입력하세요. DB에는 접속하지 않습니다.
import { readFile } from 'node:fs/promises';
import { segmentFingerprint } from './driver-service.js';
const route = JSON.parse(await readFile(process.argv[2], 'utf8'));
if (!Array.isArray(route.segments)) throw Error('route_data의 segments 배열이 필요합니다.');
console.log(JSON.stringify(route.segments.flatMap((s, segmentIndex) => s.type === 'BUS' ? [{ segmentIndex, routeId: s.routeId, from: s.from, to: s.to, start: s.start, end: s.end, segmentSha256: segmentFingerprint(s) }] : []), null, 2));
