package com.dmanime.rules.error

open class RuleException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 规则本身写错了（选择器无效、JSONPath 不支持、模板缺变量……）。 */
class RuleFormatException(message: String, cause: Throwable? = null) : RuleException(message, cause)

/** 命中了站点验证页，需要人工过一次验证。 */
class CaptchaRequiredException(val ruleName: String) :
    RuleException("规则 $ruleName 命中验证页，需要人工完成验证")

class HttpStatusException(val status: Int, val url: String) :
    RuleException("HTTP $status: $url")
