# ElinaAI V18

Gemini Live + VRM companion + explicit memory + Android device tools + wake-word prototype + responsive portrait/landscape UI. 5.3.1 · 3D AI Companion

> **Android AI voice companion with a bundled VRM avatar, memory, voice input/output and Gemini-compatible chat.**
> Offline-first AI guide for museums, art galleries, and cultural sites — running on Android tablets / all-in-one displays.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Status](https://img.shields.io/badge/status-v0.1%20alpha-orange)
![Platform](https://img.shields.io/badge/platform-Android%208.0+-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF)
![Build](https://img.shields.io/badge/build-passing-success)

---

## ✨ 已实现的能力（v0.1）

| 模块 | 实现 |
| --- | --- |
| 🗣 **语音输入（ASR）** | sherpa-onnx **Paraformer 中文 int8** 端侧识别（217 MB），按住说话（PTT） |
| 🔊 **语音输出（TTS）** | sherpa-onnx **VITS 中文 zh-ll**（115 MB），**句级流式播报**——LLM 一边出 token，TTS 一边读 |
| 🧠 **大模型（LLM）** | OpenAI 兼容 SSE 流式接口，**自动关闭 thinking 模式**（Gemma 4 / qwq / deepseek-r1 直答不绕弯）；支持 Ollama / OpenAI / 智谱 / 千帆 / Together / Groq |
| 📚 **知识库（RAG）** | 内置**世界八大奇迹**示例库 + 中文双字符 bigram + 别名扩展，开箱即用；接口预留可换 BM25 + jieba |
| 🎭 **真实 3D 形象** | three-vrm 加载 `.vrm` 模型，自动换装；**表情驱动**（idle / 聆听 / 思考 / 回答 / 出错）+ **句级口型同步**（aa/ih/ou/ee/oh）+ 自动眨眼 |
| 💬 **流式对话 UI** | 实时打字效果（Room throttle 持久化）、滚动跟随、用户/Elina气泡区分 |
| 🗂 **历史记录** | Room SQLite，按时间归档，可继续旧会话 |
| 🌐 **横屏一体机适配** | `minSdk 26`、`targetSdk 34`、强制 landscape，单 Activity，4K 大屏实测 |

---

## 🏛️ 应用场景

- **博物馆 / 美术馆**：每件展品配一台一体机，访客按住说话，"这件作品的作者是谁""为什么有破损"，数字Elina实时回答
- **文旅景区**：景点入口设虚拟讲解员，回答路线 / 门票 / 文化背景问题
- **校园 / 企业展厅**：来访者交互式导览
- **AI 玩具 / 陪伴机**：换上儿童 VRM 形象，做端侧故事讲述者

---

## 📸 实测截图（EMEETING_3576 4K 大屏）

> *（待补真机视频；当前为静态截图）*

```
┌────────────────────────────────┬─────────────────────────────┐
│                                │   ElinaAI               │
│         🎭 VRM 数字Elina       │                             │
│         （程序化 / VRM 双模）  │     [访客]                  │
│                                │     讲讲秦始皇兵马俑         │
│                                │                             │
│                                │     [Elina（流式打字）]     │
│                                │     兵马俑出土于 1974 年    │
│         [● 思考]               │     陕西临潼，由约 8000 件 │
│                                │     陶俑组成 ...            │
│                                │                             │
│                                │     ────  按住说话  ────    │
└────────────────────────────────┴─────────────────────────────┘
```

---

## 🏗️ 架构

```
┌──────────────────────────────────────────────────────────────┐
│                      MainActivity (Landscape)                │
│  ┌─────────────────────┐    ┌────────────────────────────┐   │
│  │   Avatar WebView    │    │   Chat Panel               │   │
│  │   (Three.js + VRM)  │    │   ┌────────────────────┐   │   │
│  │                     │    │   │  Toolbar           │   │   │
│  │   ┌───────────┐     │    │   ├────────────────────┤   │   │
│  │   │  Avatar   │◀────┼────┤   │  Streaming         │   │   │
│  │   │  Bridge   │     │    │   │  RecyclerView      │   │   │
│  │   │ JS↔Kotlin │     │    │   ├────────────────────┤   │   │
│  │   └───────────┘     │    │   │  Status + PTT btn  │   │   │
│  └─────────────────────┘    │   └────────────────────┘   │   │
└─────────────────────────────┴────────────────────────────────┘
                              │
                              ▼
                  ┌─────────────────────────┐
                  │     ChatViewModel       │
                  │  (Dialog State Machine) │
                  └────────┬────────────────┘
            ┌──────────────┼──────────────┬──────────────┐
            ▼              ▼              ▼              ▼
       AsrManager     LlmClient      TtsManager     ChatRepository
   (Sherpa or Stub)  (OpenAI SSE   (Sherpa or Stub) (Room SQLite)
                       think:false)
                          │                              │
                          ▼                              ▼
                    RagRetriever                   conversations
                  (8 Wonders demo)                  + messages
```

**对话状态机**：

```
IDLE ──[按住 PTT]──▶ LISTENING
                           │ [松开 + ASR 识别]
                           ▼
                       THINKING ──[首个 LLM token]──▶ SPEAKING
                                                          │ [流结束 + TTS 排空]
                                                          ▼
                                                        IDLE
```

---

## 🚀 快速开始（5 分钟跑通）

### 前置

- Android Studio Hedgehog (2023.1) 或更新
- JDK 17、ADB
- Android 8.0+（API 26+）设备，**横屏**最佳；4K 一体机最佳
- LLM 提供方：本地 Ollama（推荐）或任意 OpenAI 兼容服务

### 1. Clone

```bash
git clone https://github.com/liwei0513/ElinaAI.git
cd ElinaAI
```

### 2. 拉取离线依赖（一次性）

```bash
# sherpa-onnx 原生库 + Kotlin API + 中文 ASR/TTS 模型 (~334 MB)
bash scripts/setup-sherpa.sh --models

# VRM 数字人模型（默认 AvatarSample_B，~15 MB）
bash scripts/setup-avatar.sh
```

### 3. 配置 LLM 服务端

打开 `app/src/main/kotlin/io/touravatar/util/AppConfig.kt`：

```kotlin
var llmBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/"   // 你的 Gemini-compatible base URL
var llmApiKey  = ""                                // Ollama 不需要
var llmModel   = "gemma4:latest"                   // 或 qwen2.5:7b / glm-4-flash
```

| 提供方 | baseUrl | model | 备注 |
| --- | --- | --- | --- |
| **Ollama**（推荐） | `http://<lan-ip>:11434/v1` | `gemma4:latest` / `qwen2.5:7b` | 局域网部署、无云依赖 |
| **OpenAI** | `https://api.openai.com/v1` | `gpt-4o-mini` | |
| **智谱 GLM** | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` | |
| **百度千帆** | `https://qianfan.baidubce.com/v2` | `ernie-speed-128k` | |

> ⚠️ 用 Gemma 4 / qwq / deepseek-r1 等带 thinking 的模型，本仓库**默认 `disableThinking=true`** 关闭推理过程，直接出回答。要看推理过程改 `AppConfig.disableThinking = false`。

### 4. 编译 + 推 ASR/TTS 模型 + 安装

```bash
# 编译 debug APK
./gradlew assembleDebug

# 推模型到设备（替换为你的 adb serial）
bash scripts/setup-sherpa.sh --models --push <ADB_SERIAL>

# 装 APK
adb -s <ADB_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk

# 起来！
adb -s <ADB_SERIAL> shell am start -n com.elina.assistant/.MainActivity
```

跑通后效果：左侧 VRM 真人化数字人（呼吸、眨眼、张嘴口型），右侧聊天面板，按住下方 PTT 圆按钮说话即可。

### 5. （可选）替换为你自己的 VRM 形象

把任意 `.vrm` 文件命名为 `model.vrm` 放到 `app/src/main/assets/avatar/` 即可。推荐来源：

- [VRoid Hub](https://hub.vroid.com/)（免费）
- [Booth.pm](https://booth.pm/)（免费 / 付费）
- [VRoid Studio](https://vroid.com/studio)（自己捏一个穿制服的Elina）

---

## 📁 关键代码地图

```
ElinaAI/
├── app/src/main/
│   ├── kotlin/io/touravatar/
│   │   ├── MainActivity.kt                  ★ 入口；启动时择机选 Sherpa 或 Stub
│   │   ├── HistoryActivity.kt
│   │   ├── chat/
│   │   │   ├── DialogState.kt               对话状态机
│   │   │   └── ChatViewModel.kt             ★ ASR → LLM(stream) → TTS 编排
│   │   ├── voice/
│   │   │   ├── AsrManager.kt                接口（Started/Partial/Final/Stopped/Error）
│   │   │   ├── SherpaAsrManager.kt          ★ AudioRecord + OfflineRecognizer
│   │   │   ├── StubAsrManager.kt            模型缺失时的占位
│   │   │   ├── TtsManager.kt                接口（句级流式合成）
│   │   │   ├── SherpaTtsManager.kt          ★ generateWithCallback + AudioTrack
│   │   │   └── SherpaModelLayout.kt         约定：/<files>/models/{asr,tts}/
│   │   ├── llm/
│   │   │   ├── LlmClient.kt
│   │   │   ├── ChatMessage.kt               含 think + reasoning 字段
│   │   │   └── OpenAiCompatibleClient.kt    ★ SSE 解析；encodeDefaults=true
│   │   ├── data/
│   │   │   ├── AppDatabase.kt               Room
│   │   │   ├── Conversation/MessageEntity   实体
│   │   │   └── ChatRepository.kt            流式更新（throttled persist）
│   │   ├── rag/
│   │   │   ├── RagRetriever.kt              接口（含 aliases 字段）
│   │   │   └── InMemoryRagRetriever.kt      ★ 内置「世界八大奇迹」 + bigram 检索
│   │   ├── ui/
│   │   │   ├── AvatarBridge.kt              Kotlin ↔ JS（@JavascriptInterface）
│   │   │   ├── ChatAdapter.kt
│   │   │   └── HistoryAdapter.kt
│   │   └── util/
│   │       ├── AppConfig.kt                 ★ 全局配置（LLM / RAG / thinking）
│   │       └── Logging.kt
│   ├── kotlin/com/k2fsa/sherpa/onnx/        sherpa-onnx 上游 Apache-2.0 绑定
│   ├── assets/avatar/
│   │   ├── index.html                       WebView + importmap
│   │   ├── style.css
│   │   ├── avatar.js                        ★ three-vrm 渲染 + 表情/口型/眨眼
│   │   └── model.vrm                        🚫 .gitignore（脚本下载）
│   ├── jniLibs/arm64-v8a/                   🚫 .gitignore（sherpa .so）
│   └── res/...                              ConstraintLayout / Material3 主题
├── scripts/
│   ├── setup-sherpa.sh                      libs + Kotlin API + 模型 + adb push
│   └── setup-avatar.sh                      VRM 默认模型下载
├── docs/blog/                               技术博客（中文）
└── ...
```

---

## 🛣️ 路线图

### v0.1（已发布）
- [x] 横屏 UI + Avatar WebView + 聊天列表 + PTT
- [x] OpenAI 兼容流式 LLM 客户端，自动 `think:false`
- [x] Room 持久化 + 历史对话页
- [x] sherpa-onnx 真实 ASR（Paraformer-zh int8）
- [x] sherpa-onnx 真实 TTS（VITS-zh-ll）+ 句级流式
- [x] three-vrm 真人化数字人 + 表情/口型/眨眼
- [x] 内置「世界八大奇迹」RAG 示例库
- [x] 4K 一体机实测验证（EMEETING_3576）

### v0.2（计划中）
- [ ] BM25 + jieba 真正的中文 RAG 索引
- [ ] 馆方知识库导入工具（CSV / JSON / Notion API）
- [ ] 多语言支持（英 / 日 / 韩，面向境外游客）
- [ ] 展品 NFC / 二维码触发对话
- [ ] reasoning chunks 可选展示为"思考过程"侧栏

### v0.5（旗舰版）
- [ ] [Duix-Mobile](https://github.com/duixcom/Duix-Mobile) 接入：端侧真人级 photoreal 数字人（<120ms）
- [ ] 馆方后台：知识库管理 + 对话审计 + 数据回流
- [ ] 自定义服装 / 配音克隆（让数字人变成"该馆代言人"）

---

## 🤝 商业合作

| 部署形态 | 适合场景 | 起价（建议） |
| --- | --- | --- |
| **Lite 版**（程序化或 VRM） | 县/区博物馆、小型展厅、试点 | 5-10 万 |
| **Pro 版**（VRM + 馆方品牌定制） | 市级博物馆、文旅景区、企业展厅 | 15-30 万 |
| **Flagship 版**（Duix-Mobile photoreal） | 省级博物馆、5A 景区、网红打卡馆 | 50-80 万 |

提 issue 或 [GitHub Discussions](https://github.com/liwei0513/ElinaAI/discussions) 留言。

---

## 📜 License

代码本体：**MIT** — see [LICENSE](LICENSE)

第三方组件遵循各自协议：
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — Apache 2.0
- [three-vrm](https://github.com/pixiv/three-vrm) — MIT
- [Three.js](https://github.com/mrdoob/three.js) — MIT
- [VRoid Sample Models](https://github.com/madjin/vrm-samples) — CC-with-conditions / CC0

部署或合作请提 issue / discussions / DM。
