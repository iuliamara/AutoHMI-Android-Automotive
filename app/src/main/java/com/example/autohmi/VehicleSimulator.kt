package com.example.autohmi

class VehicleSimulator {

    fun advance(
        current: VehicleStatus,
        elapsedSeconds: Double
    ): VehicleStatus {

        require(
            elapsedSeconds.isFinite() &&
                    elapsedSeconds >= 0.0
        ) { "Elapsed seconds must be finite and non-negative" }

        if (elapsedSeconds == 0.0) return current

        // Distance that would be traveled at current speed.
        val requestedDistance =
            (elapsedSeconds / 3600.0) * current.speedKmh

        // Maximum distance supported by the remaining energy.
        val consumptionPerKm =
            current.consumptionKwhPerKm

        val availableDistance =
            current.batteryEnergyKwh /
                    consumptionPerKm

        val traveledDistance = minOf(
            requestedDistance,
            availableDistance
        )

        val energyUsed =
            traveledDistance * consumptionPerKm

        // Avoid a tiny positive charge caused by rounding when the battery is exhausted.
        val remainingEnergy = if (requestedDistance >= availableDistance) 0.0 else
            (current.batteryEnergyKwh - energyUsed)
                .coerceAtLeast(0.0)

        return current.copy(
            distanceKm = current.distanceKm + traveledDistance,
            batteryEnergyKwh = remainingEnergy,
            isSimulationRunning = current.isSimulationRunning && remainingEnergy > 0.0,
            speedKmh = if (remainingEnergy <= 0.0) {
                0
            } else {
                current.speedKmh
            }
        )
    }

    fun accelerate(current: VehicleStatus): VehicleStatus =
        if (current.batteryEnergyKwh == 0.0) current.copy(speedKmh = 0)
        else current.copy(speedKmh = (current.speedKmh + SPEED_STEP_KMH).coerceAtMost(MAX_SPEED_KMH))

    fun brake(current: VehicleStatus): VehicleStatus =
        current.copy(speedKmh = (current.speedKmh - SPEED_STEP_KMH).coerceAtLeast(0))

    fun resetTrip(current: VehicleStatus): VehicleStatus {
        return current.copy(distanceKm = 0.0)
    }
}
