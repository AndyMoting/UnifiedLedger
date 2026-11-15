# D-332 设计门草稿——确认 × 续窗语义（拒收后的可达性）

**状态：** **approved v3**（2026-11-15；用户裁决：Q1 = **③+① 组合**（footer 禁用化 + contentDescription 提示 + rig 等轮末重 click）、Q3 = **提示走 contentDescription**（可见文本不变）、Q2 不触发（②未选）、Q4 形制 = await 轮末（进度行消失谓词）→ 重 click → 原 await；v3 = 实施落地修订行 §2.3a，随实施提交落档。本规格即实施批任务书）—— draft（设计门待裁决——本文为 ignored 暂存区草稿，lead 搬运进 `docs/specs/` 并经裁决后才具规格效力）
**基树：** `551521f9`（D-331 squash #367）；本文全部 `file:line` 以该树为准。
**指针：** `docs/PROGRESS_LOG.local.md:5338-5347`（2026-11-15 午条，含 `:5345` 晨三补读修正）、`local/artifacts/maxrig-43663940414/`（trace 全套 + 60 样本）、`local/artifacts/b6-43663937030/`（b6 gate PASS）、D-280 §3.4（拒收门设计出处，注释原文见 `P503App.kt:3151-3159`）、`docs/specs/2026-11-14-d322-count-driven-group-entry-design.md`（规格形制先例）。

本文不改写任何既有裁决；D-280 §3.4「确认恒为全组授权」与两道单飞门语义在所有候选方案下保留（§4）。

---

## 1. 背景与证据

### 1.1 trace 时间线（run `43663940414`，maximum，单 attempt）

| 时刻 | 事件 |
| --- | --- |
| 11:16:22 | rig click「确认整组标记」（`AndroidScaleLongInstrumentedTest.kt:981-983`：`edge(last=true)` → 反向 seek → click）；rig log 记 `groupMark click accepted`——接受的是 click 事件本身。当时枚举轮 ordinal ~1001/50000（≈2%） |
| 11:16:22 | 拒收门静默 return（`P503App.kt:3160`）：无 confirmed 派发、无任何用户可见反馈 |
| 11:22:18 | 枚举轮 `Completed`（click 后 5 分 56 秒）——**此刻 outcome await（600s 窗）仍在轮询** |
| ~11:26:22（推算） | `:1007` await 600s 超时；60 样本全 `none`、`firstHitMs=-1` |

决定性事实：轮末（11:22:18）落在 await 窗内（窗至 ~11:26:22），之后近 4 分钟里 outcome 仍未出现——**缺的不是时间，是「轮末后的第二次点击」**。rig 的 click 是一次性的，被拒的 tap 不会重发。

### 1.2 拒收门原文（按设计拒收，非竞态吞提交）

- `P503App.kt:3145` `confirmImportDuplicateGroupDisposition()`；`:3151-3159` D-280 §3.4 注释原文（节选）：「if the enumeration's single-flight slot is still held, the confirm is refused (the tap while pages are landing waits for the round to finish; the progress line stays visible so the state is explicit)」；`:3160` `if (coordinator.isImportGroupEnumerationRunning()) return`——**in-flight 即静默 return，无反馈路径**。
- `P503HostCoordinator.kt:631-638` KDoc：「the confirm tap is refused and the whole-group authorization happens only once [importGroupEnumerationCompleted] has cleared the marker」——注释措辞读起来像轮末会有授权发生；实际语义是「等轮次完成后**用户再点**」（`importGroupEnumerationCompleted` `:627-629` 只清标志，不派发任何确认）。**设计留白在此**：D-280 §3.4 写语义时默认用户会在轮末再点；50k 档轮次 ~6 分钟，期间 footer 仍可点（`P503ImportReview.kt:766` 无 disabled 态）、点了无效果无提示。
- 午条初判（「确认被吞掉」）已被晨三补读修正（`PROGRESS_LOG.local.md:5345`）：**确认从未发生，按设计拒收**。这是设计留白 + 测试面缺口，不是竞态吞提交。

