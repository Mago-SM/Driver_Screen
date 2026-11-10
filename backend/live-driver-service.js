import {createHash} from 'node:crypto';
import {send,fail,serviceDate} from './mobile-reservations.js';
const normalizeNumber=n=>String(n??'').split(':').at(-1).trim().replace(/번$/,'').trim();
function currentLeg(r){
  if(r.routeSnapshot){const s=JSON.parse(r.routeSnapshot).route.segments.filter(s=>s.type==='BUS')[r.completedRideLegs??0];if(!s)throw Error('환승 구간 없음');return {number:normalizeNumber(s.busNumber),from:s.from,to:s.to};}
  return {number:normalizeNumber(r.routeNumber),from:r.boardingStopName,to:r.alightingStopName};
}
function pairs(stops,r,leg,useIds){
  const matches=(s,id,name)=>id?s.id===id:s.name.trim()===String(name??'').trim();
  const result=[];
  for(let a=0;a<stops.length;a++)for(let b=a+1;b<stops.length;b++){
    if(matches(stops[a],useIds?r.boardingStopId:'',leg.from)&&matches(stops[b],useIds?r.alightingStopId:'',leg.to))result.push([a,b]);
  }
  return result;
}
export function aggregateMobile(rows,{route,stops,oppositeStops,variantCount,direction,vehicleId,date}){
  const boarding=stops.map(()=>0),alighting=stops.map(()=>0),plannedAlighting=stops.map(()=>0);
  let excludedCount=0,onboard=0;
  for(const row of rows){
    let r;try{r=typeof row.payload==='string'?JSON.parse(row.payload):row.payload;
      if(!['RESERVED','BOARDED'].includes(r.boardingStatus))continue;
      const leg=currentLeg(r);
      if(r.boardingStatus==='RESERVED'){
        if(leg.number!==route.routeNumber)continue;
        const hasId=String(r.routeId??'').startsWith('jinju:')&&!r.routeSnapshot;
        if(hasId && r.routeId!==route.routeId)continue;
        // Recommended-route snapshots lose route ID in bususer; never guess among variants.
        if(!hasId && variantCount!==1){excludedCount++;continue;}
        const matches=pairs(stops,r,leg,hasId),other=pairs(oppositeStops,r,leg,hasId);
        if(matches.length===0 && other.length>0)continue;
        if(matches.length!==1||other.length>0){excludedCount++;continue;}
        boarding[matches[0][0]]++;
      }else{
        if(r.rideRouteId!==route.routeId||r.rideVehicleId!==vehicleId)continue;
        const useIds=!r.routeSnapshot;
        const routePairs=pairs(stops,r,leg,useIds),otherPairs=pairs(oppositeStops,r,leg,useIds);
        if(routePairs.length===0 && otherPairs.length>0)continue;
        if(routePairs.length!==1||otherPairs.length>0){excludedCount++;continue;}
        let destination=routePairs[0][1];
        if(r.rideAlightingStopId){
          const candidates=stops.filter(s=>s.id===r.rideAlightingStopId &&
            (r.rideAlightingStopOrder>0?s.sourceOrder===r.rideAlightingStopOrder:true));
          if(candidates.length!==1){excludedCount++;continue;}
          destination=candidates[0].sequence;
          alighting[destination]++;
        }
        plannedAlighting[destination]++;onboard++;
      }
    }catch{excludedCount++;}
  }
  const hash=createHash('sha256').update(JSON.stringify({route:route.routeId,direction,stops})).digest('hex');
  return {simulationId:`mobile:${date}:${vehicleId}`,configVersion:hash,routeId:route.routeId,routeNumber:route.routeNumber,
    routeLabel:`${route.routeNumber}번`,directionKey:direction,vehicleId,serviceDate:date,stops,
    complete:true,excludedCount,onboard,counts:{boarding,alighting,plannedAlighting},
    boardingScope:'ROUTE_DIRECTION',alightingScope:'VEHICLE'};
}
export function createLiveDriverHandler({db,provider,now=Date.now}){
 return async(req,res)=>{
  if(req.method!=='GET')return fail(res,405,'GET 요청만 지원합니다.');
  const url=new URL(req.url,'http://localhost'),p=url.searchParams;
  const number=p.get('routeNumber'),direction=p.get('direction');
  if(!/^[0-9]{1,6}(-[0-9]{1,3})?$/.test(number??'')||!['상행','하행'].includes(direction))return fail(res,400,'노선번호와 방향을 확인하세요.');
  try{
   const routes=await provider.routes(number);
   if(url.pathname==='/api/driver/routes')return send(res,200,{routes});
   const route=routes.find(r=>r.routeId===p.get('routeId'));
   if(!route)return fail(res,404,'해당 지원 노선을 다시 선택하세요.');
   if(url.pathname==='/api/driver/vehicles')return send(res,200,{vehicles:await provider.vehicles(route.routeId)});
   if(url.pathname!=='/api/driver/route-snapshot')return fail(res,404,'API 없음');
   const vehicleId=p.get('vehicleId');if(!vehicleId||vehicleId.length>128)return fail(res,400,'운행 차량을 선택하세요.');
   const [stops,oppositeStops]=await Promise.all([provider.stops(route.routeId,direction),provider.stops(route.routeId,direction==='상행'?'하행':'상행')]);
   if(stops.length<2 || stops.some(s=>!s.id||!s.name))return fail(res,502,'방향별 정류장 정보를 확인할 수 없습니다.');
   const date=serviceDate(now());
   let rows;
   try{[rows]=await db.execute("SELECT payload FROM mobile_reservations WHERE service_date = ? AND boarding_status IN ('RESERVED','BOARDED')",[date]);}
   catch{return fail(res,500,'모바일 예약 DB 조회 실패. 마이그레이션과 연결 설정을 확인하세요.');}
   return send(res,200,aggregateMobile(rows,{route,stops,oppositeStops,variantCount:routes.length,direction,vehicleId,date}));
  }catch{return fail(res,503,'실제 노선 API 연결·인증키·상하행 코드 설정을 확인하세요.');}
 };
}
