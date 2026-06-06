package com.molagpt.app.core.network

/**
 * MolaGPT 真实后端端点（基址 `https://chatgpt.wljay.cn/v2/`，全 HTTPS）。
 * 对话端点不在此列——按所选模型的相对 `apiUrl`（来自 model_config_public.php）动态解析。
 */
object MolaEndpoints {
    const val BASE_URL = "https://chatgpt.wljay.cn/v2/"

    const val LOGIN = "api/auth/login.php"
    const val OAUTH_EXCHANGE = "api/auth/oauth_exchange.php"
    const val STATUS = "api/auth/status.php"
    const val MODEL_CONFIG = "api/auth/model_config_public.php"

    /** ALTCHA 挑战发放（无需鉴权）。 */
    const val CHALLENGE = "api/auth/challenge.php"

    /** 用 ALTCHA 解换取 60 秒短 token（游客/登录共用；登录态再带长 token）。 */
    const val SHORT_TOKEN_AUTH = "api/auth/auth.php"

    const val STOP_STREAM = "api/auth/stop_stream.php"
    const val CHECK_STREAM_STATUS = "api/auth/check_stream_status.php"
    const val FETCH_COMPLETED_STREAM = "api/auth/fetch_completed_stream.php"
    const val GENERATE_TITLE = "api/auth/generateTitle.php"
    const val BATCH_UPLOAD = "api/=imgtemp/batchUpload.php"

    /** 对话历史云同步（full_sync / fetch_conversation / delete / 分块）。 */
    const val SYNC = "api/auth/sync.php"

    /** 个性化数据：人格洞察 (insights) 与对话风格偏好 (style_preferences)。 */
    const val USER_DATA = "api/auth/user_data_manager.php"

    /** 模型未提供 apiUrl 时的兜底（自动路由）。 */
    const val DEFAULT_CHAT_API = "api/auth/chatAuto.php"

    fun absolute(relative: String): String =
        if (relative.startsWith("http")) relative else BASE_URL + relative.removePrefix("/")
}
