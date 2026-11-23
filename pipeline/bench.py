# -*- coding: utf-8 -*-
"""基准测试：解码分析 vs 转码，在本机的真实速度。"""
import os
import sys
import time
import statistics

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from lib2049 import audio
from lib2049 import inventory

ROOT = os.environ.get("HEECHO_SRC", os.path.join("library", "raw"))


def pick(n=10, max_min=45):
    """按时长分档抽样，排除超长合集（单独测）。"""
    from mutagen.mp3 import MP3
    items = []
    for p in inventory._iter(ROOT, (".mp3",)):
        if "文字版全集" in p:
            continue
        try:
            d = MP3(p).info.length
        except Exception:
            continue
        if d > max_min * 60:
            continue
        items.append((d, p))
    items.sort()
    idx = [int(i * (len(items) - 1) / (n - 1)) for i in range(n)]
    return [items[i][1] for i in idx]


def main():
    print("ffmpeg ok:", audio.available())
    files = pick(12)
    print(f"样本 {len(files)} 个\n")

    total_audio = 0.0
    total_analyze = 0.0
    rows = []
    for p in files:
        info = audio.probe(p)
        dur = info["duration"]
        t0 = time.perf_counter()
        r = audio.analyze(p)
        dt = time.perf_counter() - t0
        total_audio += dur
        total_analyze += dt
        rows.append((os.path.basename(p), dur, dt, r))
        print(f"{dur/60:5.1f}min  分析 {dt:5.2f}s  {dur/dt:6.1f}x  "
              f"LUFS {r['lufs']}  TP {r['true_peak']}  gain {r['gain_db']:+}  "
              f"静音 {len(r['silences'])}段  {os.path.basename(p)[:32]}")

    print(f"\n解码分析：{total_audio/60:.1f} 分钟音频 / {total_analyze:.1f} 秒 "
          f"= {total_audio/total_analyze:.1f}x 实时")
    lib_hours = 864.5
    print(f"外推全库 864.5 小时：单进程 {lib_hours*3600/(total_audio/total_analyze)/3600:.1f} 小时")

    # 转码测 3 个
    print("\n—— 转码基准 ——")
    t_audio = t_time = 0.0
    outdir = os.path.join(os.environ.get("TEMP", "."), "2049bench")
    os.makedirs(outdir, exist_ok=True)
    for p in files[::4][:3]:
        info = audio.probe(p)
        dst = os.path.join(outdir, "t.m4a")
        t0 = time.perf_counter()
        audio.transcode(p, dst)
        dt = time.perf_counter() - t0
        t_audio += info["duration"]
        t_time += dt
        print(f"{info['duration']/60:5.1f}min  转码 {dt:5.2f}s  {info['duration']/dt:6.1f}x  "
              f"{os.path.getsize(p)/1e6:.1f}MB -> {os.path.getsize(dst)/1e6:.1f}MB")
    if t_time:
        print(f"\n转码：{t_audio/60:.1f} 分钟音频 / {t_time:.1f} 秒 = {t_audio/t_time:.1f}x 实时")
        print(f"外推全库 864.5 小时：单进程 {lib_hours*3600/(t_audio/t_time)/3600:.1f} 小时")


if __name__ == "__main__":
    main()
