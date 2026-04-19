package xyz.nextalone.cardtrainer.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.nextalone.cardtrainer.ui.theme.BrandDisplayFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme

enum class MjSuit { Wan, Tiao, Tong }

enum class TileSize(val w: Dp, val h: Dp, val corner: Dp) {
    Small(36.dp, 52.dp, 6.dp),
    Default(44.dp, 62.dp, 8.dp),
    Large(56.dp, 80.dp, 10.dp),
}

/** Carved-ivory mahjong tile, matches Redesign.html `.mt`. */
@Composable
fun MahjongTileView(
    suit: MjSuit,
    number: Int,
    modifier: Modifier = Modifier,
    size: TileSize = TileSize.Default,
    selected: Boolean = false,
    dim: Boolean = false,
    back: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val brand = BrandTheme.colors
    val shape = RoundedCornerShape(size.corner)
    val suitColor = when (suit) {
        MjSuit.Wan -> brand.suitGreenWan
        MjSuit.Tiao -> brand.suitGreenTiao
        MjSuit.Tong -> brand.suitBlueTong
    }
    val suitLabel = when (suit) { MjSuit.Wan -> "萬"; MjSuit.Tiao -> "條"; MjSuit.Tong -> "筒" }

    val liftTarget = when { selected -> 8.dp; else -> 0.dp }
    val lift by animateDpAsState(liftTarget)

    val base = modifier
        .offset(y = -lift)
        .size(size.w, size.h)
        .alpha(if (dim) 0.42f else 1f)
        .shadow(if (selected) 12.dp else 4.dp, shape, clip = false)

    if (back) {
        Box(
            base
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF3A6B45),
                        1f to Color(0xFF1F3C27),
                    ),
                )
                .border(1.dp, Color(0xFF0E2914), shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        )
        return
    }

    Box(
        base
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to brand.ivoryTop,
                    1f to brand.ivoryBottom,
                ),
            )
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) brand.accentBright else brand.ivoryEdge.copy(alpha = 0.5f),
                shape,
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        // subtle carved inset shadow
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .size(size.w, 6.dp)
                .background(Color.Black.copy(alpha = 0.07f)),
        )
        Column(
            Modifier.align(Alignment.Center),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                number.toString(),
                style = TextStyle(
                    fontFamily = BrandDisplayFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.w.value * 0.50f).sp,
                    color = suitColor,
                ),
            )
            Text(
                suitLabel,
                style = TextStyle(
                    fontFamily = BrandDisplayFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.w.value * 0.28f).sp,
                    color = suitColor,
                    letterSpacing = 0.6.sp,
                ),
            )
        }
    }
}
