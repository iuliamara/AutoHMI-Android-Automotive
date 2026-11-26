package com.example.autohmi

import kotlin.math.roundToInt

const val BATTERY_CAPACITY_KWH = 60.0
const val CONSUMPTION_KWH_PER_KM = 0.124
const val MAX_SPEED_KMH = 180
const val SPEED_STEP_KMH = 10
const val SIMULATED_SECONDS_PER_TICK = 60.0
const val TICK_INTERVAL_MILLIS = 1_000L

data class VehicleStatus(
    val speedKmh: Int = 0,
    val batteryEnergyKwh: Double = 39.0,
    val distanceKm: Double = 0.0,
    val cabinTemperatureC: Int = 21,
    val outsideTemperatureC: Int = 14,
    val drivingMode: DrivingMode = DrivingMode.NORMAL,
    val isSimulationRunning: Boolean = false
) {
    init {
        require(speedKmh in 0..MAX_SPEED_KMH) { "Speed must be between 0 and 180 km/h" }
        require(batteryEnergyKwh.isFinite() && batteryEnergyKwh in 0.0..BATTERY_CAPACITY_KWH) {
            "Battery energy must be finite and within capacity"
        }
        require(distanceKm.isFinite() && distanceKm >= 0.0) {
            "Trip distance must be finite and non-negative"
        }
    }

    val consumptionKwhPerKm: Double
        get() = CONSUMPTION_KWH_PER_KM * drivingMode.consumptionMultiplier

    val batteryPercent: Int
        get() = (
                batteryEnergyKwh / BATTERY_CAPACITY_KWH * 100
                ).roundToInt().coerceIn(0, 100)

    val rangeKm: Int
        get() = (
                batteryEnergyKwh /
                        consumptionKwhPerKm
                ).roundToInt().coerceAtLeast(0)
}
