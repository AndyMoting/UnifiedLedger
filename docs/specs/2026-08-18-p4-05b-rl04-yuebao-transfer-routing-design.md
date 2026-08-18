# P4-05b RL-04 余额宝转账路由设计（冻结规格）

**Status:** approved — 2026-08-18 用户批准（`docs/DECISIONS.md` D-102）。证据门已完成（主代理字节级复核 9 份真实导出：9 行余额宝行全部 交易分类=`投资理财`、收/支=`不计收支`，两腿确认与负向登记见 §Authority And Boundary）；本规格把该证据门的格式事实转换为中立契约。本规格已于 2026-08-18 由实施批落地（独立 worktree `feat/rl04-yuebao-transfer`）：`AlipaySourceTokens.kt`（冻结子类型集合/方向映射/rule 常量 + 负向登记）、`AlipayCsvParser.kt`（判定顺序第 2 步 + `parseInvestmentRow`）、RL-04 解析级测试 17 条 + E2E 9 条全绿，P4-05 既有 28+5 保持绿（R-01）；经独立 spec 评审/独立 quality 评审/distinct verification 后闭环。

**Scope:** 冻结 RL-04 余额宝转账路由批（支付宝 `投资理财` 交易分类 + `余额宝-*` 商品说明（列 4）子类型判别 → P4-04 转账语义路由）的匿名 fixtures/解析契约变更/类型范围矩阵/诊断码（零新增）/模块与依赖/测试计划与边界断言。本文件只冻结规格，不含实现。本批证明共享 spine 非首个来源特化（PHASE4_DESIGN_PACKAGE.local.md:84-87）+ RL-04 一对一转账/formalization 子切片（WORK_PLAN.local.md:123）。全部可复用原语——`ImportRecordKind.TRANSFER_FLOW_SOURCE`、`ImportConfirmDecisionFields.TransferFlow`、`TransferFlowFormalFactory`、ACCOUNT_TRANSFER 方向门——已在 main 实现（P4-04 轮 B，D-100），本批零新增域原语、零 schema/spine 改动（§5、§7）。

## Authority And Boundary

本规格全部条款对齐以下权威（行号为当前本地 main 的 tracked 文件行号）：

- 批定义：WORK_PLAN.local.md:123（RL-04 = second-source one-to-one transfer/formalization：P4-05 source/formalization 子切片 + P4-08 posting reconciliation + P4-09 全量闭合；本批只交付解析路由子切片，不创建 mirror evidence link、不改变 posting reconciliation）；PHASE4_DESIGN_PACKAGE.local.md:84-87（第二个获批标准来源复用共享合同，证明主干不是首个来源特化；provider/format/parser 技术独立过门，不能由 P4-03 的批准外推）；P4-05 规格 §8 第 2 项与 §9.4（「RL-04 路由延后至后续独立批次（D-10x）在此纠正基础上重新定界」——本批即该批次）。
- 基础契约（本批复用、零修订）：docs/specs/2026-08-17-p4-05-alipay-ordinary-flows-design.md §2.1/§2.2（输入解码、行定位、规范表头——2026-08-18 纠正后 12 token + 行尾逗号）、§2.3（字段分割与结构不变量）、§2.4（列映射；本批仅对其「列 4 商品说明」「列 5 收/支」做登记修订，见 §2）、§2.5（记录级语义与批次 outcome）、§3.3（状态语义：`交易成功`→settled 为本批唯一接受映射）、§4（诊断码）、§9（纠正修订记录；§9.4：`投资理财` 本为 fail-closed `SPINE_ALIPAY_UNKNOWN_TOKEN` 拒行，RL-04 路由延后至 D-10x——本批对该条款做登记修订，§3）。
- 转账语义（复用，零修订）：docs/specs/2026-08-14-p4-04-transfer-formalization-slice-design.md §2.3（kind 路由：类型路由列派生 record_kind）、§4.2（`ImportConfirmDecisionFields.TransferFlow(fromAccountId, toAccountId)` 确认契约、kind 门、SPINE_DECISION_KIND_MISMATCH）、§4.3（`TransferFlowFormalFactory` 方向门：out ⇒ from=钱包、in ⇒ to=钱包）、§1.4（complete canonical state oracle 模式，本批 E 系列复用）、§5（spine 级诊断码）。
- 决定记录：D-101（DECISIONS.md:1576-1603，P4-05 契约与 2026-08-18 纠正修订：表头/时间列/「9/9 无余额宝 token」负证据纠正；「最近似 token 登记保留供 D-10x 参考」）；D-100:1548-1574（P4-04 转账契约：批界/类型范围/确认契约/腿建模/方向门）；D-099:1530-1546（类型转移登记纪律 :1539、fail-closed 类型范围、格式事实行为证据 :1538、parser 技术六维模板 :1537）；D-097:1439-1473（五事实/presence/completeness :1447/:1451/:1453、未知 token 政策 :1449/:1455、类型化诊断 taxonomy :1459、安全 location :1461）；D-098:1475-1528（spine 合同：raw identity、candidate lifecycle、claim-first）；D-092:1325-1340（方案 A 共享链）。
- 外部锚点：CORE_ACCEPTANCE_PLAN（.external/requirements/golden-ledger/，只读）:63 RL-04 行 `GL-A6F5A461E605`「支付宝余额转入余额宝」= 验证两条资产分录平衡、组合账户展示和分账户对账；golden_expected_fund_movements.csv:94-95（来源账户 −X → 目标账户 +X 平衡两腿；组合账户展示下 支付宝余额 与 余额宝 为两个独立资产账户）。**真实金额/时间见 .external 只读 fixture 注册值，本规格不复制**（P405FIX-QUAL-001 隐私先例）。本批闭合「两条资产分录平衡」的解析路由前提（产出可确认的 transfer 候选）；组合账户展示属产品 UI 维度（阶段 5）；分账户对账属 P4-08。
- 证据门：docs/PROJECT_STATE.local.md:75-103（RL-04 余额宝转账研究总结，2026-08-18）、:157-170（P4-05 证据门备忘——原「9/9 无余额宝 token」负证据已于 D-101 修订撤回）。备忘本身非权威、未裁决，本规格是其格式事实的中立契约转换。
- 账务规则：docs/ACCOUNTING_RULES.md:52-60（转账：本金只在用户自己的资产或负债账户间一对一互转；导入来源已明确两端时生成完整草稿）、:164-168（支付钱包充值 = 内部转账不计消费——余额宝转入同款语义）、:227（RG-09 主例：转账保留正常转账身份，不产生普通收入/费用/消费/预算/分类或对外现金流）。
- AGENTS.md 边界：退款与真实冲回是独立经济事件（退款类拒行登记）；金额精确整数最小单位；parser 只产生带 provenance 与 confidence 的候选，只有明确确认创建正式交易。

