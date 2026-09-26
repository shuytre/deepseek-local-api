package com.ds.localapi.store

import com.ds.localapi.core.EventLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多账号池（v1.8.0）：最多 2 个 DeepSeek 账号轮换。
 *
 * 工作方式：
 *  - 账号 0 与单账号模式的 settings.userToken 自动同步（登录/手动编辑都落到槽 0）；
 *  - 通过首页三角形菜单「组成多账号池」登录第 2 个账号，进入双账号轮换；
 *  - 某账号触发风控（限流码 / 连续空回复）→ 该账号进入冷却并被摘下，
 *    自动切到另一个账号继续服务；另一个也触发时，先冷却结束的那个优先恢复使用；
 *  - 每个账号独立维护：冷却截止、冷却次数（指数递增）、风控连击计数；
 *    请求频率预算（小时/日限额）仍为全局共享，总体节奏不变。
 *
 * 状态持久化在 SharedPreferences（JSON），重启后冷却状态继续生效。
 */
class AccountPool(private val settings: Settings) {

    data class Account(
        var token: String,
        var label: String,
        val addedAt: Long,
        var cooldownUntil: Long,
        var cooldownCount: Int,
        var riskStreak: Int,
        /** 账号绑定的长效 DeepSeek 会话 id（空 = 尚未创建）。会话与账号强绑定，不能跨账号复用。 */
        var sessionId: String = "",
        /** 当前会话累计 prompt token 估算值，超过阈值自动续期新会话。 */
        var sessionTokens: Long = 0L
    )

    /** 当前使用的账号下标（内存态，写穿到持久化）。 */
    var active: Int = 0
        private set

    private val accounts = mutableListOf<Account>()

    val size: Int get() = accounts.size

    /** 双账号轮换模式（≥2 个账号）。 */
    val isPoolMode: Boolean get() = accounts.size >= 2

    init {
        load()
    }

    // ---------------- 查询 ----------------

    fun tokenAt(i: Int): String =
        if (i in accounts.indices) accounts[i].token else ""

    /** 当前活跃账号 token；池为空返回空串。 */
    fun activeToken(): String = tokenAt(active)

    fun cooldownRemainingSec(i: Int): Long {
        if (i !in accounts.indices) return 0
        val left = accounts[i].cooldownUntil - System.currentTimeMillis()
        return if (left > 0) left / 1000 else 0
    }

    fun cooldownCount(i: Int): Int =
        if (i in accounts.indices) accounts[i].cooldownCount else 0

    fun riskStreak(i: Int): Int =
        if (i in accounts.indices) accounts[i].riskStreak else 0

    /**
     * 挑一个可用账号：优先当前活跃的；其在冷却则换另一个；
     * 都在冷却则返回冷却先结束的那个（由 RateGovernor 决定排队或拒绝）。
     */
    fun pickAvailable(): Int {
        if (accounts.isEmpty()) return 0
        if (accounts.size == 1) return 0
        val aCd = cooldownRemainingSec(active)
        if (aCd == 0L) return active
        val other = otherSlot(active)
        val bCd = cooldownRemainingSec(other)
        if (bCd == 0L) return other
        // 都在冷却：选先结束的
        return if (aCd <= bCd) active else other
    }

    /** 另一个槽位下标（仅双账号时有意义）。 */
    fun otherSlot(i: Int): Int = if (i == 0) 1 else 0

    // ---------------- 会话管理（DeepSeekClient 调用） ----------------

    companion object {
        /** 会话累计 token 超过该值即续期新会话（V4 1M 上下文留 10% 余量，参考 session_store.py）。 */
        const val SESSION_TOKEN_THRESHOLD = 900_000L
    }

    fun sessionIdAt(i: Int): String =
        if (i in accounts.indices) accounts[i].sessionId else ""

    fun sessionTokensAt(i: Int): Long =
        if (i in accounts.indices) accounts[i].sessionTokens else 0L

    @Synchronized
    fun setSession(i: Int, sessionId: String) {
        if (i !in accounts.indices) return
        accounts[i].sessionId = sessionId
        accounts[i].sessionTokens = 0L
        save()
    }

    @Synchronized
    fun clearSession(i: Int) {
        if (i !in accounts.indices) return
        accounts[i].sessionId = ""
        accounts[i].sessionTokens = 0L
        save()
    }

    /** 累加当前会话的 prompt token 估算。超过阈值返回 true（调用方应续期新会话）。 */
    @Synchronized
    fun addSessionTokens(i: Int, tokens: Long): Boolean {
        if (i !in accounts.indices || tokens <= 0) return false
        accounts[i].sessionTokens += tokens
        save()
        return accounts[i].sessionTokens > SESSION_TOKEN_THRESHOLD
    }

    // ---------------- 风控状态写操作（RateGovernor 调用） ----------------

    /** 风控连击 +1，返回新的连击数。 */
    fun bumpRisk(i: Int): Int {
        if (i !in accounts.indices) return 0
        accounts[i].riskStreak++
        save()
        return accounts[i].riskStreak
    }

