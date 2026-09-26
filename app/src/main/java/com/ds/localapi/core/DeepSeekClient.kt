package com.ds.localapi.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.ds.localapi.store.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * DeepSeek 网页版（chat.deepseek.com）逆向协议客户端，
 * 通过 HTTP + SSE 与官方网页端通信，不需要官方付费 API Key。
 *
 * 流程：取账号长效会话（无则创建）-> 取 PoW challenge 并求解
 *   -> POST /chat/completion（parent_message_id 恒为 null，上下文全量随 prompt 重注入）
 *   -> 解析 SSE。
 *
 * v2.0.0 对齐 GitHub 参考实现（deepseek-web-proxy / session_store）：
 *  - 每账号一个**长效**会话：会话创建是显著的风控信号，绝不再按线程/按请求新建；
 *    上下文由 LocalServer 全量重注入 prompt，不依赖服务端会话记忆；
 *  - parent_message_id 恒为 null（参考实现同款），避免消息链错位导致的静默失败；
 *  - 会话累计 token 超过 900K 自动续期（V4 1M 上下文留 10% 余量）；
 *  - Referer 按会话拼接：https://chat.deepseek.com/a/chat/s/{session_id}；
 *  - 每次_completion 前现取现解 PoW，x-ds-pow-response 缺失会被官方静默拒绝；
 *  - 每账号固定一组浏览器指纹（频繁换指纹本身是风控信号，仅在风控重试时轮换）；
 *  - SSE 流内识别官方风控错误体（{"code":40001,...}）与 HTML 拦截页。
 */
class DeepSeekClient(private val settings: Settings) {

    /** 多账号池：token / 会话 / 风控轮换的唯一事实源。 */
    val accountPool = com.ds.localapi.store.AccountPool(settings)

    /** 全局频率治理器（LocalServer 也用它读取用量给 UI 显示）。 */
    val rateGovernor = RateGovernor(settings, accountPool)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 流式响应不设读超时
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // 整段对话的硬性时长上限。DeepSeek 深度思考（V4 Pro）会长时间静默，且网页端流
    // 结束后不一定发送 [DONE] 或关闭连接，导致 readLine 永久阻塞、客户端看着像超时。
    // 这里用 watchdog 兜底：到点强制 cancel，绝不让本地服务无限等下去。
    private val llmMaxWaitMs = 600_000L // 10 分钟

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // 每账号槽位 -> 固定浏览器指纹（真人 = 一个账号一台设备，不频繁换 UA）。
    private val slotProfiles = java.util.concurrent.ConcurrentHashMap<Int, BrowserFingerprint.Profile>()

    private fun profileFor(slot: Int): BrowserFingerprint.Profile =
        slotProfiles.getOrPut(slot) { BrowserFingerprint.random() }

    /** 风控重试时才轮换指纹（同时作废会话）。 */
    private fun rotateProfile(slot: Int) {
        slotProfiles[slot] = BrowserFingerprint.nextDifferent()
    }