术语：`本批` = RL-04 余额宝转账路由批；`子类型` = 商品说明（列 4）中以 `余额宝-` 开头的冻结判别 token（通用字段标签，非个人数据）；`路由分支` = 判定顺序第 (2) 步的余额宝转账分支（§2.3）；`冻结集合` = `{余额宝-自动转入, 余额宝-转出到余额}`（§3 矩阵）；`拒行` = 本批按冻结类型范围 fail-closed 拒绝的数据行（零 record）；`wallet 账户` = 来源行证明的支付宝余额（余额）账户腿，装配为 `TransferFlowFormalFactory` 的 `walletAccountId`（P4-04 §4.3 先例）。

## 1. 匿名 fixtures 集

全部输入为合成、来源中立、provider-neutral 数据；不含真实 provider、真实个人数据、绝对路径、原文件名、真实商户、真实单号或可识别交易。所有 ID 为合成字符串。金额一律精确整数最小货币单位（合成值），禁止二进制浮点。9 行真实样本只以「子类型分布 × 状态 × 路由/拒行预期」的结构形式出现，金额/时间一律替换为合成值。

### 1.1 基础状态

- ledger：`ledger-rl04`（单账本）。
- catalog：自有真实资产账户 `account-alipay-balance`（CNY，支付宝余额）与 `account-yuebao`（CNY，余额宝）——两个独立资产账户（golden 锚点组合账户展示语义；P4-02 命名纪律）。余额宝账户 ID 只存在于确认输入/catalog 侧，解析层与 spine 不物化（§2.4 设计决策 3）。
- 输入引用（opaque synthetic input ref）：`batch-rl04-a`（主 CSV，GB18030 编码合成字节，数据行 Y-01…Y-15）。
- 合同版本：余额宝转账行 record_kind = `transfer_flow_source`、contract_version = 2（D-100 冻结映射，kind→version 封闭派生）；candidate rule = `transfer_flow_source`、rule_version = 1；confidence = valid_complete → `1.00`、valid_incomplete → `0.50`；requires_confirmation 恒为 `formal_transaction_creation`。
- 合成 CSV 结构沿用 P4-05 §1.1（元数据区 0-based lines 0-22、表头 line 23 = 12 列 + 行尾逗号、数据行自 line 24、`record_ordinal` = row − 24、行尾 CRLF/LF 约定）；有界输入约束（10MB/20,000 行）不变。

### 1.2 来源记录 Y-01…Y-15（全部合成值，批 A 数据行）

Y-01…Y-09 镜像 9 行真实样本的匿名化结构（Y-01…Y-07 对应取证 7 行 `余额宝-自动转入`，收/付款方式形状 空×3 + `账户余额`×4；Y-08 对应唯一样本 `余额宝-单次转入`（交易关闭）；Y-09 对应唯一 `余额宝-转出到余额`（收/付款方式 `余额`））。Y-10…Y-15 为防御性合成扩展行（真实数据无，显式标注「合成」），用于钉死 fail-closed 与先例边界。金额/时间均为合成值（金额形状 `\d+\.\d{2}`，时间 `YYYY-MM-DD HH:MM:SS`）。

