package kr.ac.pbl.busdriver

data class Stop(val sequence: Int, val name: String)
data class Segment(val index: Int, val boarding: Int, val alighting: Int)
data class Reservation(val id: String, val status: String, val segments: List<Segment>)
data class Counts(val boarding: List<Int>, val alighting: List<Int>, val plannedAlighting: List<Int> = emptyList())
data class Snapshot(val simulationId: String, val configVersion: String, val routeId: String,
    val routeLabel: String, val direction: String, val stops: List<Stop>,
    val reservations: List<Reservation>, val excludedCount: Int, val serverCounts: Counts? = null) {
    val signature: String get() = listOf(simulationId, configVersion, routeId, direction,
        stops.joinToString { "${it.sequence}:${it.name}" }).joinToString("|")
    fun counts(): Counts {
        require(stops.size >= 2 && stops.map { it.sequence } == stops.indices.toList()) { "정류장 순서가 올바르지 않습니다." }
        serverCounts?.let {
            require(it.boarding.size == stops.size && it.alighting.size == stops.size &&
                it.boarding.all { n -> n >= 0 } && it.alighting.all { n -> n >= 0 } &&
                (it.plannedAlighting.isEmpty() || (it.plannedAlighting.size == stops.size && it.plannedAlighting.all { n -> n >= 0 }))) { "인원 데이터가 올바르지 않습니다." }
            return it
        }
        val board = MutableList(stops.size) { 0 }; val alight = MutableList(stops.size) { 0 }
        val seen = mutableMapOf<Pair<String, Int>, Segment>()
        reservations.filter { it.status == "RESERVED" }.forEach { r -> r.segments.forEach { s ->
            require(s.index >= 0 && s.boarding in stops.indices && s.alighting in stops.indices && s.boarding < s.alighting) { "승하차 구간이 올바르지 않습니다." }
            val key = r.id to s.index
            val previous = seen.putIfAbsent(key, s)
            require(previous == null || previous == s) { "중복 구간 정보가 서로 다릅니다." }
            if (previous == null) { board[s.boarding]++; alight[s.alighting]++ }
        } }
        return Counts(board, alight)
    }
}

