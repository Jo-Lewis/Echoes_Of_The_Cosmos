# -*- coding: utf-8 -*-
"""系列识别 v4：全局择优分配。

之前是「谁先匹配归谁」，导致「化学武器：庞大家族（上）」被括号规则抢先，
归到「化学武器庞大家族」，把一个大系列拆成好几块。

现在每条标题列出所有候选名，再按「全库出现次数」选出主导系列名。
"""
import glob
import json
import os
import re
from collections import Counter, defaultdict

LIB = r"E:\2049库\shows\2049"
OUT = r"E:\2049库\_series_v4.json"

CN_NUM = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8,
          "九": 9, "十": 10, "上": 1, "中": 2, "下": 3}
NUM = r"([0-9]{1,3}|[一二三四五六七八九十]{1,3}|上|中|下)"
PUNCT = re.compile(r"[【】『』（）()\[\]《》〈〉、，。！？：:；;·—\-–_~～\s\"'“”‘’,.]")


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
    s = PUNCT.sub("", s or "")
    s = re.sub(r"[0-9]+", "", s)
    for _ in range(2):
        s = re.sub(r"[一二三四五六七八九十]$", "", s)
        for tail in ("第", "章", "节", "回", "篇", "部", "讲", "序"):
            if s.endswith(tail) and len(s) - 1 >= 3:
                s = s[:-1]
    return s.strip()


RULES = [
    ("方括号", re.compile(r"^【([^】]{2,20})】"), 1, 0, 9),
    ("直角引号", re.compile(r"『([^』]{2,20})』"), 1, 0, 9),
    ("括号第N", re.compile(r"^(.*?)[（(]\s*第\s*" + NUM + r"\s*[集期话回篇部章讲课]\s*[）)]"), 1, 2, 8),
    ("序字", re.compile(r"^(.*?)[（(]\s*序\s*" + NUM + r"\s*[）)]"), 1, 2, 7),
    ("括号编号", re.compile(r"^(.*?)[（(]\s*" + NUM + r"\s*[）)]"), 1, 2, 6),
    ("第N回", re.compile(r"^(.*?)第\s*" + NUM + r"\s*[集期话回篇部章讲课]"), 1, 2, 6),
    ("上中下", re.compile(r"^(.*?)\s*[（(\-—·]?\s*(上|中|下)\s*[）)]?\s*$"), 1, 2, 5),
    ("之字系列", re.compile(r"^(.{4,18}?)[之—\-–]\s*\S{2,}$"), 1, 0, 4),
    ("冒号前缀", re.compile(r"^([^：:]{2,20})[：:]"), 1, 0, 3),
]


def main():
    metas = []
    for p in sorted(glob.glob(os.path.join(LIB, "*", "*", "meta.json"))):
        metas.append((p, json.load(open(p, encoding="utf-8"))))

    # 1) 每条标题的所有候选
    cands = {}   # eid -> list[(name, seq, priority)]
    for _, m in metas:
        eid = m.get("id")
        t = (m.get("title") or "").strip()
        got = {}
        for rule, pat, gname, gseq, pri in RULES:
            hit = pat.match(t)
            if not hit:
                continue
            name = norm(hit.group(gname))
            if not (2 <= len(name) <= 18):
                continue
            seq = seq_num(hit.group(gseq)) if gseq else 0
            if name not in got or pri > got[name][1]:
                got[name] = (seq, pri, rule)
        if got:
            cands[eid] = [(n, s, pr, r) for n, (s, pr, r) in got.items()]

    # 2) 全库频次
    freq = Counter()
    for eid, lst in cands.items():
        for name, _, _, _ in lst:
            freq[name] += 1

    # 3) 全局择优 + 子系列判定
    #    - 默认归到全库出现次数最多的名字（化学武器：庞大家族（上）也归化学武器）
    #    - 但如果这个名字下挂着两个以上「自身 ≥3 期」的子系列（如法的历程-大陆法系/普通法系），
    #      就保持子系列独立，不要搅成一锅
    names = set(freq)
    subs_of = defaultdict(list)
    for n in names:
        for m2 in names:
            if m2 != n and len(m2) > len(n) and n in m2 and freq[m2] >= 3:
                subs_of[n].append(m2)
    split_umbrella = {n for n, subs in subs_of.items() if len(subs) >= 2}

    assigned = {}
    for eid, lst in cands.items():
        best = max(lst, key=lambda c: (freq[c[0]], c[2], -len(c[0])))
        pick = best
        if best[0] in split_umbrella:
            # 在候选里挑一个够大的子系列名
            subs = [c for c in lst if len(c[0]) > len(best[0]) and best[0] in c[0] and freq[c[0]] >= 3]
            if subs:
                pick = max(subs, key=lambda c: (freq[c[0]], c[2], -len(c[0])))
        assigned[eid] = (pick[0], pick[1])

    # 4) 词干兜底（只给完全没有候选的期）
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

    by_name = defaultdict(list)
    for eid, (name, seq) in assigned.items():
        by_name[name].append((seq, eid))

    groups = {k: v for k, v in by_name.items() if len(v) >= 2}

    # 5) 与旧结果对比
    old = Counter()
    for _, m in metas:
        c = m.get("collection")
        if c:
            old[c.get("name")] += 1
    new = Counter({k: len(v) for k, v in groups.items()})

    print(f"旧：{len(old)} 个系列 / {sum(old.values())} 期")
    print(f"新：{len(new)} 个系列 / {sum(new.values())} 期")
    print("\n变化最大的（新-旧）:")
    diff = sorted(((k, new.get(k, 0) - old.get(k, 0), new.get(k, 0), old.get(k, 0))
                   for k in set(new) | set(old)), key=lambda x: -abs(x[1]))
    for k, d, n, o in diff[:20]:
        if d != 0:
            print(f"  {k}: {o} → {n}  ({d:+d})")

    # 6) 异常检查：特别大的组
    print("\n最大的 10 个系列:")
    for k, v in sorted(groups.items(), key=lambda x: -len(x[1]))[:10]:
        print(f"  {len(v):>4}  {k}")

    json.dump([{"name": k, "size": len(v),
                "members": [{"id": e, "title": next(m["title"] for _, m in metas if m.get("id") == e)}
                            for _, e in sorted(v)]}
               for k, v in sorted(groups.items(), key=lambda x: -len(x[1]))],
              open(OUT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"\n写入 {OUT}")


if __name__ == "__main__":
    main()
