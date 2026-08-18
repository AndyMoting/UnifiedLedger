# P4-08 镜像与过账对账 matcher 契约（冻结规格）

**Status:** approved（2026-08-19，用户批准 O-1…O-6 全部方案 A）。本文件是 P4-08 实施批的 matcher 契约；批准仅开启后续实施门，不等于已实施。批准前冻结的备选方案、权衡和推荐保留为决策审计记录；任何 matcher、evidence link、posting reconciliation 或 schema 变更仍须在独立 worktree 的后续实施批中完成（WORK_PLAN.local.md:110；D-096:1429/1435）。

**Scope:** 冻结 P4-08（镜像与过账对账，RL-07 维度）的 matcher 契约前置门：现状基线、既有权威已定约束、匿名 fixtures 集、O-1…O-6 契约条款、诊断与处置、边界断言及批准记录。本文件只冻结契约，不含实现；实施属后续 P4-08 实施批（需独立 worktree 拓扑）。

## Authority And Boundary

本规格全部条款对齐以下权威（行号为当前 main `626f55d` 的 tracked 文件行号；local-only 文档以主 checkout 为准）：

- 批定义与门：WORK_PLAN.local.md:101（P4-08 批行 = RL-07 维度：exact eligible-posting evidence match、ambiguity handling、posting reconciliation effects）；:110（**matcher gate = approve matcher fields, time window, ambiguity model, scenario cardinality and the RL-07 acceptance matrix**）；:122/123/124/125/126（RL 维度矩阵各行号：RL-03=:122、RL-04=:123、RL-05=:124、RL-06=:125、RL-07=:126；P4-08 承载其中 reconciliation 维度）；:129（P4-03/04/05 必须不创建 mirror evidence link、不产生 posting reconciliation effect；状态变更语义首现于 P4-08）；:61-65（IMPORT-002：any default matcher cardinality 被否决、matcher 语义是 P4-08 gate）。
- 决定记录：D-096:1399-1437（尤 :1425 通道总额只诊断、:1427 证据基数由场景合同决定/不建全局 1:1，:1429 matcher 字段/时间窗/歧义模型/基数须备选方案+匿名验收后批准，:1431 RG-04 镜像证据处理先例，:1433 依赖 matcher 契约的实施阻塞，:1435 未决不能由实现默认值冻结）；D-095:1380-1397（:1384-1388 Python mirror_checks = 通道总额对碰、无时间窗、暂停）；D-098:1475-1528（:1509 证据/绑定校验范围、对账前置校验留 P4-08；:1522 D-096 处置表）；D-092:1325-1340（方案 A 共享链、非 rgXX_ 前缀、竖井冻结）；D-097:1439-1473（:1457 金额精确十进制 + source scale、时间比较 source token/kind/components/offset presence）；D-085:1099-1123（RG-12 reconciliation 值对象批准：`ReconciliationMatch`/`PostingReconciliation`/`PostingReplacement`）；D-100/D-101/D-102（P4-04/05/05b 边界与类型转移登记纪律 :1539）。
- 账务规则：docs/ACCOUNTING_RULES.md:202（实际资金时间 = 对账主要时间依据）、:233（RG-12 `fully_reconciled` 语义：数值一致但剩余调整非零=balanced_with_unexplained_adjustment、剩余调整零但证据缺=evidence_incomplete）、:239-245（对账专章：Posting 级、状态枚举、部分核对、保留四要素：外部证据/证据职责/匹配依据/人工决定；资料不足可补充后重匹配、不得制造交易）、:247-253（修正只失效受影响分录原匹配并保留历史）、:80/:108/:120/:134/:152/:188（各场景证据职责与后到镜像只追加 lineage、合并到既有精确目标、不建第二笔交易）。
- 产品需求：docs/PRODUCT_REQUIREMENTS.md:21-25（对账审计工作流：按账户和时间范围查看余额/证据匹配/差异；每条实际账户变动独立对账；系统解释已核对/未核对/差异；资料不足允许待补或部分核对；不得自动伪造交易补平）、:49（本阶段边界）。
- 验收锚点：GOLDEN_TESTS.md:172（RL-03 = 同一平台内资产互转、镜像记录合并与零对外收支）、:176（**RL-07 = 银行侧与支付侧镜像证据；只形成一笔正式转账并合并证据**）；CORE_ACCEPTANCE_PLAN（.external 只读）:66（RL-07 锚点 `GL-0DCF5FCDB9BA` 行 = 找到同一资金流的平台侧证据、两端只形成一笔正式转账、第二来源作为补充证据）、:63-67（RL-04…08 对照）、:31（验收断言体系第 6 项：待对账、部分对账、完全对账、差异或待补资料的预期状态）、:91（完成标准：余额、现金流、消费、收入、预算、净资产和对账状态均有明确断言）。
- 前序规格：docs/specs/2026-08-13-p4-02-shared-import-spine-design.md:187（**import_evidence 不设 UNIQUE(source_id)：evidence 基数属 P4-08 合同，本批只保证每次 intake 恰好建一个 evidence 节点**）、:188（import_candidate 保留 UNIQUE(ledger_id,source_id)，P4-07/P4-08 多候选走加性迁移）、:7 区（import_evidence 形状、observed_at 与 source.occurred_at 字节相等）；docs/specs/2026-08-14-p4-04-transfer-formalization-slice-design.md:131-138（complete canonical state oracle、:138 九维度 report projection 不含 reconciliation 维度）；docs/specs/2026-08-18-p4-05b-rl04-yuebao-transfer-routing-design.md:202（分账户对账属 P4-08）。
- 代码现实（只读现状，证据侦察）：ledger-data ImportSpine.kt（共享导入链 source→evidence→candidate→confirmation→formal）；SqlDelightImportSpineStore.kt:289/456/617（`selectImportEvidenceForSource` —— evidence 节点当前唯一消费点 = 确认/拒绝候选的引用完整性校验与 intake 返回 ID，不承载匹配语义）；Ledger.sq:267-273（竖井 rg03_evidence_link：1:1、target_role ∈ REAL_ACCOUNT_POSTING/DESTINATION_ASSET_POSTING、status=MATCHED）、:548-554（竖井 rg04_import_evidence_match：1:1、match_kind ∈ ASSET_SOURCE/LIABILITY_MIRROR）；schema 当前 v22（21.sqm，P4-05/05b 零 schema 改动）。

