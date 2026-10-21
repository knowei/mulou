# M2：嗅探与播放链路

## 1. 为什么嗅探要单独一层

规则只负责把客户端带到「播放页」——实测 86 条 Kazumi 规则里，**没有任何一条包含 m3u8/mp4 字面量**。真实地址是页面加载后由站点自己的 JS 算出来的，可能来自：

- `fetch` / `XHR` 接口返回；
- 直接写进 `<video src>` / `<source src>`（常常是动态插入）；
- `MediaSource.addSourceBuffer`（blob 方案，页面里根本不存在可下载的 URL）。

所以嗅探器的职责被刻意压到最小：**只上报 URL，不做判断**。判断（分类、去噪、排序、探测）全部放在 `core:sniff` 的纯逻辑里，可以在 JVM 上测，不依赖 WebView。

## 2. 注入脚本 hook 的五处

| 位置 | 拿到什么 | 备注 |
| --- | --- | --- |
| `window.fetch` | 请求 URL | 覆盖绝大多数前后端分离站点 |
| `XMLHttpRequest.open` | 请求 URL | 老式播放器和 jQuery 系 |
| `Node.appendChild` | `<video>`/`<source>` 的 src | 动态插入的播放器 |
| `MediaSource.addSourceBuffer` | `mse:<mime>` 标记 | 拿不到原始地址，只能标记 |
| 1s 轮询 `video, source` | 已有 DOM 的 src | 兜底，覆盖脚本没 hook 到的情况 |

用 `window.__dmHooked` 去重，避免重复注入导致 `fetch` 被包多层。

## 3. 候选策略（`core:sniff`）

**噪声过滤**（`MediaUrls.isNoise`）：
- 分片 `.ts` / `.m4s` / `.aac` / 字幕 / 图片 —— 我们要的是播放入口，不是分片；
- 广告与统计域名（doubleclick、googlesyndication、`/ads/`、analytics…）。

**排序**：HLS(100) > DASH(80) > MP4(60) > blob(30) > unknown(0)。

**停止条件**：抓到第一条 HLS 就认为够用（`stopOnFirstHls`）。广告 m3u8 极少见，先用这个简单策略；如果误判，把它改成"等 3 秒取最高分"即可，策略在 `SniffPolicy` 里。

## 4. m3u8 探测

嗅探到的 `.m3u8` 可能是 master playlist——里面是一堆变体。这里的做法在真机实测后改过一次：

**正确做法：保持 master 原样交给播放器。** ExoPlayer 的 HLS 实现自带 ABR，会从低码率起播再逐步升档；预先替它选"最高码率"等于关掉自适应。

**踩过的坑**：最初实现按 `BANDWIDTH` 选最高变体（FHD），模拟器上 4 秒就 `BUFFERING`。改成传 master 后连续播放 35 秒以上无中断。这条经验对低端机同样成立。

变体列表仍然从 playlist 里解析出来并返回，留给后续「手动选清晰度」用；需要强制档位时把 `SniffResolver(pickHighestVariant = true)` 打开。

其余边界：

- media playlist → 原样播放，识别 `#EXT-X-KEY:METHOD=AES-128` 并标记；
- 探测失败/不是 playlist → **回退原 URL**，让播放器自己的容错接手。探测只是优化，不能成为新的失败点。

## 5. MSE 的三种出路（当前选第 3 种）

页面用 blob + MSE 播放时，嗅探拿不到可下载的 URL。可选方案：

1. hook `SourceBuffer.appendBuffer` 拿分片，自己拼流——**工作量大且容易踩到加密**；
2. 用 CDP/Playwright 抓网络层——Android 上要跑 headless Chromium，不可接受；
3. **标记 `hasMseOnly`，UI 提示"该站点使用 MSE 播放，暂不支持"**——当前实现。

第 3 种不优雅，但诚实：与其做一个半残的拼接器，不如先让用户知道是哪种失败。

## 6. 真机实测记录（MuMu 12 / Android 14 / WebView 110）

用公开 HLS 测试流（Big Buck Bunny）跑通，实测暴露了两个必须修的问题：

**① 注入时机太晚，抓不到首个请求**

宿主机起一个测试页，页面加载时立刻 `fetch` 一次 m3u8。只注入 `onPageFinished` 时**一个候选都没有**——因为页面的请求早就发完了。

修复：三管齐下
- `onPageStarted` 提前注入一次（此时文档会被重建，所以 `onPageFinished` 还要再来一次）；
- `shouldInterceptRequest` 兜住早期请求（只上报不拦截，主文档/favicon 等非媒体 URL 直接过滤）。

