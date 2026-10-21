# 解析方案选型：规则 DSL、JS 沙箱，还是浏览器嗅探？

> 追加调研 · 面向「Android 规则解析型动漫聚合」项目
> 结论按 2026-10 的实测/文档事实给出

---

## 0. 结论先行

**不要二选一，成熟方案都是分层的。** 单一路线一定会在某类站点上撞墙：

| 层 | 方法 | 覆盖 | 角色 |
| --- | --- | --- | --- |
| L0 | 结构化 API（JSON 接口） | ~15% | 最稳，优先 |
| L1 | 声明式规则（CSS / XPath / JSONPath） | ~65% | **主干** |
| L2 | 声明式 + 内嵌表达式 / 提取器链 | +10% | **性价比最高的补丁** |
| L3 | JS 沙箱（完整脚本规则） | +7% | 签名、解密、多步请求 |
| L4 | WebView 嗅探（执行站点自己的 JS） | +3% | 播放器混淆 / MSE / Cloudflare 的兜底 |
| L5 | 原生 extractor（编译进包，不远程下发） | 特例 | 特定播放器站群 |

所以答案不是"用 JS 沙箱"，而是：

> **DSL 当语法，表达式当胶水，JS 沙箱当逃生舱，WebView 当最后一道保险。**

真正的判断标准只有三条：**这条规则谁维护、坏了怎么定位、它能不能被信任**。

---

## 1. 先把问题拆开：解析其实是三段

很多人把"解析"当成一件事，其实是三段，每段最优解不同：

| 阶段 | 要拿到什么 | 典型难点 | 最优解 |
| --- | --- | --- | --- |
| **A. 发现** | 搜索结果、番剧条目 | 站点结构、编码、分页 | DSL（XPath/CSS/JSONPath） |
| **B. 定位** | 播放线路、剧集、播放页 URL | MacCMS 的 `$$$`/`#` 分隔串、下标从 0/1、多线路 | DSL + 字符串切分 |
| **C. 取流** | 真实媒体地址（m3u8/mp4）+ 请求头 | JS 拼接、签名、AES、混淆、iframe 套娃、MSE | 表达式 → JS → 嗅探（逐级降级） |

**A、B 段用 JS 是纯浪费**；**C 段只用 DSL 又必然覆盖不全**。绝大多数架构错误，是把 C 段的复杂度倒灌进 A/B 段。

---

## 2. 五种方法全景对比

| 维度 | L1 纯 DSL | L2 DSL+表达式 | L3 JS 沙箱 | L4 WebView 嗅探 | L5 原生插件 |
| --- | --- | --- | --- | --- | --- |
| 表达力 | 低 | 中 | 高 | 最高（等于真浏览器） | 最高 |
| 安全风险 | 极低 | 低 | **中高**（可发请求=可外泄） | 中（跑第三方页面） | 高（原生权限） |
| 可热更新 | ✅ | ✅ | ✅ | ✅（规则部分） | ❌ 要发版 |
| 规则作者门槛 | 低（会 F12 即可） | 中 | 高（要会 JS + 站点逆向） | 低（不需要写规则） | 高（Kotlin） |
| 调试/定位成本 | 低（分段回显） | 中 | **高**（黑盒） | 中（可看 console/网络） | 高 |
| 稳定性 | 高 | 高 | 中 | **低**（超时、内存、机型差异） | 高 |
| 性能 | 优 | 优 | 中（每规则一个 runtime） | **差**（几百 MB、秒级启动） | 优 |
| 反爬对抗 | 弱 | 弱 | 中（可算签名/加密） | **强**（能过 JS 挑战） | 强 |
| 离线/批量 | ✅ | ✅ | ✅ | ❌ | ✅ |
| 典型代表 | Kazumi XPath/API 规则 | Legado 书源 `@js:` | TVBox JS spider、Legado 全 JS 源 | Kazumi 视频嗅探 | Aniyomi/CloudStream 扩展 |

---

## 3. L1：纯声明式规则（主干，必须做好）

### 3.1 形式

```json
{
  "searchURL": "https://site/search?wd=@keyword",
  "searchList": "//ul[@id='list']//li",
  "searchName": "//h3/a/text()",
  "searchResult": "//h3/a/@href",
  "chapterRoads": "//div[@class='play-list']",
  "chapterResult": "//ul/li/a"
}
```

### 3.2 为什么它必须是主干

