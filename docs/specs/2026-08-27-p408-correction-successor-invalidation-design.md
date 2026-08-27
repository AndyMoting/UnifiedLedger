# P4-08 correction / successor invalidation 实施规格（设计门提案批次）

**Status:** approved（2026-08-27 用户按推荐批准 UQ-1..UQ-8 全部裁决项并同步授权实施批；决定登记 D-113）。冻结文本 = SHA-256 5645e1c8f4ddd05d9d2f530e716f253832caa0f433a0b7dc3c32fc9201a4f70e（评审终局 APPROVE 版，P408CORR-SPEC-001..010 全闭合）。

**Revision:** draft-1（2026-08-27）。承接登记链：D-103（DECISIONS.md:1639，correction/successor invalidation 明确延期）、D-109 O-5（DECISIONS.md:1738，维持延期并在失败矩阵登记为已知延期维度，GOLDEN_TESTS.md:194）、D-112 UQ-2 = V-5-A（DECISIONS.md:1788，「换目标账户重新表达须待未来 correction 批」）与实施边界（DECISIONS.md:1796，「本批不产生 link 失效/后继机制写入者，`invalidate_link` 继续仅为枚举预留」）。

**Scope:** 冻结「correction / successor invalidation」实施批的契约面：修正请求形状与幂等、追加式 invalidation 事件、successor link 单活性、rematch 后 reconciliation 状态转移、evidence projection 换目标重新表达（V-1-A 终态模型的承接落点）、v26→v27 加性迁移与回填确定性、失败码族、测试矩阵与显式非目标。金额全程整数 minor units / 精确十进制，禁浮点；示例全部匿名合成值；引用均带 file:line；不粘大段产品代码。本文档只冻结设计；实施、Git 写操作与最终验收属后续独立 worktree 实施批。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 文件行号为 main 基线 `a2a2c4d`；`.local.md` 与 `.external/` 以主 checkout 为准、只读；外部研究不入 tracked 文件）：

- **承接契约原文**：`docs/specs/2026-08-19-p4-08-persistence-implementation-design.md:61-67`（evidence_link_history 追加式、「Correction/successor operations remain a follow-up contract」）、`:133-136`（**延期契约核心**：「The deferred correction operation must never mutate or delete the old link/history. It will append an invalidation event, create a successor link, and append the resulting posting reconciliation state. Financial balances, transaction versions, and report financial dimensions remain unchanged.」）、`:161-165`（批次纪律：本批只实现 R1/R2/R5/R6/R7/R8，correction successor 明示不属于既有测试，任何变化重开评审门）。
- **延期登记链**：D-103 实施登记 DECISIONS.md:1639（「correction/successor invalidation 按实施规格明确延期，P4-08 不因此视为全量闭环」）；D-109 O-5 DECISIONS.md:1738 与失败矩阵 GOLDEN_TESTS.md:194（语义维度延期、不设失败模式列、以行登记）；D-112 决定 DECISIONS.md:1788（UQ-2 = V-5-A：REJECTED 终态 +「换目标重新表达须待未来 correction 批」）与边界 DECISIONS.md:1796（含再申明：「本批不产生 link 失效/后继机制写入者，`invalidate_link` 继续仅为枚举预留」——即 schema 预留在 v23，代码零写入维持至本批）。
- **会计规则行为锚点**：`docs/ACCOUNTING_RULES.md:239-245` 对账专章（Posting 级状态、已核对不被他人证据失效、:245「资料不足时允许用户补充账单或说明后重新匹配，不得制造交易以得到表面闭合」）；`:247-253` 修正/退款/重新核验专章（:249 录入错误版本替代、:251 退款不提前冲减、**:253 本批行为锚点**：「修改金额、真实账户或币种时，只失效受影响分录的原匹配并保留历史，再把新版本送回匹配流程。只修改备注、图片、标签或用户分类时，不重置未变化资金分录的对账结果。修改统计时间后仍使用保留的来源凭证时间进行证据核验。」）。
- **既有 approved 规格**：`docs/specs/2026-08-26-p408-evidence-projection-implementation-design.md`（V-1-A 每 evidence 单行终态投影、V-2..V-8、迁移 25.sqm、TP-01..18；本批的章法与迁移模板范本）；`docs/specs/2026-08-19-p4-08-persistence-implementation-design.md` §5 单事务确认叙述（:113-131）与 §6 报告/canonical oracle（:140-149）。
- **失败矩阵登记形态**：`docs/GOLDEN_TESTS.md:179-196`（P4-09 收口失败矩阵；:194 即本批延期登记所在行；:198 后续批次引用登记基线）。
- **不可触碰面**：D-099:1540 银行 parser 门仍开（D-109 O-8）；Phase 5 不开启；correction/successor 属已登记延期语义维度、不属于收口批（D-109 O-5，DECISIONS.md:1738；GOLDEN_TESTS.md:194）。

### 外部证据门摘记（neutral rationale，原始研究不入 tracked 文件）

依据 AGENTS.md 外部参考门读取 `docs/SOURCE_REFERENCES.md`（local-only junction 注册表）、`.external/requirements/DISCOVERY_DECISION_LOG.md` 与 `.external/requirements/golden-ledger/CORE_ACCEPTANCE_PLAN.md`，与本批相关的中立约束如下（非 raw 笔记）：

1. 允许部分对上；对不上时用户可补充信息或提供其他账单后重新对账（DISCOVERY_DECISION_LOG.md:150-154 D-015）→ 修正请求必须在「资料不足」分支显式落到待补资料，系统不自动补平。
2. 原始证据与用户确认冲突时两者都保留、由用户决定（DISCOVERY_DECISION_LOG.md:156-160 D-016）→ invalidation 事件与旧 link/history 永不删除，新表达以新行追加。
3. 录入错误修正采用版本替代，旧版本保留在审计历史但不再产生有效账户变动；真实退款/撤销/反向资金变化才按实际发生时间关联冲回（DISCOVERY_DECISION_LOG.md:383-389 D-047）→ 本批失效/替换是审计口径行为，不是经济事件，余额与正式交易零变化是硬不变式。
4. 修改金额/真实金融账户/币种等改变流水匹配事实的字段时仅失效受影响分录的原匹配并保留历史、新版本送回匹配流程；未受影响分录保留原状态；找到新匹配恢复已对账、只能找到旧金额显式有差异、资料不足显式待补资料、不得自动补平（DISCOVERY_DECISION_LOG.md:391-397 D-048，与 ACCOUNTING_RULES.md:253 同源）→ 本批状态结果面（CHECKED / DIFFERENCE / MISSING）与「其余分录不动」语义直接来自此条。
5. 验收审计面要求「修改、替代、退款、冲回和失效匹配」的审计历史有明确断言、并含「待对账/部分对账/完全对账/差异/待补资料」预期状态（CORE_ACCEPTANCE_PLAN.md:31-32；RG-08 修正行 :48「修改金额或账户时生成新版本、旧匹配失效并重新匹配；只改分类或备注不破坏资金证据；原始支付时间保持不变」；:92 全部断言面）→ 测试矩阵必须含 invalidation 审计点与状态 oracle，改分类/备注不重置资金分录的负路径要显式断言。

