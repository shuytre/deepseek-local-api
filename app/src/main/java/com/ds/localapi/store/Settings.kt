package com.ds.localapi.store

import android.content.Context
import android.content.SharedPreferences
import com.ds.localapi.core.ModelCatalog

/**
 * 应用设置持久化（SharedPreferences）。
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ds_local_api", Context.MODE_PRIVATE)

    var userToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

    /**
     * 当前使用的模型。v2.1.0 起模型已整合：只有 deepseek-v4.1-flash 一个模型
     * （深度思考 + 识图均为能力开关，不再靠换模型实现）。保留字段仅为兼容外部工具。
     */
    var model: String
        get() {
            val m = prefs.getString(KEY_MODEL, ModelCatalog.DEFAULT) ?: ModelCatalog.DEFAULT
            return if (m.isBlank()) ModelCatalog.DEFAULT else m
        }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /** 对话「深度思考」开关（默认开启，与模型无关的独立开关）。 */
    var thinkingEnabled: Boolean
        get() = prefs.getBoolean(KEY_THINKING, true)
        set(value) = prefs.edit().putBoolean(KEY_THINKING, value).apply()

    var lanEnabled: Boolean
        get() = prefs.getBoolean(KEY_LAN, false)
        set(value) = prefs.edit().putBoolean(KEY_LAN, value).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /** 登录页进入时是否自动打开开发者工具（F12）面板。 */
    var loginDebugPanel: Boolean
        get() = prefs.getBoolean(KEY_LOGIN_DEBUG, false)
        set(value) = prefs.edit().putBoolean(KEY_LOGIN_DEBUG, value).apply()

    /**
     * 是否「优化 API 调用」：开启时复用同一 DeepSeek 会话并串联消息链，连续多条消息
     * 在官方 App 中合为同一对话、上下文不丢；关闭时每次调用都新建会话（不保留上下文）。
     */
    var optimizeApiCalls: Boolean
        get() = prefs.getBoolean(KEY_OPTIMIZE_API, true)
        set(value) {
            prefs.edit().putBoolean(KEY_OPTIMIZE_API, value).apply()
            if (!value) clearSession()
        }

    /** 复用的 DeepSeek 会话 ID（持久化，跨进程/重启保持同一对话）。 */
    var lastSessionId: String
        get() = prefs.getString(KEY_SESSION_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()

    /** 最近一条助手回复的 message_id，作为下次请求的 parent_message_id（消息链，u32 整数）。 */
    var lastParentMessageId: Long
        get() = prefs.getLong(KEY_PARENT_MSG_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_PARENT_MSG_ID, value).apply()

    /** 清空已复用的会话状态（强制下一次新建会话）。 */
    fun clearSession() {
        prefs.edit().putString(KEY_SESSION_ID, "").putLong(KEY_PARENT_MSG_ID, -1L).apply()
    }

    // ---------- Agent / 风控（v1.6.0） ----------

    /**
     * Agent 工具调用（tool_calls）仿射开关。
     *
     * v2.1.0 起**默认关闭**：不接入任何 Agent，不在 prompt 里注入工具协议文本，
     * 请求保持「用户说了什么就发什么」。需要让 Operit 等 Agent 走 function calling
     * 时，在设置里手动打开即可。
     */
    var toolBridgeEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOOL_BRIDGE, false)
        set(value) = prefs.edit().putBoolean(KEY_TOOL_BRIDGE, value).apply()

    /** 识图开关：开启后消息里的图片会上传到网页端并以 ref_file_ids 引用（默认开启）。 */
    var visionEnabled: Boolean
        get() = prefs.getBoolean(KEY_VISION, true)
        set(value) = prefs.edit().putBoolean(KEY_VISION, value).apply()

    /** 滚动 60 分钟窗口内的最大请求数。 */
    var rateHourlyLimit: Int
        get() = prefs.getInt(KEY_RATE_HOURLY, 30)
        set(value) = prefs.edit().putInt(KEY_RATE_HOURLY, value.coerceIn(1, 1000)).apply()

    /** 滚动 24 小时窗口内的最大请求数。 */
    var rateDailyLimit: Int
        get() = prefs.getInt(KEY_RATE_DAILY, 300)
        set(value) = prefs.edit().putInt(KEY_RATE_DAILY, value.coerceIn(1, 10_000)).apply()

    /** 冷却基数（毫秒），指数递增 1x/2x/4x 封顶。 */
    var rateCooldownBaseMs: Long
        get() = prefs.getLong(KEY_RATE_COOLDOWN_BASE, 900_000L)
        set(value) = prefs.edit().putLong(KEY_RATE_COOLDOWN_BASE, value.coerceIn(60_000L, 3_600_000L)).apply()

    /** RateGovernor 滚动窗口时间戳（JSON 数组字符串）。 */
    var rateWindowStamps: String
        get() = prefs.getString(KEY_RATE_STAMPS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_RATE_STAMPS, value).apply()

    /** 冷却截止时间戳（毫秒），0 表示不在冷却。 */
        var cooldownUntil: Long
            get() = prefs.getLong(KEY_COOLDOWN_UNTIL, 0L)
            set(value) = prefs.edit().putLong(KEY_COOLDOWN_UNTIL, value).apply()

        /** 多账号池（v1.8.0）：JSON {accounts:[{token,label,addedAt,cooldownUntil,cooldownCount,riskStreak}], active:0} */
        var accountPoolJson: String
            get() = prefs.getString(KEY_ACCOUNT_POOL, "") ?: ""
            set(value) = prefs.edit().putString(KEY_ACCOUNT_POOL, value).apply()

    companion object {
        const val DEFAULT_PORT = 8080
        private const val KEY_TOKEN = "user_token"
        private const val KEY_MODEL = "model"
        private const val KEY_LAN = "lan_enabled"
        private const val KEY_PORT = "port"
        private const val KEY_LOGIN_DEBUG = "login_debug_panel"
        private const val KEY_THINKING = "thinking_enabled"
        private const val KEY_VISION = "vision_enabled"
        private const val KEY_OPTIMIZE_API = "optimize_api_calls"
        private const val KEY_SESSION_ID = "last_session_id"
        private const val KEY_PARENT_MSG_ID = "last_parent_message_id"
        private const val KEY_TOOL_BRIDGE = "tool_bridge_enabled"
        private const val KEY_RATE_HOURLY = "rate_hourly_limit"
        private const val KEY_RATE_DAILY = "rate_daily_limit"
        private const val KEY_RATE_COOLDOWN_BASE = "rate_cooldown_base_ms"
        private const val KEY_RATE_STAMPS = "rate_window_stamps"
            private const val KEY_COOLDOWN_UNTIL = "cooldown_until"
            private const val KEY_ACCOUNT_POOL = "account_pool_json"
    }
}