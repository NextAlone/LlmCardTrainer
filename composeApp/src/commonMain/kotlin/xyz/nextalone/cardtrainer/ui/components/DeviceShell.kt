package xyz.nextalone.cardtrainer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.nextalone.cardtrainer.ui.theme.BrandDisplayFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandMonoFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme

enum class DeviceMode { Phone, Desktop }

/** 720dp is the 2-column breakpoint from the design (`Redesign.html`). */
@Composable
fun WithDeviceMode(
    modifier: Modifier = Modifier,
    breakpoint: Dp = 720.dp,
    content: @Composable BoxScope.(DeviceMode) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val mode = if (maxWidth < breakpoint) DeviceMode.Phone else DeviceMode.Desktop
        content(mode)
    }
}

/**
 * Phone chrome — custom top strip (no Scaffold TopAppBar), scrollable body,
 * optional action row, optional bottom tab. Replaces the design's
 * MobileStatusBar + PokerTopStrip + content + MobileHomeBar.
 */
@Composable
fun PhoneShell(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    topRight: (@Composable RowScope_.() -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    val c = BrandTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg),
    ) {
        PhoneTopStrip(eyebrow = eyebrow, title = title, onBack = onBack, topRight = topRight)
        Box(Modifier.weight(1f).fillMaxWidth()) { body() }
        bottomBar?.invoke()
    }
}

/**
 * Desktop chrome — titlebar + custom top strip + content that *fits* the
 * viewport (no outer scroll by default), optional bottom action bar.
 */
@Composable
fun DesktopShell(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") windowLabel: String = "",
    topRight: (@Composable RowScope_.() -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    body: @Composable () -> Unit,
) {
    val c = BrandTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg),
    ) {
        PhoneTopStrip(
            eyebrow = eyebrow,
            title = title,
            onBack = onBack,
            topRight = topRight,
            desktop = true,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { body() }
        bottomBar?.invoke()
    }
}

/** Alias for RowScope for the DSL; keeps call sites terse. */
typealias RowScope_ = androidx.compose.foundation.layout.RowScope

@Composable
private fun PhoneTopStrip(
    eyebrow: String,
    title: String,
    onBack: (() -> Unit)?,
    topRight: (@Composable RowScope_.() -> Unit)?,
    desktop: Boolean = false,
) {
    val c = BrandTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(
                horizontal = if (desktop) 24.dp else 16.dp,
                vertical = if (desktop) 14.dp else 10.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onBack)
                        .padding(6.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = c.fg,
                    )
                }
                Spacer(Modifier.size(6.dp))
            }
            Column(Modifier.weight(1f)) {
                Eyebrow(eyebrow)
                Text(
                    title,
                    style = TextStyle(
                        fontFamily = BrandDisplayFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (desktop) 20.sp else 17.sp,
                        color = c.fg,
                    ),
                )
            }
            if (topRight != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { topRight() }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
}

/** Bottom tab bar used in phone mode — the design's `MobileHomeBar`. */
@Composable
fun MobileTabBar(
    active: String,
    onNav: (String) -> Unit,
    items: List<Pair<String, Pair<String, ImageVector>>>,
) {
    val c = BrandTheme.colors
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            items.forEach { (key, payload) ->
                val (label, icon) = payload
                val isActive = key == active
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onNav(key) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        icon,
                        contentDescription = label,
                        tint = if (isActive) c.accent else c.fgMuted,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) c.accent else c.fgMuted,
                    )
                }
            }
        }
    }
}

/**
 * Pinned action row — brass buttons lined up right, matches `.PokerBottomBar`.
 * [absorbNavBarInset] should be `true` when no tab bar sits below this (desktop,
 * or a phone screen where the app has hidden the global tab). On phones where
 * the global tab bar already padded the navigation-bar inset, leave it off so
 * we don't double-pad.
 */
@Composable
fun PinnedActionBar(
    absorbNavBarInset: Boolean = false,
    content: @Composable RowScope_.() -> Unit,
) {
    val c = BrandTheme.colors
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(
            Modifier
                .fillMaxWidth()
                .let { if (absorbNavBarInset) it.navigationBarsPadding() else it }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}