## 1. 现状基线（代码现实，写契约前逐条核对）

1. **失效面 schema 已预留、代码零写入**：`reconciliation_request.operation` CHECK 含预留 `'invalidate_link'`（22.sqm:7，fresh 面 Ledger.sq:7723）；`evidence_link_history.state ∈ {active, invalidated}` / `reason ∈ {confirmed, posting_replaced, corrected}`（22.sqm:60-61，fresh 面 Ledger.sq:7801-7802）；追加式守卫与转移守卫（22.sqm:100-112，fresh 面 Ledger.sq:7891-7904）。转移守卫只允许 `active → invalidated` 恰好一次且此后封闭（latest='invalidated' 再插入即 ABORT），sequence 守卫要求连续递增且 sequence 1 必为 `active`。**结论：本批激活 link 失效的 SQL 面零 schema 文本变化**。
2. **evidence_link 现状**：`evidence_link` 无全局 evidence UNIQUE（22.sqm:30-41，fresh 面 Ledger.sq:7776-7795）——「一 evidence 一 active link」由 store 的 `selectP408ActiveLinksForEvidence`（Ledger.sq:8285-8295，latest-history active JOIN）+ `P408_EVIDENCE_ALREADY_LINKED`（SqlDelightP408ReconciliationStore.kt:50-55）承担；另一活性面 = 每 posting+responsibility 至多一 active link（`selectP408ActiveLinksForPostingResponsibility`，Ledger.sq:8297-8306；store:176-184）；update/delete guard 触发器禁改禁删（22.sqm:93-94，fresh 面 Ledger.sq:7884-7885）。产品写入路径唯一 = `confirmLink`（SqlDelightP408ReconciliationStore.kt:33-311）。
3. **posting_reconciliation 状态推进**：唯一写入路径 = `updateP408PostingReconciliation`（Ledger.sq:8195-8197），由 store 在 :264-279 以 `CHECKED` 推进（append same-sequence history 后 update）；`posting_reconciliation_update_guard` 要求 `new.latest_sequence = old.latest_sequence + 1` 且必须存在同 sequence 同 status 的 history 行（Ledger.sq:7919-7926；22.sqm:127-132）；history 的 link 一致性 guard（Ledger.sq:7909-7918）。`MISSING`/`DIFFERENCE` 仅枚举（P408Reconciliation.kt:13-14 与 label 待补资料/有差异），生产无写入者——D-103 O-3「待补资料/有差异上界」与「matcher 零自动转移」维持（DECISIONS.md:1634）。**结论：本批若激活 MISSING/DIFFERENCE，写入者只能来自 correction 操作自身，且必须满足既有 update guard（sequence+history 配对）。**
4. **projection 终态面（v26）**：`evidence_projection` UNIQUE(ledger_id, evidence_id)、READY/REJECTED 终态、UPDATE/DELETE 无条件 ABORT（Ledger.sq:7843-7870；25.sqm:36-63）；store 写入路径 = `materialize`（独立事务）/`ensureReadyWithinTransaction`/`insertIfAbsent`（spine confirmLink 同事务，SqlDelightEvidenceProjectionStore.kt:46-77/:296-318）；`economicEquals` 排除审计迹（materializationRequestId/materializedAt），sourceHash 归 DRIFT 门（:113-129）；现行 confirmLink 消费面 = request ↔ READY projection ↔ posting 三边全等 + raw 回声（store:101-145），目标绑定是认证字段（P408MaterializationRequest，P408EvidenceProjectionPort.kt:51-59，SPEC-001 已消除 TARGET_ACCOUNT_MISSING）；projection_id 派生确定性 `proj-<evidenceId>`（:287）。
5. **matcher/eligibility**：`sameFundingFacts` 五字段全等（amount 带方向取号、currency、precision、direction、account；P408Matcher.kt:178-186）；回填谓词复用 `selectP408PostingIntegrity`（Ledger.sq:8271-8283）的 current-version + `ACCOUNT_TRANSFER` 资格。本批不改 eligibility 语义本体。
6. **版本替代的 posting 身份**：架构上每次修正生成新 transaction version + 新 posting_set + 新 posting_id（Ledger.sq:15-50 `transaction_version`/`posting` 定义；PostingReplacement.kt:20-22 `fromPostingId ≠ toPostingId`）。竖井侧已冻结的「posting replacement」审计链接与 reconciliation effect（preserved/invalidated/not_applicable）是 D-085 RG-12 竖井概念（domain ReconciliationMatch.kt/PostingReconciliation.kt 头注），只作行为先例，本批不回溯 RG 竖井（D-092 不退役）。
7. **迁移模板**：`25.sqm` 通读核实（v25→v26）：pre-guard 表（:22-32）、row guard（:67）、stage-copy 保序重建无 RENAME（:70-177）、确定性回填 + 行数守卫（:185-260）、late sentinel（:266）、占位清理（:273-275）；迁移链版本断言 LedgerDatabaseMigrationTest.kt:299 `assertEquals(26, LedgerDatabase.Schema.version)`。
8. **现状推论**：任何 correction 写入者都不存在；`invalidate_link` 在 request CHECK 中可用但无 request/snapshot 形状；link 活性由查询谓词而非表级约束承载；projection UNIQUE 阻断同 evidence 第二行——这些既是本批自由度边界，也是必须裁决的缺口（见 §8 V-E 与 §11 迁移）。

## 2. 既有权威已定约束（逐条带出处）

1. **旧行不可变**：correction 永不 mutate/delete 旧 link/history（persistence spec :133-136）；既有 guard 触发器全部保持（AGENTS.md 边界 + §1.3/§1.4）。
2. **不变式三零**：financial balances、transaction versions、report financial dimensions 不变（persistence spec :135-136 原文）；oracle 必须断言。
3. **单事务原子追加**：失效事件 + successor link + resulting posting reconciliation state 一次事务写入（persistence spec :134；本批 UQ-2 承接 D-112「换目标重新表达须待 correction 批」在同一事务内给出落点，见 §8）。
4. **只失效受影响分录**：改金额/账户/币种仅失效受影响分录原匹配并保留历史、其余分录原状态不动；改备注/图片/标签/分类不重置资金分录；统计时间改不破坏来源凭证时间核验（ACCOUNTING_RULES.md:253；DISCOVERY_DECISION_LOG.md:391-397）。
5. **不自动补平 / 上界保持**：资料不足补充后重新匹配、不得制造交易（ACCOUNTING_RULES.md:245 DISCOVERY_DECISION_LOG.md:150-154）；matcher 永不自动转移状态（D-103 O-3，DECISIONS.md:1634；persistence spec :88）。
6. **correction 延期不破**：D-103:1639 / D-109 O-5 / D-112 实施边界（DECISIONS.md:1796）——本批是「激活预留面」的批，不是既有批的回溯修订。
7. **迁移加性纪律**：新表非 `rgXX_` 前缀；既有行保持；fresh=migrated 等价可证；单事务可回滚（D-092/D-098/D-103 先例；25.sqm pre-guard/stage 模板）。
8. **失败码与幂等纪律**：typed failure / unique conflict / FK failure 全量回滚；等价 replay 返回原 receipt 零追加；changed retry 类型化冲突零写入（persistence spec :127-131）。
9. **金额纪律**：整数 minor units / 精确十进制，禁浮点（AGENTS.md；D-111/WORK_PLAN:170；投影规格 §Scope）。

