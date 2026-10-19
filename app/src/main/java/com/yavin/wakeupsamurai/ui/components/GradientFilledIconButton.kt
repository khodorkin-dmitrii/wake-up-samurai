package com.yavin.wakeupsamurai.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Adapted from AndroidHandbookAudio's playlist-item playback control. */
@Composable
fun GradientFilledIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    animateGradient: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val animatedFraction = if (enabled && animateGradient) {
        val transition = rememberInfiniteTransition(label = "Playback button gradient")
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "Playback gradient fraction",
        )
    } else {
        null
    }
    val primary = scheme.primary
    val inversePrimary = scheme.inversePrimary

    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .clip(CircleShape)
            .drawBehind {
                val gradient = if (enabled) {
                    Brush.radialGradient(
                        listOf(
                            lerp(primary, Color.Magenta, animatedFraction?.value ?: 0.6f),
                            primary,
                        ),
                    )
                } else {
                    Brush.radialGradient(
                        listOf(
                            inversePrimary.copy(alpha = 0.08f),
                            inversePrimary.copy(alpha = 0.82f),
                        ),
                    )
                }
                drawRect(gradient)
            },
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = scheme.onPrimary,
            disabledContentColor = scheme.onSurface.copy(alpha = 0.58f),
        ),
        content = content,
    )
}
