package com.yavin.wakeupsamurai.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.yavin.wakeupsamurai.domain.theme.AppThemeMode
import kotlin.math.hypot
import kotlinx.coroutines.launch

@Composable
fun ThemeRevealTransition(
    themeMode: AppThemeMode,
    darkTheme: Boolean,
    onCycleThemeMode: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (requestThemeChange: (Offset) -> Unit) -> Unit,
) {
    val recordedContentLayer = rememberGraphicsLayer()
    val revealProgress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val currentThemeMode by rememberUpdatedState(themeMode)
    val currentDarkTheme by rememberUpdatedState(darkTheme)
    val currentCycleThemeMode by rememberUpdatedState(onCycleThemeMode)

    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var previousFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var revealOrigin by remember { mutableStateOf(Offset.Zero) }
    var maxRevealRadius by remember { mutableStateOf(0f) }
    var previousThemeMode by remember { mutableStateOf(themeMode) }
    var previousDarkTheme by remember { mutableStateOf(darkTheme) }
    var capturedSize by remember { mutableStateOf(IntSize.Zero) }
    var transitionRunning by remember { mutableStateOf(false) }
    var transitionGeneration by remember { mutableIntStateOf(0) }

    fun clearTransition() {
        previousFrame = null
        transitionRunning = false
    }

    val requestThemeChange: (Offset) -> Unit = request@{ requestedOrigin ->
        if (transitionRunning) return@request
        val sizeAtRequest = rootSize
        if (sizeAtRequest == IntSize.Zero) {
            currentCycleThemeMode()
            return@request
        }

        transitionRunning = true
        val generation = ++transitionGeneration
        coroutineScope.launch {
            val capturedFrame = runCatching { recordedContentLayer.toImageBitmap() }.getOrNull()
            if (generation != transitionGeneration) return@launch
            if (capturedFrame == null) {
                transitionRunning = false
                currentCycleThemeMode()
                return@launch
            }

            val contentSize = Size(sizeAtRequest.width.toFloat(), sizeAtRequest.height.toFloat())
            val origin = resolveRevealOrigin(requestedOrigin, contentSize)
            previousThemeMode = currentThemeMode
            previousDarkTheme = currentDarkTheme
            capturedSize = sizeAtRequest
            revealOrigin = origin
            maxRevealRadius = calculateRevealRadius(origin, contentSize)
            revealProgress.snapTo(0f)
            previousFrame = capturedFrame
            currentCycleThemeMode()
        }
    }

    LaunchedEffect(themeMode, previousFrame, rootSize) {
        val capturedFrame = previousFrame ?: return@LaunchedEffect
        if (themeMode == previousThemeMode) return@LaunchedEffect
        if (rootSize != capturedSize || darkTheme == previousDarkTheme) {
            clearTransition()
            return@LaunchedEffect
        }

        withFrameNanos { }
        revealProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = THEME_REVEAL_DURATION_MS, easing = FastOutSlowInEasing),
        )
        if (previousFrame === capturedFrame) clearTransition()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { newSize ->
                if (rootSize != IntSize.Zero && rootSize != newSize && transitionRunning) {
                    transitionGeneration++
                    clearTransition()
                }
                rootSize = newSize
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    recordedContentLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(recordedContentLayer)
                },
        ) {
            content(requestThemeChange)
        }

        previousFrame?.let { capturedFrame ->
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            ) {
                drawImage(capturedFrame)
                drawCircle(
                    color = Color.Transparent,
                    radius = maxRevealRadius * revealProgress.value,
                    center = revealOrigin,
                    blendMode = BlendMode.Clear,
                )
            }
        }
    }
}

private fun resolveRevealOrigin(origin: Offset, size: Size): Offset {
    val fallback = Offset(size.width * 0.9f, size.height * 0.1f)
    val resolved = origin.takeIf { it.isFinite } ?: fallback
    return Offset(resolved.x.coerceIn(0f, size.width), resolved.y.coerceIn(0f, size.height))
}

private fun calculateRevealRadius(origin: Offset, size: Size): Float = maxOf(
    hypot(origin.x, origin.y),
    hypot(size.width - origin.x, origin.y),
    hypot(origin.x, size.height - origin.y),
    hypot(size.width - origin.x, size.height - origin.y),
)

private val Offset.isFinite: Boolean
    get() = x.isFinite() && y.isFinite()

private const val THEME_REVEAL_DURATION_MS = 550
