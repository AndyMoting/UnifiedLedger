# P4-04 转账正式化实施批冻结规格（轮 A）

**Status:** approved — spec closure P404-SPEC-001…017 全 PASS、quality review 0 BLOCKER / 0 MAJOR（5 MINOR 已登记不阻断）、独立 verifier V1–V5 全 PASS；主代理于 2026-08-16 批准（A/A/A/A）并放行轮 B 实施，开放问题 1–10 按推荐全部裁决（Q9 的 PROJECT_MAP 条目随轮 B 实现提交加入）。本文件自此约束实现，不再单独授权。

**Scope:** 冻结 P4-04 实施批（RL-03 转账 formalization 子切片，PHASE4_DESIGN_PACKAGE.local.md:77-83、WORK_PLAN.local.md:97-98）的匿名 fixtures/解析契约变更/类型范围矩阵/spine 对接（intake kind 路由 + 转账确认链）/schema v21→v22（21.sqm）迁移与 DDL/诊断码/模块与依赖/测试计划与边界断言。本文件只冻结规格，不含实现；实现只允许在本规格按项目评审拓扑冻结后，由后续实施批（轮 B）在独立 worktree 执行。

## Authority And Boundary

本规格全部条款对齐以下权威（行号为当前本地 main 的 tracked 文件行号）：

- 批定义：PHASE4_DESIGN_PACKAGE.local.md:77-83（完整腿经明确确认形成平衡 asset transfer、外部收支与报表效应为零；缺腿保持 pending、只形成可解释候选/诊断、不猜测或提前创建正式转账；mirror/evidence linkage 留 P4-08）、:115-129（RL 矩阵）、:130-138（阶段 3 衔接纪律）、:140-186（§6 已确认方向）；WORK_PLAN.local.md:97-98（P4-04 行）、:114-129（RL 矩阵）、:131-141（衔接纪律）。
- 账务规则：docs/ACCOUNTING_RULES.md:7-23（核心结构）、:25-36（领域不变量）、:52-60（转账：本金只在用户自己的资产或负债账户间一对一互转；两端各自独立 Posting；手工转账用户明确选择两端；导入来源已明确两端时生成完整草稿、信息不足保持待确认不得猜测；另一端来源以后出现作为同一转账的补充证据）、:164-168（支付钱包充值：自有银行资产转入钱包余额属内部转账、不计入消费）、:200-207（时间规则：导入默认来源支付时间作统计时间）、:209-215（来源/候选/正式账目分层与待确认清单）、:227（RG-09 主例：2026-01-20 资产 B 转资产 A 20.00 保留正常转账身份，不产生普通收入、费用、消费、预算、分类或对外现金流——转账报告语义先例）。
- 决定记录：docs/DECISIONS.md D-030:355-367（转账类别与范围修订：当前只冻结一对一账户互转）、D-031:369-381（手续费为关联独立支出、不属本金）、D-032:383-393（手工转账两端显式；导入优先来源明确事实、信息不足待确认不得猜测）、D-033:395-404（来源明确两端生成完整草稿、确认后入账；另一端后到仅作补充证据）、D-073:891-903（intake 只建来源/证据/待确认候选、candidate_not_pending）、D-077:951（confirmed 状态本身不授权正式分录）、D-081:1007（confirmed_at 只来自显式确认事实）、D-092:1325-1340（方案 A 共享链、非 rgXX_ 前缀、竖井冻结）、D-097:1439-1473（normalized source、completeness、unknown token 政策、类型化诊断 taxonomy、安全 location）、D-098:1475-1528（spine 合同：raw identity、candidate lifecycle、atomic confirmation、范围与延后）、D-099:1530-1546（首个来源与 parser 技术；:1539 类型范围与四类登记到 P4-04、红包类未分配、退款类 → P4-06；:1540 边界）。
- 前序规格：docs/specs/2026-08-13-p4-02-shared-import-spine-design.md（spine 合同、五事实 ImportSourceFacts、claim-first intake、candidate lifecycle、confirm/reject 端口、30-op oracle、§2 receipt、§3 诊断注册、§4 状态矩阵、§5 provenance 命名、§6 哈希与编码、§7 DDL/迁移、§8 端口签名、§9 测试计划——本规格对其应用层合同做 §4 登记的修订）与 docs/specs/2026-08-14-p4-03-wechat-ordinary-flows-design.md（§2 解析契约、§2.3 列映射与路由列不进五事实、§3 类型范围矩阵、§5 解析级诊断码、§6 模块与依赖）。
- 架构：docs/ARCHITECTURE.md:100-114（导入逻辑职责、来源事实与派生事实分层、安全 location 边界）、:147（当前 schema v21、迁移链 1.sqm~20.sqm、minSdk 34 / SQLite ≥ 3.35.0 基线）。
- 外部锚点：CORE_ACCEPTANCE_PLAN（.external/requirements/golden-ledger/，只读）RL-03 行 `GL-A3CB7F3D48BC`「微信零钱转入零钱通」= 验证两条资产分录平衡且不进入对外收支。本规格以该 report 语义为转账确认 oracle；零钱通/零钱理财类交易类型不在本批冻结类型集合内（§9 第 6 项）。
- 代码现实：ledger-application ImportSpine.kt:35-42（ImportSourceFacts 五事实）、:62/:73-80/:82-86（P4-02 确认契约）、:249-256（ImportResolvedSourceFacts）、:258-266（ImportFormalCommit/ImportCandidateFormalFactory）、:301-355（ExecuteImportIntake）、:357-392（Confirm/RejectImportCandidate）；WechatSourceTokens.kt:22/:25/:31/:34（token 表）；WechatBillParser.kt:121-135（parseDataRow 判定顺序）；WechatParserTypes.kt:25-34（`WechatRowResult.Accepted` 当前只有 facts/completeness/diagnostics、没有 recordKind）、:69-76（SPINE_WEIXIN 诊断）；ledger-domain OwnAssetPrincipalTransfer.kt:40-128（createOwnAssetPrincipalTransfer——本批必须复用的转账原语：两条资产分录 PRINCIPAL_OUT/PRINCIPAL_IN、internalTransferMinor=金额、netWorthChangeMinor=0）；AccountTransfer.kt:91-237（RG-03 竖井 factory、可选 FEE 腿——仅冻结竖井先例，不复制进 spine）；ledger-data 20.sqm:16-132（spine 九表）、:28（record_kind CHECK）、:58（candidate_kind CHECK）、:88-102（decision_snapshot category/funding 列与 CHECK）、:195-227（守卫触发器）；Ledger.sq:7382-7498（fresh spine DDL）；SqlDelightImportSpineStore.kt:68-200（intake）、:203-346（confirm）、:481-510（resolveConfirm 等价清单）、:512-540（resolveReject）、:629-648（persistFormal）；ImportSpineWechatEndToEndTest.kt:257-303（OrdinaryFlowFormalFactory——当前唯一 factory 实现是测试 fixture，生产 factory 尚不存在）；3.sqm:1-40（CHECK 变更的完整依赖链重建先例，FK 保持开启）；19.sqm Stage 4（rename-stage 重建模板）；LedgerDatabaseMigrationTest.kt:287（fresh 版本断言 21 → 22 涟漪）。

术语：`本批` = P4-04 实施批；`完整腿` = 来源证明的钱包腿 + 可经用户显式确认补全的另一自有账户腿（零钱提现/零钱充值）；`缺腿` = 来源行不存在第二自有账户腿（转账/群收款，事件对方是外部第三方）；`钱包腿` = 来源行经方向证明的零钱账户腿；`self-transfer` = 钱包↔银行自有账户互转；`spine` = D-092 共享导入链；`拒行` = fail-closed 拒绝（零 record、零写入）；`决策端口` = P4-02 冻结的 commitOnce/commitRejectOnce 共享确认端口。

## 1. 匿名 fixtures 集

全部输入为合成、来源中立、provider-neutral 数据；不含真实 provider、真实个人数据、绝对路径、原文件名、真实商户、真实单号或可识别交易。所有 ID 为合成字符串。金额一律精确整数最小货币单位，禁止二进制浮点。

### 1.1 基础状态

- ledger：`ledger-p404`（单账本）。
- catalog：自有真实资产账户 `account-wallet-wechat`（CNY，零钱钱包）、`account-bank-a`（CNY，银行）、`account-bank-usd`（USD，用于币种失败 fixture）、`account-asset-a`（CNY，沿用 P4-02 命名）；分类 `category-food`（二级支出，隐藏费用账户 `expense-account-food`）与 `category-salary`（二级收入，隐藏收入账户 `income-account-salary`）仅用于 kind-mismatch fixture。
- 输入引用（opaque synthetic input ref）：`batch-p404-a`（主工作簿 T1-T10）、`batch-p404-b`（kind-swap 变体）、`batch-p404-c`（domain-failure 候选批）、`batch-p404-d`（失败注入批）。
- normalized contract 采用显式版本化扩展：`ordinary_flow_source` 继续使用 `contract_version = 1`；本批新增的 `transfer_flow_source` / `transfer_flow_source_missing_leg` 使用 `contract_version = 2`。既有及新解析的 ordinary 行均保持 v1，不原地升级或重写；transfer kind 不得写为 v1。版本由 `ImportRecordKind` 的封闭映射派生，调用方不能独立提交不一致的 kind/version 组合（§4.1、§7；D-097 的「v1 只覆盖 ordinary、后续版本化扩展」边界）。candidate rule = record_kind 值、rule_version = 1（provenance rule 自身版本不因 normalized contract 扩域而改变）；confidence = valid_complete → `1.00`、valid_incomplete → `0.50`（P4-02 §1.2 沿用）；requires_confirmation 恒为 `formal_transaction_creation`（P4-02 §7 形状不变，本批不新增 requirement token）。
- 合成 xlsx 结构与有界输入约束沿用 P4-03 规格 §1.1（元数据区 rows 0-16、表头 0-based row 17 = 11 列、数据行自 row 18、`record_ordinal` = row − 18、10MB/10K 行上限）。

### 1.2 来源记录 T1–T10（全部合成值，workbook A 数据行）

