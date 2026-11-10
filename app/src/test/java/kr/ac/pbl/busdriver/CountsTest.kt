package kr.ac.pbl.busdriver
import org.junit.Assert.*
import org.junit.Test
class CountsTest {
    @Test fun repeatAndCancel() {
        val s = Demo.snapshot()
        assertEquals(2, s.counts().boarding[1]); assertEquals(s.counts(), s.counts())
        assertEquals(1, Demo.snapshot(false).counts().boarding[1]); assertEquals(1, s.counts().alighting[1])
    }
    @Test fun deduplicateAndTransfers() {
        val s = Demo.snapshot().copy(reservations = listOf(Reservation("A", "RESERVED", listOf(Segment(1,0,1), Segment(1,0,1), Segment(3,2,4)))))
        assertEquals(listOf(1,0,1,0,0), s.counts().boarding)
    }
    @Test fun emptyAndUnknownStatus() {
        assertEquals(List(5){0}, Demo.snapshot().copy(reservations = emptyList()).counts().boarding)
        assertEquals(List(5){0}, Demo.snapshot().copy(reservations = listOf(Reservation("A","UNKNOWN",listOf(Segment(0,0,1))))).counts().boarding)
    }
    @Test(expected = IllegalArgumentException::class) fun reversedSegmentRejected() {
        Demo.snapshot().copy(reservations = listOf(Reservation("A","RESERVED",listOf(Segment(0,3,1))))).counts()
    }
    @Test(expected = IllegalArgumentException::class) fun conflictingDuplicatesRejected() {
        Demo.snapshot().copy(reservations = listOf(Reservation("A","RESERVED",listOf(Segment(0,0,1),Segment(0,0,2))))).counts()
    }
    @Test fun moreThan50() {
        val s = Demo.snapshot().copy(reservations = (1..71).map { Reservation("$it","RESERVED",listOf(Segment(0,0,4))) })
        assertEquals(71,s.counts().boarding[0])
    }
    @Test fun oppositeDirectionHasSeparateStopsAndState() {
        val up = Demo.snapshot()
        val down = Demo.snapshot(direction = "하행")
        assertEquals(up.stops.last().name, down.stops.first().name)
        assertNotEquals(up.signature, down.signature)
        assertEquals(up.counts().boarding, down.counts().alighting.reversed())
    }
    @Test fun serverAggregateUsedDirectly() {
        val c = Counts(listOf(5,0,0,0,0), listOf(0,0,0,0,5))
        assertEquals(c, Demo.snapshot().copy(serverCounts = c).counts())
    }
    @Test(expected = IllegalArgumentException::class) fun invalidServerAggregateRejected() {
        Demo.snapshot().copy(serverCounts = Counts(listOf(-1), listOf(0))).counts()
    }
}
