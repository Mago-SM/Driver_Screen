// 로컬 통합 테스트 전용. 실제 DB/계정/배차와 연결하지 않습니다.
import http from 'node:http';
import { aggregateSnapshot } from './route-selection-service.js';
import { randomBytes } from 'node:crypto';
import { pathToFileURL } from 'node:url';
const names = ['진주시청','중앙시장','진주교 정류장','천전시장','경상국립대'];
export function createMockServer() {
 const sessions=new Map(); let second=true; let outage=false;
 const snapshot=()=>({simulationId:'http-mock-only',configVersion:'1',routeId:'example-150',routeLabel:'150번 · 예시',directionKey:'예시 방향',stops:names.map((name,sequence)=>({name,sequence})),complete:true,excludedCount:0,reservations:[
  {id:'A',status:'RESERVED',busSegments:[{segmentIndex:0,boardingSequence:1,alightingSequence:2}]},
  {id:'B',status:second?'RESERVED':'CANCELLED',busSegments:[{segmentIndex:0,boardingSequence:1,alightingSequence:4}]},
  {id:'C',status:'RESERVED',busSegments:[{segmentIndex:0,boardingSequence:0,alightingSequence:1}]}
 ]});
 return http.createServer(async(req,res)=>{
  const send=(status,data)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(data))};
  const url=new URL(req.url,'http://localhost');
  const path=url.pathname;
  if(path==='/api/driver/route-snapshot') {
   if(req.method!=='GET')return send(405,{error:{message:'GET only'}});
   const number=url.searchParams.get('routeNumber'), direction=url.searchParams.get('direction');
   if(!['상행','하행'].includes(direction)||!number)return send(400,{error:{message:'노선번호와 방향을 선택하세요.'}});
   if(number!=='150')return send(404,{error:{message:'테스트 서버는 150번 예시만 제공합니다.'}});
   if(outage)return send(503,{error:{message:'테스트용 연결 장애'}});
   const s=snapshot();s.directionKey=direction;
   if(direction==='하행') {
    const last=s.stops.length-1;
    s.stops=s.stops.reverse().map((stop,sequence)=>({...stop,sequence}));
    s.reservations=s.reservations.map(r=>({...r,busSegments:r.busSegments.map(seg=>({...seg,boardingSequence:last-seg.alightingSequence,alightingSequence:last-seg.boardingSequence}))}));
   }
   return send(200,aggregateSnapshot(s,number));
  }

  const token=/bus_session=([^;]+)/.exec(req.headers.cookie||'')?.[1]; const user=sessions.get(token);
  if(path==='/api/auth/login' && req.method==='POST') {
   let raw='';for await(const chunk of req){raw+=chunk;if(raw.length>16000)return send(413,{error:{message:'too large'}})}
   let body;try{body=JSON.parse(raw)}catch{return send(400,{error:{message:'invalid JSON'}})}
   if(!['driver','passenger'].includes(body.loginId)||body.password!=='demo1234')return send(401,{error:{message:'시연 계정 정보를 확인하세요.'}});
   const u={id:body.loginId==='driver'?1:2,name:'HTTP 시연 계정',role:body.loginId==='driver'?'DRIVER':'PASSENGER'};
   const t=randomBytes(32).toString('hex');sessions.set(t,u);res.setHeader('Set-Cookie',`bus_session=${t}; HttpOnly; SameSite=Lax; Path=/`);return send(200,{user:u});
  }
  if(path==='/api/auth/logout' && req.method==='POST'){sessions.delete(token);res.setHeader('Set-Cookie','bus_session=; Max-Age=0; Path=/');return send(200,{success:true})}
  if(!user)return send(401,{error:{message:'로그인이 필요합니다.'}});
  if(path==='/api/auth/me')return send(200,{user});
  if(user.role!=='DRIVER')return send(403,{error:{message:'운전자 계정이 필요합니다.'}});
  if(req.method==='POST' && path==='/mock/cancel'){second=false;return send(200,{success:true})}
  if(req.method==='POST' && path==='/mock/restore'){second=true;return send(200,{success:true})}
  if(req.method==='POST' && path==='/mock/outage'){outage=!outage;return send(200,{outage})}
  if(req.method==='POST' && path==='/mock/expire'){sessions.clear();return send(200,{success:true})}
  if(path==='/api/driver/reservations')return outage?send(503,{error:{message:'테스트용 연결 장애'}}):send(200,snapshot());
  send(404,{error:{message:'not found'}});
 });
}
if(process.argv[1] && import.meta.url===pathToFileURL(process.argv[1]).href){
 const server=createMockServer();const port=Number(process.env.PORT||3000);
 server.listen(port,'127.0.0.1',()=>console.log(`HTTP 시연 서버 http://127.0.0.1:${port} · driver / demo1234 · 실제 데이터 아님`));
}
