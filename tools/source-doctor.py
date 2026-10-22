"""源体检（PC 版）：拿 rules.ndjson 里每条规则真去打一遍站点，按失败原因分类。

和 App 里的「源体检」判据一致：搜索 → 若命中就点进第一条看线路。
不同的是这里把每一步的 HTTP 状态码 / 最终 URL / 标题都记下来，
方便判断到底是「站点没了」「域名换了」「被 CF 拦了」还是「规则选择器过期了」。

同时支持两种规则形态：
- xpath 模式：searchURL 模板 + searchList/searchName/searchResult
- api 模式：searchApiConfig.request（GET/POST + query/body）+ JSONPath

用法：
  python tools/source-doctor.py [关键词] [--limit=N] [--only=名字片段] [--proxy=http://127.0.0.1:7897]
输出：
  .scratch/doctor.json、.scratch/doctor.md
"""

import concurrent.futures
import json
import re
import sys
import time
import urllib.parse
from dataclasses import dataclass, field, asdict

import requests
from lxml import html as lxml_html

UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

TIMEOUT = 20
RULES_PATH = "dist/rules.ndjson"
RULES_FILE = RULES_PATH
PROXY = ""  # 空=直连；Windows 上 Python 会读注册表里的系统代理，必须显式关掉
DUMP = False  # True 时把搜索页/详情页 HTML 落到 .scratch/pages/ 便于改选择器
INSECURE = False  # True 时不校验证书（用来区分「站点证书本身有问题」和「本机 CA 包缺它」）
RETRIES = 2  # 网络抖动很常见，失败后重试次数（不含首次）


@dataclass
class Result:
    name: str
    mode: str = "xpath"
    base: str = ""
    status: int = 0
    final_url: str = ""
    bytes: int = 0
    title: str = ""
    items: int = 0
    roads: int = 0
    episodes: int = 0
    elapsed_ms: int = 0
    verdict: str = ""
    note: str = ""
    sample: list = field(default_factory=list)


def make_session() -> requests.Session:
    s = requests.Session()
    s.trust_env = False  # 不读系统代理/环境变量，否则代理没开时全是 ProxyError
    if PROXY:
        s.proxies = {"http": PROXY, "https": PROXY}
    if INSECURE:
        s.verify = False
        import warnings

        warnings.filterwarnings("ignore")
    return s


# ---------- 通用小工具 ----------

def fill(template: str, keyword: str) -> str:
    quoted = urllib.parse.quote(keyword)
    if "@keyword" in template:
        return template.replace("@keyword", quoted)
    if "%s" in template:
        return template.replace("%s", quoted)
    return template


def scalar(value) -> str:
    """URL 查询参数里的值：JSON 布尔必须是小写 true/false，否则服务端当成 "True" 会 400。"""
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return ""
    return str(value)


def resolve(base: str, href: str) -> str:
    """App 里 `//` 表示相对 baseURL；其余按标准 URL 解析。"""
    if not href:
        return ""
    if href.startswith("//"):
        return base.rstrip("/") + "/" + href.lstrip("/")
    return urllib.parse.urljoin(base, href)


def xp_first(doc, expr):
    if not expr:
        return None
    try:
        got = doc.xpath(expr)
    except Exception:
        return None
    return got[0] if got else None


def xp_all(doc, expr):
    if not expr:
        return []
    try:
        return doc.xpath(expr)
    except Exception:
        return []


def text_of(node) -> str:
    if node is None:
        return ""
    if isinstance(node, str):
        return node.strip()
    return re.sub(r"\s+", " ", node.text_content()).strip()


def attr_or_text(node, attr: str) -> str:
    if node is None:
        return ""
    if isinstance(node, str):
        return node.strip()
    if attr:
        got = node.get(attr)
        if got:
            return got.strip()
    return (node.get("href") or "").strip() or text_of(node)


def jp_read(data, path):
    """只支持 $.a.b[*].c 这种受限路径，够规则用。"""
    if not path or not path.startswith("$"):
        return []
    tokens = []
    for part in path[1:].split("."):
        if not part:
            continue
        if part.endswith("[*]"):
            head = part[: -len("[*]")]
            if head:  # "$[*]" 这种直接以 [*] 结尾的，不要再插一个空 key
                tokens.append(head)
            tokens.append("[*]")
        else:
            tokens.append(part)
    cur = [data]
    for tok in tokens:
        nxt = []
        for node in cur:
            if tok == "[*]":
                if isinstance(node, list):
                    nxt.extend(node)
                elif isinstance(node, dict):
                    nxt.extend(node.values())
            elif isinstance(node, dict) and tok in node:
                nxt.append(node[tok])
            elif isinstance(node, list) and tok.isdigit():
                idx = int(tok)
                if idx < len(node):
                    nxt.append(node[idx])
        cur = nxt
    return cur


