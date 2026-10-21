# Bangumi 反代（Cloudflare Worker / 阿里云 FC）

App 的索引默认取 `https://api.bgm.tv/calendar`。该域名在国内常见被污染（实测解析到 Facebook 网段），
所以需要一个可达的反代。

两个版本，功能一致：

| 文件 | 平台 | 说明 |
| --- | --- | --- |
| `worker.js` | **Cloudflare Workers（推荐）** | 免费 10 万请求/天，不需要充值，绑自定义域名即可 |
| `index.js` | 阿里云函数计算 FC | 需要账号余额，欠费会停服（实测踩过） |

路由都是：

```
/img/*  → https://lain.bgm.tv/*
其它     → https://api.bgm.tv/*
```

因为是前缀透传，新增任何 Bangumi 接口都不需要重新部署，只有再加新域名才改一次。

## 为什么需要这个文件

原函数在 FC 上的实际行为：**任何路径都被当成 `/`**，于是永远请求 `https://api.bgm.tv/`，
返回 Bangumi 的根路径 404（47 字节的 `{"request":"/","code":404,...}`）。

根因是 FC 把 event 传成了 **Buffer**（实测 580 字节，`Object.keys(event)` 是 `0..579`），
`typeof event === 'string'` 判断不成立，`event.rawPath` 自然是 `undefined`。

`index.js` 里已经把三种形态都处理了：Buffer、JSON 字符串、原始 HTTP 文本。

## 部署（Worker 版）

1. Cloudflare 控制台 → Workers & Pages → 创建 Worker；
2. 粘贴 `worker.js`，部署；
3. 绑定自定义域名（例如 `bgm.你的域名.com`），DNS 用 Cloudflare 托管最省事。

## 部署（FC 版）

1. FC 控制台 → 函数 `bgm-proxy` → 代码 → 用 `index.js` 整段替换；
2. 点「部署代码」；
3. 验证（应返回 80KB 左右、以 `[` 开头的数组）：

```bash
curl -s http://<你的函数域名>/calendar | head -c 80
```

同时在响应头里看 `x-bgm-path`，应当是 `/calendar` 而不是 `/`。

## 路由（改一次，以后加接口不用再部署）

```
/img/*  → https://lain.bgm.tv/*    （封面等图片）
其它     → https://api.bgm.tv/*     （/calendar、/v0/subjects/xx… 全部透传）
```

因为是前缀透传，新增任何 Bangumi 接口直接请求即可，**不需要重新部署函数**；
只有「再加一个新域名」时才需要往 `ROUTES` 里加一行。

## App 侧配置

我的 → 索引，两个地址都要填：

| 输入框 | 值 |
| --- | --- |
| 索引地址 | `http://bgm-proxy-ieuapreetn.cn-hongkong.fcapp.run` |
| 封面地址前缀 | `http://bgm-proxy-ieuapreetn.cn-hongkong.fcapp.run/img` |

索引地址不要带 `/calendar`（App 会自动拼）。

点「测试连接」，成功会显示：

```
成功 · 500ms · 109 部 · http://.../calendar
```

## 封面

封面图在 `lain.bgm.tv`，与 API 不是一个域名。只反代 API 的话首页有数据、封面是灰块。
要修的话，在同一个函数里加一条图片分支（例如 `/img/*` → `https://lain.bgm.tv/*`），
App 侧再把封面域名替换成该路径。
