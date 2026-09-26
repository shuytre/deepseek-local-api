package com.ds.localapi.core

/**
 * DeepSeek 网页版协议常量。
 *
 * 逆向接口不稳定，若上线后协议有变，请优先调整本文件里的常量。
 */
object Protocol {
    const val DS_BASE_URL = "https://chat.deepseek.com"
    const val DS_API_PREFIX = "/api/v0"

    // 与 DeepSeek 官方网页端一致的请求头基准（参考实现 DS_HEADERS）
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/134.0.0.0 Safari/537.36"
    const val CLIENT_PLATFORM = "web"
    const val CLIENT_VERSION = "2.0.2"
    const val CLIENT_LOCALE = "zh-CN"

    const val PATH_CREATE_SESSION = "/chat_session/create"
    const val PATH_DELETE_SESSION = "/chat_session/delete"
    const val PATH_CREATE_POW = "/chat/create_pow_challenge"
    const val PATH_COMPLETION = "/chat/completion"
    const val POW_TARGET_PATH = "/api/v0/chat/completion"

    /**
     * 模型已整合（v2.1.0）。
     *
     * 2026-09-09 起官方把原 V4 Flash / V4 Flash Vision / V4 Pro 的请求统一路由到
     * V4.1 Flash，网页端不再存在「快速 / 专家」两套模型，model_type 恒为 default。
     * 深度思考（thinking_enabled）与识图（ref_file_ids）改为独立开关，
     * 不再与模型选择绑定 —— 也就是说：只有一个模型，且它同时会思考、会看图。
     */
    const val MODEL_TYPE_FIELD = "model_type"
    const val MODEL_TYPE_DEFAULT = "default"

    // OpenAI 兼容对外暴露的模型 id（本地 /v1/models 用）。
    //
    // 唯一真实模型：deepseek-v4.1-flash。其余 id 仅作历史/别名兼容，
    // 服务端都会落到 V4.1 Flash，行为完全一致。
    const val MODEL_FLASH = "deepseek-v4.1-flash"          // 唯一模型（深度思考 + 识图）
    const val MODEL_FLASH_OFFICIAL = "deepseek-flash"      // 官方 API 同名别名
    const val MODEL_FLASH_LEGACY = "deepseek-v4-flash"     // 上一代旧名（已整合）
    const val MODEL_PRO = "deepseek-v4-pro"                // 旧名兼容（原 V4 Pro，已整合）

    /** 文件上传（识图 / 读文档）：multipart 上传后拿到 file_id，放进 ref_file_ids。 */
    const val PATH_FILE_UPLOAD = "/file/upload"
}

/** PoW challenge returned by `/chat/create_pow_challenge`. */
data class PowChallenge(
    val algorithm: String,
    val challenge: String,
    val salt: String,
    val expireAt: Long,
    val difficulty: Long,
    val signature: String,
    val targetPath: String
)

/** 从 completion SSE 流中解析出的增量事件。 */
sealed class SseEvent {
    /** 深度思考（thinking）增量文本。 */
    data class Thinking(val delta: String) : SseEvent()

    /** 正文实际回答增量文本。 */
    data class Text(val delta: String) : SseEvent()

    /** 整段流结束。 */
    object Done : SseEvent()

    /** 流中上浮的错误。 */
    data class Error(val message: String) : SseEvent()
}

/** 解析后的完整回答。 */
data class ChatResult(
    val text: String,
    val thinking: String
)

/** 本地 OpenAI 兼容服务对外暴露的模型（v2.1.0 起只有一个真实模型）。 */
object ModelCatalog {
    /** 唯一模型。 */
    val UNIFIED = Protocol.MODEL_FLASH
    val DEFAULT = UNIFIED
    val FLASH = UNIFIED
    /** 旧名兼容：指向同一模型，不再代表「专家模式」。 */
    val PRO = Protocol.MODEL_PRO

    fun list(): List<Map<String, Any>> {
        fun m(id: String, desc: String) = mapOf(
            "id" to id,
            "object" to "model",
            "created" to 1700000000L,
            "owned_by" to "deepseek",
            "description" to desc
        )
        return listOf(
            m(UNIFIED, "DeepSeek V4.1 Flash · 唯一模型（默认开启深度思考，支持识图）"),
            m(Protocol.MODEL_FLASH_OFFICIAL, "别名，等价于 ${Protocol.MODEL_FLASH}"),
            m(Protocol.MODEL_FLASH_LEGACY, "旧名兼容，等价于 ${Protocol.MODEL_FLASH}"),
            m(PRO, "旧名兼容（原 V4 Pro 已整合，等价于 ${Protocol.MODEL_FLASH}）")
        )
    }
}