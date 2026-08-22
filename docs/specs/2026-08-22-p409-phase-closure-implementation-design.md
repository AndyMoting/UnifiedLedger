# P4-09 阶段收口实施规格

**Status:** proposed（D-110 提案；本规格冻结 P4-09 收口批的 HOW。依 D-109 §8 批准链：本规格经独立规格评审 + 质量评审双闭环后即可实施，无需再开新决定；实施仍须 distinct verifier 与主代理最终检查）。

本规格按已批准契约 `docs/specs/2026-08-22-p409-phase-closure-contract-design.md`（Status: approved，D-109：O-1..O-9 全 A）起草，**只冻结实施 HOW，不变更契约 D1..D5 的 WHAT 语义与 O 组合**。结构与密度先例 = `docs/specs/2026-08-22-p4-06-slice2-mixed-activation-design.md`。行号依据：worktree `feat/p409-phase-closure` 基线 `28d7913`（代码文件与契约引用基线 `b19a6c1` 之间仅 docs 差异，代码行号一致）；`.local.md` 文档以主 checkout 为准。测试基础设施先例：`P406CreditFullStateOracleTest.kt`（Executor/captureFullState/Expected/12 测试）、`LedgerDatabaseMigrationTest.kt`（VERSION_ONE_STATEMENTS :2920、逐边 populated/late 系列、migrate(1,25) 用法 :519-531）、`ImportSpineMigrationCoexistenceTest.kt:222`（rg04 共存先例）、`P408ReconciliationCanonicalOracleTest.kt`（matcher/link 直驱方式）、各 RG FullStateOracle/Store 测试（竖井语料形态）。

## 1. 目的与边界

本批交付收口证明面（契约 §3 D1..D5）：D1 逐 RL-01..RL-08 合成 full-state oracle、D2 populated v1→v25 单链数据级迁移 + reopen + fresh 等价、D3 12 RG 竖井 + 完整 spine v25 + P4-08 表同库共存 + reopen 单一验收、D4 统一失败矩阵、D5 全量回归 + 正式状态文档同步。本批是证明批，不是功能批。

**零 schema / 零生产语义声明（冻结）**：

- 不新增任何 `.sqm`；schema 版本钉 v25；`Ledger.sq` 全部 DDL 与命名查询零字节改动；`docs/migrations` 注册表与本批无交集。验证方式 = 迁移 verifier 与 `LedgerDatabaseMigrationTest` 原样通过（D-108 先例）。
- 生产源码（`ledger-domain`/`ledger-application`/`ledger-data` 的 `commonMain`/`jvmMain`）零改动。全部新增代码落点 = `ledger-data/src/jvmTest`（新测试类）+ tracked 文档（GOLDEN_TESTS.md 新节、CURRENT_STATE/WORK_PLAN 同步、DECISIONS.md 实施登记）。若实施中发现任何生产缺陷：停止、登记、走 O-2 评审路由，不得与本批混编。
- 例外登记（唯一允许触碰的既有测试文件）：`ImportSpineMigrationCoexistenceTest.kt` 头注释补一行指向 D3 新验收（注释级，零断言变化）；除此以外既有测试文件一律不改、不删、不断言翻转。

## 2. 裁决（HOW 缺口八项；各配替代方案与风险）

### 裁决 1：D1 载体 = 新建独立收口 oracle 类 `P409PhaseClosureFullStateOracleTest`，不扩展任何既有类

**裁决**：新建 `ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/P409PhaseClosureFullStateOracleTest.kt`，仿 `P406CreditFullStateOracleTest.kt` 的 Executor/BatchIntakeIdSource/BatchCommitIdSource/captureFullState/Expected 纪律重建脚手架，比较面为收口超集（§3.3）。

**依据**：契约 §1.2.1 的缺口本体就是「没有任何单一 oracle 按 WORK_PLAN:120-127 矩阵逐 RL 声明覆盖闭合」——收口 oracle 的身份是 RL 矩阵闭合，与任一既有批的切片身份不同。比较面也不同：P406 oracle 只读 P4-08 7 表中的 3 表与 P4-07 5 表中的 2 表（`P406CreditFullStateOracleTest.kt:539-543` 只选 reconciliation_request/evidence_link/posting_reconciliation；duplicate 只选 :517-522 两表；P406S1-CLO-004 已登记此限制），且其 report 投影（`CreditReportProjection` :416-421，四维）无收入/转账维度，无法承载 RL-02/RL-03/RL-04。既有类全部为各批冻结验收面（P4-04 E2E 2606 行、P406 oracle 1743 行），扩展即翻搅。脚手架分叉成本 ≈ 四百行（slice2 §5.2 同量级评估），换全部既有 oracle 零扰动。

**替代方案（否决）**：扩展 `P406CreditFullStateOracleTest`——比较面缺收入/转账维度与 7+5 表全量面，且把收口矩阵塞进 P4-06 批文件，批界混乱。**替代方案（否决）**：把 capture/Expected 提升为共享 jvmTest 基类再让各 oracle 继承——重构全部既有 oracle（10+ 类），与「零翻搅」目标相反。

**风险**：脚手架复制引入漂移（复制件与 P406 原件行为不一致）。缓解 = 裁决 1 附检查项：新类 capture 的 21 个共有表投影列清单与 P406 `captureFullState`（:479-545）逐列一致（只允许扩列）；评审按此 diff 检查。

### 裁决 2：D1 报告投影 = 十维 P404 投影 + reconciliation 维度，不复用 P406 四维投影

**裁决**：收口 oracle 的 report 投影采用 `ImportSpineTransferEndToEndTest.kt:369-381` 的 `P404ReportProjection` 十字段全集（balancesByAccount、internalTransferMinor、externalIncomeMinor、externalExpenseMinor、externalCashInflowMinor、externalCashOutflowMinor、consumptionMinor、budgetEffectMinor、categoryTotals、netWorthChangeMinor），另加 reconciliation 维度（`SqlDelightP408ReconciliationStore.readReconciliationReport(ledgerId)` 的 postingId/status/activeLinkIds 投影，`P408ReconciliationCanonicalOracleTest.kt:31-33/:124-126` 先例）。

**依据**：契约术语节把「report projection（含 P4-08 reconciliation 维度）」列入 full-state 定义；RL-02 需要收入维度、RL-03/RL-04 需要 internalTransfer/两端余额维度，P406 四维投影（consumption/cashOutflow/netWorth/balances）不覆盖；P404 十维投影是已冻结的最近超集（D-100 round A 九维报告投影 + balances）。

**替代方案（否决）**：按 RL 各配小投影——投影碎片化，full-state 比较不再齐一，违背「完整比较纪律」（WORK_PLAN:137）。

**风险**：十维投影对信用/混合行的新维度取值（externalExpenseMinor 是否含信用消费）在 P404 归约中按 hidden EXPENSE 账户正总额计（`ImportSpineTransferEndToEndTest.kt:461-492` 归约先例），信用消费当日无现金流出、费用维度仍计全额——该语义与 D-058/D-107 报告语义一致，无冲突；实施时以 RL-05/RL-06 期望值表（§3.2）显式登记各维度取值，防止归约歧义。

### 裁决 3：D2 落点 = 新类 `P409SingleChainMigrationTest`，复用 `VERSION_ONE_STATEMENTS`，自带小型 helper

