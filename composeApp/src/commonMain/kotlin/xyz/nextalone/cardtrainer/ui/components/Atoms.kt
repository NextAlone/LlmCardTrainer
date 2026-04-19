package xyz.nextalone.cardtrainer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.nextalone.cardtrainer.ui.theme.BrandMonoFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme

/** Mono-uppercase micro label above titles. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = TextStyle(
            fontFamily = BrandMonoFamily,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.4.sp,
            color = BrandTheme.colors.fgSubtle,
        ),
    )
}

@Composable
fun SectionHeader(
    eyebrow: String? = null,
    title: String,
    right: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            eyebrow?.let { Eyebrow(it) }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = BrandTheme.colors.fg,
            )
        }
        if (right != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { right() }
        }
    }
}

enum class ChipTone { Default, Outline, Accent, Good, Bad, Felt }

@Composable
fun BrandChip(
    text: String,
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.Default,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = BrandTheme.colors
    val (bg, fg, border) = when (tone) {
        ChipTone.Default -> Triple(c.fg.copy(alpha = 0.08f), c.fg, Color.Transparent)
        ChipTone.Outline -> Triple(Color.Transparent, c.fg, c.border)
        ChipTone.Accent -> Triple(c.accent.copy(alpha = 0.18f), c.accent, Color.Transparent)
        ChipTone.Good -> Triple(c.good.copy(alpha = 0.18f), c.good, Color.Transparent)
        ChipTone.Bad -> Triple(c.bad.copy(alpha = 0.18f), c.bad, Color.Transparent)
        ChipTone.Felt -> Triple(Color.White.copy(alpha = 0.1f), Color(0xFFFFFAED), Color.White.copy(alpha = 0.12f))
    }
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading()
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Slim progress meter — brass fill by default, [ChipTone.Good]/[ChipTone.Bad] variants. */
@Composable
fun Meter(
    value: Float,
    modifier: Modifier = Modifier,
    max: Float = 100f,
    tone: ChipTone = ChipTone.Default,
) {
    val pct = (value / max).coerceIn(0f, 1f)
    val c = BrandTheme.colors
    val fillBrush = when (tone) {
        ChipTone.Good -> Brush.horizontalGradient(listOf(c.good, c.good.copy(alpha = 0.8f)))
        ChipTone.Bad -> Brush.horizontalGradient(listOf(c.bad, c.bad.copy(alpha = 0.8f)))
        else -> Brush.horizontalGradient(listOf(c.accentBright, c.accent))
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(c.fg.copy(alpha = 0.1f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(pct)
                .fillMaxSize()
                .clip(RoundedCornerShape(999.dp))
                .background(fillBrush),
        )
    }
}

/** KPI readout: eyebrow label + mono tabular number. */
@Composable
fun StatReadout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    big: Boolean = false,
    tone: ChipTone = ChipTone.Default,
) {
    val c = BrandTheme.colors
    val color = when (tone) {
        ChipTone.Good -> c.good
        ChipTone.Bad -> c.bad
        else -> c.fg
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = TextStyle(
                    fontFamily = BrandMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = if (big) 26.sp else 18.sp,
                    color = color,
                ),
            )
            if (unit != null) {
                Text(
                    unit,
                    modifier = Modifier.padding(start = 3.dp, bottom = if (big) 3.dp else 1.dp),
                    style = TextStyle(
                        fontFamily = BrandMonoFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = if (big) 13.sp else 11.sp,
                        color = c.fgSubtle,
                    ),
                )
            }
        }
    }
}

/** Simple seat pip for poker oval table. */
@Composable
fun SeatPip(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    folded: Boolean = false,
    bet: Int? = null,
) {
    val c = BrandTheme.colors
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(
                    if (active) {
                        Brush.verticalGradient(listOf(c.accentBright, c.accent))
                    } else {
                        Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.35f)))
                    },
                )
                .border(
                    if (active) 2.dp else 1.5.dp,
                    if (active) c.accentBright else Color.White.copy(alpha = 0.2f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = if (active) c.fg else Color(0xFFEDE3D2).copy(alpha = if (folded) 0.3f else 1f),
                ),
            )
        }
        if (bet != null) {
            Text(
                bet.toString(),
                style = TextStyle(
                    fontFamily = BrandMonoFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = c.accentBright,
                ),
            )
        }
    }
}

/** Thin 1-dp divider matching `.divider`. */
@Composable
fun BrandDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BrandTheme.colors.border),
    )
}

/** Card surface wrapper matching `.surface`. */
@Composable
fun BrandSurface(
    modifier: Modifier = Modifier,
    tone: ChipTone = ChipTone.Default,
    corner: androidx.compose.ui.unit.Dp = 14.dp,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = BrandTheme.colors
    val bg = when (tone) {
        ChipTone.Outline -> Color.Transparent
        ChipTone.Accent -> c.accent.copy(alpha = 0.08f)
        else -> c.surface
    }
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, c.border, shape)
            .padding(padding),
        content = content,
    )
}
