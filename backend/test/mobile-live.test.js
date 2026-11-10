import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {createMobileHandler,serviceDate,validateReservation} from '../mobile-reservations.js';
import {createLiveDriverHandler,aggregateMobile} from '../live-driver-service.js';
import {createJinjuProvider} from '../jinju-provider.js';
const route={routeId:'jinju:150:0',routeNumber:'150',label:'150'};
const stops=['A','B','C'].map((id,sequence)=>({id,name:id,sequence,sourceOrder:sequence+1}));
const opposite=stops.toReversed().map((s,sequence)=>({...s,sequence,sourceOrder:sequence+4}));
const date='2026-11-10',now=Date.parse('2026-11-10T01:00:00Z');
const booking=(extra={})=>({id:'r1',routeId:route.routeId,routeNumber:'150',boardingStopId:'A',boardingStopName:'A',alightingStopId:'C',alightingStopName:'C',boardingStatus:'RESERVED',createdAt:now,completedRideLegs:0,...extra});
const context={route,stops,oppositeStops:opposite,variantCount:1,direction:'상행',vehicleId:'bus-1',date};
const aggregate=(values,ctx=context)=>aggregateMobile(values.map(payload=>({payload})),ctx);
test('예약 → 승차 → 다른 정류장 하차 요청 → 완료',()=>{
 let r=booking();assert.deepEqual(aggregate([r]).counts.boarding,[1,0,0]);
 r={...r,boardingStatus:'BOARDED',rideRouteId:route.routeId,rideVehicleId:'bus-1'};
 let s=aggregate([r]);assert.equal(s.onboard,1);assert.deepEqual(s.counts.boarding,[0,0,0]);assert.deepEqual(s.counts.alighting,[0,0,0]);assert.deepEqual(s.counts.plannedAlighting,[0,0,1]);
 r={...r,rideAlightingStopId:'B',rideAlightingStopOrder:2};s=aggregate([r]);assert.deepEqual(s.counts.alighting,[0,1,0]);assert.deepEqual(s.counts.plannedAlighting,[0,1,0]);
 assert.equal(aggregate([{...r,boardingStatus:'COMPLETED'}]).onboard,0);
 assert.equal(aggregate([{...r,rideVehicleId:'bus-2'}]).onboard,0);
});
test('하행/다른 노선/경로 저장만은 섞이지 않음, 71명 제한 없음',()=>{
 assert.equal(aggregate([booking({boardingStatus:'PENDING'})]).counts.boarding[0],0);
 assert.equal(aggregate([booking({routeId:'jinju:150:1'})]).counts.boarding[0],0);
 assert.equal(aggregate([booking()],{...context,stops:opposite,oppositeStops:stops,direction:'하행'}).counts.boarding.reduce((a,b)=>a+b),0);
 assert.equal(aggregate(Array.from({length:71},(_,i)=>booking({id:`r${i}`}))).counts.boarding[0],71);
});
test('추천 경로 환승은 현재 BUS 구간만, 지원 노선 모호함/반복 정류장 제외',()=>{
 const routeSnapshot=JSON.stringify({route:{segments:[{type:'WALK'},{type:'BUS',busNumber:'151',from:'X',to:'Y'},{type:'BUS',busNumber:'일반:150',from:'A',to:'C'}]}});
 const r=booking({routeSnapshot,completedRideLegs:1});assert.equal(aggregate([r]).counts.boarding[0],1);
 assert.equal(aggregate([r],{...context,variantCount:2}).excludedCount,1);
 const repeat=[...stops,{id:'C',name:'C',sequence:3,sourceOrder:4}];assert.equal(aggregate([booking()],{...context,stops:repeat}).excludedCount,1);
});
test('KST 서비스 날짜와 잘못된 모바일 입력 검증',()=>{
 assert.equal(serviceDate(Date.parse('2026-11-09T15:00:00Z')),date);
 assert.throws(()=>validateReservation('r1',booking({boardingStatus:'UNKNOWN'})));
 assert.throws(()=>validateReservation('r1',booking({id:'r2'})));
 validateReservation('r1',null);
});
test('실제 HTTP 계약: 계정 분리/중복 재전송/삭제/동기화 → 운전자 집계',async()=>{
 const saved=new Map();
 const db={execute:async(sql,args)=>{
  if(sql.startsWith('INSERT')){const [owner,id,day,state,payload]=args;saved.set(owner+':'+id,{owner,day,state,payload});return [{}];}
  if(sql.startsWith('DELETE')){saved.delete(args[0]+':'+args[1]);return [{}];}
  if(sql.includes('WHERE user_id'))return [[...saved.values()].filter(r=>r.owner===args[0])];
  assert.ok(sql.includes('service_date = ?'));return [[...saved.values()].filter(r=>r.day===args[0]&&['RESERVED','BOARDED'].includes(r.state))];
 }};
 const requireUser=async(req,res)=>{const owner=req.headers['x-test-owner'];if(owner)return {id:owner};res.writeHead(401);res.end('{}');return null;};
 const mobile=createMobileHandler({db,requireUser});
 const driver=createLiveDriverHandler({db,now:()=>now,provider:{routes:async()=>[route],stops:async(id,d)=>d==='상행'?stops:opposite,vehicles:async()=>[{vehicleId:'bus-1',label:'bus-1'}]}});
 const server=http.createServer((req,res)=>{(req.url.startsWith('/api/mobile')?mobile:driver)(req,res)});
 await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve)});
 const base=`http://127.0.0.1:${server.address().port}`;
 const sync=(owner,reservation)=>fetch(base+'/api/mobile/reservations',{method:'POST',headers:{'x-test-owner':owner,'Content-Type':'application/json'},body:JSON.stringify({id:'r1',reservation})});
 const snapshot=async()=>{const res=await fetch(base+'/api/driver/route-snapshot?'+new URLSearchParams({routeNumber:'150',direction:'상행',routeId:route.routeId,vehicleId:'bus-1'}));assert.equal(res.status,200);return res.json();};
 try{
 assert.equal((await fetch(base+'/api/mobile/reservations')).status,401);
 await sync('one',booking());await sync('one',booking());assert.equal((await snapshot()).counts.boarding[0],1);
 await sync('two',booking());assert.equal((await snapshot()).counts.boarding[0],2);
 const own=await (await fetch(base+'/api/mobile/reservations',{headers:{'x-test-owner':'one'}})).json();assert.equal(own.reservations.length,1);
 await sync('one',booking({boardingStatus:'BOARDED',rideRouteId:route.routeId,rideVehicleId:'bus-1',rideAlightingStopId:'B',rideAlightingStopOrder:2}));
 const s=await snapshot();assert.equal(s.counts.boarding[0],1);assert.equal(s.counts.alighting[1],1);assert.ok(!('reservations' in s));
 await sync('one',null);await sync('one',null);assert.equal((await snapshot()).counts.alighting[1],0);assert.equal(saved.size,1);
 }finally{await new Promise(resolve=>server.close(resolve));}
});
test('진주시 실제 응답 형식: 지원노선, drct, 원본 순서, 캐시, 설정 누락',async()=>{
 let calls=0;
 const provider=createJinjuProvider({env:{JINJU_BUS_API_KEY:'fixture',JINJU_UP_DIRECTION:'U',JINJU_DOWN_DIRECTION:'D'},fetcher:async(url)=>{
 calls++;const data=url.pathname.endsWith('getRouteList')?{routeAll:[{routeNo:'150',suptNo:'0'},{routeNo:'150',suptNo:'1'},{routeNo:'1500',suptNo:'0'}]}:
 {routeBsta:{bsta:[{drct:'U',sqno:1,bstaSrvcId:'A',bstaNm:'A'},{drct:'U',sqno:2,bstaSrvcId:'B',bstaNm:'B'},{drct:'D',sqno:1,bstaSrvcId:'B',bstaNm:'B'},{drct:'D',sqno:2,bstaSrvcId:'A',bstaNm:'A'}]}};
 return {ok:true,json:async()=>({resultCode:'200',...data})};}});
 assert.equal((await provider.routes('150')).length,2);await provider.routes('150');assert.equal(calls,1);
 assert.equal((await provider.stops(route.routeId,'하행'))[0].id,'B');
 await assert.rejects(createJinjuProvider({env:{}}).routes('150'));
});
