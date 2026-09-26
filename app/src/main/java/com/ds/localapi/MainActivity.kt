package com.ds.localapi

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ds.localapi.core.EventLog
import com.ds.localapi.core.ModelCatalog
import com.ds.localapi.core.RateGovernor
import com.ds.localapi.store.AccountPool
import com.ds.localapi.databinding.ActivityMainBinding
import com.ds.localapi.databinding.PageChatBinding
import com.ds.localapi.databinding.PageHomeBinding
import com.ds.localapi.databinding.PageSettingsBinding
import com.ds.localapi.store.ApiKey
import com.ds.localapi.store.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeBinding: PageHomeBinding
    private lateinit var chatBinding: PageChatBinding
    private lateinit var settingsBinding: PageSettingsBinding
    private lateinit var settings: Settings

    /** 只读视图：与 LocalServer 内的 RateGovernor 共享同一 SharedPreferences 状态。 */
    private lateinit var rateGovernorView: RateGovernor
    private lateinit var accountPool: AccountPool

    /** 本次打开登录页是否为了添加池内第 2 个账号。 */
    private var pendingPoolAdd = false

    private var serverRunning = false
    private var sending = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 深度思考 / 排队慢时，一次对话可能远超 60s。
        // 本地服务端内部已有 600s（10 分钟）的对话硬上限并会返回明确错误，这里把客户端读
        // 超时设得更宽（11 分钟），保证永远先收到服务端的结果，而不是客户端先报底层的 Time out。
        .readTimeout(660, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.Main)

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val token = result.data?.getStringExtra(LoginActivity.EXTRA_TOKEN).orEmpty()
                if (token.isNotEmpty()) {
                    if (pendingPoolAdd) {
                        // 组建账号池：作为第 2 个账号加入，双账号轮换
                        if (accountPool.addSecond(token)) {
                            appendLog("✓ 账号2 已加入，双账号轮换模式启用")
                            Toast.makeText(this, R.string.pool_added, Toast.LENGTH_SHORT).show()
                        } else {
                            appendLog("账号池已满或该账号已在池中，未添加")
                            Toast.makeText(this, R.string.pool_full_or_dup, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        settings.userToken = token
                        accountPool.syncPrimary(token)
                        appendLog("✓ 登录成功，token 已更新")
                    }
                }
            }
            pendingPoolAdd = false
            refreshAccountState()
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = Settings(this)
        ApiKey.init(this)
        accountPool = AccountPool(settings)
        rateGovernorView = RateGovernor(settings, accountPool)

        requestNotificationPermissionIfNeeded()

        inflatePages()
        setupNav()
        setupHome()
        setupChat()
        setupSettings()

        switchPage(0)
    }

    // ---------- 页面框架 ----------

    private fun inflatePages() {
        val inflater = LayoutInflater.from(this)
        homeBinding = PageHomeBinding.inflate(inflater, binding.contentContainer, false)
        chatBinding = PageChatBinding.inflate(inflater, binding.contentContainer, false)
        settingsBinding = PageSettingsBinding.inflate(inflater, binding.contentContainer, false)

        binding.contentContainer.addView(homeBinding.root)
        binding.contentContainer.addView(chatBinding.root)
        binding.contentContainer.addView(settingsBinding.root)
    }

    private fun setupNav() {
        binding.navHome.setOnClickListener { switchPage(0) }
        binding.navChat.setOnClickListener { switchPage(1) }
        binding.navSettings.setOnClickListener { switchPage(2) }
    }

    private fun switchPage(index: Int) {
        homeBinding.root.visibility = if (index == 0) View.VISIBLE else View.GONE
        chatBinding.root.visibility = if (index == 1) View.VISIBLE else View.GONE
        settingsBinding.root.visibility = if (index == 2) View.VISIBLE else View.GONE

        // 选中项：圆角背景 + 蓝色图标/文字；未选中：无背景 + 灰色
        setNavState(binding.navHome, binding.iconHome, binding.labelHome, index == 0)
        setNavState(binding.navChat, binding.iconChat, binding.labelChat, index == 1)
        setNavState(binding.navSettings, binding.iconSettings, binding.labelSettings, index == 2)

        // 进入设置页时刷新风控用量显示
        if (index == 2) refreshRateUsage()
    }

    private fun setNavState(item: View, icon: ImageView, label: TextView, selected: Boolean) {
        item.background = if (selected) getDrawable(R.drawable.bg_nav_select) else null
        val color = getColor(if (selected) R.color.nav_item_on else R.color.nav_item_off)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(color)
        label.setTextColor(color)
    }

    // ---------- 首页 ----------

    private fun setupHome() {
        updateModelChips(settings.model)

        homeBinding.chipFlash.setOnClickListener { settings.model = ModelCatalog.FLASH; updateModelChips(ModelCatalog.FLASH) }
        homeBinding.chipPro.setOnClickListener { settings.model = ModelCatalog.PRO; updateModelChips(ModelCatalog.PRO) }

        homeBinding.switchLan.isChecked = settings.lanEnabled
        homeBinding.switchLan.setOnCheckedChangeListener { _, checked ->
            settings.lanEnabled = checked
            appendLog(if (checked) "局域网访问：开启" else "局域网访问：关闭（仅本机）")
            updateBaseUrl()
        }

        homeBinding.btnStartStop.setOnClickListener {
            if (serverRunning) {
                ServerService.stop(this)
                setServerRunning(false)
            } else {
                ServerService.start(this)
                setServerRunning(true)
            }
        }

        homeBinding.btnCopyKey.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("api_key", homeBinding.txtApiKey.text))
            Toast.makeText(this, "API Key 已复制", Toast.LENGTH_SHORT).show()
        }

        // 一键复制连接配置（v1.9.0）：端口 + 本机/局域网 URL + key + 模型
        homeBinding.btnCopyEndpoint.setOnClickListener { copyEndpoint() }
        homeBinding.btnCopyConnectKey.setOnClickListener { copyKey() }
        homeBinding.btnCopyAll.setOnClickListener { copyConnectionConfig() }

        homeBinding.btnLogin.setOnClickListener { startActivityForLogin() }
        homeBinding.btnLoginMenu.setOnClickListener { showLoginMenu() }
        homeBinding.btnEditToken.setOnClickListener { showEditTokenDialog() }

        // 双击 Token 字段复制
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                copyTokenToClipboard()
                return true
            }
        })
        homeBinding.txtTokenDisplay.setOnTouchListener { _, event -> gesture.onTouchEvent(event) }

        refreshAccountState()
        updateApiKeyView()
        updateBaseUrl()
    }

    private fun copyTokenToClipboard() {
        val token = settings.userToken
        if (token.isEmpty()) {
            Toast.makeText(this, R.string.token_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ds_token", token))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun showEditTokenDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.token_edit_hint)
        input.setText(settings.userToken)
        input.minLines = 3
        input.maxLines = 6
        input.setPadding(24, 12, 24, 12)

        AlertDialog.Builder(this)
            .setTitle(R.string.token_edit_title)
            .setView(input)
            .setPositiveButton(R.string.manual_save) { _, _ ->
                val value = input.text.toString().trim()
                settings.userToken = value
                accountPool.syncPrimary(value)
                appendLog(if (value.isEmpty()) "已清除 Token" else "Token 已手动更新")
                refreshAccountState()
            }
            .setNegativeButton(R.string.manual_cancel, null)
            .show()
    }

    private fun updateModelChips(model: String) {
        val usePro = ModelCatalog.isPro(model)
        homeBinding.chipFlash.background = if (!usePro)
            getDrawable(R.drawable.bg_model_chip) else null
        homeBinding.chipPro.background = if (usePro)
            getDrawable(R.drawable.bg_model_chip) else null
        chatBinding.txtChatModel.text = if (usePro) getString(R.string.model_pro) else getString(R.string.model_flash)
    }

    /** 登录按钮旁的三角形菜单：手动输入 + 多账号池管理。 */
    private fun showLoginMenu() {
        val menu = PopupMenu(this, homeBinding.btnLoginMenu, Gravity.END)
        menu.menu.add(0, 1, 0, getString(R.string.login_manual))
        if (accountPool.size < 2) {
            menu.menu.add(0, 2, 0, getString(R.string.pool_build))
        } else {
            menu.menu.add(0, 3, 0, getString(R.string.pool_manage))
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivityForLogin(forceManual = true)
                2 -> {
                    // 组成多账号池：再登录一个账号，登录成功后作为账号2 加入
                    pendingPoolAdd = true
                    if (accountPool.size == 0 && settings.userToken.isNotBlank()) {
                        accountPool.syncPrimary(settings.userToken)
                    }
                    startActivityForLogin()
                }
                3 -> showPoolManageDialog()
            }
            true
        }
        menu.show()
    }

    /** 账号池管理：查看两个账号状态，手动切换 / 解散池。 */
    private fun showPoolManageDialog() {
        val lines = StringBuilder()
        for (i in 0 until accountPool.size) {
            val cd = accountPool.cooldownRemainingSec(i)
            val state = if (cd > 0) "风控冷却中（剩 ${cd / 60 + 1} 分钟）" else "正常"
            val cur = if (i == accountPool.active) " · 当前使用" else ""
            val tk = accountPool.tokenAt(i)
            lines.append("账号${i + 1}：$state$cur\n…${tk.takeLast(12)}\n\n")
        }
        val options = arrayOf(
            getString(R.string.pool_switch_to, if (accountPool.active == 0) 2 else 1),
            getString(R.string.pool_dissolve)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.pool_manage)
            .setMessage(lines.toString().trim())
            .setItems(options) { _, which ->
                when (which) {
                    0 -> accountPool.switchTo(accountPool.otherSlot(accountPool.active))
                    1 -> accountPool.dissolve()
                }
                refreshAccountState()
            }
            .setNegativeButton(R.string.manual_cancel, null)
            .show()
    }

    private fun startActivityForLogin(forceManual: Boolean = false) {
        val intent = Intent(this, LoginActivity::class.java)
        if (forceManual) intent.putExtra(LoginActivity.EXTRA_FORCE_MANUAL, true)
        loginLauncher.launch(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setServerRunning(running: Boolean) {
        serverRunning = running
        homeBinding.btnStartStop.text = if (running) getString(R.string.server_stop) else getString(R.string.server_start)
        homeBinding.txtServerStatus.text = if (running) getString(R.string.server_running) else getString(R.string.server_stopped)
        val color = if (running) getColor(R.color.success) else getColor(R.color.text_secondary)
        homeBinding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        appendLog(if (running) "服务启动请求已发送" else "服务已停止")
    }

    private fun updateApiKeyView() {
        homeBinding.txtApiKey.text = ApiKey.get()
    }

    private fun refreshAccountState() {
        val token = accountPool.activeToken().ifEmpty { settings.userToken }
        if (token.isEmpty()) {
            homeBinding.txtTokenStatus.text = getString(R.string.account_not_logged)
            homeBinding.txtTokenStatus.setTextColor(getColor(R.color.warning))
            homeBinding.txtTokenDisplay.text = getString(R.string.token_empty)
            homeBinding.txtPoolStatus.visibility = View.GONE
        } else {
            homeBinding.txtTokenStatus.text = getString(R.string.account_logged_in)
            homeBinding.txtTokenStatus.setTextColor(getColor(R.color.success))
            homeBinding.txtTokenDisplay.text = token
            // 双账号池：显示轮换状态行
            if (accountPool.isPoolMode) {
                homeBinding.txtPoolStatus.visibility = View.VISIBLE
                val sb = StringBuilder()
                for (i in 0 until accountPool.size) {
                    if (i > 0) sb.append("  ·  ")
                    val cd = accountPool.cooldownRemainingSec(i)
                    sb.append("账号${i + 1} ").append(if (cd > 0) "冷却${cd / 60 + 1}分" else "正常")
                    if (i == accountPool.active) sb.append("（使用中）")
                }
                homeBinding.txtPoolStatus.text = sb.toString()
            } else {
                homeBinding.txtPoolStatus.visibility = View.GONE
            }
        }
    }

    private fun updateBaseUrl() {
        val host = if (settings.lanEnabled) getLocalIpAddress() ?: "0.0.0.0" else "127.0.0.1"
        val url = "http://$host:${settings.port}/v1"
        homeBinding.txtBaseUrl.text = "Base URL: $url"

        // 接入 Agent 工具卡（v1.9.2）：API端点 + API Key
        homeBinding.txtEndpointValue.text = currentEndpoint()
        homeBinding.txtConnectKeyValue.text = ApiKey.get()
    }

    // ---------- 一键复制（v1.9.2） ----------

    /**
     * 首选端点：局域网可用时用局域网地址（手机与同 WiFi 电脑都能访问），
     * 否则回退本机 127.0.0.1（手机上的工具自身访问）。
     */
    private fun currentEndpoint(): String {
        val ip = getLocalIpAddress()
        val host = if (settings.lanEnabled && ip != null) ip else "127.0.0.1"
        return "http://$host:${settings.port}/v1"
    }

    /** 复制 API 端点。 */
    private fun copyEndpoint() {
        val ep = currentEndpoint()
        copyText("ds_endpoint", ep, R.string.connect_copied_endpoint)
    }

    /** 复制 API Key。 */
    private fun copyKey() {
        val key = ApiKey.get()
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.connect_not_logged, Toast.LENGTH_LONG).show()
            return
        }
        copyText("ds_api_key", key, R.string.connect_copied_key)
    }

    /** 统一复制：API端点 + API Key（按用户指定格式）。 */
    private fun copyConnectionConfig() {
        val key = ApiKey.get()
        if (key.isEmpty()) {
            Toast.makeText(this, R.string.connect_not_logged, Toast.LENGTH_LONG).show()
            return
        }
        val sb = StringBuilder()
        sb.append("API端点：").append(currentEndpoint()).append('\n')
        sb.append("API key：").append(key)
        copyText("ds_conn_all", sb.toString(), R.string.connect_copied_all)
    }

    private fun copyText(label: String, content: String, toastRes: Int) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, content))
        Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show()
    }

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()?.forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.inetAddresses?.asSequence()?.forEach { addr ->
                    if (addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 对话 ----------

    private fun setupChat() {
        chatBinding.btnSend.setOnClickListener { sendMessage() }
        // 深度思考开关，记住上次选择
        chatBinding.switchThinking.isChecked = settings.thinkingEnabled
        chatBinding.switchThinking.setOnCheckedChangeListener { _, checked ->
            settings.thinkingEnabled = checked
        }
    }

    private fun sendMessage() {
        val text = chatBinding.chatInput.text.toString().trim()
        if (text.isEmpty()) return
        if (accountPool.activeToken().isEmpty() && settings.userToken.isEmpty()) {
            appendChatInfo(getString(R.string.chat_need_token))
            switchPage(0)
            return
        }
        if (!serverRunning) {
            appendChatInfo(getString(R.string.chat_need_server))
            switchPage(0)
            return
        }
        if (sending) return

        chatBinding.chatInput.text?.clear()
        appendBubble(text, isUser = true)
        val holder = appendBubble(getString(R.string.chat_waiting), isUser = false)

        sending = true
        val thinking = chatBinding.switchThinking.isChecked
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) {
                    callLocalApi(text, settings.model, thinking)
                }
                holder.text = reply
            } catch (e: Exception) {
                holder.text = "请求失败：${e.message}"
            } finally {
                sending = false
                scrollChatToBottom()
            }
        }
    }

    /** 非流式调用本地 /v1/chat/completions，等待完整回复后返回。 */
    private fun callLocalApi(prompt: String, model: String, thinking: Boolean): String {
        val url = "http://127.0.0.1:${settings.port}/v1/chat/completions"
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("thinking", thinking)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            ))

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${ApiKey.get()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw Exception(msg ?: "HTTP ${resp.code}")
            }
            val root = JSONObject(raw)
            val content = root.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            return content.ifEmpty { getString(R.string.chat_empty_reply) }
        }
    }

    private fun appendBubble(text: String, isUser: Boolean): TextView {
        val holder = TextView(this)
        holder.text = text
        holder.textSize = 15f
        holder.setTextColor(if (isUser) Color.WHITE else getColor(R.color.text_primary))
        holder.setPadding(dp(14), dp(10), dp(14), dp(10))
        holder.setBackgroundResource(if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble)
        holder.setLineSpacing(2f, 1f)

        // 长按复制消息内容
        holder.setOnLongClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("chat_msg", holder.text.toString()))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            true
        }

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(10), 0, 0)
        lp.gravity = if (isUser) Gravity.END else Gravity.START
        holder.layoutParams = lp
        chatBinding.chatList.addView(holder)
        scrollChatToBottom()
        return holder
    }

    private fun appendChatInfo(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 13f
        tv.setTextColor(getColor(R.color.text_secondary))
        tv.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, dp(12), 0, 0)
        tv.layoutParams = lp
        chatBinding.chatList.addView(tv)
    }

    private fun scrollChatToBottom() {
        chatBinding.chatScroll.post {
            chatBinding.chatScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    // ---------- 设置 ----------

    private fun setupSettings() {
        // 优化 API 调用开关：开启保持上下文（复用同一会话），关闭每次新建会话
        settingsBinding.switchOptimizeApi.isChecked = settings.optimizeApiCalls
        settingsBinding.switchOptimizeApi.setOnCheckedChangeListener { _, checked ->
            settings.optimizeApiCalls = checked
            appendLog(if (checked) "优化 API 调用：开启（保持上下文）" else "优化 API 调用：关闭（每次新建会话）")
        }

        // 「?」说明：点一下展开/收起
        settingsBinding.btnOptimizeInfo.setOnClickListener {
            settingsBinding.txtOptimizeInfo.visibility =
                if (settingsBinding.txtOptimizeInfo.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // 帮助：点击「帮助」才展开教程，再点收起
        settingsBinding.btnHelpHeader.setOnClickListener {
            val show = settingsBinding.helpBody.visibility != View.VISIBLE
            settingsBinding.helpBody.visibility = if (show) View.VISIBLE else View.GONE
            settingsBinding.txtHelpArrow.text = if (show) "▴" else "▾"
        }

        settingsBinding.switchLoginDebug.isChecked = settings.loginDebugPanel
        settingsBinding.switchLoginDebug.setOnCheckedChangeListener { _, checked ->
            settings.loginDebugPanel = checked
        }

        // Agent 工具调用桥接开关（Operit 等 agent 接入用）
        settingsBinding.switchToolBridge.isChecked = settings.toolBridgeEnabled
        settingsBinding.switchToolBridge.setOnCheckedChangeListener { _, checked ->
            settings.toolBridgeEnabled = checked
            appendLog(if (checked) "Agent 工具桥接：开启" else "Agent 工具桥接：关闭（tools 参数将被忽略）")
        }

        // 风控保护限额 / 事件日志
        settingsBinding.btnRateLimits.setOnClickListener { showRateLimitsDialog() }
        settingsBinding.btnEventLog.setOnClickListener { showEventLogDialog() }

        // API 调用测试：如实测试本地服务
        settingsBinding.btnApiTest.setOnClickListener { showApiTestDialog() }
    }

    // ---------- 风控保护 ----------

    /** 刷新设置页的风控用量与冷却状态显示。 */
    private fun refreshRateUsage() {
        if (!this::settingsBinding.isInitialized) return
        settingsBinding.txtRateUsage.text = getString(
            R.string.settings_rate_usage_fmt,
            rateGovernorView.usageLastHour(), settings.rateHourlyLimit,
            rateGovernorView.usageLast24h(), settings.rateDailyLimit
        )
        val cd = rateGovernorView.cooldownRemainingSec()
        if (cd > 0) {
            settingsBinding.txtCooldown.text = getString(R.string.settings_cooldown_fmt, cd / 60 + 1)
            settingsBinding.txtCooldown.setTextColor(getColor(R.color.warning))
        } else {
            settingsBinding.txtCooldown.text = getString(R.string.settings_cooldown_none)
            settingsBinding.txtCooldown.setTextColor(getColor(R.color.success))
        }
    }

    private fun showRateLimitsDialog() {
        val hourInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(settings.rateHourlyLimit.toString())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val dayInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(settings.rateDailyLimit.toString())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        container.addView(fieldLabel(R.string.rate_hourly_label))
        container.addView(hourInput)
        container.addView(fieldLabel(R.string.rate_daily_label))
        container.addView(dayInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.rate_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.manual_save) { _, _ ->
                val h = hourInput.text.toString().toIntOrNull() ?: settings.rateHourlyLimit
                val d = dayInput.text.toString().toIntOrNull() ?: settings.rateDailyLimit
                settings.rateHourlyLimit = h
                settings.rateDailyLimit = d
                appendLog("风控保护限额已调整为 $h 次/小时、$d 次/天")
                Toast.makeText(this, R.string.rate_saved, Toast.LENGTH_SHORT).show()
                refreshRateUsage()
            }
            .setNegativeButton(R.string.manual_cancel, null)
            .show()
    }

    private fun showEventLogDialog() {
        val lines = EventLog.snapshot()
        val content = if (lines.isEmpty()) getString(R.string.settings_event_log_empty)
        else lines.joinToString("\n")
        val tv = TextView(this).apply {
            text = content
            textSize = 12f
            setLineSpacing(2f, 1f)
            setTextColor(getColor(R.color.text_primary))
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_event_log_btn)
            .setView(scroll)
            .setPositiveButton(R.string.test_close, null)
            .show()
    }

    // ---------- API 调用测试 ----------

    private fun showApiTestDialog() {
        val models = arrayOf(ModelCatalog.FLASH, ModelCatalog.PRO)

        val promptInput = EditText(this).apply {
            hint = getString(R.string.test_prompt_label)
            minLines = 2
            maxLines = 6
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        container.addView(fieldLabel(R.string.test_prompt_label))
        container.addView(promptInput)
        container.addView(fieldLabel(R.string.test_model_label))
        container.addView(spinner)
        val result = TextView(this).apply {
            text = getString(R.string.test_no_server_hint)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(14), 0, 0)
        }
        container.addView(result)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.test_dialog_title)
            .setView(container)
            .setPositiveButton(R.string.test_start, null)
            .setNeutralButton(R.string.test_copy, null)
            .setNegativeButton(R.string.test_close, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val prompt = promptInput.text.toString().trim()
                if (prompt.isEmpty()) {
                    result.text = getString(R.string.test_empty_prompt)
                    result.setTextColor(getColor(R.color.warning))
                } else {
                    result.text = getString(R.string.test_running)
                    result.setTextColor(getColor(R.color.text_secondary))
                    val model = models[spinner.selectedItemPosition]
                    scope.launch {
                        try {
                            val reply = withContext(Dispatchers.IO) { testAgainstLocalApi(prompt, model) }
                            result.text = if (reply.isEmpty()) "成功：${getString(R.string.chat_empty_reply)}" else "成功：\n$reply"
                            result.setTextColor(getColor(R.color.success))
                        } catch (e: Exception) {
                            // 如实上报，不做任何兜底美化
                            result.text = "失败：\n${e.message}"
                            result.setTextColor(getColor(R.color.error))
                        }
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("api_test", result.text))
                Toast.makeText(this, R.string.test_copied, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun testAgainstLocalApi(prompt: String, model: String): String {
        val url = "http://127.0.0.1:${settings.port}/v1/chat/completions"
        val body = JSONObject()
            .put("model", model)
            .put("stream", false)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", prompt)
            ))
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${ApiKey.get()}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw Exception(msg ?: "HTTP ${resp.code}\n$raw")
            }
            return JSONObject(raw)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
        }
    }

    private fun fieldLabel(resId: Int): TextView = TextView(this).apply {
        setText(resId)
        setTextColor(getColor(R.color.text_primary))
        textSize = 13f
        setPadding(0, 0, 0, dp(4))
    }

    // ---------- 其它 ----------

    private fun appendLog(line: String) {
        val current = homeBinding.txtLog.text.toString()
        val new = if (current == getString(R.string.log_empty)) line else "$current\n$line"
        homeBinding.txtLog.text = new
    }
}