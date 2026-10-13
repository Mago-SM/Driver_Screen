package com.example.driver_screen

data class DriverStop(val name: String, val boarding: Int = 0, val alighting: Int = 0)

/** Preview state only; replace sample stops with authenticated server data later. */
data class DriverTrip(val currentIndex: Int = 1, val acknowledged: Boolean = false) {
    val current get() = stops[currentIndex.coerceIn(stops.indices)]
    val next get() = stops.getOrNull(currentIndex.coerceIn(stops.indices) + 1)
    val needsStop get() = next?.let { it.boarding > 0 || it.alighting > 0 } ?: false
    fun acknowledge() = if (needsStop) copy(acknowledged = true) else this
    fun advance() = if (next != null) copy(currentIndex = currentIndex.coerceIn(stops.indices) + 1, acknowledged = false) else this
    companion object {
        val stops = listOf(DriverStop("진주시청"), DriverStop("중앙시장"),
            DriverStop("진주교 정류장", 2, 1), DriverStop("천전시장"), DriverStop("경상국립대", 0, 2))
    }
}
