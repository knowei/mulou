# 动漫 App 调研报告

> 场景：Android 端 · 通过「自定义规则 / 解析第三方站点」播放 · 仅自己或小团队使用
> 调研时间：2026-10 · 结论基于公开仓库、官方文档与公开判例

---

## 0. 一句话结论

**这个品类的"标准答案"已经存在**：Kazumi（Flutter，37k star）用「JSON 规则 + WebView 嗅探 + libmpv」把搜索、追番、弹幕、投屏、下载全做完了，而且它的规则仓库是 MIT 协议、可被你直接复用。

所以真正要回答的不是"怎么做"，而是：

1. **你是想两周内用上，还是想拥有一个自己的可控项目？**
   - 想用 → 直接装 Kazumi / Aniyomi，或 fork Kazumi 改 UI 和默认规则（自用不发布，GPL 义务不触发）。
   - 想拥有 → 用 **Kotlin + Compose 自研**，但**规则格式与 Kazumi 保持兼容**，白嫖社区几十条现成规则。
2. **风险不在代码，而在"规则会死"和"你会不会传播"**。规则平均每月都在坏，必须有规则仓库 + 热更新 + 一键禁用；法律上"自用"和"分发/带广告"是两个世界（见第 9 节判例）。

推荐路线：**Kotlin 原生自研（媒体3 + libmpv 双播放器），规则引擎兼容 Kazumi JSON 格式**。工作量约 6–9 周出可用版。

---

## 1. 调研边界

| 维度 | 结论中的假设 |
| --- | --- |
| 平台 | Android 手机/平板（不做 iOS/TV/桌面，但架构留余地） |
| 内容来源 | 第三方动漫站的规则化解析（非自有片源、非官方授权） |
| 分发 | 不分发或极小范围分发，走 GitHub Releases / 本地 APK，不上 Google Play |
| 目标 | 能搜、能追、能看、能下、有弹幕，体验接近 B 站客户端 |

---

## 2. 五个关键发现

### 2.1 已有成熟对标，且开源协议友好度不同

| 项目 | 技术栈 | Star | 协议 | 规则形态 | 对你的价值 |
| --- | --- | --- | --- | --- | --- |
| **Kazumi** | Flutter / Dart | 37.7k | GPL-3.0 | **JSON（XPath / JSONPath 双模式）** | 产品形态与规则设计的最佳参考，规则可直接复用 |
| **Aniyomi** | Kotlin / Android | 7.8k | Apache-2.0 | **Kotlin 扩展 APK**（`AnimeSource` 接口） | 架构最"正规"，Apache 协议 fork 最自由 |
| **CloudStream** | Kotlin / Android | 11.0k | GPL-3.0 | Kotlin 插件 + Extractor | 提取器（extractor）设计值得抄 |
| **Animeko** | Kotlin / Compose MP | 21.0k | AGPL-3.0 | 无规则，走 BT + Bangumi | 弹幕/追番/播放器体验标杆，但模型不同（BT 下载） |
| **mpvKt** | Kotlin / Compose | 1.3k | Apache-2.0 | — | libmpv 接入 Android 的最佳代码参考（已归档，只读参考） |

> 关键差异：**Kazumi 的规则是数据（JSON），Aniyomi/CloudStream 的规则是代码（Kotlin 扩展）**。
> 数据型规则可以由非程序员维护、可以在线热更新、可以一键禁用；代码型规则能力强但每次改动都要发版。**自用场景强烈建议数据型**。

### 2.2 真正的工程量在"规则 + 反爬 + 播放器"，不在 UI

一个能用的聚合播放器，UI 大概占 20% 工作量，剩下 80% 是：

- 站点结构千奇百怪：HTML 直出、前后端分离 JSON、苹果 CMS（MacCMS）的 `$$$`/`#` 分隔字符串；
- 播放页是 iframe 套 iframe，真实地址在 JS 里拼、被混淆、或走 `eval`；
- Cloudflare 质询（响应头 `cf-mitigated: challenge`）、403/429/503 验证页、图片验证码；
- m3u8 有 AES-128 加密、有广告分片、有 Referer/UA 校验；
- 字幕是字幕组 ASS 特效（Media3 支持不全）。

