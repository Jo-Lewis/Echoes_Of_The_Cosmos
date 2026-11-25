# -*- coding: utf-8 -*-
"""系列识别 v3：在 v2 基础上补
  - 归一化更强（去数字、去结尾中文序号、去前导符号）→ 苹果公司1976/1984 能并到一起
  - 「X之Y」「X—Y」这种不带括号的系列
  - 第N回/篇/部/章/讲
  - （序一）（序二）
"""
import glob
import json
import os
import re
from collections import Counter, defaultdict

LIB = r"E:\2049库\shows\2049"
OUT = r"E:\2049库\_series_v3.json"

CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8,
          "九": 9, "十": 10, "上": 1, "中": 2, "下": 3}
NUM = r"([0-9]{1,3}|[一二三四五六七八九十]{1,3}|上|中|下)"

PUNCT = re.compile(r"[【】（）()\[\]《》〈〉、，。！？：:；;·—\-–_~～\s\"'“”‘’,.]")


def seq_num(tok):
    if tok.isdigit():
        return int(tok)
    if tok in CN_NUM:
        return CN_NUM[tok]
    if tok == "十":
        return 10
    if "十" in tok:
        a, b = tok.split("十", 1)
        return CN_NUM.get(a, 0) * 10 + (CN_NUM.get(b, 0) if b else 0)
    return CN_NUM.get(tok, 0)


def norm(s):
    """去掉括号、标点、数字、结尾中文序号，得到系列名。"""
    s = PUNCT.sub("", s or "")
    s = re.sub(r"[0-9]+", "", s)
    s = re.sub(r"[一二三四五六七八九十]$", "", s)   # 只去结尾一个，避免伤到「三国」
    for _ in range(2):
        s = re.sub(r"[一二三四五六七八九十]$", "", s)
        for tail in ("第", "章", "节", "回", "篇", "部", "讲", "序"):
            if s.endswith(tail) and len(s) - 1 >= 3:
                s = s[:-1]
    return s.strip()


RULES = [
    ("方括号", re.compile(r"^【([^】]{2,20})】"), 1, 0),
    ("括号第N", re.compile(r"^(.*?)[（(]\s*第\s*" + NUM + r"\s*[集期话回篇部章讲课]\s*[）)]"), 1, 2),
    ("序字", re.compile(r"^(.*?)[（(]\s*序\s*" + NUM + r"\s*[）)]"), 1, 2),
    ("括号编号", re.compile(r"^(.*?)[（(]\s*" + NUM + r"\s*[）)]"), 1, 2),
    ("第N回", re.compile(r"^(.*?)第\s*" + NUM + r"\s*[集期话回篇部章讲课]"), 1, 2),
    ("上中下", re.compile(r"^(.*?)\s*[（(\-—·]?\s*(上|中|下)\s*[）)]?\s*$"), 1, 2),
    ("之字系列", re.compile(r"^(.{4,18}?)[之—\-–]\s*\S{2,}$"), 1, 0),
    ("冒号前缀", re.compile(r"^([^：:]{2,20})[：:]"), 1, 0),
]


def main():
    metas = []
    for p in sorted(glob.glob(os.path.join(LIB, "*", "*", "meta.json"))):
        metas.append((p, json.load(open(p, encoding="utf-8"))))

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
            seq = seq_num(hit.group(gseq)) if gseq else 0
            assigned.setdefault(eid, (rule, name, seq))

    # 兜底：归一化后前 8 字相同
    stem = defaultdict(list)
    for _, m in metas:
        eid = m.get("id")
        if eid in assigned:
            continue
        t = norm(m.get("title") or "")
        if len(t) >= 6:
            stem[t[:8] if len(t) >= 8 else t].append((eid, m))
    for k, lst in stem.items():
        if len(lst) >= 2:
            for i, (eid, m) in enumerate(lst):
                assigned.setdefault(eid, ("词干", k, i + 1))

    groups = defaultdict(list)
    for eid, (rule, name, seq) in assigned.items():
        groups[(rule, name)].append((seq, eid))

    real = {k: v for k, v in groups.items() if len(v) >= 2}
    covered = sum(len(v) for v in real.values())
    print(f"系列 {len(real)} 个 · 覆盖 {covered} 期（{covered/len(metas)*100:.1f}%）")
    print("规模分布:", dict(sorted(Counter(len(v) for v in real.values()).items(), reverse=True)))
    print("按规则:", dict(Counter(k[0] for k in real)))

    # 用户点名的几个必须命中
    must = ["日本简史", "化工简史", "科学狂人", "有趣的旗帜", "苹果公司", "中国领先技术", "释永信", "资治通鉴"]
    print("\n=== 点名检查 ===")
    for kw in must:
        found = [(k[0], k[1], len(v)) for k, v in real.items() if kw in k[1]]
        print(f"  {kw}: {found if found else '❌ 未命中'}")

    print("\n=== 最大的 20 个系列 ===")
    for (rule, name), lst in sorted(real.items(), key=lambda x: -len(x[1]))[:20]:
        print(f"  {len(lst):>3} 期  [{rule}] {name}")

    out = [{"rule": k[0], "name": k[1], "size": len(v),
            "members": [{"id": eid, "title": next(m["title"] for _, m in metas if m.get("id") == eid)}
                        for _, eid in sorted(v, key=lambda x: (x[0] or 999, x[1]))]}
           for k, v in sorted(real.items(), key=lambda x: -len(x[1]))]
    json.dump(out, open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\n写入 {OUT}")


if __name__ == "__main__":
    main()
