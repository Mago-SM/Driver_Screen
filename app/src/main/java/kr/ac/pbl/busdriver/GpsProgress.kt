package kr.ac.pbl.busdriver

import kotlin.math.*

data class VehiclePosition(val latitude: Double, val longitude: Double, val observedAt: Long, val passedSequence: Int)

/** All progress is tied to the selected route/vehicle snapshot. No dead reckoning. */
class GpsProgress {
    var current = 0; private set
    var alertSequence: Int? = null; private set
    var distanceMeters: Double? = null; private set
    var live = false; private set
    // 승하차 인원은 주행 중에는 다음 정류장, 도착 후에는 출발 전까지 도착 정류장 기준.
    var serviceSequence: Int? = null; private set
    private var dwelling = false
    private var lastObserved = 0L
    fun reset() { current = 0; alertSequence = null; distanceMeters = null; live = false; lastObserved = 0L; serviceSequence = null; dwelling = false }
    fun unavailable() { live = false; alertSequence = null; distanceMeters = null }
    fun update(stops: List<Stop>, position: VehiclePosition?, now: Long) {
        if (position == null || now - position.observedAt !in -5000L..30000L ||
            position.observedAt < lastObserved || position.passedSequence !in stops.indices ||
            !position.latitude.isFinite() || !position.longitude.isFinite() ||
            position.latitude !in -90.0..90.0 || position.longitude !in -180.0..180.0) {
            unavailable(); return
        }
        live = true
        val initial = lastObserved == 0L
        val advanced = position.passedSequence > current
        if (initial) current = position.passedSequence
        current = maxOf(current, position.passedSequence)
        lastObserved = position.observedAt
        val atCurrent = stops[current].let { stop ->
            if (stop.latitude == null || stop.longitude == null) null
            else distance(position.latitude, position.longitude, stop.latitude, stop.longitude)
        }
        if (initial || advanced) dwelling = atCurrent == null || atCurrent <= 25.0
        // 도착 반경(5m)보다 큰 출발 반경으로 정차 중 GPS 흔들림에 따른 조기 전환 방지.
        if (dwelling && atCurrent != null && atCurrent > 25.0) dwelling = false
        serviceSequence = if (dwelling) current else (current + 1).coerceAtMost(stops.lastIndex)
        if (alertSequence?.let { it <= current } == true) alertSequence = null
        if (dwelling) { alertSequence = null; distanceMeters = null; return }
        val next = stops.getOrNull(current + 1)
        val lat = next?.latitude; val lon = next?.longitude
        if (lat == null || lon == null) { distanceMeters = null; alertSequence = null; return }
        val distance = distance(position.latitude, position.longitude, lat, lon)
        distanceMeters = distance
        when {
            distance <= 5.0 -> {
                current++; dwelling = true; serviceSequence = current
                alertSequence = null; distanceMeters = null
            }
            distance <= 20.0 -> alertSequence = current + 1
            // Keep the alert through small GPS jitter; clear if the vehicle moves away.
            distance > 40.0 -> alertSequence = null
        }
    }
    companion object {
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val a = sin(Math.toRadians(lat2-lat1)/2).pow(2) +
                cos(Math.toRadians(lat1))*cos(Math.toRadians(lat2))*sin(Math.toRadians(lon2-lon1)/2).pow(2)
            return 6371000 * 2 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1-a).coerceIn(0.0, 1.0)))
        }
    }
}