### 2.3 规则是消耗品，必须按"可运维资产"设计

站点改版、换域名、加防护是常态。所以第一天就要有：

- 规则带 `version` + `api level`（客户端能力版本，避免新规则把老客户端搞崩）；
- 规则来源与更新通道（远程仓库 / 镜像 / 手动导入 `kazumi://` 式 deeplink）；
- 规则自检（`测试规则` 面板：搜索→线路→剧集→播放页，逐段回显）；
- 失败可观测（哪个站点、哪一步、HTTP 状态、是否命中验证页）；
- 一键禁用 + 自动降级（这条规则坏了不影响其他规则）。

### 2.4 法律分水岭是"分发/营利"，不是"解析"

- 单纯自己写规则、自己看：民事责任风险存在（复制/传播他人作品），刑事风险低；
- **公开分发 App、接广告、做会员**：直接踩到"通过信息网络向公众传播"，已有重判（见 9.1）；
- **公开分享规则仓库**也可能被认定为帮助侵权；
- 所以"自用"要落到实处：不公开分发、不接广告、不做用户体系、不建公开规则站。

### 2.5 Android 上最脆弱的一环是 WebView 嗅探

Kazumi 官方文档明确写了：Android 的 `WebView` 在请求拦截/资源嗅探上限制多，只能用 **JavaScript 注入** 方案，稳定性弱于 Windows(WEBVIEW2)/iOS(WKWebView)。这意味着：

- 嗅探层要**可插拔**（JS 嗅探 / 直接解析 m3u8 文本 / 调用外部播放器 / 用户手动粘贴地址 四条降级路径）；
- 嗅探结果是"候选列表"而不是单一结果，要按码率、时长、是否含音轨排序，让用户能换线。

---

## 3. 目标产品功能清单（按优先级）

| 优先级 | 功能 | 说明 |
| --- | --- | --- |
| P0 | 规则管理（增删改、导入导出、更新、测试） | 没有规则 = 空壳 |
| P0 | 搜索 → 详情 → 线路/剧集 → 播放 | 主链路 |
| P0 | 播放器：HLS/MP4、倍速、手势、记忆进度、后台播放 | 体验底线 |
| P0 | 历史记录 + 追番收藏（本地 Room） | 留存 |
| P1 | 弹幕（B 站 / 弹弹play / 巴哈），屏蔽词、透明度、字号 | 动漫场景刚需 |
| P1 | 多线路切换 + 失效自动换线 | 决定"能不能看" |
| P1 | 离线下载（HLS 分片并发 + 合并） | 通勤场景 |
| P2 | 元数据：Bangumi 番剧库、时间表、评分 | 决定"好不好用" |
| P2 | 投屏（DLNA）、外部播放器、WebDAV 同步 | 加分项 |
| P3 | 超分（Anime4K shader）、一起看（SyncPlay） | 差异化，先不做 |

---

## 4. 推荐架构

```
┌──────────────────────────── Android App (Kotlin + Compose) ────────────────────────────┐
│                                                                                        │
│  UI 层      Home/Search/Detail/Player/Settings     (Compose + Navigation)              │
│              │                                                                         │
│  Domain     RuleEngine · SearchUseCase · ResolveUseCase · DanmakuUseCase · DownloadUC   │
│              │                                                                         │
│  Data       RuleRepository(JSON)   MediaRepository(Room)   MetaRepository(Bangumi)     │
│              │                          │                        │                     │
│  Infra      ┌──────────────┐   ┌──────────────────┐   ┌────────────────────┐           │
│             │ Rule Fetcher │   │  Sniffer (WebView)│   │ HTTP (OkHttp + UA/  │           │
│             │ XPath: Jsoup │   │  JS hook: fetch / │   │ Referer/Cookie/代理)│           │
│             │ JSON: kotlinx│   │  XHR / MSE / DOM  │   └────────────────────┘           │
│             └──────────────┘   └──────────────────┘                                    │
│                                        │                                               │
│  Player     ┌───────────────────────────┴───────────────────────┐                      │
│             │ Media3/ExoPlayer (默认, 硬解/HLS/AES-128)          │                      │
│             │ libmpv (备选, ASS 特效字幕/怪异编码/超分 shader)     │                      │
│             └───────────────────────────────────────────────────┘                      │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

### 4.1 主链路数据流

```
关键词
  └─► 规则.search(keyword)
        ├─ XPath 模式: GET/POST HTML ──► Jsoup 选择器 ──► [{name, sourceUrl}]
        └─ API  模式: GET/POST JSON ──► 受限 JSONPath ──► [{name, source}]
  └─► 规则.chapters(source)
        ├─ XPath: 详情/播放页 ──► 线路节点 ──► 剧集节点
        └─ API  : 详情接口 ──► roads[] ──► episodes[] / delimited 字符串
  └─► 构造播放页 URL (+ @roadIndex/@episodeIndex/@variables)
  └─► Sniffer: WebView 打开播放页
        ├─ 注入 JS：hook fetch / XHR / MediaSource / video.src / DOM 扫描 / 正则扫 m3u8|mp4
        ├─ 等待候选（超时 8~15s），按 m3u8 > mp4、码率、时长排序
        └─ 结果：{url, headers(Referer/UA/Origin/Cookie), type}
  └─► Player: Media3 (带 headers 的 DataSource) 播放；失败 → libmpv → 外部播放器
  └─► 进度写 Room + 弹幕按标题向弹幕源拉取并对时
