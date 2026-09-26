# DeepSeek Local API (Android)

把 DeepSeek 网页版（chat.deepseek.com）的**免费对话**能力，转换成跑在 Android 手机上的**本地 OpenAI 兼容 API** 服务。

借助内置 WebView 一键登录自动提取 token，无需 root，适合 Android 10+（已在 Android 11 目标环境设计）。电脑上的编程工具（如 Operating AI、Cursor、Codex 等）只要支持自定义 **Base URL + API Key**，就能直连手机上的这个本地服务，用 DeepSeek 网页端账号回答，不消耗官方付费 API 额度。

> ⚠️ 免责声明：本项目仅用于个人学习与研究 DeepSeek 网页版协议。逆向接口随时可能变更，且不保证永不封号。请在遵守 DeepSeek 服务条款的前提下使用。

---

## 特性

- **OpenAI 兼容接口**：`/v1/models`、`/v1/chat/completions`（支持流式与非流式、`model` 参数、Bearer 鉴权、CORS）。
- **适配 DeepSeek V4.1 Flash**（2026-09 官方新模型）：默认模型 `deepseek-v4.1-flash`，旧名 `deepseek-v4-flash` / `deepseek-flash` 自动兼容路由。
- **专家模式（深度思考）**：应用内开关一键切换，也可直接请求 `model=deepseek-v4-pro`（或 `expert`）触发；原 V4 Pro 已由官方下线并路由到 V4.1 Flash。
- **深度思考（thinking）**：支持 `model=deepseek-reasoner` 或 `deepseek-r1` 触发思考模式，思考过程单独返回。
- **内置登录**：WebView 跳转官方登录，登录后自动提取 token，小白也能用。
- **本地 API Key**：首次启动自动生成 `sk-...`，可在应用内查看/重置。
- **前台服务保活**：切后台不打断服务，局域网内其它设备可访问（可开关）。
- **纯 Kotlin 引擎**：自研 PoW 求解 + SSE 解析，不依赖任何第三方逆向库。

---

## 架构

```
┌────────────────────────────────────────────────────────────┐
│                       Android APP                          │
│                                                             │
│  MainActivity ── LoginActivity(WebView)                     │
│       │                │ 提取 token                          │
│       ▼                ▼                                    │
│  Settings / ApiKey(SharedPreferences)                       │
│       │                                                      │
│       ▼                                                      │
│  LocalServer (NanoHTTPD, 监听 0.0.0.0:8080)  ◄── 接入工具     │
│       │  /v1/chat/completions                                │
│       ▼                                                      │
│  DeepSeekClient (OkHttp + SSE)                               │
│       │  建会话 → 解PoW → 发prompt → 解析SSE                  │
│       ▼                                                      │
│  chat.deepseek.com（官方网页端免费接口）                        │
└────────────────────────────────────────────────────────────┘
```

关键源码位置：

| 文件 | 作用 |
| --- | --- |
| `core/Models.kt` | 协议常量、PoW Challenge、SSE 事件、模型目录 |
| `core/Pow.kt` | DeepSeekHashV1（Keccak-f[1600] 23 轮）PoW 求解 |
| `core/DeepSeekClient.kt` | 会话创建、PoW 头、对话请求、SSE 流解析 |
| `server/LocalServer.kt` | OpenAI 兼容 HTTP 服务（鉴权/流式/非流式/CORS） |
| `store/Settings.kt` | 用户 token、专家模式、端口、局域网开关 |
| `store/ApiKey.kt` | 本地 `sk-...` API Key 生成与校验 |
| `ServerService.kt` | 前台服务，托管 LocalServer 保活 |
| `MainActivity.kt` | 主界面：开关/Key/URL/日志/登录 |
| `LoginActivity.kt` | WebView 登录并自动提取 token |

---

## 构建

环境要求：JDK 17、Android SDK（`compileSdk 34`）。

### 方式一：GitHub Actions 自动构建（推荐）