### 1.3 60 样本读数与 b6 对照

- maximum：GroupMarkOutcomeProbe（`AndroidScaleLongInstrumentedTest.kt:1005-1019`）onPoll 60 样本全 `none`（三标记行零命中），`firstHitMs=-1`；终态 forensics 视口在导入面顶部格式区（按钮文本仍在树）。
- b6：run `43663937030` **PASS**（click_to_card 2855/2736/2836 ≤3000）。机理（晨三条 `:5332-5333` 推定）：b6 的 100 窗在 progress_to_card 窗（~2.7s）内落完 ⇒ rig click 时轮已 `Completed` ⇒ 拒收门不触发 ⇒ b6 与本议题无交集（待 V-1 与开放问题①复核）。

**检查点：** 本节每条 `file:line` 均可复核于 `551521f9`；时间线三时刻可对照 `local/artifacts/maxrig-43663940414/` trace；「非竞态吞提交」的定性以 `:3160` 原文 + 晨三补读为据。

---

## 2. 三候选方案

### 案 ① rig 侧重试（纯测试，零产品变更）

- 现状：`AndroidScaleLongInstrumentedTest.kt:983` click 一次性 → `:1005-1009` await 600s（任务书里 `:865` 为 D-330 旧行号，当前 main = `:983` click / `:1007` await）。
- 改形：click 后先 await 轮末（轮末的可观测形态二选一，见 Q4——进度行「正在整理重复组……」`P503ImportReview.kt:656` 消失，或 probe 已挂的 ULStartup 轮 trace 的 groupEnum Completed 事件）→ 重 click → 原 await（谓词文本一字不动）。
- 安全性：重 click 被两道既有机制幂等保护——disposition 单飞门（`P503HostCoordinator.kt:647` in-flight 丢弃）+ 重跑幂等（`P503App.kt:3137-3140`、`:3178`：Reviewed 项不重提）。b6 形态下首 click 已成功 ⇒ 重 click 落在 disposition 轮 in-flight 内被丢弃，无害。
- 触碰面：`android-app/src/androidTest/kotlin/com/unifiedledger/android/AndroidScaleLongInstrumentedTest.kt:981-1009` 单文件；零产品变更。
- **局限**：用户盲等 6 分钟的问题原样——rig 修了，用户没修；轮末前点击仍然无任何反馈。

### 案 ② 产品确认排队（一步到位，语义变更）

- 改形：`P503App.kt:3160` 拒收点不再裸 return——in-flight tap 记 pending 意图（presentation 本地态或 coordinator 标志），轮 `Completed`（`P503HostCoordinator.kt:627-629` 清标志位点）后自动执行 confirm。用户一次点击即完成（轮次长 = 等待自动化）。
- 触碰面：`P503App.kt:3145-3161`（拒收点改排队派发）+ `P503HostCoordinator.kt:627-638`（pending 存取与轮末触发）+ 排队态的测试面。
- 语义变更：**用户可见行为变更**（点了就生效 vs 现在点了被拒、需轮末再点），直接修订 D-280 §3.4 注释语义——须用户裁决。
- 待裁决细节：排队意图的生命周期（Q2：是否跨卡片关闭存活——推荐不存活，关闭 = 撤权）。

### 案 ③ in-flight footer 禁用化 + 提示（可见性改良，不改语义）

- 改形：轮进行中「确认整组标记」Button（`P503ImportReview.kt:766`）disabled + 提示（contentDescription「轮次整理中，完成后可确认」，见 Q3）；轮 `Completed` 后启用。用户明确知道点了没用、为什么没用。
- 触碰面：`P503ImportReview.kt:763-770`（footer 渲染态）+ `P503ImportReviewPresentation.kt:1194-1199`（render item 增态）+ `:1416`（装配传 in-flight 标志——`:1357` 既有枚举续窗标志可复用为来源）+ 参数链 `P503ImportReview.kt:434/:503/:537` + `P503App.kt:4939`。
- 不改确认语义：拒收门 `P503App.kt:3160` 原样保留，禁用态只是让拒收在点击**前**可见（程序化路径仍走门）；关闭按钮（`P503ImportReview.kt:768`）保持可点。
- rig 耦合：footer disabled 则 seek/click 会失败——**案③必须配合案①**（await 启用/轮末后再点）。b6 不受影响（click 时轮已完，footer 从未 disabled）。

