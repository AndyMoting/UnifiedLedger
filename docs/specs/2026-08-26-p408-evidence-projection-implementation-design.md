# P4-08 normalized evidence projection 实施规格（第二批）

**Status:** approved（2026-08-27 用户按推荐批准 UQ-1..UQ-6 全部裁决项并同步授权实施批；决定登记 D-112）。冻结文本 = SHA-256 33fc68bdbd8f6da75028688c225f872b5784d68bb0edec4aeb59e61ed05c51c1（评审终局 APPROVE 版；本行仅作批准状态与登记翻转）。本批为 WORK_PLAN.local.md「O-2 / P4-08 Precision Rescale Plan」执行批次 2（P4-08 normalized evidence projection）的 HOW 规格：承接第一批 O-2 已合并语义，新增独立追加式 evidence projection 表、materialization API、matcher 消费门、v2 request/fingerprint/snapshot 与 v25→v26 加性迁移。本文档只冻结设计；实施、Git 写操作与最终验收属后续独立 worktree 实施批。

**Revision:** 2026-08-26 独立评审 APPROVE-WITH-FINDINGS 的纯文本修订闭合（P408PROJ-SPEC-001..007）：迁移范围扩展至两处 `basis_version` CHECK 放宽与 `evidence_link` 同批重建、「写入恒 v2」新生请求类型化拒绝码、工程裁决归属声明、三处行号勘正、22.sqm 七张共享表逐一列举、来源精度列名统一为 `raw_currency_precision`、mirror 物化时机解读归属注记；其余冻结维度与结论不变。

**Scope:** 冻结 P4-08 第二批的表 DDL 草图、materialization 端口归属与幂等/拒绝语义、`P408EvidenceFacts` raw/normalized 拆分与 READY 门、v2 身份链版本方案、mirror evidence 物化时机与事务原子性、迁移回填规则、测试矩阵与显式非目标。金额全程整数 minor units / 精确十进制，禁浮点；示例全部匿名合成值。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 文件行号为 main 基线 `868bb35` 的行号；`.local.md` 文档为主 checkout local-only 文件，以主 checkout 为准；`.external/` 只读）：

- 批次合同：WORK_PLAN.local.md:158-187「O-2 / P4-08 Precision Rescale Plan」——源码依据 :160-165（同精度等值断层）、第一批范围 :169-174（已验收合并）、**第二批合同 :176-181（本规格唯一 WHAT 来源）**、验收与阶段门 :183-187（测试覆盖清单 + 两批之间非 2 位来源只能 `PENDING/UNRESOLVED` + P5 双批闭合门）。
- 第一批决定：D-111「O-2 确认装配金额精度重标定」（DECISIONS.md:1765-1777）：来源 `amountMinor`/`currencyCode`/`currencyPrecision`/指纹/source row 原样保留；仅等精度、精确补零、精确整除降精度；`AmountNotRepresentableInCurrency` 与 `ArithmeticOverflow` 分类拒绝；工厂失败经 `commitOnce` 映射 `SPINE_DOMAIN_VALIDATION_FAILED` 零写入可重试；O-2 不改 matcher/reconciliation precision 相等规则。
- matcher 契约门：D-103（DECISIONS.md:1625-1641）：O-1 资金事实核心门（amount+currency+direction+account 必选、时间窗参与）；:1639 **correction/successor invalidation 明确延期**（P4-08 不因此视为全量闭环）。D-109 O-5（DECISIONS.md:1738）：该延期维持并在失败矩阵登记（GOLDEN_TESTS.md:194）。
- 收口边界：D-109 O-8（DECISIONS.md:1738）：RL-07 按**平台侧适用子集**收口，银行侧真实镜像维度延期至银行 parser 门（指针 **D-099:1540 仍开**），不阻断阶段判据。
- 可扫标题：D-101（DECISIONS.md:1576 支付宝 parser 技术）、D-104/D-105（DECISIONS.md:1643/:1657 duplicate/closed 契约与实施授权）——两批的 spine/duplicate 纪律沿用，不扩权。
- 失败矩阵基线：GOLDEN_TESTS.md:179-196「P4-09 收口失败矩阵」——RL-07 行 :191 含已知延期维度登记方式（语义维度延期不设列、以行登记）；本批的新增测试锚点后续登记进该矩阵时按同一方式处理。
- 前序规格范本：docs/specs/2026-08-19-p4-08-matcher-contract-design.md（O-1..O-6 冻结裁决）；docs/specs/2026-08-19-p4-08-persistence-implementation-design.md:61-67/:161-165（「本批只做 X，Y 明确不在本批」批次纪律表述）；docs/specs/2026-08-23-o-2-precision-rescale-design.md（归一化契约原文）；docs/specs/2026-08-22-p409-phase-closure-contract-design.md（失败矩阵引用格式）。

### 外部证据门摘记（neutral rationale，原始研究不入 tracked 文件）

依据 AGENTS.md 外部参考门读取 `docs/SOURCE_REFERENCES.md`（local-only junction 注册表）、`.external/requirements/DISCOVERY_DECISION_LOG.md` 与 `.external/requirements/golden-ledger/CORE_ACCEPTANCE_PLAN.md`，与本批相关的中立约束如下（非 raw 笔记）：

