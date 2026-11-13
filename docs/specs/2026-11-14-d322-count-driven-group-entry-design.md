# D-322 设计门——count 驱动组入口：当前 pick 会话的「整组标记为重复」O(1) 可达

**状态：** **approved v1**（2026-11-14；独立评审 APPROVE 无 P1，S1 引用修正后批准；批准依据：用户双线授权 2026-11-14 04:10 + 推荐项即批准）
**Revision：** v1（2026-11-14 首稿；同日按评审意见修正 §6① 引用后批准，正文其余未改）

**指针：** 证据包 `local/artifacts/d322-design-pack/EVIDENCE-PACK.md`（run `43474772788` 失败读数、Q1–Q6 取证、四案裁决、用户 2026-11-14 04:10 双线授权）；决定 D-320 R5（`docs/DECISIONS.md:7253` 深行候选不可达的产品级开放项——本批闭合其组入口半边，引用而不宣称闭合全部）、D-321（`:7263` 同租约多读与 `view.copy` 字段保留的先例）、D-319 D-4/D-5/D-9（`:7221`–`:7226` 接治落地一页读、组成员集与列表状态解耦、不新设口径）、D-5/D-6（同 `:7222`–`:7223`，本批复用其 count 查询与 MemberPages 路径）、D-230 §4（`:4811` 截断/隐藏/组范围改动永久禁止）、D-243 §3（`:5182` 不得靠改「完整列表」语义提速）、D-293（`:6581` keyset 分页读裁决）、B6 规格 `docs/specs/2026-11-04-b6-group-card-paged-enumeration-design.md:119`（`P704GP-ACC-ORDER-01` 页序 == 现状全量序）、P704SPEC-12（同次选择句柄 + `EXACT_BUSINESS_TUPLE` + `DEFERRED` 的组边界）、分页读规格 `docs/specs/2026-11-05-p7-04-import-review-paged-read-design.md:49`（「UI 不再调用整表 `Rows(rows)`」）、B1/B4 规格 `docs/specs/2026-11-13-p7-04-b1-b4-windowed-read-design.md`（D-4 落地读先例与 V-5/V-6 向量形态）。

本文不改写上述任何既有裁决：不动列表语义/顺序/预算，不改 `P704GP-ACC-ORDER-01`，不改 D-230/D-243/D-293/D-319/D-320/D-321 的裁决文本与开放状态；D-320 R5 只被引用与部分闭合。

---

## 1. 问题现状（全部 `file:line` 可复核）

- maximum rig run `43474772788`（SHA `2f0e0048`）`status=ERROR`、`phases=["prepare"]`：`group_disposition` stage 的第一个 `ui.seek("整组标记为重复")`（`android-app/src/androidTest/kotlin/com/unifiedledger/android/AndroidScaleLongInstrumentedTest.kt:628-629`，80 次 × 0.6-fraction 滚动 + settle）抛 `UI target not reached`（读数细节见证据包）。
- 根因是**存在条件绑在了装载状态上**：`GroupDispositionButton` 的渲染条件 `importDuplicateGroupRows(view.rows, sessionInputRef).isNotEmpty()`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReviewPresentation.kt:1115`）要求主会话行已装载进列表态；过滤谓词本身是纯函数（`:335-342`，`SUSPECTED_DUPLICATE_PENDING_REVIEW` + `sourceInputRef == sessionInputRef`），但它只看 `view.rows`——而列表自 D-293 起按页装载，每页 50 行（`IMPORT_REVIEW_PAGE_SIZE = 50L`，`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:5723`）。
- 算术：主会话 ~10,000 行、组入口在候选序 ~50,000 位（读数 ~51,000，证据包），已装载的首页 50 行里没有一行属于该会话的重复组 ⇒ `importDuplicateGroupRows(view.rows, sessionInputRef)` 恒空 ⇒ 按钮根本不在 render 树里，rig 的 80 次滚动不可能 reach。**入口可达性不应依赖「行已装载多少页」**——这正是缺陷类。
- 修这半边**不需要读任何行**：会话级 count 查询已存在且已接线——用例 `CountImportDuplicateCandidatesForSession`（`ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ImportReviewReadPort.kt:723-742`，异常映射 null）、SQL `importDuplicateCandidateCountForSession`（`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq:11916-11943`，谓词 = 成员分页读减 keyset/LIMIT，`SqlDelightImportReviewReadAdapter.kt:546-558` 的 KDoc 钉死 count == 页累计总数）、facade 访问器 `P503LedgerFacade.countImportDuplicateCandidatesForSession`（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503LedgerFacade.kt:423-424`）、组卡回合内同查先例 `P503App.kt:2834`。入口句柄 `sessionInputRef = view.lastIntakeSession?.inputRef`（`P503App.kt:2733`，P704SPEC-12 先例）来自 view 状态，不依赖已装载行。

