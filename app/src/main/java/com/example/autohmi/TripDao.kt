package com.example.autohmi

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {

    @Insert
    suspend fun insert(snapshot: TripSnapshot): Long

    @Query(
        "SELECT * FROM trip_snapshots " +
                "ORDER BY savedAtMillis DESC, id DESC"
    )
    fun observeSnapshots(): Flow<List<TripSnapshot>>

    @Query("SELECT COUNT(*) FROM trip_snapshots")
    suspend fun countSnapshots(): Int

    @Query("DELETE FROM trip_snapshots WHERE id = :id")
    suspend fun deleteSnapshot(id: Long)

    @Query(
        "SELECT * FROM trip_snapshots " +
                "ORDER BY savedAtMillis DESC, id DESC " +
                "LIMIT 5"
    )
    fun observeRecentSnapshots(): Flow<List<TripSnapshot>>
}