1. 分层结构「原始记录 → 账务交易 → 账户变动 → 对账结果」（DISCOVERY_DECISION_LOG.md:23）与本项目 ACCOUNTING_RULES 的 source/formal/reconciliation 三分一致；projection 是对账层消费前的一次确定性投影，不改变任何上游事实，也不直接写对账状态。
2. 部分对账允许补充资料后重新对账、不得自动补平（DISCOVERY_DECISION_LOG.md:150-153、:300-302）；冲突时两侧事实都保留由用户决定（:156-158）→ 归一化失败的 evidence 必须保持待补/unresolved 上界，系统不得丢弃或猜测目标账户。
3. 导入另一侧流水时作为同一转账的补充证据、不生成第二笔转账或收入（DISCOVERY_DECISION_LOG.md:288）→ mirror evidence 的 projection 服务于「补充证据追加 lineage」语义，不允许借机产生第二笔交易或额外 link。
4. 对账状态落到每条真实账户分录、汇总展示且不改变金额或余额（DISCOVERY_DECISION_LOG.md:369-373；ACCOUNTING_RULES.md:241）→ 本批零 report 维度改动、reconciliation 状态写入路径保持原样。
5. RL-07 锚点 = 平台侧证据找到后两端只形成一笔正式转账、第二来源作为补充证据（CORE_ACCEPTANCE_PLAN.md:66，锚点 ID `GL-0DCF5FCDB9BA`）；完成标准要求对账状态有明确断言（CORE_ACCEPTANCE_PLAN.md:92）→ 测试矩阵沿用合成镜像子集（D-109 O-8），银行侧真实镜像不入本批。
6. 隐私：真实案例只在私有黄金集保存来源映射，计划/仓库文件不复制商户、商品、订单号或个人关系信息（CORE_ACCEPTANCE_PLAN.md 开篇声明）→ 本文示例一律合成值（如 `99@scale0 → 9900@scale2`、`0.5@scale1 → 50@scale2`）。

## 1. 现状基线（代码现实，写契约前逐条核对）

1. `P408Matcher.kt:147-155` `sameFundingFacts` 要求 amount、currency、precision、direction、account 五字段全等；默认常量 `DEFAULT_WINDOW_DAYS=2`/`DEFAULT_LOCAL_OFFSET_SECONDS` 在 :243-244。`P408EvidenceFacts` 定义于 :23，由调用方装配（当前生产装配源 = store 从 raw source facts 直读，见下条）。
2. `SqlDelightP408ReconciliationStore.kt:28` `confirmLink`：从 `selectP408EvidenceSourceFacts`（Ledger.sq:8173-8179，JOIN `import_evidence` × `import_source_record`）读 **raw source** 事实，:52-84 对 source/request/posting 三方做逐字段精确比较（含 `sourcePrecision != request.currencyPrecision` → `P408_SOURCE_FACT_MISMATCH`）；:133-145 插入 link；:146-154 写 history（仅 `active`/confirmed 路径）。
3. `P408ReconciliationStatus`（P408Reconciliation.kt:10-15）枚举含 `MISSING`/`DIFFERENCE`，生产代码无写入者（仅枚举定义与 `P408_RECONCILIATION_MISSING` 拒绝码字符串；显式 no-match 请求亦未实现）。提交端口 `P408ReconciliationCommitPort` 于 :97-101；请求构造器 :23 起 `require(basisVersion == 1)` 并钉死 `REQUIRED_MATCH_BASIS`（v1 唯一）。
4. `ExactAmountNormalization.kt:29-53` `normalizeSourceMinorExact`：精确补零/整除降精度/余数拒绝/溢出拒绝；任意高 scale 零金额仍精确为零。`normalizeImportAmountExact` 补充币种不符拒绝。
5. `TransferFlowFormalFactory.kt:139-236` `validateImportFormalBinding`：正式分录必为账户 precision 归一金额（第一批扩展后校验 transaction kind、版本/分录组、posting 数量顺序、账户、符号、目标币种/precision、normalized amount、时间及 mixed leg sum）。
6. `SqlDelightImportSpineStore.kt:127-147` raw intake：`insertImportSourceRecord` 保存 `content_hash` 与 funding rule/version 字段（provenance 先例），每个 intake 恰建一个 evidence 节点；第一批在确认路径追加 normalized 总额字段（:531-569 区域，`normalizedTotalMinor` 由正式 postingSets 推导）。
7. Ledger.sq:8151 `updateP408PostingReconciliation` 为对账状态更新查询；共享 reconciliation 面 schema = v23 引入的 `22.sqm`：operation CHECK 含预留 `invalidate_link`（:7）、`posting_reconciliation.status` CHECK 枚举 :44、`evidence_link_history.state/reason` 枚举 :60-61、各表 update/delete guard 触发器 :81-99。迁移尾部 `24.sqm`，`LedgerDatabaseMigrationTest.kt:298` 断言 version==25；迁移验证命令 `:ledger-data:verifyCommonMainLedgerDatabaseMigration`。
8. 因此现状：`99@scale0 → 9900@scale2` 的确认走第一批能产成正式分录，但其 evidence 无任何 projection 权威，store 比较拿 raw `currency_precision=0` 对照 posting/request `2` 即拒（`:65-71` 路径），匹配面在这类来源上维持不可达 —— 正是 PROJECT_STATE.local.md 记载的「两批之间非 2 位来源保持 PENDING/UNRESOLVED」的现实依据。

## 2. 既有权威已定约束（逐条带出处）

