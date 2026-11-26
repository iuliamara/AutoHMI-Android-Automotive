package com.example.autohmi

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.abs

/** One completed driving-endpoint event. No coordinates or route trace are persisted. */
@Entity(tableName = "completed_navigation_trips", indices = [Index(value = ["completedAtMillis"])])
data class CompletedNavigationTrip(
    @PrimaryKey val completionId: String,
    val completedAtMillis: Long,
    val simulatedDurationSeconds: Double,
    val distanceMeters: Double,
    val averageSpeedKmh: Double,
    val source: String = "DEMO",
    val completionStatus: String = "COMPLETED",
    val destinationLabel: String? = null,
    val destinationGapMeters: Double = 0.0
) {
    init {
        require(completionId.isNotBlank() && completedAtMillis > 0)
        require(simulatedDurationSeconds.isFinite() && simulatedDurationSeconds > 0)
        require(distanceMeters.isFinite() && distanceMeters > 0 && averageSpeedKmh.isFinite() && averageSpeedKmh > 0)
        require(abs(averageSpeedKmh - distanceMeters / simulatedDurationSeconds * 3.6) < 0.01)
        require(source == "DEMO" && completionStatus == "COMPLETED")
        require(destinationGapMeters.isFinite() && destinationGapMeters >= 0)
        require(destinationLabel == null || destinationLabel.length <= 120)
    }
}
