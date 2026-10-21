# 真实规则源抽样分析：Kazumi / TVBox / Legado

> 取样时间：2026-10-18。方法：克隆仓库后用脚本统计，而不是"看几个文件下结论"。
> 复现脚本与快照在 `.scratch/`（已 gitignore），命令见文末附录。

---

## 1. 取样范围

| 生态 | 取样对象 | 规模 |
| --- | --- | --- |
| Kazumi（JSON 规则） | [Predidit/KazumiRules](https://github.com/Predidit/KazumiRules)（MIT） | 86 条规则 + index 索引 |
| TVBox（配置=站点清单） | [Lightconer/tvbox-ysc-config](https://github.com/Lightconer/tvbox-ysc-config) 聚合的 10 个公开源 | 13 个配置 / 1035 个站点 |
| Legado（书源 JSON） | [rektpartyaftermath/Legado-booksource-collection](https://github.com/rektpartyaftermath/Legado-booksource-collection) | 36 条书源（18 个唯一站点） |

**局限（先说清楚）**：
- 每个生态只取了一个代表性仓库，不能代表全量；
- Kazumi 的 `deprecated` 标记是维护者主动打的，不等于"技术上跑不通"（可能是站点关站、也可能是站点被封）；
- 没做联网实测（不请求目标站），所以"存活"只代表"仍在索引里"。

---

## 2. Kazumi：80% 的规则已经死了，而且死因可预测

### 2.1 存活率

| 指标 | 数值 |
| --- | --- |
| 规则文件总数 | 86 |
| 标记 `deprecated` | 69 |
| 仍在 `index.json` 索引中 | **17（20%）** |

**按规则 api 版本分组的存活率**（api = 客户端能力版本，数字越大越新）：

| api | 总数 | 存活 | 存活率 |
| --- | --- | --- | --- |
| 1 | 20 | 1 | 5% |
| 2 | 7 | 0 | 0% |
| 3 | 3 | 0 | 0% |
| 4 | 27 | 3 | 11% |
| 5 | 10 | 3 | 30% |
| 6 | 5 | 1 | 20% |
| 7 | 3 | 0 | 0% |
| 8 | 6 | 4 | 67% |
| 9 | 5 | **5** | **100%** |

这条曲线是整份报告里最有信息量的东西：**规则格式越新、结构越"混合"，存活率越高**。api 9 的 5 条全活，且都是最近 2–3 天批量更新的；api 1–4 的老规则几乎全灭。

### 2.2 活规则的"年龄"

17 条活规则距离最后更新的天数（截至 2026-10-18）：

```
LMM/dalvdm/mgnacg/mutefun   2 天      MXdm/xfdmnext   16 天
giriGiriLove                3 天      moonci          49 天
aafun 57 / akianime 61 / ezdmw 62 / 7sefun 68
sorani 96 / xfdmneo 223 / DM84 257 / baimao 262 / AGE 395
中位数：57 天
```

**意味着**：一套"活着"的规则集，每个月都有一半在老化。不做规则热更新和失效检测，交付当天就是巅峰。

### 2.3 格式特征：它其实只有两种形态

| 特征 | 数量 | 占比 |
| --- | --- | --- |
| 纯 XPath（搜索+选集都走 HTML） | 77 | 90% |
| 用到 API（JSON 接口） | 9 | 10% |
| `useWebview = true` | 85 | 99% |
| `useNativePlayer = true` | 84 | 98% |
| `adBlocker = true` | 16 | 19% |
| `antiCrawlerConfig.enabled` | 4 | 5% |
| 设置 `referer` | 8 | 9% |
| 设置 `userAgent` | **0** | 0% |
| 含媒体直链（m3u8/mp4）字面量 | **0** | **0%** |

三个直接结论：

1. **没有一条规则包含真实播放地址。** 规则只负责把客户端带到"播放页"，取流 100% 交给 WebView 嗅探。这验证了此前"解析分三段、C 段必须另做"的判断。
2. **`userAgent` 字段从来没人用，`referer` 只有 9% 用**。说明对抗请求头不是主要矛盾，别在这上面过度设计。
3. **反爬只有 4 条，且 4 条全部已废弃。** 图形验证码方案（`captchaImage`/`captchaInput`/`captchaButton` 三个 XPath）在实践中没跑通——这是"遇验证码就放弃该站"的强烈信号。

### 2.4 XPath 的真实写法：位置选择器是主要死因

| 写法 | 规则数（共 86） |
| --- | --- |
| `//div[2]` 这类纯位置选择 | **31（36%）** |
| `contains(@class, ...)` | 12 |
| 以 `.//` 相对路径开头 | 3（搜索名） |
| 以 `//` 绝对路径开头 | 74 |
| 高级轴（`self::` / `following-sibling` / 并集 `\|`） | **1** |

36% 的规则靠 `//div[2]/div[1]/...` 定位——页面加一个 div 就废。这就是 80% 死亡率的技术解释。

唯一用到高级轴的是 `ezdmw.json`（api 8，存活），它的选集选择器长这样：

```
/self::*[@class='line_button_ban']/following-sibling::a[@class='circuit_switch_ban']
| /self::*[@class='line_button1']/following-sibling::a[@class='circuit_switch1']
| /self::*[@class='line_button3']/following-sibling::a[@class='circuit_switch3']
| /self::*[@class='line_button2']/following-sibling::a[@class='circuit_switch2']
```

**工程含义**：你的 XPath 引擎必须支持 `self::`、`following-sibling::`、并集 `|`、谓词。Jsoup 只有 CSS 选择器，不能直接用；Kotlin 侧需要 JAXP/XPath（`javax.xml.xpath`）或 Jaxen，且要先把 HTML 转成 W3C DOM（Jsoup + W3CDom）。这是 M1 里最容易被低估的一块。

### 2.5 最新的 5 条规则是同一个模板

api 9 的五条（LMM / dalvdm / giriGiriLove / mgnacg / mutefun）结构完全一致，都是苹果 CMS（MacCMS）的搜索接口：

```json
"searchApiConfig": {
  "request": { "method": "GET",
    "url": "https://<站点>/index.php/ajax/suggest",
    "query": { "mid": "1", "wd": "@keyword" } },
  "listPath": "$.list[*]", "namePath": "$.name", "sourcePath": "$.id",
  "sourceTemplate": "https://<站点>/index.php/vod/detail/id/@source.html"
}
```

差异只有 `baseURL` 和 `sourceTemplate` 的路径样式（`/vod/detail/id/@source.html` 或 `/GV@source/`）。

**这是本次分析里杠杆最高的发现**：与其手写几十条规则，不如做一个「MacCMS 通用源生成器」——输入站点清单（名字 + 域名），自动产出上面这段 JSON。动漫站里 MacCMS 系占大头，这个模板能覆盖一大片。

### 2.6 schema 的语义坑（兼容时必须照抄，不能"优化"）

| 字段 | 真实语义 | 坑 |
| --- | --- | --- |
| `muliSources` | 多源开关 | **拼写就是错的**（multi → muli），改名即不兼容 |
| `api` | 客户端能力版本（字符串 `"9"`，不是数字） | 规则用它判断"你会不会 `sourceTemplate`" |
| `searchMode` / `chapterMode` | 缺省值为 `xpath` | 老规则没有这两个字段 |
| `roadsPath: "$"` | 整个响应就是单条线路 | 见于 milimili / sorani |
| `episodeUrlPath` | 有时是"剧集 id"，要拼进 `episodePage.url` | 名字叫 Url，实际是 id |
| `@roadIndex` / `@episodeIndex` | **从 0 开始** | 与 `@roadNumber`（从 1）混用会错集 |
| `format: "delimited"` | MacCMS 的 `$$$`/`#`/`$` 三段切分 | 专为苹果 CMS 设计，正好能接 TVBox |
| `deprecated` | 维护者标记，客户端应过滤 | 仓库靠它 + `index.json` 实现"下架不下线" |

---

## 3. TVBox：它不是规则格式，是插件宿主

1035 个站点的类型分布：

| type | 含义 | 数量 | 占比 |
| --- | --- | --- | --- |
| 3 | **spider**（jar/dex 代码 / JS 文件） | 955 | **92.3%** |
| 1 | JSON 接口（多为 MacCMS `api.php/provide/vod/`） | 78 | 7.5% |
| 0 | XML 接口（同样 MacCMS，换输出格式） | 2 | 0.2% |

字段使用率：`ext` 出现 651 次（63%），`jar` 274 次（26%），8/12 个配置在顶层挂了全局 `spider` jar。

实际样例（`api` 字段的真面目）：

```
type=3  api=https://jihulab.com/.../drpy2.min.js   ext=.../douban.js     ← JS 爬虫文件
type=3  api=csp_DouDou                                                    ← dex 里的类名
type=1  api=http://xh1.xn--yetu07f.icu/api.php/provide/vod/               ← MacCMS JSON
type=0  api=https://suoniapi.com/api.php/provide/vod/at/xml/              ← MacCMS XML
```

结论：**TVBox 的 92% 是"代码插件 + 每站一个 ext 参数"，没有可解析的规则语义**。想兼容它只有两条路——要么把 dex/JS 沙箱也实现一遍（等于重做一个 TVBox），要么只吃 type 1/0 那 7.7%。

---

## 4. Legado：字段 + 表达式 + 内嵌脚本的"三层混合体"

36 条书源的语法特征：

| 语法 | 含义 | 使用率 |
| --- | --- | --- |
| `##` | 正则替换链（`##正则##替换`） | **83%** |
| `{{ }}` | 变量/表达式插值 | **83%** |
| `@js:` | 字段级 JS 脚本 | **39%** |
| `@css:` | CSS 选择器 | 33% |
| `<js>` | 整段脚本块 | 33% |
| `java.` | 调用宿主 Java 桥接 | 28% |
| `$.` | JSONPath | 11% |

规则结构是五段式：`ruleSearch` / `ruleExplore` / `ruleBookInfo` / `ruleToc` / `ruleContent`，每段 5–12 个字段（`bookList/bookUrl/name/coverUrl/intro/kind/chapterList/chapterName/chapterUrl/content/replaceRegex`…），另有 `header`（28/36）、`loginUrl`（6）、`loginCheckJs`、`concurrentRate`。

**结论**：
- 表达力确实最强——一条字段里可以混 CSS/XPath/JSONPath + `{{}}` + `##` + `@js:`；
- 但它是**字段为主、脚本为辅**，不是"整条规则写成 JS"；
- 28% 直接调 `java.` 桥接，说明它的沙箱基本等于没有边界（能读写文件、发任意请求）；
- 领域不同：书是"一个 content 字符串"，没有线路/剧集/嗅探的概念，**无法直接转成视频规则**。

对你有用的不是它的 schema，而是它的**语法设计**：`{{}}` 插值、`##` 替换链、`@js:` 字段级脚本——这三样正好对应此前设计的「提取器链 + 表达式 + 脚本字段」，而且已经被 83%/83%/39% 的真实使用率验证过。

---

## 5. 横向对比

| | Kazumi | TVBox | Legado |
| --- | --- | --- | --- |
| 规则本质 | JSON 数据 | 代码插件（jar/JS/dex） | JSON + 表达式 + JS |
| 可热更新 | ✅ | ✅（但等于下发代码） | ✅ |
| 可被非程序员维护 | ✅（F12 复制 XPath） | ❌ | 半 ✅ |
| 表达力 | 中（XPath/JSONPath + 反爬） | 高（图灵完备） | 高（`java.*` 全开） |
| 安全性 | 中（只抓 HTML/JSON） | 低（下发代码） | 低（Java 桥接） |
| 真实存活率 | 20%（本仓库） | 站点数多但依赖中心化 jar | 未统计 |
| 取流方式 | WebView 嗅探 | 插件自己算 | 不涉及 |
| 你能复用什么 | **17 条活规则 + 格式** | type1/0 的 7.7%（MacCMS） | 语法设计（`{{}}`/`##`/`@js:`） |

---

## 6. 兼容策略（结论）

### 6.1 主 schema：Kazumi 的超集，而不是"支持三种格式"

运行时只认一种格式（原生 JSON），其余靠**离线导入器**转换。这样引擎简单，兼容性靠工具链解决。

```
canonical.json (Kazumi 超集)
   ▲                ▲                    ▲
   │ 100%           │ 7.7% (type 1/0)    │ 0%（只借语法）
Kazumi 规则      TVBox 配置           Legado 书源
```

### 6.2 三条导入通道的实际产出

| 来源 | 可自动转换比例 | 产出 | 工作量 |
| --- | --- | --- | --- |
| Kazumi（17 条活规则） | **100%** | 直接可用（含 XPath 与 API 两种） | 1 天（写解析器 + 单测） |
| TVBox（1035 站点） | **78 条（7.5%）** | MacCMS JSON → delimited API 规则 | 2–3 天（含 `$$$` 拆分） |
| Legado（36 条） | 0% | 只借语法：`{{}}`/`##`/`@js:` | 设计期参考，不做转换 |

### 6.3 字段映射：TVBox type1 → Kazumi/原生

MacCMS 的 JSON 接口几乎是为 `delimited` 格式量身定做的：

| TVBox | 原生规则 | 说明 |
| --- | --- | --- |
| `site.name` | `name` | |
| `site.api`（`.../api.php/provide/vod/`） | `searchApiConfig.request.url + "?ac=videolist&wd=@keyword"` | |
| — | `listPath: "$.list[*]"`，`namePath: "$.vod_name"`，`sourcePath: "$.vod_id"` | |
| — | `chapterApiConfig.request.url + "?ac=detail&ids=@source"` | |
| `vod_play_from` | `roadNamesPath` + `roadSeparator: "$$$"` | |
| `vod_play_url` | `roadEpisodesPath` + `episodeSeparator: "#"` + `nameUrlSeparator: "$"` | 名字$地址 |
| 播放地址 | `episodeUrlPath`（直接就是 m3u8） | **这类源不需要嗅探** |

也就是说：**兼容 TVBox 的 type1，本质是兼容苹果 CMS**，顺带把 Kazumi api9 那套 `sourceTemplate` 也吃下来——两件事是一件事。

### 6.4 扩展命名空间：`x-` 前缀，保持单向兼容

自定义能力（提取器链、脚本、嗅探指令）全部放进 `x-` 字段：

```json
{
  "name": "example", "api": "9",
  "searchMode": "xpath", "searchURL": "...", "searchList": "...",
  "x-extract": { "episodeUrl": [ {"type":"regex","pattern":"..."}, {"type":"base64"} ] },
  "x-sniff": { "timeoutMs": 12000, "prefer": "hls" }
}
```

- 读 Kazumi 规则：忽略 `x-*` 即可；
- 导出给 Kazumi：剥掉 `x-*`，仍是一条合法规则（哪怕功能退化）；
- 这样"兼容"是**单向的**——你能吃它的，它不用理解你的。

### 6.5 不要兼容什么

| 不要做 | 原因 |
| --- | --- |
| TVBox 的 spider jar/JS | 等于自己实现插件沙箱 + dex 动态加载，工作量超过整个 App |
| Legado 的 `java.*` 桥接 | 无边界，安全模型直接破产 |
| 运行时多格式解析 | 引擎复杂度翻倍，收益只有 7.5% |
| `loginUrl` / `loginCheckJs` / CookieJar 登录态 | 动漫站几乎不需要；真要登录就走 WebView 手动登，别做账号体系 |

---

## 7. 这份数据改变了什么

1. **规则热更新从"加分项"变成"P0"**：中位 57 天，20% 存活，没有更新通道的 App 一个月后就是空壳。
2. **MACCM 模板 > 手写规则**：5 条最新规则同构，说明"站点清单 + 模板"能批量产出。
3. **XPath 引擎是硬需求，不是选配**：`self::`/`following-sibling`/`|` 真实出现，Jsoup 单干不行。
4. **`useWebview` 是必需品**：99% 的规则都设了它，取流没有第二条路。
5. **反爬别做重**：全仓库只有 4 条尝试，且全死。遇到验证码的正确姿势是"换站点"，不是"做验证码识别"。
6. **兼容 Kazumi 是纯赚**：格式公开、MIT、17 条活规则，1 天成本换一个能跑的源池。

---

## 附录：复现方式

```powershell
# 1. 拉规则
git clone --depth 1 https://github.com/Predidit/KazumiRules.git .scratch/kazumirules

# 2. 统计（存活率 / 字段 / XPath 特征）
python .scratch/analyze.py
python .scratch/analyze2.py
python .scratch/analyze3.py

# 3. TVBox 与 Legado
git clone --depth 1 https://github.com/Lightconer/tvbox-ysc-config.git .scratch/tvbox
git clone --depth 1 https://github.com/rektpartyaftermath/Legado-booksource-collection.git .scratch/legado
python .scratch/tvbox.py
python .scratch/legado.py
```

注意：`shidahuilang/shuyuan` 这个更常用的书源仓库已经被 GitHub 以 **TOS 违规** 屏蔽（API 返回 `Repository access blocked, reason: tos`）。规则仓库的"随时消失"不是假设，是已发生的事——**导出/备份能力要内置**。
