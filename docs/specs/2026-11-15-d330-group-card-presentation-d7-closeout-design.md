# D-330 设计门草稿——组确认卡呈现形态 + D-7 收口批设计（maximum rig 卡片头不可达）

**状态：** **approved v2**（2026-11-15；用户裁决 Q1=C / Q2=恒默认有界不记忆 / Q3=A-VIS 重新锚定 / Q4=进度行原位 / Q5=②分批；本规格即实施批的任务书）
**Revision：** v2（2026-11-15；用户裁决记录：Q1 **案 C 有界成员行 + 展开入口**、Q2 **恒默认有界不记忆**、Q3 **A-VIS 重新锚定为「展开路径逐条列出全部」**（frozen duty 实质 = payload 全文可达、绝不截断，系统默认有界但一次动作即逐条列出全部）、Q4 **进度行维持原位**、Q5 **②：呈现独立批 + 健壮性批（count 有界 + Throwable）+ GC 测量腿**——Q6 随健壮性批落地为「租约块一处改 Throwable 分流，其余 15 处登记承接」。v1（2026-11-15；v0 起草后经 ul-explore 三案爆炸半径校正——关键校正：①三案 reducer/事件/host 全部零改动，卡片展开度走 presentation 本地态（先例 comparisonExpansion P503ImportReview.kt:445-476，注释明写 no reducer event/no persistence）；②案 B stickyHeader 只在视口越过 header section 后钉住，D-329 现场「视口停在按钮/记录行区（卡片头上方）」形态不覆盖，单案采用有 rig 复跑仍红风险；③案 C 的 PresentationTest.kt:447（默认 expanded 断言）原样绿、案 A 直接推翻该断言且需重裁 PRODUCT_REQUIREMENTS.md:79 tracked 权威；④b6 V-11 判读量三案均不动（卡可见 marker 与 totalCount 口径不碰），但 (a) 禁止实现为「header 首窗前常驻」（否则 click-to-card 与 D-282 史不可比）；⑤b6 落地后当日同制复跑再续用基线。行号为主检出 30e57b94 只读读数。）

**指针：** 判读条 `docs/PROGRESS_LOG.local.md:5298-5308`（run `43621770763` 三假设证伪 + 新根因 + D-330 候选三案）、`:5279-5286`（D-328：900s 测量、Explicit GC 风暴登记、878 次/轮）、`:5221-5233`（D-327 位次修复）；先例规格 `docs/specs/2026-11-13-p7-04-b1-b4-windowed-read-design.md`（B1/B4 有界首窗：§11 窗封顶不变式、§12 D-7 测量腿、§13 深行寻址）、`docs/specs/2026-11-14-d322-count-driven-group-entry-design.md`（count 门双臂 when 先例）；决定 D-280（B6 组卡分页枚举，§3.3 首窗即开卡、§3.4 确认授权恒为全组、`P704GP-ACC-ORDER-01` 冻结页序）、D-293（keyset 分页读）、D-319（B1/B4 批准）、D-320 R5（深行可达另一半仍开放）、D-321（D-8 第二片）、D-322（count 驱动组入口）、D-327（按钮位次）、D-328/D-329（探针与轮里程碑 trace）；A-VIS frozen duty 注释 `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReview.kt:648-651`；checkpoint D-15（`docs/PROJECT_STATE.local.md:38`，写面串行规则）。

本文不改写上述任何既有裁决：不动枚举轮读链（成员页/窗封顶/hop 结构，B1/B4 §11 不变式原样）、不改 count 门（D-322 双臂 when 原样）、不改 await 谓词文本（产品契约「将逐条提交人工审核判定为重复，每条独立生效；某一条失败不影响其余各条。共 N 条。」，`P503ImportReviewPresentation.kt:790` 一字不动）、不改 stage 预算/marker/统计量、不改 `P704GP-ACC-ORDER-01`。

---

## 1. 背景与证据

**全部 `file:line` 为主检出 `30e57b94` 读数（2026-11-15 只读复核），实施前以 explore 校正。**

