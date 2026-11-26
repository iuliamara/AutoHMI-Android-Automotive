
package com.example.autohmi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class VehicleSimulatorTest {

    private val simulator = VehicleSimulator()

    @Test
    fun accelerationAndBrakingRespectSpeedBoundaries() {
        assertEquals(10, simulator.accelerate(VehicleStatus()).speedKmh)
        assertEquals(180, simulator.accelerate(VehicleStatus(speedKmh = 175)).speedKmh)
        assertEquals(180, simulator.accelerate(VehicleStatus(speedKmh = 180)).speedKmh)
        assertEquals(60, simulator.brake(VehicleStatus(speedKmh = 70)).speedKmh)
        assertEquals(0, simulator.brake(VehicleStatus(speedKmh = 5)).speedKmh)
        assertEquals(0, simulator.brake(VehicleStatus()).speedKmh)
    }

    @Test
    fun emptyBatteryPreventsAcceleration() {
        assertEquals(0, simulator.accelerate(VehicleStatus(batteryEnergyKwh = 0.0)).speedKmh)
    }

    @Test
    fun zeroElapsedTimeDoesNotChangeState() {
        val initial = VehicleStatus(speedKmh = 80, distanceKm = 5.0, isSimulationRunning = true)
        assertEquals(initial, simulator.advance(initial, 0.0))
    }

    @Test
    fun nonFiniteElapsedTimeIsRejected() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { elapsed ->
            assertThrows(IllegalArgumentException::class.java) {
                simulator.advance(VehicleStatus(), elapsed)
            }
        }
    }

    @Test
    fun extremeFiniteElapsedTimeStillStopsAtAvailableRange() {
        val result = simulator.advance(VehicleStatus(speedKmh = 180), Double.MAX_VALUE)
        assertEquals(39.0 / 0.124, result.distanceKm, 0.000001)
        assertEquals(0.0, result.batteryEnergyKwh, 0.0)
        assertFalse(result.isSimulationRunning)
    }

    @Test
    fun depletionCapsDistanceAndStopsTheSimulationForEveryMode() {
        DrivingMode.entries.forEach { mode ->
            val initial = VehicleStatus(
                speedKmh = 180,
                batteryEnergyKwh = 0.01,
                distanceKm = 2.0,
                drivingMode = mode,
                isSimulationRunning = true
            )
            val result = simulator.advance(initial, 60.0)
            assertEquals(2.0 + 0.01 / initial.consumptionKwhPerKm, result.distanceKm, 0.000001)
            assertEquals(0.0, result.batteryEnergyKwh, 0.0)
            assertEquals(0, result.speedKmh)
            assertFalse(result.isSimulationRunning)
        }
    }

    @Test
    fun fractionalTimeAndRepeatedStepsUseTheSameFormula() {
        val initial = VehicleStatus(speedKmh = 90, drivingMode = DrivingMode.SPORT)
        val single = simulator.advance(initial, 30.0)
        val repeated = (1..60).fold(initial) { state, _ -> simulator.advance(state, 0.5) }
        assertEquals(0.75, single.distanceKm, 0.000001)
        assertEquals(single.distanceKm, repeated.distanceKm, 0.000001)
        assertEquals(single.batteryEnergyKwh, repeated.batteryEnergyKwh, 0.000001)
    }

    @Test
    fun drivingForOneMinuteConsumesEnergy() {
        val initial = VehicleStatus(speedKmh = 60)

        val result = simulator.advance(
            current = initial,
            elapsedSeconds = 60.0
        )

        assertEquals(1.0, result.distanceKm, 0.000001)
        assertEquals(
            38.876,
            result.batteryEnergyKwh,
            0.000001
        )
        assertEquals(314, result.rangeKm)
    }

    @Test
    fun stationaryVehicleConsumesNoDrivingEnergy() {
        val initial = VehicleStatus(speedKmh = 0)

        val result = simulator.advance(initial, 60.0)

        assertEquals(0.0, result.distanceKm, 0.000001)
        assertEquals(
            39.0,
            result.batteryEnergyKwh,
            0.000001
        )
    }

    @Test
    fun vehicleStopsWhenBatteryIsEmpty() {
        val initial = VehicleStatus(
            speedKmh = 60,
            batteryEnergyKwh = 0.0
        )

        val result = simulator.advance(initial, 60.0)

        assertEquals(0, result.speedKmh)
        assertEquals(0.0, result.distanceKm, 0.000001)
    }


    @Test
    fun ecoModeConsumesLessEnergyThanSportMode() {
        val ecoVehicle = VehicleStatus(
            speedKmh = 60,
            drivingMode = DrivingMode.ECO
        )

        val sportVehicle = VehicleStatus(
            speedKmh = 60,
            drivingMode = DrivingMode.SPORT
        )

        val ecoResult = simulator.advance(ecoVehicle, 60.0)
        val sportResult = simulator.advance(sportVehicle, 60.0)

        assertEquals(38.8946, ecoResult.batteryEnergyKwh, 0.000001)
        assertEquals(38.845, sportResult.batteryEnergyKwh, 0.000001)
    }


    @Test
    fun changingModeUpdatesRangeButNotBatteryEnergy() {
        val normalVehicle = VehicleStatus(
            speedKmh = 80,
            batteryEnergyKwh = 39.0,
            drivingMode = DrivingMode.NORMAL
        )

        val ecoVehicle = normalVehicle.copy(
            drivingMode = DrivingMode.ECO
        )

        assertEquals(
            39.0,
            ecoVehicle.batteryEnergyKwh,
            0.000001
        )

        assertEquals(80, ecoVehicle.speedKmh)

        assertTrue(ecoVehicle.rangeKm > normalVehicle.rangeKm)
    }


    @Test
    fun batteryNeverBecomesNegative() {
        val vehicle = VehicleStatus(
            speedKmh = 100,
            batteryEnergyKwh = 0.01
        )

        val result = simulator.advance(
            current = vehicle,
            elapsedSeconds = 3600.0
        )

        assertEquals(0.0, result.batteryEnergyKwh, 0.000001)
        assertEquals(0, result.speedKmh)
        assertTrue(result.distanceKm > 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeElapsedTimeIsRejected() {
        simulator.advance(
            current = VehicleStatus(),
            elapsedSeconds = -10.0
        )
    }


    @Test
    fun resettingTripPreservesVehicleEnergy() {
        val initial = VehicleStatus(
            speedKmh = 60,
            distanceKm = 25.0,
            batteryEnergyKwh = 35.0,
            drivingMode = DrivingMode.ECO
        )

        val result = simulator.resetTrip(initial)

        assertEquals(0.0, result.distanceKm, 0.000001)
        assertEquals(35.0, result.batteryEnergyKwh, 0.000001)
        assertEquals(60, result.speedKmh)
        assertEquals(DrivingMode.ECO, result.drivingMode)
    }

}
