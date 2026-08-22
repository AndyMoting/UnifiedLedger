# P4-09 阶段收口契约（冻结规格提案）

**Status:** proposal（2026-08-22，D-109 提案，待用户批准 O-1..O-9 裁决组合）。本文件冻结 P4-09（阶段收口）的范围与验收契约：现状基线、既有权威逐条约束、交付面 D1..D5 的 WHAT 冻结、O-1..O-9 裁决点、统一失败矩阵、验证矩阵与边界断言。批准前，本文档全部裁决点及其备选方案保留为决策审计记录；批准后仅批准组合生效。本契约**只冻结收口范围与验收**；P4-09 实施规格与实施是后续独立批（需独立 worktree 拓扑与独立评审），不在本契约内。

**Scope:** P4-09 = M4 Phase Closure 批（WORK_PLAN.local.md:102）：RL-01~RL-08 全 oracle、migration/reopen、RG coexistence、失败恢复与完整受影响回归。前置 = P4-01~P4-08 全部 green。本契约不新增产品功能、不改生产语义（O-2）、不发布 golden 工件、不自动授权 publication 或 Phase 5（O-3/O-4；PHASE4_DESIGN_PACKAGE.local.md:113）。

## Authority And Boundary

本规格全部条款对齐以下权威（tracked 文件行号为 worktree `feat/p409-phase-closure` 基线 `b19a6c1` 的行号；`.local.md` 文档以主 checkout 为准）：

- 阶段判据：docs/ROADMAP.md:36-41（阶段 4 = 导入与对账闭环；进入条件 :40；**完成条件 :41 = 支持的标准来源能够从导入走到正式账目与可解释对账状态，重复导入不产生重复账目**）、:43-48（阶段 5 进入条件 :47 = 共享核心具备稳定调用边界，导入与对账最小闭环通过验收）、:71-73（阶段约束：按顺序推进、每阶段以可重复验证结果满足完成条件、后续能力不得破坏更早不变量）。
- 批定义与门：WORK_PLAN.local.md:102（P4-09 批行：RL-01~RL-08 full oracle、migration/reopen、RG coexistence、失败恢复与全量 affected regression；前置全绿；high-risk route = recon → closure contract → dual review）、:104-110（各批已过门登记）、:112（产品随机 ID/平台 Clock/应用组装默认 Phase 5）、:114-129（**RL 维度到交付批次矩阵**，行 :120-127；:129 P4-03/04/05 禁止 matcher/reconciliation 状态变更）、:131-140（Stage 3 continuity：#3 完整比较 outcome/returned IDs/complete state/deltas/status changes；#4 失败路径零副作用、并发输家不消耗物化值；#5 持久化变更须验证 reopen/migration/coexistence；#6 冻结独立评审与 distinct verification）、:142-155（backlog 欠账登记）、:157-164（约束：publication 需独立授权 :164）。
- 阶段设计包：docs/PHASE4_DESIGN_PACKAGE.local.md:107-113（P4-09 节：全 oracle 与失败矩阵闭合、migration/reopen、RG coexistence、失败恢复、完整 affected regression；**只有 P4-01~P4-08 均 green 才能进入；阶段闭合不自动授权 publication 或 Phase 5 实现**）、:115-128（RL 矩阵镜像）。
- 验收锚点：docs/GOLDEN_TESTS.md:164-177（**首批 8 个本地真实来源场景：输入和冻结答案只存在于本地测试区，公开仓库不得保存其真实来源标识或私人解释**；RL-01 :170 ～ RL-08 :177 逐行验收维度）、:297-303（隐私要求：公开数据中性名称与合成日期；本地真实场景只提交匿名编号/级别/维度；真实资料脱敏副本须专项审查后方可另行提议）。
- 决定记录：D-096:1399-1437（阶段 4 边界总纲；:1425 通道总额只诊断、:1427 场景合同定基数、:1435 未决不能由实现默认值冻结）；D-097:1439-1473（contract-only P4-01；:1457 金额/时间精确比较、:1459 诊断 taxonomy、:1465 匿名验收密度先例）；D-098:1475-1528（spine 契约与实施；:1519 RL-01~RL-08 全量闭合 = P4-09 显式延后项）；D-099:1530-1546（首来源与 parser 技术；**:1540 边界 = parser 技术门对首个来源关闭，第二个来源已由 D-101 关闭，银行 PDF 仍开**）；D-100:1548-1574（transfer formalization；:1567 complete state oracle 十四表模式）；D-101:1576-1603（支付宝 CSV 与纠正修订纪律）；D-102:1605-1623（RL-04 余额宝路由）；D-103:1625-1641（matcher 契约；**:1639 实施登记 = correction/successor invalidation 明确延期，P4-08 不因此视为全量闭环**）；D-104:1643-1655 与 D-105:1657-1669（duplicate/closed 契约与实施；:1667 provider token funding 映射仍禁止）；D-106:1671-1696（信用/混合契约；:1690 边界排除项清单）；D-107:1698-1712 与 D-108:1714-1727（两片实施授权与登记；D-108 零 schema 先例 = 版本钉 v25、fresh=migrated 原样通过、非阻塞观察 store :440 清理建议挂 backlog）。
- 正式状态：docs/CURRENT_STATE.md:6（schema v25 链与逐版本 populated migration/fresh equality 既有证据）、:41/:45（P4-06 全批完成；**措辞滞后 = 仍写「push 待用户明示」，但 `b19a6c1` 已在 `origin/main`**，收口批一并修正，见 D5）。
- 代码与测试现实（只读现状，worktree `b19a6c1` 侦察）：ledger-data jvmTest 锚点 = `ImportSpineLifecycleEndToEndTest.kt`（P4-02 30-op spine oracle）、`ImportSpineWechatEndToEndTest.kt`（P4-03）、`ImportSpineTransferEndToEndTest.kt`（P4-04）、`ImportSpineAlipayEndToEndTest.kt`/`ImportSpineAlipayYuebaoTransferEndToEndTest.kt`（P4-05/05b）、`P408ReconciliationCanonicalOracleTest.kt`/`P408ReconciliationStoreTest.kt`（P4-08）、`P407DuplicateClosedFullStateOracleTest.kt`（P4-07）、`P406CreditFullStateOracleTest.kt`（P4-06 两片，:71-85 头注释登记比较面含 P4-07 duplicate 表与 P4-08 reconciliation 面；:1608 `mixedEqualRowsProduceExactTupleDuplicateAndNoSecondTransaction`）、`ImportSpineMigrationCoexistenceTest.kt:222`（**现仅 rg04 竖井 + 共享 spine 共存**）、`LedgerDatabaseMigrationTest.kt`（逐边 populated/late-failure/fresh 系列，如 :519 `freshV25EqualsMigratedV25ForP406CreditAndP407Owners`、:543/:596 v24→v25 populated/late、:627/:652/:701 v23→v24 系列、:450 v22→v23 late）；ledger-application jvmTest = `import/wechat/WechatBillParserJvmTest.kt`、`import/alipay/AlipayCsvParserJvmTest.kt`/`AlipayCsvParserCreditJvmTest.kt`/`AlipayCsvParserYuebaoTransferJvmTest.kt`、commonTest `P408MatcherTest.kt`；ledger-domain commonTest `MixedPaymentTest.kt`。

