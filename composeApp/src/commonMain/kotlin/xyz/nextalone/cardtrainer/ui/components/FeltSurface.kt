package xyz.nextalone.cardtrainer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme

/**
 * The dark-green casino-cloth backdrop used for poker tables, mahjong boards and
 * Resume cards. Emulates the `.surface--felt` radial gradient from Redesign.html.
 */
@Composable
fun FeltSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    cornerRadius: Dp = 20.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = BrandTheme.colors
    val gradient = Brush.radialGradient(
        colorStops = arrayOf(
            0f to c.tableHighlight.copy(alpha = 0.85f),
            0.45f to c.table,
            1f to c.tableDeep,
        ),
        center = Offset.Unspecified,
        radius = Float.POSITIVE_INFINITY,
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(gradient)
            .border(1.dp, Color.Black.copy(alpha = 0.3f), shape),
        content = content,
    )
}