- **maximum rig run `43621770763`（attempt 3，trace 2112 行）判读**（`docs/PROGRESS_LOG.local.md:5298-5308`，证据 `local/artifacts/maxrig-43621770763/`）：组枚举轮 **190.8 s 完全健康**——`round start` 02:39:13.505 → `count shape=value=50000` → 首页读 3.7 s → `seam firstWindow open=true` + `event kind=Start`（首窗开卡成功，S1 机制 maximum 档首次兑现）→ 100 成员页 ×200 行（avg 1.89 s/页，DB 读健康）→ 498 窗 ×200 行全部 `hopRun gen=current`（0.38 s/hop，无饥饿）→ 498 × `event kind=Page` 全落 reducer、`continuation dropped=false`（无 stale）→ `Completed` + `round end failed=false anyPageLanded=true`。
- **根因在 render 层**：卡片以「扁平展开」装配——header → 99,600 个 `GroupDispositionItem` → footer（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReviewPresentation.kt:1317-1326`）⇒ 卡片头被自己的成员体推到 LazyColumn 视口外 ⇒ a11y 树只含视口内节点 ⇒ rig await 谓词 `ui.has(...)`（`AndroidScaleUi.kt:184`，`isVisibleToUser`）永 false ⇒ 900 s 超时（`AndroidScaleLongInstrumentedTest.kt:833`）。用户滚回需 ~2,500 屏（498 窗 × 200 行），实际同样不可用。
- **D-327 同族**：D-327 修「按钮被记录行埋住」（位次修复，PR `#355`）；D-329 判读条定性本形态为**同族缺陷第三形态：本组 UI 面板用扁平 LazyColumn 装超大集合，任何「面板头」都会被面板体推出可达视口**（`:5303`、`:5305`）。
- **await 语义注记**：卡片头披露行 = `groupDispositionCardHeaderDisclosure(itemCount)`（`P503ImportReviewPresentation.kt:790`），rig 谓词文本 `groupCardDisclosureText(relations)`（`AndroidScaleLongInstrumentedTest.kt:67`）——**谓词文本 = 产品契约，本批不动**。修正「卡片头在视口外」的观察缺口（仪器与被测面同盲区，`AndroidScaleUi.kt:164-178` cached root）不属本批（仪器侧，非产品缺陷，判读条 `:5304` 已登记）。
- **四项并入议程（D-7 设计门升格，checkpoint `docs/PROJECT_STATE.local.md:38`）**：
  1. **计数查询无页界**：`importDuplicateCandidateCountForSession`（`ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq:11916-11943`）三表 join + 每行相关 `max(sequence)` 子查询，最大档下点击前后各读一次（先例 `P503App.kt:2834` 同查 + D-322 落地租约读），D-8 后仍无页界——61k/150k 下秒级量级（B1/B4 §12 已点名其承重）。
  2. **`catch (failure: Exception)` 不捕 Error**：枚举轮租约块 catch（`app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt:3100-3102`，任务书引 ：3045 为旧锚点）——`OutOfMemoryError` 等穿透后不走 `enumerationFailed`，轮以崩溃/静默形态收场，`enumerationFailed` 判定失真。注意「catch Throwable」本身有反模式风险（见 §3 议程项 3），登记 ≠ 直接采纳。
  3. **GC 风暴归因**：D-328 登记 878 次 Explicit concurrent mark compact GC/轮（1.6–3.2 s 节律、堆 49% free 稳态、每次仅回收 ~4 MB；pre-i3a 树同窗口 0 次、prepare 相 0 次；仓内 `System.gc()` 零匹配）。最强仓内候选 = 每 hop 全表拷贝 + render 全量重建的分配节律（878 ≈ 750 hop + 常规，数量级吻合，d7-explore 文件图）。
  4. **组卡呈现形态（本文主体）**：§2 三候选。
- **A-VIS frozen duty**：整组确认页默认**全展开**（「执行前逐条列出」frozen duty，`P503ImportReview.kt:648-651` 注释原样：「this switch is the user's own density reduction — 收起 all, then 展开 all again — never a system default that hides the payload」）。**这是三案相容性的判据**：任何方案不得让系统默认态隐藏 payload。

**检查点（completion criterion）**：本节每一读数都可按 `file:line` 或 artifacts 路径复核；判读条引用与 run 编号一一对应。