术语：`本批` = P4-09 阶段收口契约批（本文档）；`P4-09 实施批` = 本契约批准后、按本契约起草实施规格并落地的后续独立批；`full-state oracle` = 按 P4-04 D-100:1567 与 P4-06/P4-07 先例，对全部相关表逐行列、状态历史、余额、report projection（含 P4-08 reconciliation 维度）、operation result/receipt/returned IDs 与 canonical delta 的完整比较；`平台侧适用子集`（RL-07）= 已接入标准来源（微信 XLSX、支付宝 CSV）一侧可端到端行使的镜像/对账维度；`真实来源锚点` = `.external` 与本地测试区内以真实账单为输入的 RL 验收（GOLDEN_TESTS:164-166 边界）。

## 1. 现状基线

### 1.1 P4-01..P4-08 各批已交付覆盖表

| 批 | 决定 | 已交付覆盖（worktree 测试锚点） | RL 维度承载（WORK_PLAN:120-127） |
| --- | --- | --- | --- |
| P4-01 contract | D-097 | 逻辑合同 + GOLDEN_TESTS:179-295 九 fixture 冻结（无实现，按设计） | 全部 RL 的 normalization/诊断底座 |
| P4-02 spine | D-098（merge `d756391`，schema v21） | `ImportSpineLifecycleEndToEndTest.kt` 30-op replay/concurrency/failure oracle；`ImportSpineMigrationCoexistenceTest.kt` | spine 链全部 |
| P4-03 微信 | D-099（`18fae64`，零 schema） | `import/wechat/WechatBillParserJvmTest.kt`（W1-W14 合成 xlsx + P-01~P-21/E-01~E-14）；`ImportSpineWechatEndToEndTest.kt` | RL-01/RL-02 子切片 |
| P4-04 转账 | D-100（`71face6`，v22） | `ImportSpineTransferEndToEndTest.kt`（15 tests，complete canonical state + 十四表 delta） | RL-03 完整/缺腿子切片 |
| P4-05/05b 支付宝 | D-101/D-102（`985413a`，零 schema） | `AlipayCsvParserJvmTest.kt`（T-01~T-26）、`AlipayCsvParserYuebaoTransferJvmTest.kt`、`ImportSpineAlipayEndToEndTest.kt`、`ImportSpineAlipayYuebaoTransferEndToEndTest.kt` | RL-04 子切片 |
| P4-06 信用/混合 | D-106/D-107/D-108（`7cf9b79` v25 + `df34388` 零 schema） | `P406CreditFullStateOracleTest.kt`（RL-05 三锚点 + 负路径 + duplicates/replay + mixed 三分录/group；D-108 验证 12/12）、`AlipayCsvParserCreditJvmTest.kt`、`MixedPaymentTest.kt` | RL-05/RL-06 子切片 + 部分 P4-07/P4-08 组合维度 |
| P4-07 重复/关闭 | D-104/D-105（`e1ab7d4`，v24） | `P407DuplicateClosedFullStateOracleTest.kt`（canonical full-state）；`LedgerDatabaseMigrationTest.kt` v23→v24 系列 | RL-08 维度 |
| P4-08 镜像/对账 | D-103（`fd57808`，v23） | `P408ReconciliationCanonicalOracleTest.kt`、`P408ReconciliationStoreTest.kt`、`P408MatcherTest.kt`；`LedgerDatabaseMigrationTest.kt` :316/:368/:450/:489 v22→v23 系列 | RL-07 维度（合成双侧镜像） |

