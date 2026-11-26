package com.example.autohmi.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FusedLocationRepository(context: Context) : LocationRepository {
    private val app = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(app)
    private val manager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _state = MutableStateFlow(LocationState())
    override val state = _state.asStateFlow()
    private var active = false
    private var generation = 0L
    private var callback: LocationCallback? = null
    private var currentRequest: CancellationTokenSource? = null
    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { restart() } }

    override fun setPermission(permission: LocationPermission) {
        if (_state.value.permission != permission) {
            _state.update { it.copy(permission = permission, fix = if (permission == LocationPermission.NONE) null else it.fix) }
            restart()
        }
    }
    override fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (active && !receiverRegistered) {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(LocationManager.MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } else if (!active && receiverRegistered) { app.unregisterReceiver(receiver); receiverRegistered = false }
        restart()
    }
    private fun actualPermission(): LocationPermission = when {
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationPermission.PRECISE
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationPermission.APPROXIMATE
        else -> LocationPermission.NONE
    }
    private fun stop() {
        generation++
        callback?.let { runCatching { client.removeLocationUpdates(it) } }; callback = null
        currentRequest?.cancel(); currentRequest = null
    }
    @SuppressLint("MissingPermission")
    private fun restart() {
        stop()
        if (!active) { _state.update { it.copy(status = LocationStatus.IDLE) }; return }
        val permission = actualPermission()
        _state.update { it.copy(permission = permission) }
        if (permission == LocationPermission.NONE) { _state.update { it.copy(status = LocationStatus.DENIED, fix = null) }; return }
        if (!LocationManagerCompat.isLocationEnabled(manager)) { _state.update { it.copy(status = LocationStatus.SERVICES_DISABLED) }; return }
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) != ConnectionResult.SUCCESS) {
            _state.update { it.copy(status = LocationStatus.UNSUPPORTED) }; return
        }
        _state.update { it.copy(status = LocationStatus.SEARCHING) }
        val attempt = generation
        val priority = if (permission == LocationPermission.PRECISE) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val interval = if (permission == LocationPermission.PRECISE) 2_000L else 10_000L
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { if (attempt == generation && active) result.lastLocation?.let(::accept) }
            override fun onLocationAvailability(availability: LocationAvailability) {
                val age = _state.value.fix?.let { SystemClock.elapsedRealtime() - it.elapsedMs }
                if (attempt == generation && !availability.isLocationAvailable && (age == null || age > 15_000)) {
                    _state.update { it.copy(status = LocationStatus.SEARCHING) }
                }
            }
        }
        callback = listener
        try {
            client.requestLocationUpdates(LocationRequest.Builder(priority, interval).setMinUpdateIntervalMillis(interval / 2)
                .setMinUpdateDistanceMeters(0f).setWaitForAccurateLocation(false).build(), listener, Looper.getMainLooper())
                .addOnFailureListener { if (attempt == generation) _state.update { it.copy(status = LocationStatus.UNAVAILABLE) } }
            requestFresh()
        } catch (_: SecurityException) { _state.update { it.copy(status = LocationStatus.DENIED, permission = LocationPermission.NONE, fix = null) }; stop() }
        catch (_: Exception) { _state.update { it.copy(status = LocationStatus.UNAVAILABLE) }; stop() }
    }
    @SuppressLint("MissingPermission")
    override fun requestFresh() {
        if (!active || callback == null || actualPermission() == LocationPermission.NONE) return
        currentRequest?.cancel()
        val cancellation = CancellationTokenSource().also { currentRequest = it }
        val attempt = generation
        val priority = if (_state.value.permission == LocationPermission.PRECISE) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        try {
            client.getCurrentLocation(CurrentLocationRequest.Builder().setPriority(priority).setMaxUpdateAgeMillis(0).setDurationMillis(10_000).build(), cancellation.token)
                .addOnSuccessListener { location ->
                    if (attempt == generation && active && !cancellation.token.isCancellationRequested) {
                        if (location != null) accept(location)
                        else if (_state.value.fix == null) _state.update { it.copy(status = LocationStatus.UNAVAILABLE) }
                    }
                }.addOnFailureListener { if (attempt == generation) _state.update { it.copy(status = LocationStatus.UNAVAILABLE) } }
        } catch (_: SecurityException) { stop(); _state.update { it.copy(status = LocationStatus.DENIED, permission = LocationPermission.NONE, fix = null) } }
    }
    private fun accept(location: Location) {
        val fix = location.toNavigationFix() ?: return
        if (fix.elapsedMs < (_state.value.fix?.elapsedMs ?: 0)) return
        _state.update { it.copy(status = LocationStatus.AVAILABLE, fix = fix) }
    }
    override fun close() { active = false; stop(); if (receiverRegistered) { app.unregisterReceiver(receiver); receiverRegistered = false } }
}

/** Preserve missing speed as null. Bad speed metadata must not discard otherwise usable coordinates. */
internal fun Location.toNavigationFix(): LocationFix? = runCatching {
    LocationFix(GeoPoint(latitude, longitude), if (hasAccuracy()) accuracy.toDouble() else 10_000.0,
        elapsedRealtimeNanos / 1_000_000, LocationCompat.isMock(this),
        if (hasSpeed()) speed.toDouble() else null,
        if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null)
}.getOrNull()
