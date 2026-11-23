# -*- coding: utf-8 -*-
"""命名、日期、系列解析。"""
import re
import hashlib

ILLEGAL = str.maketrans({
    "\\": "＼", "/": "／", ":": "：", "*": "＊", "?": "？",
    '"': "＂", "<": "＜", ">": "＞", "|": "｜",
})

CN_DIGIT = {"零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
            "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}


def cn_to_int(s):
    """把 '二十' / '十二' / '三' 转成整数，失败返回 None。"""
    s = s.strip()
    if not s:
        return None
    if s.isdigit():
        return int(s)
    if "十" in s:
        head, _, tail = s.partition("十")
        tens = CN_DIGIT.get(head, 1) if head else 1
        ones = CN_DIGIT.get(tail, 0) if tail else 0
        return tens * 10 + ones
    total = 0
    for ch in s:
        if ch not in CN_DIGIT:
            return None
        total = total * 10 + CN_DIGIT[ch]
    return total


def sanitize(name, maxlen=60):
    """文件名 / 目录名安全化。"""
    s = name.translate(ILLEGAL)
    s = re.sub(r"\s+", " ", s).strip().rstrip(". ")
    if len(s) > maxlen:
        h = hashlib.md5(s.encode("utf-8")).hexdigest()[:4]
        s = s[: maxlen - 5].rstrip() + "-" + h
    return s


PUNCT = re.compile(r"[\s\u3000\-_·、，,。！!？?（）()【】\[\]：:；;“”\"'‘’《》〈〉~～—]+")


def norm_title(s):
    """去掉扩展名、日期前缀、全部标点与空白，用于模糊比对。"""
    s = re.sub(r"\.(mp3|doc|docx|m4a|wav)$", "", s, flags=re.I)
    s = re.sub(r"^~?\$", "", s)
    s = PUNCT.sub("", s)
    return s.strip()


def parse_date_prefix(stem):
    """返回 (date_str|None, rest)。支持 YYMMDD 与 MMDD 两种前缀。"""
    m = re.match(r"^(\d{6})(?!\d)", stem)
    if m:
        yy, mm, dd = int(m.group(1)[:2]), int(m.group(1)[2:4]), int(m.group(1)[4:])
        if 10 <= yy <= 30 and 1 <= mm <= 12 and 1 <= dd <= 31:
            return f"20{yy:02d}-{mm:02d}-{dd:02d}", stem[6:]
    m = re.match(r"^(\d{4})(?!\d)", stem)
    if m:
        mm, dd = int(m.group(1)[:2]), int(m.group(1)[2:])
        if 1 <= mm <= 12 and 1 <= dd <= 31:
            return f"?-{mm:02d}-{dd:02d}", stem[4:]
    return None, stem


SERIES_RE = re.compile(r"【([^】]{1,30})】")
PART_RE = re.compile(r"第([零〇一二三四五六七八九十两\d]{1,4})([幕集部篇讲章回])")
EPISODE_RE = re.compile(r"^S(\d{1,2})E(\d{1,3})", re.I)


def extract_series(stem):
    """抽系列信息。返回 dict(series=None|str, part_label, part_no, is_episode)。"""
    out = {"series": None, "part_label": None, "part_no": None, "is_episode": False}

    m = EPISODE_RE.match(stem)
    if m:
        out["is_episode"] = True
        out["series"] = f"S{int(m.group(1)):02d}"
        out["part_no"] = int(m.group(2))
        out["part_label"] = stem[m.end():].strip()
        return out

    m = SERIES_RE.search(stem)
    if m:
        out["series"] = m.group(1).strip()
        tail = stem[m.end():].strip()

        pm = PART_RE.search(tail)
        if pm:
            out["part_no"] = cn_to_int(pm.group(1))
            out["part_label"] = tail
        else:
            lead = re.match(r"^(\d{1,3})(?=[^\d]|$)", stem)
            if lead and not stem.startswith("0" + lead.group(1)):
                out["part_no"] = int(lead.group(1))
            out["part_label"] = tail or None
        return out

    pm = PART_RE.search(stem)
    if pm:
        out["part_no"] = cn_to_int(pm.group(1))
        out["part_label"] = pm.group(0)

    return out


def make_id(date_str, title):
    d = (date_str or "0000-00-00").replace("-", "")
    if d.startswith("0000"):
        h = hashlib.md5(title.encode("utf-8")).hexdigest()[:6]
        return f"2049-undated-{h}"
    return f"2049-{d[:4]}-{d[4:]}"


def slugify_for_dir(date_str, title, seq=None):
    """目录名：YYYY-MM-DD 标题"""
    base = f"{date_str} {title}" if date_str else title
    base = sanitize(base, 70)
    if seq:
        base += f"-{seq}"
    return base
