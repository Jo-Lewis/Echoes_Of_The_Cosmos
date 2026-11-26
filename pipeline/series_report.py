# -*- coding: utf-8 -*-
"""生成系列清单：全部系列 + 2 期系列 + 疑似漏网，输出 Excel 和 Markdown。"""
import glob
import json
import os
import re
from collections import Counter, defaultdict

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

import series_v3 as SV3

LIB = r"E:\2049库\shows\2049"
XLSX = r"E:\2049库\_系列清单.xlsx"
MD = r"E:\2049库\_系列清单.md"

STOP = set("的 了 和 与 是 在 我 你 他 这 那 什么 为什么 如何 我们 一个 一期 节目 事情 故事 世界 中国 美国".split())


def load():
    out = []
    for p in sorted(glob.glob(os.path.join(LIB, "*", "*", "meta.json"))):
        m = json.load(open(p, encoding="utf-8"))
        m["_path"] = p
        out.append(m)
    return out


def build_groups(metas):
    assigned = {}
    rule_of = {}
    for rule, pat, gname, gseq in SV3.RULES:
        for m in metas:
            eid = m.get("id")
            if eid in assigned:
                continue
            t = (m.get("title") or "").strip()
            hit = pat.match(t)
            if not hit:
                continue
            name = SV3.norm(hit.group(gname))
            if not (2 <= len(name) <= 18):
                continue
            assigned[eid] = (name, SV3.seq_num(hit.group(gseq)) if gseq else 0)
            rule_of[eid] = rule

    # 词干兜底
    stem = defaultdict(list)
    for m in metas:
        eid = m.get("id")
        if eid in assigned:
            continue
        t = SV3.norm(m.get("title") or "")
        if len(t) >= 6:
            stem[t[:8] if len(t) >= 8 else t].append(eid)
    for k, ids in stem.items():
        if len(ids) >= 2:
            for i, eid in enumerate(ids):
                assigned.setdefault(eid, (k, i + 1))
                rule_of.setdefault(eid, "词干")

    by_name = defaultdict(list)
    for eid, (name, seq) in assigned.items():
        by_name[name].append((seq, eid))

    groups = []
    for name, lst in by_name.items():
        if len(lst) < 2:
            continue
        date_of = {m.get("id"): (m.get("date") or "") for m in metas}
        lst.sort(key=lambda x: (x[0] or 999, date_of.get(x[1], "")))
        members = [eid for _, eid in lst]
        rules = Counter(rule_of.get(e, "?") for e in members)
        groups.append({
            "name": name,
            "size": len(members),
            "rule": rules.most_common(1)[0][0],
            "members": members,
        })
    groups.sort(key=lambda g: (-g["size"], g["name"]))
    return groups


def fuzzy_missed(metas, grouped_ids):
    """未分组标题里找「共同开头 ≥4 字」的候选——比任意 4-gram 准得多。"""
    rest = [m for m in metas if m.get("id") not in grouped_ids]
    buckets = defaultdict(list)
    for m in rest:
        t = SV3.norm(m.get("title") or "")
        if len(t) >= 4:
            buckets[t[:4]].append(m)
    out = []
    for pre, lst in buckets.items():
        if len(lst) < 2:
            continue
        # 尽量给出更长的共同前缀
        common = pre
        for i in range(4, 12):
            nxt = {SV3.norm(m.get("title") or "")[i:i + 1] for m in lst
                   if len(SV3.norm(m.get("title") or "")) > i}
            if len(nxt) == 1 and "" not in nxt:
                common += nxt.pop()
            else:
                break
        out.append({"gram": common, "members": lst})
    out.sort(key=lambda c: -len(c["members"]))
    return out[:150]


