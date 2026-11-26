package com.example.autohmi

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trip_snapshots")
data class TripSnapshot(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val savedAtMillis: Long,
    val distanceKm: Double,
    val remainingEnergyKwh: Double,
    val drivingMode: String
) {
    companion object {
        fun fromVehicle(vehicle: VehicleStatus, savedAtMillis: Long): TripSnapshot {
            require(vehicle.distanceKm > 0.0) { "Drive before saving a snapshot" }
            require(savedAtMillis > 0) { "Snapshot timestamp must be positive" }
            return TripSnapshot(
                savedAtMillis = savedAtMillis,
                distanceKm = vehicle.distanceKm,
                remainingEnergyKwh = vehicle.batteryEnergyKwh,
                drivingMode = vehicle.drivingMode.name
            )
        }
    }
}