schema 现状 = v25（CURRENT_STATE:6；D-108 零 schema 钉 v25 先例）。既有覆盖 = 逐边（vN→vN+1）数据级 populated/late-failure 测试 + **单链 schema 级 metadata 等价**（`LedgerDatabaseMigrationTest.kt:519-531` `freshV25EqualsMigratedV25ForP406CreditAndP407Owners`：populated v1 上单次 `migrate(1, 25)` 走完全链后比较 schemaMetadata）+ migration verifier；无 D2 要求的单链 populated **数据级**等价测试。

### 1.2 既有覆盖之外的收口缺口（本契约 D1..D5 的由来）

1. **无逐 RL 全状态收口 oracle**：各批 oracle 按批界切片（P4-03 普通收支、P4-04 转账、P4-06 信用/混合、P4-07 重复、P4-08 匹配），没有任何单一 oracle 按 WORK_PLAN:120-127 矩阵逐 RL 维度声明覆盖闭合；跨 RL 组合维度（RL-03 × P4-08 evidence linkage 的导入转账全链、RL-05/RL-06 × P4-07 处置终态）亦无逐格登记。
2. **无 populated v1→v25 单链迁移测试**：既有为逐边 populated/late-failure（LedgerDatabaseMigrationTest.kt）+ verifier 的 schema 级 fresh=migrated equality；不存在「v1 正式账 + 各代 spine kind + duplicate + P4-08 行同库一路迁到 v25 + reopen + fresh 等价」的数据级单链验收。
3. **无 12 RG 竖井 + 完整 spine v25 + P4-08 表同库共存验收**：`ImportSpineMigrationCoexistenceTest.kt:222` 现仅覆盖 rg04 竖井；12 套竖井与完整收口形态 spine 的同库共存与 reopen 未被单一验收承载。
4. **失败模式无统一矩阵**：解析 fatal、intake/confirm 注入、领域拒绝、并发输家、迁移 late-stage 分散在各批测试，无 RL 维度 × 失败模式的文档化矩阵与逐格测试锚点映射。
5. **正式状态文档滞后**：CURRENT_STATE.md:41/:45 仍写「push 待用户明示」，但 `b19a6c1` 已在 `origin/main`；WORK_PLAN P4-09 行状态未随收口推进更新。

## 2. 既有权威已定约束（逐条带出处）

