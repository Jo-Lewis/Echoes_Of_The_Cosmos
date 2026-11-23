# -*- coding: utf-8 -*-
"""2049ctl — 库构建命令行。

用法:
  python 2049ctl.py scan
  python 2049ctl.py build --limit 100 --out ./library/build
  python 2049ctl.py doctor --out ./library/build
  python 2049ctl.py preview --out ./library/build
"""
import argparse
import json
import os
import random
import sys
import time
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from lib2049 import inventory, build, audio

# 目录可用环境变量覆盖：HEECHO_SRC 原始库 / HEECHO_OUT 构建产物
SRC = os.environ.get("HEECHO_SRC", os.path.join("library", "raw"))
OUT = os.environ.get("HEECHO_OUT", os.path.join("library", "build"))


def cmd_scan(args):
    t0 = time.time()
    arts, audios, docs = inventory.summarize(args.src)
    print(f"\n耗时 {time.time()-t0:.1f}s")
    if args.json:
        data = [{
            "id": a.id, "date": a.date, "title": a.title, "series": a.series,
            "parts": len(a.parts), "method": a.match_method,
            "doc": a.doc.rel if a.doc else None,
            "audio": [p.rel for p in a.parts],
        } for a in arts]
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
        print(f"已写出 {args.json}")


def select_articles(arts, limit, seed=0):
    playable = [a for a in arts if a.playable]
    playable.sort(key=lambda a: (a.date or "9999", a.title))
    by_year = defaultdict(list)
    for a in playable:
        by_year[(a.date or "0000")[:4]].append(a)

    picked = []
    if limit <= 0 or limit >= len(playable):
        return playable

    # 先保证纳入多段节目（跳过 10 小时以上的超长合集）
    def total_hours(a):
        from mutagen.mp3 import MP3
        try:
            return sum(MP3(p.path).info.length for p in a.parts) / 3600
        except Exception:
            return 0
    multi = [a for a in playable if len(a.parts) > 1 and total_hours(a) < 10]
    if multi:
        picked.append(min(multi, key=lambda a: abs(len(a.parts) - 20)))

    years = sorted(by_year)
    idx = {y: 0 for y in years}
    while len(picked) < limit:
        progressed = False
        for y in years:
            if len(picked) >= limit:
                break
            if idx[y] < len(by_year[y]):
                a = by_year[y][idx[y]]
                idx[y] += 1
                if a not in picked:
                    picked.append(a)
                    progressed = True
        if not progressed:
            break
    return picked


def cmd_build(args):
    t0 = time.time()
    arts, _, _ = inventory.build_articles(args.src)
    picked = select_articles(arts, args.limit, args.seed)
    if args.shard:
        i, n = (int(x) for x in args.shard.split("/"))
        picked = [a for k, a in enumerate(picked) if k % n == i]
    print(f"待构建 {len(picked)} 篇 -> {args.out}", flush=True)

    ok = skipped = failed = 0
    t_sum = 0.0
    for i, art in enumerate(picked, 1):
        t1 = time.time()
        try:
            st = build.build_article(art, args.out,
                                     mode=args.mode, overwrite=args.overwrite,
                                     make_thumb=not args.no_thumb)
            dt = time.time() - t1
            t_sum += dt
            if st.get("skipped"):
                skipped += 1
                mark = "-"
            else:
                ok += 1
                mark = "ok"
            print(f"[{i:>3}/{len(picked)}] {mark:>2} {dt:5.1f}s  "
                  f"{art.date or '--------'}  {art.title[:38]}  "
                  f"({len(art.parts)}段 {st.get('chars','-')}字)", flush=True)
        except Exception as e:
            failed += 1
            print(f"[{i:>3}/{len(picked)}] ER {type(e).__name__}: {e}  {art.title[:30]}", flush=True)

    print(f"\n完成：成功 {ok} / 跳过 {skipped} / 失败 {failed}")
    print(f"总耗时 {time.time()-t0:.1f}s，平均 {t_sum/max(ok,1):.1f}s/篇")