**裁决**：新建 `ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/P409SingleChainMigrationTest.kt`（同包）。复用既有 `internal val VERSION_ONE_STATEMENTS`（`LedgerDatabaseMigrationTest.kt:2920`，同包可见）；`migrationSqliteProperties`/`queryCount`/`schemaMetadata` 为该文件 private（:2839/:2711/:2757），在新类内以同名局部副本自带（合计约 40 行，注明出处），**不修改既有文件的可见性**。

**依据**：D2 是独立验收工件（单链数据级 fresh=migrated），与既有逐边系列（`LedgerDatabaseMigrationTest.kt` 已 3008 行）身份不同；契约 §1.1 明言「无 D2 要求的单链 populated 数据级等价测试」是缺口本体。零触碰既有文件把回归面压到最小。

**替代方案（否决）**：并入 `LedgerDatabaseMigrationTest`——该类是逐边系列的冻结归属，追加 400+ 行单链编排翻搅既有验收面。**替代方案（否决）**：把三个 private helper 提升 internal 共享——为省 40 行修改既有冻结文件，方向与裁决 1 相反。

**风险**：helper 副本与原件漂移。缓解 = 副本逐行取自 :2711/:2757/:2839 原文，评审对照；`schemaMetadata` 断言在两文件中同时存在本身就是双重证据。

### 裁决 4：D2 fresh 等价 = 「fresh v25 + 等价形态显式构造」全部共享表逐行数据级比较，零聚合投影；store 驱动推进为比较后独立断言

**裁决**：等价比较的两侧 = (i) 单链迁到 v25 的库（§4 stage 表终态）；(ii) fresh `Schema.create` 的 v25 库，向其**显式插入同一行集**——包括迁移才会产生的 sentinel 行（v23 迁移为合格 transfer posting 播种的 posting_reconciliation PENDING 行与 `migration-v23-seed` reconciliation_request 行，形态 = `LedgerDatabaseMigrationTest.kt:325-346`；v24 迁移为 v21-v23 既有 spine 行回填的 `funding_state='UNRESOLVED'`/`candidate_generated_at='migration-v24-unresolved'`，:644 断言先例——fresh 侧插入时显式写同值）。比较投影 = 逐表 `SELECT 全列 ORDER BY`（排序键 = 首列 + 次列，`rowComparator` 纪律同 P406 :449-463）后列表相等，**覆盖两库共有的全部表**（§4.3 清单），不做任何聚合/计数替代。store 驱动的 confirmLink 推进（对迁移播种 PENDING 行走 `SqlDelightP408ReconciliationStore.confirmLink` 至 CHECKED，`:368` confirmationOnMigratedV23 先例）在数据级比较**完成后**作为独立断言段执行（仅迁移侧），不进入等价快照。

**依据**：契约 D2 要求「与 fresh v25 构造的等价形态做数据级等价比较」——「等价形态」的唯一自洽读法是 fresh 侧以合法 INSERT 显式重建同一终态（含 sentinel 行），否则 fresh 侧结构性缺行（fresh v25 不播种、不回填），逐行比较无从谈起。比较后置 store 推进段避免把 store 行为时间性混入静态等价快照，同时保住「迁移播种行可被 store 正常推进」的守卫/迁移互操作证明。

**替代方案（否决）**：聚合/计数投影（每表 count + 金额和）——契约 §1.2.2 明言既有缺口就是缺「数据级」，聚合回到计数级。**替代方案（否决）**：把 store 推进段放进等价快照——fresh 侧须精确复刻 store 时序（PENDING 由迁移播种 vs 由 store 首建，reconciliation_id 不同源），等价构造不可判定。

**风险**：v25 重建后列可空性（:589 插入省略 funding 列成功）使 fresh 侧 sentinel 值依赖人工对齐。缓解（冻结构造纪律，消除循环性）：sentinel 一律以**命名常量**定义（`FUNDING_BACKFILL_STATE = "UNRESOLVED"`、`FUNDING_BACKFILL_GENERATED_AT = "migration-v24-unresolved"`、v23 播种行标识常量）；**fresh 侧** = §4.1 行集 + 上述常量显式写入构造；**迁移侧** = 先以同名常量独立断言其 sentinel 实际值（`:644` 先例：SELECT 实际值 == 常量，防测试常量与迁移行为意外同漂移的第二来源 = 既有逐边 populated 测试已冻结这些值）；两侧构造/断言独立完成后**再**逐行比较。非 sentinel 列若出现未预期的迁移改写（本规格未登记任何此类改写），逐行比较即失败暴露——不存在「先看迁移结果再照抄构造 fresh」的路径。

### 裁决 5：D3 最小充分集 = 每竖井一个代表性 operation + 其 replay（分层接入规则），非全语料回放

**裁决**：D3 新类 `P409SiloSpineCoexistenceTest`（落点同上目录）对 12 套竖井按分层规则接入：(i) 竖井具备可对任意 `(LedgerDatabase, driver)` 构造的 store/适配器（rg01 提交端口、rg02 `Rg02ManualIncomeAdapter`、rg03 `SqlDelightRg03TransferStore`、rg04 `ExecuteRg04ImportOperation`+`decodedCase()`（`Rg04ImportLifecycleEndToEndTest.kt:855`）、rg05/06/07 `SqlDelightRgXXStore`、rg08..rg12 `FixtureReplay` 适配 + `SqlDelightRgXXStore`，`SqlDelightRg08StoreTest.kt:58` main-path 先例）→ 从冻结语料取一个 accepted 主路径 operation 在共享库执行 + 同请求 replay 断言 NoChange/原 receipt；(ii) 若某竖井 typed operation 需要该竖井 oracle 的 private harness 状态才能驱动，则回退为按迁移 populated 测试先例以 raw SQL 播种该竖井代表性持久行，并逐竖井登记回退理由。量级：12 竖井 × (1 op + 1 replay) + spine 完整形态（§5.2 行集，约 14 个 spine 侧操作含确认/link/review）≈ 40 次操作，单测试单内存库，复杂度与 `Rg04FullStateOracleTest` 单根回放同量级。

**依据**：全语料（12 竖井合计 357 operations，CURRENT_STATE:7-14 计数）在单库回放结构性不可行——`GoldenV2Oracle.executeGoldenV2Root` 每根新建内存库（`GoldenV2Oracle.kt:74-77`），rg08/09/10/11/12 的 FullStateOracle 驱动纯 runtime 无 store（`Rg08FullStateOracleTest.kt:55`「no store」），其语料-vs-DB 等价本就由各自 Store 测试承载；D3 的 WHAT 是共存/零串音/reopen 保持（契约 §3.3），不是语料重验证。rg04 先例（`ImportSpineMigrationCoexistenceTest.kt:222` 取 importOperations[0]/[2] 两个 operation）与 **rg08-12 先例**（`MultiRgStoreCoexistenceTest.kt`，579 行：五 store 共享单一 `LedgerDatabase`、各 commit 其典型正式路径、reopen 后逐 store 快照逐字节相等；含 `adaptRg08/09/10/12Fixture` + rg11 create-input/catalog 全套 wiring）共同证明「多竖井同库 + 代表路径 + reopen 快照稳定」形态既有先例，本裁决是将其扩展到 12 竖井 + spine 共存。

