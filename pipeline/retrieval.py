# -*- coding: utf-8 -*-
"""检索原型：本地召回 + 两次 LLM（意图解析 / 精排）。

先跑通再做 App——这一层是产品成败的地方，值得在 Python 里调透。
"""
import json
import glob
import math
import os
import re
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lib2049 import tagging

ROOT = os.environ.get("ECHOES_OUT", os.path.join("library", "build"))

CJK = re.compile(r"[\u4e00-\u9fa5]+")


def bigrams(s):
    out = set()
    for seg in CJK.findall(s or ""):
        for i in range(len(seg) - 1):
            out.add(seg[i:i + 2])
        if len(seg) == 1:
            out.add(seg)
    return out


class Library:
    def __init__(self, root=ROOT):
        self.items = []
        for p in glob.glob(os.path.join(root, "shows", "2049", "*", "*", "meta.json")):
            m = json.load(open(p, encoding="utf-8"))
            if not (m.get("taxonomy") or {}).get("humor"):
                continue
            self.items.append(m)
        self.n = len(self.items)
        self.post = defaultdict(set)
        self.df = defaultdict(int)
        self.dlen = []
        for i, m in enumerate(self.items):
            terms = bigrams(self._text(m))
            self.dlen.append(max(1, len(terms)))
            for t in terms:
                self.post[t].add(i)
                self.df[t] += 1
        self._idf_cache = {}

    # ---------- 索引 ----------
    @staticmethod
    def _text(m):
        t = m.get("taxonomy") or {}
        parts = [m.get("title", ""), " ".join(t.get("topics") or []),
                 " ".join(m.get("keywords") or []), m.get("summary", ""),
                 m.get("summary_one_line", ""), " ".join(t.get("domain") or [])]
        return " ".join(parts)

    def idf(self, term):
        v = self._idf_cache.get(term)
        if v is None:
            df = self.df.get(term, 0)
            v = math.log(1 + self.n / (df + 1))
            self._idf_cache[term] = v
        return v

    # ---------- 召回 ----------
    def lexical(self, query):
        """BM25-lite：idf 加权 + 文档长度归一，避免长短文档同分并列。"""
        qs = bigrams(query)
        if not qs:
            return {}
        scores = defaultdict(float)
        for t in qs:
            ids = self.post.get(t)
            if not ids:
                continue
            w = self.idf(t)
            for i in ids:
                scores[i] += w / math.sqrt(self.dlen[i])
        mx = max(scores.values()) if scores else 1
        return {i: s / mx for i, s in scores.items()}

    @staticmethod
    def tag_score(m, it):
        t = m["taxonomy"]
        d = m.get("derived") or {}
        s = 0.0
        wsum = 0.0

        if it.get("domain"):
            wsum += 0.18
            hit = len(set(it["domain"]) & set(t.get("domain") or []))
            s += 0.18 * (hit / max(1, len(it["domain"])))
        if it.get("content_type"):
            wsum += 0.18
            s += 0.18 if t.get("content_type") == it["content_type"] else 0.0
        if it.get("tone"):
            wsum += 0.20
            mine = {t.get("tone_primary"), t.get("tone_secondary")} - {""}
            hit = len(set(it["tone"]) & mine)
            s += 0.20 * min(1.0, hit / max(1, len(it["tone"])))
        if it.get("humor_min"):
            wsum += 0.16
            s += 0.16 * max(0.0, min(1.0, (t.get("humor", 3) - it["humor_min"] + 1) / 2))
        if it.get("load_max"):
            wsum += 0.14
            s += 0.14 if t.get("cognitive_load", 3) <= it["load_max"] else 0.0
        if it.get("level_max"):
            wsum += 0.14
            s += 0.14 if t.get("level", 2) <= it["level_max"] else 0.0
        return s / wsum if wsum else 0.0

    def filter_ok(self, m, it):
        d = m.get("duration_sec") or 0
        if it.get("duration_min") and d < it["duration_min"]:
            return False
        if it.get("duration_max") and d > it["duration_max"]:
            return False
        if it.get("level_max") and m["taxonomy"].get("level", 2) > it["level_max"]:
            return False
        if it.get("year_min") and (m.get("year") or 0) < it["year_min"]:
            return False
        if it.get("sleep") and not (m.get("derived") or {}).get("sleep_friendly"):
            return False
        for bad in it.get("exclude") or []:
            if bad and bad in (m.get("title", "") + " " + " ".join(m["taxonomy"].get("topics") or [])):
                return False
        return True

    @staticmethod
    def topic_hit(m, topics):
        """查询主题在标题/主题词/关键词里的命中率。"""
        if not topics:
            return 0.0
        t = m.get("taxonomy") or {}
        hay = [x for x in (t.get("topics") or []) + (m.get("keywords") or []) if x]
        title = m.get("title", "")
        title_t = t.get("topics") or []
        hit = 0.0
        for q in topics:
            if q in title or q in hay:
                hit += 1.0
            elif any(len(x) >= 2 and (q in x or x in q) for x in hay):
                hit += 0.6
            elif len(q) >= 2 and q in "".join(title_t):
                hit += 0.4
        return min(1.0, hit / len(topics))

    def recall(self, query, it=None, k=40):
        it = it or {}
        topics = it.get("topics") or []
        lex = self.lexical(query)
        w_lex, w_tag, w_topic = 0.45, 0.25, 0.30
        if not topics:
            w_lex, w_tag = 0.55, 0.45
            w_topic = 0.0
        scored = []
        for i, m in enumerate(self.items):
            if not self.filter_ok(m, it):
                continue
            lx = lex.get(i, 0.0)
            tg = self.tag_score(m, it)
            th = self.topic_hit(m, topics)
            sc = w_lex * lx + w_tag * tg + w_topic * th
            if sc > 0.02:
                scored.append((sc, i))
        scored.sort(reverse=True)
        if not scored:
            # 只有时长/年份这类硬约束、本地分全为 0 时，按"接近约束中心"兜底
            pool = [(self.items[i], 0.001) for i in range(len(self.items))
                    if self.filter_ok(self.items[i], it)]
            pool.sort(key=lambda x: -x[0].get("duration_sec", 0))
            return pool[:k]
        return [(self.items[i], s) for s, i in scored[:k]]