术语：`本批` = P4-08 matcher 契约前置门（本文档）；`P4-08 实施批` = 本契约批准后、承载 matcher/evidence-link/posting-reconciliation 行为与（若批准）schema 的实施批次；`matcher` = 在 evidence 与精确真实账户 `Posting` 之间判定匹配并建立 evidence link 的确定性逻辑；`evidence link` = evidence 节点对既有 posting 的显式核验关联（含匹配依据）；`posting reconciliation` = 真实账户 posting 的待对账/部分匹配/有差异/待补资料/已核对状态及其变化记录；`镜像（mirror）` = 同一资金流的另一侧/另一来源证据（如 RL-07 银行侧与平台侧）；`通道总额诊断` = D-095 样式按通道汇总金额展示的差异诊断，只展示、不写 link/reconciliation。

## 1. 现状基线

### 1.1 产品 spine 当前零 matcher / 零 evidence link / 零 posting reconciliation 写入

- `import_evidence` 表当前**无** `UNIQUE(ledger_id, source_id)`（P4-02 spec:187：「本批无任何 evidence link；evidence 基数属 P4-08 合同，本批只保证每次 intake 恰好创建一个 evidence 节点，不提前冻结证据基数；后续如需多证据走加性迁移」）。每次 intake 恰好建一个 evidence 节点（observed_at 与 source.occurred_at 字节相等）。
- `import_candidate` 保留 `UNIQUE(ledger_id, source_id)`（P4-02 spec:188），P4-08 如需多候选走加性迁移。
- evidence 节点当前唯一消费点 = 确认/拒绝候选时的引用完整性校验与 intake 返回 ID：`SqlDelightImportSpineStore.kt:289/456/617` 的 `selectImportEvidenceForSource`（缺失 → `SPINE_REFERENCE_INTEGRITY_VIOLATION` 拒绝、零写入；intake 返回 evidence 引用）。**不存在任何证据→posting 的匹配、绑定或 reconciliation 写入代码。**
- report projection 九维度（P4-04 spec:138：internal transfer / external income / external expense / external cash inflow / external cash outflow / consumption / budget effect / category totals / net-worth change）**不含 reconciliation 维度**；reconciliation 状态不在 report/oracle 内且不改变余额或报表（ACCOUNTING_RULES:33）。P4-08 需定义其引入边界（O-6）。
- P4-03/04/05（含 RL-04 路由批 D-102）已明令不创建 mirror evidence link、不产生 posting reconciliation effect（WORK_PLAN.local.md:129；D-099:1540；D-100:1554；D-102:1611——P4-05b spec §7 边界重复声明）；这些状态变更语义**首现于 P4-08**，且仅在 matcher 契约批准后。当前 schema v22。

### 1.2 竖井语料与先例（只作冻结语料参考，非产品契约）

