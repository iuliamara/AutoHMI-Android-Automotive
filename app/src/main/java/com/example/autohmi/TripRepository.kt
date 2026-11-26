package com.example.autohmi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class TripRepository(
    private val tripDao: TripDao,
    private val completedTripDao: CompletedNavigationTripDao? = null,
    private val clock: () -> Long = System::currentTimeMillis
) : CompletedTripStore {

    val recentSnapshots: Flow<List<TripSnapshot>> =
        tripDao.observeRecentSnapshots()

    val snapshots: Flow<List<TripSnapshot>> = tripDao.observeSnapshots()
    val completedNavigationTrips: Flow<List<CompletedNavigationTrip>> = completedTripDao?.observeCompletedTrips() ?: flowOf(emptyList())

    override suspend fun saveCompletedTrip(trip: CompletedNavigationTrip) {
        checkNotNull(completedTripDao) { "Completed trip storage is not configured" }.insertCompletedTrip(trip)
    }

    suspend fun saveSnapshot(vehicle: VehicleStatus): Long {
        return tripDao.insert(TripSnapshot.fromVehicle(vehicle, clock()))
    }

    suspend fun deleteSnapshot(id: Long) {
        require(id > 0) { "Snapshot ID must be positive" }
        tripDao.deleteSnapshot(id)
    }
}