def jp_first(data, path) -> str:
    got = jp_read(data, path)
    if not got:
        return ""
    value = got[0]
    return "" if value is None else str(value)


def page_title(content: bytes) -> str:
    try:
        doc = lxml_html.fromstring(content)
        return re.sub(r"\s+", " ", " ".join(doc.xpath("//title/text()"))).strip()
    except Exception:
        return ""


def dump(name: str, suffix: str, content: bytes) -> None:
    if not DUMP:
        return
    try:
        import os
        os.makedirs(".scratch/pages", exist_ok=True)
        safe = re.sub(r"[^\w\-]+", "_", name)
        with open(".scratch/pages/%s-%s.html" % (safe, suffix), "wb") as f:
            f.write(content)
    except Exception:
        pass


# ---------- 分类 ----------

def classify(res, exc) -> None:
    if exc is not None:
        msg = str(exc)
        low = msg.lower()
        cause = ""
        matched = re.search(r"Caused by (\w+)\((.{,120})", msg, re.S)
        if matched:
            cause = "%s: %s" % (matched.group(1), matched.group(2).strip())
        if "name or service not known" in low or "nodename" in low or "getaddrinfo" in low:
            res.verdict, res.note = "dns", "域名解析失败"
        elif "timed out" in low or "timeout" in low:
            res.verdict, res.note = "timeout", "连接超时"
        elif "certificate_verify_failed" in low:
            res.verdict, res.note = "cert", "证书校验失败（自签/过期）：%s" % cause[:80]
        elif "wrong_version_number" in low:
            res.verdict, res.note = "http-only", "443 端口不响应 TLS（多半只能 http）"
        elif "ssl" in low or "certificate" in low:
            res.verdict, res.note = "ssl", "TLS 握手被掐：%s" % (cause or msg[:70])
        elif "connection" in low or "reset" in low or "refused" in low or "aborted" in low:
            res.verdict, res.note = "conn", "连接被拒/重置"
        else:
            res.verdict, res.note = "error", (cause or msg)[:110]
        return

    s = res.status
    low = res.title.lower()
    if s in (403, 503):
        res.verdict, res.note = "blocked", "HTTP %d，被风控/CF 拦截" % s
    elif s >= 500:
        res.verdict, res.note = "server", "HTTP %d，站点自己出错" % s
    elif s == 404:
        res.verdict, res.note = "notfound", "搜索接口 404（路径改了）"
    elif res.items == 0:
        if "just a moment" in low or "cloudflare" in low or "attention required" in low:
            res.verdict, res.note = "challenge", "CF 人机验证页（%s）" % res.title[:40]
        elif res.bytes < 2500:
            res.verdict, res.note = "empty", "HTTP %d 但只有 %d 字节（%s）" % (s, res.bytes, res.title[:36])
        else:
            res.verdict, res.note = "selector", "页面正常（%s）但搜索选择器抓不到条目" % res.title[:36]
    elif res.roads == 0:
        res.verdict, res.note = "chapter", "能搜到（%d 条），但详情页抓不到线路" % res.items
    else:
        res.verdict, res.note = "ok", ""


# ---------- 探针 ----------