- rg03（转账）证据链先例：`rg03_evidence_link`（Ledger.sq:267-273）——evidence→posting **1:1**（`UNIQUE(ledger_id, evidence_id)`、`UNIQUE(ledger_id, posting_id)`）、`target_kind='POSTING'`、`target_role ∈ {REAL_ACCOUNT_POSTING, DESTINATION_ASSET_POSTING}`、`status='MATCHED'`。
- rg04（混合支付/镜像）先例：`rg04_import_evidence_match`（Ledger.sq:548-554）——evidence→posting **1:1**、`match_kind ∈ {ASSET_SOURCE, LIABILITY_MIRROR}`、`UNIQUE(ledger_id, evidence_id)` 与 `UNIQUE(ledger_id, posting_id)`。
- RG-04 导入 runtime 先例（D-096:1431）：镜像证据在**精确 posting 候选中**处理 target missing / mismatch / ambiguity / reconciliation precondition；成功时追加 evidence link 而**不创建第二笔正式交易**。
- 上述竖井是冻结 jvmTest 语料与行为证据，**不是产品表形状先例**：产品承载一律为非 `rgXX_` 前缀共享表（D-092:1329/1335；D-098:1493「不得复用或挂接竖井表」纪律）。

## 2. 既有权威已定约束（逐条带出处）

1. **Posting 级对账**：对账/匹配发生在真实资产或负债 `Posting` 级别；字段集必须落到精确且有资格的真实账户 posting（ACCOUNTING_RULES.md:241/243；D-096:1427）。
2. **四要素保留**：匹配必须保留外部证据、证据职责、匹配依据和人工决定（ACCOUNTING_RULES.md:245）。
3. **实际资金时间 = 主要时间锚**：实际资金时间决定账户余额变化时点，也是对账的主要时间依据；运行时 Clock 不得补写来源发生/观测/入账/起息等来源时间（ACCOUNTING_RULES.md:202-203；DECISIONS.md:1409）。
4. **不建立全局一对一基数**：「一 posting 可接受多少 evidence / 一 evidence 可支持多少目标」由场景合同决定，本提案不建立全局 1:1（D-096:1427）；import_evidence 基数属 P4-08（P4-02 spec:187）；import_candidate 的 UNIQUE(ledger_id,source_id) 保留、多候选走加性迁移（P4-02 spec:188）。
5. **业务指纹与通道总额的边界**：业务相似指纹（D-095 十键）只提重复候选、不能替代 matcher 身份也不可破坏性删除来源；通道总额对碰只作诊断，不能链接证据、改变 posting reconciliation、也不能满足 RL-07 的逐记录镜像验收（D-096:1425；D-095:1384-1388 暂停条款）。
6. **排他性冲突 = 类型化拒绝 + 零写入**；后到镜像/补充证据只追加 lineage、不重复既有 link/reconciliation effect、不建第二笔正式交易（D-096:1427；ACCOUNTING_RULES.md:80/108/134/152）。
7. **资料不足允许待补/部分核对**：可补充账单或说明后重新匹配，不得自动伪造交易补平（ACCOUNTING_RULES.md:245；PRODUCT_REQUIREMENTS.md:25）。
8. **金额与时间的精确比较**：金额 = 精确十进制值 + currency + source scale，禁止二进制浮点；时间比较 = source token、parsed temporal kind、机械解析 components 与 offset presence；缺 offset 保持 unresolved、不使用 Clock 补齐（D-097:1457；DECISIONS.md:1413）。
9. **状态变更首现于 P4-08**：P4-03/04/05（含 RL-04 路由）已明令不创建 mirror evidence link、不产生 posting reconciliation effect（WORK_PLAN.local.md:129）；reconciliation 状态与 evidence link 的写入语义只在 matcher 契约批准后于 P4-08 引入。

## 3. 匿名 fixtures 集（草案）

全部输入为合成、来源中立、provider-neutral 数据；不含真实 provider、真实个人数据、绝对路径、原文件名、真实商户、真实单号或可识别交易。真实注册金额/时间/锚点值**只在 .external 只读 fixture 内**，本规格不复制（P405FIX-QUAL-001 隐私先例）。金额用精确整数最小货币单位（合成值）、时间用合成 +08:00 offset 文本；所有 ID 为合成字符串。锚点 `GL-0DCF5FCDB9BA` 仅作为验收锚点标识引用（golden 合成 case ID，非个人数据）。

### 3.1 基础状态（RL-07 锚点匿名化结构）