---

## 2. 三候选呈现方案

**共同前提**：三案都只动 render 装配与卡片呈现，**不动枚举轮读链**（成员页读、窗封顶 `IMPORT_REVIEW_GROUP_WINDOW_ITEMS`、hop FIFO、reducer 落地时序原样——B1/B4 §11 不变式）；都把「卡片头 + 披露行」放回首屏可达区（await 谓词恢复为真可达性量测）；都保留 footer 确认恒为全组授权（§3.4 原样）。

### 2.1 案 A：折叠默认态（成员载荷默认收起，用户展开才铺开）

- **机制**：`view.groupDisposition` 非空时不再无条件铺 `page.items`（`P503ImportReviewPresentation.kt:1324`），改为按卡片态默认只渲染 header + footer；成员行由用户经「展开」控件铺开。现状 `comparisonExpansion` 是**逐条比较信息**的展开态（`P503ImportReview.kt:453`），不是卡片整体态——需区分两个层级：卡片级（成员行在不在 render 树）与条目级（比较 payload 显不显示）。
- **触碰面（待 explore 校正）**：`P503ImportReviewPresentation.kt` items 装配（:1317-1326 装配门）；`P503ImportReview.kt`（渲染分支 + 展开控件，:636-666 附近）；`ImportReviewView` 若需卡片展开态字段则 + reducer 落值（`P503Reducer.kt`）；UI 事件一族（新 toggle 事件）。**爆炸半径 = 三案中最大**（新增一个用户可见状态位 + 事件 + reducer 分支）。
- **与 A-VIS 相容性：最弱，需裁决**。「默认折叠」按 :648-651 注释字面即「a system default that hides the payload」——与 frozen duty 正面冲突。缓解路径：把 frozen duty 重新锚定为「条目级比较 payload 的默认全展开」而卡片级折叠是「列表密度」，即 payload 仍在（展开即全文渲染、`EXACT_BUSINESS_TUPLE` 逐条可读），只是默认不铺——**但这需要用户显式修订 A-VIS 的语义边界，不是实现细节**（→ Q2/Q3）。
- **用户故事（maximum 档）**：61k 用户点「整组标记为重复」，卡片头 + 「共 50,000 条」披露行 + footer 立即同屏；用户读披露行、按需展开前若干条核对，确认一次授权全组。**用户故事（b6 档，20k/relations=20000 形态）**：20k 组用户同样首屏见头；与现状差异仅是「不再有 20,000 行在下方等着滚」——逐条展开控件（J4 既有，`P503ImportReview.kt:684-698`）与「收起/展开全部」批量开关（:652-664）语义需在卡片级折叠下重定义（→ 开放问题 #4）。

### 2.2 案 B：sticky header（卡片头固定于 LazyColumn 顶部）

- **机制**：`LazyColumn` 的 `stickyHeader` API（`androidx.compose.foundation.lazy`，已有 import `P503ImportReview.kt:15`）把 `GroupDispositionCardHeader` 提为 sticky 槽位——成员行铺开照旧（99,600 行仍虚拟化），但 header 在滚动时钉在视口顶。`ui.has()` 对 sticky header 的可达成性取决于其在 a11y 树中的 `isVisibleToUser`（sticky 项钉住时在视口内，可见）。**平台行为需 explore 实证**：sticky 槽在初始未滚动时是否在视口内、Compose sticky header 的语义节点可见性在 API 层如何呈现（→ 开放问题 #1）。
- **触碰面（待 explore 校正）**：`P503ImportReview.kt` 渲染层（LazyColumn item 槽位声明改 stickyHeader，:463 附近 + :636-666 header 分支）；Presentation 装配**可能零改动**（items 序不变）或需给 header 打独立槽标记。**爆炸半径 = 三案中最小**（纯呈现槽位，无新状态、无新事件、无 reducer 面）。
- **与 A-VIS 相容性：最强**。全展开默认态原样（成员行全铺、payload 全显），frozen duty 注释一字不动；「执行前逐条列出」仍由铺开的成员行承载。风险不在语义而在形态：99,600 行铺开仍会让「滚到候选列表」变成 ~2,500 屏的旅程（用户可用性问题只解决一半），且 sticky 头 + 巨量虚拟行的滚动性能读数未知（GC 风暴候选之一恰是 render 全量重建，案 B 不减分配面）。
- **用户故事（maximum 档）**：rig 首窗开卡后 header 钉在视口 → await 谓词立即为真；用户滚动时披露行与「确认整组标记」始终在顶。**用户故事（b6 档）**：小卡片形态下 sticky header 与现状几乎无差（不满一屏时 header 本就在首屏）——行为退化风险最小。