# --------------------------------------------------------------- LLM

INTENT_SYS = """你是音频点播助手的查询理解模块。把用户的一句话翻译成检索条件。

**重要**：如果用户只说了情绪、场景、时长（"轻松有趣的""睡前听""短一点"），
`topics` 和 `domain` 就必须留空——不要为了填满字段而编造主题词。
编出来的"轻松科普""趣味知识"会污染关键词召回。

字段：
- topics: 只填用户明确提到的**具体主题**（火星、鲁迅、脱欧、咖啡），否则 []。2-5 个
- domain: 只在用户说了学科时才填（"讲点物理""历史类的"），否则 []
  选项：[天文,物理,化学,生物,医学,地球科学,数学,计算机,工程,军事,历史,人文社科,哲学,杂项]
- content_type: 只在用户明确要类型时填（"想听个故事"→历史叙事/人物传记），否则 ""
  选项：[科普讲解,人物传记,历史叙事,时事评论,答疑互动,实验演示,系列专题,杂谈漫聊]
- tone: 情绪，0-2 个，从 [好奇,平静,震撼,沉痛,热血,怀旧,轻松,紧张]
- humor_min: 1-5，想要多有趣（"轻松有趣"=4，"严肃"=2），没说就 0
- load_max: 1-5，能接受的动脑程度（"不想动脑/不费脑"=2，"随便"=3），没说就 0
- level_max: 1-3，知识门槛（"入门/小白"=1），没说就 0
- year_min: 4 位年份，用户说了"2024 年之后"这类时间限制时才填，否则 0
- duration_min / duration_max: 秒（"十几分钟"=600/1200，"一小时"=3000/4200），没说就 0
- sleep: true/false，是否明确要睡前的
- exclude: 不想要的内容
- want: 一句话复述用户意图

只输出 JSON。"""


