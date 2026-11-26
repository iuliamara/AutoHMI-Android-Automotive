package com.example.autohmi.navigation

/** Immutable distance index of the provider's geometry. No routing or location writes. */
class RoutePath(geometry: List<GeoPoint>) {
    private val points = geometry.toList()
    private val cumulative = DoubleArray(points.size)
    val lengthMeters: Double
    init {
        require(points.size >= 2)
        for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + GeoMath.distance(points[i - 1], points[i])
        lengthMeters = cumulative.last()
        require(lengthMeters.isFinite() && lengthMeters > 0)
    }
    fun pointAt(distanceMeters: Double): GeoPoint {
        require(distanceMeters.isFinite())
        if (distanceMeters <= 0) return points.first()
        if (distanceMeters >= lengthMeters) return points.last()
        val found = cumulative.binarySearch(distanceMeters)
        if (found >= 0) return points[found]
        val end = -found - 1
        val start = end - 1
        val fraction = (distanceMeters - cumulative[start]) / (cumulative[end] - cumulative[start])
        return GeoMath.interpolate(points[start], points[end], fraction)
    }
}
