# 本地 Mock 站（端到端回归）

不需要任何真实站点，就能在设备/模拟器上验证完整链路：

```
规则 → XPath 搜索 → 选集 → WebView 嗅探 → m3u8 解析 → Media3 播放
```

## 为什么需要它

真实站点会带来三类不可控因素：可能失效、可能触发验证码、可能涉及版权。这套 mock 站把「代码问题」和「站点问题」彻底分开——如果这里跑不通，就一定是代码的问题。

## 前置

- `ffmpeg`（生成测试流）
- `python`（起静态服务器）
- `adb` 已连上设备

## 步骤

```powershell
# 1. 生成 40 秒本地 HLS（640x360，Main profile）
pwsh tools/mock-site/gen-hls.ps1

# 2. 起静态服务器（另开一个终端）
python -m http.server 8000 --bind 127.0.0.1 --directory tools/mock-site/www

# 3. 把设备端口反连到宿主机（绕开防火墙）
adb reverse tcp:8000 tcp:8000

# 4. 安装 APK 并导入规则
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb push tools/mock-site/rule.json /data/local/tmp/rule.json
adb shell run-as com.dmanime.app cp /data/local/tmp/rule.json files/rules.ndjson

# 5. 启动后操作：选「本地 Mock 站」→ 搜索任意词 → 选集 → 播放
```

## 期望日志

```bash
adb logcat -s dmanime:D
```

```
搜索「x」：2 条
「作品A」：2 条线路
打开播放页: http://127.0.0.1:8000/play.html?ep=1
加载播放页: http://127.0.0.1:8000/play.html?ep=1
嗅探到 HLS: http://127.0.0.1:8000/local.m3u8
开始播放: http://127.0.0.1:8000/local.m3u8
播放器状态: READY
开始渲染画面
```

只验嗅探、不经过规则时，可以用调试入口：

```bash
adb shell am start -n com.dmanime.app/.MainActivity --es play_url "http://127.0.0.1:8000/play.html?ep=1"
```

## 已知结论

- 本地 HLS 连续播放 40 秒无 `BUFFERING`；
- 换回跨境测试流（`test-streams.mux.dev`）会在 7–12 秒出现 `BUFFERING`，约 18 秒后恢复——**是网络抖动，不是播放链路**。曾尝试用 720p 上限规避，无效，已撤销该限制。
