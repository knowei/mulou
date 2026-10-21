package com.dmanime.tools

import com.dmanime.rules.css1.Css1Converter
import com.dmanime.rules.engine.RuleEngine
import com.dmanime.rules.engine.RuleValidator
import com.dmanime.rules.model.AnimeRule
import com.dmanime.rules.model.encodeRule
import com.dmanime.rules.model.parseRule
import com.dmanime.rules.net.HttpUrlConnectionClient
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 规则工具。
 *
 * 用法：
 *   rule-cli <规则文件或目录> [报告输出路径]           —— 静态自检
 *   rule-cli search <规则文件或目录> <关键词> [条数]     —— 真实搜索验证
 *
 * search 模式用与 App 完全相同的 RuleEngine，所以能验证「规则能不能搜」，
 * 而不需要手工在设备上点。
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "search" -> runSearch(args)
        "chapters" -> runChapters(args)
        "health" -> runHealth(args)
        "css1" -> runCss1(args)
        else -> runLint(args)
    }
}

// ---------------------------------------------------------------- css1

/**
 * CSS1（Kazumi Hub / FanYu 规则包）→ 本项目规则。
 *
 * 只做静态转换 + 语法自检，不发网络请求；要验证"转完还能不能用"，
 * 把输出目录再喂给 `health` / `search` 子命令。
 */
private fun runCss1(args: Array<String>) {
    if (args.size < 2) {
        println("用法: rule-cli css1 <css1.json> [输出文件，默认 ./css1-rules.json]")
        exitProcess(2)
    }
    val source = File(args[1])
    if (!source.isFile) {
        println("文件不存在: ${source.absolutePath}")
        exitProcess(2)
    }
    val output = File(args.getOrNull(2) ?: "css1-rules.json")

    val outcome = runCatching { Css1Converter.convert(source.readText()) }.getOrElse {
        println("CSS1 解析失败: ${it.message}")
        exitProcess(2)
    }
    val validated = outcome.rules.map { rule -> rule to RuleValidator.validate(rule) }

    output.writeText(
        outcome.rules.joinToString(",\n", "[\n", "\n]\n") { "  " + encodeRule(it) },
        Charsets.UTF_8,
    )

    val report = StringBuilder()
    fun emit(line: String) {
        println(line)
        report.appendLine(line)
    }

    emit("CSS1 → dmanime：${outcome.rules.size} 条转换成功，${outcome.skipped.size} 条跳过")
    emit("输出: ${output.absolutePath}")
    emit("")
    for ((rule, issues) in validated) {
        val mark = if (issues.isEmpty()) "✓" else "✗"
        val mode = when {
            rule.chapterRoads.isBlank() -> "单线路"
            rule.roadNameSelector.isNotBlank() -> "多线路(有名)"
            else -> "多线路"
        }
        emit("$mark ${rule.name.padEnd(18)} $mode  ${rule.searchList}")
        issues.forEach { emit("    - $it") }
    }

    if (outcome.notes.isNotEmpty()) {
        emit("")
        emit("信息损失（${outcome.notes.size} 条）:")
        outcome.notes.forEach { emit("  · $it") }
    }
    if (outcome.skipped.isNotEmpty()) {
        emit("")
        emit("跳过（${outcome.skipped.size} 条）:")
        outcome.skipped.forEach { emit("  · $it") }
    }

    emit("")
    emit("未迁移的播放期字段：matchVideo / cookies / defaultResolution / defaultSubtitleLanguage /")
    emit("onlySupportsPlayers / selectMedia / requestInterval / matchChannelName / matchEpisodeSortFromName。")
    emit("播放地址由 WebView 嗅探接管，前四项不影响出流；线路名过滤与集数正则的差异见上。")

    val reportFile = File(output.parentFile ?: File("."), output.nameWithoutExtension + ".report.md")
    runCatching { reportFile.writeText(report.toString(), Charsets.UTF_8) }
    println("\n报告: ${reportFile.absolutePath}")

    val broken = validated.count { it.second.isNotEmpty() }
    if (broken > 0 || outcome.rules.isEmpty()) exitProcess(1)
}

// ---------------------------------------------------------------- health

private val DEFAULT_KEYWORDS = listOf("葬送的芙莉莲", "间谍过家家", "药屋少女的呢喃")

/**
 * 规则健康巡检：对每条规则跑「搜索 → 选集」，输出可用性报告。
 *
 * 与 search 的区别：这里要判断"能不能真的用"，所以必须走到选集；
 * 并且对空结果做关键词回退（多试几个标题），避免把"这个词没搜到"误判成坏规则。
 */
