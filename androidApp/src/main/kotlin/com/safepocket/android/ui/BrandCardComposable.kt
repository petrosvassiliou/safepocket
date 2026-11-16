package com.safepocket.android.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safepocket.shared.brand.InstitutionBrandProfile
import com.safepocket.shared.brand.InstitutionBrandRegistry
import com.safepocket.shared.brand.ResolvedDualBrand
import com.safepocket.shared.model.CanonicalFieldKeys
import com.safepocket.shared.model.VaultCategory
import com.safepocket.shared.model.VaultItemPayload
import kotlin.math.cos
import kotlin.math.sin

/**
 * Dual-Logo & Institution Brand Color Renderer for SafePocket v0.2.
 *
 * Renders:
 * 1. Top-Left: Institution Logo (downloaded `data:image/...` icon OR crisp vector emblem such as
 *    Deutsche Bank's square+slash, Sparkasse's coin+dot, ING's shield, TK's badge, ADAC's mark,
 *    DB Bahn's emblem, EU 12-star ring, or stylized monogram) + Institution Name.
 * 2. Top-Right / Bottom-Right: Network or Category Type Vector Logo (`VISA`, `Mastercard`, `AMEX`,
 *    `girocard`, `Maestro`, `SEPA`, `eGK`, `EHIC`, `ICAO Biometric Passport`, `Barcode`).
 * 3. Automatic Brand Color Gradient with WCAG contrast-aware foreground text.
 */
