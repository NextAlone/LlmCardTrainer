package xyz.nextalone.cardtrainer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Sizing mirrors Redesign.html `.pc / .pc--sm / .pc--lg / .pc--xs`. */
enum class CardSize(val w: Dp, val h: Dp, val corner: Dp) {
    XS(30.dp, 42.dp, 3.dp),
    Small(42.dp, 60.dp, 5.dp),
    Default(54.dp, 78.dp, 6.dp),
    Large(68.dp, 96.dp, 8.dp),
}

/**
 * A single playing card. Either face-up, face-down ([back]) or an empty slot
 * ([slot]). Simplified to a single centered `rank + suit` glyph — no center
 * pip / mirrored BR corner — so small sizes don't overlap.
 */
@Composable
fun PlayingCardView(
    rank: String,
    suit: String,
    modifier: Modifier = Modifier,
    size: CardSize = CardSize.Default,
    back: Boolean = false,
    slot: Boolean = false,
) {
    val shape = RoundedCornerShape(size.corner)
    val brand = BrandTheme.colors
    val red = suit == "♥" || suit == "♦"
    val pipColor = if (red) brand.suitRed else brand.suitBlack
    // Scale the rank & suit glyphs by card width so every size reads clearly
    // without the smaller sizes getting overlapping corner + center pips.
    val rankPx = (size.w.value * 0.42f).sp
    val suitPx = (size.w.value * 0.34f).sp

    when {
        slot -> Box(
            modifier
                .size(size.w, size.h)
                .clip(shape)
                .border(1.5.dp, Color.White.copy(alpha = 0.2f), shape),
        )
        back -> Box(
            modifier
                .size(size.w, size.h)
                .shadow(4.dp, shape, clip = false)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        0f to Color(0xFF622121),
                        1f to Color(0xFF4B1717),
                    ),
                ),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .offset(4.dp, 4.dp)
                    .size(size.w - 8.dp, size.h - 8.dp)
                    .border(
                        1.5.dp,
                        brand.accentBright.copy(alpha = 0.6f),
                        RoundedCornerShape(size.corner - 2.dp),
                    ),
            )
        }
        else -> Box(
            modifier
                .size(size.w, size.h)
                .shadow(4.dp, shape, clip = false)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        0f to brand.ivoryTop,
                        0.6f to brand.ivoryMid,
                        1f to brand.ivoryBottom,
                    ),
                )
                .border(1.dp, brand.ivoryEdge.copy(alpha = 0.5f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    rank,
                    style = TextStyle(
                        fontFamily = BrandDisplayFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = rankPx,
                        color = pipColor,
                    ),
                    maxLines = 1,
                )
                Text(
                    suit,
                    style = TextStyle(
                        fontFamily = BrandDisplayFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = suitPx,
                        color = pipColor,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