## 3. 批次范围边界

本批只做以下事：(a) 激活 `reconciliation_request.operation='invalidate_link'` 与 `evidence_link_history` invalidation 枚举的既有预留面，作为 correction 操作的写入者；(b) 冻结修正请求形状（前驱 link + 证据 + reason + 受影响 posting + 结果态 + 可选 successor 事实）与指纹/幂等/冲突语义；(c) 追加式 invalidation 事件 + successor link 在同一事务内，「同一 evidence 至多一 active link」不变量贯穿失效-替换交替期；(d) 通过 correction 操作激活 MISSING/DIFFERENCE 作为 rematch 结果态（写入者 = 仅 correction port；matcher 零写入不变）；(e) projection 换目标重新表达（V-E：受控 supersede 面；旧行零改写、新行追加、每 evidence 至多一 current authority）；(f) v26→v27 单个加性迁移与确定性回填（存量 active link 无失效历史时零回填或显式确定性回填）；(g) correction 专用 failure 码族与幂等/零写入/回滚-重试注入契约；(h) TP-nn 测试矩阵。其余一切都不在本批（完整清单见 §15 显式非目标）。

## 4. 修正请求契约（A）

### V-A 请求形状与原子性（裁决点）

- **A. 单请求单事务（推荐）**：correction 是「invalidate +（可选 successor 确认） + resulting 状态」的原子请求，`operation='invalidate_link'`，claim-first 幂等与 confirmLink 同构（Ledger.sq:8157-8159 claim + resolveReplay 路径，store:33-48/:329-353 先例）。请求携带：(i) 身份与产出去除指纹（ledger、request_id 为输出、evidence_id、previous_link_id、(可选) affected_posting_id）；(ii) 结构化原因 `reason ∈ {corrected, posting_replaced}`（复用枚举，不扩值，见 V-B）；(iii) 结果态 `result_state ∈ {CHECKED, MISSING, DIFFERENCE}`；(iv) `result_state=CHECKED` 时携带 successor 确认事实（posting/transaction/责任/五字段 basis/时间窗/投影身份——结构上镜像 `P408ConfirmLinkRequest` 的认证域，P408Reconciliation.kt:23-112 列集），不携带时语义 = invalidation-only。请求快照落新表 `reconciliation_correction_snapshot`（附录 B；不宽化 `human_decision='confirm_match'` 的确认快照 CHECK，Ledger.sq:7749）。等效 replay → `NoChange` 返回原 receipt；changed semantic 字段 → typed conflict 零写入。权衡：与 persistence spec :133-136「一次追加三件事」字面对齐；代价 = correction 端口要复刻 confirmLink 的一部分校验（五字段/posting integrity/时间窗/责任侧），需要共享内部校验函数而非复制。
- **B. 拆两操作：invalidate 与 rematch-confirm 分开**。风险：两事务之间存在「evidence 无 active link / posting 状态悬空」的可观察窗口；report 在该窗口按 COALESCE PENDING 展示（Ledger.sq:8320），与「失效-替换须同事务」的任务要求冲突（见 §6）；且两事务各自失败的重试编排复杂。否决倾向。
- **C. invalidate-only 由 confirmLink 顺带触发（无独立请求）**。风险：confirmLink 职责面与语义域被污染，且失败码/指纹面无法独立审计；不符合 persistence spec §2「每个操作独立请求快照」纪律。否决。

**推荐 A。** 触发来源（谁调用端口，版本替代自动触发 vs 人工补充重匹配）作为产品抉择单独登记（UQ-1）。

### V-C 受影响 posting 与结果态归属（裁决点）

- **A. 请求显式携带受影响 posting（推荐）**：`affected_posting_id` 由调用方命名，store 以 `selectP408PostingIntegrity`（Ledger.sq:8271-8283，current-version + ACCOUNT_TRANSFER）校验其现存且合格。`result_state=CHECKED` 时 affected posting == successor posting（同一 id，拆为独立字段但内容必须一致，防双真相）；`result_state ∈ {MISSING, DIFFERENCE}` 时 affected posting = 需要显示待补资料/有差异的新版本分录（版本替代场景）或原匹配分录（证据切换/改错匹配场景）。旧 link 的 posting 由 `previous_link_id` 派生仅用于失效事件，不做合格性 dereference（版本替代后旧 posting 已 stale，Ledger.sq:8271 谓词必然拒绝它——旧 posting 不参与 current 报告面，见 §7）。权衡：显式携带免除「从旧 link 猜 affected」的歧义，与迁移/报告只读 current posting 的架构一致；代价 = 调用方必须自己知道修正后的 current posting，属于产品集成面职责（UQ-1）。
- **B. affected 从旧 link 派生**。风险：版本替代场景旧 posting 已 stale，无法合法解析；只能退化为「只失效不改状态」，无法表达 MISSING/DIFFERENCE 语义。否决倾向。

**推荐 A。**

## 5. 追加式 invalidation event（B）

**V-B 事件载体与 reason 语义（裁决点）**

- **A. 复用 `evidence_link_history` 行即事件、reason 沿用既有三值（推荐）**：invalidation event = 向 old link 追加 `sequence+1, state='invalidated', reason ∈ {corrected, posting_replaced}, request_id, occurred_at`，sequence/transition 由既有触发器自动守卫（22.sqm:100-112 / Ledger.sq:7891-7904，只允许 active→invalidated 一次、之后封闭）。reason 语义冻结：`posting_replaced` = 版本替代（金额/账户/币种变更，ACCOUNTING_RULES.md:253「修改金额/账户/币种」场景）；`corrected` = 人工修正/补充资料后重匹配/改错匹配（ACCOUNTING_RULES.md:245、persistence spec 措辞）。零 schema 文本变化，事件与 link 失效同一性天然成立（同一行同一 identity）。权衡：语义最小、审计沿既有 history 表；代价 = 无更细粒度原因（如「补充资料」与「改错匹配」共用 `corrected`），如需细分只能扩 CHECK 值域（UQ-2）。
- **B. 扩展 reason 值域**（如 `material_supplemented`/`wrong_match`）。风险：需 v27 schema 文本变化与历史映射评审；既有三值已覆盖 D-048 两类触发，属投机面。不推荐。
- **C. 新建独立 invalidation 事件表**。风险：与 `evidence_link_history` 双写同一事实，审计面分裂；违反 persistence spec「append-only link events」的单一载体表述（:16-17/:61-67）。不推荐。