@Composable
fun DualBrandVisualCardHero(
    item: VaultItemPayload,
    categoryLabelText: String,
    modifier: Modifier = Modifier
) {
    val resolved = remember(item) { InstitutionBrandRegistry.resolveForItem(item) }
    val (gradStart, gradEnd) = remember(item.cardTheme, resolved) {
        resolveCardGradientColors(item, resolved)
    }
    val isLightCard = remember(gradStart, gradEnd) {
        ((gradStart.luminance() + gradEnd.luminance()) / 2f) > 0.56f
    }
    val fgPrimary = if (isLightCard) Color(0xFF0F172A) else Color.White
    val fgMuted = if (isLightCard) Color(0xFF334155) else Color.White.copy(alpha = 0.80f)
    val badgeGlassBg = if (isLightCard) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.16f)
    val badgeGlassBorder = if (isLightCard) Color.Black.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.28f)

    val institutionTitle = item.cardTheme?.institutionName?.takeIf { it.isNotBlank() }
        ?: resolved.institution?.displayName
        ?: item.fieldValue(CanonicalFieldKeys.ISSUING_BANK).takeIf { it.isNotBlank() }
        ?: item.fieldValue(CanonicalFieldKeys.INSURER_NAME).takeIf { it.isNotBlank() }
        ?: item.fieldValue(CanonicalFieldKeys.ORGANIZATION).takeIf { it.isNotBlank() }
        ?: item.fieldValue(CanonicalFieldKeys.ISSUING_AUTHORITY).takeIf { it.isNotBlank() }
        ?: categoryLabelText

    val maskedPrimaryNumber = remember(item) { formatHeroMaskedIdentifier(item) }
    val holderName = remember(item) {
        item.fieldValue(CanonicalFieldKeys.CARDHOLDER)
            .ifBlank { item.fieldValue(CanonicalFieldKeys.ACCOUNT_HOLDER) }
            .ifBlank { item.fieldValue(CanonicalFieldKeys.FULL_NAME) }
            .ifBlank { item.fieldValue(CanonicalFieldKeys.MEMBER_NAME) }
    }
    val expiry = remember(item) { item.fieldValue(CanonicalFieldKeys.EXPIRY_DATE) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(gradStart, gradEnd),
                    start = Offset(0f, 0f),
                    end = Offset(900f, 520f)
                )
            )
            .border(1.dp, badgeGlassBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Row 1: Top-Left Institution Logo + Name | Top-Right Category Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    InstitutionLogoBadge(
                        institution = resolved.institution,
                        fallbackTitle = institutionTitle,
                        logoDataUri = item.cardTheme?.institutionLogoDataUri,
                        size = 38.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = institutionTitle,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = fgPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = categoryLabelText,
                            style = MaterialTheme.typography.labelSmall,
                            color = fgMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                NetworkOrTypeLogoBadge(
                    networkCode = resolved.networkOrTypeCode,
                    displayLabel = item.cardTheme?.networkBrand?.takeIf { it.isNotBlank() }
                        ?: resolved.networkDisplayLabel,
                    isLightCard = isLightCard
                )
            }

            // Row 2: Optional EMV Smart Chip + Contactless Icon (Cards, Insurance eGK, National ID)
            if (item.category in setOf(
                    VaultCategory.PAYMENT_CARD,
                    VaultCategory.INSURANCE_CARD,
                    VaultCategory.OFFICIAL_DOCUMENT
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    EmvSmartChipGraphic()
                    if (item.category == VaultCategory.PAYMENT_CARD) {
                        ContactlessWavesIcon(tint = fgMuted)
                    }
                }
            }

            // Row 3: Primary Card Title + Masked Identifier
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = fgPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (maskedPrimaryNumber.isNotBlank()) {
                    Text(
                        text = maskedPrimaryNumber,
                        style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 1.4.sp),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = fgPrimary.copy(alpha = 0.94f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (item.subtitle.isNotBlank()) {
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = fgMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Row 4: Bottom Footer — Holder Name & Valid Thru
            if (holderName.isNotBlank() || expiry.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        if (holderName.isNotBlank()) {
                            Text(
                                text = holderName.uppercase(),
                                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = fgPrimary.copy(alpha = 0.92f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (expiry.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(badgeGlassBg)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = expiry,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = fgPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact 36dp Institution Avatar + Mini Type Pill for Vault List Rows.
 */
@Composable
fun InstitutionMiniAvatar(
    item: VaultItemPayload,
    size: Dp = 38.dp
) {
    val resolved = remember(item) { InstitutionBrandRegistry.resolveForItem(item) }
    InstitutionLogoBadge(
        institution = resolved.institution,
        fallbackTitle = item.cardTheme?.institutionName?.takeIf { it.isNotBlank() } ?: item.title,
        logoDataUri = item.cardTheme?.institutionLogoDataUri,
        size = size,
        overrideBgHex = item.cardTheme?.customColorHex ?: resolved.effectiveColorHex
    )
}

@Composable
fun InstitutionLogoBadge(
    institution: InstitutionBrandProfile?,
    fallbackTitle: String,
    logoDataUri: String?,
    size: Dp = 38.dp,
    overrideBgHex: String? = null
) {
    val decodedBitmap = remember(logoDataUri) { decodeDataUriBitmap(logoDataUri) }
    if (decodedBitmap != null) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White)
                .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .padding(3.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = decodedBitmap,
                contentDescription = institution?.displayName ?: fallbackTitle,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    val logoKind = institution?.logoKind ?: "MONOGRAM"
    val primaryColor = parseHexColorOrNull(institution?.primaryColorHex ?: overrideBgHex)
        ?: Color(0xFF1E3A8A)

    when (logoKind) {
        "DB_SLASH" -> {
            // Iconic Deutsche Bank Square + Diagonal Slash Emblem
            Canvas(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White)
            ) {
                val pad = this.size.minDimension * 0.16f
                val boxSize = this.size.minDimension - pad * 2f
                val stroke = this.size.minDimension * 0.115f
                val dbBlue = Color(0xFF0018A8)
                drawRect(
                    color = dbBlue,
                    topLeft = Offset(pad, pad),
                    size = Size(boxSize, boxSize),
                    style = Stroke(width = stroke)
                )
                drawLine(
                    color = dbBlue,
                    start = Offset(pad + boxSize * 0.26f, pad + boxSize * 0.80f),
                    end = Offset(pad + boxSize * 0.74f, pad + boxSize * 0.20f),
                    strokeWidth = stroke * 1.08f,
                    cap = StrokeCap.Square
                )
            }
        }

        "SPARKASSE_S" -> {
            // Sparkasse Red Emblem with Coin Dot + S Curve
            Canvas(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFFE2001A))
            ) {
                val w = this.size.width
                val h = this.size.height
                drawCircle(
                    color = Color.White,
                    radius = w * 0.11f,
                    center = Offset(w * 0.5f, h * 0.25f)
                )
                drawArc(
                    color = Color.White,
                    startAngle = 25f,
                    sweepAngle = 290f,
                    useCenter = false,
                    topLeft = Offset(w * 0.26f, h * 0.40f),
                    size = Size(w * 0.48f, h * 0.44f),
                    style = Stroke(width = w * 0.12f, cap = StrokeCap.Round)
                )
            }
        }

        "COMMERZ_RIBBON" -> {
            // Commerzbank Gold Geometric Triangle Ribbon
            Canvas(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF002E3C))
            ) {
                val w = this.size.width
                val h = this.size.height
                val path = Path().apply {
                    moveTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.84f, h * 0.76f)
                    lineTo(w * 0.16f, h * 0.76f)
                    close()
                }
                drawPath(path, color = Color(0xFFFFCC00), style = Stroke(width = w * 0.11f))
            }
        }

        "ADAC_BADGE" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFFFFCC00))
                    .border(1.dp, Color.Black.copy(alpha = 0.25f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ADAC",
                    color = Color(0xFF111111),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        "DB_BAHN" -> {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(2.5.dp, Color(0xFFEC0016), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "DB",
                    color = Color(0xFFEC0016),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        "EU_STARS" -> {
            val code = institution?.badgeMonogram ?: "EU"
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFF003399)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = this.size.width / 2f
                    val cy = this.size.height / 2f
                    val r = this.size.minDimension * 0.36f
                    for (i in 0 until 12) {
                        val rad = Math.toRadians((i * 30.0) - 90.0)
                        drawCircle(
                            color = Color(0xFFFFCC00),
                            radius = this.size.minDimension * 0.038f,
                            center = Offset(
                                (cx + r * cos(rad)).toFloat(),
                                (cy + r * sin(rad)).toFloat()
                            )
                        )
                    }
                }
                Text(
                    text = code,
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        else -> {
            val monogram = institution?.badgeMonogram ?: computeMonogram(fallbackTitle)
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(RoundedCornerShape(10.dp))
                    .background(primaryColor)
                    .border(1.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = monogram,
                    color = if (primaryColor.luminance() > 0.58f) Color(0xFF0F172A) else Color.White,
                    fontSize = if (monogram.length >= 4) 9.sp else 11.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Renders the Card Network or Category Type vector badge (`VISA`, `Mastercard`, `AMEX`,
 * `girocard`, `Maestro`, `SEPA`, `eGK`, `EHIC`, `PASSPORT`, `BARCODE`).
 */
@Composable
fun NetworkOrTypeLogoBadge(
    networkCode: String,
    displayLabel: String,
    isLightCard: Boolean = false
) {
    when (networkCode.uppercase()) {
        "VISA" -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 5.dp, height = 10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFF7B600))
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = "VISA",
                        color = Color(0xFF1A1F71),
                        fontWeight = FontWeight.Black,
                        fontStyle = FontStyle.Italic,
                        fontSize = 15.sp,
                        letterSpacing = 0.6.sp
                    )
                }
            }
        }

        "MASTERCARD" -> {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = if (isLightCard) 0.08f else 0.24f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Canvas(modifier = Modifier.size(width = 34.dp, height = 21.dp)) {
                    val r = size.height * 0.48f
                    drawCircle(
                        color = Color(0xFFEB001B),
                        radius = r,
                        center = Offset(size.width * 0.35f, size.height * 0.5f)
                    )
                    drawCircle(
                        color = Color(0xFFF79E1B).copy(alpha = 0.90f),
                        radius = r,
                        center = Offset(size.width * 0.65f, size.height * 0.5f)
                    )
                }
            }
        }

        "AMEX" -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF006FCF))
                    .border(1.dp, Color.White, RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "AMEX",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 0.8.sp
                )
            }
        }

        "MAESTRO" -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.22f))
                    .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
                Canvas(modifier = Modifier.size(width = 34.dp, height = 21.dp)) {
                    val r = size.height * 0.48f
                    drawCircle(
                        color = Color(0xFFEB001B),
                        radius = r,
                        center = Offset(size.width * 0.35f, size.height * 0.5f)
                    )
                    drawCircle(
                        color = Color(0xFF00A2E5).copy(alpha = 0.88f),
                        radius = r,
                        center = Offset(size.width * 0.65f, size.height * 0.5f)
                    )
                }
            }
        }

        "GIROCARD" -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFF003D7C))
                    .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE2001A))
                    )
                    Text(
                        text = "girocard",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp
                    )
                }
            }
        }

        "PASSPORT", "NATIONAL_ID", "OFFICIAL_DOC" -> {
            // ICAO Biometric ePassport / eID Chip Symbol + Label
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isLightCard) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val fg = if (isLightCard) Color(0xFF0F172A) else Color(0xFFFFE082)
                Canvas(modifier = Modifier.size(width = 18.dp, height = 12.dp)) {
                    drawRoundRect(
                        color = fg,
                        cornerRadius = CornerRadius(2.dp.toPx()),
                        style = Stroke(width = 1.4.dp.toPx())
                    )
                    drawLine(
                        color = fg,
                        start = Offset(0f, size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 1.2.dp.toPx()
                    )
                    drawCircle(
                        color = fg,
                        radius = size.height * 0.24f,
                        center = Offset(size.width / 2f, size.height / 2f)
                    )
                }
                Text(
                    text = displayLabel,
                    color = if (isLightCard) Color(0xFF0F172A) else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp
                )
            }
        }

        "BARCODE" -> {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.92f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Canvas(modifier = Modifier.size(width = 16.dp, height = 12.dp)) {
                    val xs = floatArrayOf(0.08f, 0.24f, 0.36f, 0.54f, 0.70f, 0.88f)
                    xs.forEachIndexed { idx, frac ->
                        drawLine(
                            color = Color(0xFF111827),
                            start = Offset(size.width * frac, 0f),
                            end = Offset(size.width * frac, size.height),
                            strokeWidth = if (idx % 2 == 0) 2.dp.toPx() else 1.dp.toPx()
                        )
                    }
                }
                Text(
                    text = displayLabel,
                    color = Color(0xFF111827),
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }
        }

        else -> {
            val fg = if (isLightCard) Color(0xFF0F172A) else Color.White
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isLightCard) Color.Black.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.18f))
                    .border(1.dp, fg.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = displayLabel,
                    color = fg,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun EmvSmartChipGraphic() {
    Canvas(
        modifier = Modifier
            .size(width = 38.dp, height = 27.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFE6C675), Color(0xFFC99E3B), Color(0xFFF3DC9B))
                )
            )
    ) {
        val line = Color(0xFF6E5114).copy(alpha = 0.55f)
        val sw = 1.dp.toPx()
        drawLine(line, Offset(size.width * 0.33f, 0f), Offset(size.width * 0.33f, size.height), sw)
        drawLine(line, Offset(size.width * 0.67f, 0f), Offset(size.width * 0.67f, size.height), sw)
        drawLine(line, Offset(0f, size.height * 0.5f), Offset(size.width, size.height * 0.5f), sw)
    }
}

