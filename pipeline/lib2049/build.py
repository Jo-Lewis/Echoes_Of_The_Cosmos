# -*- coding: utf-8 -*-
"""把一个 Article 落盘成库目录。"""
import json
import os
import re
import shutil
from datetime import datetime

from . import audio as audiomod
from . import doc97
from . import naming

SCHEMA_VERSION = 2
PIPELINE_VERSION = "0.1.0"

DEFAULT_SPEECH_CPM = 196.0


# ---------------------------------------------------------------- 时间映射

def silence_map(silences, duration):
    """返回 (map, total_silence)。map 为 [(speech_t, wall_start, wall_end)]。"""
    m = []
    cum = 0.0
    for s, e in silences:
        if s >= duration:
            break
        e = min(e, duration)
        m.append((s - cum, s, e))
        cum += (e - s)
    return m, cum


def speech_to_wall(t, m):
    """把「有效说话时间」映射回墙钟时间。"""
    wall = t
    for sp, s, e in m:
        if t <= sp:
            break
        if t < sp + (e - s):
            return s
        wall += (e - s)
    return wall


def assign_times(paragraphs, duration, silences):
    """按字数比例 + 静音补偿，给每段分配起止时间。"""
    total_chars = sum(len(p) for p in paragraphs) or 1
    m, total_sil = silence_map(silences, duration)
    speech_total = max(duration - total_sil, 1.0)

    cum = 0
    out = []
    for p in paragraphs:
        c0, c1 = cum, cum + len(p)
        t0 = speech_to_wall(c0 / total_chars * speech_total, m)
        t1 = speech_to_wall(c1 / total_chars * speech_total, m)
        out.append((int(t0 * 1000), int(max(t1, t0 + 0.3) * 1000)))
        cum = c1
    return out


# ---------------------------------------------------------------- 文本

def load_paragraphs(doc_path):
    raw = doc97.extract_any(doc_path)
    paras = doc97.blocks_from_text(raw)
    cjk = len(re.findall(r"[\u4e00-\u9fa5]", raw))
    return paras, cjk


def write_transcript(path, title, paragraphs, images):
    lines = [f"# {title}", ""]
    for i, p in enumerate(paragraphs):
        lines.append(p)
        lines.append("")
        if images and i < len(images):
            lines.append(f"![](images/{os.path.basename(images[i])})")
            lines.append("")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


def write_blocks(path, article_id, paragraphs, times, images, source):
    blocks = []
    for i, p in enumerate(paragraphs):
        t0, t1 = times[i] if i < len(times) else (None, None)
        blocks.append({"i": len(blocks), "type": "p", "text": p,
                       "start_ms": t0, "end_ms": t1})
        if images and i < len(images):
            blocks.append({"i": len(blocks), "type": "img",
                           "src": f"images/{os.path.basename(images[i])}",
                           "caption": os.path.splitext(os.path.basename(images[i]))[0],
                           "start_ms": t1})
    doc = {"article_id": article_id, "anchor_source": source,
           "chars": sum(len(p) for p in paragraphs), "blocks": blocks}
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=1)


# ---------------------------------------------------------------- 封面

_FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
]

SERIES_COLORS = [
    (74, 158, 255), (255, 138, 76), (94, 201, 160), (196, 120, 255),
    (255, 199, 76), (120, 200, 255), (255, 120, 140),
]


def _color_for(series):
    if not series:
        return SERIES_COLORS[0]
    return SERIES_COLORS[sum(ord(c) for c in series) % len(SERIES_COLORS)]


