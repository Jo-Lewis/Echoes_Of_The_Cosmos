# -*- coding: utf-8 -*-
"""跑 34 条评估用例，出命中率报告。"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import retrieval

KEY = os.environ.get("DEEPSEEK_API_KEY", "")

# kind: auto = 自动判；human = 需要人判断（只记录结果）
CASES = [
    # A 主题明确
    dict(n=1, q="今天想听点火星的", must=r"火星", kind="auto", grp="A"),
    dict(n=2, q="讲讲黑洞吧", must=r"黑洞", kind="auto", grp="A"),
    dict(n=3, q="有没有讲鲁迅的", must=r"鲁迅", kind="auto", grp="A"),
    dict(n=4, q="想了解量子力学", must=r"量子", kind="auto", grp="A"),
    dict(n=5, q="癌症是怎么治的", must=r"癌症", kind="auto", grp="A"),
    dict(n=6, q="说说明朝那些事", must=r"明|万历|崇祯|嘉靖", kind="auto", grp="A"),
    dict(n=7, q="聊聊咖啡", must=r"咖啡", kind="auto", grp="A"),
    # B 主题 + 约束
    dict(n=8, q="火星的，短一点", must=r"火星", dur_max=20 * 60, kind="auto", grp="B"),
    dict(n=9, q="黑洞，要长的，能听一小时", must=r"黑洞", dur_min=40 * 60, kind="auto", grp="B"),
    dict(n=10, q="讲量子但别太硬核", must=r"量子", kind="human", grp="B"),
    dict(n=11, q="鲁迅的，但不要讲他收入的", must=r"鲁迅", frm=r"收入", kind="auto", grp="B"),
    dict(n=12, q="天文的，2024 年之后的", must=r"星|宇宙|黑洞|行星|天文",
         year_min=2024, kind="auto", grp="B"),
    # C 情绪 / 风格
    dict(n=13, q="今天想听点轻松有趣的", kind="human", grp="C"),
    dict(n=14, q="睡前听，别太刺激", kind="human", grp="C"),
    dict(n=15, q="太累了，随便来点不用动脑的", kind="human", grp="C"),
    dict(n=16, q="想听个故事", kind="human", grp="C"),
    dict(n=17, q="来点硬核的，别水", kind="human", grp="C"),
    dict(n=18, q="有没有那种听完很震撼的", kind="human", grp="C"),
    dict(n=19, q="想听点让人平静的", kind="human", grp="C"),
    # D 场景 / 时长
    dict(n=20, q="通勤路上听，二十分钟左右", dur_min=15 * 60, dur_max=25 * 60, kind="auto", grp="D"),
    dict(n=21, q="做饭的时候放点东西", kind="human", grp="D"),
    dict(n=22, q="五分钟能讲完的", dur_max=8 * 60, kind="auto", grp="D"),
    dict(n=23, q="来个长的，一小时的", dur_min=50 * 60, kind="auto", grp="D"),
    # E 系列
    dict(n=24, q="民族脊梁那套从头听", must=r"民族脊梁", kind="auto", grp="E"),
    dict(n=25, q="暗黑无界系列", must=r"暗黑无界", kind="auto", grp="E"),
    dict(n=26, q="接着刚才那期往下听", kind="human", grp="E"),
    # F 模糊 / 兜底
    dict(n=27, q="随便来一期", kind="auto", grp="F"),
    dict(n=28, q="今天不想听历史的", frm=r"历史|王朝|朝代|帝国|帝国", kind="human", grp="F"),
    dict(n=29, q="来个和上次不一样的", kind="auto", grp="F"),
    dict(n=30, q="有没有讲那个……就是那个天上的东西", kind="human", grp="F"),
    # G 用户原话
    dict(n=31, q="来一期轻松有趣的，大概十几分钟", kind="human", grp="G"),
    dict(n=32, q="有没有讲心理学的，来一期长点的", kind="human", grp="G"),
    dict(n=33, q="讲中国古代历史的", must=r"历史|古代|唐|宋|明|清|汉", kind="auto", grp="G"),
    dict(n=34, q="来一期小众的我还没听过的", kind="human", grp="G"),
]


def text_of(m):
    """只认标题、主题词、关键词——摘要里顺带提到的"火星"不算讲了火星。"""
    t = m.get("taxonomy") or {}
    return " ".join([m.get("title", ""),
                     " ".join(t.get("topics") or []),
                     " ".join(m.get("keywords") or [])])


def check(case, pick, prev):
    if case["kind"] == "human":
        return None, ""
    if case.get("must") and not re.search(case["must"], text_of(pick)):
        return False, "主题不符"
    if case.get("frm") and re.search(case["frm"], text_of(pick)):
        return False, "命中排除词"
    d = pick.get("duration_sec") or 0
    if case.get("dur_max") and d > case["dur_max"]:
        return False, f"太长 {round(d/60)}分"
    if case.get("dur_min") and d < case["dur_min"]:
        return False, f"太短 {round(d/60)}分"
    if case.get("year_min"):
        y = pick.get("year") or 0
        if y < case["year_min"]:
            return False, f"年份 {y}"
    if case["n"] == 29 and prev is not None and pick["id"] == prev["id"]:
        return False, "和上次一样"
    return True, ""


def main():
    lib = retrieval.Library()
    print(f"库内已打标 {lib.n} 期\n")
    rows = []
    prev = None
    for c in CASES:
        t0 = time.time()
        try:
            pick, reason, it, cands = retrieval.search(lib, c["q"], KEY)
        except Exception as e:
            rows.append((c, None, None, f"异常 {type(e).__name__}"))
            continue
        dt = time.time() - t0
        if pick is None:
            rows.append((c, None, None, "无召回"))
            continue
        ok, note = check(c, pick, prev)
        rows.append((c, pick, reason, note if ok is False else ("—" if ok is None else "hit")))
        rows[-1] = (c, pick, reason, ok, note, dt)
        prev = pick

    auto = [r for r in rows if r[0]["kind"] == "auto" and len(r) == 6]
    hit = [r for r in auto if r[3] is True]
    print(f"{'#':>2} {'结果':<6} {'分数':<5} 查询 / 选中")
    print("-" * 86)
    for r in rows:
        if len(r) != 6:
            print(f"{r[0]['n']:>2} {'ERR':<6} {'':<5} {r[0]['q'][:24]}  {r[3]}")
            continue
        c, pick, reason, ok, note, dt = r
        mark = "hit" if ok is True else ("MISS" if ok is False else "人判")
        print(f"{c['n']:>2} {mark:<6} {dt:>4.1f}s {c['q'][:22]:24} → {pick['title'][:30]:32} {note}")
    print("-" * 86)
    if auto:
        print(f"自动判定 {len(auto)} 条，命中 {len(hit)} 条 → Top-1 {len(hit)/len(auto)*100:.0f}%")
    g = [r for r in auto if r[0]["grp"] == "G"]
    if g:
        print(f"用户原话组自动项 {len(g)} 条，命中 {sum(1 for r in g if r[3])} 条")
    json.dump([{"n": r[0]["n"], "q": r[0]["q"],
                "pick": r[1]["title"] if len(r) == 6 and r[1] else None,
                "reason": r[2] if len(r) == 6 else None,
                "ok": r[3] if len(r) == 6 else None}
               for r in rows],
              open(os.path.join(retrieval.ROOT, "eval_report.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
