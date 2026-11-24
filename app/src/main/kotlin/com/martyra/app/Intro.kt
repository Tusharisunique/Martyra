package com.martyra.app

import android.content.Context
import android.graphics.Typeface
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun frac(t: Float, s: Float, e: Float) = ((t - s) / (e - s)).coerceIn(0f, 1f)

// Deceleration easing (smooth stop, no bounce)
private val decel = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/** Load Gondens font; fall back gracefully if file is absent. */
fun loadGondens(ctx: Context): FontFamily {
    return try {
        FontFamily(Typeface.createFromAsset(ctx.assets, "gondens.otf"))
    } catch (_: Exception) { FontFamily.Default }
}

/** Returns true when the system animator scale is 0 (Reduce Motion). */
private fun isReducedMotion(ctx: Context): Boolean {
    val scale = android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return scale == 0f
}

/**
 * Opening animation (UI-1 to UI-10).
 * [onDone] is called when the animation finishes or is skipped.
 */
@Composable
fun IntroScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val reduced = remember { isReducedMotion(ctx) }
    val gondens = remember { loadGondens(ctx) }

    // One animated clock: 0 to 2600 ms
    val clock = remember { Animatable(0f) }
    var done by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (reduced) {
            kotlinx.coroutines.delay(600L)
        } else {
            clock.animateTo(2600f, animationSpec = tween(durationMillis = 2600, easing = LinearEasing))
        }
        done = true
        onDone()
    }

    if (done) return

    val t = if (reduced) 2000f else clock.value  // show final frame for reduced motion

    // Derive each segment's progress from the single clock value
    val eyeAlpha = decel.transform(frac(t, 200f, 500f))
    val eyeScale = 0.9f + 0.1f * decel.transform(frac(t, 200f, 500f))
    val mouthProgress = decel.transform(frac(t, 450f, 850f))

    // Blinks: scale Y of eyes about their center
    val blink1 = frac(t, 1000f, 1180f).let { p ->
        val half = if (p < 0.5f) p * 2f else (1f - p) * 2f
        1f - 0.95f * decel.transform(half)
    }
    val blink2 = frac(t, 1450f, 1630f).let { p ->
        val half = if (p < 0.5f) p * 2f else (1f - p) * 2f
        1f - 0.95f * decel.transform(half)
    }
    val eyeScaleY = eyeScale * minOf(blink1, blink2)

    val wordAlpha = decel.transform(frac(t, 1500f, 1950f))
    val wordRise = 8.dp * (1f - decel.transform(frac(t, 1500f, 1950f)))
    // Letter spacing tightens: wide (0.3em) to normal (0.0em)
    val letterSpacing = 0.3f * (1f - decel.transform(frac(t, 1500f, 1950f)))

    // Fade out everything 2300–2600
    val fadeOut = decel.transform(frac(t, 2300f, 2600f))
    val globalAlpha = 1f - fadeOut

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(136.dp, 143.dp).graphicsLayer { alpha = globalAlpha * eyeAlpha }) {
                // Logo geometry: viewport 271x286, scaled to canvas
                val sx = size.width / 271f; val sy = size.height / 286f

                // Eyes: ellipses at x 111/157, y 114, radii 8.5x11; blink by scaleY about center
                withTransform({
                    scale(1f, eyeScaleY, pivot = Offset(111f * sx, 114f * sy))
                }) {
                    drawOval(Color.White, topLeft = Offset((111f - 8.5f) * sx, (114f - 11f) * sy), size = Size(17f * sx, 22f * sy))
                }
                withTransform({
                    scale(1f, eyeScaleY, pivot = Offset(157f * sx, 114f * sy))
                }) {
                    drawOval(Color.White, topLeft = Offset((157f - 8.5f) * sx, (114f - 11f) * sy), size = Size(17f * sx, 22f * sy))
                }

                // Mouth: polyline (105,155) -> (134,181) -> (163,155), drawn progressively
                if (mouthProgress > 0f) {
                    val mouthPath = Path().apply {
                        moveTo(105f * sx, 155f * sy)
                        lineTo(134f * sx, 181f * sy)
                        lineTo(163f * sx, 155f * sy)
                    }
                    // PathMeasure to draw partial path
                    val pm = androidx.compose.ui.graphics.PathMeasure().apply { setPath(mouthPath, false) }
                    val partial = Path()
                    pm.getSegment(0f, pm.length * mouthProgress, partial, true)
                    drawPath(partial, Color.White, style = Stroke(width = 8f * sx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }

            Spacer(Modifier.height(16.dp))

            // Wordmark "Martyra" in Gondens
            if (wordAlpha > 0f) {
                androidx.compose.foundation.text.BasicText(
                    text = "Martyra",
                    style = TextStyle(
                        color = Color.White.copy(alpha = globalAlpha * wordAlpha),
                        fontSize = 32.sp,
                        fontFamily = gondens,
                        letterSpacing = letterSpacing.sp
                    ),
                    modifier = Modifier.offset(y = wordRise)
                )
            }
        }
    }
}
