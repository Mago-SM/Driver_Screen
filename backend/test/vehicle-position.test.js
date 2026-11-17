import test from 'node:test';
import assert from 'node:assert/strict';
import {vehiclePosition,positionTime} from '../vehicle-position.js';
import {createJinjuProvider} from '../jinju-provider.js';
import {createLiveDriverHandler} from '../live-driver-service.js';
const now=Date.parse('2026-11-17T01:00:00Z');
const stops=[{id:'A',name:'A',sequence:0,direction:'1'},{id:'B',name:'B',sequence:1,direction:'1'}];
const vehicle={vehicleId:'selected',latitude:35,longitude:128,updatedAt:'20261117100000',passedStopId:'A',direction:'1'};
test('GPS freshness, invalid coordinates, direction and ambiguous stop',()=>{
 assert.equal(positionTime('2026-11-17 10:00:00'),now);
 assert.equal(vehiclePosition(vehicle,stops,now).passedSequence,0);
 for(const v of [{...vehicle,latitude:null},{...vehicle,longitude:NaN}])assert.equal(vehiclePosition(v,stops,now).status,'UNAVAILABLE');
 assert.equal(vehiclePosition(vehicle,stops,now+30001).status,'STALE');
 assert.equal(vehiclePosition({...vehicle,updatedAt:''},stops,now).status,'STALE');
 assert.equal(vehiclePosition({...vehicle,direction:'2'},stops,now).status,'WRONG_DIRECTION');
 assert.equal(vehiclePosition(vehicle,[...stops,stops[0]],now).status,'UNMATCHED');
});
test('provider coordinates and five second vehicle cache',async()=>{
 let clock=now,calls=0;
 const provider=createJinjuProvider({now:()=>clock,env:{JINJU_BUS_API_KEY:'fixture',JINJU_UP_DIRECTION:'1',JINJU_DOWN_DIRECTION:'2'},fetcher:async()=>{
  calls++;return {ok:true,json:async()=>({resultCode:200,busLoc:{bus:[{vhno:'selected',lttd:'35',lgtd:'128',pasgBstaSrvcId:'A',altrDtm:'20261117100000',drct:'1'}]}})};
 }});
 const [v]=await provider.vehicles('jinju:150:1');assert.equal(v.latitude,35);assert.equal(v.passedStopId,'A');
 clock+=4999;await provider.vehicles('jinju:150:1');assert.equal(calls,1);
 clock++;await provider.vehicles('jinju:150:1');assert.equal(calls,2);
});
test('selected vehicle only and GPS outage preserves reservation counts',async()=>{
 let vehicles=[{...vehicle,vehicleId:'other',latitude:36},vehicle];
 const handler=createLiveDriverHandler({db:{execute:async()=>[[]]},now:()=>now,provider:{
  routes:async()=>[{routeId:'jinju:150:1',routeNumber:'150'}],stops:async()=>stops,
  vehicles:async()=>{if(vehicles===null)throw Error('outage');return vehicles;}
 }});
 async function get(){let body;await handler({method:'GET',url:'/api/driver/route-snapshot?routeNumber=150&direction='+encodeURIComponent('상행')+'&routeId=jinju:150:1&vehicleId=selected'},
 {writeHead(status){assert.equal(status,200);},end(value){body=JSON.parse(value);}});return body;}
 assert.equal((await get()).position.latitude,35);
 vehicles=[];assert.equal((await get()).position.status,'UNAVAILABLE');
 vehicles=null;const result=await get();assert.equal(result.position.status,'UNAVAILABLE');assert.deepEqual(result.counts.boarding,[0,0]);
});