1. **来源事实不动**：raw `amountMinor`/`currencyCode`/`currencyPrecision`/指纹/source row 一律原样保留；归一化只是确认装配面的派生视图（D-111 决定段；WORK_PLAN:173 raw source row/source precision/fingerprint 不变条款沿用）。
2. **禁止有损转换**：只允许等精度、乘补零与可整除除法降精度；余数/币种不符 → 类型化不可表示；算术溢出单独分类（D-111；WORK_PLAN:170）。
3. **唯一权威是显式确认**：只有明确确认创建或替换正式账目；候选带 provenance/confidence，自动逻辑不伪造（AGENTS.md 边界；DISCOVERY_DECISION_LOG.md:281-302 「不得猜测」条款）。
4. **对账语义不扩张**：matcher 字段集/窗口/基数以 D-103 批准组合为唯一基线；通道总额只诊断；reconciliation 状态不改变余额/报表（D-103:1634；ACCOUNTING_RULES.md:33）。本批不改 eligibility 谓词与五字段身份本身，只改 matcher 输入的权威供给方式（§5 V-3）。
5. **correction/successor invalidation 延期不破**（D-103:1639；D-109 O-5）：本批不得实现 link 或 projection 的失效/后继机制；projection 终态不可转、不可删，由 guard 触发器与「REJECTED 不可静默改 READY」规则共同承载（§5 V-2/V-5）。
6. **银行 parser 门不因本批隐含打开**（D-109 O-8；D-099:1540）：RL-07 验收继续为平台侧适用子集。
7. **加性迁移纪律**：新表非 `rgXX_` 前缀；既有行保持；fresh=migrated 等价可证；单事务可回滚（D-092/D-098/D-103 v22→v23 先例；23.sqm 重建保序先例）。
8. **两批之间门**（WORK_PLAN:187）：非 2 位来源在没有 projection 权威时只能是 `PENDING/UNRESOLVED`、不得产生错误 link；该约束在第二批落地后仍然成立于「未被物化」的 evidence（迁移回填只覆盖已链接 precision=2 面）。

## 3. 批次范围边界

本批只做以下六件事：(a) 新增独立追加式 projection 表及其幂等唯一键与守卫；(b) materialization 端口/方法及幂等与拒绝分类；(c) `P408EvidenceFacts` 拆分 raw vs normalized 且 matcher/store 只消费 READY projection、运行时零临时归一化；(d) 新 confirm request/fingerprint/snapshot v2 双值写入并保持 v1 可读可 replay（含 `reconciliation_request_snapshot.basis_version` 与 `evidence_link.basis_version` 两处 CHECK 自 `=1` 放宽为 `{1,2}` 的加性 schema 文本变化；行集合零改写，`import_evidence` 零改动不变）；(e) mirror 显式物化门与 O-2 成功路径同事务物化；(f) v25→v26 加性迁移与确定性回填。其余一切都不在本批：

- correction/successor invalidation 明确不在本批（link 与 projection 两面同延；D-103:1639 / persistence spec :133-136 原文语义不变）；`invalidate_link` operation 继续仅为枚举预留、无写入者。
- 银行侧真实镜像与银行 parser 不在本批（D-109 O-8；D-099:1540 门仍开）。
- Phase 5 组合根 / Android/Desktop 平台壳不动；产品 Clock/随机 ID 不引入（WORK_PLAN:195）。
- parser、spine intake、candidate、duplicate、credit/mixed 工厂语义本体不动；matcher 的 O-1..O-6 已批准语义本体不动。
- 不扩展 `import_evidence`（列、索引、唯一约束均零改动；基数纪律 P4-02 spec:187 保持）。
- report projection 十维度、canonical oracle 比较面之外的报表语义、golden publication 均不在本批。

## 4. Table：独立追加式 evidence projection 表

**V-1 裁决点：键形状与终态模型。**

- **A. 每 evidence 单行终态（推荐）**：`evidence_projection` 一行代表一个 evidence 的唯一 projection 权威；PK `(ledger_id, projection_id)` + 幂等唯一键 `UNIQUE(ledger_id, evidence_id)`；`state ∈ {READY, REJECTED}` 均为终态，UPDATE/DELETE 被 guard 触发器无条件 ABORT（比 `posting_reconciliation` 更强：连受控 status 推进都不允许，因为本批不存在合法的状态转移函数）。重复 materialize 同输入 → 返回原行（NoChange 幂等），不同输入 → 类型化冲突拒绝零写入。权衡：极简守卫面、与延期中的 correction/successor 故事无缝衔接（未来后继机制与 link 同批授权）；代价 = 同一 evidence 若换目标账户重新表达，必须等 correction 批的后继语义，期间 REJECTED 即终态阻塞——这正是「REJECTED 不可静默改 READY」的字面落法。
- **B. 多目标并行行**：唯一键扩为 `(ledger_id, evidence_id, target_account_id, target_precision)`，一个 evidence 可同时持多份候选 projection。风险：一 evidence 多个 READY 权威破坏「一 evidence 一权威核验一处 posting」的方向感（D-103 O-4 场景基数的证据职责唯一性），为拣选歧义开孔；否决倾向。
- **C. 事件日志 newest-wins**：`(ledger_id, evidence_id, sequence)` 追加历史 + 当前指针查询。风险：需要历史序列 guard、指针维护与并发仲裁（`posting_reconciliation` 全套复杂度），而本批没有任何合法转移事件可记录——属于未经需求支持的投机面。

**推荐 A**。本批 REJECTED 行的产生边界见 V-5 裁决（不是所有拒绝都落行）。DDL 草图见附录 A；命名沿用共享面小写下划线风格（先例 `evidence_link`/`posting_reconciliation`），拟名 `evidence_projection`，rule/provenance 列格式复用 `import_source_record.funding_rule_id/funding_rule_version` 先例（SqlDelightImportSpineStore.kt:144-145）。

