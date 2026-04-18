package xyz.nextalone.cardtrainer

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "LLM 棋牌训练器",
        state = rememberWindowState(width = 900.dp, height = 720.dp),
    ) {
        App()
    }
}