**替代方案（否决）**：全 357-op 语料单库回放——须重构 `GoldenV2Oracle` 与 10 套 private harness 并为 5 个纯 runtime 竖井新建 DB 驱动层，扩面超出零翻搅边界一个量级，且重复各自 oracle 已闭合的证明。**替代方案（否决）**：纯 raw SQL 播种每竖井一行——丢失「竖井 runtime 在共享库可执行 + replay 稳定」的证明强度，弱于 rg04 先例。

**风险**：某竖井分层归属实施时争议（adapter 需要的 catalog/identity source 绑定主路径之外的语料状态）。缓解 = §5.1 逐竖井接入表在规格内冻结每竖井的 operation 选择与接入方式；实施若遇 (i) 层不可行，按 (ii) 回退并在 D-110 实施登记逐竖井留痕，不允许静默跳过竖井。

### 裁决 6：D4 矩阵落点 = `docs/GOLDEN_TESTS.md` 新节（覆盖登记基线），本规格只承载裁决依据

**裁决**：8 RL × 6 失败模式矩阵作为新节「P4-09 收口失败矩阵」写入 `docs/GOLDEN_TESTS.md`，位置紧随首批 8 个本地真实来源场景表（:177 之后）；本规格 §6 载同一矩阵的裁决版（含选择理由与新增锚点落点），两处矩阵格内容一致，GOLDEN_TESTS 版为后续批次引用的正式基线。

**依据**：契约 §3.4 要求矩阵「可被后续批次引用为覆盖登记基线」。落点三选一：规格文档是批次快照（ dated、不再维护），不宜作基线；独立新 tracked 文档与 AGENTS.md 文档所有权表冲突（金色契约与测试覆盖登记的 owner = GOLDEN_TESTS.md，RL 维度定义本身就在 :164-177）；GOLDEN_TESTS.md 既有 RL 场景表是矩阵行维度的定义源，矩阵落其旁即是唯一「引用时不需要跨文档拼装」的位置。

**替代方案（否决）**：矩阵只留在本规格——后续批次引用 dated 快照，行号漂移无人勘正，违背基线语义。**替代方案（否决）**：新建 `docs/FAILURE_MATRIX.md`——新增权威文档须在 AGENTS.md 登记新触发条目，扩文档面无收益。

**风险**：GOLDEN_TESTS.md 是正式权威文档，收口批编辑须最小化。缓解 = 新节为纯登记表 + 引用指针，零改动既有节文本；project_docs 验证覆盖。

### 裁决 7：O-1 必选勾选门登记位置 = 规格本节定义判据（tracked）+ `docs/PROJECT_STATE.local.md` 收口验收清单（仓库外执行与留痕）

**裁决**：勾选门的**判据定义**（验证对象 = `.external`/本地测试区真实账单跑受支持的收口形态；执行者 = 用户侧；产物 = 本地检查点留痕，不进 Git）以 tracked 文本冻结在本规格 §8（及 D-110 实施门），保证判据不可漂移；勾选门的**执行实例与完成证据**登记于主 checkout `docs/PROJECT_STATE.local.md` 的「收口验收清单」小节（实施批创建），逐项勾选。两处分工 = tracked 判据 + local 证据。

**依据**：契约 O-1 明言「其执行与结果在本地检查点文档留痕（不进 Git）」——证据面物理上只能在 local 检查点；判据若也只写 local 则不受版本控制、可被顺手改写，失去「必选门」的刚性。PROJECT_STATE.local.md 是 AGENTS.md 指定的恢复真相/验收检查点 owner。

**替代方案（否决）**：全部登记在规格 §8——证据无落点，勾选动作不可追溯。**替代方案（否决）**：全部登记在 PROJECT_STATE——判据不受 tracked 评审，D-109 的「必选」升格可被静默弱化。

**风险**：实施批在 worktree 内无法写主 checkout 的 local 文档。缓解 = 清单创建/勾选动作路由给主代理（AGENTS.md：主代理拥有 Git 状态与最终验收；`.local.md` 以主 checkout 为准），规格 §7 明示该路由。

### 裁决 8：RL-07 confirm 注入格 = 约束驱动失败锚点，不给生产 P408 store 加异常注入参数

**裁决**：D4 矩阵 RL-07 F3（matcher confirm 注入）的新增锚点采用「约束驱动 confirm 失败」形态：在收口 oracle 内以预置冲突行（如预插同一 link_id）使 `confirmLink` 在写入段触发 DB 约束失败，断言零残留 + 身份可重试 + 纠正后重试成功 + reopen 保持；不给 `SqlDelightP408ReconciliationStore` 添加 `FailureInjector` 构造参数。spine 侧注入点（`ImportSpineFailurePoint` 三点，`SqlDelightImportSpineStore.kt:44`）已存在且收口 oracle 复用（§3.1），不受本裁决影响。

**依据**：P408 store 无注入钩子（`SqlDelightP408ReconciliationStore.kt:21` 构造无 FailureInjector 参数）；加参数 = 生产源码 diff，虽可做成 default-off 加性，但 O-2 的「生产源码零语义变更」在收口批取保守读法（证明批不为例外开洞）；`P408ReconciliationStoreTest.duplicateLinkIdConstraintFailureIsTypedRejectedWithZeroWrites`（:356）已证明约束失败路径的整链回滚零残留语义，同构锚点在收口 oracle 复现即闭合「confirm 写入失败零残留」模式，非近似覆盖（格值登记为新增锚点，触发器 = 约束而非异常，格内显式登记此差异）。

**替代方案（否决）**：给 P408 store 加 default-off 注入参数——为测试便利开生产 diff 先例，收口批零生产改动的声明失效。**替代方案（否决）**：该格登记延期——模式可闭合，延期与 O-6 纪律（不得顺手丢弃可闭合覆盖）不符。

**风险**：约束驱动与异常注入的路径覆盖不完全同构（约束失败走 SQLException→typed reject 分支，异常注入走事务回滚分支）。缓解 = 收口 oracle 断言面含两分支共同不变量（零残留、身份可重试、重试成功、reopen 保持），并在矩阵格登记触发器差异；若独立评审认定语义缺口实质，处置出口 = O-2 评审路由（生产注入参数作为独立缺陷修复提案），不在本批默认。

**替代方案（否决，补记）**：jvmTest 侧以委托包装 `JdbcSqliteDriver` 在目标语句上注入真实异常——store 构造绑定 `LedgerDatabase(driver)`，包装驱动须穿过 `LedgerDatabase` 与 store 的全部构造路径并保持 SQLDelight 内部事务语义不变，单为矩阵一格引入贯穿式测试基建改动，侵入面与复杂度不成比例；且异常注入分支的不变量已由 spine 侧注入点（§3.1）与 P406 :1017/:1430 在同一事务纪律下证明。否决。

## 3. D1 逐 RL 全状态 oracle（`P409PhaseClosureFullStateOracleTest`）

### 3.1 类骨架与驱动面