## 5. Materialization API 与消费门

### V-2 端口归属

- **A. application 端口 + data 层单一入口被两个调用点复用（推荐）**：application 模块新增 `P408EvidenceProjectionPort`（`materialize(request): Result`、`readProjection(ledgerId, evidenceId)` 与结果密封类型 `Accepted/NoChange/Rejected(code)`，镜像 `P408ReconciliationCommitPort` 的既有形态 P408Reconciliation.kt:97-105），实现在 ledger-data 新设 `SqlDelightEvidenceProjectionStore`。同时该 store 暴露包内事务性内部方法，供 (i) spine `commitOnce` 成功路径在同一驱动事务内调用（E 要求）与 (ii) `SqlDelightP408ReconciliationStore.confirmLink` 同事务内调用（mirror 门）。外部独立调用者只见端口。权衡：类型与拒绝码归 application 管理（可测试性、与 P408 族一致）、事务控制留在 data 层唯一事务上下文；代价 = 一个新文件 + 少量注入连线。
- **B. 仅 store 内部方法、不开 application 端口**：最少表面，但幂等语义与 READY/REJECTED 模型只能靠 data 层自测，application 规则（终态门、拒绝分类）无法脱离 SQL 验证，违背本仓 port/oracle 纪律。
- **C. 把 materialization 塞进 `P408ReconciliationCommitPort`**：复用现有端口省一类，但物化不是 reconciliation 操作（不写 request/link/history），混入会污染 `reconciliation_request.operation` 语义域并扩大 D-103 合同表面。

**推荐 A**。物化的 provenance 用 projection 自身列承载（request token 字符串 + 时间戳 + rule id/version），**不复用也不新增** `reconciliation_request`（其一 PK 属确认链，其二 `operation` CHECK 冻结三值，其三本批对 `reconciliation_request` 本表零改动——schema 文本扩权只及 `reconciliation_request_snapshot` 列集/版本门与 `evidence_link` 版本门的加性放宽（§7），不为物化开新操作值）。

### V-3 Facts 拆分与 READY 门

`P408Matcher.P408EvidenceFacts` 拆分为两段并由装配层强制来源分离：

- raw 事实（`P408RawEvidenceFacts`）：`amountMinor`、`currencyCode`、`currencyPrecision`、direction、source hash/rule 版本透传——仅供诊断、basis 快照与「projection ↔ raw 未漂移」复核。
- normalized 投影事实（`P408NormalizedProjectionFacts`）：projection identity、target account/currency/precision、归一化 minor units、READY 标记。

matcher 的五字段身份比较（sameFundingFacts 语义文本不改）只接受从 **READY projection** 装配的 facts：store 装配函数改为 projection feed（替代现 selectP408EvidenceSourceFacts 直喂 raw），raw 行永远过不了门。`confirmLink` 店内三边比较改为：request ↔ READY projection ↔ posting 精确全等，另用 projection 内嵌 raw 回声复核 source row 未漂移（`P408_SOURCE_DRIFT` 类拒绝）。缺失 projection → `UNRESOLVED` disposition / 类型化 `P408_PROJECTION_ABSENT`；REJECTED → `P408_PROJECTION_NOT_READY`；两者均零 claim/link/reconciliation 写入。**全代码库运行时零临时归一化调用点**（`normalizeSourceMinorExact` 只出现在确认装配与 materialization 两个所有权位置）。

备选（不做的事实）：让 matcher 同时收双 facts 并运行期把 raw 归一到目标精度再比——违反 WORK_PLAN:178 「禁止运行时临时归一化」明文，否决。

### V-4 失败分类码

复用既有 `P408_*` 家族风格（先例 `P408_SOURCE_FACT_MISMATCH` 等），新码冻结如下（code/severity/scope/location 登记，零新增 severity/scope；message 不稳定不比较）：

| code | 触发 | 结果 |
| --- | --- | --- |
| `P408_PROJECTION_ABSENT` | gate 命中无任何行 | unresolved，零写入 |
| `P408_PROJECTION_NOT_READY` | gate 命中 REJECTED 行 | unresolved/rejected 上界，零写入 |
| `P408_PROJECTION_STATE_CONFLICT` | 物化请求与既有行内容不一致（非幂等重放） | typed reject，零写入 |
| `P408_PROJECTION_TARGET_ACCOUNT_MISSING` | mirror 门尝试物化但无显式目标账户绑定 | typed reject，零写入 |
| `P408_PROJECTION_CURRENCY_MISMATCH` | source currency ≠ target currency | REJECTED 落行（限定见 V-5） |
| `P408_PROJECTION_AMOUNT_NOT_REPRESENTABLE` | 余数/超 18 位非零（对齐 domain `AmountNotRepresentableInCurrency`） | 同上 |
| `P408_PROJECTION_ARITHMETIC_OVERFLOW` | 乘法溢出（对齐 domain `ArithmeticOverflow`） | 同上 |
| `P408_PROJECTION_SOURCE_DRIFT` | 确认比对时 projection.raw 回声 ≠ source row 现值 | typed reject，零写入 |
| `P408_REQUEST_BASIS_VERSION_RETIRED` | 「写入恒 v2」生效后，全新 requestId 以 v1 形状/`basisVersion=1` 提交；既有 v1 行的等价 replay 不受影响 | typed reject，零写入 |