1. **阶段完成判据固定**：阶段 4 完成 = 支持的标准来源从导入走到正式账目与可解释对账状态、重复导入不产生重复账目（ROADMAP:41）；阶段按顺序推进、以可重复验证结果关闭（ROADMAP:73）。收口证明的是该判据被满足，不新增判据、不改动判据。
2. **P4-09 批内容固定**：RL-01~RL-08 全 oracle、migration/reopen、RG coexistence、失败恢复、完整 affected regression（WORK_PLAN:102；PHASE4_DESIGN_PACKAGE:109-112）；阶段闭合不自动授权 publication 或 Phase 5（PHASE4_DESIGN_PACKAGE:113）。
3. **真实来源本地边界**：RL 场景输入与冻结答案只在本地测试区，公开仓库不得保存真实来源标识或私人解释（GOLDEN_TESTS:164-166）；公开数据用中性名称、固定合成日期；本地真实场景只提交匿名编号/级别/维度（GOLDEN_TESTS:297-303）。
4. **完整比较纪律**：比较 outcome、returned IDs、complete state、deltas、status changes，不只比总额或选中字段（WORK_PLAN:137）；失败路径零副作用、并发输家不消耗应用物化值（WORK_PLAN:138）；持久化变更验证 reopen/migration/coexistence（WORK_PLAN:139）。
5. **RG 竖井纪律**：12 套 rgXX 竖井是冻结回放语料；产品承载一律为非 `rgXX_` 前缀共享表，不复用不挂接（D-092:1325-1340；D-098:1493）。竖井不因阶段收口退役（O-7）。
6. **诊断与金额/时间语义**：诊断复用 D-097:1459 taxonomy 与各批冻结码，message 不比较；金额精确十进制 + source scale、时间按 source token/kind/components/offset presence（D-097:1457）。收口批零新增 severity/scope（P4-08 契约 §5 纪律沿用）。
7. **matcher/对账边界不扩张**：matcher 语义以 D-103 O-1..O-6 批准组合为唯一基线；通道总额只诊断（D-096:1425）；reconciliation 状态不参与余额计算（**ACCOUNTING_RULES:33 字面仅覆盖余额**），亦不改变报表金额维度——该后半句依据为 D-103 落地形态（D-103:1634：reconciliation 以 report 独立维度呈现，不改其余九维度），非 ACCOUNTING_RULES:33 字面。收口批只验收、不改语义。
8. **零 schema 默认**：schema 钉 v25（CURRENT_STATE:6；D-108 先例）；若收口过程发现必须的 schema 演进，即超出本契约范围，须退回用户重开契约（O-2）。
9. **未决不由实现默认值冻结**（D-096:1435）：O-6 排除清单中的每项欠账维持其登记落点，收口批不得顺手实现或静默丢弃。
10. **产品 ID/Clock/平台组装默认 Phase 5**（WORK_PLAN:112/:163）：收口 oracle 全部使用确定性合成 ID 与显式时间事实，不引入产品 Clock/随机 ID。
11. **publication 独立授权**（WORK_PLAN:164；golden v2 publication 先例门 = D-086/D-089/D-090）：收口批不发布任何 golden 工件（O-3）。
12. **批界禁止的追溯链**：P4-03/P4-04/P4-05（含 RL-04 路由批）不得创建 mirror evidence link、不得产生 posting reconciliation effect（WORK_PLAN:129；PHASE4_DESIGN_PACKAGE:128）。该条是**批界禁止**而非永久语义禁止——evidence-link/reconciliation 状态变更语义首现于 P4-08，并已获 D-103 matcher gate 授权落地（v23）。因此 D1 新增的 RL-03 × P4-08 组合锚点串联的是 D-103 已批准语义，不构成对 :129 字面禁止的扩权或追溯违反。

## 3. 交付面 WHAT 冻结（D1..D5）

以下冻结**范围与验收（WHAT）**；fixture 值表、测试类命名、文件落点、断言实现方式等粒度归 P4-09 实施规格（HOW），实施规格不得变更本节语义。

### 3.1 D1 逐 RL 全状态 oracle（合成，仓库内，CI 可跑）

交付一个（或一组）仓库内**合成** full-state oracle 套件：按 WORK_PLAN:120-127 矩阵逐 RL 维度映射到测试锚点表，每维度以匿名代表值驱动 spine 全链（parse/intake → candidate → 明确确认 → formal graph → 余额/report projection 含 reconciliation 维度 → 适用处置），按 full-state 定义（术语节）比较。真实来源锚点不入仓（GOLDEN_TESTS:164-166；O-1）。

| RL 维度（GOLDEN_TESTS:170-177） | 合成锚点语义（匿名化） | 既有覆盖登记 | P4-09 收口增量（必须新增/串联） |
| --- | --- | --- | --- |
| RL-01 普通支出 | 微信形态普通支出行全链：来源状态、账户扣减、分类、重复导入处置 | P4-03 E2E、P4-02 30-op、P4-07 duplicate | 单一 oracle 内串联：intake→确认→formal→report→重复 intake duplicate 候选→处置终态，全状态逐行比较 |
| RL-02 普通收入 | 支付宝形态普通收入行全链：方向、账户增加、收入统计 | P4-03/P4-05 E2E | 同 RL-01 形态，收入维度 report 断言 |
| RL-03 转账 | 完整腿（微信提现/充值路由）确认 ACCOUNT_TRANSFER、零对外收支；缺腿候选保持 pending 不猜测 | P4-04 E2E（15 tests） | 完整/缺腿在同一 full-state 序列；**新增 ×P4-08 组合锚点：导入转账候选确认后的 evidence link 与 posting reconciliation 状态推进**（现无端到端覆盖） |
| RL-04 第二来源一对一 | 余额宝路由两腿确认、两端余额、对账证据 | P4-05b E2E、P4-08 oracle（合成） | 串联：路由→确认→P4-08 posting reconciliation 推进（组合锚点） |
| RL-05 信用 | 三锚点生命周期串联：消费（费用+/负债转负）→还款（资产−/负债+）→退款（独立经济事件、关联原交易） | `P406CreditFullStateOracleTest.kt`（三锚点 + duplicates/replay + 基数登记） | 在收口 oracle 内重申三锚点串联与失败矩阵映射；组合维度按既有覆盖登记依据（见下） |
| RL-06 混合 | 三分录 + `mixed_payment_group`（合成 12.40 = 3.60 + 8.80 形态：费用+/资产−/负债−）、无重复调整 | 同上（mixed 场景） | 同 RL-05 |
| RL-07 镜像/对账 | 平台侧适用子集：合成镜像 evidence → 精确 posting 判定 → link/状态变化/零第二笔交易 | P4-08 全部 oracle（合成双侧） | 收口按**平台侧适用子集**冻结（O-8）；银行侧**真实来源**维度显式登记延期至银行 parser 门（D-099:1540 仍开）；合成银行侧镜像语义维持 D-103 oracle 验收，不随子集延期（与 O-8-A 一致） |
| RL-08 重复导入 | 关闭/失败记录零资金影响、重复导入幂等、状态解释 | `P407DuplicateClosedFullStateOracleTest.kt` | 跨 kind 组合：普通/转账/信用/混合行各自的关闭与重复形态在收口矩阵逐格登记（新格补齐） |

