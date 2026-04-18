# LlmCardTrainer

个人 AI 棋牌训练器。面向**人类玩家**（不是训练 AI 对战机器人）：把当前牌局状态送给 Claude，返回
可执行的决策建议与常见偏差提示。核心规则与牌力算法本地实现，AI 教练只负责"决策与讲解"。

## 支持的训练模块

- **德州扑克（No-Limit Hold'em）**：手牌发牌 → 逐街推进；本地计算
  - 蒙特卡洛胜率（vs N 随机对手）
  - 位置化翻前起手牌基线（UTG/MP/CO/BTN/SB/BB 的 RFI 范围）
  - 翻后 Outs 计数 + Rule-of-2/4 概率
  - 底池赔率
- **四川麻将（血战到底）**：108 张（万/条/筒），支持
  - 定缺 推荐（按弃后向听数排序）
  - 向听数 / 听牌检测 / 胡牌检测
  - 胡牌型识别（平胡 / 对对胡 / 七对 / 龙七对 / 清一色 / 根）
  - 有效进张 + 活墙剩余枚数
  - 弃牌候选综合评分 + 安全度（现张、边张）

每次把上述结构化数据送入 LLM，让模型给出推荐动作 + 简短理由 + 偏差提示。

支持两种 LLM 接口，并可自定义 Base URL：

- **Anthropic**（默认 `https://api.anthropic.com`）— `x-api-key`，系统提示启用 `ephemeral` 缓存。
- **OpenAI / 兼容**（默认 `https://api.openai.com/v1`）— `Authorization: Bearer`，任何
  `/chat/completions` 兼容端点均可（DeepSeek / Moonshot / Together / OpenRouter / Ollama-OpenAI 桥 / vLLM …）。

在设置页可分别保存两套配置，再切换当前使用的接口。

## 技术栈

- **Kotlin Multiplatform 2.1** + **Compose Multiplatform 1.7**
- **Android**：arm64-v8a only（APK）
- **macOS**：arm64 only（Apple Silicon），通过 Desktop JVM + `jpackage` 输出 `.dmg`/`.pkg`
- **Ktor 3** HTTP 客户端（OkHttp on Android/JVM）
- **multiplatform-settings** 本地保存配置

## 准备

1. 安装 JDK 17（macOS：`brew install --cask temurin@17`）
2. Android：Android Studio Giraffe+（SDK 35）。设置 `ANDROID_HOME` 或在项目根加 `local.properties`：

   ```
   sdk.dir=/Users/you/Library/Android/sdk
   ```

3. 启动 App 后进入「设置」，选择接口类型（Anthropic / OpenAI 兼容），填 API Key + Base URL + 模型 ID。
   配置仅保存于本机（Android SharedPreferences / macOS `java.util.prefs`）。

## 构建

**Android APK**（arm64-v8a）

```bash
./gradlew :composeApp:assembleDebug
# APK: composeApp/build/outputs/apk/debug/composeApp-arm64-v8a-debug.apk
```

**macOS 本地运行**（Apple Silicon）

```bash
./gradlew :composeApp:run
```

**macOS 原生安装包**

```bash
./gradlew :composeApp:packageDmg     # 产物 composeApp/build/compose/binaries/main/dmg/
./gradlew :composeApp:packagePkg
```

## CI

`.github/workflows/ci.yml` 在 push 到 `main` 与任意 PR 时触发：

- `android` job：`ubuntu-latest` → assembleDebug，产出 arm64-v8a APK artifact
- `macos` job：`macos-14`（Apple Silicon）→ packageDmg，产出 arm64 `.dmg` artifact

## 目录结构

```
composeApp/
└─ src/
   ├─ commonMain/kotlin/com/nextalone/cardtrainer/
   │  ├─ App.kt                      # Compose 根 + 路由
   │  ├─ coach/                      # Claude Messages API 客户端 & 提示词
   │  ├─ engine/holdem/              # 德州扑克引擎
   │  │   ├─ Cards.kt / HandEval.kt  # 7 张最佳牌型求解
   │  │   ├─ Equity.kt               # 蒙特卡洛胜率
   │  │   ├─ PreflopChart.kt         # 6-max RFI 基线
   │  │   ├─ Outs.kt                 # Outs + Rule of 2/4
   │  │   └─ HoldemGame.kt
   │  ├─ engine/mahjong/             # 四川麻将引擎
   │  │   ├─ Tile.kt                 # 108 张牌墙
   │  │   ├─ HandCheck.kt            # 胡牌 / 听牌 / 向听
   │  │   ├─ HandType.kt             # 牌型分类
   │  │   ├─ DingQue.kt              # 定缺推荐
   │  │   ├─ UkeIre.kt               # 有效进张 + 活墙
   │  │   ├─ Safety.kt               # 弃牌安全度
   │  │   └─ SichuanTrainer.kt
   │  ├─ storage/AppSettings.kt      # 本地设置（expect）
   │  └─ ui/                         # Home / Poker / Mahjong / Settings
   ├─ androidMain/                   # MainActivity + SharedPreferencesSettings
   └─ desktopMain/                   # Main.kt + PreferencesSettings
```

## 参考 & 致谢

德州扑克方向（仅作为算法参考，均为 AI 对战/研究向，本项目是人类训练向）：

- [Gongsta/Poker-AI](https://github.com/Gongsta/Poker-AI) — CFR 单挑 NLH，带底池赔率训练器变体
- [fedden/poker_ai](https://github.com/fedden/poker_ai) — 成熟的 CFR 框架
- [dickreuter/neuron_poker](https://github.com/dickreuter/neuron_poker) — 蒙特卡洛胜率与可视化灵感
- [datamllab/rlcard](https://github.com/datamllab/rlcard) — 规则建模参考

四川麻将方向：主流开源项目几乎没有（规则特殊：定缺 / 血战到底 / 刮风下雨），本项目的引擎
为自研。若要扩展到 AI 对局级别，可参考：

- [yuanfengyun/mj_ai](https://github.com/yuanfengyun/mj_ai) — 通用麻将概率与深度学习
- [esrrhs/majiang_algorithm](https://github.com/esrrhs/majiang_algorithm) — 胡牌算法参考
- [latorc/MahjongCopilot](https://github.com/latorc/MahjongCopilot) — 日麻实时助手（规则不同，仅作交互参考）

## 许可

个人使用。第三方依赖按其各自协议。