| 记录 | 交易类型 | 收/支 | 金额(元) 格文本 | 当前状态 | 预期解析结果 |
| --- | --- | --- | --- | --- | --- |
| T1 | 零钱提现 | 支出 | "100.00" | 提现已到账 | 接受：facts (10000, CNY, 2)/out/settled，record_kind `transfer_flow_source`、contract_version 2，valid_complete |
| T2 | 零钱充值 | 收入 | "200.00" | 支付成功 | 接受：facts (20000, CNY, 2)/in/settled，`transfer_flow_source`、contract_version 2，valid_complete |
| T3 | 转账 | 支出 | "50.00" | 支付成功 | 接受：facts (5000, CNY, 2)/out/settled，`transfer_flow_source_missing_leg`、contract_version 2，valid_complete |
| T4 | 群收款 | 收入 | "66.00" | 已存入零钱 | 接受：facts (6600, CNY, 2)/in/settled，`transfer_flow_source_missing_leg`、contract_version 2，valid_complete |
| T5 | 零钱提现 | 收入 | "10.00" | 提现已到账 | 拒行 CONFLICTING_SOURCE_FACTS（record_error/record），零 record（类型×方向矩阵违约，§2/§3） |
| T6 | 零钱提现 | / | "10.00" | 提现已到账 | 接受：direction raw "/" 保留、unresolved → valid_incomplete + REQUIRED_FACT_UNRESOLVED(direction)，`transfer_flow_source`、contract_version 2 |
| T7 | 零钱提现手续费 | 支出 | "1.00" | 支付成功 | 拒行 SPINE_WEIXIN_UNKNOWN_TOKEN，零 record（不在任何冻结集合，§3） |
| T8 | 微信红包 | 收入 | "8.80" | 已存入零钱 | 拒行 SPINE_WEIXIN_UNSUPPORTED_TX_TYPE，零 record（红包类维持 fail-closed） |
| T9 | 转账 | 收入 | "30.00" | 已退款(30.00) | 拒行 SPINE_WEIXIN_REFUND_UNSUPPORTED（判定顺序 1），零 record |
| T10 | 商户消费 | 支出 | "12.00" | 支付成功 | 接受：ordinary 路由不变，facts (1200, CNY, 2)/out/settled，`ordinary_flow_source`、contract_version 1 |

事实形状：`facts (amount_minor, currency_code, currency_precision), direction_token, status_token`；occurred_at 恒为 ISO-8601 offset datetime 文本（+08:00），如 T1 = `2026-08-01T12:30:00+08:00`。T6 是 D-097:1447/1453 边界内的 valid_incomplete（方向事实未解析；金额/时间/状态可靠，不构成 record error）。W7 交叉引用：P4-03 规格 §1.2 的 W7（零钱提现/支出/100.00/提现已到账）在本批路由下成为合法 transfer record（P-12/R-03）。

候选冻结值：C1/C2/C3/C4/C6 分别为 T1/T2/T3/T4/T6 的候选；C3/C4 的 candidate_kind = `transfer_flow_missing_leg`（§4.1 映射）。content_hash H(Tn) 按 §4.1 规则由入站 (recordKind, facts) 一次性计算，具体摘要常量随轮 B 以本规则独立计算并以第二实现核对后钉死（P4-02 §11.1 先例）。

本批合成 ID 常量（轮 B 的确定性测试 IdSource 依序产出并钉死）：source-t1/evidence-t1/candidate-t1（C1）、source-t2/evidence-t2/candidate-t2（C2）、source-t3/evidence-t3/candidate-t3（C3）、source-t4/evidence-t4/candidate-t4（C4）、source-t6/evidence-t6/candidate-t6（C6）、source-t10/evidence-t10/candidate-t10（C10）；status-t1-1/status-t1-2（C1）、status-t2-1/status-t2-2（C2）、status-t3-1/status-t3-2（C3）、status-t4-1/status-t4-2（C4）、status-t6-1（C6）；confirmation-t1/tx-t1（E-10）与 confirmation-t2/tx-t2（E-15）；失败注入、领域失败与 binding mismatch 的废弃批次（attempt-1 系列，零持久化痕迹）在 E-21~E-25/E-31/E-32/E-34 中钉死；E-34 的 setup C1d 使用 batch-p404-c / ordinal 2 与 source-t1d/evidence-t1d/candidate-t1d，十三个恶意 formal 子向量分别使用隔离的 `attempt-binding-b01-*`…`attempt-binding-b13-*` ID 命名空间（完整 transaction/version/posting-set/posting ID 清单随 T-44 case table 钉死，任何 ID 均不得持久化）；posting/version 常量随 E-10/E-15 断言钉死。

### 1.3 操作集（P-01…P-12 解析级；E-01…E-35、E-40…E-41 spine 端到端/迁移；R-01…R-03 回归）

`Δ` 表示该操作自身的新增行数（request/source/evidence/candidate/status_history/decision_snapshot/confirmation/receipt 与 formal transaction/version/posting）。`零写入` 表示该操作不留下任何新行（包括 request claim 行）。`零 record` = 该行不产生 normalized record。诊断按冻结集合（multiset）比较，message 不比较（D-097:1459）。transfer 确认的 formal 断言固定为：transaction+1、version+1、posting+2 逐币种平衡，且报表语义 internal transfer = 金额、net worth change = 0、零收入/费用/消费/分类效应（docs/ACCOUNTING_RULES.md:227 先例、CORE_ACCEPTANCE_PLAN RL-03）。

解析级（P-01…P-12 逐记录；location 的 ordinal 见 §1.2）：

- P-01 T1 → 1 条 record：facts (10000, CNY, 2)/out/settled，occurred_at `2026-08-01T12:30:00+08:00`，record_kind `transfer_flow_source`，valid_complete，诊断空。
- P-02 T2 → (20000, CNY, 2)/in/settled，`transfer_flow_source`，valid_complete。
- P-03 T3 → (5000, CNY, 2)/out/settled，`transfer_flow_source_missing_leg`，valid_complete。
- P-04 T4 → (6600, CNY, 2)/in/settled，`transfer_flow_source_missing_leg`，valid_complete。
- P-05 T5 → CONFLICTING_SOURCE_FACTS（复用 D-097:1459，record_error/record，{input_ref, record_ordinal}），零 record。
- P-06 T6 → valid_incomplete；direction_token = raw "/" 保留、unresolved；其余事实可靠；诊断 {REQUIRED_FACT_UNRESOLVED (incomplete/field, field_role=direction)}；record_kind `transfer_flow_source`。
- P-07 T7 → 拒行 SPINE_WEIXIN_UNKNOWN_TOKEN（unsupported/record），零 record。
- P-08 T8 → 拒行 SPINE_WEIXIN_UNSUPPORTED_TX_TYPE，零 record。
- P-09 T9 → 拒行 SPINE_WEIXIN_REFUND_UNSUPPORTED（判定顺序 1 优先于类型路由），零 record。
- P-10 T10 → ordinary record（路由不变）：(1200, CNY, 2)/out/settled，`ordinary_flow_source`，valid_complete。
- P-11 整批 workbook A（T1-T10）→ outcome = `partial`；6 条 record（T1-T4、T6、T10）；诊断 multiset 钉死 5 条 = {T5: CONFLICTING_SOURCE_FACTS；T6: REQUIRED_FACT_UNRESOLVED(direction)；T7: UNKNOWN_TOKEN；T8: UNSUPPORTED_TX_TYPE；T9: REFUND_UNSUPPORTED}；拒行与 record_error 行零 record。
- P-12 W7 重解析（P4-03 workbook A 在本批路由下）→ 1 条 record：(10000, CNY, 2)/out/settled，`transfer_flow_source`，valid_complete（状态 `提现已到账` 经 §2 扩展映射为 settled）。

spine 端到端（沿用 P4-02 §1.3 的 Δ 形状与 claim-first 语义；候选命名见 §1.2；intake 调用携带 recordKind，§4.1）：

- E-01 intake T1 @ req-t1-intake → accepted。Δ：request+1、source+1、evidence+1、candidate+1（C1 pending_confirmation）、status_history+1、receipt+1；formal 0/0/0。source contract_version=2；candidate_kind=`transfer_flow`、rule=`transfer_flow_source`、rule_version=1、confidence=`1.00`。returned：[{source,source-t1},{evidence,evidence-t1},{candidate,candidate-t1}]。
- E-02 同请求等价重放 E-01 → no_change（`equivalent_replay`），Δ 全零，receipt 逐值相同。
- E-03 intake T1'（amount_minor=10001，其余 facts 与 kind 同 T1、同一 raw identity）@ req-t1-intake-2 → rejected（SPINE_IDENTITY_COLLISION），Δ 全零。
- E-04 intake T1 但 recordKind=ORDINARY_FLOW_SOURCE、同一 raw identity @ req-t1-intake-3 → rejected（SPINE_IDENTITY_COLLISION），Δ 全零（哈希成员 record_kind 取值不同 ⇒ 摘要不同，§4.1）。
- E-05 intake T1 但 recordKind=ORDINARY_FLOW_SOURCE、全新 raw identity（batch-p404-b / 0）@ req-b-intake → accepted（C1o，source contract_version=1、candidate_kind=`ordinary_flow`）——kind 是独立候选维度，无跨 kind 干扰；ordinary v1 不因同一实现同时支持 transfer v2 而升级。
- E-06 intake T2 @ req-t2-intake → accepted（C2 pending_confirmation，`transfer_flow`）。
- E-07 intake T3 @ req-t3-intake → accepted（C3 pending_confirmation，`transfer_flow_missing_leg`）。
- E-08 intake T4 @ req-t4-intake → accepted（C4 pending_confirmation，`transfer_flow_missing_leg`）。
- E-09 intake T6 @ req-t6-intake → accepted（C6 status incomplete，`transfer_flow`；valid_incomplete → incomplete，P4-02 §4）。

confirm 与 reject（决策端口，§4.2/§4.3）：