- 仿 P406 Executor（:298-333）：`ExecuteImportIntake` + `ConfirmImportCandidate`（五种决策形状工厂：`OrdinaryOutFactory`、`TransferFlowFormalFactory`、`CreditFlowFormalFactory`、`MixedPaymentFlowFormalFactory`；ordinary income 工厂 = `createAssetReceivedOrdinaryIncome`，`ImportSpineLifecycleEndToEndTest.kt:226-234` 先例）+ `ReviewImportDuplicateCandidate`（`ImportSpine.kt:486`）+ `SqlDelightP408ReconciliationStore.confirmLink`。注入器支持 = `SqlDelightImportSpineStore(database, driver, failure)`（:335-349 先例，`ImportSpineFailurePoint` 三注入点 `SqlDelightImportSpineStore.kt:44`）。
- catalog：ledger `ledger-p409-oracle`，账户 = `account-asset-a`（余额宝锚 ASSET）、`account-asset-b`（ASSET）、`account-credit-huabei`（LIABILITY）、`expense-account-food`/`expense-account-clothes`、`income-account-salary`；分类 = food/clothes（EXPENSE 二级，挂费用账户）+ salary（INCOME 二级，挂收入账户）。全部确定性合成 ID；`generatedAt = "2026-08-22T08:00:00Z"`、`confirmedAt = "2026-08-22T10:00:00+08:00"`、`inputRef = "batch-p409-oracle"`。
- **形态归属登记**：oracle 以 parser 输出形状直驱 spine（P406 :80-83 先例）；「微信形态/支付宝形态/余额宝路由」的平台路由证明由 parser 测试承载（`WechatBillParserJvmTest`、`AlipayCsvParserJvmTest`、`AlipayCsvParserYuebaoTransferJvmTest`），矩阵（§6）在 F1 列引用；oracle 行只以 `ImportRecordKind`/facts/profile 区分。

### 3.2 逐 RL 合成 fixture 值表（冻结；全部匿名代表值、固定合成 +08:00 时间、精确最小单位金额）

| RL | kind / profile | 金额（minor） | 时间（+08:00） | 方向/状态 | 确认决策 | 正式形态与报告要点 |
| --- | --- | --- | --- | --- | --- | --- |
| RL-01 | `ordinary_flow_source` v1 | 4580 | 2026-08-01T12:00:00 | out / settled | `OrdinaryFlow(category-food, account-asset-a)` | 费用 +4580 / 资产 −4580；consumption/externalExpense/externalCashOutflow +4580，netWorth −4580 |
| RL-02 | `ordinary_flow_source` v1 | 7250 | 2026-08-02T12:00:00 | in / settled | `OrdinaryFlow(category-salary, account-asset-a)` | 收入 +7250 / 资产 +7250；externalIncome/externalCashInflow +7250，netWorth +7250 |
| RL-03 完整腿 | `transfer_flow_source` v2 | 3000 | 2026-08-03T12:00:00 | out / settled | `TransferFlow(account-asset-a → account-asset-b)` | −3000/+3000；internalTransfer +3000，external 四项全零 |
| RL-03 缺腿 | `transfer_flow_source_missing_leg` v2 | 1500 | 2026-08-04T12:00:00 | out / settled | 无（保持 `pending_confirmation`，不猜测） | 零正式写入；全状态断言候选仍 pending |
| RL-04 | `transfer_flow_source` v2（余额宝路由输出形状：VALID_COMPLETE 与 VALID_INCOMPLETE 两变体，`AlipayCsvParser.kt:451/:467` 形态） | 2000 | 2026-08-05T12:00:00 | out / settled | `TransferFlow(account-asset-a → account-asset-b)`（余额宝锚为 to 端） | −2000/+2000 两端余额；internalTransfer +2000；组合锚点推进 posting reconciliation |
| RL-05 消费 | `credit_expense_source` v3 + profile `CREDIT_EXPENSE_DIRECT(资产 null, 信用=花呗)` | 10000 | 2026-08-06T12:00:00 | out / settled | `CreditExpenseFlow(category-food, account-credit-huabei)` | 费用 +10000 / 负债 −10000；consumption +10000，现金流出 0，netWorth −10000 |
| RL-05 还款 | `credit_repayment_source` v3 + profile `CREDIT_REPAYMENT(资产=余额宝, null)` | 5620 | 2026-08-07T12:00:00 | out / settled | `CreditRepaymentFlow(account-asset-a, account-credit-huabei)` | 资产 −5620 / 负债 +5620；netWorth 0 |
| RL-05 退款 | `credit_expense_source` v3（refund 变体标记）+ profile `CREDIT_EXPENSE_REFUND` | 1535 | 2026-08-08T12:00:00 | in / refund_settled | `CreditExpenseRefundFlow(category-food, account-credit-huabei, tx-原消费)` | 费用 −1535 / 负债 +1535；独立经济事件、快照 `original_transaction_id` 关联 |
| RL-06 | `mixed_payment_source` v3 + profile `MIXED_PAYMENT(余额宝, 花呗)` | 1240（=360+880） | 2026-08-09T12:00:00 | out / settled | `MixedPaymentFlow(category-food, account-asset-a, account-credit-huabei, 360, 880)` | 费用 +1240 / 资产 −360 / 负债 −880；group 头 + 两腿（leg_index 1=asset/2=liability） |
| RL-07 | 平台侧适用子集：复用 RL-03 确认后的 transfer posting + spine evidence | 3000 | 同 RL-03 | — | `P408ConfirmLinkRequest`（形状 = `P408ReconciliationCanonicalOracleTest.kt:187-209`，matchBasis 五元集） | 恰一笔正式转账（既有）；link + posting_reconciliation PENDING→CHECKED；零第二笔交易 |
| RL-08 关闭行 | `ordinary_flow_source` v1 | 900 | 2026-08-10T12:00:00 | out / `closed`（raw 保留） | 无（`VALID_INCOMPLETE`/`incomplete`，不可确认） | 零资金影响、零正式写入；`ImportFundingState.NO_FUNDS` + rule `source-contract-closed-v1`、duplicate kind `CLOSED_OR_FAILED_NO_FUNDS`（P407 oracle :115-118/:533 冻结形态）；重复导入幂等（与 RL-01 重导入共用机制面） |
| RL-08 关闭变体·转账 | `transfer_flow_source` v2 | 1200 | 2026-08-11T12:00:00 | out / `closed`（raw 保留） | 无 | 同上形态，kind 维度登记（跨 kind 关闭格） |
| RL-08 关闭变体·信用 | `credit_expense_source` v3 + profile `CREDIT_EXPENSE_DIRECT` | 2400 | 2026-08-12T12:00:00 | out / `closed`（raw 保留） | 无 | 同上形态 |
| RL-08 关闭变体·混合 | `mixed_payment_source` v3 + profile `MIXED_PAYMENT(余额宝, 花呗)` | 1860 | 2026-08-13T12:00:00 | out / `closed`（raw 保留） | 无 | 同上形态（状态 raw 保留 + unresolved + `VALID_INCOMPLETE`，零拒行升级 = slice2 §3.2 状态门纪律） |

RL-05/RL-06 的**金额与 profile 原样重述**（`P406CreditFullStateOracleTest.kt:96-119`：10000/5620/1535/1240=360+880 与三 profile token）；**时间不沿用 P406 原日期**，采用本 oracle 顺序合成日期（以表列为准：消费 08-06、还款 08-07、退款 08-08、混合 08-09）。RL-08 关闭行族的状态 token/funding 形态取自 P407 冻结语料（statusToken `"closed"`、`NO_FUNDS`、`source-contract-closed-v1`，`P407DuplicateClosedFullStateOracleTest.kt:115-118`）。

### 3.3 断言面（收口 full-state 定义，冻结）

`P409FullState` = 29 个行列表 + 2 个投影（9 spine + 1 profile + 5 duplicate + 7 P4-08 + 5 formal + 2 mixed group），全部排序后逐行比较（P406 纪律 :449-463/:581-609）：