确认装配工厂内的归一化失败仍走既有 `SPINE_DOMAIN_VALIDATION_FAILED` 全事务回滚映射（D-111 #7 原样），本批不为工厂路径另开投影码。

### V-5 REJECTED 的持久性与「不可静默改 READY」

- **A. 双路径边界（推荐）**：物化有两种触发——(i) O-2/spine 成功路径：目标账户来自确认字段，归一化成功才随正式 posting 同事务提交 READY；归一化失败则整事务回滚（依 D-111 #7），**不落 REJECTED 行**；(ii) mirror/独立显式物化调用：调用方显式提供目标账户与精度，产物（READY 或 REJECTED+拒绝码）按终态模型落行。guard 触发器 + 店内检查共同保证已存在行（无论何态）永不改写：REJECTED 想变 READY 只会是 `P408_PROJECTION_STATE_CONFLICT` 零写入。权衡：audit 留痕（为何拒）与「零 claim/link/reconciliation 写入」的批界措辞精确兼容——后者 enumerates claim/link/reconciliation 三面而非 projection 自身；代价 = 两条触发路径的行为差异必须被测试矩阵逐条固化。
- **B. 所有 REJECTED 都零落行**：最纯 fail-closed，但 WORK_PLAN:177 明文要求列集携带 `READY/REJECTED` 状态与拒绝码，永久 REJECTED 变成不可持久化的幽灵状态，且独立物化 API 的拒绝原因无处审计；不推荐。
- **C. 允许受控 REJECTED→READY 受控升级**：需要状态转移函数 + history 表，正面撞上 correction/successor 延期与 V-1-A 终态模型；否决。

**推荐 A**，连同其对「同一 evidence 先 REJECTED 后换正确账户」的后果声明：在该 correction 批落地前此类证据保持终态 REJECTED、通过补充资料/新证据走新一轮 intake，而非改写旧行（与 DISCOVERY_DECISION_LOG.md:156-158 「冲突两侧都保留」精神一致）。

### V-6 Mirror 物化时机

- **A. confirmLink 时惰性同事务物化（推荐）**：mirror evidence 的 link 确认请求进入事务后，第一步就是按请求的显式目标绑定物化（或校验已有 READY 行），随后才允许 claim/snapshot/link/reconciliation 写入；缺失即物化、物化即拒绝则整体 typed reject 零写入（「必须先显式 materialize 后才可匹配」的字面落实）。配合 V-5-A(i) 的 spine 端同类物化，两个入口终点同一幂等函数。权衡：对既有 UI/调用方是一次行为收紧（不再能对未物化 evidence 直接发请求并靠 raw 比较意外成功——该路径对非 2 位来源本就不通）。
- **B. intake 时立即预物化**：intake 时目标账户尚不存在于确认字段中，只能记空壳或猜测目标——正撞「未匹配 evidence 不猜目标账户」红线；否决。

### V-7 O-2 成功路径同事务要求（无备选，合同原文照抄级锁定）

凡 spine 确认路径选择物化 projection，写入必须发生在 `commitOnce` 的同一驱动事务内，与正式 posting 原子共存亡：工厂 validated binding 通过后的定值插入，失败即整事务回滚，claim/snapshot/status/formal rows/projection 全部零残留、身份可修正重试（先例注入点族：INTAKE_AFTER_CANDIDATE / CONFIRM_AFTER_FORMAL 两点的 rollback/retry oracle，GOLDEN_TESTS.md:185/190 同格）。

## 6. Request/fingerprint/snapshot v2

**V-8 裁决点：版本方案。**

- **A. 加性双值、写入恒 v2、读取按行自适应（推荐）**：`reconciliation_request_snapshot` 经重建加入 `projection_id`、`projection_rule_id`、`projection_rule_version`、`normalized_amount_minor`、`raw_amount_minor`、`raw_currency_precision` 六列（新旧快照列全集见附录 B），同一重建把该表 `basis_version` 的 `CHECK (basis_version = 1)`（现行定义 Ledger.sq:7743；代际源 22.sqm:20）放宽为接受 `{1,2}`；新写入一律 `basisVersion == 2`，fingerprint 以 v2 规范串计算（UTF-8 `|` 分隔 ASCII 字段名、集合 token 排序去重的既有规范上加 raw 值、normalized 值、projection identity 与 rule/version 四组）；v1 行保持原 fingerprint 字符串与原列集逐列可比——equivalent replay 对本行内容全等判定、changed semantic field 类型化冲突零写入，两条路线互不可换算、不互相解释。请求构造侧放开 `basisVersion ∈ {1,2}` 并按版本分支 required-basis 集（v1 精确等于现集，v2 额外要求 projection 四组字段存在且指向 READY 行）。权衡：最小语义冲击、v1 契约字节不动；代价 = snapshot 判等代码要按行内 basis_version 分支。
- **B. 全量改写旧指纹到 v2**：一次成型无双轨，但历史 fingerprint 是已固化身份（replay/conflict 判定的比较基准，persistence spec §2），改写即破坏既有重放保证并要求重写历史 oracle 期望；否决。
- **C. 双写两版指纹并列一行**：比较器需择一权威，跨 writer 组合矩阵爆炸；否决。

