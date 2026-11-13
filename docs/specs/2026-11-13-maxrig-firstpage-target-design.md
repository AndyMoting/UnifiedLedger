# maximum rig 的 `detail_decision` 改判首页目标——不再做深行跳转

**状态：** **approved v1**（2026-11-13，批准依据 `D-320`）
**Revision：** v1（2026-11-13 首稿；同日按 D-320 批准，正文未改）

**指针：** D-319 规格 `docs/specs/2026-11-13-p7-04-b1-b4-windowed-read-design.md` §13（`:226-235` 的 maximum rig 读数与「下一动作」）；分页读规格 `docs/specs/2026-11-05-p7-04-import-review-paged-read-design.md`（`:31`/`:49` 首屏只读一页、`Rows(rows)` 保留为兼容入口）；决定 `D-293`/`D-294`（分页读与 61k 首屏）、`D-319`（§13 读数与「禁止调大预算/跳过 stage」）；验收标识 `P704GP-ACC-ORDER-01`（页序 == 现状全量序，`docs/specs/2026-11-04-b6-group-card-paged-enumeration-design.md:119`）。

本文不改写上述任何既有裁决：不改 `detail_decision` 的 180 s 预算（`tools/python/android_scale/result.py:265-270` 的默认上限）、不改 stage 集合、不改 `P704GP-ACC-ORDER-01`、不改任何产品读契约与 schema。

---

## 1. 问题现状（全部 `file:line` 可复核）

### 1.1 读数：61k 上 `detail_decision` 死在候选寻址

run `43386393213`（`fa2ce94f` = D-4 合入后的 `main`；61k 候选 / 150,000 重复关系；`android-scale.yml`，mode=maximum）→ host `status=ERROR`、`phases=["prepare"]`、`elapsed=5548 s`；`instrumentation-chain.txt` 的失败是
`java.lang.IllegalStateException: unique candidate not reached (amount=2071.99 CNY sawTargetRow=false clickAttempts=0 scans=65)`，栈在 `AndroidScaleUi.openCandidateByAmount`（`AndroidScaleUi.kt:943`，该提交的树）← `AndroidScaleLongInstrumentedTest.chain`（`:441`，该提交的树），即 **`detail_decision`**；该次 instrumentation 全程 408 s（对照 run `43322793290` 是 1829.8 s 且卡在 `saf_import`）。

### 1.2 该目标当时是谁

- 目标金额 `2071.99 CNY` = 207,199 minor = `seed`(197,198) + 1 + `rowsPerSession`(10,000)，即**唯一会话（unique-rows）的 ordinal-0**：`uniqueSessionFirstAmount()`（该提交的 `AndroidScaleLongInstrumentedTest.kt:370`）。
- 复核列表（D-293 起）首屏只读一页 50 个候选：`P503App.kt:816` 的 `queryPage(ledger.ledgerId, IMPORT_REVIEW_PAGE_SIZE, null)`，页大小 `P503App.kt:5653` 的 `IMPORT_REVIEW_PAGE_SIZE = 50L`；`加载更多` 是最后一项 `ImportReviewRenderItem.LoadMore`（`P503ImportReviewPresentation.kt:1149`）。
- `openCandidateByAmount` 的机制是「`seekBackToTop("刷新清单")` → `ACTION_SCROLL_TO_POSITION` 跳到 `jumpIndex + 12` → 有界 160 步前向扫描」（该提交的 `AndroidScaleUi.kt:815`/`:829`/`:856`），`jumpIndex` 由调用方传 `spec.rowsPerSession`（该提交的 `AndroidScaleLongInstrumentedTest.kt:441`）。

### 1.3 目标真实位置是候选序 50,000，不是 10,012