### 对照表与组合建议

| 维度 | 案① rig 重试 | 案② 确认排队 | 案③ footer 禁用+提示 |
| --- | --- | --- | --- |
| 用户价值 | 零（盲等原样） | 最高：一次点击即生效 | 明确反馈「为何没生效」，轮末仍需再点一次 |
| 触碰面 | androidTest 单文件 | P503App + HostCoordinator + 测试面 | P503ImportReview* + 装配 + 参数链 |
| 语义变更 | 无 | D-280 §3.4 直接修订（须裁决） | 无 |
| 测试影响 | 仅 maximum 路径；b6 不变 | 既有「in-flight 拒收」用例反转 + 新排队用例 | footer 态新用例；rig 必须配合① |
| 风险 | rig-only，产品问题留存 | 排队意图生命周期争议 | 视觉态变化需 PresentationTest 同步 |

**组合建议：③+①**——用户可见反馈明确（③）+ rig 全通（①），两触碰面不相交（androidTest vs P503ImportReview*），且 D-280 §3.4「确认恒为全组授权」语义零修订、最容易守住 §4 非目标。**② 备选**：若用户要一步到位则单独裁决批（语义变更面大，既有拒收臂用例全部反转）。不推荐单选②或单选③：单②语义争议大；单③ rig 仍超时（disabled footer 使 click 失败，await 照样空烧）。

**检查点：** 三案触碰面均落到 `551521f9` 实际行号；对照表四维（用户价值/触碰面/语义变更/测试影响）齐备；组合建议与 Q1 呼应。

### 2.3a 实施批 v3 修订记录（2026-11-15，随实施提交落档）

