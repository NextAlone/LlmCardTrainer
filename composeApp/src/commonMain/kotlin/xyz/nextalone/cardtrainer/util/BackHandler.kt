package xyz.nextalone.cardtrainer.util

import androidx.compose.runtime.Composable

/**
 * Platform back-gesture hook. Android delegates to the activity's
 * OnBackPressedDispatcher; desktop is a no-op (the window chrome already
 * provides navigation via the top-bar arrow).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