```

### 4.2 为什么播放器要做"双内核"

| 能力 | Media3 / ExoPlayer 1.11 | libmpv (mpv-android) |
| --- | --- | --- |
| HLS / DASH | ✅ 官方扩展完善 | ✅ |
| AES-128 HLS | ✅ 原生 | ✅ |
| 硬件解码 | ✅ 系统 MediaCodec，功耗低 | ✅ 可 `hwdec=auto-copy` |
| ASS/SSA 特效字幕 | ⚠️ 支持有限（定位/样式不全，社区仍在补 `MarginL/R/V`） | ✅ libass 完整 |
| 怪异编码 / 老片源 | ⚠️ 可能失败 | ✅ ffmpeg 全格式 |
| 体积/启动 | 小、快 | 大（+10~20MB），启动略慢 |
| 超分 shader | ❌ | ✅ libplacebo（Anime4K） |

> 结论：**默认 Media3，字幕/编码/超分场景回落 libmpv**。两者都通过 `MediaSessionService` 暴露给系统（通知栏、蓝牙、车机）。

---

## 5. 规则引擎设计（核心资产）

### 5.1 直接兼容 Kazumi 的 JSON schema（强烈建议）

这不是抄，是**为了白嫖规则生态**：KazumiRules 仓库（MIT，2.3k star）已有几十条站点规则，格式长这样（节选真实字段）：

```json
{
  "api": "9", "type": "anime", "name": "TvTFun", "version": "1.0",
  "baseURL": "https://www.tvtfun.net/",
  "searchMode": "api", "chapterMode": "api",
  "useWebview": true, "useNativePlayer": true, "useLegacyParser": false,
  "adBlocker": false, "userAgent": "", "referer": "",
  "searchURL": "", "searchList": "", "searchName": "", "searchResult": "",
  "chapterRoads": "", "chapterResult": "",
  "searchApiConfig": {
    "request": {"method": "GET", "url": ".../api/videos/search", "query": {"q": "@keyword", "pageSize": 20}},
    "listPath": "$.data.videos[*]", "namePath": "$.name", "sourcePath": "$.id"
  },
  "chapterApiConfig": {
    "request": {"method": "GET", "url": ".../api/videos/@source"},
    "format": "nested | delimited",
    "roadsPath": "$.data.playSources[*]", "roadNamePath": "$.name",
    "episodesPath": "$.episodes[*]", "episodeNamePath": "$.name", "episodeUrlPath": "",
    "variables": {"slug": "$.data.slug"},
    "episodePage": {"url": ".../video/@slug/play", "query": {"source": "@roadIndex", "episode": "@episodeIndex"}}
  },
  "antiCrawlerConfig": {
    "enabled": false, "captchaType": 1,
    "captchaImage": "", "captchaInput": "", "captchaButton": "",
    "captchaDetectType": 1, "captchaDetectValue": "", "captchaScript": ""
  }
}
```

你要实现的其实就三类能力：

| 能力 | 实现要点 |
| --- | --- |
| **XPath 规则** | `searchURL`（`@keyword` 占位）→ `searchList` 选条目 → `searchName` / `searchResult` 相对条目取字段；`chapterRoads` → `chapterResult` 取线路与剧集。用 Jsoup `Selector`（它支持 XPath 子集，注意 `//div[2]` 这类序号语义，必要时用 `:nth-child` 转换） |
| **API 规则** | 受限 JSONPath：只支持 `$.a.b`、`[*]`、`[0]`、`$['play-sources']`；**明确不支持** `..` 递归、`[?()]` 过滤、切片、函数 —— 这个"故意做小"的设计能省掉一个 JSONPath 引擎 |
| **混合** | 搜索 API + 选集 XPath（`sourceTemplate` 把裸 ID 拼成详情页 URL），反之亦然 |
| **反反爬** | 检测验证页（XPath / 文本 / 正则命中，或 `cf-mitigated` 头）→ 弹出 WebView 让用户过验证 → 取 Cookie 回填 → 重试一次 |
| **表单/分隔串** | POST 表单搜索；MacCMS 的 `vod_play_from`(`$$$`) + `vod_play_url`(`#`/`$`) 直接用字符串切分 |