- **a11y 文案定稿**（沿 J4 纪律，进 `P503ImportReviewPresentation.kt` 纯函数）：footer 确认钮可见文案 **「确认整组标记」不变**（`groupDispositionCardFooterConfirmText()` 冻结字面，Q3 裁决：可见文本不变）；in-flight 提示走 contentDescription **「轮次整理中，完成后可确认」**（新纯函数 `groupDispositionCardFooterInFlightDescription()`，无参）；**轮末 enabled 态不携带任何 contentDescription**（enabled 钮自明，a11y 面回到 pre-D-332 形——非 in-flight 时保持现状「无描述」）。关闭钮与可见文本面零改动。
- **实施落地 file:line**（分支 `feat/d332-confirm-round` 基 `551521f9`）：①装配——`ImportReviewRenderItem.GroupDispositionCardFooter` 新增 `enumerationInProgress: Boolean = false` 字段（`P503ImportReviewPresentation.kt` 渲染项定义 ~:1194-1214），装配点 `:1436-1443` 传 `view.groupEnumerationInProgress`（与进度行 `:1359` 同一来源、同一清除事件——轮次 `Completed`）；②渲染——`P503ImportReview.kt` footer 分支 `:763-792`：`enabled = !inFlight`，in-flight 时 `Modifier.semantics { contentDescription = groupDispositionCardFooterInFlightDescription() }`，轮末态 modifier 为空（无描述）；关闭钮原样；③注释同步——`P503App.kt` 拒收门（D-280 §3.4，`:3151-3163`）补 D-332 句「the disabled footer makes the refusal visible; the confirm intent itself stays refused-until-round-end」——门本体 `if (coordinator.isImportGroupEnumerationRunning()) return` 一字不动。
- **实施期发现 ①（触碰面收窄）**：**参数链零改动**——规格 §2 案③预估的 `P503ImportReview.kt:434/:503/:537` 参数链与 `P503App.kt:4939` 装配参数均不需触碰：`importReviewRenderItems(view, …)` 在 `P503ImportScreen` 内直接从 `view` 读标志（渲染项自带 `enumerationInProgress` 字段随项传递），composable 只消费渲染项。最小改动形态：数据类新字段 + 装配点 + 渲染分支 + 文案纯函数，四处全部在 presentation 域内。
- **实施期发现 ②（Q4 谓词换形，落地形制修正）**：**「进度行消失」谓词在 rig 调用点为空真（vacuous）**——`edge(last=true)` + 反向 seek 后视口停在卡 footer，LazyColumn 只组合可见窗（D-330 v4 判读同机理），进度行位于卡块上方、不在 a11y 树内，`!has("正在整理重复组……")` 无论轮次是否进行都首询即真。改用 **footer in-flight contentDescription 的消失**为轮末否谓词（`ui.await(timeout = 900_000) { !ui.has("轮次整理中，完成后可确认") }`）：该描述正是本批交付一挂到确认钮上的 Q3 提示，在此视口**必然已组合**（钮本身即 seek/click 目标），与进度行同源同清除事件，其消失即轮末；b6 形态（click 时轮已完）footer 从未禁用、描述从未出现，await 首询即过零等待——V-1 语义保持。900s 显式界 = 轮次余量（group_disposition 5,400s stage 预算内；卡开 await ~10s + 遍历前置；D-329 读数 50k 轮 190.8s）。Q4 三段形制原样：await 轮末 → 重 click `确认整组标记` → 原 outcome await（600s + 探针，谓词一字不动）。rig 落点 `AndroidScaleLongInstrumentedTest.kt`（`group_disposition` stage 内：原 click `:983` → 新 await `:1010` → 重 click `:1018` → 原 await `:1041` 附近，注释载 D-332 approved v2 + run `43663940414` 拒收时间线依据）。
- **测试面落地**：`P503ImportReviewPresentationTest.theGroupDispositionCardHeaderAndFooterExposeInputRefAndItemCount` 扩钉新文案字面「轮次整理中，完成后可确认」；`P503ImportReviewRenderItemsTest` 新增 `theCardFooterCarriesTheEnumerationInFlightFlagBothWays`（轮末默认 `enumerationInProgress=false` 构造零变化 + in-flight 臂标志随行 + 进度行同源并存 + 文案纯函数钉）；rig 侧 androidTest 改动不跑测试（CI 裁决，零本机执行纪律）。

---

## 3. 裁决点（Q1–Q4）

- **Q1 方案选型**——推荐 **③+① 组合**（理由见 §2 组合建议）；② 为备选，须用户明确接受「点了就生效」的语义变更。
- **Q2（若案②）排队语义**——排队意图**不跨卡片关闭存活**（推荐）：用户关闭组卡 = 撤回授权意图，轮末不得对已关闭的卡自动派发全组授权；refresh/会话切换同理失活。裁决后写入搬运版规格 §3 裁决表。
- **Q3（案③）提示文案**——推荐：按钮可见文本保持「确认整组标记」不变（`P503ImportReviewPresentation.kt:796` 不动，rig 谓词与既有文本断言零变），disabled 态以 contentDescription 携带提示「轮次整理中，完成后可确认」；是否加可见提示行（进度行旁）由用户裁决。
- **Q4 rig 等待/重试形制**——推荐「await 轮末 → 重 click → 原 await」；轮末观测首选**进度行消失谓词**（`P503ImportReview.kt:656` 的「正在整理重复组……」由轮次标志承载，`P503ImportReviewPresentation.kt:1357`），备选**轮末 trace 事件**（probe 的 onPoll 已挂 ULStartup 轮 trace，`AndroidScaleLongInstrumentedTest.kt:1000`）。若 await 助手不支持否谓词则退为有限重 click 循环（重 click 由单飞门幂等保护，安全，见案①安全性）。

