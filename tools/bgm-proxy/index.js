// 阿里云函数计算 FC 3.0 · Node.js 18 · HTTP 触发器
//
// 多上游路由反代：
//   /img/*   → https://lain.bgm.tv/*        （封面等图片）
//   其它     → https://api.bgm.tv/*         （保持向后兼容，/calendar 直接可用）
//
// 因为是前缀透传，新增任何 Bangumi 接口都不用重新部署，直接请求即可。
// 只有「再加一个新域名」才需要改 ROUTES 再部署一次。
//
// 关键点：FC 可能把 event 传成 Buffer（实测 Object.keys 是 0..579），
// 必须先 toString 再 JSON.parse，否则拿不到 rawPath，路径永远落到 "/"。

const ROUTES = [
  { prefix: '/img', upstream: 'https://lain.bgm.tv' },
];

const DEFAULT_UPSTREAM = 'https://api.bgm.tv';

/** event 可能是 Buffer / JSON 字符串 / 对象，统一成对象。 */
function parseEvent(event) {
  if (Buffer.isBuffer(event)) event = event.toString('utf8');
  if (typeof event === 'string') {
    try {
      return JSON.parse(event);
    } catch {
      return null;
    }
  }
  return event && typeof event === 'object' ? event : null;
}

/** 兜底：如果 event 是原始 HTTP 文本，首行形如 "GET /calendar HTTP/1.1"。 */
function pathFromRawRequest(raw) {
  const line = String(raw || '').split(/\r?\n/)[0] || '';
  const matched = line.match(/^\w+\s+(\S+)/);
  return matched ? matched[1] : null;
}

/** 按前缀选上游，返回去掉前缀后的路径。 */
function resolveTarget(path) {
  for (const route of ROUTES) {
    if (path === route.prefix || path.startsWith(route.prefix + '/')) {
      const rest = path.slice(route.prefix.length) || '/';
      return { upstream: route.upstream, path: rest.startsWith('/') ? rest : '/' + rest };
    }
  }
  return { upstream: DEFAULT_UPSTREAM, path };
}

exports.handler = async (event, context) => {
  const rawText = Buffer.isBuffer(event) ? event.toString('utf8') : event;
  const evt = parseEvent(event) || {};

  const rawPath =
    evt.rawPath ||
    evt.path ||
    evt.requestContext?.http?.path ||
    evt.headers?.['x-fc-request-path'] ||
    evt.headers?.['x-forwarded-uri'] ||
    pathFromRawRequest(rawText) ||
    '/calendar';

  const query = evt.rawQueryString
    ? `?${evt.rawQueryString}`
    : evt.queryParameters
      ? '?' + new URLSearchParams(evt.queryParameters).toString()
      : '';

  const method = evt.requestContext?.http?.method || evt.httpMethod || 'GET';
  const { upstream, path } = resolveTarget(rawPath);
  const targetUrl = upstream + path + query;

  // 复制请求头，去掉会让 undici 报错或干扰上游的字段
  const headers = { ...(evt.headers || {}) };
  delete headers.host;
  delete headers['content-length'];
  delete headers.connection;
  // 条件请求必须剥掉：客户端带 If-None-Match 时上游回 304，
  // 我们把 304 连同空 body 返回给 FC，最终会变成 400。
  delete headers['if-none-match'];
  delete headers['if-modified-since'];
  delete headers['if-match'];
  delete headers['if-unmodified-since'];
  delete headers.range;
  headers['user-agent'] = 'dmanime/0.1';
  if (!headers['accept']) headers['accept'] = '*/*';

  try {
    const response = await fetch(targetUrl, {
      method,
      headers,
      body:
        method === 'GET' || method === 'HEAD'
          ? undefined
          : evt.isBase64Encoded
            ? Buffer.from(evt.body || '', 'base64')
            : evt.body,
    });

    const bytes = Buffer.from(await response.arrayBuffer());

    const resHeaders = {};
    response.headers.forEach((value, key) => {
      const lower = key.toLowerCase();
      // content-length 由运行时重算；content-encoding 已由 fetch 解压
      if (!['content-encoding', 'content-length', 'transfer-encoding', 'connection'].includes(lower)) {
        resHeaders[key] = value;
      }
    });
    resHeaders['access-control-allow-origin'] = '*';
    // 图片走 CDN 缓存，省流量也更稳
    if (path.startsWith('/pic/')) {
      resHeaders['cache-control'] = 'public, max-age=604800, immutable';
    }
    resHeaders['x-bgm-path'] = path;
    resHeaders['x-bgm-upstream'] = upstream;

    return {
      statusCode: response.status,
      headers: resHeaders,
      isBase64Encoded: true,
      body: bytes.toString('base64'),
    };
  } catch (err) {
    return {
      statusCode: 502,
      headers: { 'content-type': 'application/json; charset=utf-8' },
      body: JSON.stringify({ error: String(err), targetUrl, rawPath }),
    };
  }
};
