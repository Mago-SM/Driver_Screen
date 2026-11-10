// Same public API/identifiers as bususer/BusAPI.kt. Keys remain server-side.
export function createJinjuProvider({env=process.env,fetcher=fetch,now=Date.now}={}) {
  const cache=new Map();
  async function request(endpoint,params={},ttl=86400000) {
    if(!env.JINJU_BUS_API_KEY)throw Error('서버에 JINJU_BUS_API_KEY를 설정하세요.');
    const key=endpoint+JSON.stringify(params); const saved=cache.get(key);
    if(saved && saved.until>now())return saved.value;
    const url=new URL('https://apis.data.go.kr/5310000/jinjubus/'+endpoint);
    let apiKey=env.JINJU_BUS_API_KEY;try{apiKey=decodeURIComponent(apiKey)}catch{}
    url.searchParams.set('serviceKey',apiKey);
    for(const [k,v] of Object.entries(params))url.searchParams.set(k,v);
    let response;
    try { response=await fetcher(url,{signal:AbortSignal.timeout(10000)}); }
    catch {throw Error('진주시 버스 API 연결 실패');}
    if(!response.ok)throw Error(`진주시 버스 API HTTP ${response.status}`);
    let value;try{value=await response.json()}catch{throw Error('진주시 API 인증 또는 응답 형식 오류');}
    if(String(value.resultCode)!=='200')throw Error('진주시 버스 API 응답 오류');
    if(cache.size>=128)cache.delete(cache.keys().next().value);
    cache.set(key,{until:now()+ttl,value});return value;
  }
  function params(id){const p=id.split(':');if(p.length!==3||p[0]!=='jinju'||!p[1]||!p[2])throw Error('노선 ID 오류');return {routeNo:p[1],suptNo:p[2]};}
  function directionCode(label){
    const up=env.JINJU_UP_DIRECTION,down=env.JINJU_DOWN_DIRECTION;
    if(!up||!down||up===down)throw Error('공식 drct 값을 확인해 JINJU_UP_DIRECTION / JINJU_DOWN_DIRECTION을 설정하세요.');
    return label==='상행'?up:down;
  }
  return {
    async routes(number){const j=await request('getRouteList');return (j.routeAll??[]).filter(r=>String(r.routeNo)===number && String(r.suptNo??'')!=='').map(r=>({routeId:`jinju:${r.routeNo}:${r.suptNo}`,routeNumber:String(r.routeNo),label:`${r.routeNo}번 · ${r.stptNm??''} → ${r.edpntNm??''} (지원 ${r.suptNo})`}));},
    async stops(id,direction){
      const code=directionCode(direction);const j=await request('getRouteBsta',params(id));
      const all=[...(j.routeBsta?.bsta??[])].sort((a,b)=>Number(a.drct)-Number(b.drct)||Number(a.sqno)-Number(b.sqno));
      return all.map((s,i)=>({id:String(s.bstaSrvcId??''),name:String(s.bstaNm??''),direction:String(s.drct),sourceOrder:i+1,apiOrder:Number(s.sqno)}))
        .filter(s=>s.direction===code).map((s,sequence)=>({...s,sequence}));
    },
    async vehicles(id){const j=await request('getBusLoc',params(id),5000);return [...new Map((j.busLoc?.bus??[]).filter(b=>b.vhno).map(b=>[String(b.vhno),{vehicleId:String(b.vhno),label:`${b.vhno} · ${b.drctNm??''}`,directionName:String(b.drctNm??''),updatedAt:String(b.altrDtm??'')}])).values()];}
  };
}