| 记录 | 交易分类 | 收/支（列 5） | 商品说明（列 4，子类型） | 交易状态 | 收/付款方式（列 7，不解析） | 预期解析结果 |
| --- | --- | --- | --- | --- | --- | --- |
| Y-01 | 投资理财 | 不计收支 | 余额宝-自动转入 | 交易成功 | 空 | 接受：facts (amount_minor, CNY, 2)/out/settled，`transfer_flow_source`、v2，valid_complete，诊断空 |
| Y-02 | 投资理财 | 不计收支 | 余额宝-自动转入 | 交易成功 | 账户余额 | 同上 |
| Y-03 | 投资理财 | 不计收支 | 余额宝-自动转入 | 交易成功 | 账户余额 | 同上 |
| Y-04 | 投资理财 | 不计收支 | 余额宝-自动转入 | 交易成功 | 空 | 同上 |
| Y-05 | 投资理财 | 不计收支 | 余额宝-自动转入 | 交易成功 | 账户余额 | 同上 |
| Y-06 | 投资理财 | 不计收支 | 余额宝-自动转入 | 交易成功 | 账户余额 | 同上 |
| Y-07 | 投资理财 | 不计收支 | 余额宝-自动转入 | 交易成功 | 空 | 同上 |
| Y-08 | 投资理财 | 不计收支 | 余额宝-单次转入 | 交易关闭 | 空 | 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN（子类型 ∉ 冻结集合），零 record |
| Y-09 | 投资理财 | 不计收支 | 余额宝-转出到余额 | 交易成功 | 余额 | 接受：facts/in/settled，`transfer_flow_source`、v2，valid_complete，诊断空 |
| Y-10（合成） | 投资理财 | 不计收支 | 余额宝-转出到银行卡 | 交易成功 | （合成掩码形状） | 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN（缺腿登记，未冻结），零 record |
| Y-11（合成） | 投资理财 | 不计收支 | 余额宝-收益发放 | 交易成功 | （合成掩码形状） | 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN（硬性负向登记：收入 RL-05，禁止路由 TransferFlow），零 record |
| Y-12（合成） | 投资理财 | 不计收支 | 余额宝-自动转入 | 交易关闭 | 空 | valid_incomplete；status raw "交易关闭" 保留、unresolved；`transfer_flow_source`；{REQUIRED_FACT_UNRESOLVED (incomplete/field, field_role=status)}（A-05 先例） |
| Y-13（合成） | 投资理财 | 不计收支 | 基金买入（未知非余额宝 token） | 交易成功 | 空 | 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN（子类型未知，fail-closed），零 record |
| Y-14（合成） | 投资理财 | 支出 | 余额宝-自动转入 | 交易成功 | 空 | 接受：facts/out/settled，`transfer_flow_source`、v2，valid_complete（收/支列不参与方向判定） |
| Y-15（合成） | 投资理财 | 不计收支 | 余额宝-自动转入 | 退款成功 | 空 | 拒行 SPINE_ALIPAY_REFUND_UNSUPPORTED（判定顺序 (1) 优先于路由分支），零 record |

事实形状：`facts (amount_minor, currency_code, currency_precision), direction_token, status_token`；occurred_at 恒为 ISO-8601 offset datetime 文本（+08:00、秒粒度）。Y-01…Y-07、Y-09、Y-14 的 direction_token 由子类型冻结映射派生（`余额宝-自动转入`→"out"、`余额宝-转出到余额`→"in"；§2.2），收/支列值（不计收支/支出）不读取、不参与判定；status_token 由列 8 映射（`交易成功`→"settled"；Y-12 保留 raw "交易关闭" + unresolved）。Y-12 是 D-097:1447/1453 边界内的 valid_incomplete（状态事实未解析；金额/时间/方向/币种可靠，不构成 record error）。

候选冻结值：C1…C7、C9、C12、C14 分别为 Y-01…Y-07、Y-09、Y-12、Y-14 的候选；content_hash 由 intake 边界按 P4-02 §6 规则计算（含 record_kind 成员；解析器不计算、不持久化哈希）。

### 1.3 操作集（P-01…P-16 解析级；E-01…E-12 spine 端到端；R-01…R-02 回归）

`Δ` 与 `零写入`/`零 record` 语义沿用 P4-04 §1.3。spine 端到端复用 P4-04 §1.4 的 complete canonical state oracle 模式（九张 spine 表 + formal chain 五表 + 独立 report projection：transfer 必须 internalTransfer=本金、其余 report 维度全零）。

解析级（P-01…P-16 逐记录；location 的 ordinal 见 §1.2）：

