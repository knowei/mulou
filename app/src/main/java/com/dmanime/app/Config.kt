package com.dmanime.app

const val DEFAULT_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/** 演示用规则：结构完整但没有真实站点，只用来验证 UI 与解析链路。 */
const val DEMO_RULE = """
{
  "api": "9",
  "type": "anime",
  "name": "示例规则（无真实站点）",
  "version": "1.0",
  "baseURL": "https://example.com/",
  "searchURL": "https://example.com/search?wd=@keyword",
  "searchList": "//ul[@class='list']//li",
  "searchName": ".//h3/a",
  "searchResult": ".//h3/a",
  "chapterRoads": "//div[@class='play-list']",
  "chapterResult": ".//li/a"
}
"""

/**
 * 规则源（仓库）。应用不内置任何规则，只内置地址；是否导入由用户决定。
 *
 * 多镜像顺序重试：GitHub raw 在国内经常不可达，jsDelivr 命中率更高。
 */
val RULE_REPO_MIRRORS = listOf(
    "https://fastly.jsdelivr.net/gh/Predidit/KazumiRules@main/",
    "https://cdn.jsdelivr.net/gh/Predidit/KazumiRules@main/",
    "https://raw.githubusercontent.com/Predidit/KazumiRules/main/",
)

/**
 * 我们自己维护的规则包（`rules/rules.ndjson` + `rules/manifest.json`）。
 *
 * 跟上面的 KazumiRules 不是一回事：那个是别人的通用规则库，这个是我们一条条
 * 体检过、修过 XPath、补过 encrypt=2 解密和独立线路分隔符的版本。
 */
val RULE_SYNC_MIRRORS = listOf(
    "https://cdn.jsdelivr.net/gh/knowei/mulou@main/rules/",
    "https://fastly.jsdelivr.net/gh/knowei/mulou@main/rules/",
    "https://raw.githubusercontent.com/knowei/mulou/main/rules/",
)