- `prepare()` 的导入顺序是 `session-01 … session-05`，最后 `unique-rows.csv`（`AndroidScaleLongInstrumentedTest.kt:218`/`:244`）；candidate id 在每次接治内按行铸造（`UuidV7ImportIntakeIdSource.kt:17-30`），接治逐文件串行，所以**唯一会话的 candidate id 全大于五个共享会话**。
- 列表键集读按 `candidate_id` 升序（`Ledger.sq:9595` 的 `importReviewRowsForLedgerPage`，`:9664` 的 `ORDER BY candidate.candidate_id, duplicate.candidate_id`），故唯一会话 ordinal-0 的候选序是 **50,000**（5 × 10,000），不是 `rowsPerSession + 12` = 10,012。
- 10,012 只是**跳转目的地**，不是目标位次。D-319 §13 的「第 10,012 位」按「跳转索引」读才成立；按「候选位次」读不成立，本文更正。即便跳转生效，从候选项 10,012 起来的前向扫描也到不了 50,000（160 步上限只覆盖约 200 行）。

### 1.4 首页装的是 `session-01` 的行

五个共享会话按 `session-01 … session-05` 串行导入，`session-01` 的 **10,000 个 candidate id 全部小于**其它会话；键集第一页的 50 个候选因此**全部是 `session-01` 的行**（`maximum` 下 `rowsPerSession` 10,000 远大于页大小 50）。这条是 R1 的落点前提，与「金额」无关。

### 1.5 三类候选的可确认性判定（决定 R1 具体选谁）

判定链（三层，全部只读投影）：

1. **折叠只看「主体」方向**：复核行的 `duplicateStatus` 只折 `import_duplicate_candidate.subject_source_id = candidate.source_id` 的关系（`Ledger.sq:9575-9577` 的 LEFT JOIN），折叠优先级 `CONFIRMED_DUPLICATE` > `DEFERRED` > `CONFIRMED_DISTINCT` > `DISMISSED_LOOKALIKE` > `REJECTED`（`SqlDelightImportReviewReadAdapter.kt:865-876`）。
2. **关系的主体是「后导入者」**：接治写入关系时 `subject_source_id = 新导入记录的 source`、`possible_existing_source_id = 既有记录的 source`（`SqlDelightImportSpineStore.kt:273-285`，`:284` 的实参 `ids.sourceId.value`）。所以「作为 possible-existing 端点」**不进入**该候选的折叠。
3. **类与可勾选/表单门**：`null` 重复状态 → `PENDING_USER_DECISION`（`P503ImportReviewPresentation.kt:88-90`）；`DEFERRED` → `SUSPECTED_DUPLICATE_PENDING_REVIEW`（`:80`）；`CONFIRMED_DUPLICATE` → `DUPLICATE_CONFIRMED_BLOCKED`（`:79`）。仅前两类中的 `PENDING_USER_DECISION` 与 `RETAINABLE_SIMILAR` 可勾选（`:61-62`）；不可勾选的行**渲染不出勾选框**（`P503ImportReview.kt:905-914`，文案见 `:913`），**决策表单也不渲染**（`:825` 的 `if (face != null && rowClass.selectable)`）。

| # | 候选类 | 主体关系的来源 | 折叠状态 | 呈现类 | 可确认？ |
|---|---|---|---|---|---|
| ① | `session-01` 的 ordinal-0（共享金额，最终 6 重） | 它是**第一个**导入的会话，导入时无既有来源 ⇒ **零条主体关系**（`SqlDelightImportSpineStore.kt:255-285` 的 `existing` 为空）；后续会话只在它作为 possible-existing 端点时引用它，不参与折叠 | `null` | `PENDING_USER_DECISION` | **可直接确认**（勾选框 + 决策表单都在），且**在首页**（§1.4） |
| ② | 主 SAF 会话（`session-06`，`saf_import` 在 `detail_decision` 之前导入）的行 | 5 条主体关系（对 `session-01..05`），均 `DEFERRED` | `DEFERRED` | `SUSPECTED_DUPLICATE_PENDING_REVIEW` | **不可确认**：无勾选框、无决策表单，`决策已补全。` 无法出现；`group_disposition` 之后折成 `CONFIRMED_DUPLICATE` → `DUPLICATE_CONFIRMED_BLOCKED`，仍不可确认 |
| ③ | `unique-rows` 会话的行 | 金额与共享区间不相交 ⇒ 零关系 | `null` | `PENDING_USER_DECISION` | **可直接确认，但不在首页**：候选序 ≥ 50,000，需驱动约 200 次「加载更多」；这是 R5 的产品级承接 |

**由此更正两条前置判断：**

