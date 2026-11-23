# -*- coding: utf-8 -*-
"""打标：把一期文稿变成结构化元数据。

不依赖 openai SDK，直接用 urllib 调 OpenAI 兼容接口。
"""
import json
import os
import re
import ssl
import time
import urllib.error
import urllib.request

API_BASE = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
MODEL = os.environ.get("DEEPSEEK_MODEL", "deepseek-flash")

DOMAIN = ["天文", "物理", "化学", "生物", "医学", "地球科学", "数学",
          "计算机", "工程", "军事", "历史", "人文社科", "哲学", "杂项"]
CONTENT_TYPE = ["科普讲解", "人物传记", "历史叙事", "时事评论",
                "答疑互动", "实验演示", "系列专题", "杂谈漫聊"]
TONE = ["好奇", "平静", "震撼", "沉痛", "热血", "怀旧", "轻松", "紧张"]

SYSTEM = f"""你是中文科普音频节目的内容标注员。给你一期节目的标题和逐字稿，输出结构化标签。

## 最重要的一条

**这些标签是用来做检索的，不是用来夸节目的。** 如果某个值你给 80% 的节目都打上，它就失去意义了。判断每个维度时，请先在心里和其他节目比一比：**这期在这一点上，是明显高于平均、还是低于平均？**

## 输出字段

### 分类（客观，选最贴切的）

- `domain`：一级学科，**最多 2 个**。从：{"、".join(DOMAIN)}
- `topics`：二级主题词，2-6 个，要写用户真会搜的词（"火星""青蒿素""脱欧"），**禁止**"科学""知识""人类""世界""发展"这类空泛词
- `content_type`：从：{"、".join(CONTENT_TYPE)} 里选 1 个

### 刻度（1-5，必须拉开差距）

- `humor` 幽默程度：1=全程严肃 · 2=偶有轻松 · 3=比较轻松 · 4=经常有梗 · 5=全程段子
- `narrative` 叙事性：1=纯讲解 · 2=少量举例 · 3=有主线故事 · 4=强叙事 · 5=完全靠故事推进
- `intensity` 情绪强度：1=很平 · 2=平缓 · 3=中等 · 4=有冲击 · 5=强烈（沉痛、震撼、热血都算高）
- `level` 知识门槛：1=完全零基础 · 2=有点常识更顺 · 3=需要相关领域背景
- `cognitive_load` 认知负荷：1=可以一边做别的事 · 2=偶尔想一下 · 3=要跟住逻辑 · 4=要停下来消化 · 5=得反复听
- `pace` 语速节奏：慢 / 中 / 快

> `level` 和 `cognitive_load` **独立判断**。讲黑洞可以很硬核（level 3）但完全不费脑（load 1）；讲博弈论可以很入门（level 1）但很费脑（load 4）。

### 基调（只选最突出的）

- `tone_primary`：**必须且只能选 1 个**，从：{"、".join(TONE)}
- `tone_secondary`：可选 1 个，**没有就留空字符串，不要硬凑**
- 注意"好奇"是科普节目的默认情绪，**只有当这期确实在制造悬念、追问时才选它**；如果这期主要让人平静或震撼，就选那个

### 文本（给人看的，也是检索的主力）

- `keywords`：4-8 个，按"用户会不会搜"排序
- `summary_one_line`：≤25 字，像人话，不要"本期节目……"
- `summary`：80-120 字，写清"讲了什么、怎么讲的"，具体到人名、事例、结论
- `best_for`：≤20 字，"什么时候适合听这期"
- `hook`：≤30 字，这期最抓人的点

## 输出格式（只输出 JSON）

{{"domain":[],"topics":[],"content_type":"","humor":3,"narrative":2,"intensity":2,"level":2,"cognitive_load":3,"pace":"中","tone_primary":"","tone_secondary":"","keywords":[],"summary_one_line":"","summary":"","best_for":"","hook":""}}"""