- E-10 confirm C1 @ req-t1-confirm，decisionFields=TransferFlow(fromAccountId=`account-wallet-wechat`, toAccountId=`account-bank-a`)，expectedContentHash=H(T1)，explicitConfirmedAt=2026-08-14T10:00:00+08:00 → accepted。Δ：request+1、decision_snapshot+1（decision=confirm / H(T1) / category_id=NULL / funding_account_id=NULL / from_account_id=account-wallet-wechat / to_account_id=account-bank-a / explicit_confirmed_at=显式值）、status_history+1（C1: seq2 confirmed, class creation, status-t1-2）、confirmation+1（class creation、request 引用 req-t1-confirm、transaction 引用 tx-t1）、receipt+1；formal：transaction+1（kind ACCOUNT_TRANSFER，tx-t1）、version+1、posting+2（account-wallet-wechat −100.00；account-bank-a +100.00，逐币种平衡）。报表：internalTransferMinor=10000、netWorthChangeMinor=0、消费/普通收入/普通费用/分类效应全零。returned：[{confirmation,confirmation-t1},{transaction,tx-t1}]。ID 只在获胜 callback 内由 ImportIdSource 惰性分配一次。
- E-11 同请求等价重放 E-10 → no_change。Δ 全零；factory 不被调用；ImportIdSource 不消耗；receipt 与 E-10 逐值相同。
- E-12 confirm C1 @ req-t1-confirm-2 → rejected（SPINE_CANDIDATE_NOT_PENDING），Δ 全零。
- E-13 confirm @ req-t1-confirm、expectedContentHash≠H(T1) → rejected（SPINE_STALE_FINGERPRINT），Δ 全零。
- E-14 confirm @ req-t1-confirm、TransferFlow(from=account-bank-a, to=account-wallet-wechat)（其余同 E-10）→ rejected（SPINE_REQUEST_IDENTITY_CONFLICT），Δ 全零（claim 失败后决策快照四列字段比对，§4.2）。
- E-15 confirm C2 @ req-t2-confirm，TransferFlow(from=account-bank-a, to=account-wallet-wechat)，explicitConfirmedAt=2026-08-14T11:00:00+08:00 → accepted（direction in → 钱包为 TO 腿）。formal：account-bank-a −200.00；account-wallet-wechat +200.00；报表同 E-10 形状（internalTransferMinor=20000、netWorthChangeMinor=0、零对外效应）。returned：[{confirmation,confirmation-t2},{transaction,tx-t2}]。
- E-16 confirm C3 @ req-t3-confirm，TransferFlow(from=account-wallet-wechat, to=account-bank-a) → rejected（SPINE_TRANSFER_NOT_CONFIRMABLE），Δ 全零（缺腿候选本片确认门关闭，§3）。
- E-17 confirm C4 @ req-t4-confirm，TransferFlow(from=account-bank-a, to=account-wallet-wechat) → rejected（SPINE_TRANSFER_NOT_CONFIRMABLE），Δ 全零。
- E-18 reject C3 @ req-t3-reject → accepted（缺腿候选的人工处置终态）。Δ 同 P4-02 O-11：request+1、decision_snapshot+1（reject/H(T3)/四字段全 NULL/confirmed_at 空）、status_history+1（C3: seq2 rejected, class status_transition, status-t3-2）、receipt+1；confirmation+0；formal 0/0/0。returned：[{candidate,candidate-t3}]。
- E-19 reject C4 @ req-t4-reject → accepted，Δ 同 E-18 形状（status-t4-2）。
- E-20 confirm C6 @ req-t6-confirm，TransferFlow(from=account-wallet-wechat, to=account-bank-a) → rejected（SPINE_CANDIDATE_INCOMPLETE），Δ 全零。
- E-21 setup：intake T1 副本 @ batch-p404-c / ordinal 0（raw identity 与 T1 不同）→ accepted（C1b pending，`transfer_flow`）。随后 confirm C1b @ req-t1b-confirm，TransferFlow(from=account-bank-a, to=account-wallet-wechat)（支出行但钱包为 TO 腿 → 方向门失败，§4.3）→ rejected（SPINE_DOMAIN_VALIDATION_FAILED）。Δ 全零（含 claim 回滚）；C1b 仍 pending；request identity 可用；ImportIdSource 批次-1 消费后无任何持久化痕迹。
- E-22 confirm C1b @ req-t1b-confirm，TransferFlow(from=account-wallet-wechat, to=account-wallet-wechat)（同账户）→ rejected（SPINE_DOMAIN_VALIDATION_FAILED，DistinctAccountsRequired）。Δ 全零。
- E-23 confirm C1b @ req-t1b-confirm，TransferFlow(from=account-wallet-wechat, to=`expense-account-food`)（方向门通过，目标腿非资产账户）→ rejected（SPINE_DOMAIN_VALIDATION_FAILED，OwnedRealAssetRequired）。Δ 全零。
- E-24 confirm C1b @ req-t1b-confirm，TransferFlow(from=account-wallet-wechat, to=`account-unknown`)（未知账户）→ rejected（SPINE_DOMAIN_VALIDATION_FAILED，KnownAccountRequired）。Δ 全零。
- E-25 confirm C1b @ req-t1b-confirm，TransferFlow(from=account-wallet-wechat, to=account-bank-usd)（方向门通过，两条真实自有资产腿币种不符）→ rejected（SPINE_DOMAIN_VALIDATION_FAILED，SameCurrencyRequired）。Δ 全零。
- E-26 confirm C1b @ req-t1b-confirm，TransferFlow(from=account-wallet-wechat, to=account-bank-a)（修正重试）→ accepted。Δ 同 E-10 形状；ImportIdSource 批次-2 钉死；C1b seq2 confirmed。
- E-27 setup：intake T1 副本 @ batch-p404-c / ordinal 1 → accepted（C1c pending）。confirm C1c @ req-t1c-confirm，decisionFields=OrdinaryFlow(categoryId=category-food, fundingAccountId=account-asset-a) → rejected（SPINE_DECISION_KIND_MISMATCH），Δ 全零（transfer 候选 + 普通决策字段）。
- E-28 confirm C1o（E-05 的普通候选）@ req-b-confirm，decisionFields=TransferFlow(from=account-wallet-wechat, to=account-bank-a) → rejected（SPINE_DECISION_KIND_MISMATCH），Δ 全零（普通候选 + 转账决策字段）。

并发与失败（线程对通过独立 SQLite 连接并发执行；busy_timeout 先例 configureSqliteConnection）：

- E-29 并发 E-10 ×2（同请求同候选同字段）→ 1 accepted + 1 no_change；行数保持单组；ImportIdSource.next() 恰好调用一次。
- E-30 并发 {E-10, E-10@req-t1-confirm-3}（不同请求同候选）→ 1 accepted + 1 rejected（SPINE_CANDIDATE_NOT_PENDING）；ImportIdSource.next() 恰好调用一次。
- E-31 intake 失败注入（candidate 插入后，T1 @ batch-p404-d / 0）→ 异常 + 事务全回滚（含 claim）；计数不变；allocateIds 在获胜路径恰好调用一次、批次-1 无任何持久化痕迹；同请求重试 → accepted（批次-2 钉死）。
- E-32 confirm 失败注入（transfer formal 持久化后，新候选 @ batch-p404-d / 1）→ 异常 + 全回滚（含 formal 行、status_history、decision_snapshot、confirmation、receipt、claim）；计数不变；每次获胜尝试恰好调用一次 ImportIdSource（批次-1 消费后零痕迹）；同请求重试 → accepted（批次-2 钉死）。
- E-33 文件库关闭重开后重放 E-10 → no_change，原 receipt；全部行恢复一致。
- E-34 formal binding mismatch（十三个独立子向量 B01…B13，共用 setup 后各自从同一 frozen pre-state 执行）：setup intake T1 副本形成 pending C1d；confirm @ req-t1d-confirm 的 immutable input 固定为 ledger=ledger-p404、resolved amount=10000/CNY/2、source occurred_at=`2026-08-01T12:30:00+08:00`、TransferFlow(from=account-wallet-wechat, to=account-bank-a)。每个测试专用恶意 callback 只偏离下列命名不变量，其余字段保持 canonical：(B01 `reversed_legs`) bank→wallet；(B02 `wrong_ledger`) ledger-p404-other；(B03 `wrong_kind`) 非 ACCOUNT_TRANSFER；(B04 `wrong_amount`) 同币种平衡但金额非 10000；(B05 `wrong_currency`) 同精度平衡但非 CNY；(B06 `wrong_precision`) 同代码平衡但 precision 非 2；(B07 `extra_posting`) current posting set 有第三条 posting；(B08 `multiple_versions_current_v2`) 领域层合法的两版本链且 current version_number=2，联合证明 versions.size 必须为 1 且 current version_number 必须为 1；(B09 `extra_posting_set`) 单版本仍另带一个未引用 posting set；(B10 `wrong_occurred_at`) 仅 occurredAt 偏离 source；(B11 `wrong_statistics_at`) 仅 statisticsAt 偏离 source；(B12 `wrong_effective_at`) 仅 effectiveAt 偏离 source；(B13 `non_null_note`) sole version note 非 NULL。commit port 必须在任何 `persistFormal` 调用前由 `validateImportFormalBinding` 检出每个 mismatch，全部 rejected（SPINE_REFERENCE_INTEGRITY_VIOLATION），Δ 全零（含 claim 回滚）；每个子向量后 C1d 仍 pending、request identity 可用、ImportIdSource 本次 `attempt-binding-bNN-*` 批次无任何持久化痕迹。十三个向量共同证明 callback 返回的完整 formal graph 必须绑定同一个 immutable input，不能只信任 factory 实现或只检查 current postings。
- E-35 cross-ledger：ledger `ledger-p404-other` 使用 req-cross-ledger-confirm 尝试确认只存在于 `ledger-p404` 的 C1（同 candidate_id 文本），decisionFields 与 E-10 相同 → rejected（SPINE_CANDIDATE_NOT_FOUND），Δ 全零（含 claim 回滚）；factory 不调用、ImportIdSource 不消耗、两个 ledger 的 complete canonical state 均不变。所有 lookup、formal command、snapshot 和持久化只使用 `ImportRequestIdentity.ledgerId`；不存在 snapshot/factory 的第二 ledger 来源。

迁移（§7）：