- 「`session-01` 的 ordinal-0 是疑似重复」不成立。共享金额只让**后导入**的共享会话（`session-02..05`）折叠出 `DEFERRED`；① 自身折叠为空。R1 原定目标（`session-01` ordinal-0）**本来就可直接确认**，不需要把 `unique-rows` 会话改成最先导入。
- 「首页必然包含 ordinal-0」不成立为**结构性**保证：列表按 `candidate_id` 排序，而 UUIDv7 同一毫秒内的位次由随机位决定（`UuidV7Generator.kt:9-12`/`:23-46` 的 74 位随机），固定 ordinal 不保证落在 50 个最小 id 里。因此 R1 的目标身份取「**首页第一个可确认的候选**」，金额由渲染行读出并按规格区间校验（§3 R1），不把某个 ordinal 写死。

---

## 2. 目标与非目标

**目标**

1. `detail_decision` 的候选寻址改成**首页目标**：一次有界前向扫描即可到达，不再依赖深行跳转或「加载更多」。
2. `detail_decision` 验的语义不变（打开候选详情 → 载入分类与账户 → 批量确认路径 → 决策补全后重开并核对「决策已补全。」→ 回列表），只换「目标是谁」。
3. 全部受影响簿记点逐条点名并更新（§3 R2、§5 V-4），不留「唯一会话最后导入」这类过期断言。
4. 把产品侧「61k 下深行候选不可达」登记为承接（R5），本批不修。

**非目标**

- 不改任何 stage 预算（尤其 `detail_decision` 的 180 s）、不跳 stage、不放宽 `openCandidateByAmount` 的 160 步上界、不以「找不到就成功」收场（R4）。
- 不改产品代码（`app-ui/**`、`ledger-*/**`、`android-app/src/main/**`）、不改 schema/迁移、不改 `.github/**`、不改 `.local.md`。
- 不改夹具生成器（`tools/python/android_scale/fixture.py`）、不改 `AndroidScaleFixtureSpec`（`android-app/src/scaleTest/**`）、不改 `prepare()` 的导入顺序（§1.5 已证明无需重排）。
- 不改候选顺序、折叠语义、展示文案、分页读契约。

---

## 3. 裁决（R1–R5）

### R1 `detail_decision` 的目标改成「复核列表首页上的一个可确认候选」

