package xyz.nextalone.cardtrainer

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import xyz.nextalone.cardtrainer.storage.AppSettings
import xyz.nextalone.cardtrainer.storage.provideSettings
import xyz.nextalone.cardtrainer.ui.HomeScreen
import xyz.nextalone.cardtrainer.ui.MahjongScreen
import xyz.nextalone.cardtrainer.ui.PokerScreen
import xyz.nextalone.cardtrainer.ui.SettingsScreen
import xyz.nextalone.cardtrainer.ui.StatsScreen
import xyz.nextalone.cardtrainer.ui.components.DeviceMode
import xyz.nextalone.cardtrainer.ui.components.MobileTabBar
import xyz.nextalone.cardtrainer.ui.components.WithDeviceMode
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme
import xyz.nextalone.cardtrainer.util.PlatformBackHandler

sealed class Route {
    data object Home : Route()
    data object Poker : Route()
    data object Mahjong : Route()
    data object Stats : Route()
    data object Settings : Route()
}

private fun Route.key(): String = when (this) {
    Route.Home -> "home"
    Route.Poker -> "poker"
    Route.Mahjong -> "mahjong"
    Route.Stats -> "stats"
    Route.Settings -> "settings"
}

private fun keyToRoute(key: String): Route = when (key) {
    "poker" -> Route.Poker
    "mahjong" -> Route.Mahjong
    "stats" -> Route.Stats
    "settings" -> Route.Settings
    else -> Route.Home
}

@Composable
fun App(darkTheme: Boolean = isSystemInDarkTheme()) {
    val settings = remember { AppSettings(provideSettings()) }
    var route by remember { mutableStateOf<Route>(Route.Home) }

    // Pop the current screen back to Home on platform back gesture / key.
    PlatformBackHandler(enabled = route != Route.Home) { route = Route.Home }

    BrandTheme(darkTheme = darkTheme) {
        Surface(color = MaterialTheme.colorScheme.background) {
            WithDeviceMode { mode ->
                val isPhone = mode == DeviceMode.Phone
                Column(Modifier.fillMaxSize().background(BrandTheme.colors.bg)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (route) {
                            Route.Home -> HomeScreen(
                                settings = settings,
                                onNav = { route = it },
                            )
                            Route.Poker -> PokerScreen(
                                settings = settings,
                                onBack = { route = Route.Home },
                            )
                            Route.Mahjong -> MahjongScreen(
                                settings = settings,
                                onBack = { route = Route.Home },
                            )
                            Route.Stats -> StatsScreen(
                                settings = settings,
                                onBack = { route = Route.Home },
                            )
                            Route.Settings -> SettingsScreen(
                                settings = settings,
                                onBack = { route = Route.Home },
                            )
                        }
                    }
                    if (isPhone) {
                        // Global bottom tab — persists across all routes so
                        // users can jump between modules without going via Home.
                        MobileTabBar(
                            active = route.key(),
                            onNav = { k -> route = keyToRoute(k) },
                            items = listOf(
                                "home" to ("训练" to Icons.Default.Home),
                                "poker" to ("扑克" to Icons.Default.Style),
                                "mahjong" to ("麻将" to Icons.Default.Casino),
                                "stats" to ("统计" to Icons.Default.BarChart),
                                "settings" to ("设置" to Icons.Default.Settings),
                            ),
                        )
                    }
                }
            }
        }
    }
}