- E-40 v21→v22：fresh schema 与迁移后 schema 相等（版本 22）；既有 v21 ordinary 行保持 contract_version=1 且可重放，rg03/rg04/rg08 竖井行存活且可重放；迁移后新 transfer v2 操作可用。八张重建表 `import_source_record`、`import_evidence`、`import_candidate`、`import_candidate_requires_confirmation`、`import_candidate_status_history`、`import_candidate_decision_snapshot`、`import_confirmation`、`import_receipt` 的 BEFORE UPDATE/DELETE 守卫，以及 `import_candidate_status_history` 的 sequence/transition INSERT 守卫全部 re-armed；既有 decision 行的 from/to 列为 NULL。
- E-41 v21→v22 late-stage failure：文件库先以 v21 完整 spine/ordinary/formal fixture、rg03/rg04/rg08 共存行和 `PRAGMA user_version=21` 建立 pre-state，`PRAGMA foreign_keys=1`。在一个 production-equivalent outer transaction 内执行真实 `Schema.migrate(21,22)`、把 user_version 更新为 22，然后在 Stage 6 已完成但 commit 前用测试专用 CHECK 违例注入失败；异常必须回滚整个事务。关闭并重开后逐值等于迁移前 canonical state，`user_version=21`，所有八张 v21 表及触发器仍为原形状，`sqlite_master` 中不存在任何 `*_stage`、P4-04 guard 或测试注入表，`foreign_keys=1`、`pragma_foreign_key_check` 计数为 0；随后不注入失败的同一路径可成功迁移至 v22。测试注入只位于 test transaction，不修改 21.sqm。

回归（R 系列）：

- R-01 P4-02 30-op oracle（O-01…O-30）逐值不变——普通 intake/confirm/reject/replay/并发/失败注入语义零变化。
- R-02 P4-03 E 系列中 E-01…E-11、E-13、E-14 逐值不变；E-12 是第三处且唯一的 E 系列冻结修订：全批 intake 由 8 条变 9 条 record/candidate，按 workbook 顺序为 C1-C5 ordinary pending、C6/W6 incomplete、C7/W7 transfer pending（record_kind=`transfer_flow_source`、contract_version=2、candidate_kind=`transfer_flow`）、C8/W10 incomplete、C9/W14 incomplete；零 intake 的拒行/record_error 由 6 行变 5 行（删除 W7），解析诊断由 9 条变 8 条并与修订后 P-14 相同。除该 E-12 delta 外，普通 flows 的 spine 端到端逐值不变。
- R-03 P4-03 P 系列逐值不变，唯两处解析级冻结修订：(1) W7/P-07 由「拒行 SPINE_WEIXIN_UNSUPPORTED_TX_TYPE」改为「接受 transfer v2 record（本规格 P-12）」；(2) P-14 记录数明确由 8 变 9，诊断 multiset 由 9 条变 8 条（删除 W7 的 UNSUPPORTED_TX_TYPE，新增 W7 record 不新增诊断）。加上 R-02 的 E-12 full-batch intake delta，共计恰好三处跨 P4-03 冻结修订。该修订是 D-099:1539 已登记的 P4-04 类型转移（§9 第 1 项），其余 P 系列断言逐值不变。

### 1.4 Complete canonical state oracle（每个 E 操作必检）

每个 E-01…E-35、E-40…E-41 都冻结一个独立 pre-state 和 complete expected post-state；复合操作另在 setup、失败、retry 每个可观察边界冻结 checkpoint。expected 只能由 §1 fixtures/操作合同在测试侧独立构造，禁止从被测数据库读回后再生成 expected，也禁止只比较 count、delta 或选定字段。

canonical state 必须逐行、逐列覆盖并使用数据库规范值（NULL 与空字符串不等价、整数/文本不互换），按各表 PRIMARY KEY/复合键稳定排序：

1. 九张共享 spine 表全部列：`import_request`、`import_source_record`、`import_evidence`、`import_candidate`、`import_candidate_requires_confirmation`、`import_candidate_status_history`、`import_candidate_decision_snapshot`、`import_confirmation`、`import_receipt`。包括 ledger/request/source/candidate/confirmation/status/formal 引用、record/candidate kind、contract/rule version、content hash、完整六事实、confidence、requirements、sequence/status/operation_class、decision 七项、confirmed_at、receipt outcome 与全部 nullable 列；不能用行数代替内容。
2. 与 import_confirmation.transaction_id 可达的完整 formal chain 五表全部列：`ledger_transaction`、`posting_set`、`transaction_version`、`posting`、`ledger_transaction_current_version`；同时断言不存在 orphan 或未被 confirmation 引用的本批 formal 行。transfer 固定为 canonical_kind=NULL、恰好一个 version/一个 posting_set、current link 指向该 version、version_number=1、occurred/statistics/effective 三时间均等于 source occurred_at、note=NULL、confirmation_id=NULL；posting index 0/1 分别为冻结 from/to 稳定 ID、账户、精确相反金额、币种与精度。任何额外 version/posting set/posting 均失败。
3. 每个 ledger 的独立 report projection：按 account_id 排序并包含 fixture catalog 每个账户（含零余额）的完整余额；internal transfer、external income、external expense、consumption、category totals 与 net-worth change。projection 由 expected postings 的独立 reducer 计算，不调用 production factory/report helper；正式 transfer 必须 internalTransfer=本金、其他对外收支/消费/category effect/netWorthChange 全零。
4. operation result 与 delta：outcome/reason/diagnostic、returned IDs 与 receipt 全字段也纳入 checkpoint；另由测试侧完整 canonical pre/post state 独立求差，逐表比较九张 spine 表与五张 formal chain 表的新增/删除/修改行集合，并与 §1.3 冻结 Δ 对齐。NoChange、Rejected、异常及并发输家 checkpoint 必须与该尝试前 state 逐值相同且 14 表 delta 全空；accepted 只允许 §1.3 明示行发生变化。E-34 的 B01…B13 必须逐个从同一 frozen pre-state 比较 complete canonical state，证明 callback 的 legs、ledger、kind、amount、currency、precision、posting 数、version 数/current version_number、posting-set 数、occurred/statistics/effective 三时间与 note 任一不绑定时均无法留下 decision/formal/claim；E-35 必须证明 cross-ledger candidate lookup 同样零残留；E-41 必须比较失败前后 v21 schema metadata、数据与 FK 状态。行数或 selected-field delta 只能作辅助，不能替代完整 pre/post state 和逐行 delta。

轮 B 将以上模型实现为 test-only immutable `P404CanonicalState` / `P404ExpectedState`，通过直接只读 SQL 投影 actual；所有 E 编号必须在显式 case table 中恰好登记一次，测试先断言 case ID 集合等于冻结 E 集合，再逐 checkpoint 比较完整对象。任何只比较 selected fields/counts 的既有断言只能作为辅助，不能替代本 oracle。

## 2. 解析契约变更（fail-closed）

P4-03 规格 §2 的容器/表头/行定位/金额/时间规则全部不变（冻结表头、禁止漂移、数值格精确十进制、+08:00、CNY、元数据区零读取、有界输入）。本批仅做以下冻结变更：

### 2.1 Token 集合

- 新冻结集合（provider 中立语义常量，登记于 WechatSourceTokens 的轮 B 修订）：`TRANSFER_SELF_TX_TYPES = {零钱提现, 零钱充值}`；`TRANSFER_MISSING_LEG_TX_TYPES = {转账, 群收款}`。
- `REJECTED_TX_TYPES` 由 {转账, 群收款, 零钱提现, 零钱充值, 微信红包} 修订为 {微信红包}（四类转入 transfer 路由；微信红包维持 fail-closed，D-099:1539 未分配批次）。
- `ACCEPTED_TX_TYPES`（五类普通收支）不变。
- 类型列判定顺序（冻结、确定性，修订 P4-03 §3）：(1) 类型或状态含「退款」→ SPINE_WEIXIN_REFUND_UNSUPPORTED；(2) 类型 ∈ TRANSFER_SELF_TX_TYPES → self-transfer 路由；(3) 类型 ∈ TRANSFER_MISSING_LEG_TX_TYPES → missing-leg 路由；(4) 类型 ∈ REJECTED_TX_TYPES → SPINE_WEIXIN_UNSUPPORTED_TX_TYPE；(5) 类型 ∈ ACCEPTED_TX_TYPES → ordinary 路由；(6) 其余 → SPINE_WEIXIN_UNKNOWN_TOKEN。判定顺序 (1) 与 fail-closed 原则不变（WechatBillParser.kt:121-135 修订点）。

### 2.2 方向、状态与类型×方向矩阵

- 方向映射不变（`收入`→"in"、`支出`→"out"、其余含 "/" 保留 raw + unresolved，D-097:1449/1455）。
- 状态接受子集最小扩展（冻结修订，§9 第 2 项）：`SETTLED_STATUS_TOKENS = {支付成功, 已存入零钱, 已到账, 提现已到账}`——仅新增 `提现已到账`（W7/T1 行为证据形状，P4-03 规格 §1.2）；其余状态 token 维持 raw + unresolved。
- 类型×方向矩阵（冻结，仅 self-transfer 类型）：`零钱提现` 期望方向 = 支出；`零钱充值` 期望方向 = 收入。方向为已映射 token 且与矩阵不符（T5）→ CONFLICTING_SOURCE_FACTS（复用 D-097:1459，record_error/record，{input_ref, record_ordinal}），零 record；方向为 raw/unresolved（"/"、空 cell、值域外）→ 不触发矩阵违约，走 unresolved → valid_incomplete（D-097 unknown token 政策）。`转账`/`群收款` 两方向均合法（缺腿处置与方向无关）。
- 金额/时间无效仍为 FIELD_AMOUNT_INVALID / FIELD_TIME_INVALID + 零 record（不降格 incomplete，D-097:1447）。

### 2.3 Kind 路由（Q6）

- 解析器从交易类型路由列派生 record_kind：self-transfer 类型 → `transfer_flow_source`；missing-leg 类型 → `transfer_flow_source_missing_leg`；ordinary 类型 → `ordinary_flow_source`。`WechatRowResult.Accepted` 因此增加 provider-neutral `recordKind: ImportRecordKind`；normalized contract version 由该封闭 kind 映射派生（transfer 两 kind → v2，ordinary → v1），不是另一个可被调用方任意组合的输入。类型 token 本身仍是路由列、不进五事实、不落盘（P4-03 规格 §2.3 列 1 纪律、provider DTO 零引入）；落盘的只有 provider 中立的归一化 kind 与对应 contract_version。
- 方向未决的 transfer 行（T6）→ raw token 保留、valid_incomplete + REQUIRED_FACT_UNRESOLVED(direction)（Q7；D-097:1447/1453/1459）。
- 批次 outcome 语义不变（P4-03 规格 §2.4：fatal → rejected；否则 complete/partial；拒行计入 partial）。