### 2.3a 实施批 v3 修订记录（2026-11-15，随实施提交）

- **a11y 文案定稿**（沿 J4 纪律，进 P503ImportReviewPresentation.kt 纯函数）：入口行可见文案 查看全部 N 条（groupCardViewAllRowsText，N = totalCount − 有界前缀数，整组口径动态计算，不冻字面量）；contentDescription 查看全部组成员行：整组共 T 条，逐条列出（groupCardViewAllRowsDescription）；展开后收回控件可见文案 收起成员行（groupCardCollapseRowsText），contentDescription 收起组成员行：回到默认前 K 行（groupCardCollapseRowsDescription）。收回控件落位于展开态卡片头之后（不落列表尾部）——超大组下尾部控件不可达，D-327 同族教训；层级与「收起全部比较信息」批量开关并列（卡片态控件），不嵌套。
- **K 常量落点**：IMPORT_REVIEW_GROUP_CARD_BOUNDED_ROWS = 200 落 P503ImportReviewPresentation.kt（装配决策所在文件），独立于 P503App.kt 的 IMPORT_REVIEW_GROUP_WINDOW_ITEMS/IMPORT_REVIEW_GROUP_MEMBER_PAGE_SIZE，KDoc 写明三界互不可推导（B1/B4 §11 第 2 条同族登记）。
- **实施期发现**：①入口行条数口径判 page.totalCount > 有界前缀数（整组溢出数），落地数 ≤ K 的组不渲染入口行——与 header/footer 共 N 条同口径，续页落地期间文案保持为真；②totalCount 语义注意：D-322 后落地租约提交的 count 是整组时点快照，若实现按「落地数溢出」口径会导致续页落地期间入口行文案抖动，故选整组口径；③默认有界形态下卡片块从 1+10,000+1 变为 1+K+1+1（K=200 时 203 行），capScale 10,000 夹具的 items 总数从 20,018 变为 10,219，P503ImportReviewRenderItemsTest cap 钉改断言有界形态（V-1 呈现面）；④PresentationTest.kt:447（groupCardComparisonDefaultsToExpandedWhileTheDetailFaceDefaultsToCollapsed）原样绿——案 C 只界成员行列表，不界逐条比较信息默认展开（Q3 重锚定后两者分层）。
- **渲染项与控件**：sealed class 新增 GroupCardViewAllRows(beyondCount, totalCount)（stableKey group-disposition-card:view-all-rows）与 GroupCardCollapseRows（stableKey group-disposition-card:collapse-rows，在展开态标示展开态并前置可达）；装配签名加 groupCardRowsExpanded: Boolean = initialGroupCardRowsExpanded()（named-default 形制沿 initialGroupComparisonExpansion），reducer/事件/host/HostCoordinator 零改动。
- **v4 修订（评审 P1 位次修复，2026-11-15，D-327 同族）**：卡片装配位次上移——卡块（header + 成员行/入口行/collapse 行 + footer）从「待确认草稿头之后」上移到「进度行之后、最近导入块（IntakeSessionHeader，含 200 条 IntakeRecordLine 披露行）之前」。依据：评审判读——await 的 `ui.has` 从不滚动（`AndroidScaleUi.kt:164-178` 只走当前视口），卡块原位次 ≈220（按钮 ~17 + 进度行 + 最近导入块 + 200 条披露行 + 待确认 shell 之后），首屏（~25-30 item）下方 ~5 屏，D-330 有界化后卡片头仍在视口外，第四跑 await 仍超时（run `43621770763` 终态截图视口停在列表顶为证）。上移后卡开时卡片头落 ~19 位、首屏内可见；无卡时装配零变化。进度行位置不动（Q4）、按钮位次不动（D-327 成果）、记录行块仍在（200 条披露上限原样）、候选行序与页序冻结面不碰。位次钉同步：`theEnumerationProgressLineRendersWhileInFlightThroughTheContinuationPages`（cardHeader < firstRecordLine 翻转）与 `capScaleGroupDispositionRendersTheBoundedPrefixAndTheViewAllEntryByDefault`（headerIndex < firstRecordLineIndex 翻转）。

