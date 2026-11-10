import test from 'node:test';
import assert from 'node:assert/strict';
import { createRouteSelectionHandler, validateCatalog } from '../route-selection-service.js';
import { segmentFingerprint } from '../driver-service.js';
import { createMockServer } from '../mock-server.js';
const segment={type:'BUS',routeId:'route-up',from:'A',to:'B'};
const route={enabled:true,simulationId:'test',routeNumber:'150',routeLabel:'150번',routeId:'route-up',directionKey:'상행',stops:[{sequence:0,name:'A'},{sequence:1,name:'B'}],mappings:[{reservationId:1,segmentIndex:0,boardingSequence:0,alightingSequence:1,segmentSha256:segmentFingerprint(segment)}]};
async function call(handler, query='routeNumber=150&direction=상행', method='GET') {
 let code,body;
 await handler({url:'/api/driver/route-snapshot?'+query,method},{writeHead:c=>code=c,end:b=>body=JSON.parse(b)});
 return {code,body};
}
test('로그인 없이 집계만 반환, 취소 반영, 개인정보/예약 ID 미노출',async()=>{
 let status='RESERVED';
 const h=createRouteSelectionHandler({loadCatalog:async()=>({routes:[route]}),db:{execute:async(sql,ids)=>{
  assert.deepEqual(ids,['1']);assert.ok(!sql.includes('LIMIT 50'));
  return [[{id:1,status,route_data:{segments:[segment]},user_id:99,start_name:'private'}]];
 }}});
 const first=await call(h);assert.equal(first.code,200);assert.deepEqual(first.body.counts,{boarding:[1,0],alighting:[0,1]});
 for(const forbidden of ['reservations','user_id','start_name','route_data','exclusions'])assert.ok(!(forbidden in first.body));
 status='CANCELLED';assert.deepEqual((await call(h)).body.counts,{boarding:[0,0],alighting:[0,0]});
});
test('노선·방향별 선택, 잘못된 요청은 DB 조회 안 함',async()=>{
 const down={...route,routeId:'route-down',directionKey:'하행',stops:[{sequence:0,name:'B'},{sequence:1,name:'A'}],mappings:[]};
 let calls=0;
 const h=createRouteSelectionHandler({loadCatalog:async()=>({routes:[route,down]}),db:{execute:async()=>{calls++;return [[]]}}});
 const response=await call(h,'routeNumber=150&direction=하행');assert.equal(response.code,200);assert.equal(response.body.stops[0].name,'B');
 assert.equal((await call(h,'routeNumber=999&direction=상행')).code,404);
 assert.equal((await call(h,'routeNumber=150&direction=UP')).code,400);
 assert.equal((await call(h,'routeNumber=150&routeNumber=999&direction=상행')).code,400);
 assert.equal((await call(h,undefined,'POST')).code,405);assert.equal(calls,0);
 assert.throws(()=>validateCatalog({routes:[route,route]}));
});
test('DB 오류와 설정 오류를 0명 성공으로 처리하지 않음',async()=>{
 const db={execute:async()=>{throw Error('db secret')}};
 assert.equal((await call(createRouteSelectionHandler({db,loadCatalog:async()=>({routes:[route]})}))).code,500);
 assert.equal((await call(createRouteSelectionHandler({db,loadCatalog:async()=>{throw Error('config')}}))).code,503);
});
test('HTTP 익명 노선 조회: 상하행 분리, 쿠키 없이 성공, 미등록 노선 오류',async()=>{
 const server=createMockServer();await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve)});
 try {
 const base=`http://127.0.0.1:${server.address().port}/api/driver/route-snapshot`;
 const up=await fetch(base+'?routeNumber=150&direction='+encodeURIComponent('상행'));
 assert.equal(up.status,200);assert.equal(up.headers.get('set-cookie'),null);const u=await up.json();assert.equal(u.counts.boarding[1],2);
 const d=await (await fetch(base+'?routeNumber=150&direction='+encodeURIComponent('하행'))).json();assert.equal(d.stops[0].name,u.stops.at(-1).name);assert.equal(d.directionKey,'하행');
 assert.equal((await fetch(base+'?routeNumber=999&direction='+encodeURIComponent('상행'))).status,404);
 }finally{await new Promise(resolve=>server.close(resolve));}
});