- **身份规则**：目标 = 复核列表第一页里**第一个可确认的候选行**（渲染序，自上而下）。判定用渲染出的类标记：元信息行以 `重复 无` 结尾（`importCandidateMetaText`，`P503ImportReviewPresentation.kt:162-169`）即折叠状态为空 = `PENDING_USER_DECISION`。**前置条件（评审 N1）**：该行 `candidateStatus` 必须是 `pending_confirmation` —— `incomplete` 与已 `confirmed`/`rejected` 的行同样渲染 `重复 无`，但都不可勾选、无决策表单（`P503ImportReviewPresentation.kt:73-76`、`P503ImportReview.kt:905-914`）。本夹具每一行都是 pending，故该标记充分；若将来出现非 pending 行，选择器会点开无表单的行，届时需把状态 token 并入标记。
- **位置保证**：`maximum` 下首页的 50 个候选全部是 `session-01` 的行（§1.4），而 `session-01` 的行零主体关系 ⇒ 全部 `PENDING_USER_DECISION`（§1.5 ①）。更小的诊断 profile（`local-small`/`local-medium`）首页可能混入后续会话的行（如 `local-small` 为 20 + 20 + 10），但行序仍以 `session-01` 打头，且 selector 的类标记会跳过混入的 `DEFERRED` 行。所以首页一定有可确认候选，且第一个可确认候选就是 `session-01` 的行。
- **金额推导**：不写死金额，也不假定 ordinal。目标行的金额 = `seed + 1 + ordinal + sessionAmountOffset(0)`（`AndroidScaleFixtureSpec.kt:64` 的 `sessionAmountOffset(0)` = 0），其中 `ordinal ∈ 0 until rowsPerSession`；由规格推导出**区间** `[seed + 1 + sessionAmountOffset(0), seed + 1 + sessionAmountOffset(0) + rowsPerSession)`，选中行渲染出的金额必须落在区间内，选中值写入 `selectedAmount`。`sessionAmountOffset(0)` 若在别的 profile 不为 0，区间随它平移（当前实现直接乘用它，不写常数）。
- **`jumpIndex` 改成小的有界常量**：`FIRST_PAGE_JUMP_INDEX = 0`（`AndroidScaleLongInstrumentedTest.kt:43`）。它是**候选在第一页内的索引**。**真正的不等式（评审 N2）**：`jumpIndex + 12 ≤ 前导渲染项数 − 1`——`maximum` 下前导约 200 项（接治记录上限），故 `jumpIndex ≤ ~188`；「页候选数 50」只是**充分**上界而非必要上界，页大小一旦超过「前导项数 − 12」该界即失效。取 0 的理由：扫描只向前、永不回退，越过目标即不可恢复；而 `jumpIndex + 12` 的 12 项松弛落在候选行之前的非候选前导里（标题栏、格式段、接治摘要的记录行等，见 `P503ImportReviewPresentation.kt:1075-1103`），不会越到行后。
- **首页内「同一金额只出现一次」论证（判据不放宽）**：首页只装 `session-01` 的行（§1.4）；同一会话内每行金额 = `seed + 1 + ordinal`，ordinal 互不相同 ⇒ 金额逐行互不相同。因此「可见行里多于一个同金额匹配即异常」的既有判据仍成立，本批**不改** `openCandidateByAmount` 里的 `matches.size > 1` 检查（`AndroidScaleUi.kt:801`），selector 端对选中金额再做一次同类断言（`:847`）。共享金额在**整表**里确有 6 重（`session-01..05` + 主会话）。**评审 B1 的修正**：金额单独不再构成身份——重开路径（`:513`/`:537`）也会走 `openCandidateByAmount`，在更小的诊断 profile 上首页会混入后续会话的行，而 `sessionAmountOffset(i) == 0` 让它们与本目标**金额相同**；那些行折叠出 `DEFERRED`、没有决策表单。故本批把 `openCandidateByAmount` 的匹配条件从「金额相等」收紧为「金额相等**且**不折叠重复状态（`重复 无`）」，把类标记做成两条路径共用的判别器（`hasNoFoldedDuplicateStatus`，`isConfirmableCandidate` 改为它的别名）。这是**收紧**，不是放宽。

### R2 受影响的簿记点（逐条，实施见 §5 V-4）

1. **目标选择**：`AndroidScaleLongInstrumentedTest.kt:433`（原提交的树）`val amount = uniqueSessionFirstAmount()` → `:464` `val amount = ui.openFirstPageCandidate(firstPageAmountRange(), FIRST_PAGE_JUMP_INDEX)`。
2. **`selectedAmount`**：`:434` → `:465` `state.put("selectedAmount", amount)` 语句保留，来源改为「渲染行读出并过区间校验」；下游 `:670`/`:720`/`:734`（现 `:699`/`:752`/`:766`）的用法不变，含义从「唯一会话 ordinal-0 金额」变成「首页目标行金额」。
3. **首次打开后的二次打开删除**：原 `:441` 的 `ui.openCandidateByAmount(amount, spec.rowsPerSession)` 删除——selector 已经打开了详情，保留它会在详情屏上跑导航（详情屏没有候选行，必然耗尽扫描）。
4. **重开目标**：原 `:484`/`:508` → `:513`/`:537`，`spec.rowsPerSession` → `FIRST_PAGE_JUMP_INDEX`。
5. **`reopen()` 的决策事实**：原 `:719` `check(confirmed.session == uniqueSessionInputRef() && confirmed.ordinal == 0)` → `:751` `check(confirmed.session == firstPageSessionInputRef())`，`:752` 的 `check(confirmed.amount == state.getLong("selectedAmount"))` 保留（`session-01` 的金额逐 ordinal 互异，会话 + 金额唯一确定一行）；原 `uniqueSessionInputRef()`（原 `:361`）删除，改为 `firstPageSessionInputRef()`（`:376`，`state.sessions[0]`）。
6. **`uniqueSessionFirstAmount()` 的归属**：原 KDoc 改写为「仅作为 traversal 的 anchor」（`:392-401`）；值不变（`seed + 1 + rowsPerSession`），调用点 `:833` 不变。
7. **批量确认摘要**：`已入账 0 项 / 跳过 1 项` 断言文本不变（`:503`）；注释点名跳过码 `IMPORT_BATCH_DECISION_INCOMPLETE`（`P503ImportBatchPresentation.kt:352`）：目标是可确认行、但决策尚未补齐，所以是「跳过 1 项」而不是「不可确认跳过」。
8. **`openCandidateByAmount` 的契约文档与实现拆分**：`AndroidScaleUi.kt:754-944` → `:754-1022`；拆成 `openCandidateByAmount`（金额 + 不折叠重复状态，唯一性判据原样）/ `openFirstPageCandidate`（首页选择）/ 私有 `openCandidate`（共用的 seek + 跳转 + 160 步扫描 + 点击校验）。原 KDoc 里「金额全局唯一」「`jumpIndex` = `spec.rowsPerSession`」两处过期表述改写；160 步上界、2 次 attempt、8 次 stationary 判据、0.12 步长全部原样。
9. **新增的夹具不变量断言**：目标金额必须落在 `firstPageAmountRange()` 内（`:464` 传入 selector），目标行必须渲染「重复 无」；两者任一不成立 ⇒ selector 扫不到行并以 `candidate not reached (...)` 失败（`AndroidScaleUi.kt:1021`），不会静默通过。

