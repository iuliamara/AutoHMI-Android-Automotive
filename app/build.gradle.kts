import java.util.Properties
import java.security.MessageDigest
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val spotifyAar = layout.projectDirectory.file("libs/spotify-app-remote-release-0.8.0.aar").asFile
check(spotifyAar.isFile) { "Spotify SDK is missing. Run scripts/setup-spotify.ps1 (see docs/SPOTIFY.md)." }
val spotifyAarHash = MessageDigest.getInstance("SHA-256").digest(spotifyAar.readBytes())
    .joinToString("") { "%02x".format(it) }
check(spotifyAarHash == "b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198") {
    "Spotify SDK checksum mismatch. Download the pinned official release using scripts/setup-spotify.ps1."
}
val spotifyProperties = Properties().apply {
    // An override permits isolated build checks without writing real local credentials.
    val configPath = providers.gradleProperty("spotifyPropertiesFile").orNull
    val config = rootProject.file(configPath ?: "spotify.properties")
    check(configPath == null || config.isFile) { "The specified spotifyPropertiesFile does not exist." }
    if (config.isFile) config.inputStream().use { load(it) }
}
val redirectValue = spotifyProperties.getProperty("spotify.redirectUri", "").trim()
val spotifyAppLink = if (redirectValue.isBlank()) null else runCatching { URI(redirectValue) }.getOrNull()
check(redirectValue.isBlank() || (spotifyAppLink != null &&
    spotifyAppLink.scheme.equals("https", true) && !spotifyAppLink.host.isNullOrBlank() &&
    '.' in spotifyAppLink.host && spotifyAppLink.host != "localhost" &&
    spotifyAppLink.port == -1 && spotifyAppLink.userInfo == null &&
    spotifyAppLink.rawQuery == null && spotifyAppLink.fragment == null &&
    spotifyAppLink.rawPath.orEmpty().startsWith("/") && spotifyAppLink.rawPath != "/" &&
    '%' !in spotifyAppLink.rawPath && '\\' !in spotifyAppLink.rawPath &&
    spotifyAppLink.normalize().rawPath == spotifyAppLink.rawPath)) {
    "spotify.redirectUri must be a canonical HTTPS URL on your hosted domain, with an exact path and no port, query or fragment. See docs/SPOTIFY_APP_LINKS.md."
}
fun quotedConfig(name: String): String = "\"" + spotifyProperties.getProperty(name, "").trim()
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

val navigationProperties = Properties().apply {
    val file = rootProject.file("navigation.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
val routingBaseUrl = navigationProperties.getProperty("routing.osrmBaseUrl", "").trim().trimEnd('/')
check(routingBaseUrl.isEmpty() || runCatching {
    val uri = URI(routingBaseUrl)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.rawQuery == null && uri.fragment == null && uri.normalize().rawPath == uri.rawPath
}.getOrDefault(false)) { "Routing requires a canonical HTTPS OSRM base URL without credentials, query or fragment. See docs/NAVIGATION_IMPLEMENTATION.md." }

android {
    namespace = "com.example.autohmi"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.autohmi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "com.example.autohmi.AutoHmiTestRunner"
        buildConfigField("String", "SPOTIFY_CLIENT_ID", quotedConfig("spotify.clientId"))
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", quotedConfig("spotify.redirectUri"))
        buildConfigField("String", "SPOTIFY_PAIRING_BASE_URL", quotedConfig("spotify.pairingBaseUrl"))
        buildConfigField("boolean", "SPOTIFY_APP_LINKS_ENABLED", (spotifyAppLink != null).toString())
        buildConfigField("String", "NAVIGATION_OSRM_BASE_URL", "\"" + routingBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        if (spotifyAppLink != null) {
            manifestPlaceholders["spotifyAppLinkHost"] = spotifyAppLink.host.lowercase()
            manifestPlaceholders["spotifyAppLinkPath"] = spotifyAppLink.rawPath
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    if (spotifyAppLink != null) {
        sourceSets.getByName("debug").manifest.srcFile("src/spotifyAppLinks/AndroidManifest.xml")
        sourceSets.getByName("release").manifest.srcFile("src/spotifyAppLinks/AndroidManifest.xml")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.gson)
    implementation(libs.zxing.core)
    implementation(libs.maplibre)
    implementation(libs.play.services.location)
    implementation(files(spotifyAar))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}
