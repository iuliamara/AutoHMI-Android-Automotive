package com.example.autohmi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VehicleStatusTest {
    @Test
    fun percentageAndRangeUseCapacityAndModeConsumption() {
        assertEquals(65, VehicleStatus().batteryPercent)
        assertEquals(100, VehicleStatus(batteryEnergyKwh = 60.0).batteryPercent)
        assertEquals(0, VehicleStatus(batteryEnergyKwh = 0.0).batteryPercent)
        val ranges = mapOf(DrivingMode.ECO to 370, DrivingMode.NORMAL to 315, DrivingMode.SPORT to 252)
        ranges.forEach { (mode, range) ->
            assertEquals(range, VehicleStatus(drivingMode = mode).rangeKm)
            assertEquals(0, VehicleStatus(drivingMode = mode, batteryEnergyKwh = 0.0).rangeKm)
        }
    }

    @Test
    fun invalidSpeedIsRejected() {
        listOf(-1, 181, Int.MIN_VALUE, Int.MAX_VALUE).forEach { speed ->
            assertThrows(IllegalArgumentException::class.java) { VehicleStatus(speedKmh = speed) }
        }
    }

    @Test
    fun invalidEnergyIsRejected() {
        listOf(-0.1, 60.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { energy ->
            assertThrows(IllegalArgumentException::class.java) { VehicleStatus(batteryEnergyKwh = energy) }
        }
    }

    @Test
    fun invalidDistanceIsRejectedOnConstructionAndCopy() {
        listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { distance ->
            assertThrows(IllegalArgumentException::class.java) { VehicleStatus(distanceKm = distance) }
            assertThrows(IllegalArgumentException::class.java) { VehicleStatus().copy(distanceKm = distance) }
        }
    }
}
