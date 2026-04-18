package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown

/**
 * Render arbitrary markdown (headings, lists, code, emphasis) with the app's
 * Material 3 theme. Kept in a single place so we can swap the renderer later
 * without touching every call site.
 */
@Composable
fun AiMarkdown(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Markdown(
            content = text,
            colors = com.mikepenz.markdown.m3.markdownColor(
                text = MaterialTheme.colorScheme.onSurface,
            ),
            typography = com.mikepenz.markdown.m3.markdownTypography(
                h1 = MaterialTheme.typography.titleLarge,
                h2 = MaterialTheme.typography.titleMedium,
                h3 = MaterialTheme.typography.titleSmall,
                text = MaterialTheme.typography.bodyMedium,
                paragraph = MaterialTheme.typography.bodyMedium,
                list = MaterialTheme.typography.bodyMedium,
                code = MaterialTheme.typography.bodySmall,
            ),
        )
    }
}
