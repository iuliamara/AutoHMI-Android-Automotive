package com.example.autohmi

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VehicleSettingsRepositoryTest {
    @Test
    fun defaultModeAndEverySavedModeSurviveReopeningDataStore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "settings-test-${UUID.randomUUID()}.preferences_pb")
        var job: Job = SupervisorJob()
        try {
            var store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            var repository = VehicleSettingsRepository(store)
            assertEquals(DrivingMode.NORMAL, repository.drivingMode.first())
            for (mode in DrivingMode.entries) {
                repository.saveDrivingMode(mode)
                assertEquals(mode, repository.drivingMode.first())
                job.cancelAndJoin()
                job = SupervisorJob()
                store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
                repository = VehicleSettingsRepository(store)
                assertEquals(mode, repository.drivingMode.first())
            }
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }

    @Test
    fun unknownStoredValueFallsBackToNormal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "settings-test-${UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            store.edit { it[stringPreferencesKey("driving_mode")] = "REMOVED_MODE" }
            assertEquals(DrivingMode.NORMAL, VehicleSettingsRepository(store).drivingMode.first())
        } finally {
            job.cancelAndJoin()
            file.delete()
        }
    }
}