- P-01 Y-01 → 1 条 record：facts/out/settled，`transfer_flow_source`、contract_version 2，valid_complete，诊断空；方向由子类型派生（rule `yuebao_subtype_direction_v1`、confidence exact），收/支列不读取。
- P-02…P-07 Y-02…Y-07 → 同 P-01（收/付款方式 空/账户余额 形状不影响任何判定；direction 恒 out）。
- P-08 Y-08 → 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN（unsupported/record，{input_ref, record_ordinal}），零 record（子类型 ∉ 冻结集合；登记待真实成功样本）。
- P-09 Y-09 → facts/in/settled，`transfer_flow_source`、v2，valid_complete，诊断空。
- P-10 Y-10 → 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN，零 record（缺腿登记，未冻结）。
- P-11 Y-11 → 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN，零 record（硬性负向登记：收益发放 = 收入，属 RL-05，禁止路由 TransferFlow）。
- P-12 Y-12 → valid_incomplete；status raw "交易关闭" 保留、unresolved；`transfer_flow_source`；{REQUIRED_FACT_UNRESOLVED (incomplete/field, field_role=status)}；其余事实可靠。
- P-13 Y-13 → 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN，零 record（子类型未知，fail-closed）。
- P-14 Y-14 → facts/out/settled，`transfer_flow_source`、v2，valid_complete，诊断空（收/支=支出 不参与方向判定；方向仍由子类型派生 out）。
- P-15 Y-15 → 拒行 SPINE_ALIPAY_REFUND_UNSUPPORTED（判定顺序 (1) 优先于路由分支），零 record。
- P-16 整批 batch-rl04-a → outcome = `partial`；10 条 record（Y-01…Y-07、Y-09、Y-12、Y-14：9 条 valid_complete + 1 条 valid_incomplete）；诊断 multiset 钉死 6 条 = {Y-08、Y-10、Y-11、Y-13: SPINE_ALIPAY_UNKNOWN_TOKEN ×4；Y-12: REQUIRED_FACT_UNRESOLVED(status)；Y-15: SPINE_ALIPAY_REFUND_UNSUPPORTED}；拒行与 record_error 行零 record。
- P-17 隐私断言（M-01 同构）：商品说明 列仅对 交易分类=`投资理财` 行做冻结子类型 token 精确匹配；任何 商品说明 值（含子类型 token）、收/付款方式 列值（空/账户余额/余额）、收/支 列值（不计收支/支出）不进入解析输出、诊断、日志或任何持久化——断言：这些列的值集合与解析输出全部字符串集合不相交。
- P-18 判定顺序防御：`投资理财` + 冻结子类型 + 状态含「退款」（Y-15）→ 退款判定优先；普通分类行（如 网上支付）路由行为与 P4-05 冻结断言逐值一致（回归面，R-01）。

spine 端到端（沿用 P4-04 §1.3 的 Δ 形状与 claim-first 语义；候选命名见 §1.2）：

- E-01 intake Y-01 @ req-y01-intake → accepted。Δ：request+1、source+1、evidence+1、candidate+1（C1 pending_confirmation）、status_history+1、receipt+1；formal 0/0/0。source contract_version=2；candidate_kind=`transfer_flow`、rule=`transfer_flow_source`、rule_version=1、confidence=`1.00`。
- E-02 同请求等价重放 E-01 → no_change（`equivalent_replay`），Δ 全零，receipt 逐值相同。
- E-03 confirm C1 @ req-y01-confirm，decisionFields=TransferFlow(fromAccountId=`account-alipay-balance`, toAccountId=`account-yuebao`)，expectedContentHash=H(Y-01)，explicitConfirmedAt=（合成显式值）→ accepted。formal：transaction+1（kind ACCOUNT_TRANSFER）、version+1、posting+2（account-alipay-balance −amount、account-yuebao +amount，逐币种平衡）；报表：internalTransferMinor=amount，netWorthChange、消费、预算、普通收入/费用、分类效应、对外现金流流入/流出全零（RG-09 语义；golden 锚点两条资产分录平衡语义）。
- E-04 同请求等价重放 E-03 → no_change，原 receipt；factory 不被调用；ImportIdSource 不消耗。
- E-05 confirm C1 @ req-y01-confirm-rev，TransferFlow(from=`account-yuebao`, to=`account-alipay-balance`)（反向腿）→ rejected（SPINE_DOMAIN_VALIDATION_FAILED，方向门：out ⇒ from=wallet/支付宝余额），Δ 全零（含 claim 回滚），C1 仍 pending，request identity 可用（P4-04 E-21 先例）。
- E-06 intake Y-09 @ req-y09-intake → accepted（C9 pending，`transfer_flow`）。confirm C9 @ req-y09-confirm，TransferFlow(from=`account-yuebao`, to=`account-alipay-balance`) → accepted（in ⇒ to=wallet/支付宝余额）；formal：posting+2（account-yuebao −amount、account-alipay-balance +amount，逐币种平衡）。
- E-07 confirm C9 @ 反向腿（from=`account-alipay-balance`, to=`account-yuebao`）→ rejected（SPINE_DOMAIN_VALIDATION_FAILED，方向门），Δ 全零。
- E-08 拒行行（Y-08/Y-10/Y-11/Y-13/Y-15）→ 零 intake 调用、零写入（拒行与 record_error 行零 intake 调用，P4-05 E-12 纪律）。
- E-09 confirm C12（Y-12 状态 unresolved 候选）@ req-y12-confirm，TransferFlow(from=`account-alipay-balance`, to=`account-yuebao`) → rejected（SPINE_CANDIDATE_INCOMPLETE），Δ 全零（P4-04 E-20 先例）；C12 只能人工 reject 终态。
- E-10 confirm C1 用 OrdinaryFlow 决策字段 → rejected（SPINE_DECISION_KIND_MISMATCH），Δ 全零（kind 门复用，P4-04 E-27/E-28 先例）；普通候选 + TransferFlow 同码拒行。
- E-11 并发 E-03 ×2（同请求同候选同字段）→ 1 accepted + 1 no_change；行数保持单组；ImportIdSource.next() 恰好一次（P4-04 E-29 同构）。
- E-12 intake/confirm 失败注入（合成新候选，attempt 批次零痕迹）→ 异常 + 事务全回滚（含 claim）；同请求重试 → accepted（P4-04 E-31/E-32 同构）。

回归（R 系列）：