**推荐 A。** 事件落点 = 既有 history 表；不新增任何表到 link 面。successor 链接由 correction 创建时以 `state='active', reason='confirmed'` 出生（新匹配确认语义，§6.1 同款），与失效原由 `corrected`/`posting_replaced` 明确区隔——该署名冻结、不作实施批自选。

## 6. successor link 与单活性（C）

同一证据在失效-替换交替期的不变量：「一 evidence 至多一 active link」不因 correction 出现缺口或双活性：

1. **同事务成立**：correction 单事务内先 apped invalidation 事件（§5），再 `INSERT` successor link + `sequence=1/state='active'/reason='confirmed'` 的 history（22.sqm:100-103 允许 sequence 1 为 active），事务提交前对外不可见 → 提交后 `selectP408ActiveLinksForEvidence`（Ledger.sq:8285-8295）对旧 link 取 latest='invalidated' 不再命中、对 successor 取 'active' 命中，恰好一行。
2. **请求级预检**：`previous_link_id` 必须存在、属于请求 evidence、且 latest history state='active'（避免对已失效 link 二次失效）。不满足 → typed reject 零写入。
3. **posting 侧单活性**：successor 的 responsibility 侧若已存在另一 active link（Ledger.sq:8297-8306 谓词）→ typed conflict 零写入；同一证据不得同时被两次 correction 目标化。
4. **并行**：两条并发 correction 对同一 evidence——claim-first 保证第二个请求走 resolveReplay 或 typed 冲突；link 侧另有 DB 级兜底：即使两个真正不同的并发 correction 都目标同一旧 link，后写者仍会在旧 link 的 `evidence_link_history` 上触发 sequence-PK 唯一冲突（`(ledger_id, link_id, sequence)` 主键）或 transfer guard（latest='invalidated' 封闭，Ledger.sq:7896-7904）→ constraint ABORT 整事务回滚、输家 typed 拒绝零残留，单赢家由 DB 保证（并入 TP-10 并发格）；投影 re-expression 的 PK/部分唯一索引提供投影侧 DB 兜底（§8/§11）。

守卫保持（22.sqm:93-99）意味着旧 link 行、successor link 行、history 行全部不可改不可删；「证据-链接-对账状态一致推进」由 `posting_reconciliation_history_link_guard`（Ledger.sq:7909-7918）保证：追加的 reconciliation history 行若携带 `evidence_link_id`，其 link 必须 join 到同一 reconciliation 的 posting —— successor 链路天然满足（affected posting=successor posting，V-C-A）。

## 7. rematch 后 reconciliation 状态更新（D）

**V-D 状态转移决策（裁决点）**

- **A. 激活 MISSING/DIFFERENCE 且写入者仅限 correction port（推荐）**：correction 事务对 affected posting：(i) `insertP408PostingReconciliation` 幂等种 PENDING（Ledger.sq:8186-8188，若新版本 posting 尚无行——新 posting 无既有 seed，§1.6），并**同时**按 confirmLink 同款条件种子写 PENDING sequence=1 的 `posting_reconciliation_history`（含 request_id；先例 SqlDelightP408ReconciliationStore.kt:248-263），避免 `posting_reconciliation_history_sequence_guard`（Ledger.sq:7905-7908）在随后推进时因缺失 sequence=1 而 ABORT；(ii) append 同 sequence history 行（status=结果态，evidence_link_id=successor link 或 NULL，request_id=correction）；(iii) `updateP408PostingReconciliation` 推进。结果态语义按 D-048（DISCOVERY_DECISION_LOG.md:391-397）：`CHECKED`（后继确认，= 找到新匹配恢复已对账）、`DIFFERENCE`（只能找到旧金额显式差额）、`MISSING`（资料不足待补）。未受影响分录零接触（ACCOUNTING_RULES.md:253）。OLD posting 在版本替代场景是 stale、不再属 current 报告面（Ledger.sq:8317-8344 只 join current version），其 reconciliation 行保留原值作为历史（R 竖井先例「old fact unchanged, replacement posting gets a fresh fact」，domain PostingReconciliation.kt 头注）——**不更新 stale posting 行**，只留 invalidation 事件。权衡：语义与 D-048/ACCOUNTING_RULES 原文对齐；代价 = 激活两个此前无写入者的状态 token，matcher 零写入承诺需测试锁定（TP-*）。
- **B. 不激活 MISSING/DIFFERENCE，invalidate-only 一律回落 PENDING**。风险：D-048「只能找到旧金额显式差额、资料不足待补」无处表达；与 GOLDEN_TESTS/外部验收的 31 号审计面不符（CORE_ACCEPTANCE_PLAN.md:31-32）。否决倾向。
- **C. 引入新状态 token（如 REMATCHING）**。风险：要求 v27 改 `posting_reconciliation.status` CHECK（Ledger.sq:7814）与 read enum（P408Reconciliation.kt:10-15）与 fresh/migrated 同步，且 D-103 O-6 五值枚举是用户批准边界（DECISIONS.md:1634）；超出本批授权面。否决，除非用户显式要求（登记为备选，不入推荐）。

**推荐 A。** `MISSING`/`DIFFERENCE` 决策与触发资格列 UQ-3。

## 8. projection 交互：换目标重新表达（E，核心设计点）

D-112 UQ-2 = V-5-A 原文（DECISIONS.md:1788）：「换目标账户重新表达须待未来 correction 批」。本批给出该落点，必须不破坏 V-1-A 终态模型（每 evidence 单行、READY/REJECTED 双终态、UPDATE/DELETE 无条件 ABORT、v26 零状态转移函数）。

**V-E 重新表达的承载（裁决点，二选一）**

