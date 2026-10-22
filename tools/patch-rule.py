"""改一条规则里的字段，直接写回 NDJSON。

用法：
  python tools/patch-rule.py aafun "chapterRoads=//ul[contains(@class,'hl-plays-list')]" "chapterResult=.//li/a"
  python tools/patch-rule.py aafun --show
  python tools/patch-rule.py aafun --file=dist/rules.ndjson ...

注意：值按 JSON 解析；解析失败就当纯字符串。写回时保持一行一条、中文不转义。
"""

import io
import json
import sys

RULES = "dist/rules.ndjson"

args = sys.argv[1:]
name = args[0]
path = RULES
show = False
pairs = []
for a in args[1:]:
    if a == "--show":
        show = True
    elif a.startswith("--file="):
        path = a.split("=")[1]
    else:
        pairs.append(a)

lines = io.open(path, encoding="utf-8").read().splitlines()
out = []
hit = False
for line in lines:
    stripped = line.strip()
    if not (stripped.startswith("{") and stripped.endswith("}")):
        out.append(line)
        continue
    rule = json.loads(stripped)
    if rule.get("name") != name:
        out.append(stripped)
        continue
    hit = True
    if show:
        print(json.dumps(rule, ensure_ascii=False, indent=1))
        out.append(stripped)
        continue
    for pair in pairs:
        key, _, raw = pair.partition("=")
        try:
            value = json.loads(raw)
        except Exception:
            value = raw
        rule[key] = value
        print("set %s = %r" % (key, value))
    out.append(json.dumps(rule, ensure_ascii=False))

if not hit:
    print("没找到规则 %r" % name)
    sys.exit(1)
if not show:
    io.open(path, "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
    print("写入 %s（共 %d 行）" % (path, len(out)))