## 3. 类型范围矩阵（本批冻结）

| 交易类型 token | 方向 | 本批处置 | record_kind / candidate_kind | 确认门 |
| --- | --- | --- | --- | --- |
| 零钱提现 | 支出 | 接受：钱包↔银行 self-transfer 候选（来源证明钱包腿；另一端待显式确认） | `transfer_flow_source` / `transfer_flow` | 可确认：TransferFlow 两端显式，方向门要求 from=钱包（§4.3） |
| 零钱提现 | 收入 | 拒行 CONFLICTING_SOURCE_FACTS，零 record（§2.2 矩阵） | — | — |
| 零钱充值 | 收入 | 接受：银行↔钱包 self-transfer 候选 | `transfer_flow_source` / `transfer_flow` | 可确认：方向门要求 to=钱包 |
| 零钱充值 | 支出 | 拒行 CONFLICTING_SOURCE_FACTS，零 record | — | — |
| 转账 | 支出或收入 | 接受：缺腿候选（来源无第二自有账户腿，事件对方为外部第三方） | `transfer_flow_source_missing_leg` / `transfer_flow_missing_leg` | 本片关闭：confirm → SPINE_TRANSFER_NOT_CONFIRMABLE；reject 可作人工处置终态 |
| 群收款 | 支出或收入 | 同上 | 同上 | 同上 |
| 微信红包 | 任意 | 拒行 SPINE_WEIXIN_UNSUPPORTED_TX_TYPE | — | 红包类 → 未分配批次（D-099:1539） |
| `<任意>-退款` 变体或状态含「退款」 | 任意 | 拒行 SPINE_WEIXIN_REFUND_UNSUPPORTED（判定顺序 1） | — | 退款类 → P4-06（D-099:1539） |
| 商户消费/扫二维码付款/二维码收款/赞赏码/其他 | 原规则 | 接受（ordinary 路由不变，P4-03 规格 §3） | `ordinary_flow_source` / `ordinary_flow` | 不变 |
| 未知类型 token | — | 拒行 SPINE_WEIXIN_UNKNOWN_TOKEN | — | fail-closed |

- 会计锚点：零钱提现/零钱充值是用户自己的两个资产账户间互转（docs/ACCOUNTING_RULES.md:52-60 转账 + :164-168 支付钱包充值 = 内部转账不计消费）；转账/群收款的事件对方是外部第三方，来源行不存在第二自有腿，本片不实现其外部化（收入/费用/应收）语义——候选保持 pending、确认门关闭，不猜测账户（D-032:387），后到第二来源的镜像合并留 P4-08（D-033:399、PHASE4_DESIGN_PACKAGE.local.md:82）。
- 缺腿候选状态为 pending_confirmation（五事实完整 → valid_complete，P4-02 §4），其「可解释」信号 = candidate_kind `transfer_flow_missing_leg` + confirm 门 SPINE_TRANSFER_NOT_CONFIRMABLE 类型化诊断；reject 决策对任意 pending 候选（含缺腿）保持可用（P4-02 §4 矩阵不变）。

## 4. Spine 对接

### 4.1 Intake（形状最小扩展：kind 判别）

intake 五事实形状、claim-first、raw identity 幂等/碰撞、Δ 形状、receipt 全部不变。唯一 additive 扩展 = `ImportRecordKind` 判别字段（P4-02 规格 §6 的哈希输入成员 `record_kind` 本就存在，本批将其从实现常量改为入站字段）：

```kotlin
enum class ImportRecordKind(val storageValue: String, val contractVersion: Int) {
    ORDINARY_FLOW_SOURCE("ordinary_flow_source", 1),
    TRANSFER_FLOW_SOURCE("transfer_flow_source", 2),
    TRANSFER_FLOW_SOURCE_MISSING_LEG("transfer_flow_source_missing_leg", 2),
}
```

- `ImportIntakeRequest` 与 `ImportIntakeSnapshot` 增加 `recordKind: ImportRecordKind`；`ImportSourceFacts` 五事实形状零改动。`contractVersion` 不作为独立可写字段，而由 `recordKind.contractVersion` 唯一派生，防止 kind/version 不一致。
- `ImportContentFingerprint.digest(recordKind: ImportRecordKind, facts: ImportSourceFacts)`（签名修订）；canonicalJson 成员清单不变（amount, currency_code, currency_precision, direction_token, occurred_at, record_kind, status_token），`record_kind` 成员值 = `recordKind.storageValue`。contract_version 不另入哈希，因为冻结映射中 kind→version 为 1:1；这保留 ordinary v1 的既有摘要字节。以后若同一 record_kind 跨版本复用，必须先修订哈希合同，不能静默复用本条。
- store 持久化：`import_source_record.record_kind = snapshot.recordKind.storageValue`、`contract_version = snapshot.recordKind.contractVersion`；candidate_kind 与 rule 按冻结 1:1 映射派生：`ORDINARY_FLOW_SOURCE → ordinary_flow`；`TRANSFER_FLOW_SOURCE → transfer_flow`；`TRANSFER_FLOW_SOURCE_MISSING_LEG → transfer_flow_missing_leg`；rule = record_kind 值、rule_version = 1。
- intake 等价（hash + facts 双条件，D-098:1518）增加 record_kind 比对；kind 不同 ⇒ 摘要不同 ⇒ 同 raw identity 不等价 ⇒ SPINE_IDENTITY_COLLISION（E-04）；kind 是独立候选维度（E-05）。
- `ExecuteImportIntake` 校验清单不变（enum 类型保证 kind 合法，无需新增运行时校验）。

### 4.2 转账确认链（Q2 冻结：decision-kind 判别确认请求，单一共享端口）

扩展现有 ConfirmImportCandidate 端口（不新增独立端口）：一个判别式确认请求、一个 commitOnce 端口、一张决策快照表（§7）。理由：D-098:1507 冻结「共享确认端口 commitOnce」；单一端口保持 replay/冲突/失败注入证明面最小（WORK_PLAN.local.md:131-141）；独立端口会复制整条 claim-first 校验链。

应用合同修订（P4-02 规格 §8 形状的登记修订；P4-02 规格文件保持其自身批次的历史权威，不修改）：

```kotlin
sealed interface ImportConfirmDecisionFields {
    /** 普通收支决策：字段与 P4-02 冻结的 ImportConfirmFields 完全一致。 */
    data class OrdinaryFlow(val categoryId: CategoryId, val fundingAccountId: AccountId) : ImportConfirmDecisionFields
    /** 转账决策：两端显式、用户选择；无 category。 */
    data class TransferFlow(val fromAccountId: AccountId, val toAccountId: AccountId) : ImportConfirmDecisionFields
}

data class ImportCandidateConfirmRequest(
    val identity: ImportRequestIdentity,
    val candidateId: ImportCandidateId,
    val expectedContentHash: String,
    val explicitConfirmedAt: String?,
    val decisionFields: ImportConfirmDecisionFields,
)

data class ImportCandidateDecisionSnapshot(
    val candidateId: ImportCandidateId,
    val decision: ImportCandidateDecision,
    val expectedContentHash: String,
    val explicitConfirmedAt: String?,
    val confirmDecisionFields: ImportConfirmDecisionFields?,
)

data class ImportCandidateFormalizationInput(
    val ledgerId: LedgerId,
    val resolved: ImportResolvedSourceFacts,
    val decisionFields: ImportConfirmDecisionFields,
)

fun interface ImportCandidateFormalFactory {
    fun create(input: ImportCandidateFormalizationInput, ids: ImportCommitIds): DomainResult<ImportFormalCommit>
}

interface ImportCandidateCommitPort {
    fun commitOnce(
        identity: ImportRequestIdentity,
        snapshot: ImportCandidateDecisionSnapshot,
        createFormalTransaction: (ImportCandidateFormalizationInput) -> DomainResult<ImportFormalCommit>,
    ): ImportCandidateDecisionResult

    fun commitRejectOnce(
        identity: ImportRequestIdentity,
        snapshot: ImportCandidateDecisionSnapshot,
        allocateStatusId: () -> ImportStatusHistoryId,
    ): ImportCandidateDecisionResult
}
```