### 2.3 案 C：有界成员行 + 展开入口（首屏渲染前 K 行 + 「查看全部 N 条」）

- **机制**：items 装配把 `page.items.forEach`（`P503ImportReviewPresentation.kt:1324`）改为**渲染前缀有界**：卡片默认渲染前 K 行成员（K = 实现常量，量级 200，对齐 `IMPORT_REVIEW_GROUP_WINDOW_ITEMS` 同级界），其余以「查看全部 N 条」入口承载；用户点击后铺开全部（铺开后形态回到现状扁平）。与 B6 窗封顶的区别：窗封顶治理**落盘节律**（一窗 ≤200 项逐 hop），本界治理**render 树规模**（items 装配层是否把全部落盘项都吐进 LazyColumn）——两个界互不可推导（B1/B4 §11 第 2 条不变式的同族表述）。
- **触碰面（待 explore 校正）**：`P503ImportReviewPresentation.kt` 装配门（:1324 + 新截断分支）；`P503ImportReview.kt`（「查看全部」行渲染 + 点击回调接线）；事件/状态是否需要落 view（「已展开」是否跨重组保留——`remember` 级局部态可能就够，待 explore）。
- **与 A-VIS 相容性：中**。「默认只渲染 K 行」与「执行前逐条列出」的张力同案 A，但形态不同：payload 是**分批可达**而非默认全铺（用户一次点击后全量逐条列出，其后形态与现状等价）。若把 A-VIS 锚定为「展开路径必须能逐条列出全部」而非「默认铺开」，案 C 天然满足；但同样需要用户裁决该语义（→ Q2/Q3，与案 A 同一裁决点、不同选项）。
- **用户故事（maximum 档）**：卡片头 + 前 K 行 + 「查看全部 50,000 条」同屏；rig await 命中 header；用户点「查看全部」后进入现状形态逐条核对。**用户故事（b6 档）**：20k 组用户首屏见头 + 200 行 + 展开入口；比现状多一次点击才能滚到「全部铺开」，但首屏永远可用。

### 2.4 三案对照

| 维度 | 案 A 折叠默认态 | 案 B sticky header | 案 C 有界 + 展开入口 |
| --- | --- | --- | --- |
| 卡片头回首屏 | 直接（无成员行在前） | 机制钉住（视口内） | 直接（成员行 ≤K 在后） |
| 触碰面 | view 字段 + 事件 + reducer + 装配 + 渲染 | 渲染槽位（装配可能零改动） | 装配 + 渲染 +（局部态） |
| A-VIS 相容 | 最弱（需语义修订） | 最强（零语义变动） | 中（展开路径满足「逐条列出」） |
| 超大组可用性 | 首屏最好；展开后又回到 ~2,500 屏 | header 恒可达；滚到候选区仍 ~2,500 屏 | 首屏好；展开后同 A |
| 新增状态 | 卡片展开态（用户可见） | 无 | 展开态（可局部 remember） |
| rig 判定力 | 直接 | 取决于 sticky 的 a11y 可见性 | 直接 |
| GC/分配面 | 降（默认不装配 99,600 item） | 不降 | 降（展开前不装配尾部 item） |

**检查点（completion criterion）**：三案的机制、触碰面、A-VIS 相容性、双档用户故事齐备；每案触碰面带 file:line 占位并标注「待 explore 校正」。

---

## 3. 裁决点（Q1…Q6，路由给用户）

