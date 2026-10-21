// Cloudflare Worker 版 Bangumi 反代（比 FC 省事：免费额度 10 万请求/天，不需要充值）
//
// 部署：Workers & Pages → 创建 Worker → 粘贴本文件 → 绑定自定义域名。
// 路由与 FC 版一致：
//   /img/*  → https://lain.bgm.tv/*
//   其它     → https://api.bgm.tv/*
//
// App 里填：
//   索引地址   https://你的域名
//   封面前缀   https://你的域名/img

const ROUTES = [{ prefix: '/img', upstream: 'https://lain.bgm.tv' }];
const DEFAULT_UPSTREAM = 'https://api.bgm.tv';

// 这些头不能透传：
// - host/content-length/connection 由 fetch 自己算；
// - 条件请求（If-None-Match 等）会让上游回 304，Worker 再回 304 带 body 就会变成 400。
const STRIP = [
  'host',
  'content-length',
  'connection',
  'if-none-match',
  'if-modified-since',
  'if-match',
  'if-unmodified-since',
  'range',
];

export default {
  async fetch(request) {
    const url = new URL(request.url);

    let upstream = DEFAULT_UPSTREAM;
    let path = url.pathname;
    for (const route of ROUTES) {
      if (path === route.prefix || path.startsWith(`${route.prefix}/`)) {
        upstream = route.upstream;
        path = path.slice(route.prefix.length) || '/';
        break;
      }
    }

    const target = upstream + path + url.search;

    const headers = new Headers(request.headers);
    for (const name of STRIP) headers.delete(name);
    headers.set('user-agent', 'dmanime/0.1');
    if (!headers.has('accept')) headers.set('accept', '*/*');

    const isBodyless = request.method === 'GET' || request.method === 'HEAD';

    let response;
    try {
      response = await fetch(target, {
        method: request.method,
        headers,
        body: isBodyless ? undefined : request.body,
      });
    } catch (error) {
      return new Response(JSON.stringify({ error: String(error), target }), {
        status: 502,
        headers: { 'content-type': 'application/json; charset=utf-8' },
      });
    }

    const out = new Headers(response.headers);
    out.set('access-control-allow-origin', '*');
    // 图片走边缘缓存，省额度也更快
    if (path.startsWith('/pic/')) {
      out.set('cache-control', 'public, max-age=604800, immutable');
    }
    // 排查用：确认 Worker 实际拿到的路径与上游
    out.set('x-bgm-path', path);
    out.set('x-bgm-upstream', upstream);

    return new Response(response.body, { status: response.status, headers: out });
  },
};
