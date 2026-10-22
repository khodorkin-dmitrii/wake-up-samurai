package com.yavin.wakeupsamurai.ui.ringing

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DismissDragPadTest {
    @Test
    fun `releases inside the target dismiss the alarm`() {
        assertTrue(
            isDismissTargetReached(
                thumbCenter = Offset(190f, 100f),
                targetCenter = Offset(200f, 100f),
                targetRadius = 24f,
            ),
        )
    }

    @Test
    fun `releases outside the target return without dismissing`() {
        assertFalse(
            isDismissTargetReached(
                thumbCenter = Offset(150f, 100f),
                targetCenter = Offset(200f, 100f),
                targetRadius = 24f,
            ),
        )
    }

    @Test
    fun `random target stays inside the pad and away from the dismiss control`() {
        val padSize = IntSize(width = 400, height = 600)
        val targetRadius = 48f
        val edgePadding = 12f
        val target = randomDismissTargetCenter(
            padSize = padSize,
            targetRadius = targetRadius,
            edgePadding = edgePadding,
            minimumDistance = 160f,
            random = Random(7),
        )
        val center = Offset(padSize.width / 2f, padSize.height / 2f)

        assertTrue(target.x >= targetRadius + edgePadding)
        assertTrue(target.x <= padSize.width - targetRadius - edgePadding)
        assertTrue(target.y >= targetRadius + edgePadding)
        assertTrue(target.y <= padSize.height - targetRadius - edgePadding)
        assertTrue((target - center).getDistance() >= 160f)
    }

    @Test
    fun `more distant targets create stronger lightning deviations`() {
        val nearAmplitude = scaledLightningBendAmplitude(
            baseAmplitude = 36f,
            targetDistance = 160f,
            minimumTargetDistance = 160f,
        )
        val distantAmplitude = scaledLightningBendAmplitude(
            baseAmplitude = 36f,
            targetDistance = 320f,
            minimumTargetDistance = 160f,
        )

        assertTrue(distantAmplitude > nearAmplitude)
    }
}