- ledger：`ledger-p408`（单账本）。
- catalog：自有真实资产账户 `account-bank-a`（CNY 银行账户）与 `account-platform-w`（CNY 平台侧钱包/数币账户）——真实账户 posting 资格前提（ACCOUNTING_RULES:241/243）。
- 输入引用（opaque synthetic input ref）：`batch-p408-bank-a`（银行侧来源行，合成）、`batch-p408-platform-a`（平台侧来源行，合成）。
- 正式转账基线（合成）：一笔 ACCOUNT_TRANSFER（P4-04 kind/版本契约），posting 两条（from 账户 −amount_minor、to 账户 +amount_minor，CNY、precision 2，逐币种平衡；内部转账 report 语义 = RG-09/P4-04 spec:131-138）——RL-07 锚点「两端只形成一笔正式转账」（GOLDEN_TESTS:176；CAPL:66）。
- evidence 节点：银行侧 evidence `E-bank`、平台侧 evidence `E-platform`，各由其 intake 同事务创建（P4-02:187），observed_at 与各自 source.occurred_at 字节相等（合成值）。

### 3.2 验收预期示意（按 O-1…O-6 备选方案假设）

下表为**各裁方案假设下的验收预期示意**；批准后仅批准的方案组合生效，其余假设不视为契约。金额/时间为合成值，仅作结构示意，不绑定任何真实注册值。

| 裁决点 | 假设方案 | link / reconciliation 效果断言（示意） | 与「通道总额诊断」的区分 |
|---|---|---|---|
| O-5 逐记录验收 | A | E-bank → 银行侧入账 posting：link 1 条（证据职责 = `destination_asset_posting` 到账核验）、reconciliation 状态推进；E-platform → 平台侧出账 posting：link 1 条（证据职责 = `real_account_posting` 真实转账核验）；两条 posting 由同一笔正式转账承载（transaction 1、posting 2），每侧 evidence 各自单一职责、不承担复合职责（ACCOUNTING_RULES:233）；后到补充证据追加 lineage 不重复 link/effect | 通道总额诊断只展示、不创建 link/reconciliation（D-096:1425 逐记录验收独占） |
| O-4 RL-07 基数 | A（各 1:1 + 转帐多对一） | evidence:posting = 1:1（每来源一证据 → 其实际证明的精确 posting）；evidence:transaction = 多对一（两证据共享一笔正式转账，不建第二笔） | 两证据各自独立证据职责、独立对账状态；不合并为单一链路计数 |
| O-3 歧义 | A（defer 人工） | 同窗多命中 → evidence 与竞争者 posting 均保持上界「待补资料/有差异」、零 link；用户决定后才写 link | 歧义候选项在诊断中分级展示（non-authoritative），不构成 link |
| O-1 字段集 | A（资金事实核心门） | 匹配身份 = amount+currency+direction+account+时间窗；order/counterparty/category 不参与身份（仅候选提示） | 候选提示带 provenance/confidence 标签，与身份断言分离 |
| O-2 时间窗 | A（有界自然日窗） | 窗内唯一命中 → link；无命中 → 待补资料/有差异；命中判定以 posting 实际资金时间为锚 ±N 自然日（默认 ±2、N 待批准）；跨来源结算偏移属来源级事实（待批准） | 时间不进入通道总额诊断；诊断只展示、不参与命中判定 |
| O-6 引入边界 | A（P4-08 引入最小新表） | 加性迁移 v22→v23（非 rgXX_ 前缀 evidence_link/posting_reconciliation 形状）；report projection 增加 reconciliation 维度并纳入 canonical oracle | 新表只被产品路径写；竖井冻结语料互不引用（D-092:1335） |

## 4. matcher 契约条款（草案）

以下 O-1…O-6 为裁决点。每项以「条款（问题定义）→ 备选方案 A/B/C（标号 + 权衡）→ 推荐」呈现；这些备选方案保留为批准前的决策审计记录，已批准组合见 §7（D-096:1429/1435）。相关子默认值（如窗口 N、必选字段）必须随批准一并给出并登记，禁止由实现默认值冻结。

### O-1 实际比对字段集

**条款：** matcher 对 evidence → posting 建立链接时，用哪些字段参与比对；必选/可选/参与优先级；是否复用 D-095 十键的某个子集。字段池（来源侧 vs posting 侧）：

| 字段 | 性质 | 证据职责锚点 | 参与性（默认候选） |
|---|---|---|---|
| amount（精确十进制 + scale） | 证据金额 vs posting 金额 | destination/real-account 核验（ACCOUNTING_RULES:80/108/120/152/188） | 必选 |
| currency | 证据币种 vs posting 币种 | 同资金核验 | 必选 |
| direction（normalized 方向） | 证据方向 vs posting 方向 | 账户侧向核验 | 必选（可与 account 合并表达） |
| account（真实账户 posting） | 证据目标账户 vs posting 账户 | 精确真实账户 posting（D-096:1427） | 必选（资格前提） |
| occurred_at（实际资金时间） | 证据时间 vs posting 时间 | 对账主要时间锚（ACCOUNTING_RULES:202） | 参与时间窗（O-2），非精确等值 |
| status（结算状态） | 证据 status_token vs 提交/实现状态 | 观测一致性 | 可选（仅诊断/置信度） |
| order_id / counterparty / category / item | — | 业务相似指纹候选（D-096:1425；D-095:1384 暂停） | 不参与身份；仅候选生成提示与诊断 |

