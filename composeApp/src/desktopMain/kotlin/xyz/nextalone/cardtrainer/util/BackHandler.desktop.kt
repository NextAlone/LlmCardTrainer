package xyz.nextalone.cardtrainer.util

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop: no OS back gesture; the TopAppBar's back arrow handles navigation.
}
