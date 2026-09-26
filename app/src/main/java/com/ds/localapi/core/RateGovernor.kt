package com.ds.localapi.core

import com.ds.localapi.store.AccountPool
import com.ds.localapi.store.Settings
import org.json.JSONArray
import java.util.concurrent.Semaphore

/** 频率预算用尽（滚动窗口限额已满）。 */
class RateBudgetExhausted(message: String, val retryAfterSec: Long) : Exception(message)

/** 风控冷却进行中。 */
class CooldownActive(message: String, val retryAfterSec: Long) : Exception(message)

/**
 * 全局频率治理器：串行队列 + 相邻间隔 + 滚动窗口限额 + 按账号指数冷却。
 *
 * 目标：把「agent 高并发、连发请求」约束成「真人重度使用」的形态，最大限度降低
 * DeepSeek 风控触发概率。所有对 DeepSeek 的网络调用（建会话 / PoW / completion）
 * 都必须在本地治理器的信号量内执行。
 *
 * v1.8.0：冷却状态从全局改为按账号池槽位维护（[AccountPool]），配合账号轮换——
 * 账号 A 冷却时自动切 B，B 也冷却时选先恢复的账号排队。频率预算（小时/日限额）
 * 仍为全局共享：两个账号合计仍按真人节奏使用，不放大总量。
 *
 * 可重入：同一线程嵌套调用 [withSlot] 不会死锁（chatOnce 内部还会调 createSession / PoW）。
 */
