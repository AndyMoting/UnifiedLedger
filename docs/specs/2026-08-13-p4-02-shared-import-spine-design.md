# P4-02 Shared Import Spine 实施批冻结规格（轮 A）

**Status:** approved — 规格/质量双评审 0 BLOCKER / 0 MAJOR、独立 verifier 全绿、主代理于 2026-08-13 接受并放行实施；P4-02 实施批（轮 B）已按本规格实现并验证。本文件自此约束实现，不再单独授权。

**Scope:** 冻结 P4-02 实施批（D-098 交付形态 A「共享 spine 最小实现」）的匿名 fixtures/操作/预期结果、receipt 形状、spine 诊断码、状态迁移矩阵、provenance 字段命名、哈希与编码、DDL 与迁移、端口签名与测试计划。本文件只冻结规格，不含实现；实现只允许在本规格按项目评审拓扑冻结后，由后续实施批（轮 B）在独立 worktree 执行。

## Authority And Boundary

本规格全部条款对齐 D-098（docs/DECISIONS.md:1475-1528，用户 2026-08-13 接受，四选项 A/A/A/A），及其引用条款：

- D-098:1483-1489 领域 1 raw identity；:1491-1496 领域 2 retention/provenance；:1498-1503 领域 3 candidate lifecycle；:1505-1511 领域 4 atomic confirmation；:1513-1520 范围与延后；:1522 D-096 处置。
- D-097:1443-1473（normalized source、typed diagnostics taxonomy、安全 location 边界）；D-092:1325-1340（方案 A 共享链、非 rgXX_ 前缀、竖井冻结）；D-073:891-903（intake 只建来源/证据/候选、candidate_not_pending、永不自动确认）；D-077:951（confirmed 状态本身不授权正式分录）；D-081:1007（confirmed_at 只来自显式确认事实）；D-065（stale 整体拒绝零写入，经 D-098:1510 引用）。
- 形状先例：ledger-data 的 Ledger.sq:473-561（RG-04 import 表族、request_id/候选绑定）、:516-532（rg04_import_confirmation_snapshot 决策快照形状）、:1801-1822（claim ON CONFLICT DO NOTHING + changes()）、:6073-6091（rg08 source record 哈希列形状）、:6764-6773（共享 formal_transaction_metadata）；19.sqm（六阶段迁移、fail-closed 数据守卫、不可变守卫模板）。
- 行为先例：ledger-application 的 ConfirmedManualExpense.kt:83-112（commitOnce 契约）、:133-141（ID 在原子首请求 callback 内惰性分配）；ledger-data 的 SqlDelightRg04ImportStore.kt:38-44（claim-first 顺序）、:94-185（typed rollback、失败零残留）、:295-343（等价重放/冲突判定）；Rg04ImportLifecycleEndToEndTest.kt（并发、失败注入、append-only、reopen 测试模式）；Rg09Fingerprint.kt:7-10、:116-147（string-only JCS（JSON Canonicalization Scheme, RFC 8785）子集与字符串转义先例）、:149-240（SHA-256 原语）。
- 边界约束：ROADMAP 阶段 4 前置条款「跨场景复用只限严格解析、明确确认、request snapshot 与正式账务链；不得提前泛化专项 DTO、表或业务 owner」（docs/DECISIONS.md:1331）。`.external/` 只读；golden 冻结契约与 rgXX 竖井 schema 零改动（D-098:1520、D-092:1335-1336）。

术语：`raw identity` = D-098 组合确定性身份 `(input ref, record ordinal)`；`spine` = D-092 共享导入链 source → evidence → candidate → confirmation → 正式账务；`本批` = P4-02 实施批；`决策端口` = 本批 confirmCandidate 端口（confirm 与 reject 两种决策）；`隐藏费用账户` = 二级支出分类对应的隐藏费用账户（docs/ACCOUNTING_RULES.md:92）。

## 1. 匿名 fixtures 集

全部输入为合成、来源中立、provider-neutral 数据；不含真实 provider、真实个人数据、绝对路径、原文件名、worksheet 名、header、整行或可识别交易。所有 ID 为合成字符串。金额一律精确整数最小货币单位。

### 1.1 基础状态

- ledger：`ledger-p402`（单账本）。
- catalog：自有真实资产账户 `account-asset-a`（CNY）；二级支出分类 `category-food`；二级收入分类 `category-salary`。
- 输入引用（opaque synthetic input ref；非空、无控制字符、长度 ≤ 256）：`batch-p402-a`、`batch-p402-b`、`batch-p402-c`。
- 合同版本 `contract_version = 1`；record_kind = `ordinary_flow_source`。

### 1.2 来源记录 R1–R3、碰撞变体 R1' 与收入记录 R5

| 记录 | input_ref / ordinal | amount_minor (currency, precision) | occurred_at | direction_token | status_token | completeness |
| --- | --- | --- | --- | --- | --- | --- |
| R1 | batch-p402-a / 0 | 12850 (CNY, 2) | 2026-08-01T12:30:00+08:00 | out | settled | valid_complete |
| R2 | batch-p402-a / 1 | 1000000 (CNY, 2) | 2026-08-05T09:00:00+08:00 | in | settled | valid_complete |
| R3 | batch-p402-b / 0 | 4500 (CNY, 2) | 2026-08-06T18:45:00+08:00 | out | 缺失 | valid_incomplete |
| R1' | batch-p402-a / 0 | 12851 (CNY, 2) | 2026-08-01T12:30:00+08:00 | out | settled | valid_complete |
| R5 | batch-p402-c / 0 | 888800 (CNY, 2) | 2026-08-08T10:00:00+08:00 | in | settled | valid_complete |

R1' 与 R1 拥有同一 raw identity（batch-p402-a, 0）但内容不等价（金额差 1 分），只用于碰撞/并发变体。R3 是 D-097:1447/1453 边界内的 valid_incomplete：可靠来源事实已形成但 status 事实不足；其金额/时间有效，不构成 record error。R5 是普通收入记录，用于收入确认 fixture（O-28~O-30）。

候选冻结值：candidate_kind = `ordinary_flow`；rule = `ordinary_flow_source`、rule_version = 1（§5）；confidence 为规范十进制文本常量，valid_complete → `1.00`、valid_incomplete → `0.50`；requires_confirmation 恒为单行 `formal_transaction_creation`（产品契约 v1 统一形状；补全契约延后）。content_hash 按 §6 规则由入站记录一次性计算。

本批合成 ID 常量（由轮 B 的确定性测试 IdSource 依序产出并钉死）：source-a / evidence-a / candidate-a（C1）、source-b / evidence-b / candidate-b（C2）、source-c / evidence-c / candidate-c（C3）、source-e / evidence-e / candidate-e（C5）；status_history status_id：status-a-1 / status-a-2（C1）、status-b-1 / status-b-2（C2）、status-c-1（C3）、status-e-1 / status-e-2（C5）；confirmation-a / tx-a（O-05）与 confirmation-b / tx-b（O-30）；失败注入与领域失败的废弃批次（attempt-1 系列，零持久化痕迹）在 O-23/O-24/O-29 中钉死；posting/version 常量随 O-05/O-30 断言钉死。