- **失效可定位**：坏了就是"选择器匹配不到"，改一行即可，5 分钟修好一条规则；
- **非程序员可维护**：会按 F12 复制 XPath 就能加站点；
- **可测试**：给定 HTML 快照 → 期望输出，纯函数，单测友好；
- **零信任成本**：规则最多"抓错内容"，不会偷你的 Cookie。

### 3.3 它覆盖不了什么（要提前知道）

1. URL 里带签名/时间戳/设备指纹；
2. 接口返回加密串（AES/RSA/base64 套娃）；
3. 播放页里 `player_aaaa={"url":"..."}` 这类配置对象需要"取值 + 反转义 + 拼接"；
4. 需要多步请求（先拿 token 再请求）；
5. 播放器用 blob + MSE，页面上根本没有 m3u8 文本。

**第 3 条其实不需要 JS**：一个"JSON 字段提取 + 二次解码"的提取器就能覆盖一大片。这引出 L2。

---

## 4. L2：DSL + 表达式 / 提取器链（推荐主力）

这是最容易被低估、性价比最高的一层。核心思想：**规则的每个字段不再是"一个选择器"，而是一条"提取管道"**。

### 4.1 提取器链

```json
{
  "episodeUrl": [
    { "type": "json", "path": "$.player_aaaa.url" },
    { "type": "base64", "mode": "decode" },
    { "type": "replace", "pattern": "^//", "with": "https://" },
    { "type": "template", "value": "@baseURL@value?token=@token" }
  ]
}
```

可用的原子提取器（建议就这 10 个，够覆盖 90% 场景）：

| 类型 | 作用 | 例 |
| --- | --- | --- |
| `xpath` / `css` | HTML 取节点/属性/文本 | `//a/@href` |
| `json` | 受限 JSONPath | `$.data.list[*]` |
| `regex` | 正则取第 N 组 | `url:"(.*?)"` |
| `replace` | 正则替换 | 去广告参数 |
| `split` | 分隔符切分（MacCMS） | `$$$` / `#` / `$` |
| `decode` | base64 / url / unicode / hex | |
| `template` | 字符串拼接 + 变量替换 | `@baseURL@value` |
| `expr` | **受限表达式**（下面详述） | `@value.replace('/play/','/m3u8/')` |
| `js` | 沙箱脚本（L3，见下节） | 算签名 |
| `const` | 常量 | |

### 4.2 受限表达式（`expr`）与完整 JS 的边界

**表达式 = 单个值，无语句、无循环、无函数定义**，只允许：字符串/数字字面量、变量引用、方法调用白名单、四则运算、三元。

```js
// ✅ 表达式：把播放页地址改成接口地址
@value.replace("/play/", "/api/play/") + "?id=" + @id

// ✅ 表达式：时间戳签名（如果站点只做简单拼接）
"t=" + now() + "&k=" + md5(@id + "salt")

// ❌ 表达式不该做的事（应下沉到 JS 沙箱）
for (let i = 0; i < 10; i++) { ... }
function decrypt(s) { ... }
```

**为什么要把"表达式"单独做一层**：它覆盖了 80% 的"需要算一下"的场景，却保留了 DSL 的全部优点（可读、可测、无副作用、可静态检查）。很多人一上来就上 JS，结果所有规则都变成黑盒。

> 参考实现：Legado（阅读，47k star）的书源规则就是"字段 + `@js:` 前缀"的混合体——默认是选择器/正则，需要时一个字段切换成脚本。这个设计活了 6 年，被几千个书源验证过。

---

## 5. L3：JS 沙箱（逃生舱）

### 5.1 什么时候才用

只有这四种情况值得付出"黑盒"的代价：

1. 请求需要 **签名**（`sign=md5(ts+key)`、HMAC、RSA）；
2. 响应/媒体地址 **加密**（AES-CBC + 固定 key/iv、异或、charCode 变换）；
3. 需要 **多步会话**（先 GET 拿 token → POST 换 ticket → 再带 ticket 取流）；
4. 站点 JS 逻辑简单但纯 DSL 表达不了（复杂条件分支）。

### 5.2 引擎选型（Android 实测视角）

