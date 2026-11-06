package com.zzu.lite.domain

/**
 * 与 ZZU.Py 异常体系对齐的业务异常。
 * UI 层捕获后按类型给出友好提示。
 */
open class ZzuException(message: String, val context: Map<String, String> = emptyMap()) : Exception(message)

/** 网络层失败：连接超时、DNS、非 2xx 等 */
open class NetworkException(message: String, context: Map<String, String> = emptyMap()) : ZzuException(message, context)

/** 响应解析失败 */
class ParsingException(message: String, context: Map<String, String> = emptyMap()) : ZzuException(message, context)

/** 服务端业务失败（code/result != 0 等） */
class OperationException(message: String, context: Map<String, String> = emptyMap()) : ZzuException(message, context)

/** 登录链路失败 */
open class LoginException(message: String, context: Map<String, String> = emptyMap()) : ZzuException(message, context)

/** 需要 MFA 验证 / MFA 验证未通过 */
class MfaException(message: String, context: Map<String, String> = emptyMap()) : LoginException(message, context)

/** 未登录或凭据已失效且无法静默重登 */
class NotLoggedInException(message: String = "尚未登录或登录已失效") : ZzuException(message)

/** 参数不合法 */
class InvalidArgumentException(message: String) : ZzuException(message)

/** 数据不存在（如周次越界） */
class DataNotFoundException(message: String) : ZzuException(message)

/** 校园网：当前网络未被网关劫持（通常意味着已认证或不在校园网内） */
class PortalNotDetectedException(message: String = "未检测到校园网认证网关（可能已在线或不在校园网内）") : NetworkException(message)