def parse_intent(query, key):
    payload = {"model": tagging.MODEL,
               "messages": [{"role": "system", "content": INTENT_SYS},
                            {"role": "user", "content": query}],
               "response_format": {"type": "json_object"},
               "temperature": 0.2, "max_tokens": 400,
               "thinking": {"type": "disabled"}}
    r = tagging._post(payload, key)
    return json.loads(r["choices"][0]["message"]["content"])


RERANK_SYS = """你是音频点播的选片员。给用户的一句话和一批候选节目，选出最该播的那一期。

判断顺序：

1. **主题匹配**（最重要）：只有**标题或 topics 字段**里包含这个主题，才算"讲了"。摘要里顺带提一句、举例时带上，都**不算**。宁可说"没有"，也不要选一期只是提到过的。
2. **如果没有任何候选真正讲了这个主题**：选最接近的一期，并在 reason 里如实说明"没有专门讲 X 的，这期最接近"。
3. **风格匹配**：情绪、趣味度、深浅、节奏是否符合用户此刻的状态。
4. **硬约束**：用户给了时长范围就必须满足；满足不了要在 reason 里说明。

输出 JSON：
{"pick": "<序号>", "reason": "≤25字，说清为什么选它", "queue": ["序号","序号"], "confidence": 0.0-1.0}

pick 和 queue 都填**序号数字**。queue 给 3-6 个相关的，要多样，不要全是一个系列。只输出 JSON。"""


def card(m, i):
    t = m["taxonomy"]
    d = m.get("derived") or {}
    return (f'{i}. {m["title"]} | {round((m.get("duration_sec") or 0)/60)}分钟 '
            f'| {t.get("content_type")} | {"".join(t.get("domain") or [])} '
            f'| L{t.get("level")} 动脑{t.get("cognitive_load")} 趣味{t.get("humor")} '
            f'| {t.get("tone_primary")} | {"/".join((t.get("topics") or [])[:4])} '
            f'| {m.get("summary_one_line","")}')


def rerank(query, cands, intent, key):
    listing = "\n".join(card(m, i + 1) for i, (m, _) in enumerate(cands))
    user = f"用户说：{query}\n意图：{intent.get('want','')}\n\n候选（序号 = 下标+1）：\n{listing}"
    payload = {"model": tagging.MODEL,
               "messages": [{"role": "system", "content": RERANK_SYS},
                            {"role": "user", "content": user}],
               "response_format": {"type": "json_object"},
               "temperature": 0.2, "max_tokens": 500,
               "thinking": {"type": "disabled"}}
    r = tagging._post(payload, key)
    d = json.loads(r["choices"][0]["message"]["content"])
    idx = d.get("pick", "")
    try:
        k = int(str(idx).strip(" .[]")) - 1
        pick = cands[k][0] if 0 <= k < len(cands) else cands[0][0]
    except Exception:
        pick = cands[0][0]
    return pick, d.get("reason", ""), d.get("queue", []), d.get("confidence", 0)


def search(lib, query, key, k=40):
    it = parse_intent(query, key)
    cands = lib.recall(query, it, k=k)
    if not cands:
        return None, None, it, []
    pick, reason, queue, conf = rerank(query, cands, it, key)
    return pick, reason, it, cands


if __name__ == "__main__":
    lib = Library()
    print(f"库内已打标 {lib.n} 期")
    q = sys.argv[1] if len(sys.argv) > 1 else "来一期轻松有趣的，大概十几分钟"
    key = os.environ.get("DEEPSEEK_API_KEY", "")
    pick, reason, it, cands = search(lib, q, key)
    print("意图:", json.dumps(it, ensure_ascii=False))
    print("候选 top5:")
    for m, s in cands[:5]:
        print(f"  {s:.3f} {m['title'][:34]} {round(m['duration_sec']/60)}min")
    print(f"\n选中: {pick['title']}  ({round(pick['duration_sec']/60)}分钟)")
    print(f"理由: {reason}")
