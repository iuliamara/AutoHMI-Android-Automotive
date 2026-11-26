
package com.example.autohmi

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Create one DataStore instance per application context.
private val Context.vehicleSettingsDataStore by preferencesDataStore(
    name = "vehicle_settings"
)

class VehicleSettingsRepository(
    private val dataStore: DataStore<Preferences>
) {
    constructor(context: Context) : this(context.applicationContext.vehicleSettingsDataStore)

    private val drivingModeKey =
        stringPreferencesKey("driving_mode")

    // Read the saved driving mode.
    val drivingMode: Flow<DrivingMode> =
        dataStore.data.map { preferences ->

            val savedMode = preferences[drivingModeKey]

            DrivingMode.entries.firstOrNull { mode ->
                mode.name == savedMode
            } ?: DrivingMode.NORMAL
        }

    // Save the selected driving mode.
    suspend fun saveDrivingMode(mode: DrivingMode) {
        dataStore.edit { preferences ->
            preferences[drivingModeKey] = mode.name
        }
    }
}