**推荐 A**。`reconciliation_request.outcome/receipt` CHECK 值域 (`ACCEPTED','NO_CHANGE'`) 与 `.sqm` guard 结构不变；version 选择函数冻结为「snapshot 行自带 basis_version，写入端永远选 2」，杜绝按调用方白名单漂移。与「互不可换算」条款的对齐措辞：v1 可读可 replay 承诺只覆盖已固化行，「写入恒 v2」生效后 v1 不再是任何新请求的合法形状（V-4 表 `P408_REQUEST_BASIS_VERSION_RETIRED`），两版互不可换算、也不互相解释。`evidence_link.basis_version` 的同款 CHECK 放宽在同一迁移内经其重建对称完成（§7 步骤 3），link 行数据零变动、追加式纪律不变。

## 7. v25→v26 additive migration（新文件 `25.sqm`）

单事务顺序（全部步骤在一个 migration 事务内，late-stage 注入回滚先例 LedgerDatabaseMigrationTest late 系列；受影响的两处版本门现行定义为 Ledger.sq:7743/`:7777`，代际源 22.sqm:20/:34）：

1. `CREATE TABLE evidence_projection` + 两条 guard 触发器 + `(ledger_id, state)` 查询索引（附录 A DDL 原样）。
2. 重建 `reconciliation_request_snapshot`：加入六新列全集、旧行逐列复制、guard 触发器按 22.sqm 同名重挂，并同批把该表 `basis_version` 的 `CHECK (basis_version = 1)`（Ledger.sq:7743；代际源 22.sqm:20）放宽为接受 `{1,2}`。历史行的 raw 对与新列以 **raw := normalized 回填**：这些确认全部产生于「两边同精度相等」时代（store :65-84 精确比较成立过的行），故 raw twin == normalized twin 为确定事实，非推断。
3. 重建 `evidence_link`：把其 `basis_version` 的 `CHECK (basis_version = 1)`（Ledger.sq:7777；代际源 22.sqm:34）放宽为接受 `{1,2}`。列集、守卫语义与历史 link 行保持零变动、逐字节复制；`evidence_link_history` 等 FK 后代经 defer 模板保证重建期间存活、guard 触发器按原语义重挂。重建顺序与触发器重建遵循仓库已在用的保序模板先例（21.sqm 头注「staged rebuild + trigger re-creation」六阶段模板；24.sqm 同模板加 FK defer 保父表替换时后代行存活）：stage-copy 先行（建新表并逐列复制旧行）→ child→parent DROP → parent→child CREATE → 复制回灌 → 行数守卫 → stage 清理 → 按 22.sqm 原语义重挂触发器；明示不使用 RENAME 换位——SQLite 非 legacy 下 ALTER TABLE RENAME 会连带改写后代 FK 引用，换位语义一律由 stage 复制与原位重建承载。这是 schema 文本变化而非行改写：追加式纪律与数据行不变。
4. **确定性 READY 回填**：`INSERT ... SELECT` 遍历每条 `evidence_link` JOIN 其当前版本 posting（复用 `selectP408PostingIntegrity` 的 current-version + `ACCOUNT_TRANSFER` 资格谓词语义），对 link 双侧 currency/precision/金额已经确认期精确相等的行，产出一条 READY 行：target account/currency/precision = posting 侧，`normalized_amount_minor = abs(posting.amount_minor)`，raw twins = source 侧原值，direction_token = source 原 token，rule_id 固定为回填专用 provenance token，request_id 用固定迁移审计 owner 字符串（22.sqm `migration_seed` audit owner 先例），时间戳用迁移常量而非 Clock。断言面保证 predicate 纯函数性：fresh 构造同一数据必然插出逐列相同行。
5. **不猜**：无 link 的 evidence（未匹配、关闭、缺腿、普通收支尚未进入 matcher 面的一切来源）零行；`state='REJECTED'` 不在迁移中出现（历史没有可判定的目标拒绝——目标从未给定）。
6. 版本推进 v25→v26；`LedgerDatabaseMigrationTest` 版本断言升为 26 并复跑 verifier。

**fresh=migrated 等价测试要求**：verifier `:ledger-data:verifyCommonMainLedgerDatabaseMigration` 之外，必须有一条 populated 数据级断言——populated v25 带 v23/v24 代际 link/recon/duplicate 行一路迁到 v26，与 fresh v26 直接构造的等价形态：(i) 行面——`evidence_projection` 全列、重建后的 `reconciliation_request_snapshot` 全列（含六个新列）与 `evidence_link` 全列、以及 22.sqm 实际创建的全部七张共享表 `reconciliation_request`、`reconciliation_request_snapshot`、`evidence_link`、`posting_reconciliation`、`reconciliation_receipt`、`evidence_link_history`、`posting_reconciliation_history` 逐行相等；(ii) schema 文本面——步骤 2/3 放宽后的两处 `basis_version` CHECK 定义（接受 `{1,2}`）在 fresh 与 migrated 一致；(iii) 行集合本身零差异、零改写——两处重建是 schema 文本演化而非行级变更（先例 `freshV25EqualsMigrated...` 系列 + D-110 D2 数据级单链模式）。

## 8. 测试矩阵（WORK_PLAN:185-186 逐项 + 一项固化为可执行断言）

命名 TP-nn；新增测试归 `ledger-application` jvmTest（normalization/matcher 门）与 `ledger-data` jvmTest（store/migration/oracle/原子性）；后文登记 GOLDEN_TESTS 失败矩阵时沿用锚点引用格式。

