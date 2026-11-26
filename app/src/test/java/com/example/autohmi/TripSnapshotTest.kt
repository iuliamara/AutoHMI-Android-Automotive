package com.example.autohmi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TripSnapshotTest {
    @Test
    fun snapshotCapturesTheCurrentTripWithoutChangingIt() {
        val vehicle = VehicleStatus(distanceKm = 12.5, batteryEnergyKwh = 30.0, drivingMode = DrivingMode.ECO)
        val snapshot = TripSnapshot.fromVehicle(vehicle, 1_000L)
        assertEquals(0L, snapshot.id)
        assertEquals(1_000L, snapshot.savedAtMillis)
        assertEquals(12.5, snapshot.distanceKm, 0.0)
        assertEquals(30.0, snapshot.remainingEnergyKwh, 0.0)
        assertEquals("ECO", snapshot.drivingMode)
        assertEquals(12.5, vehicle.distanceKm, 0.0)
    }

    @Test
    fun emptyTripsAndInvalidTimestampsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { TripSnapshot.fromVehicle(VehicleStatus(), 1_000L) }
        listOf(0L, -1L).forEach { timestamp ->
            assertThrows(IllegalArgumentException::class.java) {
                TripSnapshot.fromVehicle(VehicleStatus(distanceKm = 1.0), timestamp)
            }
        }
    }

    @Test
    fun depletedTripsCanStillBeSaved() {
        val snapshot = TripSnapshot.fromVehicle(VehicleStatus(distanceKm = 10.0, batteryEnergyKwh = 0.0), 1_000L)
        assertEquals(0.0, snapshot.remainingEnergyKwh, 0.0)
    }
}
