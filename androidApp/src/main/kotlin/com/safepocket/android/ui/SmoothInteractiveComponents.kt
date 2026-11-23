package com.safepocket.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val CenteredButtonLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both
)

internal fun TextStyle.withCenteredMetrics(): TextStyle = copy(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = CenteredButtonLineHeightStyle
)

/**
 * SafePocket v0.3 Professional Zero-Artifact Hover & Press Engine.
 *
 * Eliminates the Material 3 `RippleDrawable` / `RippleHostView` artifact on Android & Googlebook OS
 * (which previously drew a dark outer fill plus a lighter rectangular gray box/line across the
 * middle of buttons and chips under mouse hover):
 * 1. Sets `LocalRippleConfiguration provides null` in `ProvideSeamFreeHoverIndication` (must wrap
 *    both the root window and any sub-window such as `Dialog` or `DropdownMenu`).
 * 2. Renders `SmoothPrimaryButton`, `SmoothTonalButton`, `SmoothOutlinedButton`, `SmoothOutlinedIconButton`,
 *    `SmoothTextButton`, `SmoothFilterPill`, `SmoothSidebarItem`, and `SmoothContextMenuItem` directly on
 *    pure Compose surfaces with `indication = null` and smooth `animateColorAsState` transitions.
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
        LocalTextStyle provides MaterialTheme.typography.labelLarge.withCenteredMetrics().copy(
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
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
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
        LocalTextStyle provides MaterialTheme.typography.labelLarge.withCenteredMetrics().copy(
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
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
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
    minHeight: Dp = ButtonDefaults.MinHeight,
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
        LocalTextStyle provides MaterialTheme.typography.labelLarge.withCenteredMetrics().copy(
            color = animatedFg,
            fontWeight = FontWeight.Medium
        )
    ) {
        Row(
            modifier = modifier
                .defaultMinSize(minHeight = minHeight)
                .clip(shape)
                .background(animatedBg)
                .border(1.dp, animatedBorder, shape)
                .hoverable(interactionSource, enabled = enabled)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
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
 * Square / rounded-square icon button (`36.dp` by default) that centers its vector icon
 * strictly at `Alignment.Center` with zero asymmetric font padding.
 */