- `ImportConfirmFields`（P4-02）由 `ImportConfirmDecisionFields.OrdinaryFlow` 取代（字段逐一相同）；`ImportCandidateDecisionSnapshot.confirmFields` 由 `confirmDecisionFields: ImportConfirmDecisionFields?` 取代（REJECT ⇒ null）；`ImportCandidateDecision` 仍为 {CONFIRM, REJECT}。`ImportCandidateDecisionSnapshot.ledgerId` 删除：confirm/reject 的唯一 authoritative ledger identity = `ImportRequestIdentity.ledgerId`，snapshot、factory 或 store 不再携带/接受第二个 ledger 值。P4-02 ordinary 行为不变，仅 API 去冗余。
- `ImportCandidateFormalFactory.create` 修订为消费完整的 immutable `ImportCandidateFormalizationInput`，不再只接收 resolved facts；input 恰由本次 `identity.ledgerId`、store 从同 ledger 持久化 source 解析的 facts、以及本次已冻结 `snapshot.confirmDecisionFields` 组成。生产 factory 构造器不得再捕获 category/funding/from/to；同一个 input 实例既传给 callback，又用于 pre-persist binding validator 和 decision_snapshot 持久化，禁止三次独立组装。
- confirm 校验顺序（冻结；P4-02 规格 §8 顺序的唯一插入点 = kind 门）：以 `identity.ledgerId` claim（decision=confirm_candidate）→ claim 失败 ⇒ 在同 ledger 重读已存 decision_snapshot 同请求重判（哈希在前：不等 ⇒ SPINE_STALE_FINGERPRINT；然后冻结字段清单：decision=='confirm' 且 candidate_id、category_id、funding_account_id、from_account_id、to_account_id、explicit_confirmed_at 七项各自与入站快照对应项相等 ⇒ NoChange 原 receipt 原子重放，任一不等 ⇒ SPINE_REQUEST_IDENTITY_CONFLICT）→ claim 成功 ⇒ 快照形状校验（decision=CONFIRM 且 confirmDecisionFields != null，否则 SPINE_REFERENCE_INTEGRITY_VIOLATION）→ 以 `identity.ledgerId` 查候选（否则 SPINE_CANDIDATE_NOT_FOUND）→ status 校验（incomplete ⇒ SPINE_CANDIDATE_INCOMPLETE；其余非 pending ⇒ SPINE_CANDIDATE_NOT_PENDING）→ kind 门（candidate_kind=`ordinary_flow` 且 fields 为 OrdinaryFlow ⇒ 通过；`transfer_flow` 且 fields 为 TransferFlow ⇒ 通过；`transfer_flow_missing_leg` ⇒ SPINE_TRANSFER_NOT_CONFIRMABLE；其余组合 ⇒ SPINE_DECISION_KIND_MISMATCH）→ 哈希 stale 校验 → 同 ledger 证据/绑定校验（SPINE_REFERENCE_INTEGRITY_VIOLATION）→ 构造一次 `ImportCandidateFormalizationInput(identity.ledgerId, resolved, snapshot.confirmDecisionFields)` → callback create(input) → DomainResult.Failure ⇒ SPINE_DOMAIN_VALIDATION_FAILED 零残留 → `validateImportFormalBinding(input, created)` 不通过 ⇒ SPINE_REFERENCE_INTEGRITY_VIOLATION 零残留 → 成功 ⇒ 从同一 input.decisionFields 持久化决策快照（普通：category/funding 非空、from/to NULL；转账：category/funding NULL、from/to 非空）→ status_history confirmed → confirmation 行 → receipt，同一事务。
- 等价重放字段清单（§7 冻结列）：resolveConfirm 按上述七项（decision、candidate_id、category_id、funding_account_id、from_account_id、to_account_id、explicit_confirmed_at）比对；普通快照的 transfer 字段为 null、与既有普通行 from/to=NULL 等价，转账快照同理，null-pattern 即 kind 判别，无需额外判别列。resolveReject 增加 from_account_id == null && to_account_id == null 两项。
- reject 校验顺序不变（任何 pending 候选、任意 kind 均可 reject；§3）。
- 冲突行为不变：等价 ⇒ 原 receipt；不等价 ⇒ SPINE_REQUEST_IDENTITY_CONFLICT 零写入；candidate_not_pending 复用 SPINE_CANDIDATE_NOT_PENDING；invalid legs（同账户/非资产/币种不符/未知账户/方向与钱包不一致）统一经工厂 DomainResult.Failure ⇒ SPINE_DOMAIN_VALIDATION_FAILED 复用（零残留、含 claim 回滚、request identity 可用，P4-02 O-29 先例）。
- 唯一 ledger 纪律：confirm/reject/intake 的 request identity 仍按 D-098 claim-first；本批只修订 candidate decision path，所有 SQL lookup/insert、formal command 与 receipt 的 ledger 均取 `identity.ledgerId`。candidate_id 相同但 ledger 不同不得 fallback 到另一 ledger；E-35 必须在 callback/ID 分配前以 SPINE_CANDIDATE_NOT_FOUND 零残留拒绝。
- `validateImportFormalBinding` 位于 ledger-application commonMain，是 `persistFormal` 前的纯 contract validator，不修改 ledger-domain。TransferFlow 分支必须遍历并校验 callback 返回的完整 graph，而非只读取 current postings：transaction.ledgerId=input.ledgerId、kind=ACCOUNT_TRANSFER；`versions.size == 1`、`postingSets.size == 1`，transaction.currentVersionId 指向 sole version，sole version.transactionId 指向该 transaction、versionNumber=1、postingSetId 指向 sole posting set；sole version 的 occurredAt/statisticsAt/effectiveAt 分别且全部等于 persisted source 的 `resolved.occurredAt`，note=NULL；sole posting set 恰好两条 posting，index 0 的 from posting=(decision.fromAccountId, -resolved.amountMinor, resolved currency code/precision)，index 1 的 to posting=(decision.toAccountId, +resolved.amountMinor, 同币种/精度)，无额外 version、posting set 或 posting。数据库专属且 callback 不可控的 `ledger_transaction.canonical_kind` 与 `transaction_version.confirmation_id` 继续由 `persistFormal` 固定写为 NULL，并由 §1.4 oracle 验证。上述任一不符必须在首次 formal INSERT 前复用 SPINE_REFERENCE_INTEGRITY_VIOLATION 并回滚 claim，保证 14 表 delta 全空；OrdinaryFlow 维持 P4-02 路径，本批不扩写其会计语义。E-34 B01…B13 与 T-48 对同一不变量清单逐项证明 fail-closed。
- 方向与钱包腿约束（Q2/Q4 冻结）：支出行 ⇒ 钱包是 FROM 腿；收入行 ⇒ 钱包是 TO 腿。约束在生产工厂以装配的 `walletAccountId` 校验（§4.3）；store 不做钱包校验（store 无 catalog/钱包身份）。confirm 只作用于 pending 且 valid_complete 的候选，resolved.directionToken 必为已映射 in/out。

### 4.3 生产 transfer 工厂（ledger-application commonMain；ledger-domain 零改动）

```kotlin
class TransferFlowFormalFactory(
    private val catalog: LedgerCatalog,
    private val walletAccountId: AccountId,
) : ImportCandidateFormalFactory {
    override fun create(input: ImportCandidateFormalizationInput, ids: ImportCommitIds): DomainResult<ImportFormalCommit>
}
```

- factory 首先要求 `input.decisionFields is TransferFlow`，再从该值局部取得 fromAccountId/toAccountId；构造器不存在 legs，禁止从其他字段或配置重新推导。方向门先于领域原语：`input.resolved.directionToken == "out" ⇒ fromAccountId == walletAccountId`；`"in" ⇒ toAccountId == walletAccountId`；否则返回 DomainResult.Failure。冻结 failure 载体 = `DomainViolation.InvalidOrdinaryIncome`（沿用现有 spine E2E factory 对应用层不可解析确认输入的既有映射先例 ImportSpineWechatEndToEndTest.kt:293；DomainViolation 为 ledger-domain sealed 接口、本批零改动，spine 不比较 violation 内容——P4-02 规格 §8、SqlDelightImportSpineStore.kt:278-285）。该名称与转账语义不匹配，仅是维持 ledger-domain 零改动的兼容载体：轮 B 实现必须在返回点写明这一原因和 §9 第 7 项技术债引用，禁止把它解释为 ordinary income 行为或作为未来转账先例；替代方案与偿还触发器继续显式保留在 §9 第 7 项。
- 正式效果：`createOwnAssetPrincipalTransfer(catalog, OwnAssetPrincipalTransferCommand(input.ledgerId, fromAccountId, toAccountId, Money.ofMinor(input.resolved.amountMinor, CurrencyUnit(input.resolved.currencyCode, input.resolved.currencyPrecision)), TransactionTimes.collapsed(Instant.parse(input.resolved.occurredAt))), OwnAssetPrincipalTransferIds(transactionId=ids.formalIds.transactionId, versionId=ids.formalIds.versionId, postingSetId=ids.formalIds.postingSetId, sourcePostingId=ids.formalIds.postingIds[0], destinationPostingId=ids.formalIds.postingIds[1]))`——唯一转账原语。唯一 ledger 来源是 input.ledgerId（即 request identity）；唯一 leg 来源是 input.decisionFields。OwnAssetPrincipalTransfer.kt:40-128 产生两条资产分录 PRINCIPAL_OUT/PRINCIPAL_IN、internalTransferMinor=金额、netWorthChangeMinor=0、kind ACCOUNT_TRANSFER；统计时间 = 来源支付时间（docs/ACCOUNTING_RULES.md:202-204），confirmed_at 独立于时间字段。
- leg 有效性（同账户 DistinctAccountsRequired、非资产/非自有/非真实 OwnedRealAssetRequired、未知账户 KnownAccountRequired、币种不符 SameCurrencyRequired、非正金额 AmountMustBePositive）全部由原语返回的 DomainResult.Failure 承载 ⇒ SPINE_DOMAIN_VALIDATION_FAILED。
- 任何腿都绝不从交易对方/商品/备注等 provider 列文本推导（provider DTO 零引入，P4-03 规格 §2.3；D-032:387；隐私边界 docs/ARCHITECTURE.md:106-108）。
- 会计锚点：AccountTransfer.kt:91-237（RG-03 竖井 factory、可选 FEE 腿）仅作冻结竖井先例，不复制进 spine；本批 principal-only（§8）。

## 5. 诊断码

复用优先、只注册真正新增的码（P4-03 规格 §5 政策）。解析级零新增：CONFLICTING_SOURCE_FACTS、REQUIRED_FACT_UNRESOLVED/REQUIRED_FACT_MISSING、FIELD_AMOUNT_INVALID、FIELD_TIME_INVALID、INPUT_*、STRUCTURE_MISMATCH 复用 D-097:1459；SPINE_WEIXIN_UNSUPPORTED_TX_TYPE、SPINE_WEIXIN_REFUND_UNSUPPORTED、SPINE_WEIXIN_UNKNOWN_TOKEN 复用 P4-03 规格 §5（语义不变，仅路由集合修订）。spine 级新增 2 码（severity 值域沿用 P4-02 规格 §3 的 `fatal | conflict | invalid | stale` 扩展；scope 沿用 `input | container | structure | record | field | request | candidate`；安全 location 只由有界 opaque 合成引用构成，D-097:1461）：

| code | severity | scope | 安全 location |
| --- | --- | --- | --- |
| SPINE_DECISION_KIND_MISMATCH | invalid | candidate | {candidate_id} |
| SPINE_TRANSFER_NOT_CONFIRMABLE | invalid | candidate | {candidate_id} |

- SPINE_DECISION_KIND_MISMATCH：confirm 决策字段 kind 与候选 candidate_kind 不一致（普通候选 + TransferFlow，或 transfer 候选 + OrdinaryFlow；§4.2 kind 门）。
- SPINE_TRANSFER_NOT_CONFIRMABLE：candidate_kind = `transfer_flow_missing_leg` 的 confirm 决策（本片确认门关闭；§3）。
- 不注册 provider 前缀同义码；缺腿候选的 parse 级输出零诊断（五事实完整 ⇒ valid_complete，§3 说明）。
- no_change 的 reason_code 固定 token `equivalent_replay`（P4-02 规格 §3 不变）；每个 rejected 结果携带恰好一个诊断码。

