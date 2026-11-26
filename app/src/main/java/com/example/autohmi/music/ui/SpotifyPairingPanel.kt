package com.example.autohmi.music.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.autohmi.music.pairing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SpotifyPairingPanel(state: SpotifyPairingState, onRetry: () -> Unit, onCancel: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp).testTag("spotify_pairing")) {
        val wide = maxWidth >= 600.dp && maxHeight >= 300.dp
        val qrSize = if (wide) minOf(320.dp, maxHeight - 32.dp, maxWidth * 0.42f) else minOf(280.dp, maxWidth)
        if (wide) Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            if (state.phase == PairingPhase.WAITING && state.qrUrl != null) PairingCode(state.qrUrl, Modifier.size(qrSize))
            PairingInstructions(state, onRetry, onCancel, Modifier.weight(1f).verticalScroll(rememberScrollState()))
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.phase == PairingPhase.WAITING && state.qrUrl != null) PairingCode(state.qrUrl, Modifier.size(qrSize))
            PairingInstructions(state, onRetry, onCancel, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PairingInstructions(state: SpotifyPairingState, onRetry: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Spotify Connect", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(when (state.phase) {
            PairingPhase.WAITING -> "Scan with your phone"
            PairingPhase.GENERATING -> "Creating secure QR…"
            PairingPhase.CONNECTING -> "Connecting to Spotify…"
            PairingPhase.CONNECTED -> "Spotify connected"
            PairingPhase.EXPIRED -> "QR expired"
            PairingPhase.UNCONFIGURED -> "QR setup required"
            else -> "Pairing could not finish"
        }, style = MaterialTheme.typography.headlineMedium)
        Text(when (state.phase) {
            PairingPhase.WAITING -> "Open your phone’s camera, scan this QR, then tap Continue to Spotify. Approve access on Spotify. AutoHMI connects automatically."
            PairingPhase.GENERATING -> "Preparing a private, short-lived pairing session."
            PairingPhase.CONNECTING -> "Your phone has finished. Securely completing authorization on AutoHMI."
            PairingPhase.CONNECTED -> "Your session is saved securely. Open Spotify on Windows, then select it as your playback device."
            PairingPhase.EXPIRED -> "This QR is no longer usable. Generate a new QR and scan again."
            PairingPhase.UNCONFIGURED -> "The owner must configure the HTTPS pairing relay and Spotify callback, then rebuild AutoHMI. See docs/SPOTIFY_QR_PAIRING.md. Local music remains available."
            else -> pairingIssueMessage(state.issue)
        })
        if (state.phase == PairingPhase.WAITING) {
            Text("Waiting for authorization · ${state.remainingSeconds / 60}:${(state.remainingSeconds % 60).toString().padStart(2, '0')} remaining", modifier = Modifier.testTag("pairing_countdown"))
            state.issue?.let { Text(pairingIssueMessage(it), color = MaterialTheme.colorScheme.error) }
        }
        if (state.phase in setOf(PairingPhase.GENERATING, PairingPhase.CONNECTING)) CircularProgressIndicator()
        if (state.phase != PairingPhase.CONNECTED) Text("Only approve a QR you requested on this display. No PIN or code transfer is needed.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.phase in setOf(PairingPhase.WAITING, PairingPhase.EXPIRED, PairingPhase.ERROR)) Button(onClick = onRetry, modifier = Modifier.heightIn(min = 64.dp)) { Text("Generate new QR") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 64.dp)) { Text(if (state.phase == PairingPhase.CONNECTED) "Continue to devices" else "Cancel") }
        }
    }
}

@Composable
private fun PairingCode(url: String, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, url) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val matrix = PairingQr.encode(url)
                val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) Image(bitmap!!, "Spotify pairing QR code", modifier.testTag("pairing_qr"))
    else Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

private fun pairingIssueMessage(issue: PairingIssue?): String = when (issue) {
    PairingIssue.NETWORK -> "Connection interrupted. While waiting, AutoHMI retries automatically. If this attempt ends, generate a new QR."
    PairingIssue.RATE_LIMITED -> "The pairing service asked AutoHMI to wait. Waiting polls resume after its retry interval."
    PairingIssue.DECLINED -> "Access was declined on the phone. Generate a new QR when you are ready."
    PairingIssue.CONFIGURATION -> "Check the relay configuration and the registered Spotify callback, then rebuild if configuration changed."
    PairingIssue.BINDING -> "The pairing response could not be verified. Generate a new QR."
    PairingIssue.REDEEMED -> "The one-use authorization delivery could not finish. For your security, generate a new QR."
    PairingIssue.AUTH_FAILED -> "Spotify authorization could not finish. Check the registered callback and allowed test users, then generate a new QR."
    PairingIssue.STORAGE -> "Secure session storage is unavailable. Try again after resolving device storage."
    PairingIssue.BUSY -> "Another authorization or saved session is active. Return to Spotify and finish or cancel it first."
    else -> "The pairing service is unavailable or returned an unexpected response. Generate a new QR to retry."
}
