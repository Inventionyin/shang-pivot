# Design System: Shang E-Commerce — 暖橙 × 珊瑚 × 奶油

> 视觉方案（页面设计规范阶段产出）。色值取自 `docs/02-design/brand/logo.png` 程序化提取的真实主色，非臆测。
> 适用于：后台管理系统（Vue3 + Element Plus）、购物三端（uni-app Web/微信小程序/H5）。
> 方法论：`taste-design`（反 AI 套路 premium 标准）。

## 1. Visual Theme & Atmosphere

一个**温暖、克制、有辨识度**的暖系界面——像傍晚的市集灯光，而非冷感 SaaS 仪表盘。

- **密度**：后台 Daily-App Balanced 偏密（6/10，表格可读优先）；C 端 Art-Gallery Airy 偏中（4/10，留白让商品呼吸）。
- **变化**：Predictable Symmetric 偏 Offset（4/10）——专业规整，但用暖色与圆角制造性格，不机械对称。
- **运动**：Fluid CSS（5/10）——spring 物理的轻量过渡，克制不浮夸。
- **气质**：奶油底 + 暖橙点睛 + 珊瑚高亮，墨色文字压稳。整体"温润可信"，避开冷蓝/紫霓虹的 AI 套路。

## 2. Color Palette & Roles

### 暖中性（画布与表面）
- **奶油画布 Cream Canvas** `#FEFCF6` — 页面背景（取自 logo 奶油白 `#FEFFEF` 精修）
- **纯表面 Pure Surface** `#FFFFFF` — 卡片/容器填充
- **沙雾 Sand Mist** `#F4EEE0` — 次级表面/悬停/表格斑马纹

### 墨色（文字）
- **浓缩墨 Espresso Ink** `#241D15` — 主文字（暖近黑，禁用纯 `#000`）
- **暖石 Warm Stone** `#6F6354` — 次级文字/描述/元数据
- **暖岩 Warm Slate** `#9A8E7C` — 三级/禁用
- **轻语边 Whisper Border** `#E9E0CE` — 边框/分割线

### 强调色（取自 logo 前景）
- **暖琥珀 Warm Amber** `#FEBB4A` — **主强调**：链接、激活态、主按钮填充（按钮文字用浓缩墨 `#241D15`，因底色浅）。logo 原值，饱和度 0.71（<0.8 合规）。
- **珊瑚 Coral** `#FF8A5B` — **次强调**：徽标、高亮、暖系提示、次按钮描边、悬停提亮。logo `#FFA069` 精修略深以增存在感。
- **日落深 Sunset Deep** `#E07A1C` — 暖琥珀的按压/激活深化态（仅交互反馈，不作大面积）。

### 语义色（低饱和，禁霓虹）
- **林荫绿 Forest** `#47704C` — 成功/在库/正向（直接取自 logo 背景绿，与品牌同源，不另造绿）。
- **砖红 Brick** `#C8453C` — 危险/错误/下架（暖系红，非荧光红）。
- **暖琥珀** 同时承担"警告/待处理"（与主强调同色，天然契合）。
- **雾青 Muted Teal** `#4A8A8A` — 信息（罕用，保持暖系不引入冷蓝）。

### 阴影（暖调染色，非黑灰）
- `0 1px 2px rgba(36,29,21,0.06)` — 卡片浅浮
- `0 4px 16px rgba(36,29,21,0.08)` — 悬停/弹层
- 严禁紫色/霓虹外发光。

## 3. Typography Rules

- **UI/正文**：`Outfit` — 几何无衬线，温暖友好，带性格（Google Fonts 可用）。后台与三端统一。
- **数据/数字/代码**：`JetBrains Mono` — 表格金额、订单号、时间戳、库存数一律等宽，对齐可读。
- **层级**：靠字重与颜色分层，不靠巨号字。标题 track 收紧。
- **正文**：行高 1.6，单行 ≤65 字符。
- **小程序字体降级**：小程序无法加载 Web 字体时降级为系统无衬线（PingFang SC / -apple-system），但 H5/Web 用 Outfit。
- **禁用**：`Inter`（太泛）、`Roboto`、通用系统默认作 premium 主字；仪表盘禁一切衬线（`Times`/`Georgia`/`Garamond`）。

