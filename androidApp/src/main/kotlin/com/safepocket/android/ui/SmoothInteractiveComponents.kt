package com.safepocket.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * SafePocket v0.3 Professional Zero-Artifact Hover & Press Engine.
 *
 * Eliminates the Material 3 `RippleDrawable` / `RippleHostView` artifact on Android & Googlebook OS
 * (which previously drew a dark outer fill plus a lighter rectangular gray box/line across the
 * middle of buttons and chips under mouse hover):
 * 1. Sets `LocalRippleConfiguration provides null` in `ProvideSeamFreeHoverIndication` (must wrap
 *    both the root window and any sub-window such as `AlertDialog` or `DropdownMenu`).
 * 2. Renders `SmoothPrimaryButton`, `SmoothTonalButton`, `SmoothOutlinedButton`, `SmoothTextButton`,
 *    `SmoothFilterPill`, and `SmoothSidebarItem` directly on pure Compose `Row` surfaces with
 *    `indication = null` and smooth 150ms `animateColorAsState` container/border/content transitions.
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
                    activePresses.isNotEmpty() -> 0.11f
                    activeHovers.isNotEmpty() -> 0.06f
                    activeFocuses.isNotEmpty() -> 0.07f
                    else -> 0f
                }

                launch {
                    overlayAlpha.animateTo(
                        targetValue = targetAlpha,
                        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvideSeamFreeHoverIndication(
    content: @Composable () -> Unit
) {
    val tint = MaterialTheme.colorScheme.primary
    val indication = remember(tint) { SeamFreeIndication(tint) }
    CompositionLocalProvider(
        LocalIndication provides indication,
        LocalRippleConfiguration provides null,
        content = content
    )
}

@Composable
fun SmoothPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val baseBg = MaterialTheme.colorScheme.primary
    val fg = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    }
    val targetBg = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        isPressed -> lerp(baseBg, Color.Black, 0.16f)
        isHovered -> lerp(baseBg, Color.White, 0.12f)
        else -> baseBg
    }
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "smoothPrimaryBg"
    )

    CompositionLocalProvider(
        LocalContentColor provides fg,
        LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(
            color = fg,
            fontWeight = FontWeight.SemiBold
        )
    ) {
        Row(
            modifier = modifier
                .defaultMinSize(minHeight = ButtonDefaults.MinHeight)
                .clip(shape)
                .background(animatedBg)
                .hoverable(interactionSource, enabled = enabled)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun SmoothTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val baseBg = MaterialTheme.colorScheme.secondaryContainer
    val accent = MaterialTheme.colorScheme.primary
    val fg = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f)
    }
    val targetBg = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        isPressed -> lerp(baseBg, accent, 0.22f)
        isHovered -> lerp(baseBg, accent, 0.12f)
        else -> baseBg
    }
    val targetBorder = when {
        !enabled -> Color.Transparent
        isHovered || isPressed -> accent.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "smoothTonalBg"
    )
    val animatedBorder by animateColorAsState(
        targetValue = targetBorder,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "smoothTonalBorder"
    )

    CompositionLocalProvider(
        LocalContentColor provides fg,
        LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(
            color = fg,
            fontWeight = FontWeight.Medium
        )
    ) {
        Row(
            modifier = modifier
                .defaultMinSize(minHeight = ButtonDefaults.MinHeight)
                .clip(shape)
                .background(animatedBg)
                .border(1.dp, animatedBorder, shape)
                .hoverable(interactionSource, enabled = enabled)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun SmoothOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        isHovered || isPressed -> accent
        else -> MaterialTheme.colorScheme.onSurface
    }
    val targetBg = when {
        !enabled -> Color.Transparent
        isPressed -> accent.copy(alpha = 0.14f)
        isHovered -> accent.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val targetBorder = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
        isPressed -> accent.copy(alpha = 0.85f)
        isHovered -> accent.copy(alpha = 0.62f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "smoothOutlinedBg"
    )
    val animatedBorder by animateColorAsState(
        targetValue = targetBorder,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "smoothOutlinedBorder"
    )
    val animatedFg by animateColorAsState(
        targetValue = fg,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "smoothOutlinedFg"
    )

    CompositionLocalProvider(
        LocalContentColor provides animatedFg,
        LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(
            color = animatedFg,
            fontWeight = FontWeight.Medium
        )
    ) {
        Row(
            modifier = modifier
                .defaultMinSize(minHeight = ButtonDefaults.MinHeight)
                .clip(shape)
                .background(animatedBg)
                .border(1.dp, animatedBorder, shape)
                .hoverable(interactionSource, enabled = enabled)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun SmoothTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary
    val fg = if (enabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val targetBg = when {
        !enabled -> Color.Transparent
        isPressed -> accent.copy(alpha = 0.16f)
        isHovered -> accent.copy(alpha = 0.09f)
        else -> Color.Transparent
    }
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "smoothTextBg"
    )

    CompositionLocalProvider(
        LocalContentColor provides fg,
        LocalTextStyle provides MaterialTheme.typography.labelLarge.copy(
            color = fg,
            fontWeight = FontWeight.Medium
        )
    ) {
        Row(
            modifier = modifier
                .clip(shape)
                .background(animatedBg)
                .hoverable(interactionSource, enabled = enabled)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * Replaces `Material3.FilterChip` everywhere (Language pills like `"Deutsch"`, Category pills,
 * Theme pills) with a crisp, uniform hover surface that has ZERO inner gray rectangle/line.
 */
@Composable
fun SmoothFilterPill(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary
    val selectedBg = MaterialTheme.colorScheme.secondaryContainer
    val unselectedBg = MaterialTheme.colorScheme.surface

    val targetBg = when {
        selected && isPressed -> lerp(selectedBg, primary, 0.22f)
        selected && isHovered -> lerp(selectedBg, primary, 0.12f)
        selected -> selectedBg
        isPressed -> primary.copy(alpha = 0.14f)
        isHovered -> primary.copy(alpha = 0.08f)
        else -> unselectedBg
    }
    val targetBorder = when {
        selected -> primary.copy(alpha = 0.72f)
        isHovered || isPressed -> primary.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val targetFg = when {
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        isHovered || isPressed -> primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "pillBg"
    )
    val animatedBorder by animateColorAsState(
        targetValue = targetBorder,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "pillBorder"
    )
    val animatedFg by animateColorAsState(
        targetValue = targetFg,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "pillFg"
    )

    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(animatedBg)
            .border(1.dp, animatedBorder, shape)
            .hoverable(interactionSource, enabled = enabled)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Text(
                text = "✓",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = primary
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = animatedFg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Full-width Sidebar Navigation Row with uniform, seam-free hover highlight and non-truncated label.
 */
@Composable
fun SmoothSidebarItem(
    icon: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    val primary = MaterialTheme.colorScheme.primary
    val selectedBg = MaterialTheme.colorScheme.secondaryContainer

    val targetBg = when {
        selected && isHovered -> lerp(selectedBg, primary, 0.10f)
        selected -> selectedBg
        isPressed -> primary.copy(alpha = 0.14f)
        isHovered -> primary.copy(alpha = 0.08f)
        else -> Color.Transparent
    }
    val targetFg = when {
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        isHovered -> primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    val animatedBg by animateColorAsState(
        targetValue = targetBg,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "sidebarItemBg"
    )
    val animatedFg by animateColorAsState(
        targetValue = targetFg,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "sidebarItemFg"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(animatedBg)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = animatedFg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