推送到 `main` 分支即自动触发 [`.github/workflows/build.yml`](.github/workflows/build.yml)：
JDK 17 + Gradle 依赖缓存 → **项目固化签名**打包（debug + release）→ 上传 Artifacts；
推送 `v*` 标签（如 `v2.0.0`）会额外创建 GitHub Release 并附上签名 APK。

签名说明：项目内固化了一把专用 keystore（`app/deepseek-release.keystore`），
所有版本（含 CI 产物）均用同一把 key 签名，因此**新版 APK 可直接覆盖安装**、
数据无缝保留。版本号见 `app/build.gradle.kts`（如 `DeepSeekLocalAPI-v2.0.0-release.apk`）。

### 方式二：本地构建

```bash
cd deepseek-local-api
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

> 已内置 Gradle Wrapper（Gradle 8.5）。若网络受限，请自行配置代理或本地 SDK 路径（`local.properties` 的 `sdk.dir`）。

---

## 使用

1. **安装并打开 App**，点击「登录 DeepSeek」，在 WebView 中登录你的账号（仅需一次，token 会持久化）。
2. **开启本地服务**：点击「启动服务」。手机上本机地址为 `http://127.0.0.1:8080`。
3. **查看 API Key**：主界面会显示应用自动生成的 `sk-...`，点「重置」可换新。
4. **（可选）专家模式**：打开「专家模式（V4 Pro）」开关后，所有请求默认走专家模型；也可在工具里直接请求模型 `deepseek-v4-pro`。
5. **（可选）局域网访问**：打开「允许局域网访问」，则同一 Wi-Fi 下其它设备可用 `http://<手机IP>:8080` 访问（IP 显示在主页）。

接入工具配置示例：

| 配置项 | 值 |
| --- | --- |
| Base URL | `http://127.0.0.1:8080/v1` |
| API Key | 主页显示的 `sk-...` |
| Model | `deepseek-v4.1-flash`（快速·默认）/ `deepseek-v4-pro`（专家·深度思考） |

curl 快速验证：

```bash
curl http://127.0.0.1:8080/v1/models \
  -H "Authorization: Bearer sk-xxxx"

curl http://127.0.0.1:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-xxxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4.1-flash","stream":false,
       "messages":[{"role":"user","content":"你好"}]}'
```

---

## 模型目录（v2.0.0 · 适配 V4.1 Flash）

| 请求 model | 行为 |
| --- | --- |
| `deepseek-v4.1-flash` | **默认**。网页端快速模式，官方 2026-09-09 发布的新架构模型 |
| `deepseek-flash` | 官方 API 同名别名，等价 `deepseek-v4.1-flash` |
| `deepseek-v4-flash` | 上一代旧名，官方已下线并由服务端路由到 V4.1 Flash |
| `deepseek-v4-pro` / 含 `expert` | 专家通道 + 深度思考（原 V4 Pro 已被官方路由到 V4.1 Flash） |
| `deepseek-reasoner` / `deepseek-r1` | 深度思考 |

---

## 专家模式说明

- 网页端「专家模式」是一个 UI 开关（深度思考），对应 expert 请求通道。
- 本实现通过两种方式触发：
  1. 应用内开关：`设置.automation.expertMode = true`，所有请求默认附加专家字段。
  2. 模型名：请求 `model` 含 `v4-pro` 或 `expert` 时自动附加。
- 附加字段定义在 `core/Models.kt` 的 `Protocol.EXPERT_FIELD / EXPERT_VALUE`（默认 `model=deepseek-v4-pro`）。由于该字段无公开协议文档，若上线后网页端字段变更，改这两处即可。
- 注意：官方自 2026-09-14 起 V4 Pro 有序下线，`deepseek-v4-pro` 请求由官方路由到 V4.1 Flash，直到 V4.1 Pro 上线。

---

## 已知限制

- **依赖官方网页端**：token 失效、接口变更、风控都会影响可用性。
- **逆向接口不稳定**：`Models.kt` 中的路径/字段为最佳实践，上线后如失效需自行排查。
- **单用户串行**：一次仅处理一个对话流，多个工具同时请求会排队。
- **免费版可能限流**：高频请求可能触发网页端频率限制。