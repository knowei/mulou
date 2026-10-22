"""看详情页里的"线路"结构：每个候选线路容器的条目数、以及它前后的标题文字。

用法：python tools/roads.py <file> --xpath="//ul[contains(@class,'hl-plays-list')]"
默认会尝试几种常见选集容器。
"""

import re
import sys

from lxml import html as lxml_html


def squash(node) -> str:
    if node is None:
        return ""
    return re.sub(r"\s+", " ", node.text_content()).strip()


path = sys.argv[1]
expr = ""
for a in sys.argv[2:]:
    if a.startswith("--xpath="):
        expr = a.split("=")[1]

doc = lxml_html.parse(path).getroot()
if not expr:
    for cand in (
        "//ul[contains(@class,'play')]",
        "//div[contains(@class,'play')]",
        "//ul[contains(@class,'list')]",
    ):
        got = doc.xpath(cand)
        print("%-40s -> %d" % (cand, len(got)))
    sys.exit(0)

nodes = doc.xpath(expr)
print("file=%s expr=%s hits=%d" % (path, expr, len(nodes)))
for i, node in enumerate(nodes[:12]):
    anchors = node.xpath(".//a[@href]")
    tail = node.getprevious()
    head = squash(tail)[:40] if tail is not None else ""
    parent = node.getparent()
    print(
        "  #%d tag=%s class=%r anchors=%d prev=%r parent=%s.%s"
        % (i, node.tag, (node.get("class") or "")[:40], len(anchors), head, parent.tag, (parent.get("class") or "")[:30])
    )
    for a in anchors[:3]:
        print("      %-40s %r" % ((a.get("href") or "")[:40], squash(a)[:20]))
