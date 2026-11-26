package com.example.autohmi.navigation

enum class SpeedSource { DEMO, GPS }

/** Null is an unavailable measurement, never an assumed stationary vehicle. */
data class NavigationSpeed(val source: SpeedSource, val speedKmh: Double?, val demoStatus: DemoDriveStatus? = null)

/** Uses the provider's reported speed only; no coordinate differencing or vehicle simulation. */
internal fun LocationState.validGpsSpeedKmh(nowElapsedMs: Long): Double? {
    val measurement = fix ?: return null
    val speed = measurement.speedMetersPerSecond ?: return null
    if (status != LocationStatus.AVAILABLE || permission != LocationPermission.PRECISE ||
        nowElapsedMs - measurement.elapsedMs !in 0..15_000 || measurement.accuracyMeters > 50.0 ||
        !speed.isFinite() || speed !in 0.0..100.0) return null
    val uncertainty = measurement.speedAccuracyMetersPerSecond
    if (uncertainty != null && (!uncertainty.isFinite() || uncertainty !in 0.0..2.0)) return null
    return speed * 3.6
}