| Token | 字体 | 字重 | 字号(后台) | 字号(C端) |
|---|---|---|---|---|
| Display | Outfit | 700 | 24px | clamp(28px,5vw,44px) |
| H2 | Outfit | 600 | 20px | 22px |
| Body | Outfit | 400 | 14px | 16px |
| Caption | Outfit | 500 | 12px | 13px |
| Data | JetBrains Mono | 500 | 13px | 14px |

## 4. Component Stylings

- **按钮**：扁平、无外发光。主按钮=暖琥珀填充+浓缩墨文字；次按钮=描边 Whisper Border + 暖墨文字；激活态 `-1px` 下沉 tactile 反馈。圆角 10px。
- **卡片**：圆角 14px，`Sand Mist` 或白底，暖调浅阴影。仅当层次需要才用卡片；高密度表格改用 border-top 分割而非卡片堆叠。
- **输入**：标签在上、错误在下；聚焦环用暖琥珀 `rgba(254,187,74,0.35)`。无浮动标签。
- **表格**：表头 `Sand Mist` 底 + 暖石文字；行悬停 `#FEFCF6→#F4EEE0`；数字列右对齐 + JetBrains Mono。斑马纹可选。
- **徽标 Badge**：圆角 6px 全圆角，淡底深字三色系（琥珀/珊瑚/林荫绿/砖红），不饱和。
- **加载态**：骨架屏匹配布局尺寸，暖调 shimmer；禁圆形转圈。
- **空状态**：组合插画 + 引导动作，非纯"No data"。
- **侧栏菜单**：激活项左侧 3px 暖琥珀竖条 + `Sand Mist` 底；图标 20px。
- **顶栏**：奶油底 + Whisper Border 下边线；面包屑暖石色。

## 5. Layout Principles

- 后台：左侧固定侧栏（240px，可折叠 64px）+ 顶栏 + 内容区 max-width 1400px 居中，内边距 24px。
- 三端 C 端：移动优先单列；Web/H5 卡片网格 CSS Grid（非 flex 计算）；小程序适配安全区。
- 严禁元素重叠/绝对定位堆叠——每个元素独占清晰空间带。
- 禁"3 等宽卡片横排"特性行——改 2 列错位、非对称网格或横向滚动。
- 全高区用 `min-h-[100dvh]`，禁 `h-screen`（iOS Safari 跳变）。
- 容器 max-width 约束居中。

## 6. Motion & Interaction

- 默认 spring：`stiffness 100 / damping 20`，有重量感，禁线性 easing。
- 列表挂载用级联延迟（stagger）瀑布揭示，不瞬挂。
- 激活态持续微循环：按钮 hover 仅 transform/opacity 过渡 150ms；数据卡可 subtle shimmer。
- 仅 animate `transform`/`opacity`，禁 animate `top/left/width/height`。
- 后台过渡更克制（120–180ms）；C 端可更舒展（200–300ms）。

## 7. Anti-Patterns（禁用 AI 套路）

- 禁 emoji；禁 `Inter`；禁纯黑 `#000`；禁紫/霓虹/外发光阴影；禁过饱和强调。
- 禁 3 等宽卡片行；禁居中 Hero（C 端首页改非对称/左对齐）。
- 禁模板占位名（"John Doe"/"Acme"）——用真实化中文示例数据。
- 禁编造数据/指标（"99.99% SLA""124ms 响应"）——无真实数据用 `[指标]` 占位。
- 禁 `LABEL // YEAR` 排版；禁 AI 文案词（"赋能/无缝/极致/下一代"）。
- 禁 filler UI 文（"向下滑动"/滚动箭头）；禁失效 Unsplash 链（用 `picsum.photos` 或 SVG）。
- 禁渐变文字大标题；禁自定义鼠标光标。