@Composable
fun SmoothOutlinedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 36.dp,
    shape: Shape = RoundedCornerShape(10.dp),
    content: @Composable BoxScope.() -> Unit
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
        label = "smoothIconBtnBg"
    )
    val animatedBorder by animateColorAsState(
        targetValue = targetBorder,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "smoothIconBtnBorder"
    )
    val animatedFg by animateColorAsState(
        targetValue = fg,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "smoothIconBtnFg"
    )

    CompositionLocalProvider(LocalContentColor provides animatedFg) {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(animatedBg)
                .border(1.dp, animatedBorder, shape)
                .hoverable(interactionSource, enabled = enabled)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center,
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
        LocalTextStyle provides MaterialTheme.typography.labelLarge.withCenteredMetrics().copy(
            color = fg,
            fontWeight = FontWeight.Medium
        )
    ) {
        Row(
            modifier = modifier
                .clip(shape)
                .background(animatedBg)
                .hoverable(interactionSource, enabled = enabled)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
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
 * Hover-highlighted menu row for context menus, `+ New` category dropdown, language selector,
 * and smart field preset dropdowns. Restores crisp rounded hover highlight even when
 * `LocalRippleConfiguration` is `null`.
 */
@Composable
fun SmoothContextMenuItem(
    text: String,
    isDestructive: Boolean = false,
    accentHighlight: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()
    val tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(
        targetValue = when {
            isPressed -> tint.copy(alpha = 0.22f)
            isHovered -> tint.copy(alpha = 0.14f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing),
        label = "ctxMenuHoverBg"
    )
    val fg = when {
        isDestructive -> MaterialTheme.colorScheme.error
        accentHighlight || isHovered || isPressed -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minWidth = 210.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .hoverable(interactionSource)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.withCenteredMetrics(),
            fontWeight = if (isHovered || accentHighlight) FontWeight.Medium else FontWeight.Normal,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Geometrically centered vector icon for Theme cycling (`LIGHT` sun, `DARK` crescent moon,
 * `SYSTEM` half-contrast circle).
 */
@Composable
fun CenteredThemeContrastIcon(
    themeModeName: String,
    modifier: Modifier = Modifier.size(16.dp)
) {
    val tint = LocalContentColor.current
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val strokePx = 1.65.dp.toPx()
        when (themeModeName) {
            "LIGHT" -> {
                val r = size.minDimension * 0.23f
                drawCircle(color = tint, radius = r, center = Offset(cx, cy), style = Stroke(width = strokePx))
                val innerRay = size.minDimension * 0.34f
                val outerRay = size.minDimension * 0.46f
                for (i in 0 until 8) {
                    val rad = (i * 45.0) * PI / 180.0
                    val sx = cx + (cos(rad) * innerRay).toFloat()
                    val sy = cy + (sin(rad) * innerRay).toFloat()
                    val ex = cx + (cos(rad) * outerRay).toFloat()
                    val ey = cy + (sin(rad) * outerRay).toFloat()
                    drawLine(
                        color = tint,
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = strokePx,
                        cap = StrokeCap.Round
                    )
                }
            }
            "DARK" -> {
                val r = size.minDimension * 0.39f
                val outer = Path().apply {
                    addOval(Rect(center = Offset(cx, cy), radius = r))
                }
                val cutout = Path().apply {
                    addOval(
                        Rect(
                            center = Offset(cx + r * 0.44f, cy - r * 0.30f),
                            radius = r * 0.84f
                        )
                    )
                }
                val crescent = Path().apply {
                    op(outer, cutout, PathOperation.Difference)
                }
                drawPath(path = crescent, color = tint)
            }
            else -> {
                val r = size.minDimension * 0.39f
                drawCircle(
                    color = tint,
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = strokePx)
                )
                drawArc(
                    color = tint,
                    startAngle = 90f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(cx - r, cy - r),
                    size = Size(r * 2f, r * 2f)
                )
            }
        }
    }
}

/**
 * Geometrically centered vector padlock icon (`CenteredLockOutlineIcon`) with zero baseline shift.
 */
@Composable
fun CenteredLockOutlineIcon(
    modifier: Modifier = Modifier.size(16.dp)
) {
    val tint = LocalContentColor.current
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = 1.6.dp.toPx()
        val bodyW = w * 0.68f
        val bodyH = h * 0.46f
        val bodyLeft = (w - bodyW) / 2f
        val bodyTop = h * 0.44f
        drawRoundRect(
            color = tint,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(bodyW, bodyH),
            cornerRadius = CornerRadius(2.4.dp.toPx(), 2.4.dp.toPx()),
            style = Stroke(width = strokePx)
        )
        val shackleW = w * 0.40f
        val shackleLeft = (w - shackleW) / 2f
        val shackleTop = h * 0.12f
        val shacklePath = Path().apply {
            moveTo(shackleLeft, bodyTop)
            lineTo(shackleLeft, shackleTop + shackleW / 2f)
            arcTo(
                rect = Rect(shackleLeft, shackleTop, shackleLeft + shackleW, shackleTop + shackleW),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
            lineTo(shackleLeft + shackleW, bodyTop)
        }
        drawPath(
            path = shacklePath,
            color = tint,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawCircle(
            color = tint,
            radius = 1.25.dp.toPx(),
            center = Offset(w / 2f, bodyTop + bodyH * 0.50f)
        )
    }
}

/**
 * Section header database cylinder icon matching Screenshot #2 (`Datenbank & Synchronisierung`).
 */
@Composable
fun DatabaseSyncSectionIcon(
    color: Color,
    modifier: Modifier = Modifier.size(18.dp)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = 1.65.dp.toPx()
        val rx = w * 0.36f
        val ry = h * 0.13f
        val cx = w / 2f
        val topY = h * 0.22f
        val botY = h * 0.78f
        drawOval(
            color = color,
            topLeft = Offset(cx - rx, topY - ry),
            size = Size(rx * 2f, ry * 2f),
            style = Stroke(width = strokePx)
        )
        val sidePath = Path().apply {
            moveTo(cx - rx, topY)
            lineTo(cx - rx, botY)
            arcTo(
                rect = Rect(cx - rx, botY - ry, cx + rx, botY + ry),
                startAngleDegrees = 180f,
                sweepAngleDegrees = -180f,
                forceMoveTo = false
            )
            lineTo(cx + rx, topY)
        }
        drawPath(path = sidePath, color = color, style = Stroke(width = strokePx, cap = StrokeCap.Round))
        val midY = (topY + botY) / 2f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - rx, midY - ry),
            size = Size(rx * 2f, ry * 2f),
            style = Stroke(width = strokePx)
        )
    }
}

private fun Path.addCloudOutline(w: Float, h: Float) {
    moveTo(w * 0.26f, h * 0.74f)
    lineTo(w * 0.75f, h * 0.74f)
    cubicTo(w * 0.89f, h * 0.74f, w * 0.94f, h * 0.54f, w * 0.80f, h * 0.47f)
    cubicTo(w * 0.78f, h * 0.26f, w * 0.50f, h * 0.20f, w * 0.40f, h * 0.36f)
    cubicTo(w * 0.24f, h * 0.35f, w * 0.12f, h * 0.48f, w * 0.15f, h * 0.62f)
    cubicTo(w * 0.16f, h * 0.70f, w * 0.21f, h * 0.74f, w * 0.26f, h * 0.74f)
    close()
}

/**
 * `cloud_off` vector icon for the `Lokale DB` card in Screenshot #2.
 */
@Composable
fun CloudOffCardIcon(
    color: Color,
    modifier: Modifier = Modifier.size(22.dp)
) {
    Canvas(modifier = modifier) {
        val strokePx = 1.85.dp.toPx()
        val cloud = Path().apply { addCloudOutline(size.width, size.height) }
        drawPath(
            path = cloud,
            color = color,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.16f, size.height * 0.18f),
            end = Offset(size.width * 0.84f, size.height * 0.84f),
            strokeWidth = strokePx,
            cap = StrokeCap.Round
        )
    }
}

/**
 * `cloud_done` vector icon (cloud with checkmark) for the `Cloud-Sync` card in Screenshot #2.
 */
@Composable
fun CloudDoneCardIcon(
    color: Color,
    modifier: Modifier = Modifier.size(22.dp)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokePx = 1.85.dp.toPx()
        val cloud = Path().apply { addCloudOutline(w, h) }
        drawPath(
            path = cloud,
            color = color,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        val check = Path().apply {
            moveTo(w * 0.39f, h * 0.55f)
            lineTo(w * 0.48f, h * 0.64f)
            lineTo(w * 0.64f, h * 0.46f)
        }
        drawPath(
            path = check,
            color = color,
            style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round)
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
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
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
                style = MaterialTheme.typography.labelMedium.withCenteredMetrics(),
                fontWeight = FontWeight.Bold,
                color = primary
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.withCenteredMetrics(),
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
            .pointerHoverIcon(PointerIcon.Hand)
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
            style = MaterialTheme.typography.bodyLarge.withCenteredMetrics()
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.withCenteredMetrics(),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = animatedFg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
