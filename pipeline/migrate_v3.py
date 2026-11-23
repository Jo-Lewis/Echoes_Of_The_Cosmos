# -*- coding: utf-8 -*-
"""把 v2 meta.json 迁移到 v3。

- 重新按显式规则确定「本期资产目录」，避免分类目录串图
- 配图重命名并写入 caption（源文件名）
- 写 provenance / validation
- 生成 library.json 清单
"""
import json
import os
import re
import shutil
import sys
import time

OUT = os.environ.get("ECHOES_OUT", os.path.join("library", "build"))
LIB = os.path.join(OUT, "shows", "2049")
SRC = os.environ.get("ECHOES_SRC", os.path.join("library", "raw"))
EXTS = (".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".jfif", ".jpe", ".heic")
LONG = "\\\\?\\"
CATEGORY = ("短篇", "长篇")
MAX_IMAGES = 120


def lp(p):
    return p if p.startswith(LONG) else LONG + os.path.abspath(p)


def natural(s):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def collect(folder):
    out = []
    for dp, dn, fn in os.walk(lp(folder)):
        for f in fn:
            if f.lower().endswith(EXTS):
                out.append(os.path.join(dp, f))
    out.sort(key=natural)
    return out


def resolve_doc_dir(doc_rel, doc_stem):
    """返回 (episode_dir | None, rule)"""
    if not doc_rel:
        return None, "no-doc"
    doc_dir = os.path.join(SRC, os.path.dirname(doc_rel))
    if not os.path.isdir(lp(doc_dir)):
        return None, "doc-missing"

    if os.path.basename(doc_dir) in CATEGORY:
        stem = re.sub(r"^\d{3,8}[-_ ]?", "", doc_stem)
        base = lp(doc_dir)
        try:
            for name in os.listdir(base):
                p = os.path.join(doc_dir, name)
                if os.path.isdir(lp(p)) and stem and (stem in name or name in stem):
                    return p, "category-sibling"
        except Exception:
            pass
        return None, "none"

    return doc_dir, "episode-folder"


def main():
    metas = []
    for y in sorted(os.listdir(LIB)):
        yd = os.path.join(LIB, y)
        if not os.path.isdir(yd):
            continue
        for name in sorted(os.listdir(yd)):
            adir = os.path.join(yd, name)
            mp = os.path.join(adir, "meta.json")
            if os.path.exists(mp):
                metas.append((adir, mp))

    print(f"待迁移 {len(metas)} 期")
    stat = {"single": 0, "series": 0, "img": 0, "noimg": 0, "warn": 0}

    for i, (adir, mp) in enumerate(metas, 1):
        m = json.load(open(mp, encoding="utf-8"))
        assets_v2 = m.get("assets") or {}
        src = m.get("source") or {}
        doc_rel = src.get("doc")
        doc_stem = os.path.splitext(os.path.basename(doc_rel or ""))[0]

        # ---- 音频 ----
        parts = m.get("parts") or []
        audio = []
        if parts:
            pdir = os.path.join(adir, "parts")
            subdirs = sorted([d for d in os.listdir(lp(pdir))
                              if os.path.isdir(lp(os.path.join(pdir, d)))]) if os.path.isdir(lp(pdir)) else []
            for idx, p in enumerate(parts):
                rel = os.path.join("parts", subdirs[idx] if idx < len(subdirs) else "",
                                   p.get("audio", "audio.mp3"))
                audio.append({
                    "path": rel.replace(os.sep, "/"),
                    "duration_sec": p.get("duration_sec", 0),
                    "gain_db": p.get("gain_db", 0),
                    "title": p.get("title") or p.get("part_label"),
                    "no": p.get("no", idx),
                })
            kind = "series"
        else:
            a = assets_v2.get("audio")
            if isinstance(a, str) and a:
                tech = m.get("tech") or {}
                audio.append({
                    "path": a,
                    "duration_sec": tech.get("duration_sec", m.get("duration_sec")),
                    "gain_db": tech.get("gain_db", 0),
                })
            kind = "single"

        # ---- 文稿 ----
        text = None
        if assets_v2.get("blocks"):
            text = {
                "markdown": assets_v2.get("transcript"),
                "blocks": assets_v2.get("blocks"),
                "chars": (m.get("tech") or {}).get("chars", 0),
                "anchor_source": "ratio+silence",
            }

        # ---- 配图 ----
        ep_dir, rule = resolve_doc_dir(doc_rel, doc_stem)
        names, warnings = [], []
        src_files = []
        if ep_dir:
            src_files = collect(ep_dir)
            if len(src_files) > MAX_IMAGES:
                warnings.append("image_count_suspicious")
                src_files = []
            if not src_files:
                warnings.append("no_images_in_folder")

        img_dir = os.path.join(adir, "images")
        if os.path.isdir(lp(img_dir)):
            shutil.rmtree(lp(img_dir), ignore_errors=True)
        images = []
        if src_files:
            os.makedirs(lp(img_dir), exist_ok=True)
            for idx, f in enumerate(src_files, 1):
                ext = os.path.splitext(f)[1].lower()
                nm = f"{idx:02d}{ext}"
                try:
                    shutil.copyfile(f, lp(os.path.join(img_dir, nm)))
                except Exception as e:
                    warnings.append("copy_failed")
                    continue
                images.append({
                    "path": f"images/{nm}",
                    "caption": os.path.splitext(os.path.basename(f))[0],
                })

        v3 = {
            "schema": 3,
            "id": m.get("id"),
            "kind": kind,
            "title": m.get("title"),
            "date": m.get("date"),
            "year": m.get("year"),
            "duration_sec": m.get("duration_sec"),
            "series": m.get("series"),
            "assets": {
                "audio": audio,
                "cover": assets_v2.get("cover"),
                "thumb": assets_v2.get("thumb"),
                "text": text,
                "images": images,
            },
            "taxonomy": m.get("taxonomy"),
            "derived": m.get("derived"),
            "provenance": {
                "audio": {"from": (src.get("audio") or [None])[0], "rule": m.get("match_method")},
                "doc": {"from": doc_rel, "rule": m.get("match_method")},
                "images": {"from": os.path.relpath(ep_dir, SRC) if ep_dir else None, "rule": rule},
            },
            "validation": {
                "images_expected": len(src_files),
                "images_found": len(images),
                "warnings": warnings,
            },
            "flags": m.get("flags") or {},
            "pipeline": {"version": "3.0.0",
                         "built_at": time.strftime("%Y-%m-%dT%H:%M:%S")},
        }
        with open(mp, "w", encoding="utf-8") as f:
            json.dump(v3, f, ensure_ascii=False, indent=1)

        stat[kind] = stat.get(kind, 0) + 1
        stat["img" if images else "noimg"] += 1
        if warnings:
            stat["warn"] += 1
        if i % 400 == 0:
            print(f"  {i}/{len(metas)}", stat, flush=True)

    manifest = {
        "schema": 3,
        "count": len(metas),
        "built_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "series": stat.get("series", 0),
        "with_images": stat["img"],
    }
    with open(os.path.join(os.path.dirname(LIB), "..", "library.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=1)
    print("完成", stat)


if __name__ == "__main__":
    main()
