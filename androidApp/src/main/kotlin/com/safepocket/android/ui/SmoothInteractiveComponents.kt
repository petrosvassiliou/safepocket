package com.safepocket.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Seam-free, smooth 180ms hover/focus/press indication for Android & ChromeOS (Googlebook OS).
 *
 * Replaces Android's `RippleShader` / stadium two-cap `RippleDrawable` which produces a visible
 * vertical gray seam line down the center of rounded buttons under mouse hover in ChromeOS ARCVM.
 * Draws a single continuous full-surface overlay clipped cleanly by the parent Material shape.
 */
class SeamFreeIndication(
    private val tintColor: Color
) : IndicationNodeFactory {

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        SeamFreeIndicationNode(interactionSource, tintColor)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeamFreeIndication) return false
        return tintColor == other.tintColor
    }

    override fun hashCode(): Int = tintColor.hashCode()
}

private class SeamFreeIndicationNode(
    private val interactionSource: InteractionSource,
    private val tintColor: Color
) : Modifier.Node(), DrawModifierNode {

    private val overlayAlpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            val activeHovers = mutableSetOf<HoverInteraction.Enter>()
            val activePresses = mutableSetOf<PressInteraction.Press>()
            val activeFocuses = mutableSetOf<FocusInteraction.Focus>()

            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is HoverInteraction.Enter -> activeHovers.add(interaction)
                    is HoverInteraction.Exit -> activeHovers.remove(interaction.enter)
                    is PressInteraction.Press -> activePresses.add(interaction)
                    is PressInteraction.Release -> activePresses.remove(interaction.press)
                    is PressInteraction.Cancel -> activePresses.remove(interaction.press)
                    is FocusInteraction.Focus -> activeFocuses.add(interaction)
                    is FocusInteraction.Unfocus -> activeFocuses.remove(interaction.focus)
                }

                val targetAlpha = when {
                    activePresses.isNotEmpty() -> 0.13f
                    activeHovers.isNotEmpty() -> 0.075f
                    activeFocuses.isNotEmpty() -> 0.09f
                    else -> 0f
                }

                launch {
                    overlayAlpha.animateTo(
                        targetValue = targetAlpha,
                        animationSpec = tween(
                            durationMillis = 180,
                            easing = FastOutSlowInEasing
                        )
                    )
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = overlayAlpha.value
        if (alpha > 0.001f) {
            drawRect(color = tintColor.copy(alpha = alpha))
        }
    }
}

@Composable
fun ProvideSeamFreeHoverIndication(
    content: @Composable () -> Unit
) {
    val tint = MaterialTheme.colorScheme.onSurface
    val indication = remember(tint) { SeamFreeIndication(tint) }
    CompositionLocalProvider(
        LocalIndication provides indication,
        content = content
    )
}

@Composable
fun SmoothPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val baseBg = MaterialTheme.colorScheme.primary
    val fg = MaterialTheme.colorScheme.onPrimary
    val targetBg = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        isPressed -> lerp(baseBg, fg, 0.18f)
        isHovered -> lerp(baseBg, fg, 0.10f)
        else -> baseBg
    }
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "smoothPrimaryBg"
    )
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        interactionSource = interactionSource,
        contentPadding = contentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = animatedBg,
            contentColor = fg
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            hoveredElevation = 1.dp,
            pressedElevation = 0.dp
        ),
        content = content
    )
}

@Composable
fun SmoothTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val baseBg = MaterialTheme.colorScheme.secondaryContainer
    val fg = MaterialTheme.colorScheme.onSecondaryContainer
    val targetBg = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        isPressed -> lerp(baseBg, MaterialTheme.colorScheme.primary, 0.20f)
        isHovered -> lerp(baseBg, MaterialTheme.colorScheme.primary, 0.11f)
        else -> baseBg
    }
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "smoothTonalBg"
    )
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        interactionSource = interactionSource,
        contentPadding = contentPadding,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = animatedBg,
            contentColor = fg
        ),
        elevation = ButtonDefaults.filledTonalButtonElevation(
            defaultElevation = 0.dp,
            hoveredElevation = 0.dp,
            pressedElevation = 0.dp
        ),
        content = content
    )
}

@Composable
fun SmoothOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val baseBg = Color.Transparent
    val hoverTint = MaterialTheme.colorScheme.primary
    val targetBg = when {
        !enabled -> Color.Transparent
        isPressed -> hoverTint.copy(alpha = 0.14f)
        isHovered -> hoverTint.copy(alpha = 0.08f)
        else -> baseBg
    }
    val targetBorder = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        isHovered || isPressed -> hoverTint.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "smoothOutlinedBg"
    )
    val animatedBorder by animateColorAsState(
        targetValue = targetBorder,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "smoothOutlinedBorder"
    )
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        interactionSource = interactionSource,
        contentPadding = contentPadding,
        border = BorderStroke(1.dp, animatedBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = animatedBg,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        content = content
    )
}

@Composable
fun SmoothTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val hoverTint = MaterialTheme.colorScheme.primary
    val targetBg = when {
        !enabled -> Color.Transparent
        isPressed -> hoverTint.copy(alpha = 0.15f)
        isHovered -> hoverTint.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "smoothTextBg"
    )
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        interactionSource = interactionSource,
        colors = ButtonDefaults.textButtonColors(
            containerColor = animatedBg,
            contentColor = hoverTint
        ),
        content = content
    )
}