### 1.3 操作集（O-01…O-30）

`Δ` 表示该操作自身的新增行数（request/source/evidence/candidate/status_history/decision_snapshot/confirmation/receipt 与 formal transaction/version/posting）。`零写入` 表示该操作不留下任何新行（包括 request claim 行）。状态迁移见 §4，receipt 见 §2。C1/C2/C3/C5 分别为 R1/R2/R3/R5 的候选。

intake（source + evidence + 候选创建，同事务；执行顺序见 §8 claim-first）：

- O-01 intake R1 @ req-a-intake → accepted。Δ：request+1、source+1、evidence+1、candidate+1（C1）、status_history+1（C1: seq1 pending_confirmation, class creation）、receipt+1；formal 0/0/0。returned：[{source,source-a},{evidence,evidence-a},{candidate,candidate-a}]。
- O-02 同请求等价重放 O-01 → no_change（reason `equivalent_replay`）。Δ 全零；receipt 与 O-01 逐值相同；returned 与 O-01 相同。
- O-03 intake R1 @ req-a-intake-2（不同请求、内容等价）→ no_change。Δ 全零（不写 request 行）；returned = O-01 的既有引用；无 receipt 对象（raw identity 幂等路径，§2）。
- O-04 intake R1' @ req-a-intake-3 → rejected（SPINE_IDENTITY_COLLISION）。Δ 全零。
- O-10 intake R2 @ req-b-intake → accepted（C2 seq1 pending_confirmation）。Δ 同 O-01 形状。
- O-15 intake R3 @ req-c-intake → accepted（C3 seq1 incomplete, class creation）。Δ 同 O-01 形状；evidence.observed_at = R3.occurred_at。
- O-28 intake R5 @ req-e-intake → accepted（C5 seq1 pending_confirmation）。Δ 同 O-01 形状。

confirm 与 reject（决策端口，§8；同请求重判走决策快照，§7）：

- O-05 confirm C1 @ req-a-confirm，decision=confirm，expectedContentHash=H(R1)，category=category-food，funding=account-asset-a，explicitConfirmedAt=2026-08-07T10:00:00+08:00 → accepted。Δ：request+1、decision_snapshot+1（confirm/R1 哈希/confirm_fields/confirmed_at）、status_history+1（C1: seq2 confirmed, class creation, status-a-2）、confirmation+1（class creation、request 引用 req-a-confirm、transaction 引用 tx-a、confirmed_at=显式值）、receipt+1；formal：transaction+1（tx-a）、version+1、posting+2（category-food 隐藏费用账户 +128.50；account-asset-a −128.50，逐币种平衡）。returned：[{confirmation,confirmation-a},{transaction,tx-a}]。ID 只在获胜 callback 内由 ImportIdSource 惰性分配一次。
- O-06 同请求等价重放 O-05 → no_change。Δ 全零；factory 不被调用；ImportIdSource 不消耗；receipt 与 O-05 逐值相同。
- O-07 confirm C1 @ req-a-confirm-2 → rejected（SPINE_CANDIDATE_NOT_PENDING）。Δ 全零。
- O-08 confirm @ req-a-confirm、category=category-other（其余同 O-05）→ rejected（SPINE_REQUEST_IDENTITY_CONFLICT）。Δ 全零（判定点：claim 失败后决策快照字段比对，§8）。
- O-09 confirm @ req-a-confirm、expectedContentHash≠H(R1) → rejected（SPINE_STALE_FINGERPRINT）。Δ 全零（哈希比对在字段比对之前，§8）。
- O-16 confirm C3 @ req-c-confirm → rejected（SPINE_CANDIDATE_INCOMPLETE）。Δ 全零。
- O-11 reject C2 @ req-b-reject，decision=reject，expectedContentHash=H(R2) → accepted。Δ：request+1、decision_snapshot+1（reject/R2 哈希/confirm_fields 为空/confirmed_at 为空）、status_history+1（C2: seq2 rejected, class status_transition, status-b-2）、receipt+1；confirmation+0；formal 0/0/0。returned：[{candidate,candidate-b}]。statusHistoryId 由 ImportStatusIdSource 在获胜路径分配一次。
- O-12 同请求等价重放 O-11 → no_change。Δ 全零；receipt 与 O-11 逐值相同（同构于 confirm 的决策快照重判）。
- O-13 reject C2 @ req-b-reject-2 → rejected（SPINE_CANDIDATE_NOT_PENDING）。Δ 全零。
- O-14 confirm C2 @ req-b-confirm → rejected（SPINE_CANDIDATE_NOT_PENDING）。Δ 全零（rejected 为人工处置终态）。
- O-17 reject C3 @ req-c-reject → rejected（SPINE_CANDIDATE_NOT_PENDING）。Δ 全零（reject 对任何非 pending ⇒ NOT_PENDING，§4）。
- O-29 confirm C5 @ req-e-confirm，decision=confirm，expectedContentHash=H(R5)，category=category-unknown（非法分类，回调前领域校验失败）→ rejected（DOMAIN_VALIDATION_FAILED）。Δ 全零（含 claim 回滚）；回调被调用并返回 DomainResult.Failure；ImportIdSource 批次-1（confirmation-b-attempt-1、status-e-2-attempt-1、tx-b-attempt-1 与对应 version/posting attempt-1）消费后无任何持久化痕迹；request identity 仍可用。
- O-30 confirm C5 @ req-e-confirm，category=category-salary（修正重试，其余同 O-29）→ accepted。Δ 同 O-05 形状；formal：transaction+1（tx-b）、version+1、posting+2（account-asset-a +8888.00；category-salary 隐藏收入账户 −8888.00，逐币种平衡）；status_history（C5: seq2 confirmed, class creation, status-e-2）；confirmation（class creation、confirmation-b、tx-b、confirmed_at=显式值 2026-08-09T09:00:00+08:00）。returned：[{confirmation,confirmation-b},{transaction,tx-b}]。ImportIdSource 批次-2 在测试中钉死。

并发与失败（线程对通过独立 SQLite 连接并发执行；busy_timeout 先例 configureSqliteConnection）：