def make_cover(path, title, subtitle, date, duration_min, series=None, size=1080):
    from PIL import Image, ImageDraw, ImageFont

    font_path = next((f for f in _FONT_CANDIDATES if os.path.exists(f)), None)
    accent = _color_for(series)
    img = Image.new("RGB", (size, size), (18, 20, 26))
    d = ImageDraw.Draw(img)

    # 竖向渐变
    for y in range(size):
        k = y / size
        d.line([(0, y), (size, y)],
               fill=(int(18 + 22 * k), int(20 + 24 * k), int(28 + 30 * k)))

    # 顶部系列色带
    d.rectangle([0, 0, size, int(size * 0.012)], fill=accent)
    # 左下角装饰色块
    d.rectangle([0, size - 8, size, size], fill=accent)

    big = ImageFont.truetype(font_path, 96) if font_path else ImageFont.load_default()
    mid = ImageFont.truetype(font_path, 46) if font_path else ImageFont.load_default()
    small = ImageFont.truetype(font_path, 34) if font_path else ImageFont.load_default()

    if series and naming.norm_title(series) != naming.norm_title(title):
        d.text((72, 90), series, font=mid, fill=accent)

    # 标题自动换行；末尾只剩一两个字时缩字号重排
    max_w = size - 144
    fsize = 96
    while True:
        big = ImageFont.truetype(font_path, fsize) if font_path else ImageFont.load_default()
        lines, cur = [], ""
        for ch in title:
            if d.textlength(cur + ch, font=big) > max_w:
                lines.append(cur)
                cur = ch
            else:
                cur += ch
        if cur:
            lines.append(cur)
        if len(lines) > 1 and len(lines[-1]) <= 2 and fsize > 58:
            fsize -= 10
            continue
        if len(lines) > 6:
            fsize -= 10
            continue
        break

    lh = int(fsize * 1.24)
    total_h = len(lines) * lh
    y = (size - total_h) // 2 + 40
    for ln in lines:
        d.text((72, y), ln, font=big, fill=(245, 246, 250))
        y += lh

    if duration_min >= 90:
        span = f"{duration_min/60:.1f} 小时"
    else:
        span = f"{int(duration_min)} 分钟"
    meta = f"{date or '—'}   ·   {span}"
    d.text((72, size - 190), meta, font=small, fill=(150, 158, 175))

    img.save(path, quality=88)
    return path


# ---------------------------------------------------------------- 主流程

def _safe_stem(art):
    return naming.slugify_for_dir(art.date or "", art.title)


def _link_or_copy(src, dst):
    if os.path.exists(dst):
        return
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def _analyze_part(part):
    info = audiomod.probe(part.path)
    ana = audiomod.analyze(part.path)
    part.duration = info["duration"]
    return info, ana