class RateGovernor(
    private val settings: Settings,
    private val pool: AccountPool? = null
) {

    /** 全局串行信号量（公平模式，先到先得）。 */
    private val semaphore = Semaphore(1, true)

    /** 同线程重入标记。 */
    private val held = ThreadLocal.withInitial { false }

    /** 上次真正发出网络调用的时刻（信号量保护内读写）。 */
    private var lastCallAt = 0L

    /** 兼容单账号模式（无池）的风控连击与冷却计数（内存态）。 */
    private var soloRiskStreak = 0
    private var soloCooldownCount = 0

    /** 冷却中剩余等待 < 该值时排队等待，否则直接拒绝（避免 HTTP 长挂）。 */
    private val maxQueueWaitMs = 120_000L

    // ---------------- 主入口 ----------------

    /**
     * 在串行 + 限额 + 冷却约束内执行 [block]。
     * 可能阻塞（排队/间隔/短冷却等待），可能抛 [RateBudgetExhausted] / [CooldownActive]。
     */
    fun <T> withSlot(block: () -> T): T {
        if (held.get()) return block() // 已持有，重入直接执行
        semaphore.acquire()
        held.set(true)
        try {
            awaitCooldownIfNeeded()
            enforceInterval()
            consumeBudget()
            lastCallAt = System.currentTimeMillis()
            return block()
        } finally {
            held.set(false)
            semaphore.release()
        }
    }

    /** 调用成功：清空该账号的风控连击；冷却自然结束后恢复冷却次数。 */
    fun onSuccess(slot: Int = -1) {
        if (pool != null && slot >= 0) {
            pool.markSuccess(slot)
        } else {
            soloRiskStreak = 0
            if (soloCooldownCount > 0 && System.currentTimeMillis() > settings.cooldownUntil) {
                EventLog.log("RATE", "冷却已自然结束，恢复正常调用")
                soloCooldownCount = 0
            }
        }
    }

    /**
     * 风控信号（空回复 / 429 / 403）。连续 ≥2 次空回复或收到限流码即让该账号进入冷却。
     * 返回 true 表示本次信号触发了冷却（调用方据此切换账号）。
     */
    fun onRiskSignal(reason: String, slot: Int = -1): Boolean {
        if (pool != null && slot >= 0) {
            val streak = pool.bumpRisk(slot)
            return if (reason == "rate_limit" || streak >= 2) {
                enterCooldown(reason, slot)
                true
            } else {
                EventLog.log("RATE", "账号${slot + 1} 疑似风控信号（$reason，第 $streak 次），再触发一次将冷却摘下")
                false
            }
        }
        // 单账号兼容路径
        soloRiskStreak++
        return if (reason == "rate_limit" || soloRiskStreak >= 2) {
            enterCooldown(reason, -1)
            true
        } else {
            EventLog.log("RATE", "疑似风控信号（$reason，第 $soloRiskStreak 次），再触发一次将进入冷却")
            false
        }
    }

    /** 查询最近 24h 用量（UI 显示用）。 */
    fun usageLast24h(): Int = loadStamps().count { System.currentTimeMillis() - it < DAY_MS }

    fun usageLastHour(): Int = loadStamps().count { System.currentTimeMillis() - it < HOUR_MS }

    /** 当前活跃账号（或单账号）的冷却剩余秒数。 */
    fun cooldownRemainingSec(): Long {
        if (pool != null) return pool.cooldownRemainingSec(pool.pickAvailable())
        val left = settings.cooldownUntil - System.currentTimeMillis()
        return if (left > 0) left / 1000 else 0
    }

    // ---------------- 内部 ----------------

    private fun enforceInterval() {
        // 相邻两次调用至少间隔 1500ms + 0..800ms 抖动，模拟真人节奏。
        // 现在有信号量保护，并发请求会真正被串行延迟。
        val gap = 1500L + (Math.random() * 800).toLong()
        val since = System.currentTimeMillis() - lastCallAt
        if (lastCallAt > 0 && since < gap) {
            try { Thread.sleep(gap - since) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    private fun awaitCooldownIfNeeded() {
        val leftMs: Long
        val label: String
        if (pool != null) {
            val slot = pool.pickAvailable()
            leftMs = pool.cooldownRemainingSec(slot) * 1000
            label = if (pool.size > 1) "账号${slot + 1}" else ""
        } else {
            leftMs = settings.cooldownUntil - System.currentTimeMillis()
            label = ""
        }
        if (leftMs <= 0) return
        if (leftMs <= maxQueueWaitMs) {
            EventLog.log("RATE", "${label}冷却中，剩余 ${leftMs / 1000}s，本请求排队等待")
            try { Thread.sleep(leftMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        } else {
            throw CooldownActive(
                "${label}风控冷却中，剩余 ${leftMs / 1000 / 60} 分钟。为避免账号被进一步限制，请稍后再试。",
                leftMs / 1000
            )
        }
    }

    private fun enterCooldown(reason: String, slot: Int) {
        if (pool != null && slot >= 0) {
            val duration = pool.enterCooldown(slot, settings.rateCooldownBaseMs)
            EventLog.log(
                "RATE",
                "账号${slot + 1} 进入冷却 ${duration / 60000} 分钟（原因：$reason）。" +
                        (if (pool.size > 1) "将自动切换到另一账号继续服务。" else "冷却期间请求会被拒绝或排队，这是对账号的保护。")
            )
            return
        }
        // 单账号兼容路径
        soloCooldownCount++
        val mult = when {
            soloCooldownCount >= 3 -> 4L
            soloCooldownCount == 2 -> 2L
            else -> 1L
        }
        val duration = settings.rateCooldownBaseMs * mult
        settings.cooldownUntil = System.currentTimeMillis() + duration
        soloRiskStreak = 0
        EventLog.log(
            "RATE",
            "进入冷却 ${duration / 60000} 分钟（原因：$reason，第 $soloCooldownCount 次冷却）。" +
                    "冷却期间请求将被拒绝或排队，这是对账号的保护，不是故障。"
        )
    }

    private fun consumeBudget() {
        val now = System.currentTimeMillis()
        val stamps = loadStamps().filter { now - it < DAY_MS }.toMutableList()
        val hourly = stamps.count { now - it < HOUR_MS }
        if (hourly >= settings.rateHourlyLimit) {
            val oldest = stamps.filter { now - it < HOUR_MS }.min()
            val retryAfter = (HOUR_MS - (now - oldest)) / 1000
            throw RateBudgetExhausted(
                "已达到每小时上限（${settings.rateHourlyLimit} 次/60 分钟，当前窗口 $hourly 次）。" +
                        "这是为降低风控风险主动设置的保护限额。约 $retryAfter 秒后窗口释放。",
                retryAfter
            )
        }
        if (stamps.size >= settings.rateDailyLimit) {
            val oldest = stamps.min()
            val retryAfter = (DAY_MS - (now - oldest)) / 1000
            throw RateBudgetExhausted(
                "已达到每日上限（${settings.rateDailyLimit} 次/24 小时）。" +
                        "这是为降低风控风险主动设置的保护限额。约 ${retryAfter / 3600} 小时后窗口释放。",
                retryAfter
            )
        }
        stamps.add(now)
        saveStamps(stamps)
    }

    private fun loadStamps(): List<Long> {
        val out = mutableListOf<Long>()
        try {
            val arr = JSONArray(settings.rateWindowStamps)
            for (i in 0 until arr.length()) out.add(arr.optLong(i))
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveStamps(stamps: List<Long>) {
        // 只保留最近 24h 内的，防止无限增长
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        stamps.filter { now - it < DAY_MS }.forEach { arr.put(it) }
        settings.rateWindowStamps = arr.toString()
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
    }
}
