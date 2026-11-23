# -*- coding: utf-8 -*-
"""扫描源目录，把「文稿 + 音频段」组装成 Article。"""
import os
import re
import difflib
from dataclasses import dataclass, field
from typing import Optional

from . import naming


@dataclass
class AudioPart:
    path: str
    rel: str
    stem: str
    date: Optional[str]
    title: str
    title_norm: str
    series: Optional[str] = None
    part_label: Optional[str] = None
    part_no: Optional[int] = None
    is_episode: bool = False
    duration: float = 0.0
    size: int = 0


@dataclass
class DocFile:
    path: str
    rel: str
    date: Optional[str]
    title: str
    title_norm: str
    title_alt: str = ""
    title_alt_norm: str = ""
    year: Optional[int] = None
    category: Optional[str] = None


@dataclass
class Article:
    id: str
    date: Optional[str]
    title: str
    title_norm: str
    doc: Optional[DocFile] = None
    parts: list = field(default_factory=list)
    series: Optional[str] = None
    year: Optional[int] = None
    match_method: str = "none"

    @property
    def duration(self):
        return sum(p.duration for p in self.parts)

    @property
    def playable(self):
        return bool(self.parts)


SKIP_DOC = re.compile(r"(^~\$)|(^\.[_])|(^\._)")
BGM = re.compile(r"^.+?\s-\s.+$")          # 「艺人 - 曲名」视为背景音乐


def _iter(root, exts):
    for dp, dn, fn in os.walk(root):
        for f in fn:
            if f.lower().endswith(exts):
                yield os.path.join(dp, f)


def _split_date(stem, year):
    date, rest = naming.parse_date_prefix(stem)
    if date and date.startswith("?-") and year:
        date = f"{year}{date[1:]}"
    return date, rest.strip()


def scan_audio(root):
    out = []
    for path in _iter(root, (".mp3",)):
        base = os.path.basename(path)
        if BGM.match(base):
            continue
        rel = os.path.relpath(path, root)
        parts = rel.split(os.sep)
        year = parts[0] if parts[0].isdigit() and len(parts[0]) == 4 else None
        stem = os.path.splitext(os.path.basename(path))[0]
        date, rest = _split_date(stem, year)
        se = naming.extract_series(rest)
        title = rest
        if se["is_episode"]:
            m = naming.EPISODE_RE.match(rest)
            title = rest[m.end():].strip() if m else rest
        out.append(AudioPart(
            path=path, rel=rel, stem=stem, date=date, title=title,
            title_norm=naming.norm_title(title),
            series=se["series"], part_label=se["part_label"],
            part_no=se["part_no"], is_episode=se["is_episode"],
            size=os.path.getsize(path),
        ))
    return out


def scan_docs(root):
    out = []
    for path in _iter(root, (".doc", ".docx")):
        base = os.path.basename(path)
        if SKIP_DOC.search(base):
            continue
        rel = os.path.relpath(path, root)
        parts = rel.split(os.sep)
        year = None
        category = None
        for p in parts:
            if re.fullmatch(r"20\d{2}", p):
                year = int(p)
            if p in ("短篇", "长篇"):
                category = p
        folder = parts[-2] if len(parts) >= 2 else ""
        fdate, ftitle = _split_date(folder, year)
        fstem = os.path.splitext(base)[0]
        sdate, stitle = _split_date(fstem, year)
        date = fdate or sdate
        title = ftitle or stitle
        # 文件夹是「短篇」「长篇」这类分类名时，改用文件名
        if naming.norm_title(title) in ("短篇", "长篇", "其他", ""):
            title = stitle or title
        alt = stitle if naming.norm_title(stitle) != naming.norm_title(title) else ""
        out.append(DocFile(
            path=path, rel=rel, date=date, title=title,
            title_norm=naming.norm_title(title),
            title_alt=alt, title_alt_norm=naming.norm_title(alt) if alt else "",
            year=year, category=category,
        ))
    return out


