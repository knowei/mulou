# 番仓（FanCang）

Android 端动漫聚合客户端。当前进度：**M1（规则引擎）+ M2（嗅探与播放）**，可解析 Kazumi 格式规则、抓取搜索/线路/剧集、嗅探 m3u8 并用 Media3 播放。

> 内容来源由用户自行配置，应用不内置任何规则。仅供个人学习研究。

## 模块

```
app/                 Android 客户端（Compose + Media3 + WebView 嗅探）
core/rules/          纯 JVM 规则引擎：schema、受限 JSONPath、XPath、两条抓取链路
core/sniff/          纯 JVM 嗅探逻辑：候选分类/去噪/排序、m3u8 解析、JS 注入脚本
core/catalog/        纯 JVM 番剧索引：Bangumi 日历接口与模型
tools/rule-cli/      规则自检 / 搜索 / 健康巡检命令行
tools/mock-site/     本地 mock 站 + 生成脚本，用于端到端回归（不碰真实站点）
docs/                调研、方案与设计说明
```

`core/*` 不依赖 Android，所以能在 JVM 上跑单测；Android 侧只做很薄的胶水（WebView、播放器、UI）。

> 注意：核心模块是纯 JVM 库，JDK 上能跑的 API 未必在 Android 上存在
> （比如 `URLEncoder.encode(String, Charset)` 需要 API 33）。`AndroidApiGuardTest`
> 会静态扫描这类用法，防止「单测全绿、真机崩溃」。

## 构建与测试

```bash
gradle :core:rules:test :core:sniff:test :core:catalog:test   # 88 个测试
gradle :app:assembleDebug                      # 产出 app/build/outputs/apk/debug/app-debug.apk
gradle :tools:rule-cli:run --args="<规则目录> [报告文件]"
```

`./gradlew` 首次需要联网下载 Gradle 发行包；网络受限时用本机 Gradle 执行同名任务。

## 当前能力

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| Kazumi 规则解析 | ✅ | 86 条真实规则全部解析通过 |
| XPath 抓取 | ✅ | `self::`、`following-sibling::`、并集 `\|`、`text()` |
| JSON API 抓取 | ✅ | 受限 JSONPath、`sourceTemplate`、`@` 模板变量 |
| 苹果 CMS `delimited` | ✅ | `$$$` / `#` / `$` 三段切分 |
| 规则自检 | ✅ | CLI + 测试，必填字段与语法 |
| 人机验证 | ✅ | 403 挑战/验证码站点：WebView 过一次 → 存 cf_clearance/会话 Cookie → 规则请求自动带上并重试 |
| Kazumi Hub CSS1 | ✅ | 粘贴 `css1.json` 即转换为原生 XPath 规则（实测 37/37，10 条当场可完整解析） |
| 并发搜索 | ✅ | 4 路并发，17 个源约 7 秒；按源分组、不去重、失败原地显示原因 |
| 番剧索引（P0） | ✅ | 底部三 Tab；首页「今日更新 + 本季新番」来自 Bangumi 日历，缓存优先可离线渲染；索引地址可配、支持本地导入 |
| 详情页 | ✅ | 索引富信息 + 追番 + 播放 + 「选集」弹层（点某集直接进播放）；不放片源列表 |
| 追番 / 继续观看 | ✅ | 追番落盘；播放器每 5 秒回报进度，首页卡片带进度条，点一下恢复到该集 |
| 搜索 | ✅ | 只做番剧搜索：本地索引 + 在线 Bangumi（400ms 防抖、封面、历史）；点结果进详情。原始搜源移到「我的 → 高级」 |
| 播放页 | ✅ | 控制层+手势+横屏全屏、倍速列表；源面板（可用/失败分组、线路展开、403 验证）；自动连播、记忆源、下拉刷新 |
| 播放续播 | ✅ | 起播位置交给 `setMediaItem(item, startMs)`；<15 秒或 >95% 视为误点/看完，从 0 开始 |
| WebView 嗅探 | ✅ | hook fetch/XHR/DOM/MSE + 请求拦截兜底 |
| m3u8 探测 | ✅ | 识别 master/media、AES-128 标记、失败回退；默认保留 master 交给播放器做 ABR |
| Media3 播放 | ✅ | 带 Referer/UA 的 HLS，已在 MuMu 12 验证起播与渲染 |
| libmpv 回落 / ASS 字幕 | ⛔ | 下一步 |
| 弹幕 / 下载 | ⛔ | 后续里程碑 |

## 关键设计

**规则格式是 Kazumi 的超集**：Kazumi 字段原样保留，自定义能力放 `x-` 前缀，导出时剥掉即可回退成合法 Kazumi 规则。

几条必须照抄、不能"顺手优化"的语义：