**跨 RL 组合维度归属裁决（契约冻结）**：

- **RL-05/RL-06 × P4-07 duplicate 处置**：判属**各批已覆盖为主、收口登记为辅**。依据 = `P406CreditFullStateOracleTest.kt` 比较面显式含 P4-07 duplicate 表（头注释 :76），且含信用 duplicates/replay 场景（:82）与混合 exact-tuple duplicate 场景（:1608 `mixedEqualRowsProduceExactTupleDuplicateAndNoSecondTransaction`，断言 `EXACT_BUSINESS_TUPLE`/`DEFERRED`/无第二笔交易）。收口矩阵只登记既有锚点引用，并为未覆盖格（如信用/混合行的 duplicate review-claim → 处置终态形态）补新锚点。
- **RL-03 × P4-08 evidence linkage**：判属 **P4-09 收口新增**。依据 = P4-08 oracle 以合成 evidence/posting 直驱 `P408ConfirmLinkRequest`（`P408ReconciliationCanonicalOracleTest.kt`），不经导入转账候选链；P4-04 E2E 先于 v23 无 link 面。收口必须新增「导入转账候选 → 确认 ACCOUNT_TRANSFER → matcher 对其 posting 建 link/推进 reconciliation」的组合 full-state 锚点。

### 3.2 D2 populated v1→v25 单链迁移测试

单一数据库、单一迁移链：以 populated v1（正式账数据）起步，逐边迁移至 v25；在对应代际版本插入该代 spine 形态行（v21 普通、v22 transfer kind、v23 P4-08 link/reconciliation 行、v24 duplicate 行、v25 信用/混合行），一路迁到 v25 后：reopen 重开、数据保持、守卫触发器有效，并与 fresh v25 构造的等价形态做数据级等价比较。与既有逐边测试（LedgerDatabaseMigrationTest.kt）与 verifier 的 schema 级 equality 互补：本测试是**数据级单链**验收。stage 编排、行集规模、等价比较的精确投影归实施规格。

### 3.3 D3 RG 竖井 + 完整 spine v25 + P4-08 表同库共存 + reopen 单一验收

单一数据库内：12 套 rgXX 竖井 populated 回放语料 + 完整 spine v25（含各代 kind、duplicate、P4-08 link/reconciliation 行）+ formal 图共存；断言竖井与共享表零串音（对照 `ImportSpineMigrationCoexistenceTest.kt:222` 的 rg04 先例扩展到 12 竖井）、reopen 后全部保持、RG oracle 语料不受 spine 写入影响。竖井本身不退役、不改写（O-7；D-092/D-098 纪律）。

### 3.4 D4 统一失败矩阵（文档化矩阵 + 测试锚点映射）

交付一份 tracked 正式文档化的**失败矩阵**：行 = RL-01..RL-08 维度，列 = 失败模式（解析 fatal、intake 注入、confirm 注入、领域拒绝、并发输家、迁移 late-stage）；每格登记「既有覆盖锚点（file/test 名）或新增 P4-09 锚点」，新增锚点在收口 oracle 内落地。矩阵落点（GOLDEN_TESTS.md 新节或本规格实施批文档）归实施规格，但矩阵必须可被后续批次引用为覆盖登记基线。既有覆盖示例（登记依据）：解析 fatal = 两 parser 测试 fatal 变体（D-097 P401-FATAL 族传承）；intake/confirm 注入 = P4-06 失败注入回滚/重试 oracle（D-107 补测 `d5442f5`：`INTAKE_AFTER_CANDIDATE`/`CONFIRM_AFTER_FORMAL`）与 slice 2 group 两表回滚（D-108 实施门）；领域拒绝 = `MixedPaymentTest.kt` 与各批负路径；并发输家 = P4-02 30-op concurrency 与 P4-07 review claim；迁移 late-stage = `LedgerDatabaseMigrationTest.kt` late 系列逐边。新增项 = 逐 RL 维度上未被任何批 oracle 行使过的格。

### 3.5 D5 全量回归 + 正式状态文档同步