def cmd_doctor(args):
    root = args.out
    shows = os.path.join(root, "shows", "2049")
    if not os.path.isdir(shows):
        print("库目录不存在:", shows)
        return
    total = 0
    problems = []
    dur_sum = 0.0
    years = defaultdict(int)
    for y in sorted(os.listdir(shows)):
        ydir = os.path.join(shows, y)
        if not os.path.isdir(ydir):
            continue
        for name in os.listdir(ydir):
            adir = os.path.join(ydir, name)
            if not os.path.isdir(adir):
                continue
            total += 1
            years[y] += 1
            meta_p = os.path.join(adir, "meta.json")
            if not os.path.exists(meta_p):
                problems.append(f"缺 meta.json: {name}")
                continue
            try:
                meta = json.load(open(meta_p, encoding="utf-8"))
            except Exception as e:
                problems.append(f"meta.json 损坏: {name} {e}")
                continue
            dur_sum += meta.get("duration_sec") or 0
            a = meta.get("assets", {})
            if a.get("audio") and not os.path.exists(os.path.join(adir, a["audio"])):
                problems.append(f"缺音频: {name}")
            if a.get("parts_dir"):
                pdir = os.path.join(adir, a["parts_dir"])
                n = len(meta.get("parts") or [])
                if not os.path.isdir(pdir) or len(os.listdir(pdir)) != n:
                    problems.append(f"parts 数量不符: {name} ({n})")
            if a.get("blocks") and not os.path.exists(os.path.join(adir, a["blocks"])):
                problems.append(f"缺 blocks.json: {name}")
            if a.get("cover") and not os.path.exists(os.path.join(adir, a["cover"])):
                problems.append(f"缺封面: {name}")
            # 字数与 blocks 一致性
            if a.get("blocks"):
                try:
                    b = json.load(open(os.path.join(adir, a["blocks"]), encoding="utf-8"))
                    chars = sum(len(x.get("text", "")) for x in b["blocks"] if x["type"] == "p")
                    if meta.get("tech", {}).get("chars") and abs(chars - meta["tech"]["chars"]) > meta["tech"]["chars"] * 0.05:
                        problems.append(f"字数不一致: {name} blocks={chars} meta={meta['tech']['chars']}")
                except Exception as e:
                    problems.append(f"blocks 读取失败: {name} {e}")

    print(f"节目数 {total}")
    print(f"总时长 {dur_sum/3600:.1f} 小时")
    print("按年:", dict(sorted(years.items())))
    print(f"问题 {len(problems)} 条")
    for p in problems[:40]:
        print("  -", p)


def cmd_preview(args):
    root = args.out
    shows = os.path.join(root, "shows", "2049")
    rows = []
    for y in sorted(os.listdir(shows)):
        ydir = os.path.join(shows, y)
        if not os.path.isdir(ydir):
            continue
        for name in sorted(os.listdir(ydir)):
            mp = os.path.join(ydir, name, "meta.json")
            if not os.path.exists(mp):
                continue
            m = json.load(open(mp, encoding="utf-8"))
            rel = os.path.relpath(os.path.join(ydir, name), root).replace("\\", "/")
            rows.append((y, name, m, rel))

    html = ["<!doctype html><meta charset='utf-8'><title>2049 库</title>",
            "<style>body{background:#12141a;color:#e8eaf0;font:14px/1.6 system-ui,sans-serif;margin:0;padding:24px}",
            "h1{font-size:20px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:18px}",
            ".card{background:#1b1e26;border-radius:10px;overflow:hidden}",
            ".card img{width:100%;display:block}.t{padding:8px 10px 12px}",
            ".m{color:#8b93a7;font-size:12px}.y{color:#4a9eff;font-size:12px}",
            "</style><h1>2049 媒体库</h1>"]
    cur_year = None
    for y, name, m, rel in rows:
        if y != cur_year:
            if cur_year is not None:
                html.append("</div>")
            html.append(f"<h2 class='y'>{y}</h2><div class='grid'>")
            cur_year = y
        dur = int((m.get("duration_sec") or 0) // 60)
        parts = f" · {len(m['parts'])}段" if m.get("parts") else ""
        chars = m.get("tech", {}).get("chars") or 0
        html.append(
            f"<div class='card'><img src='{rel}/cover.jpg' loading='lazy'>"
            f"<div class='t'>{m['title'][:40]}<div class='m'>"
            f"{m.get('date') or '—'} · {dur}分{parts} · {chars}字</div></div></div>")
    if cur_year is not None:
        html.append("</div>")
    out = os.path.join(root, "preview.html")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(html))
    print(f"已生成 {out}（{len(rows)} 期）")


