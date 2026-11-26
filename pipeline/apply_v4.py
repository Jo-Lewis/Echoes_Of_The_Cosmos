# -*- coding: utf-8 -*-
"""把 v4 结果写入 meta，并从 meta 重新生成清单。"""
import glob
import json
import os

LIB = r"E:\2049库\shows\2049"
SRC = r"E:\2049库\_series_v4.json"


def main():
    groups = json.load(open(SRC, encoding="utf-8"))
    want = {}
    for g in groups:
        for i, m in enumerate(g["members"], 1):
            want[m["id"]] = {"name": g["name"], "seq": i, "size": g["size"]}

    changed = cleared = 0
    for p in sorted(glob.glob(os.path.join(LIB, "*", "*", "meta.json"))):
        m = json.load(open(p, encoding="utf-8"))
        eid = m.get("id")
        w = want.get(eid)
        old = m.get("collection")
        if w:
            if old != w:
                m["collection"] = w
                json.dump(m, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
                changed += 1
        elif old is not None:
            m.pop("collection", None)
            json.dump(m, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
            cleared += 1

    print(f"写入 {changed} 期，清除 {cleared} 期，共 {len(want)} 期有系列")


if __name__ == "__main__":
    main()
