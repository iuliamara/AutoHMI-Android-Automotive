package com.example.autohmi.navigation.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.autohmi.navigation.NavigationSpeed
import com.example.autohmi.navigation.DemoDriveStatus
import kotlin.math.roundToInt

/** Read-only HUD. Only the ring animates; digits and accessibility always expose current speed. */
@Composable
internal fun NavigationSpeedometer(reading: NavigationSpeed, modifier: Modifier = Modifier.size(126.dp)) {
    val speed = reading.speedKmh?.toFloat()
    val arcFraction by animateFloatAsState(
        targetValue = ((speed ?: 0f) / DIAL_MAX_KMH).coerceIn(0f, 1f),
        animationSpec = tween(350, easing = FastOutSlowInEasing), label = "demo_speed_arc"
    )
    BoxWithConstraints(modifier.aspectRatio(1f).shadow(6.dp, CircleShape).clip(CircleShape)
        .testTag("demo_speedometer").semantics(mergeDescendants = true) {
            contentDescription = "${reading.source.name} speedometer"
            stateDescription = when (reading.demoStatus) {
                DemoDriveStatus.RUNNING -> "Running"
                DemoDriveStatus.PAUSED -> "Paused"
                DemoDriveStatus.ARRIVED -> "Arrived"
                null -> if (speed == null) "Speed unavailable" else "GPS measurement"
            } + ". Ring scale 0 to 120 km/h"
            progressBarRangeInfo = if (speed == null) ProgressBarRangeInfo.Indeterminate
                else ProgressBarRangeInfo(speed.coerceIn(0f, DIAL_MAX_KMH), 0f..DIAL_MAX_KMH)
        }, contentAlignment = Alignment.Center) {
        val typeScale = maxWidth.value / 112f
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2
            drawCircle(Brush.radialGradient(
                listOf(Color(0xFF193347), Color(0xFF0A1829), Color(0xFF050C17)),
                center = Offset(size.width * 0.36f, size.height * 0.22f), radius = radius * 1.7f
            ))
            drawCircle(Color(0xFF426078).copy(alpha = 0.65f), radius - 1.dp.toPx(), style = Stroke(0.8.dp.toPx()))
            drawCircle(Color(0xFF60C8F4).copy(alpha = 0.12f), radius - 10.dp.toPx(), style = Stroke(0.7.dp.toPx()))
            drawCircle(Color(0xFF7C9BB5).copy(alpha = 0.10f), radius - 15.dp.toPx(), style = Stroke(0.5.dp.toPx()))
            val inset = 5.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            // The dim full circle is the scale track. The illuminated sweep is actual speed / 120.
            drawCircle(Color(0xFF21435A), radius - inset, style = Stroke(1.3.dp.toPx()))
            if (arcFraction > 0f) {
                drawArc(Color(0xFF36C5FF).copy(alpha = 0.18f), -90f, 360f * arcFraction, false,
                    Offset(inset, inset), arcSize, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                drawArc(Color(0xFF64DCFF), -90f, 360f * arcFraction, false,
                    Offset(inset, inset), arcSize, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(reading.source.name, Modifier.width((56f * typeScale).dp).testTag("demo_speed_label"), color = Color(0xFFA2D9EC),
                fontSize = (9f * typeScale).sp, lineHeight = (11f * typeScale).sp,
                letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
            Text(speed?.roundToInt()?.toString() ?: "--", Modifier.width((80f * typeScale).dp).testTag("demo_speed_value"), color = Color.White,
                fontSize = (36f * typeScale).sp, lineHeight = (44f * typeScale).sp,
                fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
            Text("km/h", Modifier.width((48f * typeScale).dp).testTag("demo_speed_unit"), color = Color(0xFFB8C7D7),
                fontSize = (10f * typeScale).sp, lineHeight = (13f * typeScale).sp,
                letterSpacing = 0.7.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
        }
    }
}

private const val DIAL_MAX_KMH = 120f