def cmd_recovers(args):
    root = args.out
    shows = os.path.join(root, "shows", "2049")
    n = 0
    for y in sorted(os.listdir(shows)):
        ydir = os.path.join(shows, y)
        if not os.path.isdir(ydir):
            continue
        for name in os.listdir(ydir):
            adir = os.path.join(ydir, name)
            mp = os.path.join(adir, "meta.json")
            if not os.path.exists(mp):
                continue
            m = json.load(open(mp, encoding="utf-8"))
            build.make_cover(os.path.join(adir, "cover.jpg"), m["title"],
                             m.get("series") or "", m.get("date"),
                             (m.get("duration_sec") or 0) / 60, m.get("series"))
            n += 1
    print(f"重绘封面 {n} 张")


def cmd_thumbs(args):
    """补生成波形缩略图（建库时可以用 --no-thumb 跳过，之后再补）。"""
    root = args.out
    shows = os.path.join(root, "shows", "2049")
    done = skip = 0
    for y in sorted(os.listdir(shows)):
        yd = os.path.join(shows, y)
        if not os.path.isdir(yd):
            continue
        for name in os.listdir(yd):
            adir = os.path.join(yd, name)
            mp = os.path.join(adir, "meta.json")
            if not os.path.exists(mp):
                continue
            m = json.load(open(mp, encoding="utf-8"))
            a = m.get("assets", {})
            targets = []
            if a.get("audio"):
                targets.append((os.path.join(adir, a["audio"]), os.path.join(adir, "thumb.jpg")))
            for p in (m.get("parts") or []):
                pdir = os.path.join(adir, "parts")
                for d in os.listdir(pdir) if os.path.isdir(pdir) else []:
                    fp = os.path.join(pdir, d, "audio" + os.path.splitext(p["audio"])[1])
                    if os.path.exists(fp):
                        targets.append((fp, os.path.join(pdir, d, "thumb.jpg")))
            for src, dst in targets:
                if os.path.exists(dst) and not args.overwrite:
                    skip += 1
                    continue
                if audio.waveform(src, dst):
                    done += 1
    print(f"生成 {done} 张，跳过 {skip} 张")