- **A. 受控 supersede 转移 + successor 行（推荐）**：v27 在 `evidence_projection` 上引入**唯一合法状态转移**——`superseded_by_projection_id`（nullable，注入列）：任何 evidence 的新表达 = 新 `projection_id` 的行（`proj-<evidenceId>-<n>` 确定性派生，基于该 evidence 既有投影行数）+ 旧行的一次性 supersede 转移。转移语义：UPDATE 触发器**只允许**把 `superseded_by_projection_id` 从 NULL 置为新行 id，其余 18 列逐列不变（v26 `evidence_projection` 共 18 列；触发器把「任何内容变化 / 任何旧行为非 NULL / 新值仍为 NULL」一律 ABORT）；`UNIQUE(ledger_id, evidence_id)`（Ledger.sq:7863）替换为**部分唯一索引** `ON evidence_projection(ledger_id, evidence_id) WHERE superseded_by_projection_id IS NULL` → DB 级保证「每 evidence 至多一条 current authority」；READY/REJECTED 各行本身仍终态、仍禁 UPDATE/DELETE（除该转移外）。消费面（`selectP408EvidenceProjection` 与 store `resolutionFor`，SqlDelightEvidenceProjectionStore.kt:41-43/:85-101）改为「evidence → current authority = superseded_by IS NULL 且 evidence_id 匹配」；`economicEquals` 比较语义不变（新增列不参与）。后果：REJECTED→换目标→新 READY 行合法、旧 REJECTED 行保留可审计（与 DISCOVERY_DECISION_LOG.md:156-160 冲突两侧保留一致）；`P408_PROJECTION_NOT_READY` 只对 current authority 判定。V-1-A 的「v26 零转移函数」以批界形式保持（v26 语义不动，v27 由本批登记该唯一转移）。
- **B. 独立 succession 事件表 / 独立投影事件面，`evidence_projection` 文本零变化（替代）**：新表存 (evidence_id, previous_projection_id, successor_projection_id, reason)，仍需要插第二行 → 必须同时撤 UNIQUE，否则第二行插不进去；跨表「每 evidence 一 current」守卫需在触发器/store 双写，消费查询要跨表 join。风险：单点守卫变成两点守卫（触发器 + 消费谓词各写一份真相），并发/回滚边界更脆；收益相对 A 仅是把转移痕迹挪进新表——而转移本身就是一行新列，审计面不因放新表面更完整。不推荐。
- **C. 保持 UNIQUE 不动、换目标重新表达 = 新票据/新证据重导入（替代）**。风险：与 D-048「同一证据 rematch、不重复导入」语义冲突（DISCOVERY_DECISION_LOG.md:391-397）；证据身份断裂、旧 link 与旧证据的关系永远无法在同一 evidence 身份下重新表达；正是 D-112 以「须待 correction 批」把手伸出的原因。否决。

**推荐 A，作为 V-1-A 的授权承接面（本批唯一的 schema 文本修订点之一，连同附表 DDL）。** 副作用清单：`selectP408EvidenceProjection` 查询加 current 过滤；`EconomicEquals`、`insert` 不动；迁移零回填（存量行 superseded_by 全 NULL = current）；`countEvidenceProjectionRows` 语义不变。

### 双路径复配（E 辅）

correction 事务内的 projection 步骤与既有两路径终点同函数（镜像投影规格 V-6/V-7，D-112 实施登记 1/3）：correction store 复用 `SqlDelightEvidenceProjectionStore` 的包内事务核心（`resolutionFor` 决定 desired current row；若 desired 与 current 匹配 → 零投影写入；若不匹配（换目标/换币种/换精度）→ 先写受控 supersede 转移 + 新行，再以新 current 做 successor 链路校验）。为此投影 store 将**新增包内受控 supersede 原语**（受控转移 + 新行插入同一事务）；现有 `resolutionFor` 不携带 desired 模型（仅计算-取回，SqlDelightEvidenceProjectionStore.kt:85-101）、不可直接复用，须新增带 desired 的变体或转移专用入口（实施批决定形状）。spine 端不受影响（correction 批不做 spine 自动materialize 广播；spine 成功路径仍按 D-112 在确认时物化，未匹配 correction 前不变）。

## 9. 失败分类码族（G）

**V-F 码族裁决（推荐；具体落点 frozen 于实施批）**

复用既有（不新增）：`P408_REQUEST_IDENTITY_CONFLICT`（changed retry / 回声失配）、`P408_EVIDENCE_ALREADY_LINKED`（successor 时 evidence 已有 active 冲突；correction 事务内该判定时序在事务内 invalidation 事件追加之后——前驱 link 在判定点仍为 active 属合法状态——或显式豁免 previous_link_id）、`P408_POSTING_NOT_ELIGIBLE` / `P408_POSTING_FACT_MISMATCH` / `P408_TRANSACTION_ID_MISMATCH` / `P408_RESPONSIBILITY_POSTING_MISMATCH` / `P408_POSTING_TIME_WINDOW_MISMATCH`（successor 校验沿用 store:147-174 语义）、`P408_RECONCILIATION_MISSING` / `P408_RECONCILIATION_ID_MISMATCH`（store:241-247）、`P408_REQUEST_BASIS_VERSION_RETIRED`（correction 恒 v2，杜绝 v1 correction 形状）、`P408_PROJECTION_*` 族（V-4 全码，current-authority 语义）与 `P408_RECONCILIATION_CONSTRAINT_VIOLATION`（constraint 兜底）。

新增（冻结如下，code/severity/scope/location 实施批登记；message 不稳定不比较）：

| code | 触发 | 结果 |
| --- | --- | --- |
| `P408_INVALIDATE_LINK_NOT_ACTIVE` | previous_link_id 不存在、不属于请求 evidence、或 latest history 非 active | typed reject，零写入 |
| `P408_CORRECTION_RESULT_INVALID` | result_state 与 successor 事实互斥/缺失（CHECKED 无完整 successor、MISSING/DIFFERENCE 带 successor） | typed reject，零写入 |
| `P408_CORRECTION_AFFECTED_POSTING_MISMATCH` | CHECKED 时 affected_posting_id ≠ successor posting；或 affected posting 不合格（selectP408PostingIntegrity 谓词，Ledger.sq:8271） | typed reject，零写入 |
| `P408_CORRECTION_PROJECTION_CONFLICT` | 重新表达 desired 与 current authority 冲突且非受控转移形态（如并发双 correction 的投影写入败方） | typed reject/concurrent loser，零写入 |
| `P408_CORRECTION_SNAPSHOT_MISMATCH` | replay 等价比较发现 correction snapshot 内容不等（与 P408_REQUEST_IDENTITY_CONFLICT 分工：后者用于确认族，本码用于 correction 族内） | typed reject，零写入 |

零写入契约：上述任一 typed failure 与 unique/FK 冲突沿 correction 事务全回滚（claim 行亦回滚→身份可重试，persistence spec :127-131、store :39/:304-310 先例）。回滚/重试注入点（H 矩阵）：`CORRECTION_AFTER_INVALIDATION`（invalidation 事件后）、`CORRECTION_AFTER_SUCCESSOR_LINK`（successor link 后）、`CORRECTION_AFTER_PROJECTION_SUPERCEDE`（投影转移后）。

## 10. v26→v27 加性迁移（F）

单事务顺序（新文件 `26.sqm`，模板以 `25.sqm` 为准：pre-guard → stage-copy 保序重建无 RENAME → 复制回灌 → 行数守卫 → late sentinel → stage 清理；源 25.sqm:17-33/:67/:70-177/:185-260/:266/:273-275）：

