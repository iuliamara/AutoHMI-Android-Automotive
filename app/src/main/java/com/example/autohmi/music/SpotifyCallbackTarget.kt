package com.example.autohmi.music

import java.net.URI
import java.util.Locale

/** URL validation is not evidence of domain ownership or Android verification. */
data class SpotifyCallbackTarget(val host: String, val path: String) {
    fun accepts(incoming: String?): Boolean {
        if (incoming == null) return false
        val uri = runCatching { URI(incoming) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase(Locale.ROOT) == host &&
            uri.port == -1 && uri.userInfo == null && uri.fragment == null &&
            uri.rawPath == path
        // Query parameters are intentionally neither parsed, stored, nor forwarded.
    }

    companion object {
        fun parse(configuredUri: String): SpotifyCallbackTarget? {
            val uri = runCatching { URI(configuredUri) }.getOrNull() ?: return null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            val path = uri.rawPath.orEmpty()
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.port != -1 ||
                uri.userInfo != null || uri.rawQuery != null || uri.fragment != null ||
                !path.startsWith("/") || path == "/" || '%' in path || '\\' in path ||
                uri.normalize().rawPath != path || host == "localhost" || '.' !in host
            ) return null
            return SpotifyCallbackTarget(host, path)
        }
    }
}