| 引擎 | 实现 | 包体 | 启动 | 语法 | 维护状态（2026-10） | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| **Rhino 1.9.1** | 纯 Java | 约 1.2 MB dex | 中（首次解析慢，可预编译缓存） | ES5 + 常用 ES6 子集 | ✅ mozilla 活跃，2026-02 发版 | **首选**：无需 NDK，限权最容易 |
| QuickJS (JNI) | C | 约 1 MB so | 快 | ES2020 | ⚠️ 官方 Android wrapper（seven332/quickjs-android）2023 年后停更，需自行编 quickjs-ng | 需要现代语法/高性能时再上 |
| J2V8 | V8 | 10 MB+ | 快 | 现代 | ❌ 已停更 | 不用 |
| WebView 当引擎 | Chromium | 0（系统自带） | 快 | 现代 | ✅ | **不是沙箱**：有 DOM/网络/存储，只适合"执行站点自己的 JS"（L4） |

结论：**规则内脚本用 Rhino，站点 JS 用 WebView**。两者职责不同，别混。

### 5.3 安全模型（这 8 条不做，等于把手机交出去）

远程下发的 JS = 远程代码执行。即使"沙箱"，只要给它网络，它就能把 Cookie/账号外泄。必须：

1. **默认无网络**：JS 里没有 `fetch`/`XMLHttpRequest`，只能用宿主注入的 `http.get/post`；
2. **域名白名单**：规则声明它要访问的域（`allowHosts`），宿主逐请求校验，越界直接拒绝并记录；
3. **无文件、无反射**：Rhino 用 `ClassShutter` 只放行白名单类（禁止 `java.io.*`、`java.lang.Runtime`、`Packages`）；
4. **资源限额**：`Context.observeInstructionCount` 做指令数上限 + 墙钟超时（如 10s）+ 响应体上限（如 5 MB）；
5. **Cookie 隔离**：只注入该规则域名下的 cookie jar，不给全局 CookieManager；
6. **输出结构化**：脚本只能 `return` 数据，不能触达 UI/数据库；
7. **规则签名**：规则包带 hash + 发布者签名，安装时展示"该规则将访问：a.com, cdn.a.com"；
8. **可审计**：执行日志（请求 URL、耗时、返回值摘要）落本地，出问题能复盘。

> 反面教材：有些书源/爬虫规则直接暴露 `java.*` 桥接（能读写文件、起线程、访问任意 URL），能力很强，但等于没有边界。自用可以，一旦要给别人用就必须收窄。

### 5.4 宿主 API 建议（够用就好）

| 命名空间 | 方法 | 说明 |
| --- | --- | --- |
| `http` | `get(url, {headers, timeout})` / `post(url, body, {json/form})` | 走白名单 + 规则级 UA/Referer/Cookie |
| `crypto` | `md5` / `sha1` / `sha256` / `hmacSha256` / `aesDecrypt(key, iv, data, mode)` | 纯计算，无副作用 |
| `codec` | `base64Decode/Encode` / `hex` / `urlEncode` / `unicode` | |
| `html` | `query(html, selector)` / `attr(node, name)` / `text(node)` | 脚本里也能解析 |
| `json` | `parse(str)` / `stringify(obj)` / `get(obj, path)` | |
| `util` | `now()`（可冻结用于测试）/ `log()` / `sleep()`（禁） | |
| `kv` | `get(key)` / `set(key, value)` | 规则级持久化（token 缓存） |
| `env` | `baseUrl` / `keyword` / `source` / `roadIndex` / `episodeIndex` | 上下文 |
| `output` | `return` | 返回值即结果 |

### 5.5 Kotlin 侧骨架（Rhino + 限权 + 超时）

```kotlin
class JsRuleSandbox(private val allowHosts: Set<String>) {

    private val ctx = Context.enter().apply {
        // 1. 禁掉 Java 类访问：只放行白名单
        classShutter = ClassShutter { name ->
            name in setOf("java.lang.String", "java.lang.Math") // 极窄
        }
        optimizationLevel = -1          // 解释执行，启动快、可中断
        languageVersion = Context.VERSION_ES6
    }

    fun run(script: String, env: Map<String, Any?>, timeoutMs: Long = 10_000): Any? {
        // 2. 指令数上限 + 墙钟超时（Rhino 没有内建 timeout，用 observer 计数）
        val deadline = System.currentTimeMillis() + timeoutMs
        ctx.instructionObserverThreshold = 10_000
        ctx.setInstructionObserverCallback {
            if (System.currentTimeMillis() > deadline) throw JsTimeout()
        }

        val scope = ctx.initStandardObjects()
        // 3. 只注入受控 API，绝不注入 Packages/java
        ScriptableObject.putProperty(scope, "http", HostHttp(allowHosts))
        ScriptableObject.putProperty(scope, "crypto", HostCrypto())
        ScriptableObject.putProperty(scope, "env", env.toScriptable())

        return ctx.evaluateString(scope, "(function(){ $script })()", "rule", 1, null)
    }
}
```

