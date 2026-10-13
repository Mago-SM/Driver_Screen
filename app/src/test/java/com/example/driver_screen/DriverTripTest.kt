package com.example.driver_screen

import org.junit.Assert.*
import org.junit.Test

class DriverTripTest {
    @Test fun acknowledgmentPreservesReservations() {
        val trip = DriverTrip().acknowledge()
        assertTrue(trip.acknowledged)
        assertEquals(2, trip.next!!.boarding)
        assertEquals(1, trip.next!!.alighting)
    }
    @Test fun emptyStopDoesNotNeedNotice() {
        val trip = DriverTrip().acknowledge().advance()
        assertFalse(trip.needsStop)
        assertFalse(trip.acknowledged)
        assertFalse(trip.acknowledge().acknowledged)
    }
    @Test fun alightingAloneNeedsStop() {
        val trip = DriverTrip().advance().advance()
        assertTrue(trip.needsStop)
        assertEquals(0, trip.next!!.boarding)
        assertEquals(2, trip.next!!.alighting)
    }
    @Test fun terminalCannotAdvance() {
        val trip = DriverTrip(DriverTrip.stops.lastIndex)
        assertNull(trip.next)
        assertFalse(trip.needsStop)
        assertEquals(trip, trip.advance())
    }
}