### 5.2 规则引擎的三个"必须有"

1. **占位符系统**：`@keyword`、`@source`、`@roadIndex`(0 起)、`@episodeIndex`(0 起)、`@roadNumber`/`@episodeNumber`(1 起)、响应变量 `@slug`。下标从 0 还是 1 搞错是新手第一大坑。
2. **测试面板**：输入关键词，逐步展示「搜索请求 URL/响应片段 → 解析出的条目 → 线路 → 剧集 → 最终播放页 URL」。**这是规则作者的生产力工具**，比任何文档都重要。
3. **能力版本（api level）**：规则声明它需要的最低客户端能力；客户端按版本决定是否加载/提示升级。否则一条新规则能让老版本闪退。

### 5.3 嗅探器设计（Android 现实约束）

```kotlin
// 在 WebViewClient.shouldInterceptRequest 不可靠的前提下，用 JS 注入 + 轮询
val hook = """
  (function(){
    const hit = (u) => /\.(m3u8|mp4)(\?|$)/i.test(u) || u.startsWith('blob:');
    const post = (url, extra) => window.__dshSniff && window.__dshSniff(JSON.stringify({url, ...extra}));
    const of = window.fetch; window.fetch = function(...a){ const u = (a[0]&&a[0].url)||a[0]; hit(u)&&post(u,{}); return of.apply(this,a); };
    const oo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(m,u){ hit(u)&&post(u,{m}); return oo.apply(this,arguments); };
    // MSE: 部分站点用 blob + MSE 播放，需要 hook SourceBuffer.appendBuffer 才能拿到分片地址
    if (window.MediaSource) { const ab = MediaSource.prototype.addSourceBuffer;
      MediaSource.prototype.addSourceBuffer = function(t){ post('MSE:'+t,{}); return ab.apply(this,arguments); }; }
  })();
""".trimIndent()
```

要点：
- **超时 + 候选排序**：8–15s 内没结果就降级；m3u8 优先于 mp4，`bandwidth` 高者优先；
- **抓 headers**：Referer 常被媒体服务器校验，必须把播放页 URL 作为 Referer 传给播放器；
- **iframe 递归**：最多跟 3 层，记录来源链便于调试；
- **安全**：JS bridge 只暴露白名单方法；规则里若允许自定义脚本（Kazumi 的 `captchaScript`），必须限制 API 面、禁用文件/网络任意访问。

---

## 6. 技术选型对比

### 6.1 语言与框架

| 方案 | 优势 | 劣势 | 适用 |
| --- | --- | --- | --- |
| **Kotlin + Compose 原生（推荐）** | 播放器/WebView/MediaSession/下载全部一等公民；性能与功耗好；可精细控制 DNS/代理/Cookie | 只能 Android；UI 代码量略大 | 目标就是 Android、要长期自维护 |
| Flutter + media_kit（Kazumi 路线） | 一套代码多端；media_kit 即 libmpv；UI 迭代快 | Android WebView 嗅探同样受限；JNI 层问题排查成本高；包体大 | 未来要 iOS/桌面 |
| React Native | 生态大 | 播放器/嗅探要写原生模块，等于双倍 | 不推荐 |

