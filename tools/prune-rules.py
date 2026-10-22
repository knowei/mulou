"""按名字删掉失效规则：留备份、留被删清单，重写 rules.ndjson 与 rules-pretty.json。

删除名单单独放在 dist/rules-dead.txt（每行一个规则名，# 开头是注释），
这样脚本本身不含任何第三方站点信息，可以进仓库。

用法：
    python tools/prune-rules.py --dry        # 只看会删什么
    python tools/prune-rules.py              # 真删
    python tools/prune-rules.py --rules=dist/rules.ndjson --dead=dist/rules-dead.txt
"""

import json
import os
import shutil
import sys

RULES = "dist/rules.ndjson"
PRETTY = "dist/rules-pretty.json"
DEAD = "dist/rules-dead.txt"
DROPPED = ".scratch/rules-dropped.ndjson"


def load_names(path: str) -> list:
    if not os.path.exists(path):
        print("找不到删除名单 %s" % path)
        return []
    names = []
    for line in open(path, encoding="utf-8"):
        text = line.split("#")[0].strip()
        if text:
            names.append(text)
    return names


def main() -> None:
    global RULES, DEAD
    dry = "--dry" in sys.argv
    for arg in sys.argv[1:]:
        if arg.startswith("--rules="):
            RULES = arg.split("=", 1)[1]
        elif arg.startswith("--dead="):
            DEAD = arg.split("=", 1)[1]

    wanted = load_names(DEAD)
    if not wanted:
        return

    kept, dropped = [], []
    for line in open(RULES, encoding="utf-8"):
        line = line.strip()
        if not line.startswith("{"):
            continue
        rule = json.loads(line)
        (dropped if rule.get("name") in wanted else kept).append(rule)

    print("原始 %d 条，删除 %d 条，保留 %d 条" % (len(kept) + len(dropped), len(dropped), len(kept)))
    missing = [n for n in wanted if n not in {r.get("name") for r in dropped}]
    if missing:
        print("!! 名单里有名字没匹配到（可能已经删过或拼写不一致）：")
        for name in missing:
            print("   ? %s" % name)
    print("--- 删除 ---")
    for rule in dropped:
        print("  - %s  %s" % (rule.get("name"), rule.get("baseURL")))
    print("--- 保留 ---")
    for rule in kept:
        print("  + %s  %s" % (rule.get("name"), rule.get("baseURL")))
    if dry:
        return

    shutil.copyfile(RULES, ".scratch/rules-before-prune.ndjson")
    with open(DROPPED, "w", encoding="utf-8", newline="\n") as f:
        for rule in dropped:
            f.write(json.dumps(rule, ensure_ascii=False) + "\n")
    with open(RULES, "w", encoding="utf-8", newline="\n") as f:
        for rule in kept:
            f.write(json.dumps(rule, ensure_ascii=False) + "\n")
    with open(PRETTY, "w", encoding="utf-8", newline="\n") as f:
        json.dump(kept, f, ensure_ascii=False, indent=1)
    print("\n已写入 %s（%d 条）与 %s；被删规则留在 %s" % (RULES, len(kept), PRETTY, DROPPED))


if __name__ == "__main__":
    main()
