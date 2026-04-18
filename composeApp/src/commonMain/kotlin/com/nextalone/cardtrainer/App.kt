package com.nextalone.cardtrainer

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
import com.nextalone.cardtrainer.storage.AppSettings
import com.nextalone.cardtrainer.storage.provideSettings
import com.nextalone.cardtrainer.ui.HomeScreen
import com.nextalone.cardtrainer.ui.MahjongScreen
import com.nextalone.cardtrainer.ui.PokerScreen
import com.nextalone.cardtrainer.ui.SettingsScreen

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