    private fun buildRequest(
        token: String,
        method: String,
        path: String,
        body: JSONObject?,
        powHeader: String?,
        profile: BrowserFingerprint.Profile,
        refererSessionId: String? = null
    ): Request {
        val url = Protocol.DS_BASE_URL + Protocol.DS_API_PREFIX + path
        val builder = Request.Builder().url(url)
        builder.header("Host", "chat.deepseek.com")
        builder.header("User-Agent", profile.userAgent)
        builder.header("Accept", profile.accept)
        builder.header("Content-Type", "application/json")
        builder.header("Authorization", "Bearer $token")
        // 网页端是 Bearer + Cookie 双重携带（浏览器登录后每个请求都带 userToken cookie）。
        builder.header("Cookie", "userToken=$token")
        builder.header("x-client-platform", profile.platform)
        builder.header("x-client-version", profile.version)
        builder.header("x-client-locale", profile.locale)
        builder.header("accept-language", profile.acceptLanguage)
        builder.header("accept-charset", "UTF-8")
        builder.header("Origin", "https://chat.deepseek.com")
        // 参考实现：Referer 必须精确到当前会话页面，否则被判定为非浏览器流量。
        builder.header(
            "Referer",
            if (refererSessionId.isNullOrEmpty()) "https://chat.deepseek.com/"
            else "https://chat.deepseek.com/a/chat/s/$refererSessionId"
        )
        if (profile.secChUa != null) builder.header("sec-ch-ua", profile.secChUa)
        if (profile.secChUaMobile != null) builder.header("sec-ch-ua-mobile", profile.secChUaMobile)
        if (profile.secChUaPlatform != null) builder.header("sec-ch-ua-platform", profile.secChUaPlatform)
        if (profile.secFetchSite != null) builder.header("sec-fetch-site", profile.secFetchSite)
        if (profile.secFetchMode != null) builder.header("sec-fetch-mode", profile.secFetchMode)
        if (profile.secFetchDest != null) builder.header("sec-fetch-dest", profile.secFetchDest)
        if (!powHeader.isNullOrEmpty()) {
            builder.header("x-ds-pow-response", powHeader)
        }
        if (method == "POST") {
            val payload = body?.toString() ?: "{}"
            builder.post(payload.toRequestBody(jsonMedia))
        } else {
            builder.get()
        }
        return builder.build()
    }