1. `muliSources` 的拼写是错的（multi → muli），改了就不兼容。
2. `@roadIndex` / `@episodeIndex` 从 0 开始，`@roadNumber` / `@episodeNumber` 从 1 开始。
3. `sourceTemplate` 用朴素替换（`/GV@source/` 可用）；URL/body 模板用带负向后瞻的正则（避免误伤 `a@b.com`）。
4. `//` 在条目上下文要相对化成 `.//`——74/86 条真实规则这么写，按 XPath 1.0 字面语义会命中文档第一个节点。
5. `delimited` 的字段分隔符叫 `fieldSeparator`。

**CSS1 兼容靠翻译，不靠第二个执行器**：CSS 选择器在导入时翻译成 XPath（`core/rules/css1/`），只覆盖 CSS1 实际用到的子集（tag / class / id / 属性 / 后代 / 子元素 / 并集 / `:nth-child`），遇到 `:has()` 这类直接报错跳过，不静默降级。CSS1 里「标题与链接是两组平行节点」的写法用 `x-searchNameSelector` / `x-roadNameSelector` 按索引对齐；`chapterRoads` 为空表示整页一条线路（`no-channel`）。这三个都是 `x-` 扩展，导出给 Kazumi 时剥掉即可。

## 规则从哪来

**应用不内置任何规则。** 原因有两个：规则平均 57 天就失效一半（见 `docs/rule-sources-analysis.md`），内置等于出厂即过期；另外这也决定了应用的性质——它只是浏览器/播放器，内容来源由用户配置。

四条导入路径：

1. **导入规则源**：从公开仓库拉 `index.json`（只含未标 `deprecated` 的规则），逐条下载并解析。内置三个镜像按顺序重试（jsDelivr ×2、raw.githubusercontent），国内网络下通常第一个可用。
2. **从剪贴板导入**：粘贴单条规则 JSON 或 JSON 数组。
3. **示例**：一条结构完整但没有真实站点的规则，用来验证 UI 与解析链路。
4. **粘贴 CSS1 规则包**：整份 `css1.json`（Kazumi Hub 的 `web-selector` 格式）直接粘进「我的 → 从剪贴板」，导入时把 CSS 选择器翻译成 XPath。播放地址仍由嗅探拿，不需要移植它的 `matchVideo`。

规则导入后落盘到 `filesDir/rules.ndjson`，重启仍在；仓库里标了 `deprecated` 的规则不会被导入。

## 索引从哪来

索引（番剧元数据）默认取 Bangumi 的 `GET /calendar`，策略是**缓存优先**：先读本地缓存渲染，再后台刷新；刷新失败不清空缓存。

因为 `api.bgm.tv` 在部分网络下会被 DNS 污染，Mine 页提供了三个逃生口：

- **索引地址**：填任意可达的镜像/反代（例如用 Cloudflare 映射出来的域名）。填到域名即可，应用自动拼 `/calendar`；已带 `/calendar` 也能识别。
- **封面地址前缀**：图片在 `lain.bgm.tv`，跟 API 不是一个域名。填 `http://<反代>/img` 后，所有封面会改走这个前缀。
- **测试连接**：只探测不写缓存，直接告诉你成败、耗时、条目数和实际请求的 URL——用来分辨「域名解析不了 / 超时 / HTTP 4xx / 返回不是 JSON」。
- **从剪贴板导入索引 JSON**：直接贴一份与原接口同结构的 JSON，绕开网络落盘。

参考反代见 `tools/bgm-proxy/`（阿里云 FC，单文件）：`/img/*` → `lain.bgm.tv`，其余路径 → `api.bgm.tv`，**新增任何 Bangumi 接口都不需要重新部署**，只有再加新域名才改一次。

> 列表封面用 `common` 尺寸而不是 `large`：原图常见 200 KB～2 MB，一屏十几张会把单实例并发的反代打爆。
>
> **条件请求必须剥掉**：图片缓存会带 `If-None-Match`，反代若原样转给上游，上游回 304 → 函数把 304 带 body 返回 → 最终变成 **HTTP 400**，表现为"封面全灰"。`tools/bgm-proxy/index.js` 和 App 的 OkHttp 拦截器都做了剥离。
>
> 另：`*.vercel.app` 这类通配域名在国内常被整段污染（实测解析到 Facebook 网段），反代部署在 Vercel 时建议绑自定义域名。

## 已知限制