- R-01 P4-05 全 oracle（P-01…P-23/E-01…E-14/R-01…R-02 既有断言）逐值不变——P4-05 fixtures 无 `投资理财` 数据行，本批路由分支对既有 oracle 零影响；唯一变更 = P4-05 §3.2/§3.4「`投资理财` → UNKNOWN」合同条款的登记修订（§3），属 D-099:1539 类型转移纪律（与 P4-04 对 P4-03 的 W7 修订同构；本次无 P-14/E-12 型全批记录数修订面，因 P4-05 fixtures 不含 投资理财 行）。
- R-02 P4-04/P4-03/P4-02 全 oracle 逐值不变——本批对共享代码、schema、微信 parser 与支付宝既有路由零改动。

## 2. 解析契约（fail-closed）

### 2.1 复用（零修订）

P4-05 §2.1 输入与解码（严格 UTF-8 → GB18030 冻结顺序、字节预检）、§2.2 行定位与表头（规范表头 12 token + 行尾逗号、固定 line 23、禁扫描）、§2.3 字段分割与结构不变量（13 字段、tab 不变量、单号列形状校验后剥离）、§2.5 记录级语义与批次 outcome、§3.3 状态/方向处置与批次判定顺序框架——全部原样复用，零修订。

### 2.2 列映射修订（登记修订，仅限路由分支）

P4-05 §2.4 的列映射冻结，本批仅做以下登记修订（其余列映射逐字不变）：

| 列 | 读取规则（修订后） | 归一化映射 |
| --- | --- | --- |
| 0 交易时间 | 不变（`fields[0]`，2026-08-18 纠正后） | occurred_at（+08:00、秒粒度） |
| 1 交易分类 | 不变（路由列） | 判定顺序见 §2.3 |
| 4 商品说明 | **修订**：仅对 交易分类=`投资理财` 行读取冻结子类型 token（精确匹配）；其余行不读取（P4-05「不持久化、不解析」维持） | 路由列（不进五事实、不持久化、provider DTO 零引入） |
| 5 收/支 | **修订**：对路由分支命中的行不读取、不参与方向判定；其余行维持 P4-05 映射 | 方向事实来源 = 子类型冻结映射（rule `yuebao_subtype_direction_v1`、version 1、confidence `exact`，D-097:1455 分层）；`不计收支` 只作行为证据 |
| 6 金额 | 不变（`fields[6]`，形状 `\d+\.\d{2}`） | amount_minor 精确整数 ×100；currency_code 恒 CNY；currency_precision 恒 2 |
| 7 收/付款方式 | 不变（不持久化、不解析，P4-05 §3.4 冻结维持） | `账户余额`/`余额` 通用 token 仅行为证据，不参与判定、不进持久化 |
| 8 交易状态 | 不变 | `交易成功`→"settled"；其余 token 保留 raw + unresolved（D-097:1449） |

- **方向派生（冻结）**：`余额宝-自动转入` → "out"（余额 → 余额宝，wallet=from）；`余额宝-转出到余额` → "in"（余额宝 → 余额，wallet=to）。收/支列（取证恒 `不计收支`）不参与方向判定（P4-04 微信 self-transfer 先例：token 族 → 方向）。方向事实的五事实成员身份不变（ImportSourceFacts 形状零改动），只是其来源从 收/支 列切换为冻结子类型映射——仅对路由分支命中的行生效。
- **状态门（冻结）**：路由要求 交易状态=`交易成功`（settled，与 P4-05 §3.3 状态语义一致）。冻结子类型 + 非成功状态 → 不进入 transfer 路由：status raw 保留 + unresolved → valid_incomplete（A-05 先例语义），record_kind 仍为 `transfer_flow_source`（T6 同构先例）；其不可确认性（incomplete ⇒ SPINE_CANDIDATE_INCOMPLETE，P4-05 §3.3 登记语义）保证零正式分录。

### 2.3 判定顺序（冻结，插入 P4-05 §3.3 既有顺序）

P4-05 既有顺序（退款 → 拒绝集合 → 未知 → 事实映射）保持不变，余额宝路由分支插入为第 (2) 步（与 P4-04「退款 → transfer 路由 → 拒绝集合」同构）：

1. 分类或状态含「退款」→ SPINE_ALIPAY_REFUND_UNSUPPORTED（不变）。
2. 分类 = `投资理财` → **余额宝路由分支**：
   - (2a) 子类型 ∈ 冻结集合 `{余额宝-自动转入, 余额宝-转出到余额}` 且 状态 = `交易成功` → transfer 路由：record_kind `transfer_flow_source`（v2），方向由子类型派生（§2.2），status settled，valid_complete。
   - (2b) 子类型 ∈ 冻结集合 但 状态 ≠ `交易成功` → record_kind `transfer_flow_source` + status raw 保留、unresolved → valid_incomplete（A-05 先例；状态门，§2.2）。
   - (2c) 其余（子类型 ∉ 冻结集合、商品说明 空或未知，含 `余额宝-单次转入`/`余额宝-转出到银行卡`/`余额宝-收益发放` 登记族）→ SPINE_ALIPAY_UNKNOWN_TOKEN 拒行（fail-closed，零 record）。