private fun runHealth(args: Array<String>) {
    if (args.size < 2) {
        println("用法: rule-cli health <规则文件或目录> [关键词...]")
        exitProcess(2)
    }
    val target = File(args[1])
    val keywords = args.drop(2).ifEmpty { DEFAULT_KEYWORDS }

    val rules = collectRuleFiles(target).mapNotNull { file ->
        runCatching { parseRule(file.readText()) }.getOrNull()
    }
    if (rules.isEmpty()) {
        println("没有找到规则文件: ${target.absolutePath}")
        exitProcess(2)
    }

    println("规则健康巡检：${rules.size} 条规则，关键词 ${keywords.joinToString(" / ")}\n")

    val engine = RuleEngine(HttpUrlConnectionClient(connectTimeoutMs = 6_000, readTimeoutMs = 8_000))
    val pool = Executors.newFixedThreadPool(4)
    val futures = rules.map { rule ->
        pool.submit(Callable { rule to checkRule(engine, rule, keywords) })
    }

    val rows = ArrayList<RuleHealth>()
    for (future in futures) {
        val (rule, health) = future.get()
        rows += health
        println(health.line(rule.name))
    }

    pool.shutdown()
    pool.awaitTermination(30, TimeUnit.SECONDS)

    val full = rows.count { it.status == HealthStatus.OK }
    val searchOnly = rows.count { it.status == HealthStatus.SEARCH_ONLY }
    val empty = rows.count { it.status == HealthStatus.EMPTY }
    val failed = rows.count { it.status == HealthStatus.FAILED }
    println("\n${rules.size} 条：$full 完整可用 / $searchOnly 仅能搜索 / $empty 无结果 / $failed 失败")

    val markdown = buildString {
        appendLine("# 规则健康巡检")
        appendLine()
        appendLine("生成时间：${java.time.LocalDateTime.now().withNano(0)}")
        appendLine()
        appendLine("关键词：${keywords.joinToString(" / ")}")
        appendLine()
        appendLine("> 同一轮内，指向同一站点的规则会共享限流；HTTP 403/429 多半是短时间重复巡检触发，")
        appendLine("> 不代表规则坏了。判断规则死亡应看 DNS / 证书 / 404 / 结构变化。")
        appendLine()
        appendLine("| 规则 | 状态 | 搜索 | 选集 | 耗时 |")
        appendLine("| --- | --- | --- | --- | --- |")
        rows.sortedBy { it.status.ordinal }.forEach { row ->
            appendLine(
                "| ${row.name} | ${row.status.label} | ${row.searchInfo} | ${row.chapterInfo} | ${row.elapsedMs} ms |"
            )
        }
        appendLine()
        appendLine("合计：$full 完整可用 / $searchOnly 仅能搜索 / $empty 无结果 / $failed 失败")
    }
    System.getenv("RULE_CLI_REPORT")?.let { path ->
        runCatching { File(path).writeText(markdown, Charsets.UTF_8) }
    }
}

private enum class HealthStatus(val label: String) {
    OK("完整可用"),
    SEARCH_ONLY("仅搜索"),
    EMPTY("无结果"),
    FAILED("失败"),
}

private data class RuleHealth(
    val name: String,
    val status: HealthStatus,
    val searchInfo: String,
    val chapterInfo: String,
    val elapsedMs: Long,
) {
    fun line(ruleName: String): String {
        val mark = when (status) {
            HealthStatus.OK -> "✓"
            HealthStatus.SEARCH_ONLY -> "◐"
            HealthStatus.EMPTY -> "○"
            HealthStatus.FAILED -> "✗"
        }
        return "$mark ${ruleName.padEnd(16)} ${status.label.padEnd(6)} " +
            "搜索:$searchInfo  选集:$chapterInfo  ${elapsedMs}ms"
    }
}

