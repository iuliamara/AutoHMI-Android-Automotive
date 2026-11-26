package com.example.autohmi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException

interface CompletedTripStore {
    suspend fun saveCompletedTrip(trip: CompletedNavigationTrip)
}

/** Application-owned writes survive leaving Navigate or clearing its ViewModel. */
class CompletedTripRecorder(private val store: CompletedTripStore, private val scope: CoroutineScope) {
    fun record(trip: CompletedNavigationTrip): Deferred<Result<Unit>> = scope.async {
        try { store.saveCompletedTrip(trip); Result.success(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Result.failure(error) }
    }
}