### R3 `detail_decision` 验的语义不变

仍是：打开候选详情 → 载入分类与账户（`ui.click("○ " + categoryLabel)` / `ui.click("○ " + accountLabel)`）→ 批量确认路径（勾选 → 进入批量确认 → 授权逐项入账 → 回到列表）→ 决策补全后重开该候选并核对 `决策已补全。` → 回列表（`clickUntil("返回", "刷新清单")`）。只换「目标是谁」，不换「验什么」。

### R4 禁止的救法

不调大 `stage("detail_decision", …)` 预算（`AndroidScaleLongInstrumentedTest.kt:463` 与 `result.py:265-270` 的 180 s 上限都不动）；不跳过该 stage；不放宽 `openCandidateByAmount` 的 160 步上界；不以「找不到就成功」收场。目标不可达时仍然硬失败。

### R5 产品级承接：61k 下深行候选不可达

**登记为新的产品级开放问题，本批不修。** 事实：复核列表首屏只读 50 行（`P503App.kt:816`），欲到达候选序 50,000 的 `unique-rows` 候选需约 200 次「加载更多」（每次 50 行），既超 `detail_decision` 的 180 s 预算也没有用户意义。**闭合条件**（二选一）：要么给导入复核列表一条**有界的深行入口**（例如按 `candidate_id` 键集直跳页的测试专用入口，或按会话/组过滤后寻址），要么裁决该可达性不在本期范围。归属：分页读规格（`docs/specs/2026-11-05-p7-04-import-review-paged-read-design.md`）的后续片；本批只登记，不改任何产品代码。

---

## 4. 可写范围

- 入：`android-app/src/androidTest/kotlin/com/unifiedledger/android/AndroidScaleLongInstrumentedTest.kt`、`android-app/src/androidTest/kotlin/com/unifiedledger/android/AndroidScaleUi.kt`（均为 test-only）；本文；`docs/DECISIONS.md` 的 D-320 条目。
- 出：产品代码（`app-ui/**`、`ledger-*/**`、`android-app/src/main/**`）、`android-app/src/scaleTest/**`（`AndroidScaleFixtureSpec` 不动）、`tools/python/android_scale/**`、`.github/**`、`docs/PROJECT_STATE.local.md`、`docs/PROGRESS_LOG.local.md`、schema/迁移。

---

## 5. 验收向量

