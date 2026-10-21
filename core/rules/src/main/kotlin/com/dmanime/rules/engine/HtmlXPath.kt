package com.dmanime.rules.engine

import com.dmanime.rules.error.RuleFormatException
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

/**
 * XPath 执行层：Jsoup 解析 HTML → W3C DOM → `javax.xml.xpath`。
 *
 * 为什么不用 Jsoup 自带的选择器：Jsoup 只支持 CSS。真实规则里出现了
 * `self::`、`following-sibling::`、并集 `|`、`text()`，CSS 表达不了。
 */
object HtmlXPath {

    fun parse(html: String): Document =
        try {
            // namespaceAware 必须关掉：默认开启时 jsoup 会给元素挂上 XHTML 命名空间，
            // 规则里的 //div 这种无前缀选择器就一个都匹配不到。
            W3CDom().namespaceAware(false).fromJsoup(Jsoup.parse(html))
        } catch (e: Exception) {
            throw RuleFormatException("HTML 响应解析失败", e)
        }

    fun select(context: Node, expression: String): List<Node> {
        if (expression.isBlank()) throw RuleFormatException("XPath 不能为空")
        val expr = relativize(context, expression.trim())
        return try {
            val nodes = XPathFactory.newInstance().newXPath()
                .compile(expr)
                .evaluate(context, XPathConstants.NODESET) as NodeList
            (0 until nodes.length).map { nodes.item(it) }
        } catch (e: RuleFormatException) {
            throw e
        } catch (e: Exception) {
            throw RuleFormatException("XPath 无效: $expression", e)
        }
    }

    /**
     * 兼容性关键：86 条真实规则里有 74 条把条目字段写成 `//div[2]/a`，
     * 而 XPath 1.0 中前导 `//` 是「从文档根开始」，会让每个条目都命中
     * 文档里的第一个节点。规则显然不是这个意思，所以对「非文档上下文」
     * 的查询把前导 `//` 改写为 `.//`（即相对当前节点查找后代）。
     */
    private fun relativize(context: Node, expression: String): String =
        if (context !is Document && expression.startsWith("//")) ".$expression" else expression

    fun text(node: Node): String = when (node.nodeType) {
        Node.ATTRIBUTE_NODE -> node.nodeValue?.trim().orEmpty()
        Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> node.nodeValue?.trim().orEmpty()
        else -> node.textContent?.trim().orEmpty()
    }

    /** 取 href：节点自身是属性节点时取属性值，否则取 href 属性。 */
    fun href(node: Node): String {
        if (node.nodeType == Node.ATTRIBUTE_NODE) return node.nodeValue?.trim().orEmpty()
        val attrs = node.attributes ?: return ""
        val href = attrs.getNamedItem("href") ?: return ""
        return href.nodeValue?.trim().orEmpty()
    }
}