| # | 裁决点 | 选项 | 推荐 + 理由 |
| --- | --- | --- | --- |
| **Q1** | 呈现形态选型 | A 折叠默认态 / B sticky header / C 有界 + 展开入口 / 混合（如 B+C） | **推荐 C（次选 B）**：C 以最小语义代价换首屏可达 + 分配面下降（GC 风暴候选同源缓解）；B 触碰面最小但不动分配面、且 sticky 的 a11y 可见性未实证；A 与 frozen duty 冲突最直接。若 explore 证实 sticky 项在 a11y 树恒可见且 await 可命中，B 升为并列推荐。 |
| **Q2** | collapsed/有界默认值 | 默认收起/有界（推荐）；记住上次态；随组规模自适应（N > 阈值才折叠） | **推荐「恒默认有界/收起，不记忆」**：与 D-322 count 门「按当前读数定形态」同纪律——记忆态引入跨轮次陈旧风险（D-322 §6① 同族）；自适应阈值多一个 magic number 且 b6 档行为分叉。 |
| **Q3** | A-VIS frozen duty 的语义边界（仅 Q1 选 A 或 C 时触发） | ①frozen duty 重新锚定为「展开路径逐条列出全部」；②维持「默认全展开」字面 → 弃 A/C 改 B | **推荐①**（若 Q1 选 C）：:648-651 注释改写为一句话（系统默认有界、用户一次动作即逐条列出全部，系统永不静默丢弃 payload），frozen duty 的实质（payload 全文可达、绝不截断）保住。**此项必须用户裁决，不得由实现方自行解释**。 |
| **Q4** | 「正在整理重复组……」进度行与折叠/有界态的并存形态 | ①进度行维持原位（按钮后、卡片前），折叠态照旧显示；②进度行并入卡片头（头部加「整理中……」行）；③轮次进行中强制展开 | **推荐①**：D-329 判读证实轮次健康时进度行承载完整（S1 语义），卡片头与进度行是两个意图（前者 = 卡片已开，后者 = 续页在飞）；强制展开与 Q2 矛盾。 |
| **Q5** | 四项议程合批/分批 | ①全部同批（呈现 + count 有界 + Throwable + GC 归因）；②呈现形态独立一批，其余三项一批；③四项各自一批 | **推荐②**：呈现形态是裁决重的呈现批（Q1–Q4），count 有界 + Throwable 是小型健壮性批（可同 PR 不同文件域），GC 归因是先测量后裁决腿（沿 D-7 先例，可能零产品代码）——三者节奏不同。若 lead 判串行窗口（i3b 已合，见 §5）足够宽，①也可接受，但把测量腿和裁决批绑死会拖呈现形态的交付。 |
| **Q6** | catch Throwable 的采纳形态（若 Q5 计入本批） | ①租约块 catch 改 Throwable + 按类型分流（CancellationException rethrow、OOM/Error 落 enumerationFailed + 类型化横幅）；②维持 Exception、只加 StartupTrace 未捕获兜底钩子；③本批不改，仅登记 | **推荐①**（收窄到租约块，仓内已有 `catch (failure: Throwable)` 先例两处 `P503App.kt:6001`/`:6039`）：OOM 穿透使 rig 失败归因失真是已登记缺陷；但**全仓 16 处 `catch (failure: Exception)` 逐处改**不在本批（爆炸半径失控），只改枚举轮租约块一处，其余登记承接。 |

**检查点（completion criterion）**：每个 Q 附选项、推荐与理由；Q3/Q6 明示「须用户裁决/登记」边界；无隐藏裁决（凡涉及既有裁决文本改写的都进了 Q 表）。

---

## 4. 非目标

- **不改枚举轮**：成员页读、`IMPORT_REVIEW_GROUP_WINDOW_ITEMS` 窗封顶、hop FIFO 派发、reducer 落地时序、R2 失败恒作废语义、代际丢弃——全部原样（B1/B4 §11 不变式）。
- **不改 count 门**：D-322 双臂 when（`P503ImportReviewPresentation.kt:1244-1248`）原样。
- **不改 await 谓词**：`groupCardDisclosureText` 文本 = 产品契约，`groupDispositionCardHeaderDisclosure`（`P503ImportReviewPresentation.kt:790`）与 rig 侧（`AndroidScaleLongInstrumentedTest.kt:67`）一字不动；不调 stage 预算/marker/统计量。
- **不改列表语义/顺序**：候选行序、`ACC-COUNT-01`/`ACC-SEL-01`/`ACC-SORT-01`/`P704GP-ACC-ORDER-01` 一字不动（组卡成员行序在其冻结范围内，案 C 的前 K 行 = keyset 前缀，不重排）。
- **不做深行候选可达性**（D-320 R5 另一半仍开放）、不做仪器侧可见性改造（`AndroidScaleUi.kt` cached root 观察，判读条已定性非产品缺陷）。
- **零 DDL/零迁移/零依赖**（若 Q5 含 count 有界腿且其裁决需要新查询，另按其裁决升 DDL 登记批——本规格草稿默认无 DDL）。

