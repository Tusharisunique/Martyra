package com.martyra.app

import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val White = Color.White
private val Black = Color.Black
private val Dim = White.copy(alpha = 0.4f)
private val Red = Color(0xFFE53935)

private val enterEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val exitEasing  = CubicBezierEasing(0.4f, 0f, 0.8f, 1f)
private val winkCloseEasing = CubicBezierEasing(0.45f, 0f, 0.7f, 1f)
private val winkOpenEasing  = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** Winking face logo rendered entirely in a single Canvas. */
@Composable
fun WinkFace(sizeDp: Float = 88f) {
    val tl = rememberInfiniteTransition(label = "wink")

    val opacity by tl.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 1600
        0f at 0 using enterEasing; 1f at 300; 1f at 1120 using exitEasing; 0f at 1600
    }, RepeatMode.Restart), "opacity")

    val scale by tl.animateFloat(0.95f, 0.95f, infiniteRepeatable(keyframes {
        durationMillis = 1600
        0.95f at 0 using enterEasing; 1.08f at 300; 1.08f at 1600
    }, RepeatMode.Restart), "scale")

    val winkSpec: (Float, Float) -> KeyframesSpec<Float> = { open, closed ->
        keyframes {
            durationMillis = 1600
            open at 0; open at 440 using winkCloseEasing
            closed at 550; closed at 630 using winkOpenEasing
            open at 850; open at 1600
        }
    }

    val wW by tl.animateFloat(14f, 14f, infiniteRepeatable(winkSpec(14f, 20f), RepeatMode.Restart), "wW")
    val wH by tl.animateFloat(18f, 18f, infiniteRepeatable(winkSpec(18f,  4f), RepeatMode.Restart), "wH")
    val wX by tl.animateFloat(0f,  0f,  infiniteRepeatable(winkSpec(0f, -2.4f), RepeatMode.Restart), "wX")
    val wY by tl.animateFloat(0f,  0f,  infiniteRepeatable(winkSpec(0f,  5f), RepeatMode.Restart), "wY")
    val wR by tl.animateFloat(0f,  0f,  infiniteRepeatable(winkSpec(0f, 12f), RepeatMode.Restart), "wR")

    Canvas(
        Modifier
            .size(sizeDp.dp, (sizeDp * 82f / 88f).dp)
            .graphicsLayer { alpha = opacity; scaleX = scale; scaleY = scale }
    ) {
        val sc = size.width / 88f

        // Left eye (static)
        val eyeW = 14f * sc; val eyeH = 18f * sc
        drawRoundRect(
            White,
            topLeft = Offset(17f * sc, 0f),
            size = Size(eyeW, eyeH),
            cornerRadius = CornerRadius(eyeW / 2f, eyeH * 7f / 18f)
        )

        // Right eye (winking) — rotated around its own center
        val rW = wW * sc; val rH = wH * sc
        val rX = (56f + wX) * sc; val rY = wY * sc
        val pivX = rX + rW / 2f; val pivY = rY + rH / 2f
        rotate(wR, pivot = Offset(pivX, pivY)) {
            drawRoundRect(
                White,
                topLeft = Offset(rX, rY),
                size = Size(rW, rH),
                cornerRadius = CornerRadius(rW / 2f, rH * 7f / 18f)
            )
        }

        // Smile
        val mOX = 16.5f * sc; val mOY = 41.5f * sc
        val mW = 55f * sc; val mH = 27f * sc
        val mouth = Path().apply {
            moveTo(mOX + 3.5f / 55f * mW,  mOY + 3.5f / 27f * mH)
            lineTo(mOX + 27.5f / 55f * mW, mOY + 23.5f / 27f * mH)
            lineTo(mOX + 51.5f / 55f * mW, mOY + 3.5f / 27f * mH)
        }
        drawPath(mouth, White, style = Stroke(7f * sc, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun ProfileScreen(onContinue: (String) -> Unit, permissionDenied: Boolean) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val ok = trimmed.length in 1..40
    val view = LocalView.current

    Box(Modifier.fillMaxSize().background(Black), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            WinkFace(88f)

            Spacer(Modifier.height(10.dp))

            BasicText(
                "martyra",
                style = TextStyle(color = White, fontSize = 18.sp, fontWeight = FontWeight.W300, letterSpacing = 6.sp)
            )

            Spacer(Modifier.height(52.dp))

            // Name field — bottom-border only
            Box(
                Modifier.fillMaxWidth()
                    .drawBehind {
                        drawLine(White, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                    }
                    .padding(bottom = 8.dp)
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    textStyle = TextStyle(color = White, fontSize = 18.sp),
                    cursorBrush = SolidColor(White),
                    singleLine = true,
                    decorationBox = { inner ->
                        Box {
                            if (name.isEmpty()) BasicText("Your name", style = TextStyle(color = Dim, fontSize = 18.sp))
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))

            BasicText(
                "Camera and microphone are required. Location and notifications are optional.",
                style = TextStyle(color = Dim, fontSize = 13.sp, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth()
            )

            if (permissionDenied) {
                Spacer(Modifier.height(8.dp))
                BasicText(
                    "Camera and microphone access is required.",
                    style = TextStyle(color = White, fontSize = 14.sp, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(40.dp))

            Box(
                Modifier
                    .border(1.dp, if (ok) White else Dim, RoundedCornerShape(3.dp))
                    .clickable(
                        enabled = ok, indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onContinue(trimmed)
                    }
                    .padding(horizontal = 44.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    "Enter",
                    style = TextStyle(color = if (ok) White else Dim, fontSize = 15.sp, letterSpacing = 4.sp)
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    rooted: Boolean,
    recording: Boolean,
    elapsedMs: Long,
    onSurfaceReady: (Surface) -> Unit,
    onSurfaceDestroyed: () -> Unit,
    onStartStop: () -> Unit,
    onPhoto: () -> Unit
) {
    var photoMode by remember { mutableStateOf(false) }
    val view = LocalView.current

    Box(Modifier.fillMaxSize().background(Black)) {
        if (rooted) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText("This device is not supported.", style = TextStyle(color = White, fontSize = 16.sp))
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) { onSurfaceReady(holder.surface) }
                            override fun surfaceDestroyed(holder: SurfaceHolder) { onSurfaceDestroyed() }
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                onSurfaceReady(holder.surface)
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (recording) {
                val s = elapsedMs / 1000
                BasicText(
                    "%02d:%02d".format(s / 60, s % 60),
                    style = TextStyle(color = White, fontSize = 20.sp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
                )
            }

            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row {
                    BasicText(
                        "Video",
                        style = TextStyle(color = if (!photoMode && !recording) White else Dim, fontSize = 14.sp),
                        modifier = Modifier.padding(8.dp).clickable(
                            enabled = !recording, indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { photoMode = false }
                    )
                    BasicText(
                        "Photo",
                        style = TextStyle(color = if (photoMode && !recording) White else Dim, fontSize = 14.sp),
                        modifier = Modifier.padding(8.dp).clickable(
                            enabled = !recording, indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { photoMode = true }
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Shutter button — ring turns RED when recording, pulses on photo tap
                var shutterPressed by remember { mutableStateOf(false) }
                val shutterScale by animateFloatAsState(
                    targetValue = if (shutterPressed) 0.82f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessHigh
                    ),
                    finishedListener = { shutterPressed = false },
                    label = "shutter"
                )

                val ringColor = if (recording) Red else White
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(shutterScale)
                        .drawBehind {
                            drawCircle(ringColor, size.minDimension / 2 - 4.dp.toPx(), style = Stroke(4.dp.toPx()))
                        }
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            shutterPressed = true
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            if (photoMode) onPhoto() else onStartStop()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        photoMode -> Box(Modifier.size(44.dp).background(White, CircleShape))
                        recording -> Box(Modifier.size(28.dp).background(Red, RoundedCornerShape(6.dp)))
                    }
                }
            }
        }
    }
}
