const number = (value, fallback = 0) => Number.isFinite(Number(value)) && value !== null && value !== '' ? Number(value) : fallback;
const coordinate = (point) => {
  const lat = Number(point?.lat), lng = Number(point?.lon);
  return Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180 ? {lat, lng} : null;
};
export function linePoints(line) {
  if (typeof line !== 'string') return [];
  return line.trim().split(/\s+/).map(pair => {
    const [lng, lat] = pair.split(',').map(Number);
    return Number.isFinite(lat) && Number.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180 ? {lat, lng} : null;
  }).filter(Boolean);
}
export function parsePlaces(data) {
  const raw = data?.searchPoiInfo?.pois?.poi ?? [];
  return (Array.isArray(raw) ? raw : [raw]).map(p => {
    const lat = Number(p.frontLat || p.noorLat), lng = Number(p.frontLon || p.noorLon);
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
    return {id: String(p.id ?? `${p.name}-${lat}-${lng}`), name: String(p.name ?? '이름 없는 장소'), address: [p.upperAddrName, p.middleAddrName, p.lowerAddrName, p.detailAddrName, p.roadName, p.buildingNo1].filter(Boolean).join(' '), lat, lng};
  }).filter(Boolean);
}
export function parseRoutes(data) {
  const itineraries = data?.metaData?.plan?.itineraries;
  if (!Array.isArray(itineraries)) return [];
  const routes = itineraries.map((itinerary, index) => {
    const segments = (itinerary.legs ?? []).map(leg => {
      const type = String(leg.mode ?? 'OTHER');
      const stations = leg.passStopList?.stations;
      const stops = (Array.isArray(stations) ? stations : []).map(s => ({name: String(s.stationName ?? ''), point: coordinate(s)}));
      const lanes = Array.isArray(leg.lane) ? leg.lane : [];
      const shape = type === 'WALK' ? (leg.steps ?? []).map(s => linePoints(s.linestring)).filter(a => a.length) : [linePoints(leg.passShape?.linestring)].filter(a => a.length);
      return {type, time: number(leg.sectionTime), distance: number(leg.distance), from: String(leg.start?.name ?? ''), to: String(leg.end?.name ?? ''), start: coordinate(leg.start), end: coordinate(leg.end), busNumber: type === 'BUS' ? String(leg.route || lanes[0]?.route || '') : null, routeId: type === 'BUS' ? String(leg.routeId || lanes[0]?.routeId || '') : null, alternatives: type === 'BUS' ? lanes.map(l => String(l.route)).filter(Boolean) : [], stops, stopCount: Math.max(0, stops.length - 1), steps: type === 'WALK' ? (leg.steps ?? []).map(s => String(s.description ?? '')).filter(Boolean) : [], paths: shape};
    });
    if (!segments.some(s => s.type === 'BUS')) return null;
    return {id: index, recommended: false, totalTime: number(itinerary.totalTime), totalDistance: number(itinerary.totalDistance), totalWalkTime: number(itinerary.totalWalkTime, segments.filter(s => s.type === 'WALK').reduce((n,s) => n+s.time,0)), totalWalkDistance: number(itinerary.totalWalkDistance, segments.filter(s => s.type === 'WALK').reduce((n,s) => n+s.distance,0)), transferCount: number(itinerary.transferCount), totalFare: number(itinerary.fare?.regular?.totalFare), segments};
  }).filter(Boolean);
  // Give travel time priority, then transfers and walking. Each transfer adds 5 min;
  // walking adds a small preference so an excessive walk does not win on time alone.
  const score = r => r.totalTime + r.transferCount * 300 + r.totalWalkTime * .2 + r.totalWalkDistance * .1;
  routes.sort((a,b) => score(a)-score(b) || a.totalFare-b.totalFare || a.id-b.id);
  if (routes[0]) routes[0].recommended = true;
  return routes;
}

