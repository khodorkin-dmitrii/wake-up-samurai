package com.yavin.wakeupsamurai.ui.ringing

import android.content.res.Configuration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yavin.wakeupsamurai.R
import com.yavin.wakeupsamurai.ui.theme.WakeUpSamuraiTheme
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun DismissDragPad(
    onDismiss: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val dismissLabel = stringResource(R.string.dismiss)
    val dismissDragDescription = stringResource(R.string.dismiss_drag_description)
    val thumbDiameter = 72.dp
    val targetDiameter = 96.dp
    val targetEdgePadding = 12.dp
    val minimumTargetDistance = 160.dp
    val thumbRadiusPx = with(density) { thumbDiameter.toPx() / 2f }
    val targetRadiusPx = with(density) { targetDiameter.toPx() / 2f }
    val edgePaddingPx = with(density) { targetEdgePadding.toPx() }
    val minimumTargetDistancePx = with(density) { minimumTargetDistance.toPx() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var isDragging by remember { mutableStateOf(false) }
    var targetCenter by remember { mutableStateOf<Offset?>(null) }
    var lightningPaths by remember { mutableStateOf(emptyList<DismissLightningPath>()) }
    val targetRandom = remember { Random(System.nanoTime()) }
    val center = Offset(padSize.width / 2f, padSize.height / 2f)
    LaunchedEffect(padSize) {
        if (padSize.width > 0 && padSize.height > 0) {
            targetCenter = randomDismissTargetCenter(
                padSize = padSize,
                targetRadius = targetRadiusPx,
                edgePadding = edgePaddingPx,
                minimumDistance = minimumTargetDistancePx,
                random = targetRandom,
            )
        }
    }
    LaunchedEffect(targetCenter) {
        if (targetCenter == null) {
            lightningPaths = emptyList()
        } else {
            val targetDistance = (targetCenter!! - center).getDistance()
            lightningPaths = createDismissLightningPaths(
                random = targetRandom,
                targetDistance = targetDistance,
                minimumTargetDistance = minimumTargetDistancePx,
            )
            lightningPaths.forEachIndexed { index, initialPath ->
                launch {
                    var path = initialPath
                    while (currentCoroutineContext().isActive) {
                        delay(path.geometryRefreshMillis)
                        path = path.withRandomizedBends(
                            random = targetRandom,
                            targetDistance = targetDistance,
                            minimumTargetDistance = minimumTargetDistancePx,
                        )
                        lightningPaths = lightningPaths.toMutableList().also { it[index] = path }
                    }
                }
            }
        }
    }
    val dragVisualOffset by animateOffsetAsState(
        targetValue = dragOffset,
        animationSpec = if (isDragging) snap() else spring(),
        label = "Dismiss drag position",
    )

    val thumbCenter = center + dragVisualOffset
    val isOverTarget = targetCenter?.let {
        isDismissTargetReached(thumbCenter, it, targetRadiusPx)
    } == true
    val targetScale by animateFloatAsState(
        targetValue = if (isOverTarget) 1.08f else 1f,
        animationSpec = spring(),
        label = "Dismiss target scale",
    )
    val infiniteTransition = rememberInfiniteTransition(label = "Dismiss target pulse")
    val targetGradientFraction by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "Dismiss target gradient fraction",
    )
    val linePulses = buildList {
        lightningPaths.forEachIndexed { index, path ->
            add(
                infiniteTransition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(
                            durationMillis = path.pulseDurationMillis,
                            delayMillis = path.pulseStartDelayMillis,
                            easing = FastOutSlowInEasing,
                        ),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "Dismiss lightning $index pulse",
                ).value,
            )
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val targetGradient = Brush.radialGradient(
        colors = listOf(
            androidx.compose.ui.graphics.lerp(primary, Color.Magenta, targetGradientFraction),
            primary,
        ),
    )
    Box(
        modifier = modifier
            .heightIn(min = 160.dp)
            .onSizeChanged { padSize = it }
            .semantics(mergeDescendants = true) {
                contentDescription = dismissDragDescription
                onClick(label = dismissLabel) {
                    if (enabled) onDismiss()
                    enabled
                }
            }
            .pointerInput(enabled, padSize) {
                detectDragGestures(
                    onDragStart = { startOffset ->
                        if (enabled) {
                            val thumbCenter = center + dragVisualOffset
                            isDragging = (startOffset - thumbCenter).getDistance() <= thumbRadiusPx + edgePaddingPx
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        dragOffset = Offset.Zero
                    },
                    onDragEnd = {
                        if (isDragging && targetCenter?.let {
                                isDismissTargetReached(center + dragOffset, it, targetRadiusPx)
                            } == true
                        ) {
                            onDismiss()
                        }
                        isDragging = false
                        dragOffset = Offset.Zero
                    },
                    onDrag = { _, dragAmount ->
                        if (isDragging) {
                            dragOffset = (dragOffset + dragAmount).coerceToDismissPad(padSize, thumbRadiusPx)
                        }
                    },
                )
            },
    ) {
        targetCenter?.let { target ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                lightningPaths.forEachIndexed { index, lightningPath ->
                    val linePulse = linePulses[index]
                    val lineBrush = Brush.linearGradient(
                        colors = listOf(
                            primary.copy(alpha = 0.28f * linePulse),
                            Color.Magenta.copy(alpha = 0.8f * linePulse),
                            primary.copy(alpha = 0.52f * linePulse),
                        ),
                        start = thumbCenter,
                        end = target,
                    )
                    val strokeWidth =
                        (1.dp.toPx() + 3.dp.toPx() * linePulse) * lightningPath.widthMultiplier
                    val lightningPathGeometry = lightningPath.toPath(thumbCenter, target)
                    drawPath(
                        path = lightningPathGeometry,
                        brush = lineBrush,
                        alpha = lightningPath.alpha * 0.18f * linePulse,
                        style = Stroke(
                            width = strokeWidth * 3.5f,
                            cap = StrokeCap.Round,
                        ),
                    )
                    drawPath(
                        path = lightningPathGeometry,
                        brush = lineBrush,
                        alpha = lightningPath.alpha * linePulse,
                        style = Stroke(
                            width = strokeWidth,
                            cap = StrokeCap.Round,
                        ),
                    )
                    drawPath(
                        path = lightningPathGeometry,
                        color = Color.White.copy(alpha = 0.88f * linePulse),
                        style = Stroke(
                            width = strokeWidth * 0.32f,
                            cap = StrokeCap.Round,
                        ),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = (target.x - targetRadiusPx).toInt(),
                            y = (target.y - targetRadiusPx).toInt(),
                        )
                    }
                    .size(targetDiameter)
                    .graphicsLayer {
                        scaleX = targetScale
                        scaleY = targetScale
                    }
                    .clip(CircleShape)
                    .background(targetGradient)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (isOverTarget) 0.92f else 0.62f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.86f)),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(dragVisualOffset.x.toInt(), dragVisualOffset.y.toInt()) }
                .size(thumbDiameter)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dismissLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Text(
            text = stringResource(R.string.drag_to_dismiss),
            modifier = Modifier.align(Alignment.BottomCenter),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Offset.coerceToDismissPad(padSize: IntSize, thumbRadius: Float): Offset {
    val maxX = (padSize.width / 2f - thumbRadius).coerceAtLeast(0f)
    val maxY = (padSize.height / 2f - thumbRadius).coerceAtLeast(0f)
    return Offset(x = x.coerceIn(-maxX, maxX), y = y.coerceIn(-maxY, maxY))
}

internal fun isDismissTargetReached(
    thumbCenter: Offset,
    targetCenter: Offset,
    targetRadius: Float,
): Boolean = (thumbCenter - targetCenter).getDistance() <= targetRadius

internal fun randomDismissTargetCenter(
    padSize: IntSize,
    targetRadius: Float,
    edgePadding: Float,
    minimumDistance: Float,
    random: Random,
): Offset {
    val center = Offset(padSize.width / 2f, padSize.height / 2f)
    if (padSize.width <= 0 || padSize.height <= 0) return center
    val minX = (targetRadius + edgePadding).coerceAtMost(center.x)
    val maxX = (padSize.width - targetRadius - edgePadding).coerceAtLeast(center.x)
    val minY = (targetRadius + edgePadding).coerceAtMost(center.y)
    val maxY = (padSize.height - targetRadius - edgePadding).coerceAtLeast(center.y)
    var furthestCandidate = center
    repeat(32) {
        val candidate = Offset(
            x = minX + (maxX - minX) * random.nextFloat(),
            y = minY + (maxY - minY) * random.nextFloat(),
        )
        if ((candidate - center).getDistance() >= minimumDistance) return candidate
        if ((candidate - center).getDistance() > (furthestCandidate - center).getDistance()) {
            furthestCandidate = candidate
        }
    }
    return furthestCandidate
}

internal data class DismissLightningPath(
    val bends: List<DismissLightningBend>,
    val segmentCount: Int,
    val baseBendAmplitude: Float,
    val bendAmplitude: Float,
    val widthMultiplier: Float,
    val alpha: Float,
    val pulseDurationMillis: Int,
    val pulseStartDelayMillis: Int,
    val geometryRefreshMillis: Long,
)

internal data class DismissLightningBend(
    val fraction: Float,
    val perpendicularOffset: Float,
)

internal fun createDismissLightningPaths(
    random: Random,
    targetDistance: Float,
    minimumTargetDistance: Float,
): List<DismissLightningPath> =
    List(6) { index ->
        createDismissLightningPath(
            index = index,
            random = random,
            targetDistance = targetDistance,
            minimumTargetDistance = minimumTargetDistance,
        )
    }

private fun createDismissLightningPath(
    index: Int,
    random: Random,
    targetDistance: Float,
    minimumTargetDistance: Float,
): DismissLightningPath {
    val segmentCount = if (index == 0) 5 else random.nextInt(from = 3, until = 6)
    val baseBendAmplitude = if (index == 0) 36f else 18f + random.nextFloat() * 20f
    val bendAmplitude = scaledLightningBendAmplitude(
        baseAmplitude = baseBendAmplitude,
        targetDistance = targetDistance,
        minimumTargetDistance = minimumTargetDistance,
    )
    return DismissLightningPath(
        bends = createDismissLightningBends(segmentCount, bendAmplitude, random),
        segmentCount = segmentCount,
        baseBendAmplitude = baseBendAmplitude,
        bendAmplitude = bendAmplitude,
        widthMultiplier = if (index == 0) 1f else 0.32f + random.nextFloat() * 0.24f,
        alpha = if (index == 0) 1f else 0.32f + random.nextFloat() * 0.28f,
        pulseDurationMillis = random.nextInt(from = 750, until = 1_051),
        pulseStartDelayMillis = random.nextInt(from = 0, until = 241),
        geometryRefreshMillis = random.nextLong(from = 850, until = 1_151),
    )
}

private fun DismissLightningPath.withRandomizedBends(
    random: Random,
    targetDistance: Float,
    minimumTargetDistance: Float,
): DismissLightningPath {
    val updatedAmplitude = scaledLightningBendAmplitude(
        baseAmplitude = baseBendAmplitude,
        targetDistance = targetDistance,
        minimumTargetDistance = minimumTargetDistance,
    )
    return copy(
        bends = createDismissLightningBends(segmentCount, updatedAmplitude, random),
        bendAmplitude = updatedAmplitude,
    )
}

internal fun scaledLightningBendAmplitude(
    baseAmplitude: Float,
    targetDistance: Float,
    minimumTargetDistance: Float,
): Float {
    val distanceScale = (targetDistance / minimumTargetDistance.coerceAtLeast(1f)).coerceIn(1f, 2.25f)
    return baseAmplitude * distanceScale
}

private fun createDismissLightningBends(
    segmentCount: Int,
    bendAmplitude: Float,
    random: Random,
): List<DismissLightningBend> =
    (1 until segmentCount).map { segment ->
        DismissLightningBend(
            fraction = (segment.toFloat() / segmentCount) + (random.nextFloat() - 0.5f) * 0.08f,
            perpendicularOffset = (random.nextFloat() * 2f - 1f) * bendAmplitude,
        )
    }

private fun DismissLightningPath.toPath(start: Offset, end: Offset): Path {
    val direction = end - start
    val distance = direction.getDistance()
    if (distance == 0f) return Path().apply { moveTo(start.x, start.y) }
    val normal = Offset(x = -direction.y / distance, y = direction.x / distance)
    return Path().apply {
        moveTo(start.x, start.y)
        bends.forEach { bend ->
            val point = start + direction * bend.fraction + normal * bend.perpendicularOffset
            lineTo(point.x, point.y)
        }
        lineTo(end.x, end.y)
    }
}

@Preview(name = "Dismiss drag pad light", showBackground = true)
@Composable
private fun DismissDragPadLightPreview() {
    WakeUpSamuraiTheme(darkTheme = false) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            DismissDragPad(onDismiss = {}, enabled = true, modifier = Modifier.fillMaxSize())
        }
    }
}

@Preview(
    name = "Dismiss drag pad dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DismissDragPadDarkPreview() {
    WakeUpSamuraiTheme(darkTheme = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            DismissDragPad(onDismiss = {}, enabled = true, modifier = Modifier.fillMaxSize())
        }
    }
}