1. 全量受影响回归按 §5 验证矩阵执行并留痕。
2. 正式状态文档同步（收口批一并修正**全部同源滞后措辞**，`b19a6c1` 已在 `origin/main` 为既成事实）：CURRENT_STATE.md:41/:45 的「push 待用户明示」及 :45「`main` 领先 origin」表述改为既成事实陈述；WORK_PLAN.local.md:99（P4-06 行「push pending user」）、:102 P4-09 行内括注（「P4-06 slice 2 merged df34388; push pending user」）、:150（P4-06 片 1 行「DONE pending push」）、:153（片 2 行「push pending user」）同步修正；CURRENT_STATE 登记阶段 4 收口状态；WORK_PLAN.local.md P4-09 行（:102）状态更新为收口完成。阶段 4 判据满足的声明以 ROADMAP:41 原文为唯一基准（O-4）。

## 4. 裁决点（O-1..O-9）

以下每项以「条款 → 备选方案 → 推荐与风险」呈现；备选方案保留为批准前决策审计记录。未获用户批准前，任何选项均不生效；实现不得以默认值代替裁决（D-096:1435）。

### O-1 full oracle 场景基数与真实来源边界

**条款：** D1 oracle 的场景来源形态——仓库内合成 oracle 与真实来源验收的边界如何划。

- **A. 仓库内合成逐 RL 全状态 oracle + 真实验证仓库外留痕（推荐）**：仓库内交付全部合成匿名代表值的逐 RL full-state oracle（CI 可跑、无隐私面）；真实来源锚点维持 GOLDEN_TESTS:164-166 本地边界不入仓；用户侧本地真实验证（对 `.external`/本地测试区真实账单跑受支持的收口形态）作为**仓库外验收步骤**，其执行与结果在本地检查点文档留痕（不进 Git）。风险：仓库内 oracle 证明合成语义，真实数据面只由仓库外步骤覆盖——若用户跳过该步骤，真实面无证据；缓解 = 收口验收清单把该步骤列为显式可勾选门。
- **B. 真实脱敏 fixture 入仓**：与 GOLDEN_TESTS:164-166/:301-302 正面冲突（真实资料脱敏副本须不可逆且专项审查后方可**另行提议**），否决。
- **C. 仅合成、不设仓库外真实验证步骤**：实现最省，但阶段判据「支持的标准来源能够从导入走到正式账目」最有力的一手证据缺位，收口声明强度弱化；不推荐。

### O-2 零 schema、版本钉 v25、零生产语义变更

**条款：** 收口批对生产代码与 schema 的变更权限。

- **A. 零 schema + 版本钉 v25 + 仅测试/oracle/文档（推荐）**：沿用 D-108 零 schema 先例（无 `.sqm`、DDL 零改动、fresh=migrated 原样通过）；生产源码零语义变更。若收口过程发现缺陷：缺陷修复走评审路由（独立规格/质量评审 + distinct verifier）可入收口批，但不得夹带新功能；若发现必须的 schema/语义演进，即停止并退回用户重开契约。风险：缺陷修复与收口混批会稀释「收口=证明」语义——缓解 = 修复逐项登记（发现、根因、影响面、验证），与收口新增面分开列示。
- **B. 允许按需生产/Schema 变更**：收口批变质为功能批，破坏批界与 O-3/O-4 门序；否决。
- **C. 一切缺陷皆延后（含阻断判据的缺陷）**：若缺陷使阶段判据不成立（如重复导入产生重复账目），延后等于带病收口；否决——A 的评审路由是正确出口。

### O-3 不发布 golden 工件；不自动授权 publication/Phase 5

**条款：** 收口批与 golden v2 publication、Git publication、Phase 5 的关系。

- **A. 收口批零 golden 工件、零自动授权（推荐）**：RL oracle 以 jvmTest 形态交付，不发布 `golden/rules-v2/` 工件（publication 有独立先例门 D-086/D-089/D-090）；阶段闭合不自动授权 Git publication 或 Phase 5 实现（PHASE4_DESIGN_PACKAGE:113）；push 与 Phase 5 开启均为用户显式门。风险：无实质风险；唯一成本是若未来想把 RL oracle 升格为 golden 工件需另开决定。
- **B. 顺带发布 RL golden 工件**：引入 publication 门（manifest、hash、LF integrity）到收口批，扩面且无判据需求；不推荐。
- **C. 收口即视为 Phase 5 授权**：与阶段顺序纪律（ROADMAP:73）冲突；否决。

### O-4 P4-09 收口的完成语义

**条款：** 「P4-09 收口完成」的判定基准。