## 6. 模块与依赖

- 解析器：`ledger-application` jvmMain（不变位置；WechatSourceTokens.kt token 集合修订、WechatBillParser.kt 判定顺序/record kind 产出修订、WechatParserTypes.kt 的 `WechatRowResult.Accepted` 增加 `recordKind: ImportRecordKind`；诊断类型与集合不变；POI 依赖与 jvm-only 边界不变，P4-03 规格 §6）。
- 应用合同与生产工厂：`ledger-application` commonMain（ImportSpine.kt 合同修订 + 新文件 TransferFlowFormalFactory.kt，含 `validateImportFormalBinding` 纯 validator；依赖 ledger-domain，无新增依赖）。
- 持久化：`ledger-data`——21.sqm（§7）+ Ledger.sq fresh DDL 同步 + SqlDelightImportSpineStore 修订（intake kind 持久化、confirm kind 门与四列决策快照、等价清单扩展）。
- `ledger-domain` 零改动（复用 createOwnAssetPrincipalTransfer，OwnAssetPrincipalTransfer.kt:40-128）。
- 无新 Gradle 模块；无产品 Clock 端口、无产品随机 ID 算法（D-098:1519）；APK/Android 装配不在本批。

## 7. DDL 设计与迁移 21.sqm（v21→v22）

新列与 CHECK 全部落在既有 spine 表，不新增表（非 rgXX_ 前缀，D-092:1329）；竖井表零改动；Ledger.sq 中与迁移同形状同步（fresh = migrated，20.sqm:7 纪律）。

冻结 DDL 终态：

- `import_source_record.record_kind TEXT NOT NULL CHECK (record_kind IN ('ordinary_flow_source', 'transfer_flow_source', 'transfer_flow_source_missing_leg'))`；`contract_version INTEGER NOT NULL CHECK (contract_version IN (1, 2))`，并增加配对 CHECK：`(record_kind = 'ordinary_flow_source' AND contract_version = 1) OR (record_kind IN ('transfer_flow_source', 'transfer_flow_source_missing_leg') AND contract_version = 2)`（其余列不变）。该终态保留所有既有 ordinary v1 行且 fail-closed 拒绝 transfer-v1/ordinary-v2 错配。
- `import_candidate.candidate_kind TEXT NOT NULL CHECK (candidate_kind IN ('ordinary_flow', 'transfer_flow', 'transfer_flow_missing_leg'))`（其余列不变）。
- `import_candidate_decision_snapshot`：在 category_id/funding_account_id 后新增 `from_account_id TEXT`、`to_account_id TEXT`（无 FK——category/funding 同款先例，账户合法性由领域 factory 校验），CHECK 重写为 XOR：

```sql
CHECK (decision != 'confirm' OR
       (category_id IS NOT NULL AND funding_account_id IS NOT NULL
        AND from_account_id IS NULL AND to_account_id IS NULL)
       OR
       (category_id IS NULL AND funding_account_id IS NULL
        AND from_account_id IS NOT NULL AND to_account_id IS NOT NULL)),
CHECK (decision != 'reject' OR
       (category_id IS NULL AND funding_account_id IS NULL
        AND from_account_id IS NULL AND to_account_id IS NULL))
```

- `import_request.operation` 值域不变（transfer confirm 复用 `confirm_candidate`，共享端口推论）；`import_confirmation`/`import_receipt`/`import_evidence`/`import_candidate_requires_confirmation`/`import_candidate_status_history` 形状零改动。

迁移 21.sqm 六阶段（沿用 20.sqm 模板；语句逐条执行、无内部事务，原子性由调用方事务保证）：

1. 创建——本批无新表，本阶段为空并注释原因。
2. 回填——本批无跨表数据移动，本阶段为空并注释原因（新列值随 Stage 4 逐行拷贝）。
3. fail-closed 数据守卫：guard 表 `CHECK (value = 0)` + 条件 INSERT——断言迁移前既有 source 行全部为 ordinary kind + contract_version=1（transfer kind、非 v1 ordinary 或 kind/version 错配计数均为 0），transfer 类 candidate_kind 计数为 0、既有 decision 行满足旧 CHECK 形状；guard 表在本阶段内创建并即删。
4. 分阶段重建（3.sqm:1-40 完整依赖链重建模板，FK 保持开启；SQLite 无法修改 CHECK 约束）：八张表按依赖顺序重建——`import_source_record`、`import_evidence`、`import_candidate`、`import_candidate_requires_confirmation`、`import_candidate_status_history`、`import_candidate_decision_snapshot`、`import_confirmation`、`import_receipt`。先为这八张表建立 stage 拷贝（`CREATE TABLE X_stage AS SELECT * FROM X`，3.sqm 先例），再按子→父删除旧表（`import_receipt` → `import_confirmation` → `import_candidate_decision_snapshot` → `import_candidate_status_history` → `import_candidate_requires_confirmation` → `import_evidence` → `import_candidate` → `import_source_record`；触发器随表自动删除），再按父→子以新形状重建并回填（`import_source_record`（新 record_kind/contract_version 配对 CHECK）→ `import_evidence`（原形状）→ `import_candidate`（新 candidate_kind CHECK）→ `import_candidate_requires_confirmation` → `import_candidate_status_history` → `import_candidate_decision_snapshot`（新列 + XOR CHECK；回填显式列清单：旧 8 列 + NULL, NULL）→ `import_confirmation` → `import_receipt`），最后删除八张 stage 表。`import_request` 不重建（被引用、形状不变）。守卫触发器在 Stage 4 前随表删除、Stage 6 重建（3.sqm/19.sqm 先例）。
5. 删除——本批无既有表删除，本阶段为空；竖井零改动以恒假 count 断言登记 rg03/rg04/rg08 owner 仍可查询（20.sqm Stage 4 形状）。
6. 不可变/追加-only 与状态迁移触发器重建（20.sqm:195-227 同形状：上述八张重建表各自的 BEFORE UPDATE/DELETE ABORT 守卫 + `import_candidate_status_history` 的 sequence/transition BEFORE INSERT 守卫）。E-40/T-44 必须逐表、逐类断言，不能以触发器总数替代。

- 迁移 verifier 计划：构建期 `:ledger-data:verifyCommonMainLedgerDatabaseMigration`（README 命令）+ `LedgerDatabaseMigrationTest` 的 fresh 版本断言 21 → 22 涟漪（LedgerDatabaseMigrationTest.kt:287 当前断言 21）+ 本批新增迁移测试（E-40：fresh = migrated 经真实入口验证、v21 数据存活、rg 竖井共存、reopen、守卫 re-armed；E-41：真实 migration 在 production-equivalent outer transaction 的 Stage 6 后故障，完整回滚并可重试）。E-41 沿用 `ImportSpineMigrationCoexistenceTest.versionTwentyToTwentyOneDdlFailureRollsBackEverySpineOwner` 的文件库/outer-transaction 先例，但把故障点推进到迁移全部语句执行后、commit 前，并增加 user_version/FK/stage-table 断言。
- SQLite 版本约束：Stage 4 只用 DROP/CREATE/INSERT SELECT（3.sqm 已证明的组合），无 RENAME、无 ALTER；不引入任何新 SQLite 版本要求，维持既有 ≥ 3.35.0 / minSdk 34 基线（docs/ARCHITECTURE.md:147）。
- append-only 纪律：重建只允许逐行等价拷贝（阶段守卫断言各表 stage 计数 == 重建后计数），禁止 UPDATE/DELETE 任何既有行语义；重建后守卫触发器阻止一切后续 UPDATE/DELETE。
- ledger-domain 零改动声明：本批正式效果完全由既有 `createOwnAssetPrincipalTransfer` 产生；无领域文件修改、无新 DomainViolation（§4.3 方向门载体的登记说明）。

## 8. 测试计划

jvmTest（ledger-application 与 ledger-data；模式沿用 P4-02 规格 §9 与 P4-03 规格 §7：in-memory + 文件库、CountDownLatch 并发、failure injector、direct SQL 守卫断言、reopen、合成 xlsx 构建器）：

