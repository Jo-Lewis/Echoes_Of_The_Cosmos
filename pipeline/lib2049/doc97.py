# -*- coding: utf-8 -*-
"""Word 97-2003 (.doc) 文本提取。OLE 复合文档 + piece table，不依赖 Office。"""
import struct

import olefile


def _parse_clx(table, fc_clx, lcb_clx):
    clx = table[fc_clx:fc_clx + lcb_clx]
    i = 0
    while i < len(clx) and clx[i] == 0x01:          # Prc
        cb = struct.unpack_from("<h", clx, i + 1)[0]
        i += 3 + cb
    if i >= len(clx) or clx[i] != 0x02:             # Pcdt
        raise ValueError("no piece table")
    lcb = struct.unpack_from("<I", clx, i + 1)[0]
    plc = clx[i + 5:i + 5 + lcb]
    n = (lcb - 4) // 12
    cps = struct.unpack_from("<%dI" % (n + 1), plc, 0)
    return cps, plc[4 * (n + 1):]


def extract(path):
    ole = olefile.OleFileIO(path)
    try:
        wd = ole.openstream("WordDocument").read()
        flags = struct.unpack_from("<H", wd, 0x0A)[0]
        tbl = "1Table" if (flags & 0x0200) else "0Table"
        table = ole.openstream(tbl).read()

        fc_clx, lcb_clx = struct.unpack_from("<II", wd, 0x01A2)
        cps, pcds = _parse_clx(table, fc_clx, lcb_clx)

        out = []
        for k in range(len(cps) - 1):
            ln = cps[k + 1] - cps[k]
            if ln <= 0:
                continue
            fc = struct.unpack_from("<I", pcds, k * 8 + 2)[0]
            compressed = bool(fc & 0x40000000)
            fc &= 0x3FFFFFFF
            if compressed:
                out.append(wd[fc // 2: fc // 2 + ln].decode("cp1252", errors="replace"))
            else:
                out.append(wd[fc: fc + ln * 2].decode("utf-16-le", errors="replace"))
        text = "".join(out)
    finally:
        ole.close()

    text = (text.replace("\r", "\n").replace("\x0b", "\n")
                .replace("\x07", "\n").replace("\x0c", "\n").replace("\x1e", "-"))
    return "".join(ch for ch in text if ch >= " " or ch in "\n\t")


def docx_text(path):
    """docx 正文提取（不需要 python-docx）。"""
    import zipfile
    from xml.etree import ElementTree as ET

    W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
    with zipfile.ZipFile(path) as z:
        try:
            xml = z.read("word/document.xml")
        except KeyError:
            return ""
    root = ET.fromstring(xml)
    lines = []
    for p in root.iter(W + "p"):
        line = "".join((t.text or "") for t in p.iter(W + "t")).strip()
        if line:
            lines.append(line)
    return "\n".join(lines)


def extract_any(path):
    """按扩展名分发。"""
    low = path.lower()
    if low.endswith(".docx"):
        return docx_text(path)
    if low.endswith(".doc"):
        return extract(path)
    raise ValueError(f"unsupported: {path}")


def paragraphs(text, min_len=8):
    """切成自然段，去掉空行和过短的行。"""
    out = []
    for line in text.split("\n"):
        line = line.strip().strip("\u3000")
        if len(line) >= min_len:
            out.append(line)
    return out


_SENT = __import__("re").compile(r"(?<=[。！？!?；;])")
_CLAUSE = __import__("re").compile(r"(?<=[，,、：:])")


def blocks_from_text(text, target=60, max_len=140, min_len=6):
    """把逐字稿切成适合滚动显示的块。

    实测文稿是「一句一行」，且很多没有标点。策略：
    1. 超长行先按句末标点断，仍过长再按逗号断
    2. 短行按顺序合并，攒够 target 字收一块（断点落在原稿换行处 = 自然停顿）
    """
    lines = [l.strip().strip("\u3000") for l in text.split("\n")]
    lines = [l for l in lines if len(l) >= min_len]

    pieces = []
    for l in lines:
        if len(l) <= max_len:
            pieces.append(l)
            continue
        for p in [x for x in _SENT.split(l) if x.strip()]:
            if len(p) <= max_len:
                pieces.append(p.strip())
                continue
            for q in [x for x in _CLAUSE.split(p) if x.strip()]:
                while len(q) > max_len:
                    pieces.append(q[:max_len])
                    q = q[max_len:]
                if q.strip():
                    pieces.append(q.strip())

    out, buf = [], ""
    for p in pieces:
        if buf and len(buf) >= target:
            out.append(buf)
            buf = ""
        buf += p
    if buf:
        out.append(buf)
    return out
