package com.ds.localapi.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Agent 工具调用（tool_calls）仿射桥 —— 深度适配 Operit 等移动端 Agent。
 *
 * DeepSeek 网页版没有原生 function calling，本桥通过 prompt 工程实现 OpenAI
 * tool_calls 协议。针对 Operit 的实际使用形态做了四项专项优化：
 *
 *  1. 工具集动态压缩：Operit 接入 MCP 后单请求可能携带 20~50 个工具定义，
 *     直接注入会撑爆网页端 prompt。工具数 ≤ TOOL_VERBOSE_MAX 时输出完整
 *     （压缩后）Schema；超出时自动切换为「紧凑签名」模式（名称 + 类型 +
 *     required 标记），几十个 MCP 工具也能控制在数千字符内。
 *  2. 工具名防幻觉：模型对 mcp__server__tool 之类的长名容易写错。解析时
 *     对 name 做白名单校验，未命中时按 前缀 → 包含 → 编辑距离≤2 模糊修复；
 *     修不回来则按普通文本下发（Agent 侧看到模型原话，优于调用不存在的工具）。
 *  3. 每轮格式提醒：网页端会话虽有记忆，但工具协议会随轮次遗忘导致格式崩坏，
 *     增量模式下由 LocalServer 注入 [buildToolReminder] 保持格式稳定。
 *  4. tool_choice 完整支持：auto / none / required / {"type":"function",
 *     "function":{"name":...}} 三种形态，强制调用会写入协议文本。
 */
object AgentToolBridge {

    private val rng = SecureRandom()

    /** 超过该工具数自动切换紧凑签名模式。 */
    const val TOOL_VERBOSE_MAX = 20

    /** 单工具描述保留上限（MCP 工具描述经常超长）。 */
    private const val DESC_CAP = 400

    /** 紧凑模式下描述进一步压到该长度。 */
    private const val DESC_CAP_TERSE = 120

    data class ToolSpec(val name: String, val description: String, val schema: JSONObject?)

    // ---------------- 请求侧 ----------------

