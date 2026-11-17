package kr.ac.pbl.busdriver

import kotlin.math.*

data class VehiclePosition(val latitude: Double, val longitude: Double, val observedAt: Long, val passedSequence: Int)

/** All progress is tied to the selected route/vehicle snapshot. No dead reckoning. */
class GpsProgress {
    var current = 0; private set
    var alertSequence: Int? = null; private set
    var distanceMeters: Double? = null; private set
    var live = false; private set
    private var lastObserved = 0L
    fun reset() { current = 0; alertSequence = null; distanceMeters = null; live = false; lastObserved = 0L }
    fun unavailable() { live = false; alertSequence = null; distanceMeters = null }
    fun update(stops: List<Stop>, position: VehiclePosition?, now: Long) {
        if (position == null || now - position.observedAt !in -5000L..30000L ||
            position.observedAt < lastObserved || position.passedSequence !in stops.indices ||
            !position.latitude.isFinite() || !position.longitude.isFinite() ||
            position.latitude !in -90.0..90.0 || position.longitude !in -180.0..180.0) {
            unavailable(); return
        }
        live = true
        if (lastObserved == 0L) current = position.passedSequence
        lastObserved = position.observedAt
        current = maxOf(current, position.passedSequence)
        if (alertSequence?.let { it <= current } == true) alertSequence = null
        val next = stops.getOrNull(current + 1)
        val lat = next?.latitude; val lon = next?.longitude
        if (lat == null || lon == null) { distanceMeters = null; alertSequence = null; return }
        val distance = distance(position.latitude, position.longitude, lat, lon)
        distanceMeters = distance
        when {
            distance <= 5.0 -> { current++; alertSequence = null; distanceMeters = null }
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
