package com.ds.localapi.server

import com.ds.localapi.core.AgentToolBridge
import com.ds.localapi.core.ChatResult
import com.ds.localapi.core.CooldownActive
import com.ds.localapi.core.DeepSeekClient
import com.ds.localapi.core.DeepSeekException
import com.ds.localapi.core.EventLog
import com.ds.localapi.core.ModelCatalog
import com.ds.localapi.core.RateBudgetExhausted
import com.ds.localapi.core.SseEvent
import com.ds.localapi.store.ApiKey
import com.ds.localapi.store.Settings
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * 本地 OpenAI 兼容 HTTP 服务。
 *
 * 暴露：
 *  - GET  /health
 *  - GET  /v1/models
 *  - POST /v1/chat/completions  （支持流式与非流式，带 Bearer 鉴权）
 *
 * v2.0.0：
 *  - 解析 tools / tool_choice / role:"tool" 消息，经 AgentToolBridge 仿射 tool_calls；
 *  - 上下文每次全量注入 prompt（对齐参考实现），不依赖 DeepSeek 服务端会话记忆，
 *    配合 DeepSeekClient 的「每账号长效会话 + parent_message_id=null」策略，
 *    从根上消除「频繁建会话」这一风控信号；
 *  - 频率预算 / 冷却错误如实映射为 429 + Retry-After，绝不造假兜底。
 */
