package com.example.autohmi.navigation

import kotlin.math.*

object GeoMath {
    private const val EARTH = 6_371_008.8
    private fun radians(value: Double) = Math.toRadians(value)
    private fun lonDelta(a: Double, b: Double) = atan2(sin(radians(b - a)), cos(radians(b - a)))
    fun distance(a: GeoPoint, b: GeoPoint): Double {
        val lat = radians(b.latitude - a.latitude)
        val lon = lonDelta(a.longitude, b.longitude)
        val h = sin(lat / 2).pow(2) + cos(radians(a.latitude)) * cos(radians(b.latitude)) * sin(lon / 2).pow(2)
        return 2 * EARTH * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    /** Short-arc interpolation, including routes crossing the longitude wrap. */
    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        require(fraction.isFinite())
        val t = fraction.coerceIn(0.0, 1.0)
        if (t == 0.0) return a
        if (t == 1.0) return b
        val angle = distance(a, b) / EARTH
        val denominator = sin(angle)
        if (abs(denominator) < 1e-10) {
            val longitude = a.longitude + Math.toDegrees(lonDelta(a.longitude, b.longitude)) * t
            return GeoPoint(a.latitude + (b.latitude - a.latitude) * t, ((longitude + 540) % 360) - 180)
        }
        val wa = sin((1 - t) * angle) / denominator
        val wb = sin(t * angle) / denominator
        val la = radians(a.latitude); val lb = radians(b.latitude)
        val loa = radians(a.longitude); val lob = radians(b.longitude)
        val x = wa * cos(la) * cos(loa) + wb * cos(lb) * cos(lob)
        val y = wa * cos(la) * sin(loa) + wb * cos(lb) * sin(lob)
        val z = wa * sin(la) + wb * sin(lb)
        return GeoPoint(Math.toDegrees(atan2(z, hypot(x, y))), Math.toDegrees(atan2(y, x)))
    }
    data class Projection(val alongMeters: Double, val lengthMeters: Double, val offsetMeters: Double)
    fun project(point: GeoPoint, line: List<GeoPoint>): Projection {
        require(line.size >= 2)
        var total = 0.0; var bestAlong = 0.0; var bestOffset = Double.POSITIVE_INFINITY
        for (index in 0 until line.lastIndex) {
            val a = line[index]; val b = line[index + 1]
            val segment = distance(a, b)
            val cosLat = cos(radians((a.latitude + b.latitude) / 2)).coerceAtLeast(0.000001)
            val bx = lonDelta(a.longitude, b.longitude) * EARTH * cosLat
            val by = radians(b.latitude - a.latitude) * EARTH
            val px = lonDelta(a.longitude, point.longitude) * EARTH * cosLat
            val py = radians(point.latitude - a.latitude) * EARTH
            val denominator = bx * bx + by * by
            val t = if (denominator <= 0) 0.0 else ((px * bx + py * by) / denominator).coerceIn(0.0, 1.0)
            val offset = hypot(px - t * bx, py - t * by)
            if (offset < bestOffset) { bestOffset = offset; bestAlong = total + segment * t }
            total += segment
        }
        return Projection(bestAlong, total, bestOffset)
    }
}