---

## 2. 目标与非目标

**目标**

1. 当前 pick 会话的组入口 **O(1) 可达**：按钮的存在条件改由接治落地时的会话级 count 读承载，主会话行未装载也渲染。
2. legacy 行枚举保留为 **fallback**：view 字段无读数（null）时，门行为与现状等价，全部既有测试原样通过。

**非目标**

- 不动列表语义/顺序/预算（D-230 §4、D-243 §3、D-293、`P704GP-ACC-ORDER-01`；D-319 D-9/D-320 R4 不调 stage 预算/marker）。
- 不做深行候选可达性的其余半边（D-320 R5 继续开放）；不做 rig-only「加载更多」路线（仅按证据包 Q4(c) 定量文档化备选）。
- 零 DDL/零迁移/零依赖：count 查询、端口、facade 接线全部已存在，本批不新增任何 SQL。

---

## 3. 裁决

| # | 裁决 | 内容 |
| --- | --- | --- |
| **D-1** | count 读接入接治落地租约与事件 | `runImportIntakePipeline`（`P503App.kt:2357-2450`）的既有租约块内（`:2386-2411`，D-4 一页读 `:2407-2409` 旁）加一条 `facade.countImportDuplicateCandidatesForSession?.query(ledger.ledgerId, session.inputRef)`；`importIntakeLandingEvent`（`P503HostCoordinator.kt:766-782`）增第三参 `count: Long? = null`，事件 `ImportFileIntakeResult` 增 `currentSessionDuplicateGroupCount: Long? = null`（`P503UiEvent.kt:720-724`，默认 null 保全部既有构造点编译，沿 `nextAfterCandidateId` 同形先例）。租约失败路径（`:2421-2432`）传 null。 |
| **D-2** | view 字段与 reducer 落值/保留 | `ImportReviewView` 增 `currentSessionDuplicateGroupCount: Long? = null`（`P503ImportReview.kt:197-228`，紧挨 `lastIntakeSession`）。`reduceImportFileIntakeResult`（`P503Reducer.kt:2148-2195`）在 `:2190` 位点落值：count 读成功 ⇒ 落新值（**与页分支无关**——count 在接治提交后的同一租约读取，描述新会话的已提交状态；页 `Unavailable` 只治理 rows/游标的 F1 保留，不得让旧会话的 stale count 冒充新会话）；count 读失败 ⇒ 落 null（= 回退臂待命，绝不冒充零）。append（`:2225-2243`）与 refresh（`:2204-2218`）经既有 `view.copy` 天然保留该字段，零改动、测试钉住。 |
| **D-3** | 渲染门 count 驱动 + legacy fallback | `P503ImportReviewPresentation.kt:1115` 改为：`sessionInputRef != null && (view.currentSessionDuplicateGroupCount ?: importDuplicateGroupRows(view.rows, sessionInputRef).size) > 0`。`null` 只代表「无读数」⇒ 回退到既有行枚举（legacy 形态不变）；`0` 是真零（SQL `COUNT(*)` 对无组会话返回 0L，用例仅把异常映射为 null）⇒ 门照常隐藏。`sessionInputRef == null` 仍先判（`:1114` 原样）。 |
| **D-4** | 空态共渲染意图对 | `NoCandidatesEmptyState`（`:1106-1107`）与 `GroupDispositionButton` 可共渲染：count>0 而装载行为空时，空态行与按钮同屏（按钮留在 `:1116` 既有位置、候选行之前）。意图对钉死：空态描述「装载窗口无行」，按钮描述「会话组存在」——两者不互斥。 |
| **D-5** | 陈旧会话分歧登记残余 | count 谓词（`Ledger.sq:11929-11943`）与成员分页读字节同形仅差 keyset/LIMIT，但与渲染层 Kotlin 折叠过滤（`P503ImportReviewPresentation.kt:335-342`）在**重审过的陈旧会话**上可分歧：行折叠按 blocking-first 把已处置重复折成 `CONFIRMED_DUPLICATE`，而 count 是落地时点快照。当前 pick 会话的重复全 `DEFERRED` ⇒ count>0 ⟺ 组非空；分歧形态按 §6① 登记，不做消除机制。 |