### 6.2 依赖清单（Kotlin 方案）

| 用途 | 选型 | 备注 |
| --- | --- | --- |
| UI | Compose + Material3 + Navigation | |
| 网络 | OkHttp 5 + kotlinx.serialization | 需要 per-rule 的 UA/Referer/Cookie/代理 |
| HTML 解析 | Jsoup | XPath 用 `Selector`，必要时补 `:nth-child` 转换 |
| JSONPath | 自写 40 行受限解析器 | 只支持 `$.a[*].b` |
| 播放 | Media3 1.11 + `media3-exoplayer-hls` | 版本以 2026-09 的 1.11.1 为准 |
| 备选播放 | libmpv（mpv-android 的 `libmpv.so` + JNI） | 参考 mpvKt（已归档，Apache-2.0，可读代码） |
| 弹幕渲染 | 自绘（Compose Canvas 或自定义 View） | 老库 DanmakuFlameMaster 已停更多年，不建议 |
| 存储 | Room + DataStore | 规则/历史/收藏/进度 |
| 下载 | 自研 HLS 下载器（OkHttp 并发分片 + `javax.crypto` 解 AES-128 + Media3 Transformer 封装 MP4） | **不要再依赖 ffmpeg-kit**（原项目已停更，仅有社区 maintained fork） |
| 投屏 | jUPnP（DLNA） | Chromecast 需 GMS，自用可略 |
| 后台播放 | Media3 `MediaSessionService` | |

### 6.3 元数据源对比

| 源 | 内容 | 限制 | 建议 |
| --- | --- | --- | --- |
| **Bangumi（api.bgm.tv v0）** | 中文名、放送时间表、评分、收藏同步 | 需自带 `User-Agent`，文档为社区维护 | **主源**（中文动漫场景最贴） |
| AniList GraphQL | 英文/罗马音、评分、进度同步 | 官方文档标注当前处于降级状态，限 **30 req/min**（正常 90），有 burst 限制 | 备源/国际化 |
| Jikan (MAL) | 日文/英文元数据 | 速率限制严格，非官方 | 备源 |
| TMDB | 剧集/海报/背景图 | 动漫条目质量一般，需 API Key | 只用来补图 |

> 追番同步优先级：本地 Room 为真相源 → 可选 Bangumi OAuth 同步 → 可选 WebDAV 备份（Kazumi 就是这么做的）。不要一上来做账号体系。

---

## 7. 弹幕方案

| 环节 | 方案 |
| --- | --- |
| 来源 | B 站（按标题搜索，非官方接口，注意 Cookie/风控）、弹弹play 开放平台（需申请 appId/appSecret，官方文档在 doc.dandanplay.com）、巴哈姆特 Gamer（港台番） |
| 匹配 | 用「番名 + 集数」做候选，**必须提供手动改搜索词 + 手动选集**，自动匹配永远有错 |
| 格式 | 统一转内部结构 `{timeMs, mode(滚动/顶部/底部), color, text}`；B 站 XML、弹弹play JSON、ASS 各自 adapter |
| 渲染 | 三轨道泳道 + 碰撞检测；Canvas 批量绘制；1 万条以上开"海量模式"（抽稀/合并）；支持透明度、字号、描边、区域、屏蔽词（关键词 + 正则） |
| 性能 | 只渲染 `[t-1s, t+10s]` 窗口；预解析到内存池；避免每帧 new 对象 |
| 版权注意 | 弹幕本身也是用户生成内容，自用无碍；一旦分发就别做弹幕云端 |

---

## 8. 分发与更新

- **不上 Google Play**：其开发者政策明确禁止"诱导或鼓励侵犯版权"的应用，这类 App 必然被拒/下架。
- 走 **GitHub Releases + Obtainium**（或自建更新检查接口）：签名 APK，应用内检查版本、下载、`PackageInstaller` 安装。
- 规则单独发版：规则仓库用 Git 托管，客户端定时拉 `index.json`（含 `version`、`api level`、`sha256`），支持镜像地址（国内访问 GitHub raw 不稳定）。
- 崩溃/规则失败日志：本地环形缓冲 + 导出文本（不要默认上报到服务器，自用场景没必要）。

