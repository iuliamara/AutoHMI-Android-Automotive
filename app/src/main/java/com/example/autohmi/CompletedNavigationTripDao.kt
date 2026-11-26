package com.example.autohmi

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CompletedNavigationTripDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCompletedTrip(trip: CompletedNavigationTrip): Long

    @Query("SELECT * FROM completed_navigation_trips ORDER BY completedAtMillis DESC, completionId DESC")
    fun observeCompletedTrips(): Flow<List<CompletedNavigationTrip>>
}