3. 分类 ∈ 拒绝集合 → SPINE_ALIPAY_UNSUPPORTED_TX_TYPE（不变）。
4. 分类 ∉ 接受集合 → SPINE_ALIPAY_UNKNOWN_TOKEN（不变；`投资理财` 行永不落入本步——第 (2) 步已捕获）。
5. 其余按 P4-05 §2.4 映射（ordinary 路由，不变）。

判定顺序保持既有先例语义：退款标记恒优先；转账族在拒绝集合之前路由；未知 token fail-closed。`投资理财` 由 P4-05 的 UNKNOWN 处置移入冻结转账族（§3 登记修订）。

## 3. 类型范围矩阵（本批冻结）

| 交易分类 | 子类型（商品说明，列 4） | 交易状态 | 本批处置 | record_kind / candidate_kind | 方向（子类型派生） | 登记 |
| --- | --- | --- | --- | --- | --- | --- |
| 投资理财 | 余额宝-自动转入 | 交易成功 | 接受：余额↔余额宝一对一转账候选（确认门可开） | `transfer_flow_source` / `transfer_flow` | out（余额→余额宝；wallet=from） | 7/9 真实样本（收/付款方式 空×3 + 账户余额×4，全部 不计收支） |
| 投资理财 | 余额宝-转出到余额 | 交易成功 | 接受：同上 | `transfer_flow_source` / `transfer_flow` | in（余额宝→余额；wallet=to） | 1/9 真实样本（收/付款方式=余额） |
| 投资理财 | 余额宝-自动转入 / 余额宝-转出到余额 | 非交易成功（如 交易关闭） | valid_incomplete（status raw 保留、unresolved；不路由） | `transfer_flow_source` | 由子类型派生 | A-05 先例；incomplete ⇒ 不可确认 ⇒ 零正式分录；本批无真实样本（Y-12 合成防御） |
| 投资理财 | 余额宝-单次转入 | 交易关闭（唯一样本，无成功样本） | 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN | — | — | **本批不冻结路由**；登记待真实成功样本（若样本 收/付款方式 为银行卡支付则属缺腿语义，需单独定界，§8 第 1 项） |
| 投资理财 | 余额宝-转出到银行卡 | 任意（真实数据无） | 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN | — | — | 不冻结；缺腿登记（目标为外部银行卡，非自有资产账户） |
| 投资理财 | 余额宝-收益发放 | 任意（真实数据无） | 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN | — | — | **硬性负向登记：收益 = 收入（RL-05），禁止路由到 TransferFlow** |
| 投资理财 | 未知 token / 商品说明空 | 任意 | 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN | — | — | fail-closed（无法安全路由，不猜测） |
| 其余分类（网上支付/扫码支付/其他/账户存取/转账红包/亲友代付/信用借还/退款/未知） | — | — | P4-05 §3.2 冻结处置不变 | — | — | 不变（账户存取/转账红包 维持 fail-closed 登记） |

- **登记修订（跨规格）**：P4-05 §3.2/§3.4 与 D-101 中「`投资理财` ∉ 接受/拒绝集合 → `SPINE_ALIPAY_UNKNOWN_TOKEN` 拒行；RL-04 路由延后至后续独立批次」条款，由本批修订为「`投资理财` + 冻结子类型 + `交易成功` → transfer 路由；其余 `投资理财` 行维持 fail-closed UNKNOWN 拒行」。P4-05 规格文件保持其批次历史权威不修改；P4-05 oracle 无 `投资理财` fixture 行 ⇒ 零 oracle 影响（§1.3 R-01）。类型转移属 D-099:1539 登记纪律。
- **两腿确认（冻结）**：9/9 真实样本 收/付款方式 无银行卡 → 全部两腿（余额 ↔ 余额宝），无缺腿样本；路由命中的候选为完整腿候选（candidate_kind `transfer_flow`，确认门可开），不产出 `transfer_flow_source_missing_leg`。
- **确认契约（复用 P4-04 §4.2/§4.3，零修订）**：confirm 使用 `TransferFlow(fromAccountId, toAccountId)`（双账户显式、用户选择、无 category）；方向门：out ⇒ from == wallet（支付宝余额）、in ⇒ to == wallet（支付宝余额），违约 → SPINE_DOMAIN_VALIDATION_FAILED 零残留；缺腿确认门（SPINE_TRANSFER_NOT_CONFIRMABLE）本批不触发（无缺腿候选产出）。

## 4. 诊断码

**零新增。** 解析级全部复用 P4-05 §4 与 D-097:1459 冻结码；spine 级全部复用 P4-04 §5 冻结码：

