# 星河回响 Echoes of the Cosmos

[![Android CI](https://github.com/Jo-Lewis/Echoes_Of_The_Cosmos/actions/workflows/android.yml/badge.svg)](https://github.com/Jo-Lewis/Echoes_Of_The_Cosmos/actions/workflows/android.yml)

本地优先的私人音频库播放器。为**十几年积累、三千多期的长篇科普音频**（如《2049》）设计：
你记得听过某一期，但不记得是哪一期；你想按心情听，而不是按日期听。

> 这是一个个人项目，不是商品。代码开源，供学习 Android/Compose 与本地媒体库工程实现参考。

---

## 它要解决的问题

一个真实的使用场景：3,200 期、860 小时的音频，文件名里只有日期和标题。

- **想不起在哪一期**：想到"讲火星的那期"，只能一期期翻
- **按日期排列没有意义**：你不想听"2021 年 9 月那期"，你想听"轻松点的、十几分钟的"
- **晚上听着睡着**：需要不刺眼的界面、息屏能继续、到点自动停
- **有文稿却用不上**：三千多份 `.doc`/`.docx` 文稿配着音频，但和音频没有任何时间对应关系

这个项目就是围绕这几件事做的。

---

## 功能现状

| 能力 | 状态 |
|---|---|
| 本地媒体库扫描与缓存（3,200+ 期，冷启动 ~2s） | ✅ |
| 后台播放、息屏继续、耳机控制、进度记忆 | ✅ |
| 三种播放模式：封面 / 配图轮播 / 同步字幕 | ✅ |
| 配图全屏查看，双指缩放 | ✅ |
| 文稿逐句跟随播放进度，点句跳转 | ✅ |
| 收听统计（时长、时段、类型偏好、完播率） | ✅ |
| 收藏、历史、定时关闭 | ✅ |
| 自然语言点播（"今天想听火星"→自动播放） | ✅ 需自备 API Key；未配置时退回本地匹配 |
| 意图解析 + 本地召回 + LLM 精排（带理由与候选队列） | ✅ 真机验证 |
| 播放队列、节目详情页的"下一期播放" | ⬜ 未实现 |
| 首启动导入向导（SAF 目录授权） | ⬜ 未实现 |

**关于 AI**：密钥由你自己填，只存本机，不入代码。没填也能用——只是退化成关键词匹配，
界面上会明说「未配置 AI，当前是本地关键词匹配」。调用失败时同样会显示失败原因，不会假装成功。

点播流程是三步：**理解需求**（LLM 把话翻译成检索条件）→ **检索节目**（本地 BM25-lite + 标签打分召回 40 条）
→ **排列候选**（LLM 从候选里挑一期并给理由）。只有第二步在本地，所以断网时仍然有结果。

---

## 界面结构

```
底栏：听什么 / 节目库 / 我的
 ├─ 听什么   输入卡、继续收听、为你发现、最近在听
 ├─ 节目库   搜索 + 范围/排序/年份/类型筛选 + 列表 → 节目详情
 ├─ 我的     收听统计 / 收藏 / 历史 / 定时关闭 / 播放设置 / 媒体库 / 关于
 └─ 迷你播放器 → 全屏播放器
                  ├─ 封面
                  ├─ 画面（配图，上下滑换图）
                  └─ 字幕（跟随播放位置的文稿）
```

手势：**横滑**切换三种播放模式，**纵滑**在页面内滚动/换图。两个轴分工，避免手势打架。

---

## 技术栈

- **客户端**：Kotlin 2.0.21 + Jetpack Compose（Material 3）+ Media3（ExoPlayer / MediaSessionService）
- **元数据流水线**：Python 3.12+，依赖 `olefile` / `mutagen` / `Pillow`（见 `requirements.txt`）
- **媒体处理**：ffmpeg（`ebur128` 响度测量、`silencedetect` 静音检测）

编译要求：JDK 17+、Android SDK 35、minSdk 26。

---

## 快速开始

### 1. 编译 App

```bash
git clone https://github.com/Jo-Lewis/Echoes_Of_The_Cosmos.git
cd Echoes_Of_The_Cosmos
# 指向你的 Android SDK
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

App 默认从 `/sdcard/2049lib/` 读媒体库（见 `Library.kt` 的 `root()`）。

想要自然语言点播，在 App 里进「我的 → AI 设置」，填接口地址、Key、模型名（任何 OpenAI 兼容接口都行，
默认 `https://api.deepseek.com` + `deepseek-chat`）。不填也能用，只是走本地匹配。

### 2. 准备媒体库

App 读的是流水线构建出来的目录，不是原始文件。目录结构：

```
library/build/shows/2049/<年>/<日期 标题>/
├── meta.json          # 元数据（schema v3）
├── audio.mp3          # 或 parts/ 分成多段
├── cover.jpg
├── thumb.jpg
├── transcript.md      # 文稿正文
├── blocks.json        # 逐段文本 + 起始毫秒（字幕用）
└── images/01.png …    # 配图
```

推到手机：

```bash
adb push library/build/. /sdcard/2049lib/
```

---

## 数据流水线

```bash
pip install -r requirements.txt

export ECHOES_SRC=/path/to/raw/library   # 原始音频 + 文稿
export ECHOES_OUT=./library/build        # 构建产物

python pipeline/2049ctl.py scan          # 扫描原始库
python pipeline/2049ctl.py build         # 构建每期的 meta.json / 文稿 / 封面
python pipeline/2049ctl.py doctor        # 体检：缺音频、缺文稿、时长对不上
python pipeline/2049ctl.py preview "火星" # 预览检索效果
```

几个值得一提的实现：

- **`.doc`（Word 97 二进制）解析**：自己写了 OLE2 复合文档 + piece table 解析器（`pipeline/lib2049/doc97.py`），
  因为文稿是早期 Word 格式，现成库读不干净。
- **响度测量**：`loudnorm` 对 3,000 个文件太慢（17.6× 实时），改用 `ebur128`（117×），
  测出的增益**不转码**，播放时按 `gain_db` 施加。
- **时间轴对齐**：用 `silencedetect` 找段落边界 + 中文 196 字/分的语速比例，把文稿切块对到时间上。
  这是**估算**，不是 ASR 对齐，所以界面上不显示"跳到 18:20"这种精确时间。
- **图片归属**：文稿可能在"每期一个文件夹"里，也可能直接躺在 `短篇/长篇` 分类目录里。
  用显式规则（`episode-folder` / `category-sibling` / `none`）判定，并把规则写进 `provenance`。
  早期版本没做这个判定，结果某期被塞了 599 张无关图片。

元数据格式见 [`docs/元数据规范-v3.md`](docs/元数据规范-v3.md)。

---

## 文档

| 文档 | 内容 |
|---|---|
| [技术方案](docs/技术方案-科普音频智能点播.md) | 整体设计与取舍 |
| [媒体库规范](docs/媒体库规范.md) | 目录结构与文件约定 |
| [元数据规范 v3](docs/元数据规范-v3.md) | `meta.json` 字段、资产归属规则、校验清单 |
| [检索与播放设计](docs/检索与播放设计.md) | 意图解析、召回、排序 |
| [评估报告](docs/评估报告-第一轮.md) | 检索准确率的实测数据 |
| [UI 设计规范](docs/UI设计规范.md) | 色板、组件、页面层级 |
| [问题清单](docs/问题清单.md) | 每个 bug 的根因、修法、验证方式 |
| [开发日志](docs/开发日志.md) | 逐轮迭代记录 |

`问题清单` 可能是最有价值的一份——里面有十几个真实踩坑，
包括 Compose 的 `LaunchedEffect` key 写错导致动画被取消、
`Dialog` 默认宽度限制导致图片"没放大"、全屏页忘记处理 insets 之类。

---

## 已知限制

- **未接 LLM**：自然语言输入目前走本地 bigram 匹配，"想听点轻松有趣的"这类模糊表达效果有限。
- **字幕居中**：按实测位置计算，仍有几十像素误差；滚动是 480ms 补间。
- **只在小米 Android 17（arm64）上验证过**，其他 ROM 可能有后台保活、存储权限差异。
- **没有导入向导**：媒体库要靠 adb 推，普通用户走不通。
- **改数据要手动清缓存**：App 现在只按"期数变化"判断是否重建缓存，改了图片但期数没变就看不到。

---

## 免责声明

- 本项目**不是**《2049》或任何节目方的官方项目，与其没有任何关联。
- 仓库**不包含**任何音频、文稿正文、节目配图等受版权保护的内容，也不提供任何获取方式。
- 代码只解决"本地已有一份音频库，如何组织与播放"这件事。
- 仓库中的示例数据、测试用例仅用于说明数据结构。
- 你在使用本代码处理任何内容时，需自行确保拥有该内容的合法使用权。
- 本项目仅供学习与技术交流，作者不对使用者的行为及其后果负责。

## License

[MIT](LICENSE)