private fun checkRule(engine: RuleEngine, rule: AnimeRule, keywords: List<String>): RuleHealth {
    val started = System.currentTimeMillis()
    var lastError: Throwable? = null
    var searchCount = 0
    var hit: Pair<String, com.dmanime.rules.model.SearchItem>? = null

    for (keyword in keywords) {
        // 单次抖动不该判定为坏规则：每条关键词给两次机会（间隔 300ms）。
        repeat(2) { attemptIndex ->
            if (hit != null) return@repeat
            val attempt = runCatching { engine.search(rule, keyword) }
            attempt.onSuccess { outcome ->
                if (outcome.items.isNotEmpty()) {
                    searchCount = outcome.items.size
                    hit = keyword to outcome.items.first()
                }
            }.onFailure { lastError = it }
            if (hit == null && attemptIndex == 0) Thread.sleep(300)
        }
        if (hit != null) break
    }

    val elapsed = System.currentTimeMillis() - started
    val found = hit
    if (found == null) {
        return if (lastError != null) {
            RuleHealth(rule.name, HealthStatus.FAILED, describe(lastError!!), "—", elapsed)
        } else {
            RuleHealth(rule.name, HealthStatus.EMPTY, "0 条", "—", elapsed)
        }
    }

    val chapterAttempt = runCatching { engine.chapters(rule, found.second.src) }
    val totalElapsed = System.currentTimeMillis() - started
    return chapterAttempt.fold(
        onSuccess = { chapters ->
            val road = chapters.roads.firstOrNull()
            if (road == null) {
                RuleHealth(rule.name, HealthStatus.SEARCH_ONLY, "$searchCount 条", "0 线路", totalElapsed)
            } else {
                RuleHealth(
                    rule.name, HealthStatus.OK,
                    "$searchCount 条",
                    "${chapters.roads.size} 线路 / ${road.urls.size} 集",
                    totalElapsed,
                )
            }
        },
        onFailure = { error ->
            RuleHealth(rule.name, HealthStatus.SEARCH_ONLY, "$searchCount 条", describe(error), totalElapsed)
        },
    )
}

private fun describe(error: Throwable): String = when (error) {
    is java.net.UnknownHostException -> "域名解析失败"
    is java.net.SocketTimeoutException -> "超时"
    is javax.net.ssl.SSLHandshakeException -> "证书/SSL"
    is java.net.ConnectException -> "连接被拒绝"
    is java.net.SocketException -> "连接被重置"
    is com.dmanime.rules.error.HttpStatusException -> "HTTP ${error.status}"
    is com.dmanime.rules.error.RuleFormatException -> "规则无效: ${error.message?.take(30)}"
    else -> error.javaClass.simpleName
}

// ---------------------------------------------------------------- chapters

/**
 * 搜索 + 取第一条结果的线路/剧集。
 *
 * 这一步才真正考验规则：搜索只用到 searchList/Name/Result，
 * 选集要跑 chapterRoads/chapterResult，坏规则通常在这里露馅。
 */
private fun runChapters(args: Array<String>) {
    if (args.size < 3) {
        println("用法: rule-cli chapters <规则文件或目录> <关键词>")
        exitProcess(2)
    }
    val target = File(args[1])
    val keyword = args[2]

    val rules = collectRuleFiles(target).mapNotNull { file ->
        runCatching { parseRule(file.readText()) }.getOrNull()
    }
    if (rules.isEmpty()) {
        println("没有找到规则文件: ${target.absolutePath}")
        exitProcess(2)
    }

    println("关键词「$keyword」，${rules.size} 条规则（搜索 + 第一条结果的选集）\n")

    val engine = RuleEngine(HttpUrlConnectionClient(connectTimeoutMs = 8_000, readTimeoutMs = 10_000))
    val pool = Executors.newFixedThreadPool(4)
    val futures = rules.map { rule ->
        pool.submit(Callable {
            val outcome = runCatching {
                val search = engine.search(rule, keyword)
                val first = search.items.firstOrNull() ?: return@runCatching "0 条搜索结果"
                val chapters = engine.chapters(rule, first.src)
                val road = chapters.roads.firstOrNull() ?: return@runCatching "有结果但无线路"
                "线路 ${chapters.roads.size} 条 / 首条 ${road.urls.size} 集（${road.name}）"
            }
            rule to outcome
        })
    }

    val report = StringBuilder()
    fun emit(line: String) {
        println(line)
        report.appendLine(line)
    }

    for (future in futures) {
        val (rule, outcome) = future.get()
        val name = rule.name.ifBlank { "?" }.padEnd(16)
        outcome.onSuccess { emit("✓ $name $it") }
            .onFailure { emit("✗ $name ${it.javaClass.simpleName}: ${it.message?.take(110)}") }
    }

    pool.shutdown()
    pool.awaitTermination(30, TimeUnit.SECONDS)
    System.getenv("RULE_CLI_REPORT")?.let { path ->
        runCatching { File(path).writeText(report.toString(), Charsets.UTF_8) }
    }
}