- **A. 收口 = ROADMAP:41 阶段 4 完成判据被 D1..D5 证据满足（推荐）**：判据原文（支持的标准来源从导入走到正式账目与可解释对账状态、重复导入不产生重复账目）以收口 oracle + 失败矩阵 + 迁移/共存验收 + 全量回归的通过证据闭合；Phase 5 进入是独立用户门（ROADMAP:47），不因收口自动开启。风险：判据中「可解释对账状态」在银行侧维度受 O-8 子集边界限制——契约以显式登记的子集边界回应（支持的来源 = 已接入的微信/支付宝），不留隐性豁免。
- **B. 收口同时开启 Phase 5**：同 O-3-C，否决。
- **C. 收口附加「全部登记欠账关闭」判据**：与 O-6 排除清单冲突，等于无限扩批；否决。

### O-5 P4-08 correction/successor invalidation 维持延期

**条款：** D-103 实施登记中明确延期的 correction/successor invalidation 是否在收口批补做。

- **A. 维持延期，失败矩阵登记为已知延期维度（推荐）**：D-103:1639 已登记延期理由（P4-08 不因此视为全量闭环）；收口批零生产语义变更（O-2）不容纳该功能；其在 D4 矩阵中显式标注「延期至后续独立批」，不冒充已覆盖。风险：矩阵存在未覆盖格可能被误读为收口不完整——缓解 = 该格标注为「已登记延期」而非「无覆盖」，并指回 D-103。
- **B. 收口批实现**：违反 O-2，把功能批塞进证明批；否决。
- **C. 从矩阵中删除该维度**：静默丢弃已登记欠账，违反登记纪律；否决。

### O-6 已登记欠账显式排除（逐条指针）

**条款：** 全部已登记欠账在收口契约中的处置。

- **A. 逐条显式排除 + 指针（推荐）**：以下各项不属于 P4-09 范围，维持各自登记落点，收口批不实现、不关闭、不丢弃：① constraint_solved 推断证据产出落点（WORK_PLAN:154；D-106 §7.4；P406S2-SPEC-005/D-108 闭合登记）；② mixed confirm 的 null explicitConfirmedAt 类型化门（WORK_PLAN:155；P406S2-IMPSPEC-002/P406S2-QUAL-002；当前仅测试侧可达）；③ store :440 冗余 safe-call 清理（D-108 非阻塞观察，挂 backlog）；④ 营销腿/非资金标注腿剥离（D-107:1704 边界）；⑤ 微信侧信用负证据与「信用卡还款」形态（D-106 边界 future_rule）；⑥ 分期付款（D-049，D-106 边界）；⑦ 利息/手续费/逾期费分录（D-106 O-5 future_rule）；⑧ 拆单支付/他人代付/亲友代付（D-106:1690 边界，未分配批次）；⑨ 仅资产腿退款与信用借还其余形态（D-106 边界维持类型化拒行）；⑩ 银行 PDF 来源门（D-099:1540 仍开，联动 O-8）；⑪ 共享负债账户映射（D-106 O-4，属迁移配置）；⑫ provider token funding 映射（D-105:1667 禁止，待来源契约修订独立批准）；⑬ 整文件保留生命周期（D-098:1494 独立门禁）；⑭ 产品随机 ID 算法/平台 Clock/应用组装（WORK_PLAN:112/:163，Phase 5）。风险：排除清单长可能被读作收口含糊——缓解 = 每条带唯一指针，D4 矩阵相应格标「登记延期/范围外」。
- **B. 静默不提**：违反登记纪律（D-096:1435 精神），否决。
- **C. 收口批逐项关闭**：批面失控，且多项需自身契约门；否决。

### O-7 RG 竖井不退役

**条款：** 12 套 rgXX 竖井在阶段收口后的地位。

- **A. 竖井保留为冻结回放语料（推荐）**：D-092 默认——竖井是黄金回放语料与行为证据，不因产品 spine 收口而退役；D3 证明共存而非替代。风险：竖井维护成本随 schema 演进上升——已由逐版本迁移测试承载，无新增风险。
- **B. 收口批退役竖井**：破坏 12 套 RG oracle 与黄金回放语料，destructive；否决。
- **C. 部分退役**：无任何决定授权选择性退役；否决。

### O-8 RL-07 收口子集边界（银行侧镜像延期）

**条款：** RL-07（银行侧与支付侧镜像）在无银行来源 parser 前提下的收口口径。

- **A. 平台侧适用子集收口 + 银行侧维度显式登记延期（推荐）**：仓库内 oracle 维持 D-103 已批准的合成双侧镜像语义验收（matcher 语义来源中立）；「平台侧适用子集」= 已接入标准来源一侧的端到端镜像/对账维度在 D1 收口；银行侧**真实来源**维度延期至银行 parser 门（D-099:1540 仍开），契约与 D4 矩阵显式登记该子集边界。ROADMAP:41 判据以「支持的标准来源」为基准，银行来源不在支持集内，不阻断收口。风险：未来银行 parser 接入后需回补银行侧真实镜像验收——已在 D-099:1540 开放门登记，无丢失。
- **B. 收口等待银行 parser**：把阶段闭合挂在未开启的来源门上，与「支持的标准来源」判据不符；否决。
- **C. 宣称 RL-07 全量闭合（含银行侧真实）**：为无 parser 来源宣称验收，不诚实；否决。

