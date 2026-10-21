# UI 美化方案（Compose 组件库调研 + 分阶段计划）

> **版本**：2026-10-21 / v1.0
> **适用项目**：MyApplication2（三角警示牌 / 锥桶协同布设）
> **技术栈基线**：Kotlin 2.2.10、Compose BOM 2025.12.00（Compose UI 1.10.x、Material3 1.4.0）、minSdk 24、AGP 9.4.0
> **目标**：在不改变业务逻辑与安全语义的前提下，提升界面层级、一致性、可读性与操作效率。

---

## 1. 现状诊断

### 1.1 已经做得不错的地方

- 已有统一的 `PageTitle`、`SectionCard`、`StatusChip`、`PrimaryActionButton` 等基础组件；
- 安全语义色（红/橙/绿/蓝）已经独立定义，方向正确；
- 关键操作有二次确认、状态芯片、异常提示，安全信息完整；
- `RemoteControlPad` 已具备较好的触觉反馈与按压反馈；
- 页面结构基本一致：标题 → 卡片 → 操作区，改造成本可控。

### 1.2 主要问题

| 问题 | 现状 | 影响 |
|---|---|---|
| 主题仍是模板默认值 | `Theme.kt` 使用 Purple/Pink，且 `dynamicColor = true` | 不同手机壁纸导致品牌色变化；安全语义色与主题色冲突 |
| 视觉层级偏平 | 所有内容都是同一种 `SectionCard` + 文本 | 重点不突出，紧急信息不显眼 |
| 状态表达单一 | `StatusChip` 只有文字 + 底色 | 远距离/强光下不够醒目，色弱用户不友好 |
| 字体与排版未定制 | `Type.kt` 基本是默认字体 | 中文标题/正文层级不够，数字可读性一般 |
| 缺少加载/空/错误状态 | 多数页面直接显示文字或空白 | 网络慢时体验差，显得“没做完” |
| 动效几乎为零 | 状态切换、页面切换都是硬切 | 缺少反馈，界面显得生硬 |
| 卡片阴影统一 2dp | 所有卡片同样的 elevation | 层级没有区分，工业感不足 |
| 图标体系不统一 | 自绘 `AppIcons` + Material core + 少量扩展 | 图标风格混杂，部分业务语义缺失 |

---

## 2. 设计方向：工业安全仪表盘

建议采用**固定品牌的工业安全风格**，而不是引入一套通用 UI Kit：

- **关键词**：可靠、清晰、高对比、低装饰、快速识别；
- **主色**：深海军蓝 / 石墨蓝（可信、专业），替代模板紫色；
- **强调色**：安全橙（用于紧急操作、注意状态）；
- **语义色**：红=危险/故障，橙=注意/暂停，绿=安全/完成，蓝=信息/运行；
- **表面**：浅色 `#F6F8FA` + 白色卡片；深色 `#0F1418` + `#1A2026` 卡片；
- **层级**：用**色块、边框、留白**区分层级，减少大面积阴影；
- **圆角**：卡片 16dp、按钮 14dp、芯片胶囊形、弹窗 24dp；
- **字体**：继续使用系统中文字体（避免打包字体导致 APK 膨胀），但建立完整字号/字重阶梯；
- **状态**：颜色 + 文字 + 圆点/图标三重表达，不能只靠颜色；
- **动效**：150–250ms 的进入/状态切换动画，不做炫技动画，不影响安全操作。

> 不建议引入 Miuix、ComposeCraft、FormaUI 等整包 UI Kit：风格与工业安全场景不匹配，
> 组件黑盒多、可访问性与性能不可控，后期定制成本高于自建。

---

## 3. 第三方组件库调研与选型

> 下表中的版本是调研时 Maven Central / GitHub 的最新稳定版。
> **接入前必须验证与 Kotlin 2.2.10 / Compose 1.10.x 的兼容性**；
> 部分 2026 年新版本已基于 Kotlin 2.3/2.4 或 Compose 1.11/1.12 编译，
> 可能出现 metadata 版本不兼容，需要降级或先升级工具链。

### 3.1 推荐接入（低风险、高收益）