1. spine 9 表（import_request / import_source_record / import_evidence / import_candidate / import_candidate_requires_confirmation / import_candidate_status_history / import_candidate_decision_snapshot / import_confirmation / import_receipt；列清单 = P406 :495-516）；
2. profile 1 表（import_candidate_payment_profile，:503-507）；
3. P4-07 duplicate 5 表（import_duplicate_candidate / import_duplicate_status_history :517-522 + **import_duplicate_review_request / import_duplicate_review_snapshot / import_duplicate_review_receipt**，列 = `P407DuplicateClosedFullStateOracleTest.kt:361-363`——P406 未读的 3 表在本 oracle 全读）；
4. P4-08 7 表全量（reconciliation_request / reconciliation_request_snapshot / evidence_link / evidence_link_history / posting_reconciliation / posting_reconciliation_history / reconciliation_receipt；列 = `P408ReconciliationCanonicalOracleTest.kt:231-267`）；
5. formal 5 表（ledger_transaction / posting_set / transaction_version / ledger_transaction_current_version / posting；= P406 :523-527）；
6. mixed group 2 表（:528-537）；
7. report 投影（裁决 2 十维 + 余额）；
8. reconciliation 维度（readReconciliationReport 投影）。

每个 RL 维度测试在每个检查点断言**整个** `P409FullState`（不仅该 RL 触碰的表）——收口语义 = 逐维度证明对全状态的影响。

### 3.4 测试清单（新锚点；~12 个测试）

1. `rl01OrdinaryExpenseFullChainDuplicateIntakeAndReviewDisposition`：intake→确认→formal→report→等值二次 intake（新 request id/ordinal）→ `EXACT_BUSINESS_TUPLE` duplicate 候选 DEFERRED → review `CONFIRMED_DUPLICATE` 终态（review 三表行 + 原候选 formal 不变）；三个检查点全状态比较。
2. `rl02OrdinaryIncomeFullChainWithIncomeReportDimension`：intake→确认→收入维度 report 断言→重放原 receipt。
3. `rl03TransferCompleteMissingLegAndEvidenceLinkCombo`（**组合锚点 a**）：完整腿确认 ACCOUNT_TRANSFER → 对其 posting `confirmLink` → posting_reconciliation PENDING→CHECKED + link/快照/receipt 全行 → replay NoChange；缺腿同序列保持 pending；四个检查点。
4. `rl04SecondSourceRoutingBothEndsAndReconciliationAdvance`：VALID_COMPLETE 变体确认两端余额 → confirmLink 推进；VALID_INCOMPLETE 变体保持资料不足。
5. `rl05CreditThreeAnchorsLifecycleRestated`：消费→还款→退款三锚点单序列串联（含 `original_transaction_id` 快照关联与三时点余额/报告），断言面含 P4-07/P4-08 全表（重申 P406 锚点，契约 D1「收口增量 = 在收口 oracle 内重申」）。
6. `rl06MixedThreePostingsAndGroupRestated`：12.40=3.60+8.80 三分录 + group 两表 + 报告效应重申。
7. `rl07PlatformSideMirrorSubsetZeroSecondTransaction`（含两条 RL-07 新增腿）：主路径 = 合成镜像 evidence → 精确 posting 判定 → link/状态变化/零第二笔交易 → replay NoChange；**腿 2（F3 新格）** = 约束驱动 confirm 失败（预置冲突 link_id 使 `confirmLink` 在写入段约束失败）→ 零残留 + 身份可重试 → 纠正重试成功 → reopen 后保持（裁决 8）；**腿 3（F5 新格）** = 并发 confirmLink（同 posting 两请求）→ 单赢家 Accepted、输家类型化冲突零残留（`changedRequestRetry` :198 语义的并发形态）；银行侧真实维度以断言旁注释登记延期指针（O-8/D-099:1540），不做银行侧行。
8. `rl08ClosedRowsAcrossKindsStayZeroFunds`：普通/转账/信用/混合四 kind 关闭变体（状态 token raw 保留）零资金影响 + 重复导入幂等重放。
9. `creditAndMixedDuplicateReviewClaimDispositionFinalStates`（**组合锚点 b**，契约 :82 残余格）：两条等值信用行 → duplicate → review-claim `CONFIRMED_DUPLICATE` → 第二候选阻断正式化（`p407ConfirmedDuplicateBlocksFormalFactory` 先例 `ImportSpineLifecycleEndToEndTest.kt:1242`）；两条等值混合行 → duplicate → `DISMISSED_LOOKALIKE` → distinct 重入可正式化（`P407DuplicateClosedFullStateOracleTest.matrix3bNoWinnerAndDistinctOrDismissedSourcesStillFormalize` 先例，P407 :691）；两处置终态全状态。
10. `closureFormSurvivesReopenAndReplaysOriginalReceipts`：文件库（`Files.createTempFile`，P408 reopen 先例 :148-185）承载 §5.2 之外的收口形态行集，reopen 后全状态相等 + replay 原 receipt。
11. `concurrentCreditAndMixedConfirmsHaveSingleWinnerAndLoserReplay`（F5 新格）：双线程并发确认同一信用候选与同一混合候选，单赢家、输家零写入且不消耗提交 id（BatchCommitIdSource 剩余断言）、输家重放返回赢家 receipt（P4-02 :659 先例形态）。
12. `ordinaryAndIncomeConfirmDomainFailuresStayPendingZeroWrites`（F4 新格）：未知分类/未持有 funding 账户/跨账本 → `SPINE_DOMAIN_VALIDATION_FAILED` 零写入、候选保持待确认、claim 可重试。

### 3.5 排除（本节内）

真实来源数据不入仓（O-1）；RL-07 银行侧行不合成宣称（O-8）；不新增诊断 severity/scope（契约 §6）；信用/混合既有 12 测试不迁移不复制断言体——收口测试只做序列重申与矩阵登记。

## 4. D2 populated v1→v25 单链迁移测试（`P409SingleChainMigrationTest`）

### 4.1 stage 编排表（冻结；插入语句形状逐条给出先例）

