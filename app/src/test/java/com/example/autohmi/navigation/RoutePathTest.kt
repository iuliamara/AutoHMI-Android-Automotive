package com.example.autohmi.navigation

import org.junit.Assert.*
import org.junit.Test

class RoutePathTest {
    private fun north(meters: Double) = GeoPoint(Math.toDegrees(meters / 6_371_008.8), 0.0)
    @Test fun interpolationUsesDistanceRatherThanCoordinateIndex() {
        val path = RoutePath(listOf(north(0.0), north(10.0), north(100.0), north(110.0)))
        assertEquals(110.0, path.lengthMeters, 0.001)
        assertTrue(GeoMath.distance(north(55.0), path.pointAt(55.0)) < 0.001)
        assertTrue(GeoMath.distance(north(105.0), path.pointAt(105.0)) < 0.001)
    }
    @Test fun interpolationFollowsCornersInsteadOfCuttingAcrossThem() {
        val angle = Math.toDegrees(100.0 / 6_371_008.8)
        val a = GeoPoint(0.0, 0.0); val corner = GeoPoint(0.0, angle); val end = GeoPoint(angle, angle)
        val path = RoutePath(listOf(a, corner, end))
        assertTrue(GeoMath.distance(corner, path.pointAt(100.0)) < 0.001)
        assertTrue(GeoMath.distance(GeoPoint(angle / 2, angle), path.pointAt(150.0)) < 0.001)
        assertTrue(GeoMath.distance(path.pointAt(150.0), GeoPoint(angle / 2, angle / 2)) > 40)
    }
    @Test fun duplicateCoordinatesAndOvershootRetainExactEndpoints() {
        val a = north(0.0); val b = north(50.0)
        val path = RoutePath(listOf(a, a, north(20.0), north(20.0), b, b))
        assertEquals(a, path.pointAt(-100.0)); assertEquals(b, path.pointAt(1000.0))
        assertTrue(GeoMath.distance(north(30.0), path.pointAt(30.0)) < 0.001)
    }
    @Test fun geometryIsCapturedIndependentlyOfLaterListChanges() {
        val points = mutableListOf(north(0.0), north(100.0))
        val path = RoutePath(points)
        points[1] = north(1000.0)
        assertEquals(north(100.0), path.pointAt(path.lengthMeters))
        assertEquals(100.0, path.lengthMeters, 0.001)
    }
    @Test fun longitudeWrapTakesTheShortArc() {
        val a = GeoPoint(0.0, 179.9); val b = GeoPoint(0.0, -179.9)
        val path = RoutePath(listOf(a, b))
        val middle = path.pointAt(path.lengthMeters / 2)
        assertTrue(kotlin.math.abs(middle.longitude) > 179.99)
        assertEquals(path.lengthMeters / 2, GeoMath.distance(a, middle), 0.01)
        assertEquals(a, GeoMath.interpolate(a, b, 0.0)); assertEquals(b, GeoMath.interpolate(a, b, 1.0))
    }
    @Test fun emptyOrStationaryGeometryCannotStartMovement() {
        for (points in listOf(emptyList(), listOf(north(0.0)), listOf(north(0.0), north(0.0)))) {
            assertTrue(runCatching { RoutePath(points) }.isFailure)
        }
        val path = RoutePath(listOf(north(0.0), north(10.0)))
        assertTrue(runCatching { path.pointAt(Double.NaN) }.isFailure)
    }
}
