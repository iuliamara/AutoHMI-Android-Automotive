package com.example.autohmi.navigation

import org.junit.Assert.*
import org.junit.Test

class GeoMathTest {
    @Test fun distanceAndProjectionFollowThePolylineRatherThanDirectDestinationDistance() {
        val line = fixtureRoute().geometry
        val projection = GeoMath.project(line[1], line)
        assertTrue(projection.lengthMeters > GeoMath.distance(line.first(), line.last()))
        assertTrue(projection.alongMeters in 60.0..80.0)
        assertTrue(projection.offsetMeters < 1)
        assertEquals(0.0, GeoMath.distance(fixtureOrigin, fixtureOrigin), 0.0)
    }
    @Test fun invalidPointsAreRejectedAndAntimeridianDistancesStayShort() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, 91.0).forEach {
            try { GeoPoint(it, 0.0); fail("Invalid point accepted") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(GeoMath.distance(GeoPoint(0.0, 179.9), GeoPoint(0.0, -179.9)) < 23_000)
        assertFalse(fixtureOrigin.toString().contains("52.0"))
    }
}
