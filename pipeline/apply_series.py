# -*- coding: utf-8 -*-
"""应用系列识别结果到 meta.json。

- 同名组合并（不管来自哪条规则）
- 写入 collection = {name, seq, size}
- 清掉旧的、这次没被识别的 collection 字段
"""
import glob
import json
import os
import re
import sys
from collections import defaultdict

sys.path.insert(0, r"E:\Documents\WorkSpace\2049\tool")
from series_v3 import RULES, norm, seq_num  # noqa: E402

LIB = r"E:\2049库\shows\2049"


def main():
    metas = []
    for p in sorted(glob.glob(os.path.join(LIB, "*", "*", "meta.json"))):
        metas.append((p, json.load(open(p, encoding="utf-8"))))

    # 1) 按规则优先级给每期打一个候选（只记录名字和序号）
    assigned = {}
    for rule, pat, gname, gseq in RULES:
        for _, m in metas:
            eid = m.get("id")
            if eid in assigned:
                continue
            t = (m.get("title") or "").strip()
            hit = pat.match(t)
            if not hit:
                continue
            name = norm(hit.group(gname))
            if not (2 <= len(name) <= 18):
                continue
            assigned[eid] = (name, seq_num(hit.group(gseq)) if gseq else 0)

    # 2) 兜底：归一化前 8 字
    stem = defaultdict(list)
    for _, m in metas:
        eid = m.get("id")
        if eid in assigned:
            continue
        t = norm(m.get("title") or "")
        if len(t) >= 6:
            stem[t[:8] if len(t) >= 8 else t].append(eid)
    for k, ids in stem.items():
        if len(ids) >= 2:
            for i, eid in enumerate(ids):
                assigned.setdefault(eid, (k, i + 1))

    # 3) 按名字合并
    by_name = defaultdict(list)
    for eid, (name, seq) in assigned.items():
        by_name[name].append((seq, eid))

    final = {}
    for name, lst in by_name.items():
        if len(lst) < 2:
            continue
        date_of = {m.get("id"): (m.get("date") or "") for _, m in metas}
        lst.sort(key=lambda x: (x[0] or 999, date_of.get(x[1], "")))
        size = len(lst)
        for i, (_, eid) in enumerate(lst, 1):
            final[eid] = {"name": name, "seq": i, "size": size}

    # 4) 写回；清掉不再属于任何系列的旧字段
    changed = 0
    cleared = 0
    for p, m in metas:
        eid = m.get("id")
        want = final.get(eid)
        old = m.get("collection")
        if want:
            if old != want:
                m["collection"] = want
                json.dump(m, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
                changed += 1
        else:
            if old is not None:
                m.pop("collection", None)
                json.dump(m, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
                cleared += 1

    names = {v["name"] for v in final.values()}
    print(f"系列 {len(names)} 个 · 覆盖 {len(final)} 期")
    print(f"写入 {changed} 期，清除旧标记 {cleared} 期")

    # 抽样核对
    print("\n覆盖最多的 12 个系列:")
    sizes = defaultdict(int)
    for v in final.values():
        sizes[v["name"]] = v["size"]
    for n, s in sorted(sizes.items(), key=lambda x: -x[1])[:12]:
        print(f"  {s:>3} 期  {n}")


if __name__ == "__main__":
    main()