| # | 动作 | 行集与先例 |
| --- | --- | --- |
| 0 | `DriverManager` 执行 `VERSION_ONE_STATEMENTS` | v1 核心链 + 种子行（`tx-existing` EXPENSE + 2 postings，`LedgerDatabaseMigrationTest.kt:2987-3007`）——即「populated v1 正式账」 |
| 1 | `migrate(1, 20)` | v20：全部 rg owners、无 import_* |
| 2 | v20 插入 | rg03 一行（`:142` 形态）+ rg04 silo 一行（`ImportSpineMigrationCoexistenceTest.kt:155` 形态）——spine 前代竖井在场 |
| 3 | `migrate(20, 21)` | spine 9 owners 建立 |
| 4 | v21 插入 | 普通 spine 行（15 列形态，无 funding 列，`:637-640` 先例）：request/source(ordinary_flow_source v1)/evidence/candidate(ordinary_flow)/status_history |
| 5 | `migrate(21, 22)` | transfer kind CHECK 扩展（21.sqm） |
| 6 | v22 插入 | transfer_flow_source v2 行 + missing_leg 行（15 列）；正式 ACCOUNT_TRANSFER 链（tx/posting_set/version/current_version/±postings，`:326-331` 形态）——供 v23 播种 |
| 7 | `migrate(22, 23)` | P4-08 7 表 + 为 v22 transfer 两 posting 播种 posting_reconciliation PENDING(latest_sequence=1) + `migration-v23-seed` request（`:343-345` 断言先例） |
| 8 | v23 插入 | P4-08 行（reconciliation_request/snapshot、evidence_link/history、reconciliation_receipt；`:675-681` 形态）。**不**手改 posting_reconciliation（update guard 触发器在位，推进留给 store 段） |
| 9 | `migrate(23, 24)` | duplicate 5 表 + funding 列 + 重建；v21/v22 spine 行回填 sentinel（UNRESOLVED / migration-v24-unresolved，`:644`） |
| 10 | v24 插入 | duplicate candidate + history（v3 行形态 = P406 `Expected.intake` duplicate 段 :684-693 的持久投影）+ 一条带显式 funding 列的 spine 行（`:555-559` 形态） |
| 11 | `migrate(24, 25)` | credit/mixed 结构 + source/candidate 重建收录 v3 kinds |
| 12 | v25 插入 | credit_expense_source/credit_repayment_source/mixed_payment_source v3 行（funding 列可空形态，`:589` 先例）+ import_candidate v3 kinds + import_candidate_payment_profile 行（双腿 token 形态） |
| 13 | reopen（新 driver 同文件） | 数据保持 + 守卫 + FK 检查（§4.2）→ 数据级 fresh 等价（裁决 4）→ store confirmLink 推进段（仅迁移侧，`:368` 先例，PENDING→CHECKED + history seq2 + receipt，且推进后 reopen 再读保持） |

### 4.2 reopen 断言

种子与插入行逐值保持（v1 核心链、rg03/rg04 行、各代 spine 行、sentinel 值、P4-08 行）；`pragma_foreign_key_check` = 0；`schemaMetadata(fresh) == schemaMetadata(migrated)` 前置（:519-531 同法）。守卫断言 = **按家族逐名枚举**（`:584-586`/`:695` 形态）+ sqlite_master 触发器总数，分组为本人从 `20.sqm`..`24.sqm` 逐文件枚举核实（每文件 `CREATE TRIGGER` 全名清点）：

| 边 | 迁移文件 | 触发器数 | 构成（核实） | 断言先例 |
| --- | --- | --- | --- | --- |
| →v21 | 20.sqm | 20 | 9 张 import 表 ×2 update/delete 守卫（18）+ `import_status_history_sequence_guard` + `import_status_history_transition_guard` | `ImportSpineMigrationCoexistenceTest.kt:175-176`（两 history 守卫逐名） |
| →v22 | 21.sqm | 20（同名重建） | 同上 20 个同名重建（v22 重建边不改守卫集） | E40 测试族 |
| →v23 | 22.sqm | 18 | P4-08 全部 18 个（`LedgerDatabaseMigrationTest.kt:655-665` 清单 18 项；断言循环 :695；其中 `posting_reconciliation` 仅 delete 守卫 + `posting_reconciliation_update_guard` 语义守卫） | :695 |
| →v24 | 23.sqm | 17 | `import_source_record` 守卫 ×2 同名重建 + duplicate 家族 15（5 表 ×2 守卫 + `import_duplicate_history_sequence`/`_terminal`/`_creation_owner`/`_review_owner`/`import_duplicate_review_receipt_consistency`） | P407 oracle 迁移段 |
| →v25 | 24.sqm | 14 | 同名重建 6（`import_source_record`/`import_candidate`/`import_candidate_decision_snapshot` 各 ×2）+ 新增 8（profile ×2、group/leg guard ×4、`mixed_payment_group_complete`、`mixed_payment_group_leg_before_head`） | `:579-586`（8 新增逐名） |

v25 终态**去重后独立触发器名合计 = 20（import 家族）+ 18（P4-08）+ 15（duplicate 家族）+ 8（v25 新增）= 61**；测试以逐名枚举 + `SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name IN (61 名清单)` = 61 断言，另加一次 append-only 探针（对 import_candidate_status_history 的 UPDATE 须抛 SQLException，`spineOwnersAreAppendOnlyAndStatusTransitionsAreGuarded` :891 同族）。

### 4.3 数据级等价投影（裁决 4 细化）

逐行比较的表集合（两库共有）：formal 5 表、rg03/rg04 插入行所在表、spine 9 表、profile、duplicate 5 表、P4-08 7 表、mixed group 2 表（空表相等）。fresh 侧构造 = §4.1 行集 + 命名常量 sentinel（裁决 4 冻结纪律；funding 列写 UNRESOLVED/migration-v24-unresolved 于 v21/v22 行；v23 播种行与 migration-v23-seed request 显式插入）。比较实现 = 每表 `SELECT` 全列后以**全行比较器排序**（`selectRows` + `sortedWith(rowComparator)`，P406 :449-463 纪律）再列表相等，**不依赖 SQL ORDER BY**——四张 history 表（import_candidate_status_history / import_duplicate_status_history / evidence_link_history / posting_reconciliation_history）首两列（ledger_id + 拥有者 id）非唯一（sequence 才唯一），`ORDER BY 1,2` 不足以确定行序；全行比较器与排序键无关地保证齐一比较。**零聚合投影**。

## 5. D3 RG 竖井 + 完整 spine v25 + P4-08 表同库共存（`P409SiloSpineCoexistenceTest`）

### 5.1 逐竖井接入表（分层规则裁决 5 的实例化；实施遇不可行按 (ii) 回退并登记）

| 竖井 | 接入层 | 代表 operation（冻结语料内主路径 accepted） |
| --- | --- | --- |
| rg01 | (i) 提交端口（ConfirmedManualExpenseCommitPort 族） | create 主路径 |
| rg02 | (i) `Rg02ManualIncomeAdapter` | create 主路径 |
| rg03 | (i) `SqlDelightRg03TransferStore` | manual_create accepted |
| rg04 | (i) `ExecuteRg04ImportOperation` + `decodedCase()` | importOperations[0]（intake accepted） |
| rg05 | (i) `SqlDelightRg05Store` | manual_path accepted |
| rg06 | (i) `SqlDelightRg06Store` | manual_path accepted |
| rg07 | (i) `SqlDelightRg07Store` | original accepted |
| rg08 | (i) `Rg08FixtureReplay` + `SqlDelightRg08Store` | main path lend accepted |
| rg09 | (i) `SqlDelightRg09Store` | main_path accepted |
| rg10 | (i) `SqlDelightRg10Store` | main_path accepted |
| rg11 | (i) `SqlDelightRg11Store` | 首个 accepted |
| rg12 | (i) `SqlDelightRg12Store` | 首个 accepted |

每竖井执行 accepted op + 同请求 replay（NoChange/原 receipt）。

**rg08-12 接入与 reopen 纪律先例**：`MultiRgStoreCoexistenceTest.kt`（579 行）已验证五 store 共享单一 `LedgerDatabase`、各 commit 典型正式路径（RG-08 lend / RG-09 main / RG-10 main / RG-11 create / RG-12 correction）、reopen 后逐 store 快照逐字节相等，且其 `adaptRg08/09/10/12Fixture` + rg11 create-input/catalog wiring 可直接复用为本表 (i) 层 rg08-12 的接入蓝本；本测试把该形态扩展到 12 竖井 + spine + P4-08 同库（§5.3 断言面相应扩展为零串音 + 反向 id 过滤 + 竖井 replay）。

