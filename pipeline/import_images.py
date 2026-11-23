# -*- coding: utf-8 -*-
r"""把源目录里的配图拷进库，按 01.jpg 顺序命名，并写回 meta.assets.images。

- 源目录：meta.source.doc 所在的文件夹（含子文件夹，如「鲁迅插图/01/」）
- 长路径：Windows 260 字符限制，统一用 \\?\ 前缀
- 幂等：已存在的图片跳过
"""
import json
import os
import re
import shutil
import sys
from concurrent.futures import ThreadPoolExecutor

OUT = os.environ.get("HEECHO_OUT", os.path.join("library", "build"))
LIB = os.path.join(OUT, "shows", "2049")
SRC = os.environ.get("HEECHO_SRC", os.path.join("library", "raw"))
EXTS = (".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".jfif", ".jpe", ".heic")
LONG = "\\\\?\\"


def lp(p):
    r"""给绝对路径加 \\?\ 前缀，绕开 MAX_PATH。"""
    if p.startswith(LONG):
        return p
    return LONG + os.path.abspath(p)


def natural(s):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def collect_images(src_dir):
    out = []
    base = lp(src_dir)
    for dp, dn, fn in os.walk(base):
        for f in fn:
            if f.lower().endswith(EXTS):
                out.append(os.path.join(dp, f))
    out.sort(key=lambda p: natural(p))
    return out


def do_one(args):
    adir, meta_path, meta = args
    doc = (meta.get("source") or {}).get("doc")
    if not doc:
        return "nodoc"
    src_dir = os.path.join(SRC, os.path.dirname(doc))
    if not os.path.isdir(lp(src_dir)):
        return "nosrc"

    imgs = collect_images(src_dir)
    if not imgs:
        return "noimg"

    dst_dir = os.path.join(adir, "images")
    os.makedirs(lp(dst_dir), exist_ok=True)

    names = []
    for i, src in enumerate(imgs, 1):
        ext = os.path.splitext(src)[1].lower()
        name = f"{i:02d}{ext}"
        dst = os.path.join(dst_dir, name)
        names.append(name)
        if os.path.exists(lp(dst)):
            continue
        try:
            shutil.copyfile(src, lp(dst))
        except Exception as e:
            print("copy fail", src, e, file=sys.stderr)

    assets = meta.setdefault("assets", {})
    assets["images"] = names
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)
    return f"ok:{len(names)}"


def main():
    jobs = []
    for y in sorted(os.listdir(LIB)):
        yd = os.path.join(LIB, y)
        if not os.path.isdir(yd):
            continue
        for name in sorted(os.listdir(yd)):
            adir = os.path.join(yd, name)
            mp = os.path.join(adir, "meta.json")
            if not os.path.exists(mp):
                continue
            m = json.load(open(mp, encoding="utf-8"))
            jobs.append((adir, mp, m))

    print(f"待处理 {len(jobs)} 期")
    stat = {}
    total_imgs = 0
    for i, j in enumerate(jobs, 1):
        r = do_one(j)
        key = r.split(":")[0]
        stat[key] = stat.get(key, 0) + 1
        if r.startswith("ok:"):
            total_imgs += int(r.split(":")[1])
        if i % 300 == 0:
            print(f"  {i}/{len(jobs)}  {stat}", flush=True)

    print("完成", stat, "图片总数", total_imgs)


if __name__ == "__main__":
    main()
