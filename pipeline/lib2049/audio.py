# -*- coding: utf-8 -*-
"""ffmpeg 封装：探测、响度测量、静音检测、可选转码。"""
import json
import os
import re
import subprocess

FFMPEG = os.environ.get("FFMPEG_BIN", "ffmpeg")
FFPROBE = os.environ.get("FFPROBE_BIN", "ffprobe")


def available():
    try:
        subprocess.run([FFMPEG, "-version"], capture_output=True, timeout=20)
        subprocess.run([FFPROBE, "-version"], capture_output=True, timeout=20)
        return True
    except Exception:
        return False


def _run(cmd, timeout=1800):
    p = subprocess.run(cmd, capture_output=True, text=True,
                       encoding="utf-8", errors="replace", timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def probe(path):
    """返回 {duration, bitrate, sample_rate, channels, codec}。"""
    rc, out, err = _run([
        FFPROBE, "-v", "error", "-show_entries",
        "format=duration,bit_rate:stream=codec_name,sample_rate,channels",
        "-of", "json", path,
    ], timeout=60)
    if rc != 0:
        raise RuntimeError(f"ffprobe failed: {err[:200]}")
    d = json.loads(out)
    fmt = d.get("format", {})
    st = (d.get("streams") or [{}])[0]
    return {
        "duration": float(fmt.get("duration") or 0),
        "bitrate": int(fmt.get("bit_rate") or 0),
        "codec": st.get("codec_name"),
        "sample_rate": int(st.get("sample_rate") or 0),
        "channels": int(st.get("channels") or 0),
    }


_JSON_RE = re.compile(r"\{[^{}]*\"input_i\"[^{}]*\}", re.S)


def measure_loudness(path, target_i=-16.0, target_tp=-1.5):
    """单遍 loudnorm 分析（不解码输出），返回 LUFS / 真峰值 / 建议增益。"""
    rc, out, err = _run([
        FFMPEG, "-hide_banner", "-nostats", "-i", path,
        "-af", f"loudnorm=I={target_i}:TP={target_tp}:LRA=11:print_format=json",
        "-f", "null", "-",
    ])
    m = _JSON_RE.search(err)
    if not m:
        return None
    try:
        d = json.loads(m.group(0))
        lufs = float(d["input_i"])
        tp = float(d["input_tp"])
    except Exception:
        return None
    gain = target_i - lufs
    gain = min(gain, target_tp - tp)          # 防削波
    gain = max(min(gain, 15.0), -15.0)
    return {"lufs": round(lufs, 2), "true_peak": round(tp, 2), "gain_db": round(gain, 2)}


_EBU_I = re.compile(r"I:\s*(-?\d+(?:\.\d+)?)\s*LUFS")
_EBU_PEAK = re.compile(r"Peak:\s*(-?\d+(?:\.\d+)?)\s*dBFS")


def analyze(path, target_i=-16.0, noise="-35dB", min_dur=0.45):
    """一遍解码同时拿到响度（EBU R128）与静音区间。

    loudnorm 滤波只有 ~17x 实时，ebur128 有 ~120x，所以测量用 ebur128，
    增益离线算好，播放时应用。
    """
    rc, out, err = _run([
        FFMPEG, "-hide_banner", "-nostats", "-i", path,
        "-af", (f"silencedetect=noise={noise}:d={min_dur},"
                "ebur128=peak=true"),
        "-f", "null", "-",
    ])
    res = {"silences": [], "lufs": None, "true_peak": None, "gain_db": None}
    starts = [float(x) for x in _SIL_START.findall(err)]
    ends = [float(x) for x in _SIL_END.findall(err)]
    res["silences"] = list(zip(starts, ends))
    mi = _EBU_I.findall(err)
    mp = _EBU_PEAK.findall(err)
    if mi:
        lufs = float(mi[-1])
        tp = float(mp[-1]) if mp else 0.0
        gain = min(target_i - lufs, -1.5 - tp)
        res.update(lufs=round(lufs, 2), true_peak=round(tp, 2),
                   gain_db=round(max(min(gain, 15.0), -15.0), 2))
    return res


_SIL_START = re.compile(r"silence_start:\s*([0-9.]+)")
_SIL_END = re.compile(r"silence_end:\s*([0-9.]+)")


def detect_silences(path, noise="-35dB", min_dur=0.45):
    """返回 [(start_s, end_s), ...]"""
    rc, out, err = _run([
        FFMPEG, "-hide_banner", "-nostats", "-i", path,
        "-af", f"silencedetect=noise={noise}:d={min_dur}", "-f", "null", "-",
    ])
    starts = [float(x) for x in _SIL_START.findall(err)]
    ends = [float(x) for x in _SIL_END.findall(err)]
    return list(zip(starts, ends))


def transcode(src, dst, bitrate="96k", apply_loudnorm=True, target_i=-16.0):
    """单声道 AAC 转码；dst 用 .m4a。"""
    af = f"loudnorm=I={target_i}:TP=-1.5:LRA=11" if apply_loudnorm else "anull"
    rc, out, err = _run([
        FFMPEG, "-hide_banner", "-nostats", "-y", "-i", src,
        "-ac", "1", "-c:a", "aac", "-b:a", bitrate,
        "-af", af, "-movflags", "+faststart", dst,
    ])
    if rc != 0:
        raise RuntimeError(f"transcode failed: {err[-300:]}")
    return os.path.getsize(dst)


def waveform(src, dst, size="640x360"):
    """波形缩略图。先重采样到 4kHz，否则长节目会吃掉几分钟。"""
    rc, out, err = _run([
        FFMPEG, "-hide_banner", "-nostats", "-y", "-i", src,
        "-filter_complex",
        f"aformat=channel_layouts=mono,aresample=4000,showwavespic=s={size}:colors=#4a9eff",
        "-frames:v", "1", dst,
    ])
    return rc == 0
