// altrDtm without an offset is Korea local time. Unknown timestamps are not fresh GPS.
export function positionTime(value) {
  const s = String(value ?? '').trim();
  const compact = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})$/.exec(s);
  const iso = compact ? `${compact[1]}-${compact[2]}-${compact[3]}T${compact[4]}:${compact[5]}:${compact[6]}+09:00`
    : /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d+)?$/.test(s) ? s.replace(' ', 'T')+'+09:00' : s;
  return Date.parse(iso);
}
export function vehiclePosition(vehicle, stops, now) {
  if (!vehicle) return {status:'UNAVAILABLE'};
  const {latitude, longitude} = vehicle;
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude)>90 || Math.abs(longitude)>180 || (latitude===0 && longitude===0)) return {status:'UNAVAILABLE'};
  const observedAt = positionTime(vehicle.updatedAt);
  if (!Number.isFinite(observedAt) || now-observedAt>30000 || observedAt-now>5000) return {status:'STALE'};
  if (vehicle.direction && stops.some(s=>s.direction && s.direction!==vehicle.direction)) return {status:'WRONG_DIRECTION'};
  const matches = stops.filter(s=>s.id===vehicle.passedStopId);
  // Never guess among repeated visits to the same stop.
  if (matches.length!==1) return {status:'UNMATCHED'};
  return {status:'LIVE',latitude,longitude,observedAt,passedSequence:matches[0].sequence};
}