def main():
    metas = load()
    groups = build_groups(metas)
    by_id = {m.get("id"): m for m in metas}
    grouped = {e for g in groups for e in g["members"]}

    wb = Workbook()
    head_fill = PatternFill("solid", fgColor="2F4F6F")
    head_font = Font(color="FFFFFF", bold=True)

    def sheet(title, headers, rows, widths):
        ws = wb.create_sheet(title)
        ws.append(headers)
        for c in range(1, len(headers) + 1):
            cell = ws.cell(row=1, column=c)
            cell.fill = head_fill
            cell.font = head_font
        for r in rows:
            ws.append(r)
        for i, w in enumerate(widths, 1):
            ws.column_dimensions[get_column_letter(i)].width = w
        ws.freeze_panes = "A2"
        return ws

    # 概览
    sizes = Counter(g["size"] for g in groups)
    total_eps = sum(g["size"] for g in groups)
    ov = wb.active
    ov.title = "概览"
    ov.append(["项目", "数值"])
    rows = [
        ("总期数", len(metas)),
        ("识别到的系列数", len(groups)),
        ("系列覆盖期数", total_eps),
        ("覆盖率", f"{total_eps/len(metas)*100:.1f}%"),
        ("2 期系列", sizes.get(2, 0)),
        ("3 期系列", sizes.get(3, 0)),
        ("4 期及以上", sum(v for k, v in sizes.items() if k >= 4)),
        ("未归入任何系列", len(metas) - total_eps),
    ]
    for r in rows:
        ov.append(list(r))
    ov.column_dimensions["A"].width = 22
    ov.column_dimensions["B"].width = 18

    # 全部系列
    rows = []
    for i, g in enumerate(groups, 1):
        ms = [by_id[e] for e in g["members"]]
        dur = sum(m.get("duration_sec") or 0 for m in ms) // 60
        rows.append([
            i, g["name"], g["size"], dur, g["rule"],
            ms[0].get("date") or "", ms[-1].get("date") or "",
            " / ".join((m.get("title") or "")[:26] for m in ms[:3]) +
            (" …" if len(ms) > 3 else ""),
        ])
    sheet("全部系列", ["#", "系列名", "期数", "总分钟", "识别规则", "最早", "最晚", "代表标题"],
          rows, [6, 26, 7, 9, 12, 12, 12, 70])

    # 2 期系列（需重点核对）
    def confidence(name, ms):
        """标题里真的带着这个系列名 → 高；只靠冒号/词干推出来的 → 中低。"""
        hits = sum(1 for m in ms if name in SV3.norm(m.get("title") or ""))
        if hits == 2 and len(name) >= 4:
            return "高"
        if hits == 2:
            return "中"
        if hits == 1:
            return "低"
        return "很低"

    rows = []
    for g in [g for g in groups if g["size"] == 2]:
        ms = [by_id[e] for e in g["members"]]
        rows.append([g["name"], (ms[0].get("title") or "")[:40], (ms[1].get("title") or "")[:40],
                     g["rule"], confidence(g["name"], ms), "待确认"])
    rows.sort(key=lambda r: {"高": 0, "中": 1, "低": 2, "很低": 3}[r[4]])
    sheet("2期系列待核对", ["系列名", "第 1 期", "第 2 期", "规则", "可信度", "是否成系列"],
          rows, [24, 42, 42, 12, 10, 14])

    # 疑似漏网
    rows = []
    for c in fuzzy_missed(metas, grouped):
        for m in c["members"]:
            rows.append([c["gram"], m.get("title") or "", m.get("date") or "",
                         m.get("duration_sec", 0) // 60, "待确认"])
    sheet("疑似漏网", ["共同片段", "标题", "日期", "分钟", "是否成系列"],
          rows, [14, 52, 12, 8, 14])

    # 成员明细
    rows = []
    for g in groups:
        for i, e in enumerate(g["members"], 1):
            m = by_id[e]
            rows.append([g["name"], g["size"], i, m.get("title") or "",
                         m.get("date") or "", (m.get("duration_sec") or 0) // 60, e])
    sheet("成员明细", ["系列名", "系列总期数", "第几期", "标题", "日期", "分钟", "id"],
          rows, [24, 12, 8, 56, 12, 8, 26])

    wb.save(XLSX)

    # Markdown 摘要
    lines = ["# 系列清单", "",
             f"- 总期数：{len(metas)}",
             f"- 系列数：**{len(groups)}**",
             f"- 覆盖：**{total_eps} 期（{total_eps/len(metas)*100:.1f}%）**",
             f"- 未归入：{len(metas)-total_eps} 期", "",
             "## 全部系列", "", "| # | 系列 | 期数 | 分钟 | 规则 |", "|---|---|---|---|---|"]
    for i, g in enumerate(groups, 1):
        dur = sum((by_id[e].get("duration_sec") or 0) for e in g["members"]) // 60
        lines.append(f"| {i} | {g['name']} | {g['size']} | {dur} | {g['rule']} |")
    open(MD, "w", encoding="utf-8").write("\n".join(lines) + "\n")

    print(f"系列 {len(groups)} 个 · 覆盖 {total_eps} 期")
    print(f"Excel: {XLSX}")
    print(f"Markdown: {MD}")


if __name__ == "__main__":
    main()
