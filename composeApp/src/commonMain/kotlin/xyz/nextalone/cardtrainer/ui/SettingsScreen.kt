@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.nextalone.cardtrainer.coach.ProviderConfig
import xyz.nextalone.cardtrainer.coach.ProviderKind
import xyz.nextalone.cardtrainer.coach.ReasoningMode
import xyz.nextalone.cardtrainer.coach.TestResult
import xyz.nextalone.cardtrainer.coach.testConnection
import xyz.nextalone.cardtrainer.storage.AppSettings
import xyz.nextalone.cardtrainer.storage.settingsEncrypted

@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    var activeKind by remember { mutableStateOf(settings.providerKind) }
    var editingKind by remember { mutableStateOf(activeKind) }
    var apiKey by remember(editingKind) { mutableStateOf(settings.apiKey(editingKind)) }
    var baseUrl by remember(editingKind) { mutableStateOf(settings.baseUrl(editingKind)) }
    var model by remember(editingKind) { mutableStateOf(settings.model(editingKind)) }
    var maxTokensText by remember(editingKind) {
        mutableStateOf(settings.maxTokens(editingKind).toString())
    }
    var reasoningMode by remember(editingKind) {
        mutableStateOf(settings.reasoningMode(editingKind))
    }
    var saved by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    val scope = rememberCoroutineScope()

    val formContent: @Composable () -> Unit = {
            if (!settingsEncrypted()) {
                PlaintextStorageWarning()
            }

            Text("接口类型（编辑 / 切换）", style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderKind.entries.forEach { kind ->
                    val isActive = kind == activeKind
                    val isEditing = kind == editingKind
                    Column {
                        FilterChip(
                            selected = isEditing,
                            onClick = { editingKind = kind; saved = false; testResult = null },
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
                onValueChange = { apiKey = it; saved = false; testResult = null },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; saved = false; testResult = null },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; saved = false; testResult = null },
                label = { Text("模型 ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("推理模式", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReasoningMode.entries.forEach { mode ->
                    FilterChip(
                        selected = reasoningMode == mode,
                        onClick = {
                            reasoningMode = mode
                            saved = false
                            testResult = null
                        },
                        label = { Text(mode.label) },
                    )
                }
            }
            Text(
                "自动 = 按模型 id 猜；推理模型 = 强制走 extended thinking / " +
                    "max_completion_tokens + reasoning_effort；标准对话 = 关闭。",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = maxTokensText,
                onValueChange = { new ->
                    // Tolerate empty while editing; only digits persist.
                    maxTokensText = new.filter { it.isDigit() }.take(6)
                    saved = false
                    testResult = null
                },
                label = { Text("单次输出上限 (max tokens)") },
                supportingText = {
                    Text(
                        "推理模型（Claude extended thinking / DeepSeek-R1 等）建议 ≥ 8192；" +
                            "默认 ${editingKind.defaultMaxTokens}",
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                        val parsedMax = maxTokensText.toIntOrNull()
                            ?: editingKind.defaultMaxTokens
                        settings.setMaxTokens(editingKind, parsedMax)
                        settings.setReasoningMode(editingKind, reasoningMode)
                        // Echo the clamped value back into the field so the
                        // user sees what was actually stored.
                        maxTokensText = settings.maxTokens(editingKind).toString()
                        saved = true
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }

                OutlinedButton(
                    onClick = {
                        testResult = null
                        testing = true
                        scope.launch {
                            testResult = testConnection(
                                ProviderConfig(
                                    kind = editingKind,
                                    apiKey = apiKey.trim(),
                                    baseUrl = baseUrl.trim().ifEmpty { editingKind.defaultBaseUrl },
                                    model = model.trim().ifEmpty { editingKind.defaultModel },
                                    maxTokens = maxTokensText.toIntOrNull()
                                        ?: editingKind.defaultMaxTokens,
                                    reasoningMode = reasoningMode,
                                ),
                            )
                            testing = false
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier.weight(1f),
                ) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("测试中…")
                    } else {
                        Text("测试连接")
                    }
                }
            }

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

            testResult?.let { TestResultCard(it) }

            Text(
                "• Anthropic 会自动启用 ephemeral 系统提示缓存。\n" +
                    "• OpenAI 兼容接口同样支持自定义 Base URL：DeepSeek、Moonshot、Together、OpenRouter、Ollama/vLLM 代理均可直接填入。\n" +
                    "• Key 与 Base URL 仅保存于本机（Android 端 EncryptedSharedPreferences / macOS java.util.prefs）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            EngineToggle(settings)
    }

    xyz.nextalone.cardtrainer.ui.components.WithDeviceMode { mode ->
        val isPhone = mode == xyz.nextalone.cardtrainer.ui.components.DeviceMode.Phone
        val body: @Composable () -> Unit = {
            val maxW = if (isPhone) Modifier.fillMaxWidth() else Modifier.widthIn(max = 680.dp)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = if (isPhone) 16.dp else 32.dp,
                        vertical = if (isPhone) 14.dp else 22.dp,
                    ),
            ) {
                Column(maxW, verticalArrangement = Arrangement.spacedBy(14.dp)) { formContent() }
            }
        }
        if (isPhone) {
            xyz.nextalone.cardtrainer.ui.components.PhoneShell(
                eyebrow = "PROVIDER · 配置",
                title = "AI 教练设置",
                onBack = onBack,
                body = body,
            )
        } else {
            xyz.nextalone.cardtrainer.ui.components.DesktopShell(
                eyebrow = "PROVIDER · 配置",
                title = "AI 教练设置",
                windowLabel = "LLM Card Trainer · Settings",
                onBack = onBack,
                body = body,
            )
        }
    }
}

@Composable
private fun EngineToggle(settings: AppSettings) {
    var enabled by remember { mutableStateOf(settings.multiwayEngineEnabled) }
    var opponents by remember { mutableStateOf(settings.multiwayOpponents) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("德扑引擎（实验）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(6.dp))
            Text(
                "默认引擎为单 villain 脚本；开启后改用新版多人引擎（range 决策、" +
                    "多家摊牌、边池、三视角 AI 分析）。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.size(8.dp))
            FilterChip(
                selected = enabled,
                onClick = {
                    enabled = !enabled
                    settings.multiwayEngineEnabled = enabled
                },
                label = { Text(if (enabled) "新引擎（已启用）" else "启用新引擎") },
            )
            Spacer(Modifier.size(10.dp))
            Text("桌面对手数（hero 加上这些人）", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.size(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (n in 1..5) {
                    FilterChip(
                        selected = opponents == n,
                        onClick = {
                            opponents = n
                            settings.multiwayOpponents = n
                        },
                        label = { Text("$n 对手") },
                    )
                }
            }
            Spacer(Modifier.size(10.dp))
            var revealNow by remember { mutableStateOf(settings.revealSituationImmediately) }
            FilterChip(
                selected = revealNow,
                onClick = {
                    revealNow = !revealNow
                    settings.revealSituationImmediately = revealNow
                },
                label = {
                    Text(if (revealNow) "情境分析即时揭晓（已开）" else "提交后再揭晓 AI 情境分析")
                },
            )
            Text(
                "默认关闭 · 关闭时 A 槽会等到你提交本街决策后才显示，避免偷看基线；" +
                    "开启后分析完成就展示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PlaintextStorageWarning() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("⚠️ API Key 当前以明文存储", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(4.dp))
            Text(
                "此设备未启用加密存储（macOS 桌面端暂未接入 Keychain；Android 端 Keystore 初始化失败时会退回到明文）。" +
                    "共享电脑或 root 设备上请谨慎填写生产 Key。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TestResultCard(result: TestResult) {
    val (title, body, ok) = when (result) {
        is TestResult.Ok -> Triple("✅ 连接成功", "模型响应：${result.sample}", true)
        is TestResult.Fail -> Triple(
            "❌ ${result.reason}",
            result.detail ?: "（无细节）",
            false,
        )
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