- T-01…T-12 对应 P-01…P-12 逐操作断言：facts 字段级、record_kind 及其派生 contract_version、completeness、诊断 code/severity/scope/location、零 record/零写入、W7 重解析（P-12）。
- T-13…T-21 一一对应 E-01…E-09：逐操作断言 outcome、Δ、receipt 逐值、IdSource 消耗、contract_version/candidate_kind/rule/confidence 冻结值、状态迁移、kind 碰撞与 kind 分离（E-03/E-04/E-05）。
- T-22…T-40 一一对应 E-10…E-28：逐操作断言 transfer confirm 的 decision_snapshot 四列逐值（category/funding NULL、from/to 非空）、formal 断言（transaction+1/version+1/posting+2 逐币种平衡、posting 账户与金额精确对应 −100.00/+100.00、−200.00/+200.00）、报表断言（internalTransfer=金额、netWorthChange=0、零收入/费用/消费/分类效应——docs/ACCOUNTING_RULES.md:227 与 CORE_ACCEPTANCE_PLAN RL-03 语义）、replay/conflict/stale/NOT_PENDING（E-11~E-14）、NOT_CONFIRMABLE（E-16/E-17）、缺腿 reject（E-18/E-19）、incomplete 拒认（E-20）、方向门独立失败向量（E-21）、实际到达原语的四个领域失败向量与修正重试（E-22~E-26，每变体含 claim 回滚、候选仍 pending、identity 可用、attempt 批次钉死）、kind 失配（E-27/E-28）。每项同时执行 §1.4 complete canonical state 比较。
- T-41 并发（E-29/E-30）：失败方零残留的行级计数与 ImportIdSource 恰好一次。
- T-42 失败注入（E-31/E-32）：全回滚（含 formal 行）、identity 可用、重试成功、批次-1 零痕迹、批次-2 钉死。
- T-43 reopen/replay（E-33）。
- T-44 formal binding mismatch（E-34 十三个独立子向量，case ID 与顺序固定为 B01 `reversed_legs`、B02 `wrong_ledger`、B03 `wrong_kind`、B04 `wrong_amount`、B05 `wrong_currency`、B06 `wrong_precision`、B07 `extra_posting`、B08 `multiple_versions_current_v2`、B09 `extra_posting_set`、B10 `wrong_occurred_at`、B11 `wrong_statistics_at`、B12 `wrong_effective_at`、B13 `non_null_note`）：显式 case table 先断言 ID 集合/顺序与本清单完全相等，再让每个 callback 从同一 immutable input 返回仅对应不变量恶化的领域合法 formal graph；B08 用合法两版本/current v2 链联合覆盖多版本与错误 current version_number。pre-persist validator 必须逐项以 SPINE_REFERENCE_INTEGRITY_VIOLATION 零残留拒绝，并对每项执行 §1.4 complete canonical comparator、assert `persistFormal` 零调用及 `attempt-binding-bNN-*` ID 零痕迹。同时证明 factory 构造器无 ledger/from/to 参数，唯一 ledger/legs 来自同一个 input，且 snapshot 持久化使用该 input 的同一 decisionFields。
- T-45 cross-ledger（E-35）：唯一 ledger 来源为 request identity；另一 ledger 的同文本 candidate_id 返回 NOT_FOUND，callback/IdSource 零调用，两 ledger complete state 不变。
- T-46 成功迁移（E-40）：fresh = migrated（版本 22）、既有 ordinary 行保持 contract_version=1 且可 replay、rg03/rg04/rg08 竖井重放、迁移后 transfer v2 confirm accepted；逐一断言八张重建表 UPDATE/DELETE 全部 ABORT，并断言 status_history 非法 sequence/transition INSERT 全部 ABORT；既有 decision 行 from/to 为 NULL。
- T-47 late-stage migration failure（E-41）：actual 21.sqm 全部执行且 user_version 暂设 22 后、outer transaction commit 前注入 CHECK failure；关闭重开后 complete v21 pre-state/user_version/schema metadata 逐值恢复，无任何 stage/guard/test 表，foreign_keys=1、foreign_key_check=0，随后 retry 成功。
- T-48 应用层：ExecuteImportIntake 的 ledger 不一致 require 拒绝（P4-02 先例）；decision snapshot 不再含 ledgerId；TransferFlowFormalFactory 方向门单元向量（out/in/未知方向）；`validateImportFormalBinding` 的 table-driven 单元测试必须与 T-44 共用同一十三项 ID/名称 manifest，逐项覆盖 reversed legs、wrong ledger/kind/amount/currency/precision、extra posting、multiple versions + current version_number=2、extra posting set、wrong occurredAt/statisticsAt/effectiveAt、non-null note，并另有 canonical graph 通过向量。每个失败向量断言 validator 在 persistence boundary 前返回同一 binding failure，不得只靠 E-34 端到端测试间接覆盖。
- T-49 哈希与版本：T1 与 T1' 摘要不同；同 facts 不同 recordKind 摘要不同（record_kind 成员值随 kind）；ordinary v1 摘要逐字节保持 P4-02/P4-03 既有值；kind→contract_version 映射与 DDL 配对 CHECK 拒绝 transfer-v1/ordinary-v2；R3-style 成员省略可复现（P4-02 规格 §6 转义向量沿用）。
- T-50 R-01：P4-02 30-op oracle 逐值不变。
- T-51 R-02：P4-03 E-01…E-11/E-13/E-14 逐值不变；E-12 精确断言 8→9 个 record/candidate、W7 成为 transfer v2 pending、拒行 6→5、诊断 9→8（§1.3 第三处修订）。
- T-52 R-03：P4-03 P 系列逐值不变，唯 W7/P-07 路由与 P-14 的记录数 8→9、诊断 9→8 两处解析级冻结修订（§1.3）。

T-13…T-47 除各自专项断言外全部调用同一 §1.4 canonical comparator；T-50…T-52 继续运行冻结前序 oracle，防止 canonical helper 只覆盖新路径。测试 manifest 必须精确覆盖 P-01…P-12、E-01…E-35、E-40…E-41、R-01…R-03，无遗漏或重复 ID。

## 9. 边界断言（本批不含）

- 不含 matcher/evidence-link、posting 匹配/绑定与 reconciliation（P4-08）；不含 dedup/duplicate 数据合同（P4-07）；不含产品随机 ID 算法与产品/应用 Clock 端口（后续阶段门）；不含 zip 解包与 6 位密码处理（平台适配层）。
- 不含新接受类型：红包类 fail-closed（未分配批次）、退款类 fail-closed（→ P4-06）、未知 token fail-closed；零钱通/零钱理财类 token 不在冻结集合 → SPINE_WEIXIN_UNKNOWN_TOKEN（RL-03 anchor 的 report 语义由提现/充值 fixture 验证，§9 第 6 项）。
- 手续费：本金-only 平衡转账；`零钱提现手续费` 等手续费类行不在冻结集合 → 现有 fail-closed 路由（D-031 手续费为独立支出，本批不实现）；缺腿候选永不生成第二笔转账/收入（D-033:401 镜像合并留 P4-08）。
- provider DTO 零引入：交易对方/商品/支付方式/交易单号/商户单号/备注的值与交易类型 token 不进入事实、候选或任何持久化；任何腿账户绝不从上述列推导。
- schema 变更仅限 §7 范围；golden 冻结契约与 `.external/` 零改动；竖井表零改动（仅作 jvmTest 冻结语料）；ledger-domain 零改动。
- 永不自动确认（D-073:895）；拒行与失败路径零残留；只有获胜首请求调用应用回调并消耗 ID。
- 不声明 RL-03 闭合（mirror/evidence-link 与全量闭合留 P4-08/P4-09，WORK_PLAN.local.md:122）。

## 10. 开放问题（供主代理/独立评审定夺）

1. P4-03 冻结令牌表与 oracle 的类型转移修订：本规格 §2.1/§1.3 R-02/R-03 恰好冻结三处：(1) W7/P-07 由拒行改 transfer v2 record；(2) P-14 记录数 8→9、诊断 9→8；(3) E-12 full-batch intake 由 8→9 个 record/candidate、W7 新增 transfer pending、拒行 6→5、诊断 9→8。其余逐值不变。推荐：批准——这是 D-099:1539 已登记的 P4-04 类型转移的必然结果，不是回归。
2. 状态接受子集最小扩展 `提现已到账`（§2.2）：推荐批准；否则 T1/W7 将降级为 incomplete 且本批没有任何真实形状的提现确认 fixture。
3. R 系列「byte-identical」口径：本规格冻结为 P4-02 30-op 逐值不变；P4-03 除第 1 项列明的三处跨规格修订外逐值不变。推荐采用本口径。
4. 21.sqm 依赖链重建体积（8 张 spine 表 stage+drop+create）：推荐按 3.sqm 模板执行（FK 保持开启、仓库既有先例）；备选 defer_foreign_keys 方案无仓库先例且 SQLDelight 对 .sqm 内 PRAGMA 的可解析性未验证，不推荐。
5. `ImportConfirmFields` 名称由 `OrdinaryFlow` 变体取代（字段逐一相同，§4.2）：推荐登记为「P4-02 规格 §8 形状经 D-100 修订」并在批准后不改写 P4-02 规格文件（该文件保持其批次历史）。
6. RL-03 anchor（零钱通 `GL-A3CB7F3D48BC`）类型不在冻结集合：本规格以提现/充值 fixture 验证其语义（两条资产分录平衡、不进入对外收支）。推荐：批准本口径；零钱通/零钱理财类 token 保持 UNKNOWN_TOKEN fail-closed，其类型集合与方向证据留待后续批次。
7. 方向门失败载体（§4.3）：本规格冻结沿用 `DomainViolation.InvalidOrdinaryIncome`（ImportSpineWechatEndToEndTest.kt:293 先例；DomainViolation 为 ledger-domain sealed 接口，本批 ledger-domain 零改动，spine 不比较 violation 内容）。备选：(a) 请求改携带 walletAccountId + otherAccountId、由 store 按方向机械派生 from/to（方向违约结构性不可能，但改变 Q4 冻结的 from+to 形状）；(b) 新增专用领域 violation（违反 ledger-domain 零改动边界）。推荐维持本规格冻结。
   技术债处置：`InvalidOrdinaryIncome` 名称与转账语义不匹配，轮 B 必须在返回点加兼容性注释并引用本项；下一个获批且允许修改 ledger-domain 的 transfer 批次必须重新评估并优先迁移到专用 violation，不能把该兼容载体默认为长期合同。
8. 本文件放置（docs/specs/）与命名沿用 P4-02 规格 §11.4 先例；批准后状态标记由 proposal 改为 approved。
9. docs/PROJECT_MAP.md 是否为本文件与轮 B 的 21.sqm 增加导航条目：属既有文件修改，本批 writer 无权执行，由主代理裁决。
10. 轮 B 完成后本文件应与本地 checkpoint/plan（PROJECT_STATE.local.md、WORK_PLAN.local.md）的实施登记同步（本地文档由主代理维护）。

## 轮 B 建议文件布局（仅建议，不创建）

- `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/ImportSpine.kt`（§4.1/§4.2 合同修订：ImportRecordKind、ImportConfirmDecisionFields、请求/快照修订）。
- `ledger-application/src/commonMain/kotlin/com/unifiedledger/application/TransferFlowFormalFactory.kt`（§4.2/§4.3 生产工厂与 `validateImportFormalBinding`）。
- `ledger-application/src/jvmMain/kotlin/com/unifiedledger/application/import/wechat/WechatSourceTokens.kt`、`WechatBillParser.kt` 与 `WechatParserTypes.kt`（§2 修订：token/路由/Accepted recordKind 类型合同）。
- `ledger-data/src/commonMain/sqldelight/com/unifiedledger/data/db/21.sqm` 与 `Ledger.sq`（§7 终态同形状）。
- `ledger-data/src/commonMain/kotlin/com/unifiedledger/data/SqlDelightImportSpineStore.kt`（intake kind、confirm kind 门与四列决策快照、等价清单）。
- `ledger-application/src/jvmTest/.../import/wechat/WechatBillParserJvmTest.kt`（T-01…T-12、T-49）与 `ledger-data/src/jvmTest/.../ImportSpineTransferEndToEndTest.kt`（T-13…T-48、T-50…T-52；含 `P404CanonicalState`、合成 xlsx 构建器与 P4-02/P4-03 装配复用）。