class LocalServer(
    host: String,
    port: Int,
    private val settings: Settings,
) : NanoHTTPD(host, port) {

    private val ds = DeepSeekClient(settings)

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        val method = session.method

        // CORS 预检
        if (method == Method.OPTIONS) {
            return cors(newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", ""))
        }

        return try {
            when {
                path == "/health" && method == Method.GET ->
                    cors(json(Response.Status.OK, JSONObject().put("status", "ok")))
                path == "/v1/models" && method == Method.GET -> handleModels(session)
                path == "/v1/chat/completions" && method == Method.POST -> handleChat(session)
                else -> cors(error(404, "Not Found", "not_found_error", "not_found"))
            }
        } catch (e: Exception) {
            EventLog.log("HTTP", "请求处理异常: ${e.message}")
            cors(error(500, "内部错误: ${e.message}", "server_error", "internal"))
        }
    }

    private fun handleModels(session: IHTTPSession): Response {
        if (!authenticated(session)) return unauth()
        val data = JSONArray()
        ModelCatalog.list().forEach { data.put(JSONObject(it)) }
        val root = JSONObject().put("object", "list").put("data", data)
        return cors(json(Response.Status.OK, root))
    }

    private fun handleChat(session: IHTTPSession): Response {
        if (!authenticated(session)) return unauth()

        val token = ds.accountPool.activeToken()
        if (token.isBlank()) {
            return cors(error(400, "尚未登录 DeepSeek，请先在应用内登录", "invalid_request_error", "no_token"))
        }

        val bodyText = readBody(session)
        val request = try {
            JSONObject(bodyText)
        } catch (e: Exception) {
            return cors(error(400, "请求体不是合法 JSON: ${e.message}", "invalid_request_error", "invalid_json"))
        }

        val messages = request.optJSONArray("messages")
        if (messages == null || messages.length() == 0) {
            return cors(error(400, "messages 数组为空", "invalid_request_error", "empty_messages"))
        }

        val model = request.optString("model", ModelCatalog.DEFAULT)
        val stream = request.optBoolean("stream", false)
        // 深度思考开关：请求体可显式传 thinking，覆盖按模型名推断的逻辑
        val forcedThinking = if (request.has("thinking")) request.optBoolean("thinking") else null
        // agent 工具定义（OpenAI tools 参数）：一次解析出 ToolSpec 列表全链路复用
        val tools: JSONArray? = if (settings.toolBridgeEnabled) request.optJSONArray("tools") else null
        val toolSpecs = AgentToolBridge.parseTools(tools)
        val toolNames = toolSpecs.map { it.name }
        val toolChoice: Any? = if (request.has("tool_choice")) request.opt("tool_choice") else null

        val (effectiveModel, thinking, search) = resolveModel(model, forcedThinking)

        // 识图：消息里的图片先上传到网页端，拿到 file_id 供 completion 引用。
        // 上传失败不阻断对话（退化为纯文本请求），只在事件日志里留痕。
        val images = if (settings.visionEnabled) collectImages(messages) else emptyList()
        val refFileIds = if (images.isEmpty()) emptyList() else uploadImages(images)
        if (images.isNotEmpty()) {
            EventLog.log("HTTP", "识图：共 ${images.size} 张图片，成功上传 ${refFileIds.size} 张")
        }

        // 上下文每次全量注入（对齐参考实现）：DeepSeekClient 复用每账号长效会话，
        // 不依赖服务端会话记忆，多 agent 任务也不会互相污染。
        val prompt = buildPrompt(messages, toolSpecs, toolChoice)
        val promptLen = prompt.length

        val chatId = "chatcmpl-" + randomHex(24)
        val created = System.currentTimeMillis() / 1000

        if (stream) {
            return streamResponse(token, prompt, effectiveModel, thinking, search, chatId, created, toolSpecs, toolNames, promptLen, refFileIds)
        }

        return nonStreamResponse(token, prompt, effectiveModel, thinking, search, chatId, created, toolNames, promptLen, refFileIds)
    }

    /**
     * 模型已整合（v2.1.0）：只有 V4.1 Flash 一个模型，不再区分快速 / 专家。
     * - 深度思考：默认开启（应用内开关 [Settings.thinkingEnabled]），请求体传 thinking 可覆盖。
     * - 识图：由 ref_file_ids 承载（见 [collectImages]），与模型选择无关。
     */
    private fun resolveModel(model: String, forcedThinking: Boolean?): Triple<String, Boolean, Boolean> {
        val m = model.lowercase()
        val thinking = forcedThinking ?: settings.thinkingEnabled
        val search = m.contains("search")
        return Triple(ModelCatalog.UNIFIED, thinking, search)
    }

    // ---------------- 识图：图片解析 + 上传 ----------------

    private data class PendingImage(val bytes: ByteArray, val filename: String, val mime: String)

    /** 单张图片上限（网页端过大文件会被拒，超限直接跳过并记日志）。 */
    private val maxImageBytes = 8 * 1024 * 1024

    /**
     * 从 OpenAI 格式消息里抽取图片，支持三种输入形态：
     *  - {"type":"image_url","image_url":{"url":"data:image/png;base64,…"}}
     *  - {"type":"image_url","image_url":{"url":"https://…"}}（先下载再上传）
     *  - {"type":"file","file":{"file_data":"base64","filename":"a.png"}}
     */
    private fun collectImages(messages: JSONArray): List<PendingImage> {
        val out = mutableListOf<PendingImage>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val raw = m.opt("content") ?: continue
            if (raw !is JSONArray) continue
            for (j in 0 until raw.length()) {
                val part = raw.optJSONObject(j) ?: continue
                when (part.optString("type").lowercase()) {
                    "image_url" -> {
                        val url = part.optJSONObject("image_url")?.optString("url").orEmpty()
                        decodeImageSource(url)?.let { out.add(it) }
                    }
                    "file" -> {
                        val f = part.optJSONObject("file") ?: continue
                        val b64 = f.optString("file_data").ifEmpty { f.optString("data") }
                        if (b64.isEmpty()) continue
                        val name = f.optString("filename").ifEmpty { "file.png" }
                        decodeBase64(b64)?.let {
                            out.add(PendingImage(it, name, guessMime(name, it)))
                        }
                    }
                }
            }
        }
        return out.filter { it.bytes.size <= maxImageBytes }
    }

    /** data URI 直接解码；http(s) 链接先下载。 */
    private fun decodeImageSource(url: String): PendingImage? {
        if (url.isEmpty()) return null
        return try {
            when {
                url.startsWith("data:") -> {
                    val meta = url.substringAfter("data:").substringBefore(",")
                    val mime = meta.substringBefore(";").ifEmpty { "image/png" }
                    val bytes = decodeBase64(url.substringAfter(","))
                        ?: return null
                    PendingImage(bytes, "image.${mime.substringAfter("/")}", mime)
                }
                url.startsWith("http") -> {
                    val req = okhttp3.Request.Builder().url(url).build()
                    okhttp3.OkHttpClient().newCall(req).execute().use { resp ->
                        if (resp.code != 200) {
                            EventLog.log("HTTP", "图片下载失败 HTTP ${resp.code}：$url")
                            return null
                        }
                        val bytes = resp.body?.bytes() ?: return null
                        val mime = resp.header("Content-Type")
                            ?.substringBefore(";")?.trim()?.ifEmpty { "image/png" } ?: "image/png"
                        PendingImage(bytes, "image.${mime.substringAfter("/")}", mime)
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            EventLog.log("HTTP", "图片解析失败：${e.message}")
            null
        }
    }

    private fun decodeBase64(s: String): ByteArray? = try {
        android.util.Base64.decode(s.trim(), android.util.Base64.DEFAULT)
    } catch (e: Exception) {
        EventLog.log("HTTP", "base64 解码失败：${e.message}")
        null
    }

    /** 按文件名后缀推断 MIME，兜底用 PNG 魔数判断。 */
    private fun guessMime(name: String, bytes: ByteArray): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> if (bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) "image/png" else "image/jpeg"
        }
    }

    /** 上传全部图片，返回可用的 file_id 列表。 */
    private fun uploadImages(images: List<PendingImage>): List<String> {
        val slot = ds.accountPool.pickAvailable()
        return images.mapNotNull { img ->
            ds.uploadFile(slot, img.bytes, img.filename, img.mime)
        }
    }

    // ---------------- prompt 构造（全量上下文 + 工具协议） ----------------

    /**
     * 全量上下文注入（对齐参考实现 convert_messages_for_deepseek）：
     * 每次请求把完整对话历史（含 tool 调用与结果）+ 完整工具协议写进 prompt。
     * DeepSeek 服务端会话只是容器（parent_message_id=null），不依赖其记忆，
     * 因此多 agent / 多线程任务天然隔离，且大幅减少建会话调用（风控信号）。
     */
    private fun buildPrompt(
        messages: JSONArray,
        toolSpecs: List<AgentToolBridge.ToolSpec>,
        toolChoice: Any?
    ): String {
        val toolProtocol = AgentToolBridge.buildToolProtocol(toolSpecs, toolChoice)
        val systemText = collectSystem(messages)
        val head = if (toolProtocol.isEmpty()) systemText
        else if (systemText.isEmpty()) toolProtocol else "$toolProtocol\n\n$systemText"
        return buildFullTranscript(messages, head)
    }

    private fun collectSystem(messages: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") == "system") {
                val c = extractContent(m)
                if (c.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    /** 全量历史转写：user/assistant/tool 全部保留，tool 结果标注工具名。 */
    private fun buildFullTranscript(messages: JSONArray, head: String): String {
        val toolNames = collectToolCallNames(messages)
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            when (m.optString("role")) {
                "system" -> {} // 已并入 head
                "user" -> sb.append("用户: ").append(extractContent(m)).append("\n\n")
                "assistant" -> {
                    val calls = m.optJSONArray("tool_calls")
                    if (calls != null && calls.length() > 0) {
                        sb.append("助手（调用工具）: ")
                        for (j in 0 until calls.length()) {
                            val c = calls.optJSONObject(j) ?: continue
                            val fn = c.optJSONObject("function")
                            if (fn != null) sb.append(fn.optString("name"))
                                .append("(").append(fn.optString("arguments")).append(") ")
                        }
                        sb.append("\n\n")
                    }
                    val text = extractContent(m)
                    if (text.isNotEmpty()) sb.append("助手: ").append(text).append("\n\n")
                }
                "tool" -> sb.append(AgentToolBridge.toolResultToText(m, toolNames)).append("\n\n")
            }
        }
        sb.append("请根据以上对话历史，直接回复最后一条消息。")
        var transcript = sb.toString().trim()
        // 长度守卫：agent 自主开发会长会话 + 大文件内容，网页端 prompt 过长
        // 会被截断或触发限流。超限时掐中间保两头（任务目标 + 最近上下文）。
        if (transcript.length > MAX_TRANSCRIPT_CHARS) {
            val headKeep = MAX_TRANSCRIPT_CHARS / 4
            val tailKeep = MAX_TRANSCRIPT_CHARS * 3 / 4
            transcript = transcript.take(headKeep) +
                    "\n\n[…中间部分历史因长度限制被省略，请基于任务目标与最近的对话继续…]\n\n" +
                    transcript.takeLast(tailKeep)
            EventLog.log("HTTP", "全量历史超长（${sb.length} 字符），已掐中间保两头截断到 $MAX_TRANSCRIPT_CHARS")
        }
        return if (head.isEmpty()) transcript else "$head\n\n=== 对话历史 ===\n$transcript"
    }

    private companion object {
        /** 全量注入的 transcript 字符上限（不含 system/工具协议 head）。 */
        private const val MAX_TRANSCRIPT_CHARS = 24_000

        /** 流式静默期心跳间隔（毫秒）：深度思考长静默时维持连接活性，防 agent 读超时。 */
        private const val KEEPALIVE_MS = 15_000L
    }

    /** 从 assistant 历史 tool_calls 收集 tool_call_id -> 工具名 映射。 */
    private fun collectToolCallNames(messages: JSONArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            if (m.optString("role") != "assistant") continue
            val calls = m.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) {
                val c = calls.optJSONObject(j) ?: continue
                val id = c.optString("id")
                val name = c.optJSONObject("function")?.optString("name") ?: continue
                if (id.isNotEmpty()) map[id] = name
            }
        }
        return map
    }

    // ---------------- 响应 ----------------

    private fun streamResponse(
        token: String,
        prompt: String,
        model: String,
        thinking: Boolean,
        search: Boolean,
        chatId: String,
        created: Long,
        toolSpecs: List<AgentToolBridge.ToolSpec>,
        toolNames: List<String>,
        promptLen: Int,
        refFileIds: List<String> = emptyList()
    ): Response {
        val channel = SseChannel()
        val writer = channel.writer()
        val sniffer = if (toolSpecs.isNotEmpty()) AgentToolBridge.Sniffer() else null
        // usage 统计（流结束时下发 usage chunk，兼容 stream_options.include_usage 客户端）
        val textLen = java.util.concurrent.atomic.AtomicInteger(0)
        val thinkLen = java.util.concurrent.atomic.AtomicInteger(0)

        // 深度思考（V4 Pro）会长时间静默（十余分钟），而 Harness 等 agent 对侧的 HTTP
        // 读超时通常只有几分钟。这里用心跳线程：连接未关闭时每 KEEPALIVE_MS 发一条
        // SSE 注释行（客户端会忽略注释，但能让底层 TCP 有数据流动、重置对方的读超时），
        // 避免「模型还在想、agent 却以为超时」而中断整个自主任务。
        val streamClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        val keepAlive = Thread {
            try {
                while (!streamClosed.get() && !Thread.currentThread().isInterrupted) {
                    Thread.sleep(KEEPALIVE_MS)
                    if (!streamClosed.get()) writer.write(": keepalive")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.apply { isDaemon = true; start() }

        writer.write(sseChunk(chatId, model, created, JSONObject().put("role", "assistant"), null))

        Thread {
            try {
                ds.chat(prompt, thinking, search, model, refFileIds) { event ->
                    when (event) {
                        is SseEvent.Thinking -> {
                            thinkLen.addAndGet(event.delta.length)
                            val delta = JSONObject().put("reasoning_content", event.delta)
                            writer.write(sseChunk(chatId, model, created, delta, null))
                        }
                        is SseEvent.Text -> {
                            textLen.addAndGet(event.delta.length)
                            if (sniffer == null) {
                                val delta = JSONObject().put("content", event.delta)
                                writer.write(sseChunk(chatId, model, created, delta, null))
                            } else {
                                val forward = sniffer.accept(event.delta)
                                if (forward != null && forward.isNotEmpty()) {
                                    val delta = JSONObject().put("content", forward)
                                    writer.write(sseChunk(chatId, model, created, delta, null))
                                }
                            }
                        }
                        SseEvent.Done -> {
                            if (sniffer != null && sniffer.bufferingToolCall) {
                                // 工具调用路径：解析缓冲 JSON，一次性下发 tool_calls
                                val calls = AgentToolBridge.extractToolCalls(sniffer.bufferedText(), toolNames)
                                if (calls != null) {
                                    val arr = JSONArray()
                                    calls.forEachIndexed { idx, c ->
                                        arr.put(JSONObject()
                                            .put("index", idx)
                                            .put("id", c.id)
                                            .put("type", "function")
                                            .put("function", JSONObject()
                                                .put("name", c.name)
                                                .put("arguments", c.argumentsJson)))
                                    }
                                    writer.write(sseChunk(chatId, model, created,
                                        JSONObject().put("tool_calls", arr), "tool_calls"))
                                } else {
                                    // 解析失败：如实按原文下发（不吞内容）
                                    writer.write(sseChunk(chatId, model, created,
                                        JSONObject().put("content", sniffer.bufferedText()), "stop"))
                                }
                            } else {
                                sniffer?.finish()?.takeIf { it.isNotEmpty() }?.let {
                                    writer.write(sseChunk(chatId, model, created,
                                        JSONObject().put("content", it), null))
                                }
                                writer.write(sseChunk(chatId, model, created, JSONObject(), "stop"))
                            }
                            writer.write(sseUsageChunk(chatId, model, created, promptLen,
                                textLen.get() + thinkLen.get()))
                            writer.write("data: [DONE]")
                        }
                        is SseEvent.Error -> {
                            writer.write(sseChunk(chatId, model, created, JSONObject(), "stop"))
                            writer.write("data: [DONE]")
                        }
                    }
                }
            } catch (e: RateBudgetExhausted) {
                // 预算用尽：流已开（200 已发），只能在流内如实告知错误后关闭
                writer.write(sseErrorChunk(chatId, model, created, e.message ?: "频率预算已用尽"))
                writer.write("data: [DONE]")
                EventLog.log("HTTP", "流式请求被预算拦截: ${e.message}")
            } catch (e: CooldownActive) {
                writer.write(sseErrorChunk(chatId, model, created, e.message ?: "风控冷却中"))
                writer.write("data: [DONE]")
            } catch (e: DeepSeekException) {
                writer.write(sseErrorChunk(chatId, model, created, e.message ?: "DeepSeek 请求失败"))
                writer.write("data: [DONE]")
            } catch (e: Exception) {
                writer.write(sseErrorChunk(chatId, model, created, e.message ?: "内部错误"))
                writer.write("data: [DONE]")
            } finally {
                streamClosed.set(true)
                keepAlive.interrupt()
                channel.close()
            }
        }.apply { isDaemon = true; start() }

        val resp = newChunkedResponse(Response.Status.OK, "text/event-stream", channel.inputStream())
        resp.addHeader("Cache-Control", "no-cache")
        resp.addHeader("Connection", "keep-alive")
        resp.addHeader("X-Accel-Buffering", "no")
        return cors(resp)
    }

    private fun nonStreamResponse(
        token: String,
        prompt: String,
        model: String,
        thinking: Boolean,
        search: Boolean,
        chatId: String,
        created: Long,
        toolNames: List<String>,
        promptLen: Int,
        refFileIds: List<String> = emptyList()
    ): Response {
        val result: ChatResult = try {
            var text = StringBuilder()
            var think = StringBuilder()
                ds.chat(prompt, thinking, search, model, refFileIds) { event ->
                when (event) {
                    is SseEvent.Thinking -> think.append(event.delta)
                    is SseEvent.Text -> text.append(event.delta)
                    else -> {}
                }
            }
            ChatResult(text.toString(), think.toString())
        } catch (e: RateBudgetExhausted) {
            return cors(errorWithRetry(429, e.message ?: "频率预算已用尽", "rate_limit_error", "budget_exhausted", e.retryAfterSec))
        } catch (e: CooldownActive) {
            return cors(errorWithRetry(429, e.message ?: "风控冷却中", "rate_limit_error", "cooldown_active", e.retryAfterSec))
        } catch (e: DeepSeekException) {
            return cors(error(502, "DeepSeek 请求失败: ${e.message}", "upstream_error", "deepseek_error"))
        } catch (e: Exception) {
            return cors(error(500, "内部错误: ${e.message}", "server_error", "internal"))
        }

        // 工具调用仿射：优先解析 tool_calls（带白名单校验 + 模糊修复）
        if (toolNames.isNotEmpty()) {
            val calls = AgentToolBridge.extractToolCalls(result.text, toolNames)
            if (calls != null) {
                val arr = JSONArray()
                calls.forEach { c ->
                    arr.put(JSONObject()
                        .put("id", c.id)
                        .put("type", "function")
                        .put("function", JSONObject()
                            .put("name", c.name)
                            .put("arguments", c.argumentsJson)))
                }
                val message = JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                    .put("tool_calls", arr)
                val choice = JSONObject().put("index", 0).put("message", message)
                    .put("finish_reason", "tool_calls")
                val root = JSONObject()
                    .put("id", chatId)
                    .put("object", "chat.completion")
                    .put("created", created)
                    .put("model", model)
                    .put("choices", JSONArray().put(choice))
                    .put("usage", usage(promptLen, result.text.length))
                return cors(json(Response.Status.OK, root))
            }
        }

        val message = JSONObject()
        message.put("role", "assistant")
        if (result.thinking.isNotEmpty()) {
            // 把思考过程以 <think> 标签拼进正文，便于普通客户端看到
            message.put("content", "<think>\n${result.thinking}\n</think>\n\n${result.text}")
        } else {
            message.put("content", result.text)
        }

        val choice = JSONObject()
        choice.put("index", 0)
        choice.put("message", message)
        choice.put("finish_reason", "stop")

        val root = JSONObject()
        root.put("id", chatId)
        root.put("object", "chat.completion")
        root.put("created", created)
        root.put("model", model)
        root.put("choices", JSONArray().put(choice))
        root.put("usage", usage(promptLen, result.text.length + result.thinking.length))

        return cors(json(Response.Status.OK, root))
    }

    /** 粗估 token（约 4 字符 = 1 token），部分客户端无 usage 会异常。 */
    private fun usage(promptChars: Int, completionChars: Int): JSONObject {
        val p = promptChars / 4
        val c = completionChars / 4
        return JSONObject()
            .put("prompt_tokens", p)
            .put("completion_tokens", c)
            .put("total_tokens", p + c)
    }

    private fun sseChunk(
        chatId: String,
        model: String,
        created: Long,
        delta: JSONObject,
        finishReason: String?
    ): String {
        val choice = JSONObject()
        choice.put("index", 0)
        choice.put("delta", delta)
        if (finishReason != null) choice.put("finish_reason", finishReason) else choice.put("finish_reason", JSONObject.NULL)

        val chunk = JSONObject()
        chunk.put("id", chatId)
        chunk.put("object", "chat.completion.chunk")
        chunk.put("created", created)
        chunk.put("model", model)
        chunk.put("choices", JSONArray().put(choice))
        return "data: $chunk"
    }

    /** 流式结束前的 usage chunk（choices 为空数组，OpenAI include_usage 惯例）。 */
    private fun sseUsageChunk(
        chatId: String,
        model: String,
        created: Long,
        promptChars: Int,
        completionChars: Int
    ): String {
        val chunk = JSONObject()
            .put("id", chatId)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray())
            .put("usage", usage(promptChars, completionChars))
        return "data: $chunk"
    }

    /** 流式内错误帧（OpenAI 流式错误惯例：choices 空 + 顶层 error）。 */
    private fun sseErrorChunk(chatId: String, model: String, created: Long, message: String): String {
        val chunk = JSONObject()
        chunk.put("id", chatId)
        chunk.put("object", "chat.completion.chunk")
        chunk.put("created", created)
        chunk.put("model", model)
        chunk.put("choices", JSONArray())
        chunk.put("error", JSONObject().put("message", message).put("type", "upstream_error"))
        return "data: $chunk"
    }

    // ---------- helpers ----------

    private fun authenticated(session: IHTTPSession): Boolean {
        val auth = session.headers["authorization"] ?: session.headers["Authorization"]
        return ApiKey.verify(auth)
    }

    private fun readBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length > 0) {
            val buf = ByteArray(length)
            var read = 0
            try {
                val input = session.inputStream
                while (read < length) {
                    val n = input.read(buf, read, length - read)
                    if (n < 0) break
                    read += n
                }
            } catch (_: Exception) {
            }
            return String(buf, 0, read, Charsets.UTF_8)
        }
        // 兜底：解析 postData
        val raw = session.parms?.get("NanoHTTPD.QUERY_STRING") ?: ""
        return raw
    }

    private fun json(status: Response.Status, obj: JSONObject): Response {
        val resp = newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())
        return resp
    }

    private fun unauth(): Response = cors(error(401, "Unauthorized", "authentication_error", "invalid_api_key"))

    private fun error(statusCode: Int, message: String, type: String, code: String): Response {
        val body = JSONObject()
            .put("error", JSONObject()
                .put("message", message)
                .put("type", type)
                .put("code", code))
        return json(Response.Status.lookup(statusCode) ?: Response.Status.INTERNAL_ERROR, body)
    }

    /** 带 Retry-After 头的错误（429 预算/冷却）。 */
    private fun errorWithRetry(statusCode: Int, message: String, type: String, code: String, retryAfterSec: Long): Response {
        val resp = error(statusCode, message, type, code)
        resp.addHeader("Retry-After", retryAfterSec.coerceAtLeast(1).toString())
        return resp
    }

    private fun cors(resp: Response): Response {
        resp.addHeader("Access-Control-Allow-Origin", "*")
        resp.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        resp.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        return resp
    }

    private fun randomHex(len: Int): String {
        val sb = StringBuilder()
        val chars = "0123456789abcdef"
        while (sb.length < len) {
            val rand = java.util.concurrent.ThreadLocalRandom.current().nextInt(chars.length)
            sb.append(chars[rand])
        }
        return sb.toString()
    }

    /**
     * 兼容多种 OpenAI 客户端传来的 content 形态：
     *  - 字符串："问个问题"
     *  - 数组：[{"type":"text","text":"问个问题"}]（很多编程工具按多模态格式传）
     *  - 缺省 / 其它对象：尽量 toString，取不到就返回空。
     */
    private fun extractContent(msg: JSONObject): String {
        val raw = msg.opt("content") ?: return ""
        return when (raw) {
            is String -> raw
            is JSONArray -> {
                val sb = StringBuilder()
                for (i in 0 until raw.length()) {
                    val part = raw.optJSONObject(i) ?: continue
                    val type = part.optString("type").lowercase()
                    val text = when (type) {
                        "text" -> part.optString("text")
                        "" -> part.optString("content") // 某些客户端只给 content
                        else -> part.optString("text").ifEmpty { part.toString() }
                    }
                    if (text.isNotEmpty()) sb.append(text).append("\n")
                }
                sb.toString().trim()
            }
            else -> raw.toString()
        }
    }
}

/** 供 ChunkedResponse 使用的 SSE 行通道。 */
private class SseChannel {
    private val queue = LinkedBlockingQueue<String>()
    private val eof = "\u0000EOF\u0000"

    fun writer(): SseWriter = SseWriter { line -> queue.put(line) }
    fun close() = queue.put(eof)

    fun inputStream(): InputStream = object : InputStream() {
        private var current = ByteArray(0)
        private var pos = 0
        override fun read(): Int {
            if (pos >= current.size) {
                val line = queue.take()
                if (line == eof) return -1
                current = (line + "\r\n").toByteArray(Charsets.UTF_8)
                pos = 0
            }
            return current[pos++].toInt() and 0xFF
        }
    }
}

private fun interface SseWriter {
    fun write(line: String)
}