1. **pre-guard `p408_v27_pre_guard`**：fail-closed 断言存量 P4-08 行满足 v27 倚赖的不变量——`evidence_projection` 每 (ledger_id, evidence_id) 恰一行（UNIQUE 现存即隐含，显式重复断言防代际 bug）、`evidence_link_history` 无违反 active→invalidated 封闭转移的非法 latest 态、`reconciliation_request.operation` 不出现已废弃值。违例即 ABORT 整事务。
2. **重建 `evidence_projection`**（不给 RENAME 换位）：撤 `UNIQUE(ledger_id, evidence_id)`、新增 `superseded_by_projection_id TEXT`、改 UPDATE guard 触发器为「仅受控 supersede」语义（§8 V-E-A 字面）、补 `CREATE UNIQUE INDEX evidence_projection_current_by_evidence ON evidence_projection(ledger_id, evidence_id) WHERE superseded_by_projection_id IS NULL`、按 fresh 名逐字重挂 `evidence_projection_by_state`（Ledger.sq:7868 索引文本）与 DELETE guard；旧行逐列复制（superseded_by 回填 NULL）。`evidence_projection_by_state` 与部分唯一索引同为下述 fresh/migrated schema 文本等价断言项，且同列于 fresh 终态定义附录 A。**零回填**：存量 active link 无失效历史（`:invalidate_link` 无写入者，§1.1/§1.8），无任何 supersede/失效推演——零猜、零推断；行数守卫 + fresh=migrated 数据级等价断言承载。
3. **新表 `reconciliation_correction_snapshot`**（附录 B 字面；空表 + FK 链 + `reconciliation_correction_snapshot_guard_update/delete` 两条 ABORT 触发器，同 `reconciliation_snapshot_guard_*` 款）。
4. **不动**：`import_evidence`、`import_source_record`、`evidence_link`、`evidence_link_history`、`posting_reconciliation`(_history)、`reconciliation_request`/`_snapshot`/`receipt`、formal transaction/posting/version 全部行与 schema 文本零变化（link/投影两面激活全部复用既有预留面）。
5. **late sentinel `p408_v27_late_sentinel`**（重建+迁移后插入失败 → 全事务回滚，先例 25.sqm:266）→ 清理 guard/sentinel → 版本推进 v26→v27。

**fresh=migrated 断言面**（实施批不缩水）：populated v26 带已失效历史（本批测试自建）迁到 v27 与 fresh v27 直接构造等价：(i) 行面——`evidence_projection` 全列（含新列）、`reconciliation_correction_snapshot` 全列逐行相等（存量两侧均为空行集）、`evidence_link`/`evidence_link_history`/`posting_reconciliation` 三表逐行相等；(ii) schema 文本面——受控转移触发器、`evidence_projection_by_state`（Ledger.sq:7868 文本原样）、部分唯一索引与 `reconciliation_correction_snapshot` 两条 guard 触发器文本在 fresh/migrated 一致；行集合零改写（重建是 schema 文本演化而非行级变更，25.sqm 步骤 3 注释同口径）。

## 11. 测试矩阵（H）

命名 TP-nn；新增测试归 ledger-application jvmTest（请求构造/指纹）与 ledger-data jvmTest（store/migration/oracle/原子性）；登记 GOLDEN_TESTS「P4-09 收口失败矩阵」时按 :194 语义维度行附注形态延后落盘（投影规格 UQ-6 同款）。

| # | 覆盖项 | 断言核心 |
| --- | --- | --- |
| TP-01 | 正常修正（版本替代，同事实） | 旧 link invalidated（reason=posting_replaced）、successor link active、affected CHECKED、receipt ACCEPTED；旧 link/history 行逐列未动；其余分录状态不变 |
| TP-02 | 换目标重新表达（投影接管） | 新 current projection（READY/新 target）落行、旧投影 superseded 冻结、successor link 经新权威校验通过；`selectP408EvidenceProjection` 只回 current |
| TP-03 | 幂等重放 | 等效重放 diff=NoChange 原 receipt；countEvidenceProjectionRows/link/history/request 零新增 |
| TP-04 | changed retry | successor 事实变更 → `P408_CORRECTION_SNAPSHOT_MISMATCH`/`P408_REQUEST_IDENTITY_CONFLICT` 零写入 |
| TP-05 | invalidate-only → MISSING | 资料不足分支：旧 link 失效、affected→MISSING（history 同 sequence 配对+update guard 满足）、无 successor link |
| TP-06 | invalidate-only → DIFFERENCE | 只能找到旧金额：affected→DIFFERENCE；未受影响分录零变化 |
| TP-07 | 非法修正拒绝 | 旧 link 非 active→`P408_INVALIDATE_LINK_NOT_ACTIVE`；affected 不合格→`P408_CORRECTION_AFFECTED_POSTING_MISMATCH`；result/successor 互斥→`P408_CORRECTION_RESULT_INVALID`；全部零 claim 残留、身份可重试 |
| TP-08 | 失败注入回滚/重试（oracle） | 注入点 §9 三处：invalidation/successor/projection supersede 后失败 → 全事务回滚（claim/link/history/recon/projection/snapshot 零残留），corrected retry ACCEPTED |
| TP-09 | 金融不变式 oracle | correction 前后全部 formal 行（ledger_transaction/transaction_version/posting/current_version/posting_set）、派生余额与十维 report financial projection 逐值相等；reconciliation 维度展示 successor 后状态 |
| TP-10 | 单活性 | correction 后 evidence 恰一 active link；posting+responsibility 侧恰一；report active_link_ids 只含 successor；并发 correction 单赢家、输家 typed 零残留（含 link 侧 sequence-PK / transfer guard 兜底与投影侧部分唯一索引兜底） |
| TP-11 | 事件追加守卫 | old/successor link history 行 UPDATE/DELETE ABORT；sequence 连续；active→invalidated 后再插 ABORT；二次失效 typed 拒绝 |
| TP-12 | 状态链一致性 | PENDING→CHECKED→（correction）→MISSING/DIFFERENCE→（后续 confirmLink）→CHECKED 全链 history 连续、sequence+1 配对、link 一致性 guard 满足 |
| TP-13 | projection REJECTED→重新表达 | REJECTED 行冻结 superseded、新 READY current 行落、READY 门通过、迁移/回填零受影响 |
| TP-14 | 迁移两侧等价 | populated（含失效历史）与 fresh 逐行相等 + 新触发器/部分索引文本一致 + late sentinel 回滚真 late-stage |
| TP-15 | v1/v2 边界 | correction 请求恒 v2；以 v1 形状提交 → `P408_REQUEST_BASIS_VERSION_RETIRED`；确认族 v1 replay 不受 correction 影响 |
| TP-16 | matcher 零自动转移锁定 | 任意 correction 触发不改变 matcher 输出；missing/difference 只能由 correction 写、matcher/projection 面零写回（D-103 O-3 回归锁） |
| TP-17 | remark/分类/统计时间负路径 | 同 evidence 事实不变但账务仅改备注/标签/分类/统计时间 → correction 不触发（或触发后零状态变化），资金分录结果保留（ACCOUNTING_RULES.md:253） |

