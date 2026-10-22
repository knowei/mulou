"""把新规则并进 dist/rules.ndjson，或从备份里恢复指定名字的规则。

用法：
    python tools/merge-rules.py --add .scratch/omofun-rule.ndjson
    python tools/merge-rules.py --restore .scratch/rules-before-prune.ndjson --names "hanime1[1080p],hanime1[720p]"
"""

import json
import sys

RULES = "dist/rules.ndjson"
PRETTY = "dist/rules-pretty.json"


def read(path):
    out = []
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line.startswith("{"):
            out.append(json.loads(line))
    return out


def write(items):
    with open(RULES, "w", encoding="utf-8", newline="\n") as f:
        for r in items:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    with open(PRETTY, "w", encoding="utf-8", newline="\n") as f:
        json.dump(items, f, ensure_ascii=False, indent=1)


def main():
    current = read(RULES)
    names = {r.get("name") for r in current}
    only = set()
    for arg in sys.argv[1:]:
        if arg.startswith("--names="):
            only = {n.strip() for n in arg.split("=", 1)[1].split(",") if n.strip()}

    for arg in sys.argv[1:]:
        if arg.startswith("--add=") or arg.startswith("--restore="):
            src = arg.split("=", 1)[1]
            for rule in read(src):
                name = rule.get("name")
                if only and name not in only:
                    continue
                if name in names:
                    current = [rule if r.get("name") == name else r for r in current]
                    print("= 覆盖同名规则 %s" % name)
                else:
                    current.append(rule)
                    names.add(name)
                    print("+ 新增规则 %s（%s）" % (name, rule.get("baseURL")))
    write(current)
    print("现在共 %d 条" % len(current))


if __name__ == "__main__":
    if len(sys.argv) == 1:
        print(__doc__)
    else:
        main()