**检查点：** 每个 Q 均有推荐项与理由；Q1–Q4 裁决后可直接落入搬运版规格的 §3 裁决表。

---

## 4. 非目标

1. 不改枚举轮读链——三 seam（first-window / continuation / round-end，`P503HostCoordinator.kt:814/:844/:871` 起）的分发序原样。
2. 不改单飞门机制本身——枚举门（`P503HostCoordinator.kt:616-629`）、确认门（`:640-656`）语义保留；案②的排队是在拒收点 `P503App.kt:3160` 之外新增意图记录，不是改门。
3. 不改 await 谓词文本——「已标记：CONFIRMED_DUPLICATE」等一字不动；案①只改 await 的前置序列。
4. D-280 §3.4「确认恒为全组授权」语义保留——**所有方案都不引入「部分组确认」**；确认时刻的 `page.items` 恒为全组（`P503App.kt:3156-3158` 注释语义不变）。

**检查点：** 四条非目标均指名机制锚点 `file:line`；任何实施批的 diff 触及上述锚点即越界，须回本门重裁。

---

## 5. 验收向量骨架

- **V-1 b6 不受影响**：b6 复跑 gate PASS，click_to_card/progress_to_card 落在 `43663937030` 对照带（2855/2736/2836）；案③的 disabled 态在 b6 从未出现（click 时轮已完）。同时闭合 §6 开放问题①。
- **V-2 maximum `group_disposition` 全通路径**：click（in-flight，被拒或遇 disabled）→ [案①：await 轮末 → 重 click] → 「已标记：CONFIRMED_DUPLICATE」首命中（`firstHitMs > 0`）→ 60 样本出现标记行 → `AndroidScaleLongInstrumentedTest.kt:1020` evidence 键照落 → reopen() oracle 等式（prepared + main）→ `:1027` batch_confirmation 照走。await 谓词文本零变（§4③）。
- **V-3（若案②）排队路径**：in-flight click → pending 意图留痕（证据键）→ 轮 Completed 后自动 confirm → outcome 命中；关卡撤权用例（Q2 语义钉住）。
- **V-4 呈现层用例面**（案③）：footer disabled/enabled 双态 + contentDescription 断言进 `P503ImportReviewRenderItemsTest` 族；文本面（`P503ImportReviewPresentation.kt:796`）断言不变。

**检查点：** 向量只列判定式与证据键骨架，不新增谓词文本；V-2 的全通定义 = await 首命中 + oracle 等式 + 下一 stage 照走三段齐备。

---

## 6. 开放问题（≤6，各附闭合条件）

1. **b6 链是否包含确认 click**——闭合：读 b6 run（`43663937030`）round log/gate.json 是否有 `:983` click 与 `:1007` await 的执行行；一眼定，决定 V-1 的表述。
2. **rig await 助手是否支持否谓词**（「进度行消失」形制）——闭合：读 `AndroidScaleUi` 的 await/has 实现签名；不支持则 Q4 退为轮末 trace 事件或有限重试。
3. **轮末 trace 事件对 rig 的可达性**——闭合：读 GroupMarkOutcomeProbe 的 onPoll trace 挂载（`AndroidScaleLongInstrumentedTest.kt:1000`）确认 groupEnum Completed 行名可读。
4. **案③ disabled 态的 a11y 形制**——闭合：PresentationTest 单测钉住 disabled Button 的 semantics 暴露（文本仍在树、contentDescription 可读），rig seek/click 谓词据此定形。
5. **案② pending 意图的失活面**（若案②入选）——闭合：Q2 裁决 + 协调器/用例钉住「关卡即撤权」。
6. **文案与注释修订的登记位**——闭合：搬运版规格 approved 后，D-332 决定条目随批登记 `docs/DECISIONS.md`；案②选中时 D-280 §3.4 注释改写随批走。

**检查点：** ≤6 条且每条附可执行的闭合条件；闭合动作均为「读一处/裁决一次/随批登记」量级。