# --- 少样本：校准刻度，防止全挤在中间值 -------------------------------------
FEWSHOT = [
    {
        "u": "标题：多普勒效应与宇宙学红移\n日期：2017-04-08    时长：6 分钟\n系列：无\n\n逐字稿：\n"
             "从多普勒1842年解释双星颜色变化讲起，用消防车鸣笛、火车小号手说明波源与观察者"
             "相对运动如何改变频率，再延伸到天文学：通过恒星光谱的红移蓝移寻找系外行星……",
        "a": {"domain": ["天文", "物理"], "topics": ["多普勒效应", "宇宙学红移", "系外行星", "光谱"],
              "content_type": "科普讲解", "humor": 4, "narrative": 2, "intensity": 2,
              "level": 2, "cognitive_load": 3, "pace": "中",
              "tone_primary": "好奇", "tone_secondary": "轻松",
              "keywords": ["多普勒效应", "红移", "系外行星", "光谱", "宇宙膨胀"],
              "summary_one_line": "从消防车鸣笛到系外行星，讲透红移",
              "summary": "从多普勒1842年解释双星颜色变化讲起，用消防车鸣笛、火车小号手等生活例子说明相对运动如何改变频率，再延伸到天文：用光谱红移寻找系外行星、追踪双星，最后解释宇宙学红移与空间膨胀，并澄清它并非真正的多普勒效应。",
              "best_for": "通勤路上想轻松学点天文",
              "hook": "红移其实不是多普勒效应"},
    },
    {
        "u": "标题：英国脱欧探究：难说的再见\n日期：2020-01-31    时长：15 分钟\n系列：无\n\n逐字稿：\n"
             "从2016年脱欧公投讲起，梳理分手费、过渡期和北爱尔兰边界三大难题，再讲特蕾莎·梅"
             "在议会三次投票接连失败、被本党议员拆台而下台，最后约翰逊整合派系完成脱欧……",
        "a": {"domain": ["历史", "人文社科"], "topics": ["英国脱欧", "欧盟", "北爱尔兰边界", "特蕾莎·梅"],
              "content_type": "时事评论", "humor": 4, "narrative": 4, "intensity": 3,
              "level": 1, "cognitive_load": 2, "pace": "中",
              "tone_primary": "轻松", "tone_secondary": "",
              "keywords": ["英国脱欧", "欧盟", "特蕾莎·梅", "鲍里斯·约翰逊", "北爱尔兰"],
              "summary_one_line": "脱欧为何一波三折，换掉一个首相才办成",
              "summary": "从2016年脱欧公投讲起，梳理分手费、过渡期与北爱尔兰边界三大难题，再讲特蕾莎·梅议会三次投票失败被本党拆台下台，最后约翰逊整合派系完成脱欧。用离婚分财产、NBA球星不结婚等比喻把复杂政治讲得通俗。",
              "best_for": "通勤路上想轻松了解时政",
              "hook": "用离婚分财产理解脱欧"},
    },
]


def build_prompt(meta, text):
    # transcript.md 的首行是 "# 标题"，和 head 里的标题重复，去掉
    text = re.sub(r"^#\s.*\n+", "", text.strip(), count=1)
    head = (f"标题：{meta.get('title')}\n"
            f"日期：{meta.get('date') or '未知'}    时长：{meta.get('duration_sec', 0)/60:.0f} 分钟\n"
            f"系列：{meta.get('series') or '无'}\n\n逐字稿：\n")
    body = (text[:5000] + "\n……\n" + text[-1500:]) if len(text) > 7000 else text
    return head + body


def fewshot_messages():
    msgs = []
    for ex in FEWSHOT:
        msgs.append({"role": "user", "content": ex["u"]})
        msgs.append({"role": "assistant",
                     "content": json.dumps(ex["a"], ensure_ascii=False)})
    return msgs


def _post(payload, key, timeout=120):
    req = urllib.request.Request(
        f"{API_BASE}/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {key}"},
        method="POST",
    )
    ctx = ssl.create_default_context()
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
        return json.loads(r.read().decode("utf-8"))