| 库 | 用途 | 建议版本 | 适配度 | 说明 |
|---|---|---|---|---|
| **compose-shimmer** `com.valentinilk.shimmer:compose-shimmer` | 加载骨架屏 | `1.3.3`（稳妥）/ `1.4.0`（Compose 1.10.3，需验证） | ★★★★★ | 设备列表、风险列表、记录列表加载时替代空白/转圈；体积小、API 简单 |
| **Reorderable** `sh.calvin.reorderable:reorderable` | 拖拽排序 | `3.1.0` | ★★★★★ | 锥桶从桶顺序拖拽调整；与 `LazyColumn`/普通 Column 兼容；要求 Compose 1.7+ |
| **Zoomable** `net.engawapg.lib:zoomable` | 图片缩放 | `2.14.0` | ★★★★☆ | 事故照片/快照支持双指缩放、双击放大；比自写手势稳定 |
| **Coil 3** `io.coil-kt.coil3:coil-compose` | 图片加载/缓存 | `3.6.3`（需验证 Kotlin 版本，必要时 `3.4.0`） | ★★★★☆ | 若继续使用现有 `RemoteImage` 可暂不接入；接入后获得内存/磁盘缓存、淡入、占位图 |
| **Vico** `com.patrykandpatrick.vico:compose-m3` | 图表 | `3.3.1` | ★★★☆☆ | 记录页/调试页的里程、电量、速度趋势；只在有连续数据时使用，避免为了图表而图表 |

### 3.2 可选/试验（先做 PoC）

| 库 | 用途 | 建议版本 | 风险 | 建议 |
|---|---|---|---|---|
| **Haze 2** `dev.chrisbanes.haze:haze` + `haze-blur` | 毛玻璃/模糊 | `2.0.1` | 性能、Compose 版本、低端机 | 只用于顶部栏/地图浮层，且必须做低端机降级；不建议用于长列表卡片 |
| **Lottie Compose** `com.airbnb.android:lottie-compose` | 成功/扫码动画 | `6.6.x` | APK 体积、动画资源维护 | 只用于“布设完成/绑定成功”等少量场景，不用于安全状态 |
| **Lucide Icons** `io.github.crazy-unicooorn:lucide-icons-compose` | 线性图标 | `1.45.0-1` | 图标数量大、风格偏轻 | 建议只挑选 20–30 个图标复制为本地 `AppIcons`，不引入整包 |
| **Calendar** `com.kizitonwose.calendar:compose` | 日期选择 | `2.10.1`（Kotlin 2.3，需降级验证） | 版本兼容 | 记录页做日期筛选时再引入；当前不是美化优先项 |

### 3.3 不建议

| 库/方案 | 原因 |
|---|---|
| **MaterialKolor 6.x** | 6.0 基于 Kotlin 2.4 / CMP 1.11，与当前 Kotlin 2.2.10 不匹配；固定品牌色手写更可控 |
| **Miuix / ComposeCraft / FormaUI / VolineUI** | 整包设计语言与工业安全场景冲突，定制成本高，黑盒多 |
| **Accompanist** | 多数模块已归档/被官方替代，不建议新引入 |
| **动态取色（Dynamic Color）** | 当前已开启，导致品牌色随壁纸变化；安全应用应关闭，使用固定色板 |
| **重毛玻璃/大面积渐变/彩带庆祝动画** | 影响可读性、性能和严肃场景的一致性 |

### 3.4 Gradle 依赖建议（版本目录写法）

```toml
[versions]
shimmer = "1.3.3"          # 如验证通过可升 1.4.0
reorderable = "3.1.0"
zoomable = "2.14.0"
# coil = "3.6.3"           # 需要时再加，先验证 Kotlin 兼容性
# vico = "3.3.1"
# haze = "2.0.1"
# lottie = "6.6.6"

[libraries]
shimmer-compose = { group = "com.valentinilk.shimmer", name = "compose-shimmer", version.ref = "shimmer" }
reorderable = { group = "sh.calvin.reorderable", name = "reorderable", version.ref = "reorderable" }
zoomable = { group = "net.engawapg.lib", name = "zoomable", version.ref = "zoomable" }
```

> 接入顺序：先加 `compose-shimmer` 和 `reorderable`，验证编译与低端机性能；
> `zoomable`、`coil` 按页面需要再加；`haze/lottie/vico` 作为独立 PoC，不阻塞主线。

---

## 4. 分阶段实施计划

### Phase 0：主题与设计令牌（0.5–1 天）

**目标**：先解决“看起来像模板”的根因。

- [ ] `Theme.kt` 关闭 `dynamicColor`，改为固定 Light/Dark ColorScheme；
- [ ] 以深海军蓝为主色、安全橙为强调色，生成完整 `primary/secondary/tertiary/surface/outline`；
- [ ] 为现有语义色补齐深色变体（当前 `WarningRedBg/SafeGreenBg/InfoBlueBg` 都是浅色背景，深色模式会刺眼）；
- [ ] 建立 `Dimens`/`Spacing` 对象：4/8/12/16/24/32；
- [ ] 建立 `Shapes`：卡片 16dp、按钮 14dp、弹窗 24dp；
- [ ] `Type.kt` 补全字号阶梯：headline 28/22、title 18/16、body 16/14、label 12/11，行高与字重统一；
- [ ] 定义 `SectionCard` 的三种色调：`Surface`（普通）、`Tonal`（强调）、`Danger`（异常）。