def build_articles(root):
    audios = scan_audio(root)
    docs = scan_docs(root)

    arts = {}
    by_norm = {}
    for d in docs:
        a = Article(
            id=naming.make_id(d.date, d.title), date=d.date, title=d.title,
            title_norm=d.title_norm, doc=d, year=d.year,
        )
        arts[d.path] = a
        for k in filter(None, (d.title_norm, d.title_alt_norm)):
            by_norm.setdefault(k, []).append(a)

    def attach(audio, method):
        cands = by_norm.get(audio.title_norm)
        if not cands:
            return False
        # 同一年份优先
        best = cands[0]
        if len(cands) > 1 and audio.date:
            same = [c for c in cands if c.date and c.date[:4] == audio.date[:4]]
            if same:
                best = same[0]
        best.parts.append(audio)
        if best.match_method == "none":
            best.match_method = method
        return True

    orphans = []
    doc_norms = list(by_norm.keys())

    for a in audios:
        if attach(a, "exact"):
            continue
        if a.series:
            sn = naming.norm_title(a.series)
            cands = by_norm.get(sn)
            if cands:
                cands[0].parts.append(a)
                cands[0].series = a.series
                if cands[0].match_method == "none":
                    cands[0].match_method = "series"
                continue
        cand = difflib.get_close_matches(a.title_norm, doc_norms, n=1, cutoff=0.88)
        if cand:
            by_norm[cand[0]][0].parts.append(a)
            if by_norm[cand[0]][0].match_method == "none":
                by_norm[cand[0]][0].match_method = "fuzzy"
            continue
        if len(a.title_norm) >= 5:
            hit = None
            for k in doc_norms:
                if len(k) >= 5 and (a.title_norm in k or k in a.title_norm):
                    hit = k
                    break
            if hit:
                by_norm[hit][0].parts.append(a)
                if by_norm[hit][0].match_method == "none":
                    by_norm[hit][0].match_method = "contains"
                continue
        orphans.append(a)

    # 第二遍：同目录归属。文稿文件夹里的短名 mp3（如「土壤.mp3」）按位置归属，
    # 但只在该节目还没有任何音频时才用，避免和年份目录里的正式副本重复。
    doc_dirs = {}
    for art in arts.values():
        if art.doc:
            doc_dirs.setdefault(os.path.dirname(art.doc.path), []).append(art)
    still = []
    for a in orphans:
        cands = doc_dirs.get(os.path.dirname(a.path)) or []
        if len(cands) == 1:
            if not cands[0].parts:
                cands[0].parts.append(a)
                cands[0].match_method = "sibling"
            # 该节目已有音频 → 这是同一期的短名副本，丢掉
            continue
        still.append(a)
    orphans = still

    for a in orphans:
        art = Article(
            id=naming.make_id(a.date, a.title), date=a.date, title=a.title,
            title_norm=a.title_norm, series=a.series, match_method="no_doc",
            year=int(a.date[:4]) if a.date else None,
        )
        art.parts.append(a)
        arts[f"__orphan__{a.rel}"] = art

    for art in arts.values():
        if any(p.part_no is not None for p in art.parts):
            art.parts.sort(key=lambda p: (p.part_no is None, p.part_no or 0, p.path))
            for i, p in enumerate(art.parts):
                if p.part_no is None:
                    p.part_no = i
        else:
            art.parts.sort(key=lambda p: p.path)

    return list(arts.values()), audios, docs


def summarize(root):
    arts, audios, docs = build_articles(root)
    playable = [a for a in arts if a.playable]
    print(f"音频文件      : {len(audios)}")
    print(f"文稿文件      : {len(docs)}")
    print(f"Article 总数  : {len(arts)}  (可播放 {len(playable)})")
    methods = {}
    for a in arts:
        methods[a.match_method] = methods.get(a.match_method, 0) + 1
    print(f"匹配方式      : {methods}")
    multi = [a for a in playable if len(a.parts) > 1]
    print(f"多段节目      : {len(multi)} 篇，共 {sum(len(a.parts) for a in multi)} 段")
    for a in sorted(multi, key=lambda x: -len(x.parts))[:8]:
        print(f"   {len(a.parts):>3} 段  {a.title[:40]}  ({a.match_method})")
    nodes = [a for a in arts if not a.playable]
    print(f"无音频的文稿  : {len(nodes)}")
    for a in nodes[:6]:
        print(f"   {a.title[:50]}")
    no_doc = [a for a in playable if a.doc is None]
    print(f"无文稿的音频  : {len(no_doc)} 篇  ({no_doc[0].duration if no_doc else 0})")
    for a in no_doc[:8]:
        print(f"   {a.title[:50]}")
    return arts, audios, docs


if __name__ == "__main__":
    import sys
    root = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("HEECHO_SRC", os.path.join("library", "raw"))
    summarize(root)