    /**
     * POST /chat_session/create -> 返回会话 id。
     *
     * 官方响应格式多路径兼容：
     *  - 新格式：data.biz_data.id
     *  - 旧格式：data.biz_data.chat_session.id
     *  - 兜底：  data.chat_session.id
     *
     * 403/429 抛 [DsHttpException]（上层按风控处理），401 抛登录失效。
     */
    fun createSession(token: String, profile: BrowserFingerprint.Profile): String = rateGovernor.withSlot {
        val req = buildRequest(token, "POST", Protocol.PATH_CREATE_SESSION, JSONObject(), null, profile)
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (resp.code != 200) {
                throw DsHttpException(resp.code, "创建会话失败 (HTTP ${resp.code}): ${raw.take(200)}")
            }
            val root = JSONObject(raw)
            val data = root.optJSONObject("data")
            val biz = data?.optJSONObject("biz_data")
            val id = biz?.optString("id", null)
                ?: biz?.optJSONObject("chat_session")?.optString("id")
                ?: data?.optJSONObject("chat_session")?.optString("id")
            if (id.isNullOrEmpty()) {
                throw DsHttpException(resp.code, "未能在响应中找到会话 ID: ${raw.take(200)}")
            }
            id
        }
    }

    fun deleteSession(token: String, sessionId: String, profile: BrowserFingerprint.Profile) {
        try {
            val body = JSONObject().put("chat_session_id", sessionId)
            val req = buildRequest(token, "POST", Protocol.PATH_DELETE_SESSION, body, null, profile, sessionId)
            rateGovernor.withSlot { client.newCall(req).execute().close() }
        } catch (_: Exception) {
            // 清理失败可忽略
        }
    }

    /**
     * 获取并求解 PoW challenge，返回 `x-ds-pow-response` 头值；无 challenge 时返回 null。
     *
     * 响应格式多路径兼容（官方把 biz_data 逐渐平铺化）。
     * 解析不到 challenge 或求解失败时记录事件日志——PoW 头缺失会导致 completion
     * 静默空流（表象即「空回复/风控」），必须留下诊断线索。
     */
    fun solvePowHeader(token: String, targetPath: String, profile: BrowserFingerprint.Profile, sessionId: String): String? =
        rateGovernor.withSlot {
            val body = JSONObject().put("target_path", targetPath)
            val req = buildRequest(token, "POST", Protocol.PATH_CREATE_POW, body, null, profile, sessionId)
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.code != 200) {
                    EventLog.log("POW", "PoW 接口 HTTP ${resp.code}：${raw.take(200)}")
                    if (resp.code == 403 || resp.code == 429) {
                        throw DsHttpException(resp.code, "PoW 接口被限流 (HTTP ${resp.code})")
                    }
                    return@withSlot null
                }
                val root = JSONObject(raw)
                val data = root.optJSONObject("data")
                val biz = data?.optJSONObject("biz_data")
                val challenge = biz?.optJSONObject("challenge")
                    ?: biz?.takeIf { it.has("algorithm") && it.has("challenge") }
                    ?: data?.optJSONObject("challenge")
                if (challenge == null) {
                    EventLog.log("POW", "未能定位 PoW challenge（结构可能已变化），原始响应：${raw.take(300)}")
                    return@withSlot null
                }
                val algorithm = challenge.optString("algorithm")
                if (algorithm.isEmpty()) {
                    EventLog.log("POW", "PoW algorithm 为空，原始响应：${raw.take(300)}")
                    return@withSlot null
                }
                val c = PowChallenge(
                    algorithm = algorithm,
                    challenge = challenge.optString("challenge"),
                    salt = challenge.optString("salt"),
                    expireAt = challenge.optLong("expire_at"),
                    difficulty = challenge.optLong("difficulty"),
                    signature = challenge.optString("signature"),
                    targetPath = challenge.optString("target_path")
                )
                try {
                    Pow.solveAndBuildHeader(c)
                } catch (e: Exception) {
                    EventLog.log("POW", "PoW 求解失败：${e.message}")
                    null
                }
            }
        }

    /**
     * 发送对话请求并流式解析 SSE 结果。阻塞直到流结束；增量经 [onEvent] 回调。
     *
     * 上下文由调用方（LocalServer）全量注入 prompt；服务端会话只是「容器」，
     * 每账号复用一个长效会话，parent_message_id 恒为 null。
     *
     * 风控处理：403/429/风控错误体 → 该账号冷却（双账号自动切换）；
     * 会话失效（404/422）→ 作废会话后自动重建重试。
     */
    fun chat(
        prompt: String,
        thinking: Boolean,
        search: Boolean,
        model: String,
        onEvent: (SseEvent) -> Unit
    ) {
        rateGovernor.withSlot {
            var attempt = 0
            val maxAttempts = 4
            while (true) {
                attempt++
                val slot = accountPool.pickAvailable()
                val token = accountPool.tokenAt(slot)
                if (token.isEmpty()) {
                    throw DeepSeekException("账号池中没有可用账号，请先在应用内登录。")
                }
                // 切到冷却中的账号 = 两个账号都在冷却，如实告知（首次进入已由 withSlot 拦截）
                val pickCd = accountPool.cooldownRemainingSec(slot)
                if (pickCd > 0 && attempt > 1) {
                    throw DeepSeekException(
                        "两个账号均处于风控冷却中（账号${slot + 1}剩余 ${pickCd / 60 + 1} 分钟）。" +
                                "冷却会自动结束并恢复服务，无需重新登录。"
                    )
                }
                val result = chatOnce(slot, token, prompt, thinking, search, onEvent)
                if (!result.isEmpty && !result.rateLimited) {
                    rateGovernor.onSuccess(slot)
                    return@withSlot
                }
                // 风控信号上报：限流码立即冷却；空回复连续 2 次冷却
                val reason = if (result.rateLimited) "rate_limit" else "empty_reply"
                val cooled = rateGovernor.onRiskSignal(reason, slot)
                // 双账号模式：本账号被摘下冷却 → 立即切到另一账号继续（会话作废 + 换指纹）
                if (cooled && accountPool.isPoolMode) {
                    val other = accountPool.otherSlot(slot)
                    accountPool.clearSession(slot)
                    rotateProfile(slot)
                    accountPool.switchTo(other)
                    EventLog.log("POOL", "账号${slot + 1} 已摘下冷却，切换到账号${other + 1} 继续服务")
                    continue
                }
                if (rateGovernor.cooldownRemainingSec() > 0) {
                    // 单账号模式（或池尚未生效）：冷却已触发，继续重试没有意义，如实退出
                    throw DeepSeekException(
                        "DeepSeek 风控已触发（${if (result.rateLimited) "收到限流码" else "连续空回复"}），" +
                                "已自动进入冷却 ${rateGovernor.cooldownRemainingSec() / 60} 分钟保护账号。" +
                                "期间请求会被排队或拒绝，稍后会自动恢复。"
                    )
                }
                if (attempt >= maxAttempts) {
                    throw DeepSeekException("DeepSeek 返回空回复（已重试 $maxAttempts 次，可能触发限流或风控）。请稍后再试。")
                }
                // 同账号内重试：作废会话（下次重建）+ 换指纹 + 退避
                accountPool.clearSession(slot)
                rotateProfile(slot)
                EventLog.log("DS", "空回复，第 $attempt 次重试（换指纹 + 新会话）")
                try {
                    Thread.sleep(1200L * attempt + (Math.random() * 600).toLong())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw DeepSeekException("请求被中断")
                }
            }
        }
    }

    /** 单次调用结果：isEmpty=流结束但无内容；rateLimited=收到 403/429/风控错误体。 */
    private data class ParseResult(val isEmpty: Boolean, val rateLimited: Boolean)

    private fun chatOnce(
        slot: Int,
        token: String,
        prompt: String,
        thinking: Boolean,
        search: Boolean,
        onEvent: (SseEvent) -> Unit
    ): ParseResult {
        val profile = profileFor(slot)

        // ---- 长效会话：无则创建；token 超限则续期 ----
        var sid = accountPool.sessionIdAt(slot)
        if (sid.isEmpty()) {
            sid = try {
                createSession(token, profile)
            } catch (e: DsHttpException) {
                when {
                    e.code == 403 || e.code == 429 -> return ParseResult(isEmpty = true, rateLimited = true)
                    e.code == 401 -> throw DeepSeekException("DeepSeek 登录态已失效 (HTTP 401)，请重新登录")
                    else -> throw DeepSeekException(e.message ?: "创建会话失败")
                }
            }
            accountPool.setSession(slot, sid)
            EventLog.log("DS", "账号${slot + 1} 创建长效会话 $sid")
        }
        if (accountPool.sessionTokensAt(slot) > com.ds.localapi.store.AccountPool.SESSION_TOKEN_THRESHOLD) {
            val old = sid
            sid = createSession(token, profile)
            accountPool.setSession(slot, sid)
            deleteSession(token, old, profile)
            EventLog.log("DS", "账号${slot + 1} 会话 token 超限，已续期新会话 $sid")
        }

        // ---- 现取现解 PoW（缺失会被官方静默拒绝）----
        val powHeader = try {
            solvePowHeader(token, Protocol.POW_TARGET_PATH, profile, sid)
        } catch (e: DsHttpException) {
            if (e.code == 403 || e.code == 429) return ParseResult(isEmpty = true, rateLimited = true)
            throw DeepSeekException(e.message ?: "PoW 失败")
        }

        val payload = JSONObject()
        payload.put("chat_session_id", sid)
        // 参考实现：恒为 null。上下文每次全量随 prompt 重注入，不依赖服务端消息链。
        payload.put("parent_message_id", JSONObject.NULL)
        payload.put("prompt", prompt)
        payload.put("ref_file_ids", JSONArray())
        payload.put("thinking_enabled", thinking)
        payload.put("search_enabled", search)
        // 网页端用 model_type 切换快速/专业模式，而非 model 字段。
        payload.put(Protocol.MODEL_TYPE_FIELD, if (thinking) Protocol.MODEL_TYPE_EXPERT else Protocol.MODEL_TYPE_DEFAULT)

        val req = buildRequest(token, "POST", Protocol.PATH_COMPLETION, payload, powHeader, profile, sid)
        val call = client.newCall(req)

        // 在独立守护线程里执行阻塞式 execute + 解析，调用线程用 CountDownLatch 等待并设上限。
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        val got = java.util.concurrent.atomic.AtomicBoolean(false)
        val rateLimited = java.util.concurrent.atomic.AtomicBoolean(false)
        val msgIdRef = AtomicReference(-1L)
        Thread {
            try {
                call.execute().use { resp ->
                    if (resp.code != 200) {
                        val raw = resp.body?.string().orEmpty()
                        when (resp.code) {
                            401 -> failure.set(DeepSeekException("DeepSeek 登录态已失效 (HTTP 401)，请重新登录"))
                            403, 429 -> rateLimited.set(true) // 冷却 + 换指纹重试
                            404, 422 -> {
                                // 会话已失效（过期/被清理）：作废后由上层重建重试
                                EventLog.log("DS", "会话失效 (HTTP ${resp.code})，作废重建")
                                accountPool.clearSession(slot)
                            }
                            else -> throw DeepSeekException("对话请求失败 (HTTP ${resp.code}): ${raw.take(200)}")
                        }
                        return@use
                    }
                    // 内容类型预检：官方风控/WAF 拦截会返回 HTML 或纯文本而非 SSE
                    val ct = resp.header("content-type") ?: ""
                    if (ct.isNotEmpty() && !ct.contains("text/event-stream") && !ct.contains("application/json")) {
                        val raw = resp.body?.string().orEmpty()
                        EventLog.log("SSE", "非 SSE 响应（content-type=$ct）：${raw.take(200)}")
                        rateLimited.set(true)
                        return@use
                    }
                    val reader = BufferedReader(resp.body!!.charStream())
                    parseSse(reader, thinking, onEvent, { got.set(true) }, msgIdRef, rateLimited)
                }
            } catch (e: Exception) {
                failure.set(e)
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true }.start()

        if (!done.await(llmMaxWaitMs, TimeUnit.MILLISECONDS)) {
            call.cancel()
            accountPool.clearSession(slot) // 会话状态未知，强制下次重建
            throw DeepSeekException("等待 DeepSeek 回复超过 ${llmMaxWaitMs / 1000}s，已中止（请重试或改用非思考模式）")
        }
        failure.get()?.let {
            throw it
        }
        if (rateLimited.get()) return ParseResult(isEmpty = true, rateLimited = true)

        // 累计 prompt token（粗估：中文约 2 字符/token）
        accountPool.addSessionTokens(slot, prompt.length / 2L)

        return ParseResult(isEmpty = !got.get(), rateLimited = false)
    }

    /**
     * 解析 DeepSeek 的 SSE 流（JSON-patch 风格：p/o/v 字段）。
     *
     * 对齐参考实现的 _parse_sse：
     *  - 顶层 v.response -> 快照，按 fragments 分发（THINK/ANSWER/RESPONSE）。
     *  - p == response/fragments -> 分片 APPEND，按 type 分发。
     *  - p == response/fragments/-1/content -> 分片内容增量（新协议主力路径）。
     *  - 旧格式：response/content、response/thinking_content。
     *  - 无 p 的续行：沿用最近的分片类型。
     *  - 非 SSE 行：官方风控错误体 {"code":40001,"msg":...}（code >= 40000）与
     *    HTML 拦截页 -> 标记 rateLimited 并上抛错误事件。
     *  - 结束信号：FINISHED 分片 / [DONE] / EOF，watchdog 兜底。
     *
     * @param onContent   收到任何内容增量时回调一次（空回复判定）
     * @param msgIdOut    流中捕获的 response_message_id（诊断保留）
     * @param rateLimited 流内检测到风控信号时置 true
     */
    private fun parseSse(
        reader: BufferedReader,
        thinking: Boolean,
        onEvent: (SseEvent) -> Unit,
        onContent: () -> Unit,
        msgIdOut: AtomicReference<Long>,
        rateLimited: java.util.concurrent.atomic.AtomicBoolean
    ) {
        var currentPath = if (thinking) "thinking" else "text"
        var contentSeen = false
        val unexplained = StringBuilder()
        fun sample(kind: String, text: String) {
            if (unexplained.length >= 1400) return
            if (unexplained.isNotEmpty()) unexplained.append('\n')
            unexplained.append(kind).append(": ").append(text.take(220))
        }

        fun emit(t: String, text: String) {
            contentSeen = true
            onEvent(if (t == "thinking") SseEvent.Thinking(text) else SseEvent.Text(text))
            onContent()
        }

        stream@ while (true) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // 官方风控/错误 JSON 体（非 SSE 封装）：{"code":40001,"msg":"..."}
            if (trimmed.startsWith("{")) {
                try {
                    val errObj = JSONObject(trimmed)
                    val code = errObj.optLong("code", -1L)
                    if (code >= 40000) {
                        val msg = errObj.optString("msg", "DeepSeek 风控拦截 (code=$code)")
                        EventLog.log("SSE", "官方风控错误体：code=$code msg=$msg")
                        rateLimited.set(true)
                        onEvent(SseEvent.Error(msg))
                        break@stream
                    }
                } catch (_: Exception) {
                }
                sample("非SSE行", trimmed)
                continue
            }
            // HTML 拦截页（WAF）
            if (trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<html", ignoreCase = true)) {
                EventLog.log("SSE", "收到 HTML 拦截页（WAF）")
                rateLimited.set(true)
                onEvent(SseEvent.Error("DeepSeek 返回 HTML 拦截页（疑似 WAF/风控）"))
                break@stream
            }
            if (!trimmed.startsWith("data:")) {
                // event:/注释等其他行：忽略
                continue
            }
            val data = trimmed.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isEmpty() || data == ":") continue

            val chunk = try {
                JSONObject(data)
            } catch (_: Exception) {
                sample("非JSON", data)
                continue
            }

            if (chunk.has("error")) {
                val msg = chunk.optString("error")
                EventLog.log("SSE", "流内错误：${msg.take(200)}")
                onEvent(SseEvent.Error(msg))
                continue
            }

            val msgId = chunk.optLong("response_message_id", -1L)
            if (msgId >= 0) msgIdOut.set(msgId)

            val path = chunk.optString("p")
            val v: Any = if (chunk.has("v")) chunk.get("v") else JSONObject.NULL

            when {
                // 完整 response 快照
                v is JSONObject && v.has("response") -> {
                    val resp = v.optJSONObject("response")!!
                    if (resp.has("thinking_enabled") && !resp.isNull("thinking_enabled")) {
                        currentPath = if (resp.optBoolean("thinking_enabled")) "thinking" else "text"
                    }
                    val fragments = resp.optJSONArray("fragments")
                    if (fragments != null) {
                        val (newPath, parts, finished) = parseFragments(fragments)
                        if (newPath.isNotEmpty()) currentPath = newPath
                        parts.forEach { (t, text) -> emit(t, text) }
                        if (finished) break@stream
                    }
                }

                // 分片 APPEND（新协议主力路径）
                path == "response/fragments" && v is JSONArray -> {
                    val (newPath, parts, finished) = parseFragments(v)
                    if (newPath.isNotEmpty()) currentPath = newPath
                    parts.forEach { (t, text) -> emit(t, text) }
                    if (finished) break@stream
                }

                // 分片内容增量：{"p":"response/fragments/-1/content","v":"..."}
                path == "response/fragments/-1/content" -> {
                    if (v is String && v.isNotEmpty()) {
                        emit(currentPath, v)
                    }
                }

                // 旧格式：正文/思考增量
                path == "response/content" -> {
                    currentPath = "text"
                    if (v is String && v.isNotEmpty()) emit("text", v)
                }
                path == "response/thinking_content" -> {
                    currentPath = "thinking"
                    if (v is String && v.isNotEmpty()) emit("thinking", v)
                }

                // 搜索结果 / 用量统计：忽略
                path == "response/search_results" -> {}
                path == "response" && v is JSONArray -> {}

                else -> {
                    // 无 p 的续行：字符串即内容增量，数组拼接各项 content
                    val content: String = when (v) {
                        is String -> v
                        is JSONArray -> {
                            val sb = StringBuilder()
                            for (i in 0 until v.length()) {
                                val item = v.optJSONObject(i) ?: continue
                                sb.append(item.optString("content"))
                            }
                            sb.toString()
                        }
                        else -> ""
                    }
                    val cleaned = cleanContent(content)
                    if (cleaned.isNotEmpty()) {
                        emit(if (currentPath == "thinking") "thinking" else "text", cleaned)
                    } else if (content.isEmpty() && v != JSONObject.NULL) {
                        sample("未识别v(p=$path)", data)
                    }
                }
            }
        }

        if (!contentSeen && !rateLimited.get()) {
            EventLog.log(
                "SSE",
                "流结束但未解析出任何内容（协议可能已变化）。诊断采样：\n" +
                        (if (unexplained.isEmpty()) "（无未识别行——流可能为空或直接被关闭）" else unexplained.toString())
            )
        }

        onEvent(SseEvent.Done)
    }

    /**
     * 解析 fragments 数组，返回（新的 currentPath, 内容分片列表, 是否结束）。
     * 未知分片类型只要有 content 就按正文放行（宽容策略）。
     */
    private fun parseFragments(fragments: JSONArray): Triple<String, List<Pair<String, String>>, Boolean> {
        var newPath = ""
        var finished = false
        val parts = mutableListOf<Pair<String, String>>()
        for (i in 0 until fragments.length()) {
            val frag = fragments.optJSONObject(i) ?: continue
            val typeName = frag.optString("type").uppercase()
            val content = cleanContent(frag.optString("content"))
            when (typeName) {
                "THINK", "THINKING" -> {
                    newPath = "thinking"
                    if (content.isNotEmpty()) parts.add("thinking" to content)
                }
                "ANSWER", "RESPONSE" -> {
                    newPath = "text"
                    if (content.isNotEmpty()) parts.add("text" to content)
                }
                "FINISHED", "DONE" -> {
                    finished = true
                }
                else -> {
                    if (content.isNotEmpty()) {
                        if (seenFragmentTypes.add(typeName)) {
                            EventLog.log("SSE", "未知分片类型 \"$typeName\"，已按正文放行（宽容策略）")
                        }
                        newPath = "text"
                        parts.add("text" to content)
                    }
                }
            }
        }
        return Triple(newPath, parts, finished)
    }

    /** 已记录过的未知分片类型（避免日志刷屏）。 */
    private val seenFragmentTypes = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * 清理增量文本：
     *  - 整段即为 FINISHED 标记时清空（结束信号混入内容流的情况）；
     *  - 前缀的搜索状态词（SEARCH/WEB_SEARCH/SEARCHING）剥掉。
     */
    private fun cleanContent(content: String): String {
        if (content.isEmpty()) return content
        var c = content
        c = c.replace(Regex("(?m)^\\s*FINISHED\\s*$"), "")
        c = c.replace(Regex("^(SEARCH|WEB_SEARCH|SEARCHING)\\s*", RegexOption.IGNORE_CASE), "")
        return c
    }
}

/** DeepSeek 上游 HTTP 错误（带状态码，便于按 403/429/404 分流处理）。 */
class DsHttpException(val code: Int, message: String) : Exception(message)

class DeepSeekException(message: String) : Exception(message)