@Composable
private fun ContactlessWavesIcon(tint: Color) {
    Canvas(modifier = Modifier.size(18.dp)) {
        val sw = 1.6.dp.toPx()
        for (i in 1..3) {
            val d = size.minDimension * (0.28f * i)
            drawArc(
                color = tint,
                startAngle = -42f,
                sweepAngle = 84f,
                useCenter = false,
                topLeft = Offset((size.width - d) / 2f - 2.dp.toPx(), (size.height - d) / 2f),
                size = Size(d, d),
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
        }
    }
}

fun resolveCardGradientColors(
    item: VaultItemPayload,
    resolved: ResolvedDualBrand
): Pair<Color, Color> {
    val themeCustomHex = item.cardTheme?.customColorHex
    val inst = resolved.institution
    val customHex = themeCustomHex?.takeIf { it.isNotBlank() }
        ?: resolved.effectiveColorHex
    val parsedCustom = parseHexColorOrNull(customHex)
    if (parsedCustom != null) {
        val secondary = inst?.secondaryColorHex
            ?.takeIf { themeCustomHex == null || themeCustomHex.equals(inst.primaryColorHex, ignoreCase = true) }
            ?.let { parseHexColorOrNull(it) }
            ?: lerp(parsedCustom, Color.Black, 0.28f)
        return parsedCustom to secondary
    }

    return when (item.cardTheme?.paletteId) {
        "alpine_emerald" -> Color(0xFF0F4C3A) to Color(0xFF1B7A5E)
        "aegean_cobalt" -> Color(0xFF103766) to Color(0xFF1E5AA8)
        "terracotta_gold" -> Color(0xFF6E351A) to Color(0xFFA8572A)
        "crimson_ruby" -> Color(0xFF641826) to Color(0xFF9E2A3F)
        "midnight_navy" -> Color(0xFF0F172A) to Color(0xFF1E3A8A)
        "arctic_cyan" -> Color(0xFF0E4D5C) to Color(0xFF1B7B8E)
        "forest_sage" -> Color(0xFF1E3F2B) to Color(0xFF356849)
        "sunset_coral" -> Color(0xFF7C2D12) to Color(0xFFC2410C)
        "espresso_bronze" -> Color(0xFF3E2723) to Color(0xFF6D4C41)
        "titanium_silver" -> Color(0xFFCBD5E1) to Color(0xFF94A3B8)
        "pearl_ivory" -> Color(0xFFF8FAFC) to Color(0xFFE2E8F0)
        else -> Color(0xFF1E293B) to Color(0xFF334155)
    }
}

fun parseHexColorOrNull(hex: String?): Color? {
    if (hex.isNullOrBlank()) return null
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6) return null
    val rgb = clean.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or rgb)
}