**备选：**
- **A. 资金事实核心门**：必选 amount+currency+direction+account；occurred_at 走时间窗；status 可选诊断；order/counterparty/category/item 零参与身份、仅候选提示与诊断。权衡：字段集最小、稳定、不触碰易变业务映射；代价 = 同账户同金额多事件靠时间窗与唯一性约束（歧义负担转 O-3）。
- **B. A + 辅助键**：在资金门通过后，order_id/counterparty 作二级判别（歧义场次排序），不单独构成身份。权衡：提升区分度、缓解歧义；代价 = 引入易变映射与归一化依赖，须维护 provenance/confidence（D-094 保留方向），且需明确与 D-095 指纹纪律的划界。
- **C. 复用 D-095 十键非易变子集**（account/time/amount/direction）作为 matcher 键、其余仅诊断。权衡：字段集与 A 基本重合，但语义来源不同，最容易与 D-095 暂停条款混淆（指纹≠身份），需显式登记划界声明。

**推荐：** A。最小字段集锚定资金事实 + 时间窗，与「外部证据只核验其实际证明的分录」职责边界对齐；order/counterparty/category 属易变业务映射、不能成为权威匹配身份（D-096:1425），只作候选提示。若要缓解歧义可后续独立评估 B 的辅助键（需批准其 provenance/confidence 规则）。子裁决点：`status` 是否保留为可选诊断（推荐保留）；`direction` 是否与 `account` 合并为必选断言。

### O-2 时间窗

**条款：** evidence 与 posting 的时间比对，有界窗 vs 无界；窗口单位/容差/跨来源对齐规则。**无既有批准窗口**（D-095 Python mirror_checks 基线 = 无时间窗且暂停，D-095:1384-1388）；实际资金时间为对账主要依据（ACCOUNTING_RULES:202）；时间比较按 source token/kind/components/offset presence、缺 offset 保持 unresolved、不补 Clock（D-097:1457；DECISIONS:1409/1413）。

**备选：**
- **A. 有界自然日窗（账户/来源级配置）**：以 posting 实际资金时间为锚 ±N 自然日；单位自然日（避免本地/UTC 歧义）；窗内逐记录判定——唯一命中 → link；无命中 → 待补资料/有差异（不自动建 link）；多命中 → O-3 defer。跨来源对齐留子方案 C。权衡：落实「实际资金时间是主锚」的产品语义、可机器判定；代价 = 需批准窗口参数与跨来源对齐。
- **B. 无时间窗**：时间不参与匹配身份、仅展示；匹配靠其余资金事实 + 账户唯一性。权衡：实现最简、零对齐成本；但同账户同额时段重复事件歧义率显著（转嫁 O-3），且弱化「实际资金时间 = 主要时间锚」的产品约束。
- **C. A + 显式跨来源结算延迟偏移**：来源级注册参数（如银行侧到账日 = 支付侧发生日 + offset_days）；offset 属来源声明/证据支持的事实，不得由 Clock 或实现默认推断补写。权衡：承载银行↔支付侧时间差（RL-07 场景）；代价 = 新增来源级事实维度，需用户确认或来源证据门。

**推荐：** A。默认 N 建议 ±2 自然日（随批准登记，作为**可批准默认**而非实现默认）；若 RL-07 真实验收观察到跨来源时间差，再启用 C（offset 登记为来源事实、需用户批准）。B 因歧义风险与产品语义双重理由不推荐。子裁决点：窗口单位（自然日 vs 小时 vs UTC）、N 值、是否账户/来源级可配置、是否冻结来源级偏移参数。

### O-3 歧义处置

**条款：** 同窗内一 evidence 命中多个 posting（或一 posting 被多 evidence 竞争）如何判定 ambiguous；人工确认边界；defer vs reject；与「待核对/部分核对」（ACCOUNTING_RULES:241；REQUIREMENTS:25）的关系。既有硬约束：排他性冲突请求类型化拒绝且零写入（D-096:1427）；后到镜像只追加 lineage、不重复 link/effect（D-096:1427）；资料不足允许待补/补充后重匹配、不得自动补平（REQUIREMENTS:25；ACCOUNTING_RULES:245）。