| # | 向量 | 证据类型 |
| --- | --- | --- |
| V-1 | 首页目标可达：`openFirstPageCandidate` 在既有 160 步上限内返回金额 `m`，`m` 落在 R1 的规格区间（`maximum` 下即 `[seed+1, seed+1+rowsPerSession)`），详情屏显示 `m` | 云设备 run（`android-scale.yml`，mode=maximum）的 `detail_decision` PASS + 链上过程断言（`AndroidScaleLongInstrumentedTest.kt:464`、`AndroidScaleUi.kt:1021`）；静态锚点见 §1.4/§1.5 |
| V-2 | `detail_decision` 完成：stage 状态 PASS 且 `elapsedMs ≤ 180000`；决策事实为「`session-01` 的行 + `selectedAmount`」，重开核对 `决策已补全。` 成功 | 同一设备 run 的 device evidence（`reopen` 的 `check(...)` 见 `:751-752`）；`result.py:265-270` 的 stage 上限 |
| V-3 | 五阶段 `phases` 齐全：`prepare / chain / reopen / replay / final-reopen`，且 `stages` 集合恰为 `preparation / coldstart / saf_import / detail_decision / group_disposition / batch_confirmation / detail_monthly_refresh / reopen / traversal / same_request_replay / final_reopen` | 同一设备 run 的 `host.json`（`result.py:11` 的 `PHASES`、`:16-18` 的 `STAGES`、`:257` 的集合相等判据） |
| V-4 | 决策事实与摘要断言按新目标更新：§3 R2 的 9 条逐条落地；`reopen()` 用「会话 + 金额」定位确认行；`已入账 0 项 / 跳过 1 项` 与 `已入账 1 项` 的摘要断言文本不变且语义仍成立 | 静态 diff 复核（`git diff` 的 2 个 test-only 文件）+ 同一设备 run 的 `reopen` PASS；`git diff --check` 与 `PYTHONPATH=tools/python python -m project_docs .` 的 exit 0 |

**实施落点（2026-11-13 落地后）：**

- `AndroidScaleLongInstrumentedTest.kt`：`:43`（`FIRST_PAGE_JUMP_INDEX = 0`）、`:376`（`firstPageSessionInputRef`）、`:378-390`（`firstPageAmountRange`）、`:392-401`（`uniqueSessionFirstAmount` 现仅为 traversal anchor）、`:463-466`（stage + selector + `selectedAmount`）、`:503`（批量摘要）、`:513`/`:537`（重开）、`:743-752`（`reopen()` 的决策事实）。
- `AndroidScaleUi.kt`：`:781-804`（`openCandidateByAmount`）、`:837-852`（`openFirstPageCandidate`）、`:867-1022`（私有 `openCandidate`，`:934` 的 160 步、`:905` 的跳转松弛）、`:1262-1265`（`amountMinor`）、`:1288`（`isConfirmableCandidate`）。

---

## 6. 风险与承接

### 6.1 头号风险：寻址修好后，`detail_decision` 的第二半会撞上批量确认的整表读

- `runImportBatchDispatch` 的**派发前相**无条件读整表：`P503App.kt:3203` 的 `loadRows = { facade.queryImportReviewRows?.query(ledger.ledgerId) … }`；批量完成后的刷新同样读整表：`P503App.kt:3265` → `:3153`。`detail_decision` 本体就含一次批量确认（勾选 → 进入批量确认 → 授权），`batch_confirmation` stage 还有第二次。
- 这条读与 D-319 §1.2 里 25 分钟的那次是**同一形状**：整表读 → 适配器按 10,000 候选分页 → 行投影携带 D-308 加的两个逐行派生列（`evidence_id` 标量子查询、`used_for_reconciliation` 嵌套 `EXISTS`，`Ledger.sq:9624-9639`），而 `import_evidence` 没有 `(ledger_id, source_id)` 索引（D-319 §1.4、其 D-7 待测量）。run `43322793290` 的失败栈就停在 `ImportReviewRowsForLedgerPageQuery.execute` 的游标填充。
- 因此：**本批修好寻址，只保证「目标可达」；`detail_decision` 仍可能在这两次整表读上超过 180 s**。这不属于 R1 的寻址问题，也不允许用调大预算/跳 stage 绕过（R4）。归属 D-319 的 D-7（先测量后裁决索引）与 D-8（其余五处整表读，其中批量前相/批后刷新是第二片），本批只登记，不改产品代码。
- 同一风险覆盖 `batch_confirmation` stage（同样两次整表读）与 `group_disposition` 之后的落地读；它们不是本批引入的。

### 6.2 stage 预算的算术（若 6.1 的读被单独修好，预算本身仍然偏紧）

