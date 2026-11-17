package kr.ac.pbl.busdriver
import org.junit.Assert.*
import org.junit.Test
class GpsProgressTest {
    private val now = 100000L
    private val stops = listOf(Stop(0,"A",35.0,128.0),Stop(1,"B",35.001,128.0),Stop(2,"C",35.002,128.0))
    private fun at(m: Double, passed: Int = 0, time: Long = now) = VehiclePosition(35.001-m/111194.9266,128.0,time,passed)
    @Test fun approachArrivalAndNoRepeat() {
        val g = GpsProgress()
        g.update(stops,at(50.0),now); assertNull(g.alertSequence)
        g.update(stops,at(19.0,time=now+5000),now+5000); assertEquals(1,g.alertSequence)
        g.update(stops,at(23.0,time=now+10000),now+10000); assertEquals(1,g.alertSequence)
        g.update(stops,at(4.0,time=now+15000),now+15000); assertEquals(1,g.current); assertNull(g.alertSequence)
        g.update(stops,at(15.0,time=now+20000),now+20000); assertEquals(1,g.current); assertNull(g.alertSequence)
    }
    @Test fun serverPassClearsAlertWhenArrivalSampleSkipped() {
        val g = GpsProgress(); g.update(stops,at(15.0),now)
        g.update(stops,at(-30.0,passed=1,time=now+5000),now+5000)
        assertEquals(1,g.current); assertNull(g.alertSequence)
    }
    @Test fun staleMissingAndOutOfOrderCannotMoveBus() {
        val g = GpsProgress(); g.update(stops,at(15.0),now)
        g.update(stops,at(15.0),now+30001); assertFalse(g.live); assertNull(g.alertSequence)
        g.update(stops,null,now); assertFalse(g.live)
        g.update(stops,at(4.0,time=now-1),now); assertEquals(0,g.current); assertNull(g.alertSequence)
    }
    @Test fun midRouteResetAndTerminus() {
        val g = GpsProgress(); g.update(stops,at(50.0,passed=1),now); assertEquals(1,g.current)
        g.reset(); g.update(stops,at(15.0),now); assertEquals(1,g.alertSequence)
        g.update(stops,at(50.0,passed=2,time=now+5000),now+5000)
        assertEquals(2,g.current); assertNull(g.alertSequence)
    }
    @Test fun missingCoordinatesDoNotBecomeZeroDistance() {
        val g=GpsProgress(); g.update(listOf(Stop(0,"A"),Stop(1,"B")),at(15.0),now)
        assertNull(g.alertSequence); assertNull(g.distanceMeters)
    }
    @Test fun countsFollowDestinationThroughArrivalAndSwitchOnlyAfterDeparture() {
        val g = GpsProgress()
        val counts = Counts(listOf(9, 3, 7), listOf(8, 2, 6))
        g.update(stops,at(50.0),now)
        assertEquals(1,g.serviceSequence)
        g.update(stops,at(19.0,time=now+5000),now+5000)
        assertEquals(g.alertSequence,g.serviceSequence)
        assertEquals(3,counts.boarding[g.serviceSequence!!])
        assertEquals(2,counts.alighting[g.serviceSequence!!])
        g.update(stops,at(3.0,time=now+10000),now+10000)
        assertNull(g.alertSequence); assertEquals(1,g.serviceSequence)
        // 서버의 통과 정류장이 갱신돼도 아직 정차 반경 안이면 같은 인원을 유지.
        g.update(stops,at(-8.0,passed=1,time=now+15000),now+15000)
        assertEquals(1,g.serviceSequence)
        g.update(stops,at(-24.0,passed=1,time=now+20000),now+20000)
        assertEquals(1,g.serviceSequence)
        g.update(stops,at(-30.0,passed=1,time=now+25000),now+25000)
        assertEquals(2,g.serviceSequence); assertEquals(7,counts.boarding[g.serviceSequence!!])
        // 출발 뒤 좌표가 정류장 쪽으로 흔들려도 이전 인원으로 돌아가지 않음.
        g.update(stops,at(-15.0,passed=1,time=now+30000),now+30000)
        assertEquals(2,g.serviceSequence)
    }
    @Test fun skippedArrivalAndStalePositionKeepCorrectCountTarget() {
        val g=GpsProgress(); g.update(stops,at(19.0),now)
        g.update(stops,at(-10.0,passed=1,time=now+5000),now+5000)
        assertEquals(1,g.serviceSequence); assertNull(g.alertSequence)
        g.update(stops,null,now+10000); assertEquals(1,g.serviceSequence)
        g.update(stops,at(-30.0,passed=1,time=now+15000),now+15000)
        assertEquals(2,g.serviceSequence)
        g.reset(); assertNull(g.serviceSequence)
    }
    @Test fun firstStopAndTerminusRetainTheirOwnCountsWhileStopped() {
        val g=GpsProgress()
        g.update(stops,VehiclePosition(35.0,128.0,now,0),now)
        assertEquals(0,g.serviceSequence)
        g.update(stops,VehiclePosition(35.0003,128.0,now+5000,0),now+5000)
        assertEquals(1,g.serviceSequence)
        g.update(stops,VehiclePosition(35.002,128.0,now+10000,2),now+10000)
        assertEquals(2,g.serviceSequence); assertNull(g.alertSequence)
        g.update(stops,VehiclePosition(35.0023,128.0,now+15000,2),now+15000)
        assertEquals(2,g.serviceSequence)
    }
}