**备选：**
- **A. 默认 defer 人工**：同窗多命中/唯一性不足 → evidence 与竞争 posting 保持上界「待补资料/有差异」状态、零 link、零 reconciliation effect；系统展示候选（evidence 职责 + 对比字段 + 置信度）；用户明确选择其一、声明无匹配或补充资料后才写 link。权衡：与人审确认语义一致（ACCOUNTING_RULES:245 人工决定要素）、不制造交易；代价 = 需要人工面。
- **B. 确定性规则 + fail-closed reject**：规则（如时间最近优先）若能唯一判定 → 自动 link；不能判定 → 类型化拒绝整次匹配、零写入，用户重提交。权衡：减少人工；但自动 link 需用户预先授权的确定性规则（ACCOUNTING_RULES:32），且「拒绝后重提交」丢失候选上下文、与待补资料语义较弱。
- **C. 阈值评分 + 显式授权自动采用**：定义可审计评分（命中字段数/置信度），默认 defer；用户对特定账户/来源显式授权「唯一最高分超过阈值时自动 link」。权衡：平衡自动化与人工；但阈值 = 新决策面，需独立批准与规则/provenance 登记，且需防「自动 link」被误读为「自动补平」（link 不建交易，语义需再澄清）。

**推荐：** A 冻结为首版；C 的自动采用规则列为未来独立门（首版不批准）；B 仅用于 D-096:1427 已定的「排他性冲突请求 → 类型化拒绝零写入」边界。子裁决点：ambiguous 上界状态命名（待补资料/有差异）、多命中候选展示字段、「声明无匹配」是否为终态（如证据对应错误/已销户账户）。

### O-4 场景基数

**条款：** 一 posting 可接受多少 evidence / 一 evidence 可支持多少目标 posting，由**场景合同**决定、不建全局 1:1（D-096:1427）；import_evidence 基数属 P4-08（P4-02:187）、import_candidate 多候选走加性迁移（P4-02:188）。逐场景裁定：
- **RL-07 镜像**（GOLDEN_TESTS:176；CAPL:66）：银行侧 + 平台侧两来源、两 evidence，是否都链接到同一笔正式转账的各自 posting（evidence→transaction 多对一、evidence→posting 各 1:1），第二来源作为补充证据。
- **RL-03/RL-04 转账**（同源/单来源行）：evidence 链接其实际证明的那一 posting（钱包腿）；另一腿（如余额宝腿）无直接来源证据（P4-05b:152「两腿确认=组合账户展示」）时对账如何表达（见子裁决点）。RG-04 先例 = 在精确 posting 候选中处理 target missing/mismatch/ambiguity/precondition（D-096:1431）。
- **拆单/混合支付**（P4-06 域关联；一 evidence 多 posting 的 1:N）。

**备选：**
- **A. 场景显式登记（治理立场）**：不设全局默认；每批/每场景合同冻结 evidence:posting 基数表；import_evidence 保持无 UNIQUE、多证据/多候选按需加性迁移（P4-02:188）。建议值：RL-07 = evidence:posting 1:1（每来源一 evidence → 其实际证明的精确 posting）、evidence:transaction 多对一（一正式转账、不建第二笔）；RL-03/04 同源 = evidence:posting 1:1（另一腿经转账账户关系表达，不伪造 evidence）；拆单/混合支付 = evidence:posting 1:N 由场景合同显式登记。
- **B. 全局统一 1:1 默认**（evidence→posting 一对一、例外由合同放宽）：与 D-096:1427「不建立全局一对一基数」正面冲突；**只有用户明确推翻该句**才可选。
- **C. 全局允许 1:N 默认**：与「外部证据只核验其实际证明的分录」（ACCOUNTING_RULES:108/120/152/188）职责边界冲突，风险高。

**推荐：** A。B/C 不推荐。子裁决点：RL-07 精确表述是「两证据各 1:1 到两 posting」还是「两证据合并为一条链路」——推荐前者（各自证据职责独立、独立对账状态）；RL-04 余额宝腿无直接证据时对账状态表达（待补资料 vs 仅转账关系核验）需用户裁决。

### O-5 RL-07 验收矩阵与匿名 fixture

**条款：** 锚点 `GL-0DCF5FCDB9BA`（银行流水与平台侧镜像证据，内部资金流转）的**匿名化验收清单**；逐记录镜像验收的**可判定条件**（金额/时间/账户匿名化边界）；与「通道总额诊断」（D-096:1425）的**区分断言**。真实注册金额/时间只在 .external 只读 fixture（P405FIX-QUAL-001 先例）。

**备选（验收断言强度）：**
- **A. 逐记录验收（recommended）**：每条 evidence 在冻结时间窗（O-2）与字段集（O-1）对冻结 posting 集合做判定；断言 = link 创建（含四要素：外部证据/证据职责/匹配依据/人工决定）、reconciliation 状态变化、**零第二笔正式交易**、后到镜像证据只追加 lineage 不重复 link/effect、通道总额诊断只展示不参与判定。这是 D-096:1425 唯一满足 RL-07 的验收形式。
- **B. 总额对碰 + 逐记录核心**：通道总额相等作为非正式佐证（D-095 先例：仅诊断展示）；通过标准仍逐记录（与 A 同核心，B 明示总额不作通过条件）。权衡：保留 D-095 展示能力，无额外风险；等价于 A + 诊断展示声明。
- **C. 只断言最终状态**（对账状态 + 余额、不逐记录）：违反 D-096:1425「通道总额只诊断、不能满足 RL-07 逐记录镜像验收」，否决。