跑在独立线程 + 可取消（`Future.cancel` 不行，得靠 observer 抛异常），必要时放 `:sandbox` 独立进程（`android:process`），崩了不拖垮主进程。

### 5.6 一条带签名的规则长什么样

```json
{
  "episodeUrl": [
    { "type": "js", "code": "const ts = now(); const sign = md5('ts=' + ts + '&k=abc'); return env.playPage + '?ts=' + ts + '&sign=' + sign;" },
    { "type": "http", "method": "GET" },
    { "type": "json", "path": "$.data.url" }
  ]
}
```

**注意**：脚本只负责"算出 URL"，真正的请求仍走宿主（可审计、可重试、可缓存）。让脚本自己发请求是常见的越权口子。

---

## 6. L4：WebView 嗅探（兜底层）

### 6.1 定位

不是"解析方案"，而是"**让站点自己把答案算出来，我们偷听**"。适用于：播放器用 blob/MSE、地址在混淆 JS 里、页面有 Cloudflare 挑战。

### 6.2 实现要点（Android）

```kotlin
// 注：shouldInterceptRequest 对 XHR/fetch/blob 不可靠，主流做法是 JS 注入 + 回调
webView.evaluateJavascript("""
(function(){
  const hit = u => /\.(m3u8|mp4)(\?|$)/i.test(u);
  const post = (u, extra) => window.__sniff(JSON.stringify({url:u, ...extra}));
  const of = window.fetch;
  window.fetch = function(...a){ const u=(a[0]&&a[0].url)||a[0]; hit(u)&&post(u,{}); return of.apply(this,a); };
  const oo = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(m,u){ hit(u)&&post(u,{m}); return oo.apply(this,arguments); };
})();
""", null)
```

- **超时 8–15s**，拿不到就降级；
- **候选排序**：m3u8 > mp4；`#EXT-X-STREAM-INF` 里带宽高的优先；
- **必须带 Referer**：把播放页 URL 作为 Referer 传给播放器，否则 403；
- **iframe 递归最多 3 层**，记录来源链；
- **用完即毁**：`destroy()` + 清缓存，别常驻（Kazumi 也是"按需创建、用完销毁"）。

### 6.3 代价（为什么只能当兜底）

- 冷启动 1–3 秒，内存 100–300 MB；
- 站点改版/加防护就失效，且**失效原因不可读**（你只能看到"没抓到"）；
- 无法离线批量、无法在后台跑；
- 机型/WebView 版本差异大（国产 ROM 尤其）。

---

## 7. L5：原生 extractor（特例层）

当某个播放器站群（几十个站共用一套播放器）用 JS 也搞不定时，写一个 Kotlin extractor：`interface Extractor { fun match(url: String): Boolean; fun resolve(url: String, page: String): String }`。

- 参考 yt-dlp 的 extractor 注册表思路（每个站点一个类，按 URL 正则匹配）；
- **编译进 APK，不做远程下发**（原生代码没有沙箱，远程下发等于后门）；
- 数量要克制，超过 10 个就说明前面几层设计有问题。

---

## 8. 组合建议：决策树

```
站点
 ├─ 有公开 JSON 接口？ ──是──► L0 API 规则（最稳，优先）
 │
 ├─ HTML 直出列表/详情？ ──是──► L1 XPath/CSS 规则
 │        │
 │        └─ 媒体地址需要计算/解码？ ──是──► L2 提取器链（decode/replace/template）
 │                   │
 │                   └─ 需要签名/加密/多步？ ──是──► L3 JS 脚本字段
 │
 └─ 播放页是 iframe/混淆/MSE/CF？ ──是──► L4 WebView 嗅探（带超时降级）
            │
            └─ 站群共用播放器且嗅探不稳？ ──► L5 原生 extractor
```

**关键工程约束**：每一层都要有**明确的失败信号**（超时、状态码、选择器命中 0、验证页特征），失败才降级；不能"试到最后才报错"。

---

## 9. 现实覆盖率（动漫站）

