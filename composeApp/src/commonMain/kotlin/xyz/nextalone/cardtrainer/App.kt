package xyz.nextalone.cardtrainer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import xyz.nextalone.cardtrainer.storage.AppSettings
import xyz.nextalone.cardtrainer.storage.provideSettings
import xyz.nextalone.cardtrainer.ui.HomeScreen
import xyz.nextalone.cardtrainer.ui.MahjongScreen
import xyz.nextalone.cardtrainer.ui.PokerScreen
import xyz.nextalone.cardtrainer.ui.SettingsScreen
import xyz.nextalone.cardtrainer.util.PlatformBackHandler

sealed class Route {
    data object Home : Route()
    data object Poker : Route()
    data object Mahjong : Route()
    data object Settings : Route()
}

@Composable
fun App(darkTheme: Boolean = false) {
    val colors = if (darkTheme) {
        darkColorScheme(primary = Color(0xFF66BB6A))
    } else {
        lightColorScheme(primary = Color(0xFF0E7C3A))
    }

    val settings = remember { AppSettings(provideSettings()) }
    var route by remember { mutableStateOf<Route>(Route.Home) }

    // Pop the current screen back to Home on platform back gesture / key.
    PlatformBackHandler(enabled = route != Route.Home) { route = Route.Home }

    MaterialTheme(colorScheme = colors) {
        Surface(color = MaterialTheme.colorScheme.background) {
            when (route) {
                Route.Home -> HomeScreen(
                    onOpenPoker = { route = Route.Poker },
                    onOpenMahjong = { route = Route.Mahjong },
                    onOpenSettings = { route = Route.Settings },
                )
                Route.Poker -> PokerScreen(
                    settings = settings,
                    onBack = { route = Route.Home },
                )
                Route.Mahjong -> MahjongScreen(
                    settings = settings,
                    onBack = { route = Route.Home },
                )
                Route.Settings -> SettingsScreen(
                    settings = settings,
                    onBack = { route = Route.Home },
                )
            }
        }
    }
}