def build_article(art, out_root, mode="link", overwrite=False, make_thumb=True):
    """把一个 Article 写进库。返回 dict 统计。"""
    if not art.parts:
        return {"id": art.id, "skipped": "no_audio"}

    year = art.date[:4] if art.date else (str(art.year) if art.year else "0000")
    adir = os.path.join(out_root, "shows", "2049", year, _safe_stem(art))
    if os.path.exists(adir) and not overwrite:
        return {"id": art.id, "skipped": "exists"}
    os.makedirs(adir, exist_ok=True)
    os.makedirs(os.path.join(adir, "images"), exist_ok=True)

    stats = {"id": art.id, "dir": adir, "parts": len(art.parts)}

    # 文稿
    paragraphs, cjk = ([], 0)
    if art.doc and art.doc.path.lower().endswith((".doc", ".docx")):
        try:
            paragraphs, cjk = load_paragraphs(art.doc.path)
            stats["chars"] = cjk
        except Exception as e:
            stats["doc_error"] = str(e)[:80]
            paragraphs = []

    multi = len(art.parts) > 1
    total_chars = sum(len(p) for p in paragraphs) or 1
    part_metas = []

    # 逐段分析
    infos = []
    for p in art.parts:
        info, ana = _analyze_part(p)
        infos.append((info, ana))
    total_dur = sum(i["duration"] for i, _ in infos) or 1

    blocks = []
    cum_chars = 0
    durs = [i["duration"] for i, _ in infos] or [1]
    med_dur = sorted(durs)[len(durs) // 2]
    for idx, (part, (info, ana)) in enumerate(zip(art.parts, infos)):
        # 合集判定：一个 3 小时以上的段，且远高于同组中位时长
        is_comp = len(art.parts) > 1 and info["duration"] > 3 * 3600 and info["duration"] > 3 * med_dur
        share = info["duration"] / total_dur
        char_start = cum_chars
        char_end = cum_chars + int(round(total_chars * share)) if idx < len(art.parts) - 1 else total_chars
        cum_chars = char_end

        if multi:
            pdir = os.path.join(adir, "parts", f"{idx:02d} {naming.sanitize(part.part_label or part.title, 40)}")
            os.makedirs(pdir, exist_ok=True)
            ext = os.path.splitext(part.path)[1].lower()
            audio_name = "audio" + ext
            _link_or_copy(part.path, os.path.join(pdir, audio_name))
            if make_thumb:
                audiomod.waveform(os.path.join(pdir, audio_name), os.path.join(pdir, "thumb.jpg"))
            pm = {
                "no": idx, "title": part.part_label or part.title,
                "part_label": part.part_label,
                "part_no": part.part_no, "audio": audio_name,
                "duration_sec": round(info["duration"], 1),
                "char_start": char_start, "char_end": char_end,
                "loudness_lufs": ana["lufs"], "gain_db": ana["gain_db"],
                "is_compilation": is_comp,
            }
            with open(os.path.join(pdir, "part.json"), "w", encoding="utf-8") as f:
                json.dump(pm, f, ensure_ascii=False, indent=1)
            part_metas.append(pm)
        else:
            ext = os.path.splitext(part.path)[1].lower()
            audio_name = "audio" + ext
            _link_or_copy(part.path, os.path.join(adir, audio_name))
            if make_thumb:
                audiomod.waveform(os.path.join(adir, audio_name), os.path.join(adir, "thumb.jpg"))

        # 该段的段落切片与时间锚点
        if paragraphs:
            seg = paragraphs[char_start:char_end]
            if seg:
                times = assign_times(seg, info["duration"], ana["silences"])
                for j, (p, (t0, t1)) in enumerate(zip(seg, times)):
                    blocks.append({"i": len(blocks), "type": "p", "text": p,
                                   "part": idx if multi else 0,
                                   "start_ms": t0, "end_ms": t1})

    # 正文
    if paragraphs:
        write_transcript(os.path.join(adir, "transcript.md"), art.title, paragraphs, [])
        with open(os.path.join(adir, "blocks.json"), "w", encoding="utf-8") as f:
            json.dump({"article_id": art.id, "anchor_source": "ratio+silence",
                       "chars": total_chars, "blocks": blocks}, f,
                      ensure_ascii=False, indent=1)

    # 封面
    make_cover(os.path.join(adir, "cover.jpg"), art.title, art.series or "",
               art.date, total_dur / 60, art.series)

    # 元数据
    total_sil = sum(len(a["silences"]) for _, a in infos)
    meta = {
        "id": art.id,
        "schema_version": SCHEMA_VERSION,
        "title": art.title,
        "date": art.date,
        "year": int(year) if year.isdigit() else None,
        "series": art.series,
        "duration_sec": round(total_dur, 1),
        "taxonomy": None,
        "keywords": [],
        "summary_one_line": "",
        "summary": "",
        "best_for": "",
        "hook": "",
        "assets": {
            "audio": None if multi else ("audio" + os.path.splitext(art.parts[0].path)[1].lower()),
            "cover": "cover.jpg",
            "thumb": None if multi else "thumb.jpg",
            "transcript": "transcript.md" if paragraphs else None,
            "blocks": "blocks.json" if paragraphs else None,
            "parts_dir": "parts" if multi else None,
            "images": [],
        },
        "parts": part_metas or None,
        "flags": {
            "has_transcript": bool(paragraphs),
            "text_source": "doc" if paragraphs else "none",
            "needs_asr": not paragraphs,
            "has_compilation": any(p.get("is_compilation") for p in part_metas),
        },
        "tech": {
            "duration_sec": round(total_dur, 1),
            "loudness_lufs": infos[0][1]["lufs"],
            "gain_db": infos[0][1]["gain_db"],
            "channels": infos[0][0]["channels"],
            "bitrate": infos[0][0]["bitrate"],
            "chars": sum(len(p) for p in paragraphs),
            "cjk": stats.get("chars", 0),
            "paragraphs": len(paragraphs),
            "silence_regions": total_sil,
            "speech_rate_cpm": round(stats.get("chars", 0) / (total_dur / 60), 1) if total_dur else None,
        },
        "derived": {},
        "locked": [],
        "match_method": art.match_method,
        "source": {"audio": [p.rel for p in art.parts],
                   "doc": art.doc.rel if art.doc else None},
        "pipeline": {"version": PIPELINE_VERSION,
                     "built_at": datetime.now().isoformat(timespec="seconds")},
    }
    with open(os.path.join(adir, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)

    return stats
