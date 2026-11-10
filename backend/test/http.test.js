import test from 'node:test';
import assert from 'node:assert/strict';
import {createMockServer} from '../mock-server.js';
test('HTTP 쿠키 인증 → 예약 취소 → 장애 → 만료 → PASSENGER 차단',async()=>{
 const server=createMockServer();await new Promise(r=>server.listen(0,'127.0.0.1',r));
 try{
  const base=`http://127.0.0.1:${server.address().port}`;
  assert.equal((await fetch(base+'/api/driver/reservations')).status,401);
  let login=await fetch(base+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({loginId:'driver',password:'demo1234'})});
  const cookie=login.headers.get('set-cookie').split(';')[0];const headers={cookie};
  assert.equal((await (await fetch(base+'/api/auth/me',{headers})).json()).user.role,'DRIVER');
  assert.equal((await (await fetch(base+'/api/driver/reservations',{headers})).json()).reservations[1].status,'RESERVED');
  await fetch(base+'/mock/cancel',{method:'POST',headers});assert.equal((await (await fetch(base+'/api/driver/reservations',{headers})).json()).reservations[1].status,'CANCELLED');
  await fetch(base+'/mock/outage',{method:'POST',headers});assert.equal((await fetch(base+'/api/driver/reservations',{headers})).status,503);
  await fetch(base+'/mock/expire',{method:'POST',headers});assert.equal((await fetch(base+'/api/auth/me',{headers})).status,401);
  login=await fetch(base+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({loginId:'passenger',password:'demo1234'})});
  assert.equal((await fetch(base+'/api/driver/reservations',{headers:{cookie:login.headers.get('set-cookie').split(';')[0]}})).status,403);
 }finally{server.closeAllConnections();await new Promise(r=>server.close(r))}
});