**交付**：`ui/theme/` 下新增 `Dimens.kt`、`Shapes.kt`、`AppColors.kt`，改 `Theme.kt`、`Type.kt`、`Color.kt`。

### Phase 1：共享组件升级（2–3 天）

**目标**：让所有页面“同一套语言”，先改组件再改页面。

- [ ] `SectionCard`：可选 leading icon、trailing action、标题副文案、无阴影/1dp 边框版本；
- [ ] `StatusChip`：增加圆点/图标，改为 tonal 配色；新增 `StatusDot`；
- [ ] `PageTitle`：支持 eyebrow（如“布设执行”上方的小标签）、右侧状态/操作位；
- [ ] `ActionButtons`：统一 52dp 高度、按压/禁用/加载态；主按钮支持 icon；
- [ ] 新增 `EmptyState`、`LoadingState`、`ErrorState` 三个通用组件；
- [ ] 接入 `compose-shimmer`，封装 `SkeletonCard`/`SkeletonList`；
- [ ] 统一动效：`AnimatedVisibility`（异常/提示）、`Crossfade`（状态切换）、`animateContentSize`（卡片展开）；
- [ ] 统一图标：补齐 10–15 个业务图标（锥桶、主桶、从桶、手动、暂停、套装、电量、车道）。

**交付**：`core/ui/` 下新增/改造组件；所有页面只改 import 即可获得基础提升。

### Phase 2：主流程页面重构（5–8 天）

按“用户最常走、最影响观感”的顺序：

1. **WorkTypeSelectScreen**
   - 两张全宽 hero 卡片，带图标/渐变/能力标签；
   - “当前类型”用角标，不再单独一行；
   - 卡片内只保留一句说明 + 主按钮。

2. **ConnectScreen / ConeConnectScreen**
   - 设备卡：设备名 + 编号 + 在线圆点 + 电量环/条 + 套装 chip + 状态 chip；
   - 不可用设备置灰并显示原因；
   - 锥桶从桶顺序接入 `Reorderable` 拖拽排序；
   - 绑定/扫码入口改为顶部工具条或悬浮按钮；
   - 底部固定“下一步”操作栏。

3. **DeploymentPublishScreen / TaskPublishScreen**
   - 顶部步骤条：设备连接 → 参数 → 预检 → 发布；
   - 参数卡改为“表单组 + 实时预览”；
   - 预检项用 checklist + 状态色；
   - 底部固定“发布”主按钮，滚动时始终可见。

4. **DeploymentExecScreen / TaskExecScreen**
   - 顶部状态 hero：大号状态标签 + 进度环/条 + 已到达/已核验计数；
   - 紧急手动干预按钮保持首屏橙色大按钮；
   - 地图/视频卡片加浮层 chip（在线、跟随、图例）；
   - 设备卡：进度环、状态点、异常行、操作按钮；
   - 异常提示改为“警示卡”（橙色边框 + 图标 + 文案），不再使用红色感叹号三角；
   - 底部固定操作栏（暂停/继续/终止），减少滚动。

5. **HomeScreen / ConeHomeScreen**
   - 设备状态 hero + 当前任务卡 + 主 CTA；
   - 次要操作收进“更多”或底部区域。

### Phase 3：次级页面统一（3–5 天）

- **RecordsScreen / DeploymentRecordsScreen**：筛选 chip、按日期分组、骨架屏、空状态、记录卡统一；
- **RiskScreen / AccidentDetailScreen**：风险等级色带、地图/列表切换、照片缩略图 + `Zoomable`；
- **MapScreen**：浮动控制簇（跟随/回中/图层）、底部图例 Sheet、状态 chip；
- **Settings / DeviceInfo / About / ServerConfig**：分组列表 + 图标 + 副标题 + 分隔线，统一行高；
- **LoginScreen**：品牌标识 + 简洁表单，避免默认模板感。

### Phase 4：进阶效果（可选，2–4 天）

- [ ] `Haze` 仅用于顶部栏/地图浮层，做低端机降级；
- [ ] `Vico` 在记录/调试页展示里程、电量、速度趋势；
- [ ] `Lottie` 用于“绑定成功/布设完成”单个成功动画；
- [ ] 页面切换 shared element / predictive back（按 Compose 版本能力）；
- [ ] 主题切换（浅色/深色/跟随系统）与预览。