**检查点（completion criterion）**：每条非目标指名被保护面（决定号或 file:line），读者可据以判定实现 diff 是否越界。

---

## 5. 实施串行约束

- **写面主战场 `P503ImportReview*.kt` 与 P8 线 i3b 批冲突 ⇒ 本批实施串行于 i3b 合并之后**（checkpoint D-15 串行规则，`docs/PROJECT_STATE.local.md:38`）。**现状核（2026-11-15）：i3b 已合并（PR `#359`，squash `edb74925`，见 `:12`/`:30`），P8-04 五批全合（`:13`）**——串行前提已满足，本规格批准后即可进 worktree 实施批，但实施批仍须在 lead 确认 P8 线无在飞写面占用 `P503ImportReview*.kt` 后开出。
- 设计门（本文 + 裁决）与实施批解耦：设计门先行不占写面；实施单写手、独立 worktree、评审/核验分离（仓库路由）。
- 若 Q5 判分批：count 有界腿（`Ledger.sq` 读域 + 端口/用例）与呈现批的文件域不相交，可并行开出（各自 worktree）；GC 归因腿先于两批出读数。

**检查点（completion criterion）**：串行条件、现状核实（i3b 已合）与分批时的文件域隔离都有明确判据；lead 可据此直接排期。

---

## 6. 开放问题（explore 回来后填充，≤8）

1. **案 B 的平台实证**：Compose `stickyHeader` 槽在初始/滚动中/未滚动到底部时，其语义节点在 a11y 树的 `isVisibleToUser` 是否为真（含 Compose 版本行为差异）？——决定案 B 能否独立成立。闭合 = 设备实证或文档证据（file:line）。
2. **案 A/C 的展开态承载**：卡片展开/有界态落 `ImportReviewView`（reducer 面 + 事件）还是局部 `remember`（重组保留但进程内不持久）？现有 `comparisonExpansion` 的 `remember(openGroupInputRef)` 键位（`P503ImportReview.kt:453`）是否可复用同形？闭合 = explore 给出两形态的爆炸半径对比。
3. **案 C 的 K 值与窗界关系**：K 与 `IMPORT_REVIEW_GROUP_WINDOW_ITEMS`（200）是同值复用还是独立常量？B1/B4 §11 第 2 条「两界不可互相推导」的登记先例如何落注释？闭合 = explore 提出常量方案 + 注释锚点。
4. **【已闭合，Q1/Q2 裁决】案 C 展开后的回滚态**：案 C 选定 ⇒ 本条按「展开后可收回」实现（回滚控件与「收起全部比较信息」批量开关同层级），卡片重开时默认态复位（恒默认有界）。原问题：「查看全部」铺开 99,600 行后，用户能否收回？若可，收回控件与「收起全部比较信息」批量开关（`P503ImportReview.kt:652-664`）的层级关系；若不可，rig 二跑与重开卡轮次的行为（卡片重开时默认态复位）。闭合 = Q4 裁决后规格补一节。
5. **「查看全部」行的 a11y 描述文案**：新控件文案与 contentDescription（沿 J4 既有纪律）需在规格内定稿；与 await 样本 `maxLen` 过滤（`maxLen=25`，判读条 ：5302）无冲突（新行不进谓词）。闭合 = 文案随实施批进规格。
6. **count 查询有界化的语义等价面**（若 Q5 计入）：`importDuplicateCandidateCountForSession` 的 count 是落地时点快照（D-322 §6① 已登记分歧残余），有界化（预聚合/子查询重写）不得改变「COUNT(*) = 成员行总数」契约（`Ledger.sq:11917-11928` KDoc）；61k 实测页耗时读数缺（D-7 登记的读数腿同源）。闭合 = 先 EXPLAIN + 规模读数，再裁决是否重写（沿 D-7 先例，零 DDL 默认）。
7. **GC 风暴归因的测量设计**：每 hop 分配节律的归因需什么读数（分配采样/局部 trace）？若归因坐实「render 全量重建」，案 A/C 的分配面下降是同源缓解——但**本批不以 GC 归因结论为呈现形态的批准前提**（两腿独立）。闭合 = 探针批设计另行登记（可复用 D-329 trace 批纪律）。
8. **【已闭合，Q1 裁决】案 C/K 界与「共 N 条」口径**：案 C 选定 ⇒ 有界态下 header 披露行绑 `page.totalCount` 原样（B1/B4 §6 风险 3 不变）；rig 侧 await 谓词在 header 首屏可达后自然命中，**仪器侧判据（itemCount/keyTexts 读数形态）不修订、按实跑读数登记**。原问题：有界态下 header 披露行继续绑 `page.totalCount`（B1/B4 §6 风险 3 原样），`itemCount` 口径在 rig 判据 `itemCount=20001`（B1/B4 §12）上是否有读数形态变化——rig 判据若需随呈现形态修订，**属于仪器侧判据修订，须在裁决点单列，不并入本批默认**。闭合 = Q1 裁决后与 lead 确认 V-11 类判据是否复用或另立。

