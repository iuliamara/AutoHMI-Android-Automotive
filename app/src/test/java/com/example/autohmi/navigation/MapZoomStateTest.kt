package com.example.autohmi.navigation

import com.example.autohmi.navigation.ui.MapZoomState
import org.junit.Assert.assertEquals
import org.junit.Test

class MapZoomStateTest {
    private val point = GeoPoint(52.52, 13.405)

    @Test fun repeatedPressesAccumulateWhileAnimatingAndClampToNativeLimits() {
        val zoom = MapZoomState(MapCamera(point, 16.0))
        assertEquals(17.0, zoom.step(16.0, 1.0, 14.0, 18.0), 0.0)
        assertEquals(18.0, zoom.step(16.2, 1.0, 14.0, 18.0), 0.0)
        assertEquals(18.0, zoom.step(17.1, 1.0, 14.0, 18.0), 0.0)
        repeat(6) { zoom.step(17.5, -1.0, 14.0, 18.0) }
        assertEquals(14.0, zoom.followZoom(17.5, 5.0), 0.0)
    }

    @Test fun gpsUpdatesPreserveSelectedZoomEvenWhenTheyInterruptAnAnimation() {
        val zoom = MapZoomState(MapCamera(point, 15.0))
        assertEquals(14.0, zoom.step(15.0, -1.0, 0.0, 25.5), 0.0)
        assertEquals(14.0, zoom.followZoom(14.8, 5.0), 0.0)
        assertEquals(14.0, zoom.followZoom(14.1, 300.0), 0.0)
        assertEquals(14.0, zoom.followZoom(14.0, 5.0), 0.0)
    }

    @Test fun restoredCameraDoesNotGetRaisedToTheInitialGpsZoom() {
        val zoom = MapZoomState(MapCamera(point, 12.5))
        assertEquals(12.5, zoom.followZoom(12.5, 5.0), 0.0)
    }

    @Test fun firstFixRetainsExistingInitialFramingWithoutARecurringZoomFloor() {
        val precise = MapZoomState(MapCamera())
        assertEquals(15.0, precise.followZoom(1.5, 5.0), 0.0)
        assertEquals(12.0, precise.followZoom(12.0, 5.0), 0.0)
        assertEquals(11.0, MapZoomState(MapCamera()).followZoom(1.5, 300.0), 0.0)
    }

    @Test fun gesturesRecenterAndOverviewReplaceThePreviousButtonTarget() {
        val zoom = MapZoomState(MapCamera(point, 16.0))
        zoom.step(16.0, 1.0, 0.0, 25.5)
        zoom.cameraChanged() // A pinch or an overview now owns the camera.
        assertEquals(12.0, zoom.followZoom(12.0, 5.0), 0.0)
        zoom.cameraChanged(15.0) // Explicit Recenter retains its established framing.
        assertEquals(15.0, zoom.followZoom(13.0, 5.0), 0.0)
        zoom.cameraChanged()
        assertEquals(10.0, zoom.followZoom(10.0, 5.0), 0.0)
    }

    @Test fun zoomChosenBeforeTheFirstGpsFixIsNotOverwritten() {
        val zoom = MapZoomState(MapCamera())
        zoom.step(1.5, 1.0, 0.0, 25.5)
        assertEquals(2.5, zoom.followZoom(2.0, 5.0), 0.0)
    }
}