| code | 复用来源 | 本批触发 |
| --- | --- | --- |
| SPINE_ALIPAY_UNKNOWN_TOKEN | P4-05 §4（unsupported/record） | 子类型 ∉ 冻结集合（Y-08/Y-10/Y-11/Y-13，含登记族）；语义不变：无法安全路由 → fail-closed |
| SPINE_ALIPAY_REFUND_UNSUPPORTED | P4-05 §4（判定顺序 (1)） | 分类或状态含「退款」（Y-15） |
| SPINE_ALIPAY_UNSUPPORTED_TX_TYPE | P4-05 §4（判定顺序 (3)） | 分类 ∈ 拒绝集合（不变） |
| REQUIRED_FACT_UNRESOLVED | D-097:1459（incomplete/field） | 状态 unresolved（Y-12，field_role=status） |
| INPUT_*/STRUCTURE_MISMATCH/FIELD_*/REQUIRED_FACT_MISSING | P4-05 §4 原样 | 不变 |
| CONFLICTING_SOURCE_FACTS | D-097:1459 | 本批不触发（方向列不参与判定 ⇒ 无类型×方向冲突面） |
| SPINE_DECISION_KIND_MISMATCH / SPINE_TRANSFER_NOT_CONFIRMABLE / SPINE_CANDIDATE_INCOMPLETE / SPINE_DOMAIN_VALIDATION_FAILED / SPINE_STALE_FINGERPRINT / SPINE_REQUEST_IDENTITY_CONFLICT 等 | P4-04 §5 | 复用（E-05/E-07/E-09/E-10 等） |

- 诊断码政策不变（P4-03 §5/P4-05 §4）：不注册 provider 前缀同义码、不新增 severity/scope。**「是否有需要新诊断码的场景」= 否**——本批所有拒行/未决路径均有既有码承载；若未来 `余额宝-单次转入` 成功样本定界为缺腿，SPINE_TRANSFER_NOT_CONFIRMABLE 已存在可复用（§8 第 3 项登记）。
- 每个拒行/record_error 恰好携带一个诊断码；parse 结果的诊断集合按 multiset 比较（P-16）。

## 5. 模块与依赖

- 实现面（后续实施批，本批不实现）：`ledger-application` jvmMain `com.unifiedledger.application.import.alipay`——`AlipaySourceTokens.kt`（+冻结子类型集合 `YUEBAO_TRANSFER_SUBTYPES`、子类型→方向映射、rule 常量 `yuebao_subtype_direction_v1`）、`AlipayCsvParser.kt`（判定顺序插入第 (2) 步 + 方向派生 + 商品说明列条件读取）。零新依赖、零新 Gradle 模块（自研零依赖解析器裁决不变，D-101）。
- 应用层：`TransferFlowFormalFactory` 以 walletAccountId = 支付宝余额 账户装配（应用层注入点，P4-04 §4.3 先例；装配方式登记为开放问题 §8 第 2 项）；`validateImportFormalBinding`、`ImportSpine.kt` 合同、`ImportRecordKind`/`ImportConfirmDecisionFields` 全部复用，零修订。
- **schema/spine 零改动（冻结为不变量）**：recordKind `TRANSFER_FLOW_SOURCE`、确认契约、factory、ACCOUNT_TRANSFER 方向门均已在 main 存在；本批为纯解析路由批——ledger-data/ledger-domain/ImportSpine.kt/schema v22 零改动、无新表、无迁移。若实施中发现必须改动，作为开放问题登记（§8 第 5 项），不擅自扩展范围。
- E2E 测试位于 ledger-data jvmTest（复用 P4-04/P4-05 的 store/注入器/direct SQL 装配与 complete canonical state 模式）；合成 CSV 由测试构建器生成 GB18030/UTF-8 编码字节，不携带真实文件。

## 6. 测试计划

jvmTest（ledger-application 解析级；ledger-data E2E；合成 CSV 由测试构建器生成；模式沿用 P4-05 §6/P4-04 §8 的 in-memory/文件库/并发/失败注入/direct SQL 守卫）：

- T-01…T-15 对应 P-01…P-15 逐操作断言：facts 字段级（含方向由子类型派生的 provenance rule 断言）、record_kind/contract_version 派生、completeness、诊断 code/severity/scope/location、零 record。
- T-16 P-16：整批 partial outcome、10 条 record、6 条诊断 multiset 钉死。
- T-17 P-17：隐私断言——商品说明/收/付款方式/收/支 列值集合与解析输出、诊断、日志字符串集合不相交（M-01 同构）；子类型 token 精确匹配仅作用于 投资理财 行。
- T-18 P-18/R-01：判定顺序防御（退款优先于路由分支）+ 断言 P4-05 全部既有断言逐值不变（评审已字节级核验：P4-05 两个测试文件无 `投资理财`/`余额宝` 数据行、`商品说明` 仅表头/注释，不存在断言 `投资理财` → UNKNOWN 的向量）；实施期若发现 P4-05 测试存在此类向量（当前取证不存在），须经 §3 登记修订面清单并停止，不擅自改动 P4-05 测试。
- T-19 E-01…E-12 端到端：Δ、receipt 逐值、IdSource 消耗、方向门双向量（out/in 各自的反向腿拒行）、kind 门、incomplete 拒认、并发、失败注入；每项执行 P4-04 §1.4 complete canonical state 比较。
- T-20 R-02：P4-02/P4-03/P4-04 全 oracle 逐值不变（零改动共享代码与 schema）。
- T-21 状态门向量：冻结子类型 × 非成功状态（Y-12 与额外合成向量 `余额宝-转出到余额` + `交易关闭`）→ valid_incomplete + REQUIRED_FACT_UNRESOLVED(status)；confirm → SPINE_CANDIDATE_INCOMPLETE。
- T-22 隐私与 token：收/付款方式 列值（空/账户余额/余额）不进入任何解析输出（§2.2）；`账户余额`/`余额` 仅行为证据断言（实现自检/契约审查项，不持久化）。

