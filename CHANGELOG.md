# 更新日志（Changelog）

本文件记录对外发布的版本变更。版本号遵循语义化版本（主版本.功能版本.修复版本），
`versionCode` 单调递增，安装时系统据此识别覆盖升级。所有 APK 均使用项目内固化的
同一把签名（`app/deepseek-release.keystore`），保证新版可直接覆盖安装、数据无损保留。

## [2.0.0] - 2026-09-26

> versionCode 20 · 适配 DeepSeek 官方 2026-09-09 发布的新模型 **V4.1 Flash**

### 新增 / 适配

- **适配 DeepSeek V4.1 Flash**（2026-09-09 官方发布：552B MoE、全新 Causal-Encoder-Decoder
  非对称架构，官方多维度基准已全面超越上代旗舰 V4 Pro）：
  - 对外模型目录（`/v1/models`）新增 `deepseek-v4.1-flash` 作为**默认模型**；
  - 新增官方 API 同名别名 `deepseek-flash`，等价路由；
  - 旧模型名 `deepseek-v4-flash` 保留为兼容别名（官方已将 V4 Flash 下线并路由到 V4.1 Flash）；
  - 网页端「快速模式」底层已由官方切换为 V4.1 Flash，本客户端请求结构（`model_type`
    字段）无需变更，服务端自动生效。
- `CHANGELOG.md`、GitHub Actions CI/CD（自动缓存编译 + 固化签名打包 + Tag 自动 Release）。

### 变更

- **V4 Pro 有序下线跟进**：官方自 2026-09-14 12:00（北京时间）起将所有 `deepseek-v4-pro`
  请求路由到 V4.1 Flash。应用内「专业模式」语义保留为「深度思考 · 专家推理」，
  仍可通过 `model=deepseek-v4-pro` / `expert` 触发 expert 通道 + thinking。
- 应用内帮助 / 教程 / 关于页文案同步更新为 V4.1 Flash 语境。
- UI 模型描述更新：快速模式 → “V4.1 Flash · 快速响应”；专业模式 → “深度思考 · 专家推理”。

### 修复

- 清理 `gradle.properties` 中的本机专用配置（`org.gradle.java.home`、本地代理端口），
  避免污染 CI 构建；本地个性化配置请写入 `~/.gradle/gradle.properties`。
- 新增 `.gitignore`，`build/`、`.gradle/`、`local.properties` 不再入库。

## 历史（≤ 1.9.2）

离线分发的早期版本（功能包括：OpenAI 兼容本地服务、内置 WebView 登录、多账号池、
风控治理、Agent 工具桥接、一键接入卡片等），变更记录从略。自 v2.0.0 起项目托管于
GitHub，此后所有版本变更均记录在本文件。

[2.0.0]: https://github.com/workbuddy-agent/deepseek-local-api/releases/tag/v2.0.0
