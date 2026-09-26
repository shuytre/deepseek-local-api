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
     * completion 请求体里用于选择模型的字段名。
     *
     * 网页端「快速模式 / 专业模式」不是通过 model 字段切换，而是用 model_type：
     *  - 专业模式（专家）     -> model_type = "expert"，同时开启思考
     *  - 快速模式（V4.1 Flash）-> model_type = "default"
     *
     * 参考 deepseek-reverse-api：误发 model 字段会被官方忽略，回落到旧版默认模型。
     */
    const val MODEL_TYPE_FIELD = "model_type"
    const val MODEL_TYPE_EXPERT = "expert"
    const val MODEL_TYPE_DEFAULT = "default"

    // OpenAI 兼容对外暴露的模型 id（本地 /v1/models 用）。
    //
    // 2026-09-09 官方发布 DeepSeek V4.1 Flash（552B MoE，Causal-Encoder-Decoder 新架构），
    // 网页端「快速模式」底层已切换为 V4.1 Flash；旧 V4 Flash / V4 Flash Vision /
    // V4 Pro 请求均由官方服务端路由到 V4.1 Flash。网页端协议字段（model_type）未变，
    // 因此本客户端仅需更新对外模型目录与默认名，无需改动请求结构。
    const val MODEL_FLASH = "deepseek-v4.1-flash"          // 新默认（快速模式）
    const val MODEL_FLASH_OFFICIAL = "deepseek-flash"      // 官方 API 同名别名
    const val MODEL_FLASH_LEGACY = "deepseek-v4-flash"     // 上一代旧名（官方路由到 V4.1 Flash）
    const val MODEL_PRO = "deepseek-v4-pro"                // 专家模式（原 V4 Pro，官方路由到 V4.1 Flash）
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

/** 本地 OpenAI 兼容服务对外暴露的模型。 */
object ModelCatalog {
    // 快速模式（默认，V4.1 Flash）/ 专家模式（深度思考）
    val FLASH = Protocol.MODEL_FLASH
    val PRO = Protocol.MODEL_PRO
    val DEFAULT = FLASH

    fun isPro(model: String?): Boolean = model?.contains("pro") == true || model?.contains("expert") == true

    fun list(): List<Map<String, Any>> {
        fun m(id: String, desc: String) = mapOf(
            "id" to id,
            "object" to "model",
            "created" to 1700000000L,
            "owned_by" to "deepseek",
            "description" to desc
        )
        return listOf(
            m(FLASH, "快速模式 · DeepSeek V4.1 Flash（默认，性能全面超越上代旗舰）"),
            m(Protocol.MODEL_FLASH_OFFICIAL, "DeepSeek V4.1 Flash · 官方 API 同名别名，等价 ${Protocol.MODEL_FLASH}"),
            m(Protocol.MODEL_FLASH_LEGACY, "DeepSeek V4 Flash · 旧名兼容，已由官方路由到 V4.1 Flash"),
            m(PRO, "专家模式 · 深度思考（原 V4 Pro，官方已路由到 V4.1 Flash）")
        )
    }
}