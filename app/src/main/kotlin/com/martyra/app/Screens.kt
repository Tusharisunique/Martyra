package com.martyra.app

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView

private val White = Color(0xFFFFFFFF)
private val Black = Color(0xFF000000)
private val Dim = White.copy(alpha = 0.4f)

@Composable
fun ProfileScreen(onContinue: (String) -> Unit, permissionDenied: Boolean) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val enabled = trimmed.length in 1..40

    Column(
        modifier = Modifier.fillMaxSize().background(Black).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        androidx.compose.ui.graphics.vector.Image(
            imageVector = androidx.compose.ui.res.vectorResource(R.drawable.logo),
            contentDescription = "Martyra",
            modifier = Modifier.size(96.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(White)
        )
        Spacer(Modifier.height(48.dp))
        BasicTextField(
            value = name,
            onValueChange = { if (it.length <= 40) name = it },
            textStyle = TextStyle(color = White, fontSize = 16.sp, textDecoration = TextDecoration.Underline),
            cursorBrush = SolidColor(White),
            decorationBox = { inner ->
                Box {
                    if (name.isEmpty()) BasicText(
                        "Your name",
                        style = TextStyle(color = Dim, fontSize = 16.sp, textDecoration = TextDecoration.Underline)
                    )
                    inner()
                }
            }
        )
        Spacer(Modifier.height(16.dp))
        BasicText(
            "Camera and microphone are required. Location and notifications are optional.",
            style = TextStyle(color = Dim, fontSize = 14.sp)
        )
        Spacer(Modifier.height(8.dp))
        if (permissionDenied) BasicText(
            "Camera and microphone access is required.",
            style = TextStyle(color = White, fontSize = 14.sp)
        )
        Spacer(Modifier.height(32.dp))
        val view = LocalView.current
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .border(1.dp, if (enabled) White else Dim, RoundedCornerShape(50))
                .padding(horizontal = 32.dp, vertical = 12.dp)
                .clickable(
                    enabled = enabled,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onContinue(trimmed)
                }
        ) {
            BasicText("Continue", style = TextStyle(color = if (enabled) White else Dim, fontSize = 16.sp))
        }
    }
}

@Composable
fun MainScreen(
    rooted: Boolean,
    recording: Boolean,
    elapsedMs: Long,
    onBind: (PreviewView) -> Unit,
    onStartStop: () -> Unit,
    onPhoto: () -> Unit
) {
    var photoMode by remember { mutableStateOf(false) }
    val view = LocalView.current

    Box(Modifier.fillMaxSize().background(Black)) {
        if (rooted) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BasicText(
                    "This device is not supported.",
                    style = TextStyle(color = White, fontSize = 16.sp)
                )
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).also { pv ->
                        pv.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        onBind(pv)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (recording) {
                val secs = elapsedMs / 1000
                val mm = secs / 60
                val ss = secs % 60
                BasicText(
                    "%02d:%02d".format(mm, ss),
                    style = TextStyle(color = White, fontSize = 20.sp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
                )
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (!recording) {
                    androidx.compose.foundation.layout.Row {
                        BasicText(
                            "Video",
                            style = TextStyle(color = if (!photoMode) White else Dim, fontSize = 14.sp),
                            modifier = Modifier.padding(8.dp).clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { photoMode = false }
                        )
                        BasicText(
                            "Photo",
                            style = TextStyle(color = if (photoMode) White else Dim, fontSize = 14.sp),
                            modifier = Modifier.padding(8.dp).clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { photoMode = true }
                        )
                    }
                } else {
                    androidx.compose.foundation.layout.Row {
                        BasicText("Video", style = TextStyle(color = Dim, fontSize = 14.sp), modifier = Modifier.padding(8.dp))
                        BasicText("Photo", style = TextStyle(color = Dim, fontSize = 14.sp), modifier = Modifier.padding(8.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(72.dp)
                        .drawBehind {
                            drawCircle(White, radius = size.minDimension / 2 - 4.dp.toPx(), style = Stroke(width = 4.dp.toPx()))
                        }
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            if (photoMode) onPhoto() else onStartStop()
                        }
                ) {
                    when {
                        photoMode -> Box(Modifier.size(44.dp).background(White, CircleShape))
                        recording -> Box(
                            Modifier.size(28.dp).background(White, RoundedCornerShape(6.dp))
                        )
                    }
                }
            }
        }
    }
}
