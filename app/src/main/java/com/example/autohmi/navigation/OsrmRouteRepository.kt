package com.example.autohmi.navigation

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class RouteHttpResponse(val status: Int, val body: String, val retryAfterSeconds: Long? = null) {
    override fun toString() = "RouteHttpResponse(status=$status, body=[location data])"
}
fun interface RouteTransport { suspend fun get(url: String): RouteHttpResponse }
class HttpsRouteTransport : RouteTransport {
    override suspend fun get(url: String): RouteHttpResponse = withContext(Dispatchers.IO) {
        require(URI(url).scheme == "https") { "HTTPS is required" }
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", "AutoHMI/1.0 (Android route planner)")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            // Do not retain provider error text or log coordinate-bearing URLs.
            val body = if (status == 200) connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val size = stream.read(buffer); if (size < 0) break
                    if (output.size() + size > 4 * 1024 * 1024) throw IOException("Route response too large")
                    output.write(buffer, 0, size)
                }
                output.toString(Charsets.UTF_8.name())
            } else ""
            RouteHttpResponse(status, body, connection.getHeaderField("Retry-After")?.toLongOrNull())
        } finally { connection.disconnect() }
    }
}

class OsrmRouteRepository(
    baseUrl: String,
    private val transport: RouteTransport = HttpsRouteTransport(),
    private val elapsed: () -> Long
) : RouteRepository {
    private val base = baseUrl.trim().trimEnd('/')
    init {
        val uri = URI(base)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Invalid routing endpoint" }
    }
    override val configured = true
    private val requestGate = Mutex()
    private var retryAt = 0L
    override suspend fun calculate(origin: GeoPoint, destination: Destination): RouteResult {
        // Explicit requests only, at most one every two seconds, including rapid retries.
        // This also stays below the FOSSGIS public demo's one-request-per-second limit.
        val allowed = requestGate.withLock {
            val now = elapsed()
            if (now < retryAt) false else { retryAt = now + 2_000; true }
        }
        if (!allowed) return RouteResult.Failure(NavigationIssue.RATE_LIMITED)
        return try {
            fun coordinate(point: GeoPoint) = String.format(Locale.US, "%.7f,%.7f", point.longitude, point.latitude)
            val url = "$base/route/v1/driving/${coordinate(origin)};${coordinate(destination.point)}?geometries=geojson&overview=full&steps=false&alternatives=false"
            val response = transport.get(url)
            coroutineContext.ensureActive()
            when (response.status) {
                200 -> when (val result = parse(response.body, origin, destination)) {
                    is RouteResult.Success -> if (URI(base).host == "routing.openstreetmap.de")
                        result.copy(route = result.route.copy(attribution = "Routing: OSRM / FOSSGIS · © OpenStreetMap contributors")) else result
                    is RouteResult.Failure -> result
                }
                429 -> {
                    val seconds = (response.retryAfterSeconds ?: 30).coerceIn(1, (Long.MAX_VALUE - elapsed()) / 1000)
                    requestGate.withLock { retryAt = maxOf(retryAt, elapsed() + seconds * 1000) }
                    RouteResult.Failure(NavigationIssue.RATE_LIMITED)
                }
                else -> RouteResult.Failure(NavigationIssue.SERVER)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { RouteResult.Failure(NavigationIssue.NETWORK) }
        catch (_: Exception) { RouteResult.Failure(NavigationIssue.INVALID_ROUTE) }
    }
    companion object {
        fun parse(body: String, origin: GeoPoint, destination: Destination): RouteResult = try {
            val json = JsonParser.parseString(body).asJsonObject
            if (json.get("code")?.asString in setOf("NoRoute", "NoSegment")) RouteResult.Failure(NavigationIssue.NO_ROUTE)
            else if (json.get("code")?.asString != "Ok") RouteResult.Failure(NavigationIssue.SERVER)
            else {
                val route = json.getAsJsonArray("routes").firstOrNull()?.asJsonObject
                if (route == null) RouteResult.Failure(NavigationIssue.NO_ROUTE)
                else {
                    val geometry = route.getAsJsonObject("geometry")
                    require(geometry.get("type").asString == "LineString") { "Expected road geometry" }
                    val coordinates = geometry.getAsJsonArray("coordinates")
                    require(coordinates.size() in 2..20_000) { "Invalid geometry size" }
                    val points = coordinates.map { point ->
                        val values = point.asJsonArray
                        require(values.size() >= 2)
                        GeoPoint(values[1].asDouble, values[0].asDouble)
                    }
                    val length = GeoMath.project(points.first(), points).lengthMeters
                    val distance = route.get("distance").asDouble
                    require(length > 1 && distance >= length * 0.5 && distance <= 50_000_000)
                    require(GeoMath.distance(origin, points.first()) <= 300)
                    // OSRM's waypoints are snapped road positions, in input order.
                    // Validate the geometry against that endpoint, not the off-road pin.
                    val waypoints = json.getAsJsonArray("waypoints")
                    require(waypoints.size() == 2) { "Expected origin and destination waypoints" }
                    val waypoint = waypoints[1].asJsonObject
                    val location = waypoint.getAsJsonArray("location")
                    require(location.size() == 2) { "Expected snapped longitude and latitude" }
                    val snapped = GeoPoint(location[1].asDouble, location[0].asDouble)
                    val name = waypoint.get("name")?.takeUnless { it.isJsonNull }?.asString?.trim()?.take(120)
                    val namedDestination = if (destination.name == "Pinned destination" && !name.isNullOrBlank()) destination.copy(name = name) else destination
                    RouteResult.Success(RoutePlan(origin, namedDestination, points, distance, route.get("duration").asDouble, snappedDestination = snapped))
                }
            }
        } catch (_: Exception) { RouteResult.Failure(NavigationIssue.INVALID_ROUTE) }
    }
}