**推荐：** A（或明确 B 的「总额 = 仅诊断、不构成验收」；C 否决）。匿名化边界：金额为精确整数合成值；时间为合成 +08:00 文本；账户/来源/证据用合成 token；order_id/counterparty/category 不出现在任何验收断言；验收矩阵行 = {evidence、目标 posting、判定依据（字段/窗口）、link 预期、reconciliation 状态预期、零第二笔交易断言}。

### O-6 reconciliation 状态引入边界

**条款：** report/oracle 是否纳入 reconciliation 维度（P4-08 还是留 P4-09）；spine 是否需要新表/加性迁移（import_evidence 基数、evidence_link、posting_reconciliation——参照 rg03/rg04 竖井先例形状但**不得复用竖井表**，D-092:1329/1335；D-098:1493）。现状：report 九维度无 reconciliation（P4-04 spec:138）；D-098:1509「对账前置校验步骤留 P4-08 不实现」；P4-03/04/05 零 evidence-link/reconciliation（WORK_PLAN:129）。

**备选：**
- **A. P4-08 引入最小新表 + report 维度**：加性迁移 v22→v23（非 `rgXX_` 前缀，如 `evidence_link` / `posting_reconciliation`，形状参照 rg03/rg04 先例语义但独立承载）；**产品 `posting_reconciliation` 状态枚举取自 ACCOUNTING_RULES.md:241（待对账/部分匹配/有差异/待补资料/已核对），与 rg12 竖井的 correct/replacement 语义（`PostingReplacement` 三值 `reconciliation_effect`、`PostingReconciliation` 值对象）刻意分离、不复用其枚举**；report projection 增加 reconciliation 维度并纳入 complete canonical state oracle（P4-04 spec:131-138 模式）。权衡：验收可机器断言（canonical state 一致性）、与每批含架构演进节奏一致（P4-02 v21 → P4-04 v22）；代价 = 新增 schema/report 面。
- **B. 契约先冻结、持久化延后 P4-09**：matcher 契约 + 验收断言逻辑层冻结；schema/report/oracle 统一在 P4-09 闭合。权衡：缩小本批面；但验收的可机器断言缺持久化载体（receipt/link/reconciliation 行），complete canonical state 模式难以成立，且 P4-09 承担面过大。
- **C. P4-08 只冻结契约（不含任何 schema）**：在 B 基础上更激进地把 schema 推迟。权衡：本批纯文档；但验收断言落空、与阶段 3 衔接纪律（每批含 schema 演进）脱节。

**推荐：** A（P4-08 引入最小加性 schema + report reconciliation 维度；新表命名与列随实施批冻结，需用户随本 O 一并批准）。B/C 的持久化延后不推荐。子裁决点：新表形状采用 rg03 evidence_link 的 target_role 语义还是 rg04 match_kind 语义；是否在 P4-08 同时引入 report projection reconciliation 维度（推荐：是）。

## 5. 诊断与处置

- 复用为先：诊断严格遵守 D-097:1459 稳定 taxonomy（code/severity/scope/安全 location；message 不稳定不比较）与 P4-03/P4-04/P4-05 冻结码（`REQUIRED_FACT_UNRESOLVED`、`CONFLICTING_SOURCE_FACTS`、`SPINE_STALE_FINGERPRINT`、`SPINE_REQUEST_IDENTITY_CONFLICT`、`SPINE_DOMAIN_VALIDATION_FAILED`、`SPINE_REFERENCE_INTEGRITY_VIOLATION` 等）。**零新增 severity/scope；不注册 provider 前缀同义码**（P4-03 §5/P4-05 §4 纪律）。
- matcher 层新事件（如 evidence 目标 posting missing / mismatch / ambiguous / repeated-link / duplicate effect）若实施批需要新诊断码，必须在实施批规格中按 D-099:1539 类型转移登记纪律给出**登记理由**（code/severity/scope/安全 location），并经本契约批准后随实施批注册；本契约不预设具体编码（D-096:1435 禁止由实现默认值冻结）。
- 通道总额诊断（D-095 mirror_checks 样式）只作诊断展示，不链接证据、不改变 posting reconciliation、不压制候选或交易，也不构成 RL-07 验收（D-096:1425；D-095:1384）。
- 候选提示诊断（order_id/counterparty/category 等易变字段）与 matcher 身份断言分离：前者携带 provenance/confidence 标签并显式标注为非权威候选（D-094 保留方向；D-097:1449 未知 token unresolved）。