    /** 解析 tools 数组（兼容 OpenAI function 包装与裸 function 对象两种格式）。 */
    fun parseTools(tools: JSONArray?): List<ToolSpec> {
        if (tools == null) return emptyList()
        val out = mutableListOf<ToolSpec>()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: t
            val name = fn.optString("name")
            if (name.isEmpty()) continue
            out.add(ToolSpec(name, fn.optString("description"), fn.optJSONObject("parameters")))
        }
        return out
    }

    /** 生成的工具调用协议文本；无 tools 或 tool_choice=none 时返回空。 */
    fun buildToolProtocol(specs: List<ToolSpec>, toolChoice: Any?): String {
        if (specs.isEmpty()) return ""
        if (toolChoice is String && toolChoice == "none") return ""

        val sb = StringBuilder()
        sb.append("# 工具调用协议\n")
        sb.append("你可以调用外部工具来完成任务（如文件读写、命令执行、MCP 工具、联网搜索等）。\n")
        sb.append("当需要调用工具时，完整回复只输出如下格式的 JSON（必须严格可解析，禁止用 ``` 等代码围栏包裹）：\n")
        sb.append("{\"tool_calls\": [{\"name\": \"<工具名>\", \"arguments\": {<参数对象>}}]}\n")
        sb.append("规则：\n")
        sb.append("- name 必须与下列工具名单完全一致，禁止自创、缩写或改写工具名。\n")
        sb.append("- 可一次调用多个工具：在 tool_calls 数组里放多个对象，按执行顺序排列。\n")
        sb.append("- arguments 必须是完全符合参数 Schema 的 JSON 对象，省略可选参数。\n")
        sb.append("- 不调用工具时直接用自然语言回答，此时禁止输出上述 JSON 格式。\n")
        sb.append("- JSON 前后禁止输出任何解释文字、空白或换行。\n\n")

        forcedChoice(toolChoice)?.let { forced ->
            if (forced == REQUIRED) {
                sb.append("【本轮必须调用至少一个工具（只输出上述 JSON）】\n\n")
            } else {
                sb.append("【本轮必须调用名为 \"$forced\" 的工具（只输出上述 JSON，name 填 \"$forced\"）】\n\n")
            }
        }

        val terse = specs.size > TOOL_VERBOSE_MAX
        sb.append("## 可用工具（共 ${specs.size} 个）\n")
        for (s in specs) {
            sb.append("### ").append(s.name).append('\n')
            val cap = if (terse) DESC_CAP_TERSE else DESC_CAP
            if (s.description.isNotEmpty()) {
                sb.append("描述: ").append(s.description.take(cap)).append('\n')
            }
            sb.append("参数: ").append(if (terse) signatureSchema(s.schema) else compactSchema(s.schema)).append('\n')
        }
        return sb.toString()
    }

    /**
     * 增量模式下的每轮紧凑提醒：只带格式要求 + 工具名单（不带 Schema），
     * 防止模型在多轮 tool 往返后遗忘输出格式。名字过长时截断到名单前 24 个。
     */
    fun buildToolReminder(specs: List<ToolSpec>): String {
        if (specs.isEmpty()) return ""
        val names = specs.joinToString(", ") { it.name }
        val list = if (names.length > 900) {
            specs.take(24).joinToString(", ") { it.name } + " 等 ${specs.size} 个"
        } else names
        return "[格式提醒：需要调用工具时，完整回复只输出 " +
                "{\"tool_calls\":[{\"name\":\"…\",\"arguments\":{…}}]} 格式的 JSON（单个 JSON，禁止代码围栏，" +
                "name 必须取自：$list）。不需要工具时直接自然语言回答。]"
    }

    /** 从 tool_choice 提取强制调用目标：REQUIRED 常量或具体函数名。 */
    private fun forcedChoice(toolChoice: Any?): String? {
        return when (toolChoice) {
            is String -> if (toolChoice == "required") REQUIRED else null
            is JSONObject -> {
                val fn = toolChoice.optJSONObject("function")
                val name = toolChoice.optString("name").ifEmpty { fn?.optString("name") ?: "" }
                name.ifEmpty { null }
            }
            else -> null
        }
    }

    /** 压缩 Schema：剔除 $schema/$defs/$ref/additionalProperties 等冗余字段。 */
    private fun compactSchema(schema: JSONObject?): String {
        if (schema == null) return "{}"
        return try { compactValue(schema, 0).toString() } catch (_: Exception) { "{}" }
    }

    private fun compactValue(v: Any, depth: Int): Any {
        if (depth > 6) return "…"
        return when (v) {
            is JSONObject -> {
                val out = JSONObject()
                for (k in v.keys()) {
                    when (k) {
                        "\$schema", "\$defs", "\$ref", "additionalProperties",
                        "exclusiveMinimum", "exclusiveMaximum" -> {}
                        else -> out.put(k, compactValue(v.get(k), depth + 1))
                    }
                }
                out
            }
            is JSONArray -> {
                val out = JSONArray()
                for (i in 0 until v.length()) out.put(compactValue(v.get(i), depth + 1))
                out
            }
            else -> v
        }
    }

    /** 紧凑签名 Schema：{参数名: 类型 (必填) — 简述}，用于工具数很多的场景。 */
    private fun signatureSchema(schema: JSONObject?): String {
        if (schema == null) return "{}"
        val props = schema.optJSONObject("properties") ?: return "{}"
        val required = schema.optJSONArray("required")?.let { r ->
            (0 until r.length()).map { r.optString(it) }.toSet()
        } ?: emptySet()
        val sb = StringBuilder("{")
        var first = true
        for (k in props.keys()) {
            if (!first) sb.append(", ")
            first = false
            val p = props.optJSONObject(k) ?: continue
            val t = p.optString("type").ifEmpty { "any" }
            sb.append(k).append(": ").append(t)
            if (k in required) sb.append(" *")
            val d = p.optString("description")
            if (d.isNotEmpty()) sb.append(" — ").append(d.take(60))
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * 把 role:"tool" 的消息转成可读文本。
     * toolCallNames 提供 tool_call_id -> 工具名 的映射（从历史 assistant.tool_calls 收集）。
     */
    fun toolResultToText(msg: JSONObject, toolCallNames: Map<String, String>): String {
        val callId = msg.optString("tool_call_id")
        val name = toolCallNames[callId] ?: "未知工具"
        val content = msg.optString("content")
        return "[工具 $name 的执行结果]\n$content"
    }

    // ---------------- 响应侧 ----------------

    /** 解析结果：非 null 表示模型请求调用工具。 */
    data class ToolCall(val id: String, val name: String, val argumentsJson: String)

    /**
     * 从模型回复文本中提取工具调用。解析失败 / 无 tool_calls 结构返回 null。
     * 容错顺序：剥代码围栏 → 整体 JSON → 首 { 到末 } 的子串。
     *
     * @param validNames 合法工具名（白名单校验 + 模糊修复）；null 或空则跳过校验
     */
    fun extractToolCalls(text: String, validNames: List<String>? = null): List<ToolCall>? {
        val cleaned = text.trim()
        if (!cleaned.contains("tool_calls")) return null

        val candidates = mutableListOf<String>()
        if (cleaned.contains("```")) {
            Regex("```(?:json)?\\s*([\\s\\S]*?)```").findAll(cleaned).forEach {
                candidates.add(it.groupValues[1].trim())
            }
        }
        candidates.add(cleaned)
        val first = cleaned.indexOf('{')
        val last = cleaned.lastIndexOf('}')
        if (first in 0 until last) candidates.add(cleaned.substring(first, last + 1))

        for (c in candidates) {
            val parsed = tryParse(c, validNames)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun tryParse(s: String, validNames: List<String>?): List<ToolCall>? {
        return try {
            val root = JSONObject(s)
            val calls = root.optJSONArray("tool_calls") ?: return null
            if (calls.length() == 0) return null
            val out = mutableListOf<ToolCall>()
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                var name = c.optString("name")
                if (name.isEmpty()) return null
                if (!validNames.isNullOrEmpty() && name !in validNames) {
                    name = fuzzyMatch(name, validNames) ?: return null
                }
                val args = when (val a = c.opt("arguments")) {
                    is JSONObject -> a.toString()
                    is String -> a.ifEmpty { "{}" }
                    null, JSONObject.NULL -> "{}"
                    else -> a.toString()
                }
                val check = try { JSONObject(args); true } catch (_: Exception) { false }
                if (!check) return null
                out.add(ToolCall(newCallId(), name, args))
            }
            if (out.isEmpty()) null else out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 工具名模糊修复：忽略大小写 → 前缀互含 → 子串互含 → 编辑距离最小且 ≤2。
     * 模型对 mcp__server__tool 之类长名常见错误：丢前缀、漏下划线、错一个字母。
     */
    private fun fuzzyMatch(bad: String, valid: List<String>): String? {
        val b = bad.lowercase()
        valid.firstOrNull { it.equals(bad, true) }?.let { return it }
        valid.firstOrNull { it.lowercase().startsWith(b) || b.startsWith(it.lowercase()) }?.let { return it }
        valid.firstOrNull { it.lowercase().contains(b) || b.contains(it.lowercase()) }?.let { return it }
        return valid.filter { editDistance(b, it.lowercase()) <= 2 }
            .minByOrNull { editDistance(b, it.lowercase()) }
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1)
            )
        }
        return dp[a.length][b.length]
    }

    private fun newCallId(): String {
        val bytes = ByteArray(6)
        rng.nextBytes(bytes)
        return "call_" + bytes.joinToString("") { "%02x".format(it) }
    }

    // ---------------- 流式嗅探 ----------------

    /**
     * 流式模式下的工具调用嗅探器。
     * 策略：缓冲前 [SNIFF] 个字符，若疑似 {"tool_calls" 开头（容忍 ```json 围栏
     * 或 ≤12 字符的杂前缀）则转入全缓冲模式（流结束后一次性下发 tool_calls
     * chunk）；否则把暂扣缓冲一次性放行、后续直发。
     */
    class Sniffer {
        private val buf = StringBuilder()
        private var decided = false
        var bufferingToolCall = false
            private set

        /**
         * 收到一个文本增量。
         * 返回值：需要立即下发给客户端的文本（嗅探期暂扣、判定为普通文本后可能
         * 一次性放行全部缓冲，因此可能长于本增量）；null 表示暂扣不发。
         */
        fun accept(delta: String): String? {
            if (!decided) {
                buf.append(delta)
                if (buf.length >= SNIFF) {
                    decide()
                    if (!bufferingToolCall) return buf.toString()
                }
                return null
            }
            return if (bufferingToolCall) null else delta
        }

        /**
         * 流结束时调用。返回普通文本路径下剩余未放行的缓冲（需补发）；
         * 工具调用路径返回 null（内容在 [bufferedText] 里，供解析）。
         */
        fun finish(): String? {
            if (!decided) {
                decide()
                if (!bufferingToolCall) return buf.toString()
            }
            return if (bufferingToolCall) null else ""
        }

        /** 缓冲的完整文本（bufferingToolCall=true 时用于工具调用解析）。 */
        fun bufferedText(): String = buf.toString()

        private fun decide() {
            decided = true
            val head = buf.toString().trimStart()
            val idx = head.indexOf('{')
            bufferingToolCall = idx in 0..12 && head.substring(idx).startsWith("{\"tool_calls")
        }

        companion object {
            private const val SNIFF = 64
        }
    }

    private const val REQUIRED = "__required__"
}
