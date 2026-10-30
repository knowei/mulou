"""把 dist/rules.ndjson 发布到仓库里的 rules/ 目录。

为什么要这一步：
- App 的「规则自动更新」只认仓库里的 `rules/rules.ndjson`；
- `dist/` 是本地产物目录（被 gitignore），不能直接当发布源；
- manifest.json 给人看（多少条、什么时候发的、指纹），App 不依赖它做判断。

用法：
    python tools/publish-rules.py
"""

import hashlib
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "dist", "rules.ndjson")
OUT_DIR = os.path.join(ROOT, "rules")
OUT = os.path.join(OUT_DIR, "rules.ndjson")
MANIFEST = os.path.join(OUT_DIR, "manifest.json")


def main():
    if not os.path.exists(SRC):
        print("找不到 " + SRC)
        return 1

    rules = []
    bad = 0
    with io.open(SRC, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                rule = json.loads(line)
            except Exception as e:
                bad += 1
                print("跳过一行解析失败：%s" % e)
                continue
            name = (rule.get("name") or "").strip()
            if not name or not (rule.get("baseURL") or "").strip():
                bad += 1
                print("跳过缺少 name/baseURL 的规则：%s" % name)
                continue
            rules.append(rule)

    if not rules:
        print("没有可用规则，什么都没写")
        return 1

    # 按名字排序：规则包几百条时不排序，diff 会变成一大坨
    rules.sort(key=lambda r: r["name"])

    text = "\n".join(json.dumps(r, ensure_ascii=False, separators=(",", ":")) for r in rules) + "\n"
    if not os.path.isdir(OUT_DIR):
        os.makedirs(OUT_DIR)
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)

    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    manifest = {
        "count": len(rules),
        "sha256": digest,
        "updatedAt": __import__("time").strftime("%Y-%m-%d"),
        "file": "rules.ndjson",
        "note": "由 tools/publish-rules.py 生成，App 按内容比对，不看版本号",
    }
    with io.open(MANIFEST, "w", encoding="utf-8", newline="\n") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")

    print("已发布 %d 条规则（跳过 %d 条）→ rules/rules.ndjson" % (len(rules), bad))
    print("sha256 %s" % digest[:16])
    return 0


if __name__ == "__main__":
    sys.exit(main())