| # | 覆盖项（WORK_PLAN 出处） | 断言核心 |
| --- | --- | --- |
| TP-01 | `99@0→9900@2` 匹配成功路径（:185） | 确认→READY projection→confirmLink 全链 ACCEPTED，link/history/reconciliation 期望行齐全，零第二笔交易 |
| TP-02 | `0.5@1`（:185） | `50@2` 精确表达；evidence-to-projection 值流逐列断言 |
| TP-03 | 等精度（:185) | raw==normalized 双值写入，replay 幂等 NoChange |
| TP-04 | 精确降精度（:185） | 整除路径 READY；不可整除对照 TP-06 |
| TP-05 | overflow（:185） | `ArithmeticOverflow` 分类；确认路径零残留可重试；独立物化路径 REJECTED+拒绝码 |
| TP-06 | 余数拒绝（:185） | `NOT_REPRESENTABLE` 分类零写入；上界 `PENDING/UNRESOLVED` 保持 |
| TP-07 | 币种不符（:185） | factory 域拒绝与 projection 码两层分别断言 |
| TP-08 | ordinary/transfer/credit/mixed 全 flow graph（:185） | 各 flow 的 O-2 成功路径物化同事务（V-7）+ projection 存在性与确定值；transfer/mirror 特别行使 matcher 面 |
| TP-09 | mixed 三 posting precision（:185） | 三分录各自 precision 目标一致表达、mixed leg sum 校验后 total 投影确定 |
| TP-10 | 端到端 rollback/retry（:186） | 注入点两处（formal 后 / projection 后）rollback 全部 owner、corrected retry ACCEPTED |
| TP-11 | materialization 幂等（:186） | 同输入重放返回原行零追加；异输入 STATE_CONFLICT 零写入 |
| TP-12 | append-only 守卫（:186） | UPDATE/DELETE 触发 ABORT；guard 触发器断言 |
| TP-13 | READY/REJECTED 门（:186） | absent→`PROJECTION_ABSENT`、rejected→`PROJECTION_NOT_READY`，两种情况下 claim/link/reconciliation 零写入 |
| TP-14 | v1 replay/v2 fingerprint（:186） | v1 旧请求字节级 replay 原结果；v2 fingerprint changed→conflict 零写入；全新 requestId 以 v1 形状/basisVersion=1 提交 → `P408_REQUEST_BASIS_VERSION_RETIRED` 类型化拒绝零写入 |
| TP-15 | snapshot 双值（:186） | v2 行六新列 raw/normalized 同存且可分别断言 |
| TP-16 | migration fresh=migrated（:186） | §7 第 6 条与文末 fresh=migrated 等价测试要求段全文 |
| TP-17 | 原子 confirm（:186） | confirmLink 单事务 claim→gate→snapshot→link→history→recon→receipt；任一步typed failure 全回滚（persistence spec §5 单事务枚举 :113-115 与 typed failure 回滚/replay/conflict 句 :127-131 语义 + projection gate 步骤并入） |
| TP-18 | **两批之间非 2 位来源门（固化为可执行断言；新增强制项）** | 无 projection 权威（迁移未覆盖或物化缺席/REJECTED）的非 2 位 source evidence：候选评估保持 `UNRESOLVED`/posting `PENDING`，零 claim、零 link、零 reconciliation effect；同时断言确有 link 的 2 位证据不受影响（防止一刀切回归）。此条此前只存在于 WORK_PLAN:187 计划文字，本批将其固定为 named test |

## 9. 验证命令（冻结候选，实施批不得缩水）

聚焦 → 全量顺序沿用仓规：`:ledger-application:jvmTest`、`:ledger-data:jvmTest`、`:ledger-data:verifyCommonMainLedgerDatabaseMigration`、Android `compileAndroidMain`（涉及 data 层 driver 装配面）、Python 全套不受影响但按 O-9 八层精神复查即可豁免重跑（如实留痕）；Windows 主机串行单 worker 限制照 CONTRIBUTING 执行；本批 Gradle 验证由实施批与 distinct verifier 执行，本文档批只做 project_docs 文档验证。

## 10. Unresolved questions / 待用户裁决

以下均为产品/架构抉择，本文只给推荐，不作批准宣告；批准语汇保留给用户与评审闭环。归属声明（评审闭合，随本文批准一并生效）：V-2/V-3/V-4 属实施工程面裁决，不单列用户选项登记；V-7 为无备选豁免条款，维持原状、不属裁决项。以下仅列需要用户显式裁决的产品/架构面：

1. **UQ-1（对应 V-1）幂等唯一键与终态模型**：推荐「每 evidence 单行、READY/REJECTED 双终态、零转移」。后果：换目标账户重新表达须等未来 correction 批。请确认是否接受（或选 B/C 中任一替代语义）。
2. **UQ-2（对应 V-5）REJECTED 落行边界**：推荐仅「显式目标账户的独立/mirror 物化」可落 REJECTED 行，O-2 确认路径失败全回滚不落行。若用户希望「所有拒绝都落行审计」，需改为独立短事务记录模式，并接受与零写入措辞的解释成本。
3. **UQ-3（对应 V-6）物化传播广度**：WORK_PLAN 文字围绕 mirror/transfer 面；推荐在 spine 成功路径对**全部六 kind** 的确认统一物化（uniform、防第二次迁移波），matcher 消费面维持现 eligibility 不动。注（评审闭合）：V-6 的 mirror 物化时机——confirmLink 同事务首步惰性物化、请求须携显式目标绑定——是对 WORK_PLAN:180 字面要求的工程解读，随本文批准一并生效，不改变该条款语义幅度。请确认接受广播或收窄至 transfer-only。
4. **UQ-4（对应 V-8）v2 编码与 basis version 分支**：推荐双值加性方案与「写入恒 v2」。请确认旧 fingerprint 保守主义（方案 A）为裁决基调。
5. **UQ-5（附录 A/B）表名/列名字面冻结**：`evidence_projection`、六新列命名、回填 provenance token 字面值（`rule_id='p408_evidence_projection_backfill_v26'` 类）随批准一并敲定，实施批不得由实现默认推断（D-096:1435）。
6. **UQ-6 后续登记位**：本批测试锚点将来登记 GOLDEN_TESTS「P4-09 收口失败矩阵」RL-07 行的方式与 TP-18 的矩阵归属（建议作为语义维度行附注而非新列）。