测试 manifest 必须精确覆盖 P-01…P-18、E-01…E-12、R-01…R-02，无遗漏或重复 ID。

## 7. 边界断言（本批不含）

- 不含 `余额宝-单次转入` 路由（待真实成功样本；若银行卡支付则属缺腿语义，单独定界）、`余额宝-转出到银行卡` 路由（缺腿）、`余额宝-收益发放` 收入路由（RL-05；硬性负向登记，禁止路由 TransferFlow）。
- 不含任何 schema/migration、任何 spine/domain 改动、任何新诊断码；不含 matcher/evidence-link、posting 匹配/绑定与 reconciliation（P4-08）；不含 dedup（P4-07）；不含组合账户展示 UI（阶段 5）；不含产品 ID/Clock。
- 余额宝账户 ID 不在解析层/spine 物化：由确认输入（`TransferFlow` 双账户）/catalog 提供（P4-04 钱包账户处理先例）；`TransferFlowFormalFactory` 的 walletAccountId = 支付宝余额 账户，装配方式开放问题登记。
- 收/付款方式 列不解析（P4-05 §3.4 冻结维持）；`账户余额`/`余额` 通用 token 仅行为证据，不进持久化；商品说明 列仅做冻结子类型精确匹配，任何值不落盘（provider DTO 零引入，P4-02 §10/D-097:1449）。
- 金额/时间/币种/精度复用 P4-05 已冻结列映射（时间 `fields[0]`、金额 `fields[6]`、CNY、精度 2）；不复制真实金额/时间戳（P405FIX-QUAL-001 隐私先例，金额/时间见 .external 只读 fixture 注册值）。
- 永不自动确认（D-073:895）；拒行与失败路径零残留；只有获胜首请求调用应用回调并消耗 ID。
- 不声明 RL-04 闭合（P4-08/P4-09 门，WORK_PLAN.local.md:123）。

## 8. 开放问题（供主代理/独立评审定夺）

1. `余额宝-单次转入` 路由：本批不冻结（唯一样本 `交易关闭`）。待真实成功样本后定界；若样本 收/付款方式 为银行卡支付 → 目标非自有资产账户 → 属缺腿语义（对照 P4-04 缺腿候选：pending、确认门关闭、SPINE_TRANSFER_NOT_CONFIRMABLE），需单独批次/切片定界，本批不预判。
2. 余额宝账户目录绑定方式：`余额宝` 账户 ID 与 `支付宝余额`（wallet）账户 ID 如何进入 catalog 与 `TransferFlowFormalFactory` 装配点（应用层注入），由谁提供（用户建账/目录默认/确认时选择）；组合账户展示的命名与层级属阶段 5 产品 UI。推荐：应用层装配时注入（P4-04 先例），目录绑定方式留实施批与主代理裁决。
3. 新诊断码场景：本批结论为零新增。登记触发条件——若未来 `余额宝-单次转入`/`余额宝-转出到银行卡` 定界为缺腿路由，parse 级仍零新码（candidate_kind 缺腿 + 既有 SPINE_TRANSFER_NOT_CONFIRMABLE 承载）；若未来出现方向列与子类型矛盾的证据（当前 收/支 列不参与判定，Y-14 冻结），需评估 CONFLICTING_SOURCE_FACTS 是否应启用——需要行为证据，不预判。
4. 跨规格登记修订确认：本批修订 P4-05 §3.2/§3.4 与 D-101「`投资理财` → UNKNOWN」条款（§3 登记）；P4-05 oracle 零影响（无 投资理财 fixture 行）。推荐独立评审在批准时复核该修订面清单（与 P4-04 对 P4-03 的三处修订对照，本批为零 oracle 修订面）。
5. schema/spine 不变量：本批冻结零改动；若实施中发现必须改动（如 DDL 配对 CHECK 无法承载既有 transfer v2 写入——预期不成立，v22 已支持），作为开放问题登记并停止，不擅自扩展范围。
6. docs/PROJECT_MAP.md 是否为本文件增加导航条目：属既有文件修改，本批 writer 无权执行，由主代理裁决（P4-05 §8 第 8 项先例）。
7. 本文件放置（docs/specs/）与命名沿用 P4-05 先例；批准后状态标记由 proposal 改为 approved。

## 轮 B 建议文件布局（仅建议，不创建）

- `ledger-application/src/jvmMain/kotlin/com/unifiedledger/application/import/alipay/AlipaySourceTokens.kt`（+冻结子类型集合、子类型→方向映射、rule 常量；§2/§3 冻结 token）。
- `ledger-application/src/jvmMain/kotlin/com/unifiedledger/application/import/alipay/AlipayCsvParser.kt`（判定顺序插入第 (2) 步、方向派生、商品说明列条件读取；§2.2/§2.3）。
- `ledger-application/src/jvmTest/kotlin/com/unifiedledger/application/import/alipay/AlipayCsvParserJvmTest.kt`（T-01…T-18、T-21、T-22 增量；合成 CSV 构建器沿用）。
- `ledger-data/src/jvmTest/.../ImportSpineAlipayYuebaoTransferEndToEndTest.kt`（T-19/T-20；复用 P4-04/P4-05 spine 装配与 complete canonical state 模式）。
