package com.dmanime.rules

import com.dmanime.rules.model.AnimeRule
import com.dmanime.rules.model.encodeRule

/**
 * 一次规则同步要动的东西。
 *
 * 只描述「远端有而本地没有」「远端和本地不一样」，至于本地多出来的规则
 * （用户自己导入的）一律不动——那多半是他自己的源，删掉就是事故。
 */
data class RuleDiff(
    val added: List<AnimeRule> = emptyList(),
    val updated: List<AnimeRule> = emptyList(),
    val unchanged: Int = 0,
) {
    val total: Int get() = added.size + updated.size
    val isEmpty: Boolean get() = total == 0
    val addedNames: List<String> get() = added.map { it.name }
    val updatedNames: List<String> get() = updated.map { it.name }
}

/**
 * 规则同步的纯逻辑：比对与合并。
 *
 * 比对不看 version 字段——规则包里的 version 经常是空或没跟着改，
 * 直接比编码后的内容最实在：只要有一个字段不同就算需要更新。
 */
object RuleSync {

    fun diff(local: List<AnimeRule>, remote: List<AnimeRule>): RuleDiff {
        val localByName = local.filter { it.name.isNotBlank() }.associateBy { it.name }
        val added = ArrayList<AnimeRule>()
        val updated = ArrayList<AnimeRule>()
        var unchanged = 0

        for (rule in remote) {
            if (rule.name.isBlank() || rule.deprecated) continue
            val old = localByName[rule.name]
            when {
                old == null -> added += rule
                encodeRule(old) == encodeRule(rule) -> unchanged++
                else -> updated += rule
            }
        }
        return RuleDiff(added = added, updated = updated, unchanged = unchanged)
    }

    /** 把一次同步应用到本地规则表：同名替换、新名字追加，其它保持原样与原顺序。 */
    fun apply(local: List<AnimeRule>, diff: RuleDiff): List<AnimeRule> {
        if (diff.isEmpty) return local
        val incoming = (diff.updated + diff.added).associateBy { it.name }
        val kept = local.map { incoming[it.name] ?: it }
        val existing = local.map { it.name }.toSet()
        val appended = diff.added.filterNot { it.name in existing }
        return kept + appended
    }
}