**检查点（completion criterion）**：开放问题 8 条 ≤ 上限；每条附闭合条件与归属批；无一条静默依赖另一条的结论作为本批批准前提（GC 归因第 7 条明示独立）。

---

## 7. 验收向量骨架（实施批填充编号与证据类型，本稿只定形态）

| # | 向量 | 证据类型 |
| --- | --- | --- |
| V-1 | 卡片头 + 披露行在成员行未铺开/未滚动的默认形态下渲染于首屏可达区（对应所选方案） | 渲染模型纯函数测试 + rig 读数 |
| V-2 | A-VIS frozen duty 按所选 Q3 裁决保持：展开路径可逐条列出全部成员、payload 永不截断 | 纯函数/渲染测试 + 注释一致性核对 |
| V-3 | 装配门改动后，卡片确认恒为全组授权（§3.4）、`共 N 条` 绑 `page.totalCount`、成员行序 = keyset 前缀序不重排 | reducer/装配测试 |
| V-4 | b6 档既有判据不回归：`itemCount`、key texts、`forwardActions=0` 按 Q1/Q8 的裁决形态复核 | 云设备 run（`android-b6-card.yml`） |
| V-5 | maximum rig `group_disposition`：await 谓词在卡片头可达后于既有预算内命中（或登记新阻塞） | 云设备 run（`android-scale.yml`，mode=maximum） |
| V-6 | （若 Q5 计入）count 有界腿：EXPLAIN + 规模读数登记；语义等价测试钉「COUNT = 成员行总数」 | JVM 测试 + 读数 |
| V-7 | （若 Q6=①）租约块 Throwable 分流：CancellationException rethrow、Error 落 enumerationFailed 的宿主 seam 测试 | seam 测试 |

**披露（不计 PASS）**：sticky a11y 行为（开放问题 #1）；GC 归因结论（独立腿）；runner 镜像 revision 未固定，跨日读数不可比（D-283 残余⑤原样）；D-320 R5 深行可达另一半仍开放。

**检查点（completion criterion）**：向量覆盖三案各自的判定点 + Q5/Q6 的条件向量；条件向量明示其触发裁决。

---

## 8. 边界（草稿态）

**入**：本文（approved 后）、实施批的 tracked 变更（文件域按 Q1/Q5 裁决 + explore 校正）、D-330 决定条目（随批登记）。**出**：§4 全部非目标；`docs/PROJECT_STATE.local.md` 与 `docs/PROGRESS_LOG.local.md`（lead 维护）；`.external/`；本草稿所在的 `local/` 暂存区文件（lead 搬运后由其管理）。

**检查点（completion criterion）**：出入界可判；与 §4 非目标无重复表述（出入界管文件面，非目标管裁决面）。