实测日志正好体现了这个价值：

```
38.007  嗅探到 HLS: ...x36xhzz.m3u8?xhr=1   ← 网络层抢在页面完成前抓到
38.125  页面加载完成                          ← JS 注入这时才生效
```

**② 预选最高码率，反而播不动**

见第 4 节。修复后日志从 `..._fhd_7.m3u8` 变为 master `x36xhzz.m3u8`。

**③ 又一个只有设备能发现的问题：Java 10 / API 33 的 API**

用户在搜索时直接崩：`NoSuchMethodError: URLEncoder.encode(String, Charset)`。
这个重载是 Java 10 / Android API 33 才有的，模拟器是 API 32（Android 12L）；
而单测跑在 JDK 21 上，永远测不出来。

修法是全部改回 `URLEncoder.encode(text, "UTF-8")` 老重载（`URLDecoder` 同理）。
并加了一个 `AndroidApiGuardTest`：静态扫描 core 模块的 main 源码，禁止
`URLEncoder/URLDecoder + Charset`、`readAllBytes`、`List.of/Map.of/Set.of`、
`String.strip*` 这些 API 33+ / Java 9+ 的用法。

> 长期正解是把 core 模块改成 Android library，让 Lint 的 NewApi 检查覆盖它们；
> 在那之前用这个测试顶着。

顺带修了另一个：规则文件用的是美化过多行 JSON，而 `loadRules` 按 NDJSON 逐行
解析，导致规则"导入成功但重启后消失"。现在两种格式都认。

**④ 卡顿归因：网络，不是链路**

用本地 HLS（`tools/mock-site`，ffmpeg 生成 640x360 Main profile）连播 40 秒无
`BUFFERING`；换回 `test-streams.mux.dev` 则在 7–12 秒卡住、约 18 秒后恢复。
中间试过把视频限制到 720p/2.5Mbps，**无效**，已撤销——不该为了网络问题无端降画质。

顺带验证了错误路径：故意生成一个 profile 异常（`avc1.F4001E`）的流，
播放器报 `ERROR_CODE_DECODING_FAILED`，UI 与日志都能明确展示，没有崩。

**⑤ 可复现的端到端回归**

`tools/mock-site/` 提供本地 mock 站（搜索页 + 详情页 + 播放页 + 规则 + 生成脚本），
不接触任何真实站点就能跑通「搜索 → 选集 → 嗅探 → 播放」。用法见该目录 README。

**结论验证**

本地 HLS（640x360 Main profile，40 秒）：

```
开始播放: http://127.0.0.1:8000/local.m3u8
播放器状态: READY
开始渲染画面
```

连续播放 40 秒无 `BUFFERING`。同样的链路换成跨境流（`test-streams.mux.dev`，FHD ABR）
也能起播，但 7–12 秒处会卡一次、约 18 秒后恢复——属于网络抖动。

帧差验证（间隔 4 秒两张截图，视频区域）：平均差异 **39.9**、**35.6%** 像素变化、
亮度 236 → 198 —— 确实在放画面，不是只有音频。logcat 无 codec/播放器错误。

## 7. 已知限制

- **已在 MuMu 12（Android 12L / API 32 / WebView 110）验证**：规则 → 搜索 → 选集 → 嗅探 → 起播 → 渲染全链路，55 个单测通过。尚未在真机与低端设备上验证。
- WebView 用 2dp 透明视图挂在窗口上（不挂窗口 JS 定时器会被节流），部分 ROM 可能仍限制后台执行。
- 规则需要用户自己导入（剪贴板/JSON），应用不内置真实站点规则。
- 播放器只有 Media3/ExoPlayer 单内核；libmpv 回落、ASS 字幕还没做。

## 8. 真机冒烟清单

1. 装 APK → 规则页 → 从剪贴板导入一条 MacCMS 规则；
2. 搜索番名 → 应出现条目（观察"搜索「x」：n 条"日志）；
3. 点条目 → 应出现线路与剧集；
4. 点剧集 → 播放页应是"正在嗅探"，日志里出现候选地址；
5. 出现 `嗅探到 HLS` 后应自动起播；失败时看日志停在哪一步（加载失败 / 只有 MSE / 探测失败）。

> 没有规则时可以用调试入口单独验证嗅探：
> `adb shell am start -n com.dmanime.app/.MainActivity --es play_url "<播放页 URL>"`
