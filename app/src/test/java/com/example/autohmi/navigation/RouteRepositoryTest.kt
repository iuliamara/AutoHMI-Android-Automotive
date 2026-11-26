package com.example.autohmi.navigation

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RouteRepositoryTest {
    @Test fun osrmCoordinatesAreLongitudeLatitudeAndMapToRoadGeometryAndMetrics() = runTest {
        var requested = ""
        val repository = OsrmRouteRepository("https://router.test.invalid", RouteTransport { requested = it; RouteHttpResponse(200, RESPONSE) }, { 100_000 })
        val result = repository.calculate(fixtureOrigin, Destination(fixtureDestination.point)) as RouteResult.Success
        assertTrue(requested.contains("13.0000000,52.0000000;13.0010000,52.0010000"))
        assertTrue(requested.contains("geometries=geojson"))
        assertEquals(3, result.route.geometry.size)
        assertEquals(200.0, result.route.distanceMeters, 0.0)
        assertEquals(60.0, result.route.durationSeconds, 0.0)
        assertEquals("Offline destination road", result.route.destination.name)
    }
    @Test fun absentAndMalformedRoutesNeverBecomeStraightLineFallbacks() {
        val bad = listOf("{}", "not-json", RESPONSE.replace("LineString", "Point"), RESPONSE.replace("200.0", "-1"),
            RESPONSE.replace("60.0", "0"), RESPONSE.replace("60.0", "1e100"), RESPONSE.replace("[13.001,52.001]", "[13.001,152.001]"),
            RESPONSE.replace("[13.0,52.0],[13.001,52.0],[13.001,52.001]", "[13.0,52.0]"))
        bad.forEach { assertTrue(OsrmRouteRepository.parse(it, fixtureOrigin, fixtureDestination) is RouteResult.Failure) }
        assertEquals(RouteResult.Failure(NavigationIssue.NO_ROUTE), OsrmRouteRepository.parse("""{"code":"NoRoute"}""", fixtureOrigin, fixtureDestination))
    }
    @Test fun snappedEndpointIsSeparateFromTheSelectedPinAndNeverExtendsDrivingMetrics() {
        val selected = northOfRoadEndpoint(80.0)
        // The gap is calculated from coordinates, not copied from the provider's distance field.
        val body = RESPONSE.replace("\"name\":\"Offline destination road\"", "\"distance\":9999,\"name\":\"Offline destination road\"")
        val route = (OsrmRouteRepository.parse(body, fixtureOrigin, selected) as RouteResult.Success).route
        assertEquals(selected, route.destination)
        assertEquals(fixtureDestination.point, route.snappedDestination)
        assertEquals(fixtureDestination.point, route.drivingEndpoint)
        assertEquals(80.0, route.destinationGapMeters, 0.01)
        assertTrue(route.hasDestinationGap)
        assertEquals(listOf(fixtureOrigin, GeoPoint(52.0, 13.001), fixtureDestination.point), route.geometry)
        assertNotEquals(selected.point, route.geometry.last())
        assertEquals(200.0, route.distanceMeters, 0.0)
        assertEquals(60.0, route.durationSeconds, 0.0)
    }
    @Test fun alignedAndNearDestinationsUseTheTwentyFiveMeterWarningThreshold() {
        val aligned = (OsrmRouteRepository.parse(RESPONSE, fixtureOrigin, fixtureDestination) as RouteResult.Success).route
        assertEquals(0.0, aligned.destinationGapMeters, 0.0)
        assertFalse(aligned.hasDestinationGap)
        for ((meters, warning) in listOf(24.9 to false, 25.1 to true)) {
            val route = (OsrmRouteRepository.parse(RESPONSE, fixtureOrigin, northOfRoadEndpoint(meters)) as RouteResult.Success).route
            assertEquals(meters, route.destinationGapMeters, 0.01)
            assertEquals(warning, route.hasDestinationGap)
        }
    }
    @Test fun aLargeValidSnapIsReportedInsteadOfRejectedOrFilledWithInventedGeometry() {
        val selected = northOfRoadEndpoint(500.0)
        val route = (OsrmRouteRepository.parse(RESPONSE, fixtureOrigin, selected) as RouteResult.Success).route
        assertEquals(selected.point, route.destination.point)
        assertEquals(500.0, route.destinationGapMeters, 0.01)
        assertTrue(route.hasDestinationGap)
        assertEquals(3, route.geometry.size)
        assertEquals(fixtureDestination.point, route.geometry.last())
        assertEquals(200.0, route.distanceMeters, 0.0)
        assertEquals(60.0, route.durationSeconds, 0.0)
    }
    @Test fun missingMalformedOrInconsistentSnappedWaypointsCannotBecomeValidRoutes() {
        val location = "\"location\":[13.001,52.001]"
        val bad = listOf(
            RESPONSE.replace(location, "\"location\":[13.003,52.001]"),
            RESPONSE.replace(location, "\"location\":[13.001,152.001]"),
            RESPONSE.replace(location, "\"location\":[13.001]"),
            RESPONSE.replace(location, "\"location\":null"),
            RESPONSE.replace(location, "\"location\":[\"invalid\",52.001]"),
            RESPONSE.replace("$location,", "")
        )
        bad.forEach { assertEquals(RouteResult.Failure(NavigationIssue.INVALID_ROUTE), OsrmRouteRepository.parse(it, fixtureOrigin, fixtureDestination)) }
    }
    @Test fun minorWaypointRoundingDoesNotChangeTheRenderedGeometryEndpoint() {
        val body = RESPONSE.replace("\"location\":[13.001,52.001]", "\"location\":[13.0010003,52.0010003]")
        val route = (OsrmRouteRepository.parse(body, fixtureOrigin, fixtureDestination) as RouteResult.Success).route
        assertEquals(GeoPoint(52.0010003, 13.0010003), route.snappedDestination)
        assertEquals(fixtureDestination.point, route.drivingEndpoint)
        assertTrue(route.destinationGapMeters < 1.0)
        assertFalse(route.hasDestinationGap)
    }
    @Test fun networkAndRateLimitsAreSanitizedAndBackoffPreventsExtraRequests() = runTest {
        var time = 100_000L; var calls = 0
        val limited = OsrmRouteRepository("https://router.test.invalid", RouteTransport { calls++; RouteHttpResponse(429, "untrusted details", 10) }, { time })
        assertEquals(RouteResult.Failure(NavigationIssue.RATE_LIMITED), limited.calculate(fixtureOrigin, fixtureDestination))
        limited.calculate(fixtureOrigin, fixtureDestination); assertEquals(1, calls)
        time += 10_001; limited.calculate(fixtureOrigin, fixtureDestination); assertEquals(2, calls)
        val offline = OsrmRouteRepository("https://router.test.invalid", RouteTransport { throw IOException("fixture only") }, { time })
        assertEquals(RouteResult.Failure(NavigationIssue.NETWORK), offline.calculate(fixtureOrigin, fixtureDestination))
    }
    @Test fun rapidSuccessfulRequestsAreAlsoLimitedAndPublicServiceIsAttributed() = runTest {
        var time = 0L; var calls = 0
        val repository = OsrmRouteRepository("https://routing.openstreetmap.de/routed-car", RouteTransport { calls++; RouteHttpResponse(200, RESPONSE) }, { time })
        val first = repository.calculate(fixtureOrigin, fixtureDestination) as RouteResult.Success
        assertTrue(first.route.attribution.contains("FOSSGIS"))
        time = 1_000
        assertEquals(RouteResult.Failure(NavigationIssue.RATE_LIMITED), repository.calculate(fixtureOrigin, fixtureDestination))
        assertEquals(1, calls)
        time = 2_001
        assertTrue(repository.calculate(fixtureOrigin, fixtureDestination) is RouteResult.Success)
        assertEquals(2, calls)
    }
    @Test fun unconfiguredProviderAndUnsafeEndpointsCannotSilentlyUseADemoServer() = runTest {
        assertFalse(UnconfiguredRouteRepository().configured)
        assertEquals(RouteResult.Failure(NavigationIssue.ROUTING_NOT_CONFIGURED), UnconfiguredRouteRepository().calculate(fixtureOrigin, fixtureDestination))
        for (url in listOf("http://router.test.invalid", "https://user@router.test.invalid", "https://router.test.invalid?key=fixture")) {
            try { OsrmRouteRepository(url, elapsed = {0}); fail("Unsafe endpoint accepted") } catch (_: IllegalArgumentException) { }
        }
    }
    companion object {
        private const val RESPONSE = """{"code":"Ok","routes":[{"distance":200.0,"duration":60.0,"geometry":{"type":"LineString","coordinates":[[13.0,52.0],[13.001,52.0],[13.001,52.001]]}}],"waypoints":[{"location":[13.0,52.0],"name":"Offline origin road"},{"location":[13.001,52.001],"name":"Offline destination road"}]}"""
    }
    private fun northOfRoadEndpoint(meters: Double) = Destination(
        GeoPoint(52.001 + Math.toDegrees(meters / 6_371_008.8), 13.001), "Selected off-road point")
}
