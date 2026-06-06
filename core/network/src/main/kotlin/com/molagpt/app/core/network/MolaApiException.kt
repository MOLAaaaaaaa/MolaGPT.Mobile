package com.molagpt.app.core.network

/** JWT 失效（401）。聊天界面据此清 token 并提示重新登录，而非反复重试注定失败的请求。 */
class MolaAuthExpiredException(message: String = "登录状态已失效，请重新登录") : Exception(message)

/** 后端返回的业务错误（非 2xx 或流内 error）。 */
class MolaApiException(val statusCode: Int?, message: String) : Exception(message)
