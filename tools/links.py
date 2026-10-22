"""列出 HTML 里所有指向"播放/剧集"的链接，用来反推选集选择器。

用法：python tools/links.py <file> [--contains=play] [--limit=40]
"""

import re
import sys

from lxml import html as lxml_html

path = sys.argv[1]
contains = "play"
limit = 40
for a in sys.argv[2:]:
    if a.startswith("--contains="):
        contains = a.split("=")[1]
    if a.startswith("--limit="):
        limit = int(a.split("=")[1])

doc = lxml_html.parse(path).getroot()
anchors = doc.xpath("//a[@href]")
print("file=%s anchors=%d" % (path, len(anchors)))
shown = 0
for a in anchors:
    href = a.get("href") or ""
    if contains and contains not in href:
        continue
    text = re.sub(r"\s+", " ", a.text_content()).strip()
    parent = a.getparent()
    chain = []
    node = parent
    while node is not None and len(chain) < 4 and node.tag != "body":
        cls = (node.get("class") or "")[:28]
        chain.append("%s.%s" % (node.tag, cls) if cls else node.tag)
        node = node.getparent()
    print("%-60s | %-24s | %s" % (href[:60], text[:24], " < ".join(chain)))
    shown += 1
    if shown >= limit:
        break
if shown == 0:
    print("没有包含 %r 的链接；下面按 href 形状分组：" % contains)
    buckets = {}
    for a in anchors:
        h = a.get("href") or ""
        key = re.sub(r"\d+", "N", h)[:40]
        buckets[key] = buckets.get(key, 0) + 1
    for k, v in sorted(buckets.items(), key=lambda kv: -kv[1])[:20]:
        print("  %4d  %s" % (v, k))