// ---------------------------------------------------------------- search

private fun runSearch(args: Array<String>) {
    if (args.size < 3) {
        println("用法: rule-cli search <规则文件或目录> <关键词> [每条显示条数]")
        exitProcess(2)
    }
    val target = File(args[1])
    val keyword = args[2]
    val limit = args.getOrNull(3)?.toIntOrNull() ?: 2

    val files = collectRuleFiles(target)
    if (files.isEmpty()) {
        println("没有找到规则文件: ${target.absolutePath}")
        exitProcess(2)
    }

    val rules = files.mapNotNull { file ->
        runCatching { parseRule(file.readText()) }.getOrElse {
            println("! ${file.name} 解析失败: ${it.message}")
            null
        }
    }

    println("关键词「$keyword」，${rules.size} 条规则，并发 4\n")

    val report = StringBuilder()
    fun emit(line: String) {
        println(line)
        report.appendLine(line)
    }

    val engine = RuleEngine(HttpUrlConnectionClient(connectTimeoutMs = 8_000, readTimeoutMs = 8_000))
    val pool = Executors.newFixedThreadPool(4)
    val futures = rules.map { rule ->
        pool.submit(Callable { rule to runCatching { engine.search(rule, keyword) } })
    }

    var ok = 0
    var failed = 0
    var empty = 0
    for (future in futures) {
        val (rule, result) = future.get()
        val name = rule.name.ifBlank { "?" }.padEnd(16)
        result.onSuccess { outcome ->
            if (outcome.items.isEmpty()) {
                empty++
                emit("○ $name 0 条    ${outcome.diagnostics.firstOrNull().orEmpty()}")
            } else {
                ok++
                val sample = outcome.items.take(limit).joinToString(" | ") { it.name }
                emit("✓ $name ${outcome.items.size} 条    $sample")
            }
        }.onFailure { error ->
            failed++
            emit("✗ $name ${error.javaClass.simpleName}: ${error.message?.take(120)}")
        }
    }

    pool.shutdown()
    pool.awaitTermination(30, TimeUnit.SECONDS)

    emit("\n${rules.size} 条规则：$ok 有结果，$empty 空结果，$failed 失败")
    System.getenv("RULE_CLI_REPORT")?.let { path ->
        runCatching { File(path).writeText(report.toString(), Charsets.UTF_8) }
    }
    if (ok == 0) exitProcess(1)
}

// ---------------------------------------------------------------- lint

private fun runLint(args: Array<String>) {
    if (args.isEmpty()) {
        println("用法:\n  rule-cli <规则文件或目录> [报告输出路径]\n  rule-cli search <规则文件或目录> <关键词> [条数]")
        exitProcess(2)
    }

    val target = File(args[0])
    if (!target.exists()) {
        println("路径不存在: ${target.absolutePath}")
        exitProcess(2)
    }
    val reportFile = args.getOrNull(1)?.let(::File)

    val files = collectRuleFiles(target)
    if (files.isEmpty()) {
        println("没有找到规则文件")
        exitProcess(2)
    }

    val report = StringBuilder()
    fun emit(line: String) {
        println(line)
        report.appendLine(line)
    }

    var failed = 0
    for (file in files) {
        val result = runCatching { parseRule(file.readText()) }
        if (result.isFailure) {
            emit("✗ ${file.name}  解析失败: ${result.exceptionOrNull()?.message}")
            failed++
            continue
        }
        val rule: AnimeRule = result.getOrThrow()
        val issues = RuleValidator.validate(rule)
        val mark = if (issues.isEmpty()) "✓" else "✗"
        val flag = if (rule.deprecated) " [deprecated]" else ""
        emit("$mark ${file.name.padEnd(18)} api=${rule.api} ${rule.searchMode}/${rule.chapterMode}$flag  ${rule.name}")
        issues.forEach { emit("    - $it") }
        if (issues.isNotEmpty()) failed++
    }

    emit("")
    emit("${files.size} 条规则，${files.size - failed} 条通过，$failed 条有问题")
    reportFile?.writeText(report.toString(), Charsets.UTF_8)
    if (failed > 0) exitProcess(1)
}

private fun collectRuleFiles(target: File): List<File> {
    if (!target.exists()) return emptyList()
    return if (target.isDirectory) {
        target.listFiles { f -> f.isFile && f.extension == "json" && f.name != "index.json" }
            ?.sortedBy { it.name }
            .orEmpty()
    } else {
        listOf(target)
    }
}