### O-9 验证矩阵与发布边界

**条款：** 收口批的验证序列与发布（release）范围的归属。

- **A. 分层验证矩阵，release 归真实发布门（推荐）**：见 §5。聚焦优先、逐层扩大、串行执行（CONTRIBUTING 本机资源限制）；push 前 trace 校验（harness）为最后一层；release scope（版本、tag、发布工件）留给真实发布门，收口批不含。风险：矩阵层数多、耗时——已按「聚焦→受影响→全量」排序，失败时最小代价定位。
- **B. 只跑 CI 等价子集**：收口批的证明强度要求全量受影响面；否决。
- **C. 在收口批内执行 release 步骤**：与 O-3 冲突；否决。

## 5. 验证矩阵（O-9 冻结）

按序执行，聚焦失败先于全量定位；Gradle 串行 + daemon 管理按 CONTRIBUTING「本机 Gradle 资源限制」。命令原文以 docs/CONTRIBUTING.md 为准（同步 CI）：

| 序 | 层 | 内容 | 命令/形式 |
| --- | --- | --- | --- |
| 1 | 聚焦 | 新增收口 oracle（D1/D2/D3/D4 新锚点） | `.\gradlew.bat :ledger-data:jvmTest` 以 `--tests` 过滤新增测试类（具体类名随实施规格） |
| 2 | 迁移 verifier | 全链 schema 迁移验证（v25 不变原样通过） | `.\gradlew.bat :ledger-data:verifyCommonMainLedgerDatabaseMigration --stacktrace --rerun-tasks --warning-mode all` |
| 3 | 三 JVM 模块全量 | `:ledger-domain:jvmTest`、`:ledger-application:jvmTest`、`:ledger-data:jvmTest` 逐模块串行 | CONTRIBUTING「Kotlin 验证」三条命令；结果以 `build/test-results` 报告为准（计数不在文档复制，CURRENT_STATE:22 纪律） |
| 4 | Android 编译 | `:ledger-data:compileAndroidMain` | CONTRIBUTING 命令原样 |
| 5 | Python 全套 | 核心回归（D-108 时点 806/806，以实际执行输出为准） | `PYTHONPATH="tools/python" python -m unittest discover -s tests -t .` |
| 6 | 文档 | `project_docs` | `PYTHONPATH="tools/python" python -m project_docs .`，exit 0 |
| 7 | Harness | verify-project full（Harness 路由项目检查） | `unifiedledger-harness` skill 的 `verify-project` full |
| 8 | push 前 | trace 校验 | verify-project trace（主代理 Git 写操作前） |
| — | 范围外 | release scope（版本/tag/发布工件） | 留给真实发布门，不在收口批 |

## 6. 诊断与登记纪律

- 收口批零新增诊断 severity/scope；如实施批确需新诊断码（预期不需要），必须按 D-099:1539 类型转移登记纪律在实施规格给出登记理由并经评审，本契约不预设编码（D-096:1435）。
- D1 全部 fixture 为合成匿名代表值：中性账户/来源 token、固定合成 +08:00 时间、精确整数最小货币单位合成金额；不出现真实商户、单号、账户锚点、掩码原文、绝对路径（GOLDEN_TESTS:297-303；P405FIX-QUAL-001 先例）。
- D4 矩阵每格必须有唯一锚点（既有引用或新增），禁止「近似覆盖」表述；延期格标注「已登记延期 + 指针」（O-5/O-6/O-8）。

## 7. 边界断言（契约批不含实施）

- 本契约是 **proposal**：不实施任何测试、oracle、迁移测试或文档同步；P4-09 实施规格与实施是后续独立批（独立 worktree、单一 writer、独立规格/质量评审、distinct verifier、主代理最终检查与全量受影响套件）。
- 不改 schema（钉 v25）、不改生产语义、不发布 golden 工件、不执行 Git 写操作、不自动授权 publication 或 Phase 5（O-2/O-3/O-4）。
- 不复制真实来源数据或注册值入 tracked 文件；不碰 `.external/`（只读）；不动 12 套 rgXX 竖井（O-7）。
- 不实现 O-6 排除清单中任何欠账；不扩权 matcher/reconciliation 语义（D-103 基线不变）。
- 本文件行号引用基于基线 `b19a6c1`；实施批如遇引用漂移，以权威文件现行文本语义为准并勘正行号。

## 8. 批准记录（待用户批准）

D-109（DECISIONS.md）状态 = proposed。用户批准 O-1..O-9 组合后：本规格 Status 转 approved；D-109 状态转已批准并登记批准组合；授权起草 P4-09 实施规格（实施规格与实施仍是后续独立批，需各自的评审与验证门）。未批准前，P4-09 实施批不得启动。