def probe(rule: dict, keyword: str) -> Result:
    res = Result(name=rule.get("name", "?"), mode=rule.get("searchMode") or "xpath")
    base = (rule.get("baseURL") or "").strip()
    res.base = base
    started = time.time()
    session = make_session()
    headers = {
        "User-Agent": rule.get("userAgent") or UA,
        "Referer": rule.get("referer") or base,
        "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    }
    exc = None
    try:
        if res.mode == "api":
            probe_api(rule, keyword, session, headers, res)
        else:
            probe_xpath(rule, keyword, session, headers, res)
        res.elapsed_ms = int((time.time() - started) * 1000)
    except Exception as e:  # noqa: BLE001 - 探针就是要吞异常
        exc = e
        res.elapsed_ms = int((time.time() - started) * 1000)
    classify(res, exc)
    return res


def probe_xpath(rule, keyword, session, headers, res: Result) -> None:
    base = res.base
    if not base.startswith("http"):
        raise ValueError("baseURL 不合法：%r" % base)
    url = fill(rule.get("searchURL") or "", keyword)
    if not url:
        raise ValueError("searchURL 为空且不是 api 模式")
    r = session.get(url, headers=headers, timeout=TIMEOUT, allow_redirects=True)
    res.status, res.final_url, res.bytes = r.status_code, r.url, len(r.content)
    res.title = page_title(r.content) if r.content else ""
    dump(res.name, "search", r.content)
    if r.status_code != 200:
        return
    doc = lxml_html.fromstring(r.content)
    nodes = xp_all(doc, rule.get("searchList") or "")
    res.items = len(nodes)
    for node in nodes[:3]:
        src = attr_or_text(xp_first(node, rule.get("searchResult") or ""), "href")
        res.sample.append(
            {"title": text_of(xp_first(node, rule.get("searchName") or "")), "src": resolve(base, src)[:160]}
        )
    if not nodes:
        return
    first_src = attr_or_text(xp_first(nodes[0], rule.get("searchResult") or ""), "href")
    detail = resolve(base, first_src)
    if not detail:
        return
    r2 = session.get(detail, headers=headers, timeout=TIMEOUT, allow_redirects=True)
    if r2.status_code != 200:
        res.note = "详情页 HTTP %d" % r2.status_code
        return
    doc2 = lxml_html.fromstring(r2.content)
    dump(res.name, "detail", r2.content)
    roads = xp_all(doc2, rule.get("chapterRoads") or "")
    res.roads = len(roads)
    total = 0
    for road in roads:
        total = max(total, len(xp_all(road, rule.get("chapterResult") or "")))
    res.episodes = total


def fill_deep(obj, placeholder: str, value: str):
    """把 JSON body 里的 "@keyword"/"@source" 占位符换掉（App 里也是这么做的）。"""
    if isinstance(obj, dict):
        return {k: fill_deep(v, placeholder, value) for k, v in obj.items()}
    if isinstance(obj, list):
        return [fill_deep(v, placeholder, value) for v in obj]
    if isinstance(obj, str):
        return obj.replace(placeholder, value)
    return obj


def probe_api(rule, keyword, session, headers, res: Result) -> None:
    cfg = rule.get("searchApiConfig") or {}
    req = cfg.get("request") or {}
    url = req.get("url") or ""
    if not url:
        raise ValueError("searchApiConfig.request.url 为空")
    # 查询参数交给 requests 编码，这里绝不能提前 quote（否则百分号会被二次编码，
    # 服务端拿到 "%E8%91%AC..." 字面量 → 搜不到任何东西）
    query = {k: scalar(v).replace("@keyword", keyword).replace("%s", keyword) for k, v in (req.get("query") or {}).items()}
    method = (req.get("method") or "GET").upper()
    headers = dict(headers)
    for k, v in (req.get("headers") or {}).items():
        headers[k] = str(v)
    if method == "POST":
        body = fill_deep(req.get("body"), "@keyword", keyword)
        r = session.post(url, params=query, json=body, headers=headers, timeout=TIMEOUT)
    else:
        r = session.get(url, params=query, headers=headers, timeout=TIMEOUT)
    res.status, res.final_url, res.bytes = r.status_code, r.url, len(r.content)
    res.title = page_title(r.content) if r.content and b"<" in r.content[:200] else ""
    dump(res.name, "search", r.content)
    if r.status_code != 200:
        return
    try:
        data = r.json()
    except Exception:
        res.note = "响应不是 JSON：%s" % r.text[:80].replace("\n", " ")
        return
    nodes = jp_read(data, cfg.get("listPath") or "")
    res.items = len(nodes)
    for node in nodes[:3]:
        res.sample.append(
            {
                "title": jp_first(node, cfg.get("namePath") or ""),
                "src": jp_first(node, cfg.get("sourcePath") or "")[:160],
            }
        )
    if not nodes:
        return
    ch = rule.get("chapterApiConfig") or {}
    creq = ch.get("request") or {}
    source_value = jp_first(nodes[0], cfg.get("sourcePath") or "")
    if (rule.get("chapterMode") or "xpath") != "api":
        # 混合规则：搜索走 api，章节仍然解析详情页（sourceTemplate 决定详情地址）
        tmpl = cfg.get("sourceTemplate") or ""
        detail = tmpl.replace("@source", source_value) if tmpl else resolve(res.base, source_value)
        if not detail:
            return
        r2 = session.get(detail, headers=headers, timeout=TIMEOUT, allow_redirects=True)
        if r2.status_code != 200:
            res.note = "详情页 HTTP %d" % r2.status_code
            return
        dump(res.name, "detail", r2.content)
        doc = lxml_html.fromstring(r2.content)
        roads = xp_all(doc, rule.get("chapterRoads") or "")
        res.roads = len(roads)
        total = 0
        for road in roads:
            total = max(total, len(xp_all(road, rule.get("chapterResult") or "")))
        res.episodes = total
        return
    curl = (creq.get("url") or "").replace("@source", source_value)
    if not curl:
        return
    cquery = {k: fill(str(v), keyword) for k, v in (creq.get("query") or {}).items()}
    if (creq.get("method") or "GET").upper() == "GET":
        r2 = session.get(curl, params=cquery, headers=headers, timeout=TIMEOUT)
    else:
        cbody = fill_deep(creq.get("body"), "@source", source_value)
        r2 = session.post(curl, params=cquery, json=cbody, headers=headers, timeout=TIMEOUT)
    if r2.status_code != 200:
        res.note = "线路接口 HTTP %d" % r2.status_code
        return
    dump(res.name, "chapter", r2.content)
    try:
        data2 = r2.json()
    except Exception:
        return
    if (ch.get("format") or "nested") == "nested":
        roads = jp_read(data2, ch.get("roadsPath") or "")
        res.roads = len(roads)
        total = 0
        for road in roads:
            total = max(total, len(jp_read(road, ch.get("episodesPath") or "")))
        res.episodes = total
    else:
        roads = jp_read(data2, ch.get("roadNamesPath") or "")
        eps = jp_read(data2, ch.get("roadEpisodesPath") or "")
        res.roads = len(roads)
        best = 0
        for item in eps:
            best = max(best, len(str(item).split(ch.get("episodeSeparator") or "#")))
        res.episodes = best


def probe_with_retry(rule: dict, keyword: str) -> Result:
    """网络抖动很常见（同一批里 米粒/akianime/UZVOD 会一次好一次超时），失败重试再判死。"""
    res = probe(rule, keyword)
    tries = 0
    while res.verdict != "ok" and tries < RETRIES:
        tries += 1
        again = probe(rule, keyword)
        if again.verdict == "ok" or (res.verdict in ("timeout", "conn", "ssl", "empty") and again.verdict not in ("timeout", "conn", "ssl", "empty")):
            res = again
        if again.verdict == "ok":
            break
    return res


def main() -> None:
    global PROXY, DUMP, RULES_FILE, INSECURE
    plain = [a for a in sys.argv[1:] if not a.startswith("--")]
    keyword = plain[0] if plain else "葬送的芙莉莲"
    limit, only = 0, ""
    for a in sys.argv[1:]:
        if a.startswith("--limit"):
            limit = int(a.split("=")[1]) if "=" in a else 0
        elif a.startswith("--only"):
            only = a.split("=")[1] if "=" in a else ""
        elif a.startswith("--proxy"):
            PROXY = a.split("=")[1] if "=" in a else ""
        elif a == "--dump":
            DUMP = True
        elif a == "--insecure":
            INSECURE = True
        elif a.startswith("--rules"):
            RULES_FILE = a.split("=")[1] if "=" in a else RULES_FILE

    rules = [json.loads(line) for line in open(RULES_FILE, encoding="utf-8") if line.strip().startswith("{")]
    if only:
        wanted = [w for w in only.split(",") if w]
        rules = [r for r in rules if any(w in r.get("name", "") for w in wanted)]
    if limit:
        rules = rules[:limit]
    print("共 %d 条规则，关键词「%s」，代理=%s" % (len(rules), keyword, PROXY or "直连"))

    results = []
    GOOD = ("ok",)
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        futures = [pool.submit(probe_with_retry, rule, keyword) for rule in rules]
        for i, fut in enumerate(concurrent.futures.as_completed(futures), 1):
            res = fut.result()
            results.append(res)
            print(
                "[%d/%d] %-18s %-9s items=%-3d roads=%-2d eps=%-4d %5dms %s"
                % (i, len(rules), res.name[:18], res.verdict, res.items, res.roads, res.episodes, res.elapsed_ms, res.note)
            )

    order = {"ok": 0, "chapter": 1, "selector": 2, "empty": 3, "challenge": 4, "blocked": 5,
             "notfound": 6, "server": 6, "timeout": 7, "dns": 8, "conn": 8, "ssl": 8, "error": 9}
    results.sort(key=lambda r: (order.get(r.verdict, 10), r.name))

    with open(".scratch/doctor.json", "w", encoding="utf-8") as f:
        json.dump([asdict(r) for r in results], f, ensure_ascii=False, indent=1)

    counts = {}
    for r in results:
        counts[r.verdict] = counts.get(r.verdict, 0) + 1
    lines = ["# 源体检（关键词：%s）" % keyword, "", "## 汇总", ""]
    for k, v in sorted(counts.items(), key=lambda kv: -kv[1]):
        lines.append("- %s：%d" % (k, v))
    lines += ["", "## 明细", "", "| 规则 | 模式 | 结论 | 搜索 | 线路 | 集数 | 耗时 | 页面标题 | 备注 |",
              "|---|---|---|---|---|---|---|---|---|"]
    for r in results:
        lines.append("| %s | %s | %s | %d | %d | %d | %dms | %s | %s |"
                     % (r.name, r.mode, r.verdict, r.items, r.roads, r.episodes, r.elapsed_ms, r.title[:30], r.note))
    with open(".scratch/doctor.md", "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    print("\n汇总:", counts)


if __name__ == "__main__":
    main()