- 首页视图的候选行在**非候选前导**之后：标题栏 + 格式段 + 接治摘要（`session-06` 的 10,000 条记录按 200 条上限渲染，`P503ImportReviewPresentation.kt:224-239`）约 200 行文本，随后才是组头和 50 行候选。
- 步长 0.12（约 1.5 个候选行 ≈ 250–300 px）是按**候选行**标定的（`AndroidScaleUi.kt:992-1007` 的 round 21 读数）；对约 200 行文本前导，它是保守的：按每行约 40–50 px 计，200 行约 8,000–10,000 px ≈ 35–40 步才露出第一行候选。单次打开约 35–40 步，两次打开（首次 + 决策补全后重开）合计约 70–80 步。
- 步单价没有直接读数：run `43386393213` 的 `detail_decision` 只有 `scans=65` 这一个计数（且大部分是落地在列表底部后的 stationary 采样），不能反推每步秒数。按既有链上 `await`/`settle` 的保守估计（每步 1–3 s），70–80 步约 70–240 s——**与 180 s 上限同一量级**。
- 因此本批把「步骤数」压到机制允许的最小（首页目标，不再有 50,000 行的深行段），但**不**改步长、不改 160 步上界、不改预算（R4）。若设备读数显示仍超预算，处置顺序是：先按 6.1 裁决整表读，再评估前导段的步长/锚点（需要单独读数与裁决，不在本批）。

### 6.3 a11y 树规模

- 首页视图约 250 个节点（标题/格式段/接治摘要 200 条记录上限 `P503ImportReviewPresentation.kt:224-239` + 1 个组头 + 50 行 + 加载更多）。`candidateNodes()` 的两条路径（带 `查看候选详情` 动作的标签路径、按金额+元信息上升的兜底路径，`AndroidScaleUi.kt:599-648`）都在既有实测树规模内工作过。风险仍是 D-203 家族的客户端缓存滞后：selector 复用既有 2 attempt + 每 8 步强制刷新的节奏，不新增机制。

### 6.4 幽灵点击（半可见的首行）

- 首个可见候选行可能贴在视口下缘（半可见）；`clickNode` 先走 `ACTION_CLICK`（通常成功），坐标兜底可能落空。失败时的处置与既有 `openCandidateByAmount` 一致：`await(20000)` 未开详情就继续扫描；两次 attempt 各自重新 seek 顶部重扫，仍找不到才硬失败。登记为残余，不加新机制。

### 6.5 本机制依赖的夹具不变量，以及夹具变化时先红哪条

1. **`prepare()` 的导入顺序**（`session-01` 最先、`unique-rows` 最后）：`firstPageAmountRange()` 是 `session-01` 的区间的**第一个**红点——若顺序改成 `unique-rows` 最先，首页装的是唯一会话的行，金额落在 `[seed+rowsPerSession+1, …)`，selector 在首页扫不到区间内的行，以 `candidate not reached` 失败（不会静默选错）。
2. **`session-01` 零主体关系**（它是第一个导入的会话）：若夹具改成先导入别的共享会话、或同一会话内出现重复金额，首页会出现 `DEFERRED` 行；`isConfirmableCandidate`（`重复 无`）扫不到可确认行 ⇒ 同样以 `candidate not reached` 失败。
3. **candidate id 的时间前缀序**（UUIDv7，`UuidV7Generator.kt:9-12`/`:23-46`）：首屏 50 行的**ordinal 组成**依赖同毫秒随机位，本批正是为此不写死 ordinal；若生成器改成单调计数器，选区仍是「首页第一个可确认行」（结论不变，只少一条随机性说明）。
4. **产品页大小与展示上限**：`IMPORT_REVIEW_PAGE_SIZE = 50L`（`P503App.kt:5653`）、记录行展示上限 200（`P503ImportReviewPresentation.kt:224-239`）。页大小变小只会让首页行更靠近顶部；变大则更多 `session-01` 行进入首页。两者都不改 selector 的判据，但 `FIRST_PAGE_JUMP_INDEX` 的「上界 = 页候选数」说明要跟着改（代码里目前以注释记录该上界）。
5. **渲染的类标记**：`重复 无` 由 `importCandidateMetaText` 产出（`P503ImportReviewPresentation.kt:162-169`）。文案改动 ⇒ `isConfirmableCandidate` 先红（扫不到行），属于硬失败而非误确认。
6. **共享金额的整表 6 重**：`openCandidateByAmount` 现在按「金额 + 不折叠重复状态」匹配（B1 修正），所以整表刷新后仍安全——目标行折叠状态为空，其它会话的同金额行折叠出 `DEFERRED`。重开时目标行已被确认（`RESOLVED`，显示序最后一组），`重复 无` 依旧成立；若分组顺序或类标记文案改变，该检查会以「not unique among unfolded rows on screen」硬失败，不会被绕过。