## 6. 边界断言（契约批不含实施）

- 本文档是 matcher 契约**已批准（approved）**：本契约批本身不实施 matcher、不建立 evidence_link/posting_reconciliation 表、不写 link/reconciliation effect；这些行为只能在后续 P4-08 实施批中按本契约落地。
- 不复制真实金额/时间/锚点注册值；不写个人数据、真实单号、账户锚点、对方、别名、绝对路径或 agent/trace；合成 fixture 值全部匿名化（P405FIX-QUAL-001 先例）。
- 不碰 `.external/`（只读）；不动 12 套 rgXX 竖井（冻结语料）；产品承载一律为非 `rgXX_` 前缀共享表（D-092:1329/1335；D-098:1493）。
- 不建立全局 1:1 默认基数（D-096:1427）；不复活 D-095 暂停条款（业务指纹作身份 / 通道总额作镜像完成 / 先到先得折叠）。
- 不得自动伪造交易补平差额（PRODUCT_REQUIREMENTS:25）；reconciliation 状态与证据匹配不参与余额与 report 计算（ACCOUNTING_RULES:33）。
- P4-03/04/05（含 RL-04 路由）边界不变（零 evidence-link/reconciliation，WORK_PLAN:129）；P4-07 duplicate、P4-06 credit/mixed、产品 ID/Clock 均不在本批。
- 本文件由 P4-08 实施批承接：实施仅允许在契约批准后、独立 worktree 拓扑、单一 writer 纪律下进行；本批 writer（文档批）不实施、不 commit。

## 7. 已批准裁决

用户于 2026-08-19 批准以下 O-1…O-6 组合；本组合是 P4-08 实施批的唯一产品契约基线：

1. **O-1 A：资金事实核心门。** 必选 `amount`（精确十进制及 source scale）、`currency`、`direction`、真实 `account`；`occurred_at` 参加时间窗；`status` 仅作可选诊断/置信度；`order_id`、`counterparty`、`category`、`item` 不参与身份，仅作带 provenance/confidence 的候选提示。`direction` 与 `account` 为两个独立必选断言。
2. **O-2 A：有界自然日窗。** 以 posting 实际资金时间为锚，窗口为 **±2 个自然日**；自然日是唯一单位，窗口按账户/来源配置能力实现，但首版默认值固定为 ±2；来源级结算偏移不在本批准组合中启用，未来需单独登记来源事实并重新批准。
3. **O-3 A：歧义默认转人工。** 同窗多命中或唯一性不足时，证据与竞争 posting 保持 `待补资料`/`有差异` 上界状态，零 link、零 reconciliation effect；系统仅展示候选，用户明确选择、声明无匹配或补充资料后才写 link。
4. **O-4 A：场景显式登记基数。** 不建立全局默认基数。RL-07 为每个来源 evidence 到其实际证明 posting 各 1:1，evidence 到正式 transaction 多对一；RL-03/RL-04 同源为 evidence:posting 1:1；拆单/混合支付的 1:N 只能由对应场景合同显式登记。`import_evidence` 保持无全局 UNIQUE，既有 `import_candidate(ledger_id, source_id)` 唯一约束保留，多候选/多证据采用加性迁移。
5. **O-5 A：RL-07 逐记录验收。** 每条 evidence 必须按 O-1/O-2 对冻结 posting 集合逐条判定，并断言四要素、reconciliation 状态变化、零第二笔正式交易及后到镜像仅追加 lineage；通道总额只作诊断展示，不构成验收条件。
6. **O-6 A：P4-08 引入最小加性 reconciliation 面。** 后续实施批执行 v22→v23 加性迁移，新增共享（非 `rgXX_`）evidence-link/posting-reconciliation 产品表，并把 reconciliation 纳入 report projection 与 complete canonical state oracle。产品状态枚举固定为 `待对账`、`部分匹配`、`有差异`、`待补资料`、`已核对`；具体列形状、索引、诊断码和迁移 SQL 必须在实施批规格中登记并接受独立评审，不得由实现默认推断。

本批准不授权 P4-08 实施代码；实施仍需独立 worktree、单一 writer、独立规格/质量评审、distinct verifier，以及主代理最终检查和完整受影响套件验证。

**实施批承接项：** 具体 evidence-link/posting-reconciliation 列形状、索引、迁移 SQL、matcher 诊断码及匿名 fixture 文件仍须在后续实施批规格中登记、独立评审和验证；它们不得由实现默认推断，也不改变本节已批准的 O-1…O-6 语义。