---

## 4. 实施切片与可写范围

**S1（字段与事件形状）**：`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReview.kt`（view 字段）、`P503UiEvent.kt`（事件字段）、`P503HostCoordinator.kt`（seam 第三参）。零 schema。

**S2（读与落值）**：`P503App.kt`（租约内 count 读 + 两处 landing 传参）、`P503Reducer.kt`（落地落值位点）。零 schema。

**S3（渲染门）**：`P503ImportReviewPresentation.kt:1115`（D-3 门 + D-4 共渲染）。零 schema。

**测试**：`app-ui/src/commonTest/kotlin/com/unifiedledger/ui/P503ImportReviewRenderItemsTest.kt`（门/共渲染，`:304-359` 既有用例族旁）、`P503ImportReviewReducerTest.kt`（落值/保留）、`P503ImportIntakeLandingHostTest.kt`（seam 携带 count，既有 V-6a/b/c 用例 `:83-123` 不动）。实施在规格 approved 后按仓库路由进独立 worktree 单写手交付；本文与 D-322 决定条目随批登记。

---

## 5. 验收向量

| # | 向量 | 证据类型 |
| --- | --- | --- |
| V-1 | count 读在落地租约内且失败落 typed 值不影响落地：`Rows`/`Unavailable` 两分支的 landing 都携带 count（成功值或 null），session/rows/游标语义与 D-4 先例逐字节不变 | seam + reducer 测试 |
| V-2 | 门在 count>0 时渲染按钮——**含主会话行零装载的形态**；count=null 时行装载路径 fallback 等价（既有门测试 `P503ImportReviewRenderItemsTest.kt:304-326` 原样通过） | 渲染模型纯函数测试 |
| V-3 | append/refresh 不丢 count：页追加与列表刷新落地后 view 字段保留 | reducer 测试 |
| V-4 | 陈旧会话/重审形态登记：count=null 回退、count=0 真零、分歧残余按 §6①；count 冒充语义（null ≠ 0）有测试钉 | 本规格登记 + reducer 测试 |
| V-5 | 渲染模型纯函数测试：空态共渲染意图对钉死（空 rows + count>0 ⇒ 空态行 + 按钮同屏，按钮位次在候选行之前不变） | 纯函数测试 |
| V-6 | rig 读数 = maximum rig `group_disposition` stage **PASS**，或登记新阻塞 | 云设备 run（lead 合并后复跑判） |

**披露（不计 PASS）**：D-320 R5 的另一半（深行候选可达性，~1,000–1,220 次点击量级）仍开放；rig 的头部 await `共 <relations> 条` 与 confirm 路径在空列表窗下照常工作（D-5 MemberPages 路径零 `view.rows` 读，证据包 Q3）；runner 镜像 revision 未固定，跨日读数不可比（D-283 残余⑤原样）。

---

## 6. 风险与承接

1. **陈旧会话分歧（D-5）**：count 是落地时点快照；卡片自身的 D-5 路径在租约内**重算**权威成员集（`P503App.kt:2834` 同查先例），stale count 至多导致一次误点回合，该回合以「count 为 0 不开卡」的既有类型化行为收场（代码锚点：`P503App.kt:2766-2768` 与 `P503HostCoordinator.kt:843-854`）——无账务效应。残余登记于此，不得静默过期。
2. **count 读失败时的 fallback 语义**：失败落 null（绝不冒充零），门回到既有行枚举 = 当前行为；租约失败路径同 null。落地事件本身不受影响（F1：读失败不篡改）。
3. **D-320 R5 引用**：本批闭合「组入口可达」半边；「有界深行入口」的另一半仍按 R5 开放，闭合条件以 R5 原文为准。

---

## 7. 边界

入：本文、S1/S2/S3 的 6 个 tracked 产品文件与 3 个测试文件、D-322 决定条目（随批登记）。

出：不动 stage 预算/marker/统计量；不动列表语义/顺序（`ACC-COUNT-01`/`ACC-SEL-01`/`ACC-SORT-01`/`P704GP-ACC-ORDER-01` 一字不动）；不动 `Ledger.sq` 与读端口（count 查询已存在，零 DDL/零迁移）；不改 `.external/`；不触碰 P8-04 线的文件面（`tools/python/android_scale/fixture.py` 的 P8-04 相关面、P8-04 规格/模板文件）；不改 `docs/PROJECT_STATE.local.md` 与 `docs/PROGRESS_LOG.local.md`。