### 6.6 承接（不得静默过期）

- **R5**：61k 深行候选可达性（§3 R5）。
- **6.1**：批量确认的整表读（D-319 D-7/D-8 的第二片），其闭合 = 计划读数 + 有界化，或明确裁决。
- **V-1..V-3 的设备证据**：B6 与 maximum 两个 rig 仍未进 PR 分片，需手动 `workflow_dispatch`（D-319 残余⑤原样）。

---

## 7. 边界

- 入：本文、`D-320`、两个 test-only 文件的 tracked 变更。
- 出：零产品代码（`app-ui/**`、`ledger-*/**`、`android-app/src/main/**`）、零 schema、零迁移、零依赖、零 CI 配置、零 `.external/`；不改夹具生成器与 `AndroidScaleFixtureSpec`；不改任何 stage 预算/统计量/marker；不改 `P704GP-ACC-ORDER-01`（页序 == 全量序：本批只换 rig 的目标与簿记，产品页序一字不动）；不改 D-293/D-294/D-319 的裁决文本与开放状态。


---

## 8. 第一次设备读数：V-1 通过、V-2 撞在批量确认的整表读上（2026-11-13，§1–§7 正文未改）

**读数**：run `43406118938`（`ebc846b8`，61k / 150,000，`android-scale.yml` mode=maximum）→ host `status=ERROR`、`phases=["prepare"]`、`elapsed=4792 s`；`instrumentation-chain.txt` 的失败是 `IllegalStateException: scale deadline exceeded` at `AndroidScaleLongInstrumentedTest.tick:130` ← `AndroidScaleUi.await:245` ← `AndroidScaleLongInstrumentedTest.chain:473`。

**`:473` 是 `ui.await { ui.has("导入", prefix = true) }`** —— 即「授权逐项入账」之后等导入面回来。也就是说链条已经走完：首页目标选中（`openFirstPageCandidate` 返回金额并过区间校验）→ 「决策未补全，尚不可提交确认。」断言 → 勾选候选 → 进入批量确认 → 授权逐项入账 → **卡在批量确认的派发**，直到 stage 的 180 s 预算耗尽。

**判定**：
- **V-1（首页目标可达）= 通过**：寻址改造生效，失败点从 `openCandidateByAmount` 的 `sawTargetRow=false` 前移到批量确认之后。
- **V-2（`detail_decision` 完成）= 未通过**：失败在 `:473` 的等待上。
- **V-3（五阶段 `phases` 齐全）= 未达**；`phases` 仍只有 `prepare`。
- **V-4（簿记断言）= 未被触达**（它在其后的 `reopen()` 阶段）。

**与预登记的吻合**：§6.1 已写明「本批只保证目标可达；`detail_decision` 仍可能在这两次整表读上超过 180 s」，并点名派发前相与批后刷新。本次读数**正好停在派发前相**，`db` 的 `mtime=19:29`（接治写入在一小时前就结束了，说明这段等待不是写库，而是读）。

**下一动作（承接）**：D-8 的第二片——把批量确认的派发前相与批后刷新的整表读换成**按待派发项 id 集合的有界读**（项来自已加载页，数量有界），并按 D-7 先取 `EXPLAIN QUERY PLAN` + 61k 页耗时，再决定是否另立加性索引迁移批。**不得**调大 `detail_decision` 的 180 s 或跳过该 stage（R4 原样）。

**证据差异披露**：合并头 `e1c6386f` 比该 run 的 `ebc846b8` 多两个提交，内容是 B1 的匹配器收紧（在 `maximum` 首页上等价于 no-op：首页全是 `session-01` 的行、全为 `重复 无`）、失败串带 label、以及文档；产品与 rig 的判据集合不变。