private fun decodeDataUriBitmap(dataUri: String?): ImageBitmap? {
    if (dataUri.isNullOrBlank() || !dataUri.startsWith("data:image/")) return null
    val commaIdx = dataUri.indexOf(',')
    if (commaIdx <= 0) return null
    val b64 = dataUri.substring(commaIdx + 1)
    return runCatching {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

private fun computeMonogram(title: String): String {
    val words = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return "SP"
    if (words.size == 1) return words[0].take(3).uppercase()
    return "${words[0].first()}${words[1].first()}".uppercase()
}

private fun formatHeroMaskedIdentifier(item: VaultItemPayload): String {
    val cardNum = item.fieldValue(CanonicalFieldKeys.CARD_NUMBER).filter { it.isDigit() }
    if (cardNum.length >= 4) {
        return "••••  ••••  ••••  ${cardNum.takeLast(4)}"
    }
    val iban = item.fieldValue(CanonicalFieldKeys.IBAN).replace(" ", "")
    if (iban.length >= 8) {
        return "${iban.take(4)} •••• •••• ${iban.takeLast(4)}"
    }
    val ins = item.fieldValue(CanonicalFieldKeys.INSURANCE_NUMBER)
    if (ins.length >= 4) {
        return "••••••${ins.takeLast(4)}"
    }
    val mem = item.fieldValue(CanonicalFieldKeys.MEMBERSHIP_ID)
    if (mem.isNotBlank()) return mem
    val doc = item.fieldValue(CanonicalFieldKeys.DOCUMENT_NUMBER)
    if (doc.length >= 4) return "••••${doc.takeLast(4)}"
    return ""
}
