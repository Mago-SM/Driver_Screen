import test from 'node:test';
import assert from 'node:assert/strict';
import { normalize, segmentFingerprint, validateConfig, createDriverHandler } from '../driver-service.js';
const bus = { type: 'BUS', routeId: 'verified-test-route', from: 'test-A', to: 'test-B', start: { lat: 1, lng: 1 }, end: { lat: 2, lng: 2 } };
const row = (id = 1) => ({ id, status: 'RESERVED', route_data: JSON.stringify({ segments: [{ type: 'WALK' }, bus] }) });
const config = (n = 1) => ({ enabled: true, simulationId: 'test', routeId: 'verified-test-route', routeLabel: 'TEST', directionKey: 'outbound', stops: [{ sequence: 0, name: 'test-A' }, { sequence: 1, name: 'test-B' }], mappings: Array.from({ length: n }, (_, i) => ({ reservationId: i + 1, segmentIndex: 1, boardingSequence: 0, alightingSequence: 1, segmentSha256: segmentFingerprint(bus) })) });
test('환승 원본 인덱스 보존, route_data 미노출', () => {
 const result = normalize([row()], config()); assert.equal(result.reservations[0].busSegments[0].segmentIndex, 1); assert.equal(result.reservations[0].route_data, undefined); assert.equal(result.excludedCount, 0);
});
test('51건 이상 잘리지 않음', () => assert.equal(normalize(Array.from({ length: 71 }, (_, i) => row(i+1)), config(71)).reservations.length, 71));
test('손상 JSON / 누락 / 변경 구간 제외', () => {
 const bad = row(); bad.route_data = '{'; assert.equal(normalize([bad], config()).exclusions[0].reason, 'INVALID_JSON');
 assert.equal(normalize([], config()).excludedCount, 1);
 const changed = row(); changed.route_data = JSON.stringify({ segments: [{type:'WALK'}, {...bus, start:{lat:4,lng:4}}] });
 assert.equal(normalize([changed], config()).exclusions[0].reason, 'SEGMENT_CHANGED');
});
test('다른 노선과 알 수 없는 상태 제외', () => {
 const r = row(); r.route_data = JSON.stringify({segments:[{}, {...bus,routeId:'other'}]}); assert.equal(normalize([r],config()).excludedCount,1);
 assert.equal(normalize([{...row(),status:'OTHER'}],config()).excludedCount,1);
});
test('취소 상태 보존, 예약 추가로 노선 버전 불변', () => {
 assert.equal(normalize([{...row(),status:'CANCELLED'}],config()).reservations[0].status,'CANCELLED');
 assert.equal(normalize([row()],config()).configVersion, normalize([row(),row(2)],config(2)).configVersion);
});
test('중복 매핑/역방향/비활성 설정 거부', () => {
 const c = config(); c.mappings.push(c.mappings[0]); assert.throws(()=>validateConfig(c));
 const d = config(); d.mappings[0].boardingSequence = 1; assert.throws(()=>validateConfig(d));
 assert.throws(()=>validateConfig({...config(),enabled:false}));
});
const response = () => ({ writeHead(status){this.status=status}, end(body){this.body=JSON.parse(body)} });
test('PASSENGER는 DB 조회 없이 403', async () => {
 const res=response(); await createDriverHandler({requireUser:async()=>({role:'PASSENGER'}),db:{execute(){throw Error('must not query')}},loadConfig:async()=>config(),environment:()=> 'development'})({},res); assert.equal(res.status,403);
});
test('미인증은 401, 운전자 development 제한',async()=>{
 const res=response(); await createDriverHandler({requireUser:async(req,res)=>{res.writeHead(401);res.end('{}');return null}})({},res);assert.equal(res.status,401);
 const r=response();await createDriverHandler({requireUser:async()=>({role:'DRIVER'}),environment:()=> 'production'})({},r);assert.equal(r.status,403);
});
test('전체 조회 파라미터, 빈 데이터, DB 장애, 미설정', async()=>{
 let ids; const handler=createDriverHandler({requireUser:async()=>({role:'DRIVER'}),db:{execute:async(sql,params)=>{assert.ok(!sql.includes('LIMIT'));ids=params;return [[row()]]}},loadConfig:async()=>config(),environment:()=> 'development'});
 const res=response();await handler({},res);assert.deepEqual(ids,['1']);assert.equal(res.body.complete,true);
 const blank=response();await createDriverHandler({requireUser:async()=>({role:'DRIVER'}),db:{},loadConfig:async()=>config(0),environment:()=> 'development'})({},blank);assert.deepEqual(blank.body.reservations,[]);
 const broken=response();await createDriverHandler({requireUser:async()=>({role:'DRIVER'}),db:{execute:async()=>{throw Error('db')}},loadConfig:async()=>config(),environment:()=> 'development'})({},broken);assert.equal(broken.status,500);
 const missing=response();await createDriverHandler({requireUser:async()=>({role:'DRIVER'}),loadConfig:async()=>{throw Error('missing')},environment:()=> 'development'})({},missing);assert.equal(missing.status,503);
});