## 12. 验证命令

聚焦 → 全量顺序沿用仓规（README.md:43-63 同款）：`:ledger-application:jvmTest`、`:ledger-data:jvmTest`、`:ledger-data:verifyCommonMainLedgerDatabaseMigration`、Android `compileAndroidMain`、Python 全套复查即可豁免重跑（如实留痕，D-110 O-9 八层精神）；`:ledger-domain:jvmTest` 豁免重跑并登记依据：本批生产改动面 = ledger-application（correction 端口/请求模型）与 ledger-data（store/迁移/schema），domain 竖井零改动（§15 显式非目标 5）；若实施批触碰 domain 则取消豁免、通道并入全量。Windows 主机串行单 worker 限制照 `docs/CONTRIBUTING.md` 执行。本批次（spec draft）只做 `project_docs` 文档验证：`PYTHONPATH=tools/python python -m project_docs .`（工具仅验证 `tools/python/project_docs/validator.py` 的 `FORMAL_DOCUMENTS` 十四份正式文档，**不覆盖 docs/specs/***——本文件不在其扫描面，验证器通过不构成对本文档内容的背书）。

## 13. UQ-* 未决清单（产品/架构抉择，本文只管推荐、不作批准宣告）

1. **UQ-1 修正触发资格与调用方**：correction port 打开后，谁在何时调用？推荐 = 本批只冻结端口+持久化语义+测试（直接端口调用，与 confirmLink 同构）；版本替代自动触发、UI 补充资料重匹配等跨层集成（spine 通知、应用协调）登记后续批（连接 D-048 自动重匹配与 ACCOUNTING_RULES.md:253）。请裁决集成范围。
2. **UQ-2 reason 值域与 link/projection 失效面统一性**：推荐 = 复用三值（corrected/posting_replaced）、link 失效与投影 supersede 是同一 corrections 事务内的两个事件（不统一成单一事件，因投影转移携带 projection_id 引用）；是否要求「投影事实未变也必须 supersede」= 否（V-E 已定 link-only 修正零投影写入）。请确认不扩值、不合并事件面。
3. **UQ-3 MISSING/DIFFERENCE 激活**：推荐 = 激活为 correction 结果态（V-D-A），写入者仅 correction port。请确认（或选 V-D-B/C）。
4. **UQ-4 迁移回填形态**：推荐 = 零回填（无失效历史可推，存量全 current），pre-guard + 行数守卫 + fresh=migrated 断言。请确认。
5. **UQ-5 失败码归宿**：推荐 = §9 五新码纳入 P408_* 族、correction 族独立于确认族、落入 store companion 常量区（先例 SqlDelightEvidenceProjectionStore.kt:273-285）。请确认命名冻结。
6. **UQ-6 correction snapshot 表 vs 宽化确认快照**：推荐 = 新表 `reconciliation_correction_snapshot`（`human_decision` 确认 CHECK 零变化）。请确认。
7. **UQ-7 版本替代场景的旧 posting reconciliation 行**：推荐 = 不更新 stale 旧行（只留失效事件），新版本 posting 走 current 报告面。请确认（与 RG-12 竖井「old fact unchanged」先例一致，不回写竖井）。
8. **UQ-8 V-E-A 受控 supersede 是否为 D-112 V-1-A 的授权承接面**：本文件把「每 evidence 单行终态」演进为「每 evidence 至多一 current authority + 冻结 superseded 行」；该演进需要用户明确确认作为对 D-112 UQ-1 终态模型的承接修订（此前批准的 v26 行集不回溯改写）。

## 14. 显式非目标（I）

- 不改 formal posting/transaction/version 行，不删任何行（P4-08 面与 formal 面皆然）；不引入「反向交易/冲回」语义（DISCOVERY_DECISION_LOG.md:383-389 D-047 区分）。
- 不改 matcher eligibility/五字段身份/时间窗/D-103 approved 组合（D-103 O-1..O-3 是批准边界）；matcher 仍零自动转移。
- 不实施 spine 侧自动 correction 广播；不打开银行 parser 门（D-099:1540 仍开；D-109 O-8）。
- Phase 5 组合根/平台壳不动；产品 Clock/随机 ID 不引入。
- 不回溯 RG 竖井：12 竖井零改动（D-092 不退役）；domain `PostingReplacement`/`ReconciliationMatch` 等冻结概念只作行为先例（D-085 竖井），本批在共享产品面重新表达、不复制竖井代码。
- 不扩展 `import_evidence`/`import_source_record`；证据身份不因重新表达而更换（V-E-B/C 已否决）。
- report 十维 financial 投影、canonical oracle 之外语义、golden publication 均不在本批。

## Appendix A. evidence_projection 受控 supersede 面 DDL 草图（实施批以评审后文本为准）

```sql
CREATE TABLE evidence_projection (
  -- v26 全部既有列原样（Ledger.sq:7843-7866 字面；UNIQUE(ledger_id, evidence_id) 移除）
  ledger_id TEXT NOT NULL, projection_id TEXT NOT NULL, evidence_id TEXT NOT NULL,
  source_id TEXT NOT NULL, source_hash TEXT NOT NULL,
  target_account_id TEXT NOT NULL, currency_code TEXT NOT NULL,
  currency_precision INTEGER NOT NULL CHECK (currency_precision >= 0),
  raw_amount_minor INTEGER NOT NULL CHECK (raw_amount_minor >= 0),
  raw_currency_precision INTEGER NOT NULL CHECK (raw_currency_precision >= 0),
  normalized_amount_minor INTEGER NOT NULL CHECK (normalized_amount_minor >= 0),
  direction_token TEXT NOT NULL CHECK (direction_token IN ('in', 'out')),
  state TEXT NOT NULL CHECK (state IN ('READY', 'REJECTED')),
  rejection_code TEXT,
  rule_id TEXT NOT NULL, rule_version INTEGER NOT NULL CHECK (rule_version >= 1),
  materialization_request_id TEXT NOT NULL, materialized_at TEXT NOT NULL,
  superseded_by_projection_id TEXT,            -- v27 add: NULL = current authority
  PRIMARY KEY (ledger_id, projection_id),
  FOREIGN KEY (ledger_id, evidence_id) REFERENCES import_evidence(ledger_id, evidence_id) DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY (ledger_id, source_id) REFERENCES import_source_record(ledger_id, source_id) DEFERRABLE INITIALLY DEFERRED,
  CHECK ((state = 'READY' AND rejection_code IS NULL) OR (state = 'REJECTED' AND rejection_code IS NOT NULL))
);
-- 每 evidence 至多一 current authority（DB 级守卫，替代原 UNIQUE）
CREATE UNIQUE INDEX evidence_projection_current_by_evidence
  ON evidence_projection(ledger_id, evidence_id) WHERE superseded_by_projection_id IS NULL;
-- 既有按证据查询索引按 fresh 名原样重挂（Ledger.sq:7868；staged 重建随表 DROP 后必须同批重建，25.sqm 步骤 3 重挂先例）
CREATE INDEX evidence_projection_by_state ON evidence_projection(ledger_id, state);
-- 受控转移：唯一合法 UPDATE = 把 superseded_by_projection_id 从 NULL 置值，其余列不变
CREATE TRIGGER evidence_projection_guard_update BEFORE UPDATE ON evidence_projection BEGIN
  SELECT CASE WHEN old.superseded_by_projection_id IS NOT NULL
      OR new.superseded_by_projection_id IS NULL
      OR new.ledger_id != old.ledger_id OR new.projection_id != old.projection_id
      OR new.evidence_id != old.evidence_id OR new.source_id != old.source_id
      OR new.source_hash != old.source_hash OR new.target_account_id != old.target_account_id
      OR new.currency_code != old.currency_code OR new.currency_precision != old.currency_precision
      OR new.raw_amount_minor != old.raw_amount_minor
      OR new.raw_currency_precision != old.raw_currency_precision
      OR new.normalized_amount_minor != old.normalized_amount_minor
      OR new.direction_token != old.direction_token
      OR new.state != old.state OR COALESCE(new.rejection_code,'') != COALESCE(old.rejection_code,'')
      OR new.rule_id != old.rule_id OR new.rule_version != old.rule_version
      OR new.materialization_request_id != old.materialization_request_id
      OR new.materialized_at != old.materialized_at
    THEN RAISE(ABORT, 'cannot update evidence projection') END;
END;
CREATE TRIGGER evidence_projection_guard_delete BEFORE DELETE ON evidence_projection BEGIN
  SELECT RAISE(ABORT, 'cannot delete evidence projection'); END;
```

要点：successor projection_id 确定性派生 `proj-<evidenceId>-<seq>`（seq = 该 evidence 既有投影行数 + 1）；两并发 correction 同 seq 时 PK 冲突使败方 typed 失败 → 符合并发单赢家；`economicEquals` 不含新列（SqlDelightEvidenceProjectionStore.kt:113-129 语义保持）。FK 形状与 account 绑定沿用 fresh schema 复核纪律（投影规格附录 A 勘误条款：无独立 account 表，`target_account_id` 纯文本定向绑定）。

## Appendix B. reconciliation_correction_snapshot 列集草图

```text
ledger_id TEXT, request_id TEXT                        -- PK + FK → reconciliation_request
evidence_id TEXT                                        -- FK → import_evidence
previous_link_id TEXT                                   -- FK → evidence_link；必须为该 evidence 的 current active link
reason TEXT CHECK (reason IN ('corrected','posting_replaced'))
affected_posting_id TEXT                                -- FK → posting；CHECKED 时 = successor_posting_id
result_state TEXT CHECK (result_state IN ('CHECKED','MISSING','DIFFERENCE'))
-- successor 事实（CHECKED 时 NOT NULL，否则 NULL）：
successor_link_id TEXT, successor_posting_id TEXT, successor_transaction_id TEXT,
successor_responsibility TEXT CHECK (successor_responsibility IN ('real_account_posting','destination_asset_posting')),
successor_candidate_id TEXT, successor_match_basis TEXT,
successor_window_days INTEGER, successor_natural_day_distance INTEGER,
successor_source_occurred_at TEXT, successor_confirmed_at TEXT, successor_created_at TEXT,
-- 投影权威（v2 恒写，镜像确认快照投影四组字段的语义；重新表达时指向新 current）：
projection_id TEXT, projection_rule_id TEXT, projection_rule_version INTEGER,
normalized_amount_minor INTEGER, raw_amount_minor INTEGER, raw_currency_precision INTEGER,
basis_version INTEGER NOT NULL CHECK (basis_version = 2)
CHECK ((result_state='CHECKED' AND successor_link_id IS NOT NULL AND successor_posting_id IS NOT NULL
        AND successor_transaction_id IS NOT NULL AND successor_confirmed_at IS NOT NULL
        AND affected_posting_id = successor_posting_id)
    OR (result_state IN ('MISSING','DIFFERENCE') AND successor_link_id IS NULL
        AND successor_posting_id IS NULL AND successor_transaction_id IS NULL))
```

-- 守卫触发器（同 reconciliation_snapshot_guard_* 款，UPDATE/DELETE 一律 ABORT；fresh 终态定义与 26.sqm 逐字一致）
CREATE TRIGGER reconciliation_correction_snapshot_guard_update BEFORE UPDATE ON reconciliation_correction_snapshot BEGIN SELECT RAISE(ABORT, 'cannot update correction snapshot'); END;
CREATE TRIGGER reconciliation_correction_snapshot_guard_delete BEFORE DELETE ON reconciliation_correction_snapshot BEGIN SELECT RAISE(ABORT, 'cannot delete correction snapshot'); END;

fingerprint（v2 规范串，UTF-8 `|` 分隔 ASCII 字段名、集合 token 排序去重；**排除** link_id/request_id/reconciliation_id/created_at 等输出值，persistence spec §2 同款）：ledger、evidence、previous_link、reason、affected_posting、result_state、successor 事实域（如存在）、projection 四组、occurred/confirmed 时间。等效 replay 全列比较 → NoChange；changed → `P408_CORRECTION_SNAPSHOT_MISMATCH` 零写入。

## 15. 边界断言

- 本文档为 draft 冻结候选：在用户批准 + 独立评审闭环之前，不授予任何实施权限、不宣称 approved、不预设 D 编号；实施仍在独立 worktree、单一 bounded writer、独立规格/质量评审、distinct verifier 与主代理最终验收之下（AGENTS.md 变更路由）。
- 真实金额/时间/锚点注册值不复制入文；示例全部匿名合成（P405FIX-QUAL-001 先例）；`.external/` 只读未触碰；`docs/specs/` 现有文档零改动（本批唯一写入 = 本新文件）。
- 实施批必须保持本规格冻结的：单事务 invalidation+successor+状态、旧行零改写、MISSING/DIFFERENCE 写入者唯一性、受控 supersede 面、迁移零回填与 fresh=migrated 断言、TP-01..TP-17 覆盖面与 §9 失败码族；任何变更即重开评审门（batch-discipline 句式沿 persistence spec :161-165）。
- 「不改金额/账户/币种时的 remark/标签/分类修改」与「统计时间修改」属于本批负路径锁定（TP-17），不随 correction 语义漂移（ACCOUNTING_RULES.md:253）。
- 实施批先验登记：仓库当前无 SQLite partial unique index 先例（既有唯一约束均为整表 UNIQUE/PK），实施批必须在迁移实现前先行验证 SQLDelight 对 `CREATE UNIQUE INDEX ... WHERE`（partial index）的解析支持，并把 fresh/migrated 两面触发器与索引文本的逐字一致性纳入迁移 verifier 断言；任何兼容性失败不得静默降级为「仅测试侧可达」。