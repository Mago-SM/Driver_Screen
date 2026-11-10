package kr.ac.pbl.busdriver

/** 실제 노선 검증 자료가 아닌 원본 화면 기반의 로컬 예시입니다. */
object Demo {
    fun snapshot(second: Boolean = true, direction: String = "상행"): Snapshot {
        require(direction in listOf("상행", "하행"))
        val s = Snapshot("local-example", "1", "example-150", "150번", direction,
        listOf("진주시청", "중앙시장", "진주교 정류장", "천전시장", "경상국립대").mapIndexed { i, n -> Stop(i, n) },
        listOf(Reservation("A", "RESERVED", listOf(Segment(0, 1, 2))),
            Reservation("B", if (second) "RESERVED" else "CANCELLED", listOf(Segment(0, 1, 4))),
            Reservation("C", "RESERVED", listOf(Segment(0, 0, 1)))), 0)
        if (direction == "상행") return s
        val last = s.stops.lastIndex
        return s.copy(stops = s.stops.reversed().mapIndexed { i, stop -> Stop(i, stop.name) },
            reservations = s.reservations.map { r -> r.copy(segments = r.segments.map { seg ->
                seg.copy(boarding = last - seg.alighting, alighting = last - seg.boarding)
            }) })
    }
}