    /** 进入冷却：按该账号的冷却次数指数递增时长。返回冷却毫秒数。 */
    fun enterCooldown(i: Int, baseMs: Long): Long {
        if (i !in accounts.indices) return 0
        val acc = accounts[i]
        acc.cooldownCount++
        val mult = when {
            acc.cooldownCount >= 3 -> 4L
            acc.cooldownCount == 2 -> 2L
            else -> 1L
        }
        val duration = baseMs * mult
        acc.cooldownUntil = System.currentTimeMillis() + duration
        acc.riskStreak = 0
        save()
        return duration
    }

    /** 该账号调用成功：连击清零；冷却已自然结束则冷却次数也清零。 */
    fun markSuccess(i: Int) {
        if (i !in accounts.indices) return
        val acc = accounts[i]
        acc.riskStreak = 0
        if (acc.cooldownCount > 0 && acc.cooldownUntil <= System.currentTimeMillis()) {
            EventLog.log("POOL", "${acc.label} 冷却已自然结束，恢复正常")
            acc.cooldownCount = 0
            save()
        }
    }

    // ---------------- 账号管理（UI 调用） ----------------

    /**
     * 同步主账号（槽 0）：登录 / 手动编辑 token 都走这里。
     * token 为空 = 清除主账号（若池里只剩一个也一并清空）。
     */
    fun syncPrimary(token: String) {
        val t = token.trim()
        if (t.isEmpty()) {
            if (accounts.isNotEmpty()) {
                accounts.removeAt(0)
                if (active >= accounts.size) active = 0
                if (accounts.isNotEmpty()) settings.userToken = accounts[0].token
                save()
            }
            return
        }
        if (accounts.isEmpty()) {
            accounts.add(Account(t, "账号1", System.currentTimeMillis(), 0L, 0, 0))
            active = 0
        } else {
            val acc = accounts[0]
            if (acc.token != t) {
                // 主账号换人 = 旧会话/冷却全部作废
                acc.sessionId = ""
                acc.sessionTokens = 0L
            }
            acc.token = t
            acc.cooldownUntil = 0L
            acc.cooldownCount = 0
            acc.riskStreak = 0
        }
        save()
    }

    /**
     * 添加第 2 个账号组成池。成功返回 true；池已满或 token 重复返回 false。
     */
    fun addSecond(token: String): Boolean {
        val t = token.trim()
        if (t.isEmpty() || accounts.size >= 2) return false
        if (accounts.any { it.token == t }) return false
        if (accounts.isEmpty()) syncPrimary(settings.userToken)
        if (accounts.isEmpty()) return false
        accounts.add(Account(t, "账号2", System.currentTimeMillis(), 0L, 0, 0))
        EventLog.log("POOL", "账号2 已加入，双账号轮换模式启用")
        save()
        return true
    }

    /** 解散账号池：移除账号2，只保留主账号。 */
    fun dissolve() {
        while (accounts.size > 1) accounts.removeAt(1)
        if (accounts.isNotEmpty()) accounts[0].label = "账号1"
        active = 0
        EventLog.log("POOL", "账号池已解散，恢复单账号模式")
        save()
    }

    /** 手动切换当前使用账号（UI 管理对话框用）。 */
    fun switchTo(i: Int) {
        if (i !in accounts.indices || i == active) return
        active = i
        EventLog.log("POOL", "手动切换到${accounts[i].label}")
        save()
    }

    // ---------------- 持久化 ----------------

    @Synchronized
    private fun load() {
        accounts.clear()
        active = 0
        try {
            val root = JSONObject(settings.accountPoolJson)
            val arr = root.optJSONArray("accounts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val token = o.optString("token")
                if (token.isEmpty()) continue
                accounts.add(
                    Account(
                        token = token,
                        label = o.optString("label", "账号${i + 1}"),
                        addedAt = o.optLong("addedAt"),
                        cooldownUntil = o.optLong("cooldownUntil"),
                        cooldownCount = o.optInt("cooldownCount"),
                        riskStreak = o.optInt("riskStreak"),
                        sessionId = o.optString("sessionId"),
                        sessionTokens = o.optLong("sessionTokens")
                    )
                )
            }
            active = root.optInt("active", 0).coerceIn(0, (accounts.size - 1).coerceAtLeast(0))
        } catch (_: Exception) {
        }

        // 迁移：单账号模式（池为空但 userToken 有值）→ 自动作为槽 0
        if (accounts.isEmpty() && settings.userToken.isNotBlank()) {
            accounts.add(Account(settings.userToken.trim(), "账号1", System.currentTimeMillis(), 0L, 0, 0))
            save()
        }

        // 反向同步：userToken 与槽 0 不一致时，以池内为准回写（池是唯一事实源）
        if (accounts.isNotEmpty() && settings.userToken.trim() != accounts[0].token) {
            settings.userToken = accounts[0].token
        }
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        accounts.forEach {
            arr.put(JSONObject()
                .put("token", it.token)
                .put("label", it.label)
                .put("addedAt", it.addedAt)
                .put("cooldownUntil", it.cooldownUntil)
                .put("cooldownCount", it.cooldownCount)
                .put("riskStreak", it.riskStreak)
                .put("sessionId", it.sessionId)
                .put("sessionTokens", it.sessionTokens))
        }
        settings.accountPoolJson = JSONObject()
            .put("accounts", arr)
            .put("active", active)
            .toString()
    }
}
