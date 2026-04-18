@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import xyz.nextalone.cardtrainer.coach.ProviderKind
import xyz.nextalone.cardtrainer.storage.AppSettings

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    var activeKind by remember { mutableStateOf(settings.providerKind) }
    var editingKind by remember { mutableStateOf(activeKind) }
    var apiKey by remember(editingKind) { mutableStateOf(settings.apiKey(editingKind)) }
    var baseUrl by remember(editingKind) { mutableStateOf(settings.baseUrl(editingKind)) }
    var model by remember(editingKind) { mutableStateOf(settings.model(editingKind)) }
    var saved by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 教练设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("接口类型（编辑 / 切换）", style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderKind.entries.forEach { kind ->
                    val isActive = kind == activeKind
                    val isEditing = kind == editingKind
                    Column {
                        FilterChip(
                            selected = isEditing,
                            onClick = { editingKind = kind; saved = false },
                            label = {
                                val tag = if (isActive) "  · 当前使用中" else ""
                                Text("${kind.label}$tag")
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; saved = false },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; saved = false },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; saved = false },
                label = { Text("模型 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    settings.setApiKey(editingKind, apiKey.trim())
                    settings.setBaseUrl(
                        editingKind,
                        baseUrl.trim().ifEmpty { editingKind.defaultBaseUrl },
                    )
                    settings.setModel(
                        editingKind,
                        model.trim().ifEmpty { editingKind.defaultModel },
                    )
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存该接口的配置") }

            OutlinedButton(
                onClick = {
                    settings.providerKind = editingKind
                    activeKind = editingKind
                    saved = true
                },
                enabled = editingKind != activeKind,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("切换为当前使用接口") }

            if (saved) {
                Text("已保存。", color = MaterialTheme.colorScheme.primary)
            }

            Text(
                "• Anthropic 会自动启用 ephemeral 系统提示缓存。\n" +
                    "• OpenAI 兼容接口同样支持自定义 Base URL：DeepSeek、Moonshot、Together、OpenRouter、Ollama/vLLM 代理均可直接填入。\n" +
                    "• Key 与 Base URL 仅保存于本机（Android SharedPreferences / macOS java.util.prefs）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