### 5.2 spine 完整形态行集（同库）

§3.2 全部 RL 行（六 kind 全覆盖 + 关闭变体）+ 每可确认 kind 一次确认（普通/收入/转账完整/信用三锚/混合）+ RL-01 duplicate→review + RL-07 confirmLink + 组合锚点 a/b 的最小重演。ledger 与各竖井 ledger 不同 id（rg04 先例 :74/:230 不同 ledger 共享 formal 链合法）。

### 5.3 断言面

1. **零串音**：每竖井表行数与逐行投影在全部 spine 写入前后相等（捕获-比较）；反向 = spine 9+5+7 表行不出现任何 rgXX 前缀 id（按 id 前缀过滤计数 = 0）；共享 formal 链计数 = 各竖井 accepted 交易数 + spine 确认数（rg04 先例 :296-301 形态扩展）。
2. **reopen**：文件库，reopen 后全投影相等（竖井 + spine + P4-08 + formal）。
3. **RG 语料不受 spine 写入影响**：reopen 后逐竖井 replay 代表 op → NoChange/原 receipt（:233-246 rg04 replay 先例扩展）。

## 6. D4 统一失败矩阵（GOLDEN_TESTS.md 新节 + 本节裁决版）

### 6.1 矩阵（8 RL × 6 模式；格值 = 既有锚点（文件:测试）或 **新增**（收口 oracle 测试名）或 已登记延期+指针）

| RL | F1 解析 fatal | F2 intake 注入 | F3 confirm 注入 | F4 领域拒绝 | F5 并发输家 | F6 迁移 late-stage |
| --- | --- | --- | --- | --- | --- | --- |
| RL-01 | WechatBillParserJvmTest fatal 容器/结构族（:354-465；D-097 P401-FATAL 传承） | ImportSpineLifecycleEndToEndTest.injectedFailuresRollBackEverySpineOwnerAndKeepIdentityUsable（:730，INTAKE_AFTER_CANDIDATE 点） | 同左（:730，CONFIRM_AFTER_FORMAL 点） | **新增** rl01…+ ordinaryAndIncomeConfirmDomainFailures（§3.4.12）；既有类型化拒绝 :1052/:1082 | ImportSpineLifecycleEndToEndTest.concurrentIntakes/Confirms（:595/:659） | LedgerDatabaseMigrationTest.lateV24ToV25FailureRollsBackCreditStructuresAndSpineRows（:596，普通行保持）+ lateV23ToV24（:701） |
| RL-02 | AlipayCsvParserJvmTest fatal 容器/结构族（:407-509） | 同 RL-01（:730） | 同 RL-01（:730） | ImportSpineLifecycleEndToEndTest.incomeDomainFailureLeavesZeroResidueAndCorrectedRetryAccepts（:811） | 同 RL-01（:595/:659） | 同 RL-01（:596/:701；schema 级行级） |
| RL-03 | WechatBillParserJvmTest fatal 容器/结构族（:354-465；行级路由 fatal 不存在：结构错 = 容器 fatal） | :730 | ImportSpineTransferEndToEndTest.executesE31E32InjectedFailuresWithFullRollbackAndCorrectedRetries（:1316） | 同左 E17-E28（:997 域失败/门失败族） | executesE29E30ConcurrentTransferConfirmsWithSingleWinner（:1184） | executesE41LateStageMigrationFailureRollsBackCompletely（:2079） |
| RL-04 | AlipayCsvParserJvmTest fatal 容器/结构族（:407-509）；行级路由形态另列 AlipayCsvParserYuebaoTransferJvmTest（行级类型化，非 fatal） | :730 | :1316 | 同左（:997 族，绑定失配向量 B01-B13 :1598） | :1184 | :2079（同 transfer kind 族） |
| RL-05 | AlipayCsvParserJvmTest fatal 容器/结构族（:407-509，来源容器级）；信用路由行级类型化拒行另列 AlipayCsvParserCreditJvmTest（行级，非 fatal） | P406CreditFullStateOracleTest.injectedIntakeFailureAfterCandidateRollsBackAllV3RowsAndAcceptsOnRetry（:973） | …injectedConfirmFailureAfterFormalRollsBackDecisionAndAcceptsOnRetry（:1017） | …creditConfirmationNegativePathsStayPendingWithZeroWritesAndClaimRetry（:1160） | **新增** concurrentCreditAndMixedConfirms（§3.4.11） | lateV24ToV25（:596，信用结构回滚） |
| RL-06 | 同 RL-05（fatal = AlipayCsvParserJvmTest :407-509 容器级；AlipayCsvParserCreditJvmTest 为行级类型化，非 fatal） | 同 RL-05（:973） | …mixedConfirmInjectionRollsBackGroupTablesAndReplayReturnsOriginalReceipt（:1430） | …mixedConfirmationNegativePaths（:1534）+ MixedPaymentTest | **新增**（§3.4.11） | lateV24ToV25（:596） |
| RL-07 | 平台侧 = WechatBillParserJvmTest（:354-465）与 AlipayCsvParserJvmTest（:407-509）fatal 容器/结构族（来源侧）；银行侧真实维度 = 已登记延期（O-8 → D-099:1540 银行 parser 门仍开） | spine evidence 侧 = :730（evidence 随 intake 注入） | **新增** 约束驱动 confirm 失败 + 重试 + reopen（裁决 8；P408ReconciliationStoreTest :356 同构先例；触发器 = 约束而非异常，格内登记差异） | P408ReconciliationStoreTest 类型化拒绝族（:55-:273/:280-:526） | **新增** rl07 并发 confirmLink 单赢家 + 输家类型化冲突零残留（changedRequestRetry 语义 :198 的并发形态） | versionTwentyTwoToTwentyThreeDdlFailureRollsBackEverySharedP408Owner（:450） |
| RL-08 | WechatBillParserJvmTest（:354-465）与 AlipayCsvParserJvmTest（:407-509）fatal 容器/结构族；行级关闭形态归正路径锚点 rl08ClosedRows…（本行 F4 括注登记） | P407DuplicateClosedFullStateOracleTest.matrix5cReviewFailuresLeaveZeroResidueAndRetryableIdentities（:772，REVIEW_AFTER_SNAPSHOT 点） | 同左（:772）+ lifecycle p407ConfirmedDuplicateBlocksFormalFactory（:1242） | P407DuplicateClosedFullStateOracleTest.matrix7UnknownRefundAndNonSettledAmbiguityStayUnresolved（P407 :1009）+ lifecycle p407NoFunds…（:1281/:1317）；正路径关闭行覆盖 = **新增** rl08ClosedRowsAcrossKindsStayZeroFunds（§3.4.8，四 kind 关闭变体，正路径格登记） | P407DuplicateClosedFullStateOracleTest.matrix5cConcurrentReviewClaimsHaveSingleWinnerAndLoserReplay（:841）+ …matrix5dConcurrentSameRequestIntakesKeepOneDuplicateRow（:977） | lateV23ToV24FailureRollsBackSourceRebuildAndP408Rows（:701，duplicate 表属 v24 对象） |

### 6.2 新增锚点落点汇总

