# -*- coding: utf-8 -*-
"""从 meta.collection 生成清单（以实际写入的数据为准）。"""
import glob
import json
import os
from collections import Counter, defaultdict

from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill
from openpyxl.utils import get_column_letter

LIB = r"E:\2049库\shows\2049"
XLSX = r"E:\2049库\_系列清单.xlsx"
import re

PUNCT = re.compile(r"[【】『』（）()\[\]《》〈〉、，。！？：:；;·—\-–_~～\s\"'“”‘’,.]")


def norm(s):
    s = PUNCT.sub("", s or "")
    s = re.sub(r"[0-9]+", "", s)
    return s


def main():
    eps = []
    for p in sorted(glob.glob(os.path.join(LIB, "*", "*", "meta.json"))):
        eps.append(json.load(open(p, encoding="utf-8")))

    groups = defaultdict(list)
    for m in eps:
        c = m.get("collection")
        if c:
            groups[c["name"]].append((c.get("seq") or 0, m))
    for v in groups.values():
        v.sort(key=lambda x: (x[0], x[1].get("date") or ""))

    wb = Workbook()
    hf = PatternFill("solid", fgColor="2F4F6F")
    hfont = Font(color="FFFFFF", bold=True)

    def sheet(title, headers, rows, widths):
        ws = wb.create_sheet(title)
        ws.append(headers)
        for c in range(1, len(headers) + 1):
            ws.cell(row=1, column=c).fill = hf
            ws.cell(row=1, column=c).font = hfont
        for r in rows:
            ws.append(r)
        for i, w in enumerate(widths, 1):
            ws.column_dimensions[get_column_letter(i)].width = w
        ws.freeze_panes = "A2"

    total = sum(len(v) for v in groups.values())
    sizes = Counter(len(v) for v in groups.values())
    ov = wb.active
    ov.title = "概览"
    ov.append(["项目", "数值"])
    for row in [
        ("总期数", len(eps)), ("系列数", len(groups)), ("系列覆盖期数", total),
        ("覆盖率", f"{total/len(eps)*100:.1f}%"), ("2 期系列", sizes.get(2, 0)),
        ("3 期系列", sizes.get(3, 0)), ("4 期及以上", sum(v for k, v in sizes.items() if k >= 4)),
        ("未归入系列", len(eps) - total),
    ]:
        ov.append(list(row))
    ov.column_dimensions["A"].width = 20
    ov.column_dimensions["B"].width = 16

    rows = []
    for i, (name, lst) in enumerate(sorted(groups.items(), key=lambda x: -len(x[1])), 1):
        ms = [m for _, m in lst]
        dur = sum((m.get("duration_sec") or 0) for m in ms) // 60
        rows.append([i, name, len(ms), dur, ms[0].get("date") or "", ms[-1].get("date") or "",
                     " / ".join((m.get("title") or "")[:24] for m in ms[:3]) + (" …" if len(ms) > 3 else "")])
    sheet("全部系列", ["#", "系列名", "期数", "总分钟", "最早", "最晚", "代表标题"],
          rows, [6, 26, 7, 9, 12, 12, 70])

    rows = []
    for name, lst in groups.items():
        if len(lst) != 2:
            continue
        ms = [m for _, m in lst]
        hit = sum(1 for m in ms if name in norm(m.get("title") or ""))
        conf = "高" if hit == 2 and len(name) >= 4 else ("中" if hit == 2 else "低")
        rows.append([name, (ms[0].get("title") or "")[:40], (ms[1].get("title") or "")[:40], conf, "待确认"])
    rows.sort(key=lambda r: {"高": 0, "中": 1, "低": 2}[r[3]])
    sheet("2期系列待核对", ["系列名", "第 1 期", "第 2 期", "可信度", "是否成系列"], rows, [24, 42, 42, 10, 14])

    # 疑似漏网：未归入、共同开头 ≥4 字
    rest = [m for m in eps if not m.get("collection")]
    buckets = defaultdict(list)
    for m in rest:
        t = norm(m.get("title") or "")
        if len(t) >= 4:
            buckets[t[:4]].append(m)
    rows = []
    for pre, lst in sorted(buckets.items(), key=lambda x: -len(x[1])):
        if len(lst) < 2:
            continue
        for m in lst:
            rows.append([pre, m.get("title") or "", m.get("date") or "",
                         (m.get("duration_sec") or 0) // 60, "待确认"])
    sheet("疑似漏网", ["共同开头", "标题", "日期", "分钟", "是否成系列"], rows, [14, 52, 12, 8, 14])

    rows = []
    for name, lst in groups.items():
        for i, (_, m) in enumerate(lst, 1):
            rows.append([name, len(lst), i, m.get("title") or "", m.get("date") or "",
                         (m.get("duration_sec") or 0) // 60, m.get("id")])
    sheet("成员明细", ["系列名", "总期数", "第几期", "标题", "日期", "分钟", "id"],
          rows, [24, 10, 8, 56, 12, 8, 26])

    wb.save(XLSX)
    print(f"系列 {len(groups)} 个 · 覆盖 {total} 期")
    print(f"Excel: {XLSX}")


if __name__ == "__main__":
    main()