- O-18 并发 O-01 ×2（同请求同内容）→ 1 accepted + 1 no_change；行数保持单组（1/1/1/1/1/1）。
- O-19 并发 {R1, R1'} @ req-a-race（同请求不同内容）→ 1 accepted + 1 rejected（SPINE_REQUEST_IDENTITY_CONFLICT）；失败方零写入；败者判定点在 claim 失败分支（§8 intake 顺序）。
- O-20 并发 {R1@req-a-intake-4, R1@req-a-intake-5}（不同请求、同一 raw identity、等价内容）→ 1 accepted + 1 no_change；失败方零写入（无第二条 request 行）。
- O-21 并发 O-05 ×2（同请求）→ 1 accepted + 1 no_change；ImportIdSource.next() 恰好调用一次。
- O-22 并发 {O-05, O-05@req-a-confirm-3}（不同请求同候选）→ 1 accepted + 1 rejected（SPINE_CANDIDATE_NOT_PENDING）；ImportIdSource.next() 恰好调用一次。
- O-23 intake 在 candidate 插入后注入失败 → 异常 + 事务全回滚（含 claim）；计数不变；allocateIds 在获胜路径恰好调用一次、批次-1（source-a-attempt-1、evidence-a-attempt-1、candidate-a-attempt-1、status-a-1-attempt-1）无任何持久化痕迹；同请求重试 → accepted（批次-2 = {source-a, evidence-a, candidate-a, status-a-1} 钉死）；request identity 仍可用。
- O-24 confirm 在 formal 持久化后注入失败 → 异常 + 全回滚（含 formal 行、status_history、decision_snapshot、confirmation、receipt、claim）；计数不变；每次获胜尝试恰好调用一次 ImportIdSource（批次-1 = {confirmation-a-attempt-1、status-a-2-attempt-1、tx-a-attempt-1 与对应 version/posting attempt-1}，消费后零痕迹）；同请求重试 → accepted（批次-2 = {confirmation-a, status-a-2, tx-a 与对应 version/posting} 钉死）。

守卫/重开/迁移：

- O-25 对每张新表执行 UPDATE/DELETE 及非法状态迁移（§4/§7 触发器）→ 全部 ABORT（SQLException）。
- O-26 文件库关闭重开后重放 O-05 → no_change，原 receipt；全部行恢复一致。
- O-27 v20→v21 迁移：fresh schema 与迁移后 schema 相等；既有 rg04 竖井行存活且仍可重放；迁移 fail-closed 守卫与不可变守卫生效。

## 2. Receipt 形状

持久化行 `import_receipt`（仅 accepted 持久化，§7）：

| 列 | intake | confirm | reject |
| --- | --- | --- | --- |
| request_id（操作/请求引用） | 必填 | 必填 | 必填 |
| outcome | accepted | accepted | accepted |
| source_id | 必填 | 空 | 空 |
| evidence_id | 必填 | 空 | 空 |
| candidate_id | 必填 | 必填 | 必填 |
| confirmation_id（确认行引用） | 空 | 必填 | 空 |
| transaction_id（正式效果引用） | 空 | 必填 | 空 |

- 满足 D-098:1516 最低形状（操作/请求引用、确认行引用、正式效果引用），随本批冻结。
- 等价重放必须返回与首次 accepted 同标识、同内容、逐值相等的原 receipt（D-098:1516）。no_change 与结果变体 Rejected 不持久化任何 receipt 行（失败零残留）；reject 决策成功持久化 receipt。raw identity 等价重 intake（O-03）不产生持久化 receipt（该请求零写入），结果仅携带既有引用（§1.3 O-03）。
- 应用层返回对象 `ImportReceipt{requestId, sourceId?, evidenceId?, candidateId, confirmationId?, transactionId?}` 是持久化行的逐列投影；outcome 由结果变体（accepted/no_change/rejected）承载。

## 3. Spine 诊断码

按 D-097 taxonomy 同风格（code/severity/scope/安全 location；message 不稳定、不比较；不透出底层库异常文本）追加注册。severity 值域：`fatal | conflict | invalid | stale`——其中 conflict/invalid/stale 是本规格按 D-098:1516「同风格追加注册」授权的 spine 显式扩展，fatal 与 D-097 同义。scope 值域 = D-097 集合 `input | container | structure | record | field` 加本批扩展 `request | candidate`。

| code | severity | scope | 安全 location |
| --- | --- | --- | --- |
| SPINE_IDENTITY_COLLISION | fatal | record | {input_ref, record_ordinal} |
| SPINE_REQUEST_IDENTITY_CONFLICT | conflict | request | {request_id} |
| SPINE_CANDIDATE_NOT_PENDING | invalid | candidate | {candidate_id} |
| SPINE_CANDIDATE_NOT_FOUND | invalid | candidate | {candidate_id} |
| SPINE_CANDIDATE_INCOMPLETE | invalid | candidate | {candidate_id} |
| SPINE_STALE_FINGERPRINT | stale | candidate | {candidate_id} |
| SPINE_REFERENCE_INTEGRITY_VIOLATION | invalid | candidate | {candidate_id} |
| SPINE_INTAKE_INVALID | invalid | record | {input_ref, record_ordinal} |
| SPINE_DOMAIN_VALIDATION_FAILED | invalid | candidate | {candidate_id} |

- SPINE_IDENTITY_COLLISION：同一 raw identity、内容不等价的重复 intake（D-098:1518 hard reject、fail-closed；不采用 D-095 已暂停的先到先得折叠）。
- SPINE_REQUEST_IDENTITY_CONFLICT：同 request 快照不等价（D-098:1510；含 intake 同请求不同内容，O-19）。
- SPINE_CANDIDATE_NOT_PENDING：决策端口收到 status ≠ pending_confirmation 且不适用专属分支的候选（D-073:897 先例；confirmed/rejected 与 reject 决策下的任何非 pending 皆然；confirm 决策下的 incomplete 走 SPINE_CANDIDATE_INCOMPLETE）。
- SPINE_CANDIDATE_NOT_FOUND：candidate_id 不存在。防御性注册，轮 A oracle 不触发（候选不存在由轮 B 的 FK 行为证明）。
- SPINE_CANDIDATE_INCOMPLETE：confirm 决策且 status = incomplete（§4 矩阵；补全操作延后）。
- SPINE_STALE_FINGERPRINT：决策快照 expectedContentHash ≠ 候选来源持久化 content_hash（D-065 stale 整体拒绝、零写入；判定先于其余字段比对，§8）。
- SPINE_REFERENCE_INTEGRITY_VIOLATION：候选与其来源/evidence 引用的存在性与一致性校验失败（本批「证据/绑定校验」范围，D-098:1509）。防御性注册，轮 A oracle 不触发（§8 校验步骤的 fail-closed 路径；正常路径由 FK 与 intake 同事务写入保证）。
- SPINE_INTAKE_INVALID：intake 输入结构非法（§8 校验清单）。
- SPINE_DOMAIN_VALIDATION_FAILED：confirm 回调返回 DomainResult.Failure ⇒ 类型化拒绝，零残留（含 claim 回滚）、request identity 可用（ConfirmedManualExpense.kt:100-103 先例）；消息不比较、不泄露领域 violation 之外的异常信息。
- 安全 location 只由有界 opaque 合成引用构成（input_ref、record_ordinal、request_id、candidate_id）；不含 raw value、绝对路径、原文件名、header、整行或个人标识（D-097:1461）。location 由 §8 `ImportDiagnosticLocation` 承载，每个码恰好填充其注册字段。
- no_change 的 reason_code 固定 token：`equivalent_replay`，由 NoChange 结果变体的 reasonCode 字段承载（§8）。每个 rejected 结果携带恰好一个诊断码。

## 4. 状态迁移矩阵

产品状态契约 v1：小写四值 `pending_confirmation | confirmed | rejected | incomplete`（D-098:1502；rgXX 竖井的 UPPER token 是冻结回放语料拼写，不构成产品拼写先例）。契约版本 = 1，后续扩展经显式合同修订。

| from | to | 触发（唯一合法迁移） | 历史行 operation_class | 正式效果 |
| --- | --- | --- | --- | --- |
| —（创建） | pending_confirmation | intake，valid_complete 记录 | creation | 无 |
| —（创建） | incomplete | intake，valid_incomplete 记录 | creation | 无 |
| pending_confirmation | confirmed | confirm 决策，获胜首请求且全部校验通过 | creation | 回调创建正式 transaction/version/postings |
| pending_confirmation | rejected | reject 决策（人工处置） | status_transition | 无 |
| confirmed | —（终态，本批无出边） | — | — | — |
| rejected | —（人工处置终态，本批无出边） | — | — | — |
| incomplete | —（不可直接确认；补全操作延后，本批无出边） | — | — | — |

- confirm 决策仅接受 status = pending_confirmation；status = incomplete ⇒ SPINE_CANDIDATE_INCOMPLETE；其余非 pending ⇒ SPINE_CANDIDATE_NOT_PENDING（D-098:1503 语义 + 本规格 §4 冻结）。
- reject 决策仅接受 status = pending_confirmation（D-098:1503 语义 + 本规格 §4 冻结）；任何非 pending（含 incomplete）⇒ SPINE_CANDIDATE_NOT_PENDING。
- 导入候选默认 pending_confirmation、永不自动确认（D-073:895）。
- rejected = 人工处置终态、无正式效果（D-098:1503）。incomplete 只按 D-097:1447/1453 边界进入（无效 amount/time 是 record error，不降格为 incomplete）。
- confirmed 状态本身不授权、不创建正式分录（D-077:951；正式分录只由确认回调创建）。
- status_history 追加-only：每项 {status_id, sequence ≥ 1 连续, status, request_id, operation_class}；形状参照 docs/GOLDEN_SCHEMA.md:113 的 {id, sequence, status}，request_id/operation_class 两列是本规格对 D-098:1502 引用形状的有意扩展（登记产生该状态的操作关联，与 D-098:1509 确认行操作关联同向）；DB 触发器强制 sequence 连续递增与迁移合法性（§7）；历史永不 UPDATE/DELETE。
- 补全必要事实的明确操作（incomplete 出边）留后续批次；本批矩阵已冻结其不存在。

## 5. Provenance 字段命名

选取 golden `rule` / `rule_version`（docs/GOLDEN_SCHEMA.md:130），不采用 RG-04 `provenance_rule` / `provenance_rule_version`（Ledger.sq:504-505）。理由：

1. D-098:1501 规定 provenance rule 命名沿用 golden 先例 `rule`/`rule_version`，且只允许在 golden `rule`/`rule_version` 与 RG-04 `provenance_rule`/`provenance_rule_version` 两套既有先例命名中选取、不得引入第三套；产品字段名随本实施批冻结。
2. golden v2 是产品级稳定契约词汇，共享 spine 候选最终向产品契约收敛；`rule`/`rule_version` 是该词汇的规范拼写。
3. RG-04 的 `provenance_` 前缀是竖井表内的消歧（rg04_import_candidate 混存多个 provenance 概念）；共享 `import_candidate` 的列环境无此歧义，前缀冗余。
4. 不引入第三套命名（D-098:1501）。

冻结：`import_candidate.rule TEXT NOT NULL`、`import_candidate.rule_version INTEGER NOT NULL CHECK (rule_version = 1)`；本批 rule 常量 `ordinary_flow_source`；候选同时携带 confidence（规范十进制文本）与 requires_confirmation 子表（D-098:1501；docs/ACCOUNTING_RULES.md:213）。

## 6. 哈希与编码

- 规范化：string-only JCS 子集（Rg09Fingerprint.kt:7-10 文档注释先例）。哈希输入 = 由入站记录 source facts 一次性序列化的封闭 JSON 对象：成员名按 UTF-16 code unit 升序（amount, currency_code, currency_precision, direction_token, occurred_at, record_kind, status_token）；只包含 presence=present 的事实，缺失成员整体省略；所有叶值均为 JSON 字符串——amount 为带 precision 位小数的精确十进制文本（如 "128.50"），currency_precision 为整数文本（如 "2"）；无数字叶值，因此 RFC 8785 数字规范化不参与（与 D-098:1486 一致）。字符串转义按 RFC 8785：转义 `"`、`\`，控制字符使用具名短转义 `\b \t \n \f \r`，其余 < 0x20 用 \u00XX（对齐 Rg09Fingerprint.kt:116-147），合法代理对直通、不成对代理拒绝；无空白。
- 摘要：SHA-256 over UTF-8 字节，编码为小写 `sha256:<hex>`（64 位小写十六进制，先例 docs/GOLDEN_SCHEMA.md:529）。
- 计算时机：仅在 intake 时由入站字节一次性计算并随 source record 持久化（原文不落盘、事后不重算，D-098:1486/:1493）。
- 用途边界：只作完整性/交叉校验——(a) intake 幂等等价判定：content_hash 与全部持久化 source facts 均等价才算等价（D-098:1518 双条件）；(b) 决策端口 expectedContentHash 与持久化 content_hash 比对（stale 判定）。哈希不构成身份、不参与去重、不复活 D-095 暂停条款（D-098:1493）。
- 与 golden publication canonical hash 的跨实现可比性不成立（docs/GOLDEN_SCHEMA.md:528-530）；本批规则是产品自有确定性实现。fixture 的具体摘要常量（H(R1)/H(R2)/H(R3)/H(R1')/H(R5)）随轮 B 测试以本规则独立计算并双重核对后钉死（§11 开放问题 1）。

## 7. DDL 设计与迁移 20.sqm（v20→v21）

全部新表非 rgXX_ 前缀（D-092:1329）；竖井表零改动；Ledger.sq 中与迁移同形状加入 CREATE TABLE 与查询（fresh 与 migrated schema 必须相等，19.sqm 先例）。

表（列顺序即冻结形状）：

- `import_request(ledger_id, request_id, operation CHECK (operation IN ('intake','confirm_candidate','reject_candidate')), PRIMARY KEY (ledger_id, request_id))` —— claim-first 锚点（ON CONFLICT DO NOTHING + changes()，Ledger.sq:1801-1822 先例）。
- `import_source_record(ledger_id, source_id, owner_request_id, input_ref, record_ordinal CHECK (record_ordinal >= 0), record_kind CHECK (record_kind IN ('ordinary_flow_source')), content_hash, contract_version CHECK (contract_version = 1), completeness CHECK (completeness IN ('valid_complete','valid_incomplete')), amount_minor, currency_code, currency_precision CHECK (currency_precision IS NULL OR currency_precision >= 0), occurred_at, direction_token, status_token, PRIMARY KEY (ledger_id, source_id), UNIQUE (ledger_id, input_ref, record_ordinal), UNIQUE (ledger_id, owner_request_id), FOREIGN KEY (ledger_id, owner_request_id) REFERENCES import_request)`；CHECK：valid_complete ⇒ 五类事实共六列（amount_minor、currency_code、currency_precision、occurred_at、direction_token、status_token）全非空；amount_minor 非空 ⇒ currency_code 与 currency_precision 非空（rg08_source_record 形状先例 :6073-6091）。
- `import_evidence(ledger_id, evidence_id, source_id, evidence_kind CHECK (evidence_kind IN ('source_observation')), observed_at, PRIMARY KEY (ledger_id, evidence_id), FOREIGN KEY (ledger_id, source_id) REFERENCES import_source_record)`；observed_at 与 source.occurred_at 逐字节相等（source 缺失时为 NULL）；intake 创建来源时同事务创建（D-098:1517；D-073:895）；本批无任何 evidence link。不设 UNIQUE(ledger_id, source_id)：evidence 基数属 P4-08 合同，本批只保证每次 intake 恰好创建一个 evidence 节点，不提前冻结证据基数；后续如需多证据走加性迁移。
- `import_candidate(ledger_id, candidate_id, source_id, candidate_kind CHECK (candidate_kind IN ('ordinary_flow')), confidence, rule, rule_version CHECK (rule_version = 1), PRIMARY KEY (ledger_id, candidate_id), UNIQUE (ledger_id, source_id), FOREIGN KEY (ledger_id, source_id) REFERENCES import_source_record)`；confidence 为规范十进制文本（精确十进制，无二进制浮点），值随 fixture 钉死（§1.2）。UNIQUE(ledger_id, source_id) 保留：P4-07/P4-08 如需多候选走加性迁移。
- `import_candidate_requires_confirmation(ledger_id, candidate_id, requirement_index CHECK (requirement_index >= 0), requirement CHECK (requirement IN ('formal_transaction_creation')), PRIMARY KEY (ledger_id, candidate_id, requirement_index), UNIQUE (ledger_id, candidate_id, requirement), FOREIGN KEY (ledger_id, candidate_id) REFERENCES import_candidate)`。
- `import_candidate_status_history(ledger_id, candidate_id, sequence CHECK (sequence >= 1), status_id, status CHECK (status IN ('pending_confirmation','confirmed','rejected','incomplete')), request_id, operation_class CHECK (operation_class IN ('creation','status_transition')), PRIMARY KEY (ledger_id, candidate_id, sequence), UNIQUE (ledger_id, status_id), FOREIGN KEY (ledger_id, candidate_id) REFERENCES import_candidate, FOREIGN KEY (ledger_id, request_id) REFERENCES import_request)`。
- `import_candidate_decision_snapshot(ledger_id, request_id, decision CHECK (decision IN ('confirm','reject')), candidate_id, expected_content_hash, category_id, funding_account_id, explicit_confirmed_at, PRIMARY KEY (ledger_id, request_id), FOREIGN KEY (ledger_id, request_id) REFERENCES import_request, FOREIGN KEY (ledger_id, candidate_id) REFERENCES import_candidate, CHECK (decision = 'confirm' ⇒ category_id 与 funding_account_id 非空, decision = 'reject' ⇒ 两者皆空))`（rg04_import_confirmation_snapshot 形状先例 Ledger.sq:516-532）；同请求重判的字段等价清单与顺序见 §8。
- `import_confirmation(ledger_id, confirmation_id, request_id, candidate_id, status_id, transaction_id, operation_class CHECK (operation_class IN ('creation')), confirmed_at, PRIMARY KEY (ledger_id, confirmation_id), UNIQUE (ledger_id, request_id), UNIQUE (ledger_id, candidate_id), FOREIGN KEY (ledger_id, request_id) REFERENCES import_request, FOREIGN KEY (ledger_id, candidate_id) REFERENCES import_candidate, FOREIGN KEY (ledger_id, status_id) REFERENCES import_candidate_status_history (ledger_id, status_id), FOREIGN KEY (transaction_id, ledger_id) REFERENCES ledger_transaction (transaction_id, ledger_id) DEFERRABLE INITIALLY DEFERRED)`（Ledger.sq:534-541 形状先例）；confirmed_at 仅在显式确认事实提供时非空，不得由来源时间、支付时间、operation time 或运行时时钟推导（D-098:1509、D-081:1007）。
- `import_receipt(ledger_id, request_id, outcome CHECK (outcome IN ('accepted')), source_id, evidence_id, candidate_id, confirmation_id, transaction_id, PRIMARY KEY (ledger_id, request_id), FOREIGN KEY (ledger_id, request_id) REFERENCES import_request, FOREIGN KEY (ledger_id, candidate_id) REFERENCES import_candidate)`（§2 形状）。

触发器（20.sqm Stage 6；19.sqm 守卫模板）：

- 每张新表 BEFORE UPDATE / BEFORE DELETE → RAISE(ABORT)（不可变/追加-only；decision_snapshot 含内）。
- import_candidate_status_history BEFORE INSERT 两个守卫：sequence = 该候选当前 MAX(sequence)+1（无行时 =1），否则 ABORT；状态迁移合法性（无前行 ⇒ status ∈ {pending_confirmation, incomplete}；前行 pending_confirmation ⇒ 新行 ∈ {confirmed, rejected}；前行 confirmed/rejected/incomplete ⇒ ABORT 终态，§4）。

迁移 20.sqm 六阶段（沿用 19.sqm 模板；语句逐条执行、无内部事务，原子性由调用方事务保证）：

1. 创建全部新表（普通 CREATE TABLE，存在即失败 = fail-closed）。
2. 回填——本批无数据移动，本阶段为空并注释原因（纯新表，不触碰任何既有行）。
3. fail-closed 数据守卫：guard 表 `CHECK (value = 0)` + 逐表条件 INSERT——每张新表以 `(SELECT count(*)) != (SELECT 0)` 断言可查询且为空（语句失败或计数非零即插入 1 并使整个迁移原子失败）；SQLDelight 方言不支持 sqlite_master/pragma_table_info 引用，列数与结构相等由构建期 migration verifier 与 `freshSchemaAndMigratedVersionOneHaveEquivalentSchemaMetadata` 强制（19.sqm 同构）；guard 表在本阶段内创建并即删（与 19.sqm Stage 3 同构）。
4. 分阶段重建——本批无既有表重命名/重建，本阶段为空；竖井表零改动以 guard 表 `CHECK (value = 0)` + 恒假 count 断言（`< (SELECT 0)`）登记各 rg04 竖井 owner 仍可查询。
5. 删除——本批无既有表删除，本阶段为空并注释原因（19.sqm Stage 5 删除 rg11/12 私表的对应位置）。
6. 不可变/追加-only 触发器（上表全部守卫）。

## 8. 端口签名与模块归属

模块归属：`ledger-domain` 本批零改动（确认回调复用既有 `createAssetPaidOrdinaryExpense` / `createAssetReceivedOrdinaryIncome`，OrdinaryExpense.kt / OrdinaryIncome.kt，逐字段明确、不推断，D-098:1509）；新类型与端口全部落在 `ledger-application`（RequestId/ConfirmationId 先例在 ConfirmedManualExpense.kt）；持久化实现在 `ledger-data`；无产品 Clock 端口、无产品随机 ID 算法（D-098:1519）。

`ledger-application`（类型名与签名为本批冻结契约）：

```kotlin
data class ImportRequestId(val value: String)
data class ImportSourceId(val value: String)
data class ImportEvidenceId(val value: String)
data class ImportCandidateId(val value: String)
data class ImportConfirmationId(val value: String)
data class ImportStatusHistoryId(val value: String)

data class ImportRawIdentity(val ledgerId: LedgerId, val inputRef: String, val recordOrdinal: Int)
data class ImportRequestIdentity(val ledgerId: LedgerId, val requestId: ImportRequestId)

enum class ImportCompleteness { VALID_COMPLETE, VALID_INCOMPLETE }
data class ImportSourceFacts(
    val amountMinor: Long, val currencyCode: String, val currencyPrecision: Int,
    val occurredAt: String, val directionToken: String, val statusToken: String?,
)
data class ImportIntakeRequest(
    val ledgerId: LedgerId, val requestId: ImportRequestId,
    val inputRef: String, val recordOrdinal: Int,
    val facts: ImportSourceFacts, val completeness: ImportCompleteness,
)
data class ImportIntakeSnapshot(
    val ledgerId: LedgerId, val identity: ImportRawIdentity,
    val facts: ImportSourceFacts, val completeness: ImportCompleteness,
)

enum class ImportCandidateDecision { CONFIRM, REJECT }
data class ImportConfirmFields(val categoryId: CategoryId, val fundingAccountId: AccountId)
data class ImportCandidateDecisionSnapshot(
    val ledgerId: LedgerId, val candidateId: ImportCandidateId,
    val decision: ImportCandidateDecision, val expectedContentHash: String,
    val explicitConfirmedAt: String?, val confirmFields: ImportConfirmFields?,
)
data class ImportCandidateConfirmRequest(
    val identity: ImportRequestIdentity, val candidateId: ImportCandidateId,
    val expectedContentHash: String, val explicitConfirmedAt: String?,
    val categoryId: CategoryId, val fundingAccountId: AccountId,
)
data class ImportCandidateRejectRequest(
    val identity: ImportRequestIdentity, val candidateId: ImportCandidateId,
    val expectedContentHash: String,
)

data class ImportReceipt( // §2 持久化行投影
    val requestId: ImportRequestId, val sourceId: ImportSourceId?, val evidenceId: ImportEvidenceId?,
    val candidateId: ImportCandidateId, val confirmationId: ImportConfirmationId?, val transactionId: TransactionId?,
)
data class ImportDiagnosticLocation( // §3 注册表；每个码恰好填充其注册字段，其余为 null
    val inputRef: String?, val recordOrdinal: Int?,
    val requestId: ImportRequestId?, val candidateId: ImportCandidateId?,
)
sealed interface ImportDiagnostic {
    val code: String; val severity: String; val scope: String; val location: ImportDiagnosticLocation
}
enum class ImportReturnedIdKind { SOURCE, EVIDENCE, CANDIDATE, CONFIRMATION, TRANSACTION }
data class ImportReturnedId(val kind: ImportReturnedIdKind, val id: String)
sealed interface ImportIntakeResult {
    data class Accepted(val receipt: ImportReceipt, val returnedIds: List<ImportReturnedId>) : ImportIntakeResult
    // 同请求等价重放（O-02）返回原 receipt；raw identity 幂等路径（O-03）零写入、无 receipt 对象（receipt = null）
    data class NoChange(val returnedIds: List<ImportReturnedId>, val receipt: ImportReceipt?,
                        val reasonCode: String) : ImportIntakeResult // reasonCode 固定 "equivalent_replay"
    data class Rejected(val diagnostic: ImportDiagnostic) : ImportIntakeResult
}
sealed interface ImportCandidateDecisionResult {
    data class Accepted(val receipt: ImportReceipt, val returnedIds: List<ImportReturnedId>) : ImportCandidateDecisionResult
    data class NoChange(val receipt: ImportReceipt, val reasonCode: String) : ImportCandidateDecisionResult // 等价重放返回原 receipt（D-098:1516）；reasonCode 固定 "equivalent_replay"
    data class Rejected(val diagnostic: ImportDiagnostic) : ImportCandidateDecisionResult
}

data class ImportIntakeIds(val sourceId: ImportSourceId, val evidenceId: ImportEvidenceId,
                           val candidateId: ImportCandidateId, val statusHistoryId: ImportStatusHistoryId)
fun interface ImportIntakeIdSource { fun next(): ImportIntakeIds }
data class ImportFormalIds(val transactionId: TransactionId, val versionId: TransactionVersionId,
                           val postingSetId: PostingSetId, val postingIds: List<PostingId>)
data class ImportCommitIds(val confirmationId: ImportConfirmationId, val statusHistoryId: ImportStatusHistoryId,
                           val formalIds: ImportFormalIds)
fun interface ImportIdSource { fun next(): ImportCommitIds } // 仅在获胜首请求 callback 内调用（ConfirmedManualExpense.kt:133-141）
fun interface ImportStatusIdSource { fun next(): ImportStatusHistoryId } // reject 获胜路径的状态行 ID

// store 在决策路径从持久化来源行（import_source_record）解析以下六列传入确认回调；
// 不接受调用方自填经济事实。confirm 只解析 pending（valid_complete）来源，statusToken 实际非空，
// 可空仅与 import_source_record 列形状一致。
data class ImportResolvedSourceFacts(
    val amountMinor: Long, val currencyCode: String, val currencyPrecision: Int,
    val occurredAt: String, val directionToken: String, val statusToken: String?,
)
data class ImportFormalCommit(
    val confirmationId: ImportConfirmationId, val statusHistoryId: ImportStatusHistoryId,
    val transaction: FormalTransaction,
)
fun interface ImportCandidateFormalFactory {
    fun create(resolved: ImportResolvedSourceFacts, ids: ImportCommitIds): DomainResult<ImportFormalCommit>
}

fun interface ImportIntakeCommitPort {
    fun commitIntake(identity: ImportRequestIdentity, snapshot: ImportIntakeSnapshot,
                     allocateIds: () -> ImportIntakeIds): ImportIntakeResult
}
interface ImportCandidateCommitPort {
    // 勘误登记（2026-08-13 轮 B）：原文 `fun interface` 为笔误——Kotlin 不允许双抽象方法的
    // fun interface；实现为两方法 interface，方法签名零改动。
    // confirmCandidate(identity, snapshot) 确认端口；commitOnce 语义（ConfirmedManualExpense.kt:89-112）
    fun commitOnce(identity: ImportRequestIdentity, snapshot: ImportCandidateDecisionSnapshot,
                   createFormalTransaction: (resolved: ImportResolvedSourceFacts) -> DomainResult<ImportFormalCommit>): ImportCandidateDecisionResult
    fun commitRejectOnce(identity: ImportRequestIdentity, snapshot: ImportCandidateDecisionSnapshot,
                         allocateStatusId: () -> ImportStatusHistoryId): ImportCandidateDecisionResult
}

class ExecuteImportIntake(
    private val commitPort: ImportIntakeCommitPort,
    private val idSource: ImportIntakeIdSource,
    private val fingerprint: ImportContentFingerprint,
) { fun execute(request: ImportIntakeRequest): ImportIntakeResult }
class ConfirmImportCandidate(
    private val commitPort: ImportCandidateCommitPort,
    private val idSource: ImportIdSource,
    private val createFormalTransaction: ImportCandidateFormalFactory,
) { fun execute(request: ImportCandidateConfirmRequest): ImportCandidateDecisionResult }
class RejectImportCandidate(
    private val commitPort: ImportCandidateCommitPort,
    private val statusIdSource: ImportStatusIdSource,
) { fun execute(request: ImportCandidateRejectRequest): ImportCandidateDecisionResult }
```

- 用例装配（冻结）：ExecuteImportIntake 由 ImportIntakeRequest 组装 ImportIntakeSnapshot（identity = (ledgerId, inputRef, recordOrdinal)），校验通过后 `commitPort.commitIntake(identity, snapshot) { idSource.next() }`。ConfirmImportCandidate 由 ImportCandidateConfirmRequest 组装 decision=CONFIRM 快照（confirmFields = (categoryId, fundingAccountId)），`commitPort.commitOnce(identity, snapshot) { resolved -> createFormalTransaction.create(resolved, idSource.next()) }`（ID 惰性分配仅发生在获胜 callback 内，ConfirmedManualExpense.kt:133-141 先例）。RejectImportCandidate 由 ImportCandidateRejectRequest 组装 decision=REJECT 快照（confirmFields 为 null），`commitPort.commitRejectOnce(identity, snapshot) { statusIdSource.next() }`。
- 端口实现强制规则（契约文本随批冻结，对齐 ConfirmedManualExpense.kt:90-106）：identity 与 snapshot 同 ledger；首请求回调至多一次；DomainResult.Failure ⇒ 类型化拒绝（SPINE_DOMAIN_VALIDATION_FAILED）、零残留（含 claim）、request identity 可用；成功 all-or-nothing；等价重放不调回调、不覆盖既有正式交易。
- confirm 校验顺序（D-098:1509；SqlDelightRg04ImportStore.kt:94-135 先例、产品相对收紧，对账前置校验步骤留 P4-08 不实现）：claim（decision=confirm_candidate）→ claim 失败 ⇒ 重读已存 import_candidate_decision_snapshot 做同请求重判：expected_content_hash 与 source.content_hash 比对在前（不等 ⇒ SPINE_STALE_FINGERPRINT）；decision/candidate_id/category_id/funding_account_id/explicit_confirmed_at 全等价 ⇒ NoChange 原 receipt 原子重放；任一其余字段不等 ⇒ SPINE_REQUEST_IDENTITY_CONFLICT（零写入）→ claim 成功 ⇒ 候选存在（否则 SPINE_CANDIDATE_NOT_FOUND）→ status 校验（incomplete ⇒ SPINE_CANDIDATE_INCOMPLETE；其余非 pending ⇒ SPINE_CANDIDATE_NOT_PENDING）→ 快照 expectedContentHash 与 source.content_hash 比对（不等 ⇒ SPINE_STALE_FINGERPRINT）→ 证据/绑定校验（候选与其 source/evidence 引用存在且一致，否则 SPINE_REFERENCE_INTEGRITY_VIOLATION）→ 回调 createFormalTransaction(resolved)（resolved 由 store 从持久化来源行解析）→ DomainResult.Failure ⇒ SPINE_DOMAIN_VALIDATION_FAILED 零残留 → 成功 ⇒ 持久化决策快照 → status_history confirmed（seq+1, class creation）→ confirmation 行（class creation、request 引用、transaction 引用）→ receipt，同一事务。经济时间由持久化来源事实（ImportResolvedSourceFacts）显式传入确认回调，不由 Clock 推导（docs/ARCHITECTURE.md:67）。
- reject 校验顺序（与 confirm 同构，O-12）：claim（decision=reject_candidate）→ claim 失败 ⇒ 重读已存决策快照：hash 比对在前（不等 ⇒ SPINE_STALE_FINGERPRINT），decision/candidate_id/expected_content_hash/category_id/funding_account_id/explicit_confirmed_at 全等价 ⇒ NoChange 原 receipt，任一字段不等 ⇒ SPINE_REQUEST_IDENTITY_CONFLICT → claim 成功 ⇒ 候选存在 → status = pending_confirmation（任何非 pending 含 incomplete ⇒ SPINE_CANDIDATE_NOT_PENDING）→ 证据/绑定校验 → allocateStatusId（仅在获胜路径）→ 持久化决策快照 → status_history rejected（seq+1, class status_transition）→ receipt，同一事务；无正式效果、无 confirmation 行。
- intake 校验清单（全部前置、零写入；SPINE_INTAKE_INVALID 由用例在端口调用前判定，端口按 fail-closed 再防御一次）：ledger 一致；input_ref 非空、无控制字符、≤256；record_ordinal ≥ 0；currency_precision ≥ 0；present 的 token 非空；completeness 合法且 valid_complete ⇒ 五类事实六列齐全、valid_incomplete ⇒ 至少一项事实 present。
- intake 执行顺序（claim-first，对齐 SqlDelightRg04ImportStore.kt:38-44）：输入校验 → content_hash 由入站字节一次性计算 → claim request（ON CONFLICT DO NOTHING + changes()）→ claim 失败 ⇒ 重读已存 request 绑定（owner_request_id）的 source 行做 hash+facts 双等价比对（等价 ⇒ NoChange 原 receipt 零写入；不等价 ⇒ SPINE_REQUEST_IDENTITY_CONFLICT 零写入；O-19 败者判定点在此分支）→ claim 成功 ⇒ raw identity 查重：source 已存在 ⇒ hash+facts 双等价 ⇒ NoChange 零写入（事务回滚含 claim）、不等价 ⇒ SPINE_IDENTITY_COLLISION hard reject 零写入；source 不存在 ⇒ allocateIds 仅在获胜路径调用 → 写 source/evidence/candidate/status_history/receipt 同事务；并发同身份同内容后到者撞 UNIQUE(ledger_id, input_ref, record_ordinal) ⇒ 事务回滚后重读重判（等价 ⇒ NoChange、不等价 ⇒ SPINE_IDENTITY_COLLISION，均零写入）。
- 确定性测试端口：ImportIdSource / ImportIntakeIdSource / ImportStatusIdSource 的测试实现为计数/序列源（Golden 路径注入冻结 ID 与文本时间保持既有惯例；本批不引入产品算法，D-098:1519）；ImportFormalIds 由测试 IdSource 产出并由确认回调映射到领域工厂的 ID 类型（AssetPaidOrdinaryExpenseIds / AssetReceivedOrdinaryIncomeIds 对应形状）；确认回调在测试中可替换为确定性 factory；ImportContentFingerprint 为纯函数，jvmTest 直接断言摘要。
- 哈希实现：`ImportContentFingerprint`（canonical 序列化 + SHA-256），沿用 Rg09Fingerprint 的 string-only JCS 子集转义与 SHA-256 原语语义；不改变 Rg09Fingerprint 任何字节行为（原语抽取与否属实现细节，§11）。摘要计算读取登记（2026-08-13 轮 B）：ExecuteImportIntake 的 `fingerprint.digest` 调用是 intake 边界的 canonicalization fail-closed 校验（预计算值不传递），持久化摘要由 store 在 intake 时从 snapshot facts 一次性计算并落盘（同一纯函数，值恒等）。

## 9. 测试计划

jvmTest（ledger-data 与 ledger-application；模式沿用 Rg04ImportLifecycleEndToEndTest：in-memory + 文件库、CountDownLatch 并发、failure injector、direct SQL 守卫断言、reopen）。

- T-01..T-17 及 T-28..T-30：O-01..O-17 及 O-28..O-30 逐操作断言 outcome、returned ids、计数 Δ、状态迁移、receipt 逐值（§1.3 表格是 oracle）。
- T-18 哈希确定性：同一 R1 多次 digest 相同；R1 与 R1' 摘要不同；成员缺失（R3 status 省略）可复现；转义向量（引号/反斜杠/具名短转义 \b \t \n \f \r、\u00XX 控制字符/代理对）。
- T-19 intake 幂等：等价（哈希+事实双条件）⇒ 零写入；哈希等价但事实不等价 ⇒ 碰撞（双条件，D-098:1518）。
- T-20 并发：O-18..O-22（含失败方零残留的行级计数与 IdSource 消耗计数）。
- T-21 失败注入：O-23/O-24（全回滚、identity 可用、重试成功、每个获胜尝试恰好一次 IdSource、批次-1 零痕迹、批次-2 钉死）。
- T-22 append-only 守卫：O-25（每表 UPDATE/DELETE 与非法状态迁移全部 ABORT；direct SQL 断言）。
- T-23 reopen/replay：O-26。
- T-24 迁移：O-27（fresh = migrated schema 经真实入口 `LedgerDatabaseMigrationTest.freshSchemaCreatesEveryLedgerDataTableAtVersionTwenty` 验证——轮 B 需登记该既有断言从 v20 到 v21 的版本涟漪更新；rg04 竖井行存活并重放）。
- T-25 共存：同一账本先跑 RG-04 竖井语料再跑 spine 操作（或反之），互不引用、互不污染（D-092:1335）。
- T-26 应用层：ExecuteImportIntake / ConfirmImportCandidate / RejectImportCandidate 的 ledger 不一致 require 拒绝（ConfirmedManualExpense.kt:93 先例）；reject 决策不触发 factory/ImportIdSource（仅 ImportStatusIdSource 在获胜路径调用一次）；SPINE_INTAKE_INVALID 在端口调用前零写入。
- T-27 确认回调域语义：O-05 的 formal transaction 逐币种平衡、费用/资产分录金额与账户精确匹配（+128.50 / −128.50）；confirmed 状态本身不创建额外正式分录（D-077:951）。
- T-28 intake R5 accepted：O-28 计数与状态迁移（C5 seq1 pending_confirmation）。
- T-29 领域失败零残留：O-29（DOMAIN_VALIDATION_FAILED、含 claim 回滚、批次-1 无持久化痕迹、identity 可用）。
- T-30 修正重试 accepted：O-30（同请求复用、批次-2 钉死、收入域语义：资产 +8888.00、category-salary 隐藏收入账户 −8888.00、逐币种平衡）。

## 10. 边界断言（本批不含）

- 不含 provider/parser、来源顺序与格式技术（P4-03 门）；不含 dedup/duplicate 数据合同（P4-07）；不含 mirror/evidence-link、posting 匹配/绑定与 reconciliation（P4-08）；不含产品随机 ID 算法与产品/应用 Clock 端口（后续阶段门；本批零 Clock 使用，confirmed_at 只取显式事实）。
- 不含整文件保留及其生命周期合同（独立门禁）；原文不落盘。
- ROADMAP 约束：共享范围仅限严格解析（已归一化输入）、明确确认、request snapshot 与正式账务链；本批表与端口不含任何 provider DTO、业务 owner 列（无 counterparty、无 order id、无分类建议、无账户映射）与专项场景表。
- 竖井表零改动；golden 冻结契约与 `.external/` 零改动；竖井仅作 jvmTest 冻结语料。
- 只有 accepted 操作持久化；拒绝与失败路径零残留；只有获胜首请求调用应用回调并消耗 ID。
- 本批不授权补全操作、任何 rejected/confirmed/incomplete 出边，以及 RL-01~RL-08 任何闭合声明（P4-09 门）。

## 11. 开放问题（供主代理/独立评审定夺）

1. 摘要常量 H(R1)/H(R2)/H(R3)/H(R1')/H(R5)：本规格冻结规则；具体摘要值由轮 B 以本规则独立计算并以第二实现核对后钉入测试。评审需确认该钉死流程。
2. SHA-256 原语：Rg09Fingerprint 内为私有；轮 B 选择提取共享内部原语或按文件私有复制，均不得改变 RG-09 字节行为。属实现细节，评审确认即可。
3. incomplete 候选的 reject 决策：本规格要求 reject 仅接受 pending（任何非 pending 含 incomplete ⇒ SPINE_CANDIDATE_NOT_PENDING）；incomplete 的人工 reject 是否在后续批次允许，留待补全操作合同裁决（本批不实现）。
4. 本文件放置（docs/specs/ 而非 docs/migrations/phase4/）与命名：请主代理确认。docs/specs 是仓库既有的 dated 设计/合同文档位置（12 份 RG 设计文档 + 1 份 contract closure proposal 先例，docs/CONTRIBUTING.md:83 为其定义了状态标记约定）；docs/migrations 存 golden-v2 迁移与 closure 工件。批准后状态标记由 proposal 改为 approved。
5. docs/PROJECT_MAP.md 是否为本文件与轮 B 的 20.sqm 增加导航条目：属既有文件修改，本批 writer 无权执行，由主代理裁决。
6. 轮 B 完成后本文件应与本地 checkpoint/plan（PROJECT_STATE.local.md、WORK_PLAN.local.md）的实施登记同步（本地文档由主代理维护）。

## 轮 B 建议文件布局（仅建议，不创建）

- `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ImportSpine.kt`（§8 全部类型/端口/用例；或按类型拆分 ImportSpineTypes.kt / ImportContentFingerprint.kt / ExecuteImportIntake.kt / ConfirmImportCandidate.kt / RejectImportCandidate.kt）。
- `ledger-application/src/jvmTest/kotlin/com/unifiedledger/application/ImportContentFingerprintJvmTest.kt`。
- `ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/Ledger.sq`（新表 CREATE + 查询，与 20.sqm 终态同形状）。
- `ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/20.sqm`。
- `ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightImportSpineStore.kt`（双方法决策端口 + intake 端口实现 + failure injector）。
- `ledger-data/src/jvmTest/kotlin/com/unifiedledger/data/ImportSpineLifecycleEndToEndTest.kt`、`ImportSpineMigrationCoexistenceTest.kt`（或并入既有迁移测试模式）。