全部新增格落 D1 收口 oracle（§3.4 测试 3/4/7/9/11/12 内），**不**向既有模块测试追加；F6 列零新增（逐边 late 系列已闭合，D2 单链为正路径验收）。测试 8（`rl08ClosedRowsAcrossKindsStayZeroFunds`）为**正路径覆盖**（关闭行零资金影响 + 跨 kind 关闭变体 + 重复导入幂等），非失败注入格，其矩阵登记位置 = §6.1 RL-08 行 F4 格括注（正路径格登记），GOLDEN_TESTS 版同步。GOLDEN_TESTS.md 新节文本 = §6.1 表去掉裁决括注后的登记版 + 每格锚点全名 + 本规格指针 + 矩阵下方**显式延期登记行**：「语义维度延期：P4-08 correction/successor invalidation → D-103:1639，延期至后续独立批（O-5-A；属语义维度而非失败模式列，故不设列、以本行登记）」。

## 7. D5 文档同步清单（实施批编辑动作；措辞基准冻结）

| 文件:行（主 checkout 现状） | 现文 | 收口后措辞（基准） |
| --- | --- | --- |
| CURRENT_STATE.md:41（段内） | 「已合并 `main`，merge `df34388`，push 待用户明示」 | 改既成事实：merge `df34388` + `b19a6c1` 已推送 origin/main；同段追加 P4-09 收口登记（D-110 实施登记指针 + 阶段 4 收口证据面一句话） |
| CURRENT_STATE.md:45 | 「`main` 领先 origin，push 需用户明示授权。push 后，P4-09 阶段收口门等待用户开启」 | 改为：推送已完成；P4-09 收口已完成（判据声明以 ROADMAP:41 原文为唯一基准：「支持的标准来源能够从导入走到正式账目与可解释对账状态，重复导入不产生重复账目」，证据 = D1..D5）；Phase 5 进入为独立用户门（ROADMAP:47） |
| WORK_PLAN.local.md:15 | 阶段总览行「…slice 1 (D-107) merged to main (7cf9b79, v25; push pending user)」 | 改「pushed」（slice 1 推送为既成事实；同源滞后措辞一并修正） |
| WORK_PLAN.local.md:99 | 「push pending user」 | 「pushed」 |
| WORK_PLAN.local.md:102 | 「Gate open: …（P4-06 slice 2 merged df34388; push pending user）; awaits user to open the closure batch」 | 「Done: P4-09 阶段收口按 D-109 契约 + D-110 实施完成（RL-01~08 全 oracle、单链迁移、12 竖井共存、失败矩阵、全量回归；D-110 实施登记）」；行终态 = 收口完成 |
| WORK_PLAN.local.md:150 | 「DONE pending push」 | 「DONE (pushed)」 |
| WORK_PLAN.local.md:153 | 「push pending user」 | 「pushed」 |
| GOLDEN_TESTS.md:177 后 | — | 新节「P4-09 收口失败矩阵」（裁决 6） |
| DECISIONS.md D-110 | proposed | 实施登记段落补全（评审闭合、验证证据、known limitations） |

**路由登记**：CURRENT_STATE.md/GOLDEN_TESTS.md/DECISIONS.md 编辑在 worktree 内完成（tracked）；WORK_PLAN.local.md 与 PROJECT_STATE.local.md 的收口验收清单（裁决 7）在主 checkout 由主代理执行（`.local.md` 以主 checkout 为准，AGENTS.md 路由）。O-1 勾选门判据（本规格 §8）为措辞唯一基准。

## 8. 评审门与收口验收清单

1. **双评审**：独立规格评审（findings 修复闭环）→ 独立质量评审 → CLOSURE APPROVE 后实施（D-109 §8 批准链）；实施后再经 distinct verifier 实际执行聚焦与全量 → 主代理全量受影响套件 + diff 检视。
2. **O-1 必选勾选门**（D-109 §8 升格）：收口验收清单首项 = 用户侧对 `.external`/本地测试区真实账单执行受支持的收口形态（导入→确认→正式账目→对账状态→重复导入幂等），执行与结果在 PROJECT_STATE.local.md 收口验收清单留痕，**不勾选即收口不完成**；清单实例与勾选动作由主代理在主 checkout 维护（裁决 7）。
3. **零 schema 证据四条**（D-108 先例）：无新 `.sqm`、`Ledger.sq` DDL 零字节改动、版本钉 v25、迁移 verifier fresh=migrated 原样通过；`docs/migrations` 注册表无交集。

## 9. 排除项（契约边界重申 + 本批已知约束）

1. **O-2/O-6 全部边界重申**：零 schema、零生产语义、不发布 golden 工件、不自动授权 publication/Phase 5；十四项欠账逐条维持登记落点不实现不关闭（constraint_solved 产出、null explicitConfirmedAt 类型化门、store :440 清理、营销腿剥离、微信信用负证据、分期 D-049、利息/手续费/逾期费、拆单/代付、仅资产腿退款与信用借还其余形态、银行 PDF 门、共享负债账户、provider token funding 映射、整文件保留生命周期、产品 ID/Clock/组装）。
2. **O-5**：P4-08 correction/successor invalidation 维持延期；其在矩阵基线的登记形态 = GOLDEN_TESTS 新节矩阵下方显式延期登记行（§6.2 措辞），不设失败模式列（属语义维度而非失败模式）但**必须登记**（契约 O-5-A「在 D4 矩阵中显式标注延期」），指针 D-103:1639 不变。
3. **实施批已知约束登记**：fixture 全合成（中性 token、固定 +08:00 合成时间、整数最小单位金额；不落盘掩码原文/括号原文/绝对路径）；Gradle 串行 + 单 worker + 1 GB heap + 每轮停 daemon（CONTRIBUTING 本机资源限制）；D3 若某竖井回退 raw SQL 播种须逐竖井登记理由（裁决 5）；P408 store 无异常注入钩子的现状与不加生产注入参数的处置（§6.1 RL-07 F3 格）；contract 分支 `feat/p409-phase-closure`（`28d7913`）随实施批一并合并（PROJECT_STATE.local.md:9 既定策略）。
4. 本批不做 release scope（版本/tag/发布工件，O-9 范围外）。

## 10. 验证顺序（对齐契约 §5 八层矩阵的实施时序）

1. 聚焦：`.\gradlew.bat :ledger-data:jvmTest --tests "com.unifiedledger.data.P409PhaseClosureFullStateOracleTest" --tests "com.unifiedledger.data.P409SingleChainMigrationTest" --tests "com.unifiedledger.data.P409SiloSpineCoexistenceTest"`（三新类全绿）。
2. 迁移 verifier：`.\gradlew.bat :ledger-data:verifyCommonMainLedgerDatabaseMigration --stacktrace --rerun-tasks --warning-mode all`（v25 原样通过 = 零 schema 证据之一）。
3. 三 JVM 模块全量串行：`:ledger-domain:jvmTest`（预期零改动原样通过）→ `:ledger-application:jvmTest` → `:ledger-data:jvmTest`（结果以 `build/test-results` 为准，计数不在文档复制）。
4. Android 编译：`.\gradlew.bat :ledger-data:compileAndroidMain`。
5. Python 全套：`PYTHONPATH="tools/python" python -m unittest discover -s tests -t .`。
6. 文档：`PYTHONPATH="tools/python" python -m project_docs .`（exit 0；D5 全部 tracked 编辑完成后执行）。
7. Harness：`unifiedledger-harness` skill `verify-project` full。
8. push 前：`verify-project` trace（主代理 Git 写操作前；O-1 勾选门在此之前完成勾选）。
