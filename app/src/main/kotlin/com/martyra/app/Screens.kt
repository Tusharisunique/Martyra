package com.martyra.app

import android.view.HapticFeedbackConstants
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val White = Color.White
private val Black = Color.Black
private val Dim = White.copy(alpha = 0.4f)

@Composable
fun ProfileScreen(onContinue: (String) -> Unit, permissionDenied: Boolean) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val ok = trimmed.length in 1..40
    val view = LocalView.current

    Column(
        Modifier
            .fillMaxSize()
            .background(Black)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(96.dp)
                .drawBehind {
                    val cx = size.width / 2
                    val cy = size.height / 2
                    val sc = size.width / 271f
                    drawOval(
                        color = White,
                        topLeft = Offset(cx - 42.5f * sc, cy - 36f * sc),
                        size = Size(17f * sc, 22f * sc)
                    )
                    drawOval(
                        color = White,
                        topLeft = Offset(cx + 14.5f * sc, cy - 36f * sc),
                        size = Size(17f * sc, 22f * sc)
                    )
                    val p = Path().apply {
                        moveTo(cx - 29f * sc, cy + 12f * sc)
                        lineTo(cx, cy + 38f * sc)
                        lineTo(cx + 29f * sc, cy + 12f * sc)
                    }
                    drawPath(
                        p,
                        White,
                        style = Stroke(
                            width = 8f * sc,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }
        )

        Spacer(Modifier.height(48.dp))

        BasicTextField(
            value = name,
            onValueChange = { if (it.length <= 40) name = it },
            textStyle = TextStyle(color = White, fontSize = 16.sp, textDecoration = TextDecoration.Underline),
            cursorBrush = SolidColor(White),
            decorationBox = { inner ->
                Box {
                    if (name.isEmpty()) {
                        BasicText(
                            "Your name",
                            style = TextStyle(color = Dim, fontSize = 16.sp, textDecoration = TextDecoration.Underline)
                        )
                    }
                    inner()
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        BasicText(
            "Camera and microphone are required. Location and notifications are optional.",
            style = TextStyle(color = Dim, fontSize = 14.sp)
        )

        if (permissionDenied) {
            Spacer(Modifier.height(8.dp))
            BasicText(
                "Camera and microphone access is required.",
                style = TextStyle(color = White, fontSize = 16.sp)
            )
        }

        Spacer(Modifier.height(32.dp))

        Box(
            Modifier
                .border(1.dp, if (ok) White else Dim, RoundedCornerShape(50))
                .padding(horizontal = 32.dp, vertical = 12.dp)
                .clickable(
                    enabled = ok,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onContinue(trimmed)
                }
        ) {
            BasicText("Continue", style = TextStyle(color = if (ok) White else Dim, fontSize = 16.sp))
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
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onSurfaceReady(holder.surface)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onSurfaceDestroyed()
                            }

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
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 48.dp)
                )
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row {
                    BasicText(
                        "Video",
                        style = TextStyle(color = if (!photoMode && !recording) White else Dim, fontSize = 14.sp),
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable(
                                enabled = !recording,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { photoMode = false }
                    )
                    BasicText(
                        "Photo",
                        style = TextStyle(color = if (photoMode && !recording) White else Dim, fontSize = 14.sp),
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable(
                                enabled = !recording,
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { photoMode = true }
                    )
                }

                Spacer(Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .drawBehind {
                            drawCircle(White, size.minDimension / 2 - 4.dp.toPx(), style = Stroke(4.dp.toPx()))
                        }
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            if (photoMode) onPhoto() else onStartStop()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        photoMode -> Box(Modifier.size(44.dp).background(White, CircleShape))
                        recording -> Box(Modifier.size(28.dp).background(White, RoundedCornerShape(6.dp)))
                    }
                }
            }
        }
    }
}