## Appendix A. evidence_projection DDL 草图（实施批以评审后文本为准）

```sql
CREATE TABLE evidence_projection (
  ledger_id TEXT NOT NULL,
  projection_id TEXT NOT NULL,
  evidence_id TEXT NOT NULL,
  source_id TEXT NOT NULL,
  source_hash TEXT NOT NULL,                -- echoes import_source_record.content_hash
  target_account_id TEXT NOT NULL,
  currency_code TEXT NOT NULL,
  currency_precision INTEGER NOT NULL CHECK (currency_precision >= 0),
  raw_amount_minor INTEGER NOT NULL CHECK (raw_amount_minor >= 0),
  raw_currency_precision INTEGER NOT NULL CHECK (raw_currency_precision >= 0),
  normalized_amount_minor INTEGER NOT NULL CHECK (normalized_amount_minor >= 0),
  direction_token TEXT NOT NULL CHECK (direction_token IN ('in', 'out')),
  state TEXT NOT NULL CHECK (state IN ('READY', 'REJECTED')),
  rejection_code TEXT,                      -- NOT NULL iff state='REJECTED'
  rule_id TEXT NOT NULL,
  rule_version INTEGER NOT NULL CHECK (rule_version >= 1),
  materialization_request_id TEXT NOT NULL, -- provenance token; no FK to reconciliation_request in this batch
  materialized_at TEXT NOT NULL,
  PRIMARY KEY (ledger_id, projection_id),
  UNIQUE (ledger_id, evidence_id),          -- idempotent key per V-1-A
  FOREIGN KEY (ledger_id, evidence_id) REFERENCES import_evidence(ledger_id, evidence_id)
    DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY (ledger_id, source_id) REFERENCES import_source_record(ledger_id, source_id)
    DEFERRABLE INITIALLY DEFERRED,
  FOREIGN KEY (target_account_id) REFERENCES account(account_id) -- exact FK shape verified against fresh schema at implementation review
);
CREATE INDEX evidence_projection_by_state ON evidence_projection(ledger_id, state);
CREATE TRIGGER evidence_projection_guard_update BEFORE UPDATE ON evidence_projection BEGIN
  SELECT RAISE(ABORT, 'cannot update evidence projection'); END;
CREATE TRIGGER evidence_projection_guard_delete BEFORE DELETE ON evidence_projection BEGIN
  SELECT RAISE(ABORT, 'cannot delete evidence projection'); END;
```

要点：本批不加 history 表（无合法转移事件，见 V-1）；`rejection_code` 与 state 的互斥一致性 CHECK、account FK 的复合键形状，均留给实施批规格终稿按 fresh schema 复核后登记。

## Appendix B. reconciliation_request_snapshot 新列全集（相对现行的增量）

```text
projection_id TEXT              -- v2 only
projection_rule_id TEXT         -- v2 only
projection_rule_version INTEGER -- v2 only
normalized_amount_minor INTEGER -- v2 only
raw_amount_minor INTEGER        -- raw twin; backfilled := legacy amount_minor for historical rows
raw_currency_precision INTEGER  -- raw twin; backfilled := legacy currency_precision for historical rows
```

v1 行的历史回填：legacy `amount_minor`/`currency_precision` 保持原义并被确认为 raw 真值；六个新列中前四个为 v2 写入专有（历史行 NULL），raw twins 按同值回填以满足 §7 步骤 2 的确定性；任何口径下的浮点转换都不出现。

> 扩注（评审闭合修订）：随本重建一并把 `reconciliation_request_snapshot.basis_version` 的 `CHECK (basis_version = 1)`（Ledger.sq:7743；代际源 22.sqm:20）放宽为接受 `{1,2}`；`evidence_link.basis_version` 的同款 CHECK（Ledger.sq:7777；代际源 22.sqm:34）在同一次迁移内经 `evidence_link` 重建做对称放宽（§7 步骤 3）。两处均为 schema 文本变化，行零改写、追加式纪律不变。来源精度双列统一命名为 `raw_currency_precision`（快照与投影两侧同名，附录 A 已同步），依据既有 `currency_precision` 列名惯例，不再保留 `raw_precision` 变体。

## 11. 边界断言

- 本文档为 draft 冻结候选：在用户批准 + 独立评审闭环之前，不授予任何实施权限；approved 后实施仍在独立 worktree、单一 bounded writer、独立规格/质量评审、distinct verifier 与主代理最终验收之下进行（AGENTS.md 变更路由）。
- 真实金额/时间/锚点注册值不复制入文；示例全部匿名合成（P405FIX-QUAL-001 先例）；`.external/` 只读未触碰。
- 实施批必须保持本规格冻结的目标账户取值途径（确认字段显式引用）、归一化白名单运算、终态模型、v1 兼容性、迁移谓词与 TP-01..TP-18 覆盖面；任何变更即重开评审门（batch-discipline 句式沿 persistence spec :161-165）。
