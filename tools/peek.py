"""看 HTML 结构的小工具。

用法：
  python tools/peek.py .scratch/pages/ezdmw-detail.html anthology
  python tools/peek.py <file> <keyword> [--width=1200] [--count=3]
打印每个命中位置前后各 width/2 字符的压缩文本，方便判断选择器该怎么写。
"""

import re
import sys

path = sys.argv[1]
keyword = sys.argv[2]
width = 1200
count = 3
for a in sys.argv[3:]:
    if a.startswith("--width="):
        width = int(a.split("=")[1])
    if a.startswith("--count="):
        count = int(a.split("=")[1])

text = open(path, encoding="utf-8", errors="replace").read()
print("file=%s len=%d keyword=%r" % (path, len(text), keyword))
hits = [m.start() for m in re.finditer(re.escape(keyword), text)][:count]
if not hits:
    print("没有命中 %r" % keyword)
    # 顺手给出所有 class 名，便于找线索
    classes = sorted(set(re.findall(r'class="([^"]{1,60})"', text)))
    print("页面里的 class 共 %d 种：" % len(classes))
    for c in classes[:80]:
        print("  ", c)
    sys.exit(0)
for i, pos in enumerate(hits, 1):
    half = width // 2
    snippet = text[max(0, pos - half): pos + half]
    snippet = re.sub(r"\s+", " ", snippet)
    print("\n--- 命中 %d @%d ---\n%s" % (i, pos, snippet))