---

## 9. 法律与合规风险（必须看）

### 9.1 已有重判

无锡市新吴法院公布的"国内最大盗链案"：2017–2023 年运营多款影视聚合 App，通过下载上传 + **盗链**大型平台播放地址向公众提供作品，传播 **8 万多部**，单月点击 4800 万次，广告费 **3.92 亿元**；主犯以**侵犯著作权罪**被判 **3 年至 5 年 6 个月**，罚金合计 **2400 万元**。
（来源：[光明网转载《扬子晚报》2025-04-24](https://m.gmw.cn/2025-04/24/content_1304022033.htm)）

### 9.2 风险分层

| 行为 | 民事 | 行政 | 刑事 |
| --- | --- | --- | --- |
| 自己写规则、自己看 | 有（未经许可使用作品） | 低 | 很低 |
| 小范围给朋友用、不收费 | 有 | 低~中 | 低（但"传播"要素已具备） |
| 公开分发 APK / 建群传播 | 高 | 中 | 中 |
| 分发 + 广告/会员/引流 | 高 | 高 | **高（判例明确）** |
| 公开规则仓库（教人解析） | 中 | 中 | 可能被认定帮助侵权 |

### 9.3 落到设计上的"合规边界"

1. **不做账号、不做支付、不接广告、不做统计上报**；
2. **不内置任何默认规则**（Kazumi 现在也是这么做的：安装包不含规则，首次启动让用户自己导入）——这一条既降风险也降维护成本；
3. 规则只存在本地，导入靠剪贴板/文件，**不提供官方规则商店**；
4. App 内放一句"本应用不提供任何内容，仅作为浏览器/播放器使用，内容来源由用户自行配置"——不是免责金牌，但能体现主观状态；
5. 如果哪天想公开做，**换模型**：只做元数据+追番（Bangumi 客户端）、或自有片库（Jellyfin/Emby 客户端）、或谈授权。这三条都能做成正经产品。

### 9.4 开源协议

- fork **Kazumi（GPL-3.0）**：只要分发就必须开源你的修改（含规则引擎），且不能用闭源插件规避；**自用不分发则无义务**。
- fork **Aniyomi（Apache-2.0）**：可闭源分发，但需保留 NOTICE。
- **Animeko 是 AGPL-3.0**：网络服务化也要开源，最严格。
- 你的自研项目：建议 **AGPL-3.0 或 GPL-3.0**，天然阻断"别人拿去商业化"。

---

## 10. 决策矩阵

| 方案 | 上手速度 | 可控性 | 长期维护 | 法律暴露面 | 综合 |
| --- | --- | --- | --- | --- | --- |
| A. 直接用 Kazumi | ★★★★★（0 天） | ★★ | 靠上游 | 低（不分发） | 想马上看片就选它 |
| B. fork Kazumi 改 | ★★★★（3–5 天） | ★★★ | 中（要跟上游合并） | 中（GPL 分发义务） | 想快速有个"自己的 App" |
| **C. Kotlin 自研 + 兼容 Kazumi 规则** | ★★（6–9 周） | ★★★★★ | 高 | 低（不分发） | **推荐：当成自己的项目做** |
| D. fork Aniyomi | ★★★ | ★★★ | 中（扩展生态不稳） | 中 | 想要"扩展 APK"生态时选 |

选 C 的理由：你需要的能力（WebView 嗅探、Media3、MediaSession、下载、DLNA）在原生 Android 上都是一等公民；而规则格式兼容能让你在第 1 周就拥有几十条可用规则，不用从零写解析。

---

## 11. 建议路线（自研，6–9 周）

### M1 · 骨架 + 规则引擎（1.5 周）
- 工程模块化：`app / core:model / core:network / core:rules / core:sniffer / feature:* / player`
- 规则模型 + JSON 解析 + XPath/JSONPath 执行器 + 单测（用 3 条 Kazumi 规则做 golden case）
- 规则管理页：导入 JSON、列表、启停、编辑
- **验收**：输入关键词，控制台打印出「条目 → 线路 → 剧集 → 播放页 URL」

### M2 · 播放闭环（1.5 周）
- WebView 嗅探器（JS hook + 超时 + 候选排序 + Referer/UA 透传）
- Media3 播放页：手势、倍速、选集、记忆进度、后台播放
- **验收**：任意一条规则，点开就能播，进度能续

### M3 · 追番与体验（1.5 周）
- Bangumi 元数据（搜索/详情/时间表）+ 本地追番/历史（Room）
- 多线路切换、失效自动换线、错误提示（"嗅探超时 / 403 / 命中验证页"）
- **验收**：能像正常 App 一样"找番 → 追番 → 接着看"

### M4 · 弹幕 + 下载（2 周）
- 弹幕拉取/解析/渲染/屏蔽词
- HLS 下载器（并发分片、AES-128、断点续传、合并 MP4）
- **验收**：地铁上能看缓存 + 有弹幕

### M5 · 加分项（按需）
- libmpv 回落（ASS 特效字幕）、DLNA 投屏、WebDAV 同步、Anime4K

> 单人全职估：M1–M4 约 6.5 周；业余每晚 2 小时约 3 个月。真正的时间黑洞是**给具体站点写规则**，不是写代码。

---

## 12. 风险清单与预案

| 风险 | 概率 | 影响 | 预案 |
| --- | --- | --- | --- |
| 站点改版导致规则集体失效 | 高（每月都有） | 高 | 规则热更新 + 一键禁用 + 内置"规则失效上报"（本地） |
| Cloudflare / 验证码 | 高 | 中 | WebView 过验证拿 Cookie；优先选有公开 JSON 接口的站点 |
| WebView 嗅探拿不到 blob/MSE 地址 | 中 | 高 | 降级到解析播放器 JS（受限 JS 引擎）、外部播放器、手动粘贴 |
| 媒体服务器校验 Referer/UA | 中 | 高 | 规则级 headers + 播放器 DataSource 注入 |
| 版权方投诉/下架 | 中 | 高 | 不分发、不建公开规则站、不接广告 |
| 上游规则仓库被删 | 中 | 中 | 本地规则可导出备份；自己维护一份私有规则集 |
| 依赖停更（ffmpeg-kit、弹幕库） | 中 | 中 | 用 Media3 Transformer 替代 ffmpeg；自绘弹幕 |
| 老设备性能（4GB RAM、HEVC 1080p） | 中 | 中 | Media3 硬解优先，低内存模式，避免 libmpv 常驻 |

---

## 13. 参考资料

**项目与文档**
- Kazumi 官网与文档（规则、视频嗅探原理、功能模块）：<https://kazumi.app/docs/rules/introduce-rules>、<https://kazumi.app/docs/architecture/video-parser>、<https://kazumi.app/docs/rules/develop-api-rules>
- Kazumi 仓库：<https://github.com/Predidit/Kazumi>；规则仓库：<https://github.com/Predidit/KazumiRules>
- Aniyomi 与 `AnimeSource` 接口：<https://extension-docs.aniyomi.org/extensions-lib/eu.kanade.tachiyomi.animesource/-anime-source/>
- CloudStream：<https://github.com/recloudstream/cloudstream>
- Animeko：<https://github.com/open-ani/animeko>
- mpvKt（libmpv + Compose 参考实现）：<https://github.com/abdallahmehiz/mpvKt>
- media_kit（Flutter 的 libmpv 封装）：<https://github.com/media-kit/media-kit>

**API**
- AniList 速率限制（当前降级 30 req/min）：<https://docs.anilist.co/guide/rate-limiting>
- Media3 / ExoPlayer 版本与 HLS：<https://github.com/androidx/media/releases>、<https://developer.android.google.cn/media/media3/exoplayer/hls>
- 弹弹play 开放平台：<https://doc.dandanplay.com/open/>

**合规**
- 盗链聚合 App 刑事判例（光明网/扬子晚报）：<https://m.gmw.cn/2025-04/24/content_1304022033.htm>
- Google Play 开发者政策（禁止鼓励侵权）：<https://support.google.com/googleplay/android-developer/answer/16543315>