def cmd_needs(args):
    """导出没有正文的节目清单，交给 GPU 批量 ASR。"""
    root = args.out
    shows = os.path.join(root, "shows", "2049")
    out = []
    for y in sorted(os.listdir(shows)):
        yd = os.path.join(shows, y)
        if not os.path.isdir(yd):
            continue
        for name in os.listdir(yd):
            adir = os.path.join(yd, name)
            mp = os.path.join(adir, "meta.json")
            if not os.path.exists(mp):
                continue
            m = json.load(open(mp, encoding="utf-8"))
            if not (m.get("flags") or {}).get("needs_asr"):
                continue
            a = m.get("assets", {})
            audios = []
            if a.get("audio"):
                audios.append(os.path.join(adir, a["audio"]))
            for p in (m.get("parts") or []):
                pdir = os.path.join(adir, "parts")
                for d in os.listdir(pdir) if os.path.isdir(pdir) else []:
                    fp = os.path.join(pdir, d, "audio" + os.path.splitext(p["audio"])[1])
                    if os.path.exists(fp):
                        audios.append(fp)
            out.append({"id": m["id"], "title": m["title"],
                        "duration_sec": m.get("duration_sec"), "audios": audios})
    path = os.path.join(root, "needs_asr.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    total_h = sum(x["duration_sec"] or 0 for x in out) / 3600
    print(f"待转录 {len(out)} 期 / {total_h:.1f} 小时 -> {path}")


def _iter_metas(root, only_untagged=True):
    shows = os.path.join(root, "shows", "2049")
    for y in sorted(os.listdir(shows)):
        yd = os.path.join(shows, y)
        if not os.path.isdir(yd):
            continue
        for name in sorted(os.listdir(yd)):
            adir = os.path.join(yd, name)
            mp = os.path.join(adir, "meta.json")
            if not os.path.exists(mp):
                continue
            m = json.load(open(mp, encoding="utf-8"))
            if only_untagged and (m.get("taxonomy") or {}).get("humor"):
                continue
            if not (m.get("flags") or {}).get("has_transcript"):
                continue
            yield adir, mp, m


def _read_text(adir, m):
    f = (m.get("assets") or {}).get("transcript")
    if not f:
        return ""
    p = os.path.join(adir, f)
    if not os.path.exists(p):
        return ""
    return open(p, encoding="utf-8").read()


def cmd_tag(args):
    from concurrent.futures import ThreadPoolExecutor, as_completed
    from lib2049 import tagging

    items = list(_iter_metas(args.out, only_untagged=not args.force))
    if args.sample and args.limit and len(items) > args.limit:
        step = len(items) / args.limit
        items = [items[int(i * step)] for i in range(args.limit)]
    elif args.limit:
        items = items[:args.limit]
    if not items:
        print("没有待打标的节目")
        return

    cache_dir = os.path.join(args.out, "cache", "tagging")
    os.makedirs(cache_dir, exist_ok=True)

    # 组装输入
    jobs = []
    total_tok = 0
    for adir, mp, m in items:
        text = _read_text(adir, m)
        if not text.strip():
            continue
        tok = tagging.estimate_tokens(text[:7000])
        total_tok += tok
        jobs.append((adir, mp, m, text, tok))

    print(f"待打标 {len(jobs)} 期")
    print(f"输入 token 粗估 {total_tok/1e6:.2f}M  "
          f"（按 flash 峰价 输入 $0.30/M + 输出约 $0.0012×期数）")
    est_in = total_tok / 1e6 * 0.30
    est_out = len(jobs) * 900 / 1e6 * 1.2
    print(f"预估成本 输入 ${est_in:.2f} + 输出 ${est_out:.2f} ≈ ${est_in+est_out:.2f} "
          f"≈ ¥{(est_in+est_out)*7.2:.1f}")

    if args.dry_run:
        sample_adir, sample_mp, sample_m, sample_text, _ = jobs[0]
        prompt = tagging.build_prompt(sample_m, sample_text)
        out = os.path.join(args.out, "prompt_sample.txt")
        with open(out, "w", encoding="utf-8") as f:
            f.write("=== SYSTEM ===\n" + tagging.SYSTEM + "\n\n=== USER ===\n" + prompt)
        print(f"\n已把完整 prompt 写到 {out}")
        print(f"（样例：{sample_m['title']}，输入约 {tagging.estimate_tokens(sample_text[:7000])} token）")
        print("\n--- prompt 开头 600 字 ---")
        print(prompt[:600])
        return

    key = args.api_key or os.environ.get("DEEPSEEK_API_KEY", "")
    if not key:
        print("缺少 API key：设 DEEPSEEK_API_KEY 环境变量，或传 --api-key")
        return

    done = fail = 0
    issues_stat = {}
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        futs = {ex.submit(tagging.tag_one, m, text, key): (adir, mp, m) for adir, mp, m, text, _ in jobs}
        for i, fut in enumerate(as_completed(futs), 1):
            adir, mp, m = futs[fut]
            clean, issues, usage = fut.result()
            if clean is None:
                fail += 1
                print(f"[{i}/{len(jobs)}] FAIL {m['title'][:30]} {issues[:1]}", flush=True)
                continue
            for k in issues:
                issues_stat[k.split(":")[0]] = issues_stat.get(k.split(":")[0], 0) + 1
            m["taxonomy"] = {k: clean[k] for k in
                             ("domain", "topics", "content_type", "humor", "narrative",
                              "intensity", "level", "cognitive_load", "pace",
                              "tone_primary", "tone_secondary")}
            m["derived"] = tagging.derive(clean, m.get("duration_sec") or 0)
            m["keywords"] = clean["keywords"]
            m["summary_one_line"] = clean["summary_one_line"]
            m["summary"] = clean["summary"]
            m["best_for"] = clean["best_for"]
            m["hook"] = clean["hook"]
            m["flags"]["tagged"] = True
            m["flags"]["tagged_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
            with open(mp, "w", encoding="utf-8") as f:
                json.dump(m, f, ensure_ascii=False, indent=1)
            with open(os.path.join(cache_dir, m["id"] + ".json"), "w", encoding="utf-8") as f:
                json.dump({"taxonomy": m["taxonomy"], "keywords": m["keywords"],
                           "summary": m["summary"], "usage": usage}, f, ensure_ascii=False, indent=1)
            done += 1
            if i % 10 == 0 or i == len(jobs):
                print(f"[{i}/{len(jobs)}] ok  {m['title'][:28]}  "
                      f"{m['taxonomy']['content_type']}/L{m['taxonomy']['level']}", flush=True)

    print(f"\n完成 {done} / 失败 {fail}，用时 {time.time()-t0:.0f}s")
    if issues_stat:
        print("校验告警:", issues_stat)


def main():
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--src", default=SRC)
    common.add_argument("--out", default=OUT)

    ap = argparse.ArgumentParser(prog="2049ctl")
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("scan", parents=[common])
    s.add_argument("--json", default="")
    s.set_defaults(func=cmd_scan)

    b = sub.add_parser("build", parents=[common])
    b.add_argument("--limit", type=int, default=100)
    b.add_argument("--mode", default="link", choices=["link", "transcode"])
    b.add_argument("--overwrite", action="store_true")
    b.add_argument("--no-thumb", action="store_true")
    b.add_argument("--seed", type=int, default=0)
    b.add_argument("--shard", default="", help="i/n，只处理第 i 份（共 n 份）")
    b.set_defaults(func=cmd_build)

    d = sub.add_parser("doctor", parents=[common]); d.set_defaults(func=cmd_doctor)
    p = sub.add_parser("preview", parents=[common]); p.set_defaults(func=cmd_preview)
    r = sub.add_parser("recovers", parents=[common]); r.set_defaults(func=cmd_recovers)
    t = sub.add_parser("thumbs", parents=[common])
    t.add_argument("--overwrite", action="store_true")
    t.set_defaults(func=cmd_thumbs)
    n = sub.add_parser("needs", parents=[common]); n.set_defaults(func=cmd_needs)
    g = sub.add_parser("tag", parents=[common])
    g.add_argument("--limit", type=int, default=0)
    g.add_argument("--sample", action="store_true", help="按跨度抽样而不是取前 N 条")
    g.add_argument("--workers", type=int, default=4)
    g.add_argument("--dry-run", action="store_true")
    g.add_argument("--force", action="store_true")
    g.add_argument("--api-key", default="")
    g.set_defaults(func=cmd_tag)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
