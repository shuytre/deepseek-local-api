package com.ds.localapi.core

/**
 * 客户端指纹池：每个对话线程绑定一组 User-Agent / 平台 / 语言 / 浏览器头，
 * 让服务端看到的是「不同真人浏览器/设备在不同时刻访问」，避免单一指纹被风控打标。
 *
 * v1.6.0：扩池到 9 条，全部为 2025–2026 年在役版本（Chrome 130/132/135、Edge、Firefox），
 * 新增 sec-fetch-* 系列头，更贴近真实浏览器指纹。
 */
object BrowserFingerprint {

    data class Profile(
        val userAgent: String,
        val platform: String,          // x-client-platform
        val version: String,           // x-client-version
        val locale: String,            // x-client-locale
        val acceptLanguage: String,
        val accept: String,
        val secChUa: String?,          // Chromium 系才发
        val secChUaMobile: String?,
        val secChUaPlatform: String?,
        val secFetchSite: String? = "same-origin",
        val secFetchMode: String? = "cors",
        val secFetchDest: String? = "empty"
    )

    private val pool: List<Profile> = listOf(
        // 1. Windows Chrome 135
        Profile(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36",
            platform = "web", version = "2.0.2", locale = "zh-CN",
            acceptLanguage = "zh-CN,zh;q=0.9,en;q=0.8",
            accept = "application/json, text/plain, */*",
            secChUa = "\"Chromium\";v=\"135\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"135\"",
            secChUaMobile = "?0", secChUaPlatform = "\"Windows\""
        ),
        // 2. macOS Chrome 132
        Profile(
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36",
            platform = "web", version = "2.0.2", locale = "zh-CN",
            acceptLanguage = "zh-CN,zh;q=0.9,en;q=0.8",
            accept = "application/json, text/plain, */*",
            secChUa = "\"Chromium\";v=\"132\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"132\"",
            secChUaMobile = "?0", secChUaPlatform = "\"macOS\""
        ),
        // 3. Android Chrome 134（三星）
        Profile(
            userAgent = "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36",
            platform = "web", version = "2.0.2", locale = "zh-CN",
            acceptLanguage = "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7",
            accept = "application/json, text/plain, */*",
            secChUa = "\"Chromium\";v=\"134\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"134\"",
            secChUaMobile = "?1", secChUaPlatform = "\"Android\""
        ),
        // 4. Android Chrome 130（小米）
        Profile(
            userAgent = "Mozilla/5.0 (Linux; Android 13; 2210132C) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
            platform = "web", version = "2.0.2", locale = "zh-CN",
            acceptLanguage = "zh-CN,zh;q=0.9",
            accept = "application/json, text/plain, */*",
            secChUa = "\"Chromium\";v=\"130\", \"Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"130\"",
            secChUaMobile = "?1", secChUaPlatform = "\"Android\""
        ),
        // 5. Windows Edge 132
        Profile(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 Edg/132.0.0.0",
            platform = "web", version = "2.0.2", locale = "zh-CN",
            acceptLanguage = "zh-CN,zh;q=0.9,en;q=0.8",
            accept = "application/json, text/plain, */*",
            secChUa = "\"Chromium\";v=\"132\", \"Not_A Brand\";v=\"8\", \"Microsoft Edge\";v=\"132\"",
            secChUaMobile = "?0", secChUaPlatform = "\"Windows\""
        ),
        // 6. Windows Firefox 133（无 sec-ch-ua）
        Profile(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
            platform = "web", version = "2.0.2", locale = "zh-CN",
            acceptLanguage = "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2",
            accept = "application/json, text/plain, */*",
            secChUa = null, secChUaMobile = null, secChUaPlatform = null,
            secFetchSite = "same-origin", secFetchMode = "cors", secFetchDest = "empty"
        ),
        // 7. iOS Safari 18（iPhone）
        Profile(
            userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Mobile/15E148 Safari/604.1",
            platform = "web", version = "2.0.2", locale = "en_US",
            acceptLanguage = "en-US,en;q=0.9,zh-CN;q=0.8",
            accept = "application/json, text/plain, */*",
            secChUa = null, secChUaMobile = null, secChUaPlatform = null
        ),
        // 8. macOS Safari 18
        Profile(
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.3 Safari/605.1.15",
            platform = "web", version = "2.0.2", locale = "en_US",
            acceptLanguage = "en-US,en;q=0.9",
            accept = "application/json, text/plain, */*",
            secChUa = null, secChUaMobile = null, secChUaPlatform = null
        ),
        // 9. Linux Chrome 131
        Profile(
            userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            platform = "web", version = "2.0.2", locale = "en_US",
            acceptLanguage = "en-US,en;q=0.9",
            accept = "application/json, text/plain, */*",
            secChUa = "\"Chromium\";v=\"131\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"131\"",
            secChUaMobile = "?0", secChUaPlatform = "\"Linux\""
        )
    )

    @Volatile
    private var lastIndex: Int = -1

    /** 随机取一组（记录索引，保证 nextDifferent 语义正确）。 */
    fun random(): Profile {
        val i = (Math.random() * pool.size).toInt().coerceIn(0, pool.size - 1)
        lastIndex = i
        return pool[i]
    }

    /** 强制切到与上次不同的一组（风控触发时用）。 */
    fun nextDifferent(): Profile {
        val prev = lastIndex
        var i = (Math.random() * pool.size).toInt().coerceIn(0, pool.size - 1)
        if (i == prev) i = (i + 1) % pool.size
        lastIndex = i
        return pool[i]
    }
}