def validate(d):
    """把模型输出收敛到合法值，返回 (清洗后, 问题列表)。"""
    issues = []

    def as_list(key, lo, hi, vocab=None):
        vals = d.get(key) or []
        if isinstance(vals, str):
            vals = [vals]
        out = []
        for v in vals:
            v = str(v).strip()
            if not v or v in out:
                continue
            if vocab is not None and v not in vocab:
                issues.append(f"{key}: 非法值 {v}")
                continue
            out.append(v)
        if len(out) < lo:
            issues.append(f"{key}: 数量不足({len(out)})")
        return out[:hi]

    def clamp(key, lo, hi):
        try:
            v = int(d.get(key))
        except Exception:
            issues.append(f"{key}: 非整数")
            return (lo + hi) // 2
        return max(lo, min(hi, v))

    tp = str(d.get("tone_primary") or "").strip()
    if tp not in TONE:
        issues.append(f"tone_primary: 非法值 {tp}")
        tp = ""
    ts = str(d.get("tone_secondary") or "").strip()
    if ts not in TONE or ts == tp:
        ts = ""

    pace = str(d.get("pace") or "").strip()
    if pace not in ("慢", "中", "快"):
        issues.append(f"pace: 非法值 {pace}")
        pace = "中"

    ct = str(d.get("content_type") or "").strip()
    if ct not in CONTENT_TYPE:
        issues.append(f"content_type: 非法值 {ct}")
        ct = ""

    out = {
        "domain": as_list("domain", 1, 2, DOMAIN),
        "topics": as_list("topics", 2, 6),
        "content_type": ct,
        "humor": clamp("humor", 1, 5),
        "narrative": clamp("narrative", 1, 5),
        "intensity": clamp("intensity", 1, 5),
        "level": clamp("level", 1, 3),
        "cognitive_load": clamp("cognitive_load", 1, 5),
        "pace": pace,
        "tone_primary": tp,
        "tone_secondary": ts,
        "keywords": as_list("keywords", 3, 8),
        "summary_one_line": str(d.get("summary_one_line") or "")[:60],
        "summary": str(d.get("summary") or "")[:300],
        "best_for": str(d.get("best_for") or "")[:40],
        "hook": str(d.get("hook") or "")[:80],
    }
    if not out["domain"]:
        issues.append("domain 为空")
    if not out["content_type"]:
        issues.append("content_type 为空")
    if not out["summary"]:
        issues.append("summary 为空")
    return out, issues


BANNED_TOPIC = re.compile(r"^(科学|知识|人类|世界|发展|研究|技术|历史|文化)$")


def derive(t, duration_sec=0):
    """从刻度派生场景与睡眠友好度——规则算，不让模型猜。"""
    level, load = t["level"], t["cognitive_load"]
    humor, inten, pace = t["humor"], t["intensity"], t["pace"]

    sleep_friendly = (level <= 2 and load <= 3 and pace in ("慢", "中")
                      and inten <= 3 and duration_sec <= 2400)
    requires_attention = load >= 4

    scene = []
    if sleep_friendly:
        scene.append("睡前")
    if load <= 3:
        scene.append("做家务")
    if load <= 2:
        scene.append("开车")
    if humor >= 4 and inten <= 3:
        scene.append("小酌")
    if requires_attention:
        scene.append("专注")

    return {
        "sleep_friendly": sleep_friendly,
        "requires_attention": requires_attention,
        "energy": min(5, max(1, int(round((inten + load) / 2)))),
        "scene": scene,
    }


def tag_one(meta, text, key, retries=2):
    prompt = build_prompt(meta, text)
    msgs = [{"role": "system", "content": SYSTEM}] + fewshot_messages() + \
           [{"role": "user", "content": prompt}]
    payload = {
        "model": MODEL,
        "messages": msgs,
        "response_format": {"type": "json_object"},
        "temperature": 0.3,
        "max_tokens": 900,
        "thinking": {"type": "disabled"},
    }
    last = None
    for attempt in range(retries + 1):
        try:
            r = _post(payload, key)
            content = r["choices"][0]["message"]["content"]
            data = json.loads(content)
            clean, issues = validate(data)
            clean["topics"] = [x for x in clean["topics"] if not BANNED_TOPIC.match(x)]
            usage = r.get("usage") or {}
            return clean, issues, usage
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}: {e.read()[:200]!r}"
            if e.code in (429, 500, 502, 503, 504):
                time.sleep(2 * (attempt + 1))
                continue
            break
        except Exception as e:
            last = f"{type(e).__name__}: {e}"
            time.sleep(1.5 * (attempt + 1))
    return None, [str(last)], {}


def estimate_tokens(text):
    """粗估：中文约 0.7 token/字。"""
    return int(len(text) * 0.7) + 400