| 站点类型 | 占比（经验值） | 所需层级 |
| --- | --- | --- |
| 苹果 CMS（MacCMS）系，`vod_play_url` 直给 m3u8 | ~55–65% | L1 + `split` 提取器 |
| 前后端分离，JSON 接口 | ~15% | L0 |
| 自研前端 + DPlayer/ArtPlayer + 接口返回 m3u8 | ~10% | L1 + L2 |
| 接口加密 / 签名 / 多步换票 | ~7% | L3 |
| blob/MSE/混淆播放器 + Cloudflare | ~3% | L4 |
| 站群/特殊播放器 | ~2% | L5 |

**所以先做 L0+L1+L2 就能覆盖 ~85%**，这是 MVP 的正确范围；L3 留接口，L4 做兜底，L5 最后再说。

---

## 10. 一条规则的"进化"示例

同一个站点，随着对抗升级，规则长这样（也说明为什么分层是对的）：

```jsonc
// 第 1 天：纯 DSL
{ "episodeUrl": [{ "type": "xpath", "value": "//a[@id='play']/@href" }] }

// 第 30 天：站点把地址放进了 JS 变量
{ "episodeUrl": [
    { "type": "regex", "pattern": "player_aaaa=(\\{.*?\\})", "group": 1 },
    { "type": "json", "path": "$.url" }
] }

// 第 60 天：地址被 base64 + 反转义
{ "episodeUrl": [
    { "type": "regex", "pattern": "data-url=\"(.*?)\"", "group": 1 },
    { "type": "base64", "mode": "decode" },
    { "type": "replace", "pattern": "\\\\/", "with": "/" }
] }

// 第 90 天：接口要签名
{ "episodeUrl": [
    { "type": "js", "code": "return '/api/v2/play?id=' + env.id + '&t=' + now() + '&s=' + md5(env.id + now() + 'k3y');" },
    { "type": "http" },
    { "type": "json", "path": "$.data.playUrl" }
] }

// 第 120 天：播放器上 MSE + Cloudflare
{ "episodeUrl": [{ "type": "sniff", "url": "@playPage", "timeoutMs": 12000, "prefer": "hls" }] }
```

**规则格式从第一天就要为"混合"设计**：字段值既可以是字符串（简写），也可以是提取器数组（完整），否则后期改不动。

---

## 11. 常见坑

| 坑 | 后果 | 对策 |
| --- | --- | --- |
| 一开始就做通用 JS 规则引擎（TVBox 路线） | 规则作者只有你自己，调试黑盒，生态起不来 | 先 DSL，JS 只做字段级 |
| 全用 WebView 嗅探 | 慢、耗电、批量不可用、失效不可读 | 只当兜底 + 明确超时 |
| 把反爬逻辑写进每条规则 | 规则体积爆炸、维护地狱 | 抽成公共 preflight：CF 检测、Cookie 池、UA/Referer 默认值 |
| 规则无版本、无签名、无测试 | 一条坏规则拖垮整个 App | `api level` + hash + gold case 快照测试 |
| JS 引擎开 `Packages`/反射 | 任意代码执行、Cookie 外泄 | ClassShutter + 域名白名单 + 只走宿主 http |
| 下标 0/1 混用 | 选集错位（"点第 2 集放第 1 集"） | 规则里显式区分 `@roadIndex`(0起) 与 `@roadNumber`(1起) |
| 提取器无长度上限 | 一个 50 MB 页面把 JS 撑爆 | 响应体截断 + 流式解析 |

---

## 12. 参考

- Kazumi 规则与嗅探原理：<https://kazumi.app/docs/rules/introduce-rules>、<https://kazumi.app/docs/architecture/video-parser>、<https://kazumi.app/docs/rules/develop-api-rules>
- Kazumi 规则仓库（XPath/API 混合的真实样例）：<https://github.com/Predidit/KazumiRules>
- Legado（阅读）书源：字段 + 内嵌脚本的长期验证：<https://github.com/gedoor/legado>
- TVBox JS 爬虫（脚本型规则的真实复杂度）：<https://github.com/jadehh/TVSpider>
- Rhino（1.9.1，2026-02）：<https://github.com/mozilla/rhino>
- quickjs-android（已停更，仅作参考）：<https://github.com/seven332/quickjs-android>
- yt-dlp extractor 体系（原生提取器的工业级参考）：<https://github.com/yt-dlp/yt-dlp>