- **验证范围**：已在 MuMu 12（Android 12L / API 32 / WebView 110）跑通「规则 → 搜索 → 选集 → 嗅探 → 起播 → 渲染」，以及「索引首页 → 详情 → 找片源」；尚未在真机、低端设备、以及真实站点规则上验证。
- **Bangumi 在当前网络下不可达**（宿主机也时通时不通）：`lain.bgm.tv` 的 AAAA 被解析到 Facebook 网段（`2a03:2880:…`，典型 DNS 污染），A 记录连接超时。**已通过自建反代解决**（见 `tools/bgm-proxy/`），索引与封面均走反代；不配反代时首页退化为缓存渲染 + 错误提示。
- 索引目前只有 P0 数据（当日 + 当季），没有全文搜索、没有追番进度。
- 2dp 透明 WebView 在部分 ROM 上可能被节流。
- MSE（blob）页面暂时只能识别并提示，不做分片拼接。
- 规则需用户自行导入，应用不内置真实站点规则。
- CSS1 规则只迁移「搜索 + 选集」；`matchVideo` / `cookies` / `defaultResolution` / 字幕与线路名过滤不迁移（播放地址由嗅探拿，线路名可能带多余后缀）。
- 调试入口：`adb shell am start -n com.dmanime.app/.MainActivity --es play_url "<播放页 URL>"` 可跳过规则直接验证嗅探；`--es search "<关键词>" --es rule "<规则名>"` 可直接跑一条规则；`--es rules_json_file "<filesDir 下的文件名>"` 可导入规则或整份 CSS1 规则包（省去在模拟器里粘贴大段剪贴板）。

## 文档

- [调研报告](docs/anime-app-research.md)
- [解析方案选型](docs/extraction-strategies.md)
- [真实规则源分析](docs/rule-sources-analysis.md)
- [搜索可用性实测](docs/search-verification.md)
- [P0 UI 结构与线框](docs/ui-p0.md)
- [规则健康巡检（2026-10-18）](docs/rule-health-2026-10-18.md)
- [番剧索引设计](docs/catalog-design.md)
- [M2 嗅探设计](docs/m2-sniffing.md)

## 下一步

1. **番剧索引（P0）**：本季新番 + 周时间表（Bangumi 同步 + 离线缓存），点进去复用现有搜索 —— 见 [索引设计](docs/catalog-design.md)
2. lipmpv 回落 + ASS 字幕
3. 追番 / 继续观看（依赖索引层）

## 构建正式包

```bash
# 签名密钥（已生成，务必自己备份，丢了就没法覆盖安装）
#   app/release.jks   alias=fancang   storepass=fancang2026
gradle :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

正式包与调试包**签名不同，切换时必须先卸载**（`adb uninstall com.dmanime.app`），否则会提示签名冲突。

图标：`res/drawable/ic_launcher_foreground.xml` + `ic_launcher_background.xml`（自适应图标，紫色渐变 + 仓库盒 + 播放键）。
名称：`res/values/strings.xml` 里的 `app_name`。

## 集数状态（防"播错集"）

播放前会先对账，**不会再悄悄播上一集**：

| 状态 | 判据 | 表现 |
|---|---|---|
| 可播 | 该源线路里有这一集 | 正常播放 |
| 源未更新 | 官方已播，源最多到第 N 集 | 选集里灰显，点开弹卡片：先看第 N 集 / 换源 / 提醒我 |
| 还没播出 | 官方进度都不够 | 更淡的"幽灵格"，点开提示还没播 |

配套：「提醒我」把 (番, 集, 源) 记进 `files/alerts.json`，WorkManager 每 30 分钟去该站点探一次分集，
出现目标集数就发通知，点通知直达播放（`--es alert_id`）。
## 源体检

「我的 → 高级 → 源体检」：对全部规则跑一遍，输出可用 / 空 / 失败清单与耗时，可导出文本。

- **浅检**：只搜索，判断站点是否活着（51 个源约 40 秒）；
- **深检**：再取一次线路，只有真拿到可播放线路才算可用（时间翻倍，结果可信）；
- 导出走系统分享，方便贴出来或存档。

实测（2026-10，`frieren`，51 条规则）：浅检可用 15，深检可用 **14**。
## 调试入口

`--es play_url`、`--es verify_rule`、`--es rules_json_file` 这类 intent extra **只在 debug 包里生效**（`BuildConfig.DEBUG` 门控），正式包收到也当没看见。

注意：通知点击用的 `alert_id` 是正式功能，不受影响。
## 主题

「我的 → 外观」三选一：**浅色 / 暗色 / 跟随系统**，存 `shared_prefs/ui.xml`，切换即时生效，状态栏图标颜色同步。

角色立绘容器固定白色（Bangumi 的角色图是白底 JPEG），浅色下与页面融为一体，暗色下像相片卡。
## 季度番剧表

首页「本季新番」右侧的「季度表 ›」进入：**◀ 2026 秋番 ▶** 可前后翻季度。

数据来自 `POST /v0/search/subjects` 的 `air_date` 区间过滤（关键字留空），
能拿到**整季全量**（含已完结、未开播），带中文名 / 首播日 / 集数 / 封面。

- 实测：2026 秋番 145 部、夏番 249 部；
- 服务端**每页最多 20 条**（limit 传 50 也只回 20），客户端按 offset 翻页，滚到底自动加载；
- Bangumi 的 search 不支持按日期排序（sort 只有 match/heat/rank/score），所以首播日排序在客户端做。