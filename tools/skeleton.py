"""打印某个节点（或它的父节点）的子树骨架，帮你看清"线路名+选集列表"的嵌套关系。

用法：python tools/skeleton.py <file> --xpath="//div[contains(@class,'hl-list-wrap')]" [--depth=3]
"""

import re
import sys

from lxml import html as lxml_html


def squash(node) -> str:
    return re.sub(r"\s+", " ", node.text_content()).strip()


path = sys.argv[1]
expr = ""
depth = 3
for a in sys.argv[2:]:
    if a.startswith("--xpath="):
        expr = a.split("=")[1]
    if a.startswith("--depth="):
        depth = int(a.split("=")[1])

doc = lxml_html.parse(path).getroot()
nodes = doc.xpath(expr)
print("file=%s expr=%s hits=%d" % (path, expr, len(nodes)))


def walk(node, level, max_level):
    if level > max_level:
        return
    for child in node:
        if not isinstance(child.tag, str):
            continue
        own = "".join(child.itertext() and list(child.itertext())[:1] or []).strip()[:30]
        print(
            "  " * level
            + "%s%s  direct_text=%r  children=%d"
            % (child.tag, ("." + (child.get("class") or "")[:40]) if child.get("class") else "", own, len(child))
        )
        walk(child, level + 1, max_level)


for i, node in enumerate(nodes[:2]):
    print("\n=== #%d ===" % i)
    walk(node, 1, depth)
