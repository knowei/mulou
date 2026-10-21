package com.dmanime.rules.engine

import com.dmanime.rules.error.RuleFormatException
import com.dmanime.rules.model.AnimeRule

data class RuleIssue(val field: String, val message: String) {
    override fun toString(): String = "$field: $message"
}

/**
 * 规则自检：在「导入规则」和「规则测试面板」里跑，把问题在保存前就暴露出来。
 *
 * 除了必填字段，还会真的编译一遍 XPath / 校验一遍 JSONPath——
 * 真实世界 80% 的规则都是这样坏掉的。
 */
object RuleValidator {

    private val apiStrategy = ApiStrategy()
    private val emptyDocument = HtmlXPath.parse("<html><body></body></html>")

    fun validate(rule: AnimeRule): List<RuleIssue> {
        val issues = ArrayList<RuleIssue>()

        if (rule.name.isBlank()) issues += RuleIssue("name", "规则名称不能为空")
        if (rule.baseUrl.isBlank()) issues += RuleIssue("baseURL", "基础地址不能为空")

        if (rule.usesApiSearch) {
            try {
                apiStrategy.validateSearchConfig(rule.searchApiConfig)
            } catch (e: RuleFormatException) {
                issues += RuleIssue("searchApiConfig", e.message.orEmpty())
            }
        } else {
            requireXPath(issues, "searchURL", rule.searchUrl, isUrl = true)
            requireXPath(issues, "searchList", rule.searchList)
            if (rule.searchName.isNotBlank()) requireXPath(issues, "searchName", rule.searchName)
            requireXPath(issues, "searchResult", rule.searchResult)
            if (rule.searchNameSelector.isNotBlank()) {
                requireXPath(issues, "x-searchNameSelector", rule.searchNameSelector)
            }
        }

        if (rule.usesApiChapters) {
            try {
                apiStrategy.validateChapterConfig(rule.chapterApiConfig)
            } catch (e: RuleFormatException) {
                issues += RuleIssue("chapterApiConfig", e.message.orEmpty())
            }
        } else {
            // chapterRoads 允许为空：表示整页只有一条线路（CSS1 的 no-channel）。
            if (rule.chapterRoads.isNotBlank()) {
                requireXPath(issues, "chapterRoads", rule.chapterRoads)
            }
            requireXPath(issues, "chapterResult", rule.chapterResult)
            if (rule.roadNameSelector.isNotBlank()) {
                requireXPath(issues, "x-roadNameSelector", rule.roadNameSelector)
            }
        }

        if (rule.antiCrawlerConfig.enabled) {
            val config = rule.antiCrawlerConfig
            if (config.captchaDetectValue.isNotBlank() &&
                config.captchaDetectType == com.dmanime.rules.model.CaptchaDetectType.XPATH
            ) {
                requireXPath(issues, "antiCrawlerConfig.captchaDetectValue", config.captchaDetectValue)
            }
        }

        return issues
    }

    private fun requireXPath(issues: MutableList<RuleIssue>, field: String, expression: String, isUrl: Boolean = false) {
        if (expression.isBlank()) {
            issues += RuleIssue(field, "不能为空")
            return
        }
        if (isUrl) {
            if (!expression.contains("@keyword")) issues += RuleIssue(field, "缺少 @keyword 占位符")
            return
        }
        try {
            HtmlXPath.select(emptyDocument, expression)
        } catch (e: RuleFormatException) {
            issues += RuleIssue(field, e.message.orEmpty())
        }
    }
}