### Phase 5：验收与回归（1–2 天）

- [ ] 浅色/深色/字体放大 1.3x 三套截图；
- [ ] 小屏（360dp 宽）与大屏（平板）检查；
- [ ] 低端机滚动帧率、地图/视频页面性能；
- [ ] 对比度 ≥ 4.5:1，触控目标 ≥ 48dp；
- [ ] 安全关键流程回归：连接、发布、暂停、手动干预、继续、终止、核验。

---

## 5. 页面优先级与预期收益

| 优先级 | 页面 | 主要改动 | 收益 |
|---|---|---|---|
| P0 | 主题 + 共享组件 | 色板、字体、卡片、芯片、按钮 | 全 App 立刻脱离“模板感” |
| P0 | DeploymentExecScreen | 状态 hero、紧急按钮、异常卡、底部操作栏 | 现场最常用页面，安全与效率提升最大 |
| P1 | ConeConnectScreen | 设备卡、电量/套装、拖拽排序、底部下一步 | 连接阶段更清晰、更少误操作 |
| P1 | DeploymentPublishScreen | 步骤条、表单组、预览、固定发布按钮 | 发布流程更顺 |
| P1 | WorkTypeSelect / Home / ConeHome | hero 卡、任务卡、CTA 层级 | 首屏观感提升明显 |
| P2 | Records / Risk / Map / Settings | 筛选、列表、空状态、地图控件 | 次级页面一致性 |
| P3 | 动效/图表/毛玻璃 | 高级效果 | 锦上添花，不影响主线 |

---

## 6. 技术约束与风险

1. **Kotlin/Compose 版本**：项目是 Kotlin 2.2.10 + Compose 1.10.x。部分 2026 年库已基于 Kotlin 2.3/2.4，接入前先跑 `./gradlew :app:compileDebugKotlin` 验证 metadata 兼容性；不兼容时降级库版本，或单独安排工具链升级。
2. **minSdk 24**：避免依赖 Android 12+ 的 `RenderEffect`/动态取色；Haze 的 Android 17 backdrop 只能作为增强路径。
3. **性能**：地图/视频页面已经有较重负载，不在主滚动区域使用模糊、Lottie、复杂图表；动画只做透明度/位移/尺寸。
4. **安全语义**：红色只用于危险/故障，橙色只用于注意/暂停，绿色只用于安全/完成；不要为了“好看”改变语义色。
5. **可访问性**：状态不能只靠颜色；所有 chip 带文字；触控目标 ≥48dp；支持字体放大。
6. **不要引入整包 UI Kit**：组件黑盒、风格冲突、升级不可控；自建 10–15 个共享组件更稳。
7. **业务逻辑零改动**：所有改动限制在 `core/ui` 与各页面 Compose 层，不动 Repository/ViewModel/协议。

---

## 7. 建议的落地顺序（最小可用版本）

如果时间有限，先做这 8 件事即可获得 70% 的观感提升：

1. 关闭动态取色，换固定深蓝/安全橙主题；
2. 补全 Typography（标题/正文/标签字重与行高）；
3. `SectionCard` 去掉统一阴影，改为白底 + 1dp 边框 + 16dp 圆角；
4. `StatusChip` 增加圆点/图标，改为低饱和 tonal 背景；
5. 统一按钮高度 52dp，主按钮加 icon，增加 loading 态；
6. DeploymentExec 顶部状态 hero + 底部固定操作栏；
7. 设备连接页设备卡加电量条/在线点/套装 chip；
8. 列表页接入 Shimmer 骨架屏 + 空状态。

---

## 8. 参考来源

- Haze：https://github.com/chrisbanes/haze
- Vico：https://guide.vico.patrykandpatrick.com/getting-started
- compose-shimmer：https://github.com/valentinilk/compose-shimmer/releases
- Reorderable：https://github.com/Calvin-LL/Reorderable
- Zoomable：https://central.sonatype.com/namespace/net.engawapg.lib
- Coil：https://coil-kt.github.io/coil/
- Lottie：https://github.com/airbnb/lottie-android
- Compose BOM 映射：https://developer.android.com/develop/ui/compose/bom/bom-mapping
- MaterialKolor：https://github.com/jordond/MaterialKolor/releases
- Calendar：https://github.com/kizitonwose/Calendar/releases
- Lucide Compose：https://codeberg.org/Crazy_Unicorn/lucide-icons-compose
- Awesome Android UI：https://github.com/wasabeef/awesome-android-ui
