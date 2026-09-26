package com.ds.localapi

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.ds.localapi.databinding.ActivityLoginBinding
import com.ds.localapi.store.Settings
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 内置浏览器登录 chat.deepseek.com，自动提取 user_token。
 *
 * 设计原则：
 *  - 不预写旧 token 到 Cookie（避免"空白却报成功"）。
 *  - 完整 WebView 配置 + 桌面 UA，避免白屏。
 *  - 轮询提取 token 并【实时显示在页面上】，但【绝不自动返回】。
 *  - 由用户点击底部「完成并返回」才保存并退出；点返回键 = 取消，不保存。
 *  - 开发者工具（F12）面板改为底部停靠，不再覆盖 WebView，保证登录页可交互。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var settings: Settings

    private val handler = Handler(Looper.getMainLooper())
    private val consoleLines = StringBuilder()

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            extractAndShowToken()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = Settings(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnManual.setOnClickListener { showManualInputDialog() }
        binding.btnDone.setOnClickListener { onUserFinish() }

        setupDevtools()
        setupWebView()

        // 从首页三角形菜单进入且指定手动模式时，直接弹出手动输入
        if (intent?.getBooleanExtra(EXTRA_FORCE_MANUAL, false) == true) {
            showManualInputDialog()
        }
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s: WebSettings = binding.webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = false
        s.loadsImagesAutomatically = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        // 关键：使用桌面 Chrome UA，DeepSeek 前端对默认移动 UA 渲染异常会导致白屏
        s.userAgentString = DESKTOP_UA

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, true)
        }

        // 捕获 console 输出到 F12 面板
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                msg ?: return super.onConsoleMessage(msg)
                appendConsole("[${msg.messageLevel()}] ${msg.message()}")
                return true
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // chat.deepseek.com 域内跳转（含 OAuth 回跳）在 WebView 内打开
                return !url.contains("deepseek.com")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url?.contains("chat.deepseek.com") == true) {
                    refreshDevtools()
                    extractAndShowToken()
                }
            }
        }

        binding.webView.loadUrl(DS_SITE)
        handler.postDelayed(pollRunnable, 2000)
    }

    // ---------- Token 提取（只显示，不自动退出）----------

    private fun extractAndShowToken() {
        readLocalStorage { raw ->
            val fromStorage = parseUserToken(raw)
            val fromCookie = readCookieToken()
            val token = fromStorage ?: fromCookie
            runOnUiThread {
                if (!token.isNullOrEmpty()) {
                    binding.txtLoginStatus.text = getString(R.string.login_detected)
                    binding.txtLoginStatus.setTextColor(getColor(R.color.success))
                    binding.txtLoginToken.setText(token)
                } else {
                    binding.txtLoginStatus.text = getString(R.string.login_waiting)
                    binding.txtLoginStatus.setTextColor(getColor(R.color.warning))
                }
            }
        }
    }

    private fun readCookieToken(): String? =
        CookieManager.getInstance().getCookie(DS_SITE)
            ?.split(";")
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("user_token=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotEmpty() }

    private fun readLocalStorage(cb: (String?) -> Unit) {
        binding.webView.evaluateJavascript(
            "(function(){try{return localStorage.getItem('userToken')}catch(e){return null}})()"
        ) { value ->
            // evaluateJavascript 返回 JSON 字符串字面量：null / "plain" / "{\"value\":...}"
            cb(value)
        }
    }

    /**
     * 从 localStorage 原始值提取真实 token。
     *
     * DeepSeek 实际存的是 {"value":"xxx","__version":"0"}，且 evaluateJavascript
     * 会再包一层 JSON 转义（"{\"value\":\"xxx\",...}"）。这里做完整解码：
     *  1. 若以引号开头 -> 用 JSONTokener 解出字符串本体（自动去转义）
     *  2. 若是 {"value":...} 对象 -> 取 value 字段
     *  3. 兜底：整串就是裸 token（长度足够且无空白）
     */
    private fun parseUserToken(raw: String?): String? {
        if (raw.isNullOrEmpty() || raw == "null") return null

        // 第一步：剥掉 evaluateJavascript 的 JSON 字符串包装
        var s: String = raw
        if (s.startsWith("\"")) {
            val decoded: String = try {
                when (val v = JSONTokener(s).nextValue()) {
                    is String -> v
                    is JSONObject -> v.toString() // 解出的是对象，继续下一步取 value
                    else -> return null
                }
            } catch (_: Exception) {
                s.trim('"')
            }
            s = decoded
        }

        // 第二步：{"value":"xxx","__version":"0"} -> 取 value
        if (s.startsWith("{")) {
            try {
                val v = JSONObject(s).optString("value")
                if (v.isNotEmpty()) return v
            } catch (_: Exception) {
            }
            return null
        }

        // 第三步：裸 token
        return s.takeIf { it.length >= 16 && !it.contains(' ') }
    }

    /** 用户点击【完成并返回】：保存检测到的 token（若有），否则仅返回不保存。不强制保存旧值。 */
    private fun onUserFinish() {
        val manual = binding.txtLoginToken.text.toString().trim()
        val token = manual.ifEmpty { null }
        if (token != null) {
            settings.userToken = token
            setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
        } else {
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    // ---------- 手动输入 ----------

    private fun showManualInputDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.manual_placeholder)
        input.minLines = 4
        input.maxLines = 6
        input.setPadding(24, 12, 24, 12)

        AlertDialog.Builder(this)
            .setTitle(R.string.manual_title)
            .setMessage(R.string.manual_hint)
            .setView(input)
            .setPositiveButton(R.string.manual_save) { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val token = detectTokenFromText(raw)
                if (!token.isNullOrEmpty()) {
                    // 填入页面供用户核对，不直接退出
                    binding.txtLoginToken.setText(token)
                    binding.txtLoginStatus.text = getString(R.string.login_detected)
                    binding.txtLoginStatus.setTextColor(getColor(R.color.success))
                } else {
                    AlertDialog.Builder(this)
                        .setMessage("未能识别有效的 Token / Cookie，请检查后重试。")
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.manual_cancel, null)
            .show()
    }

    private fun detectTokenFromText(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // user_token=xxx Cookie 形式
        if (text.contains("user_token=")) {
            val v = text.substringAfter("user_token=").substringBefore(";").trim()
            if (v.isNotEmpty()) return v
        }
        // {"value":...} 或带转义的 JSON 对象：统一走健壮解析
        parseUserToken(text)?.let { return it }
        // Bearer 前缀
        val cleaned = text.removePrefix("Bearer").trim()
        if (cleaned.length >= 16) return cleaned
        return null
    }

    // ---------- 开发者工具（F12） ----------

    private fun setupDevtools() {
        binding.btnDevtools.setOnClickListener { toggleDevtools() }
        binding.btnDevtoolsRefresh.setOnClickListener { refreshDevtools() }
        binding.btnDevtoolsClose.setOnClickListener { toggleDevtools() }
        binding.btnDevCopyToken.setOnClickListener { copyTokenFromDevtools() }
    }

    private fun toggleDevtools() {
        val panel = binding.devtoolsPanel
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (panel.visibility == View.VISIBLE) refreshDevtools()
    }

    private fun refreshDevtools() {
        binding.txtDevUrl.text = binding.webView.url ?: "-"
        binding.txtDevCookies.text = CookieManager.getInstance().getCookie(DS_SITE)
            ?: getString(R.string.devtools_empty)

        binding.webView.evaluateJavascript(
            "(function(){try{var o={};for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);o[k]=localStorage.getItem(k)}return JSON.stringify(o,null,1)}catch(e){return 'error: '+e}})()"
        ) { value ->
            val text = value?.removePrefix("\"")?.removeSuffix("\"")
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?: getString(R.string.devtools_empty)
            binding.txtDevStorage.text = text
        }

        binding.txtDevConsole.text = consoleLines.toString().ifEmpty { getString(R.string.devtools_empty) }
    }

    private fun copyTokenFromDevtools() {
        readLocalStorage { raw ->
            val token = parseUserToken(raw) ?: readCookieToken()
            runOnUiThread {
                if (!token.isNullOrEmpty()) {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ds_token", token))
                    Toast.makeText(this, "Token 已复制", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "当前未检测到 Token（请先登录）", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Synchronized
    private fun appendConsole(line: String) {
        if (consoleLines.length > 8000) consoleLines.setLength(0)
        consoleLines.append(line).append('\n')
        if (binding.devtoolsPanel.visibility == View.VISIBLE) {
            binding.txtDevConsole.text = consoleLines.toString()
        }
    }

    // ---------- 生命周期 ----------

    override fun onBackPressed() {
        if (binding.devtoolsPanel.visibility == View.VISIBLE) {
            binding.devtoolsPanel.visibility = View.GONE
            return
        }
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            // 返回键 = 取消登录，不保存
            setResult(RESULT_CANCELED)
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollRunnable)
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TOKEN = "token"
        const val EXTRA_FORCE_MANUAL = "force_manual"
        private const val DS_SITE = "https://chat.deepseek.com/"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}