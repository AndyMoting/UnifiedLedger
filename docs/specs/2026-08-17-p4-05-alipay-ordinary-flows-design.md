# P4-05 Alipay Ordinary Flows Design (Frozen Spec)

**Status:** approved — 2026-08-17 用户批准。证据门已完成（2026-08-17：社区预研 + 9/9 用户真实导出取证，备忘登记于 docs/PROJECT_STATE.local.md:81-89）；本规格把该证据门的格式事实转换为中立契约。用户决策：RL-04 转账切片选项 A（提供新导出含「余额转入余额宝」行）；表头 token 字节级独立重验批准。实现由后续实施批在独立 worktree 执行。 **2026-08-18 纠正修订（corrective amendment，详见 §9）：** 表头 token、时间列索引（`fields[4]`→`fields[0]`）与「9/9 无余额宝 token」负证据经字节级复核 9 份真实导出后纠正；2026-08-18 纠正实施批：文档纠正 + 代码/测试纠正同批完成（§9.5(a)(b) 测试已实现）。

**Scope:** 冻结 P4-05 实施批（第二个标准来源 = 支付宝个人交易明细导出 CSV；RL-04 维度的来源整合与普通收支 formalization 子切片）的匿名 fixtures/解析契约/类型范围矩阵（开放 token 域处置）/解析级诊断码/模块与依赖（parser 技术门裁决）/测试计划与边界断言。本文件只冻结规格，不含实现。本批证明共享 spine 不是首个来源特化（PHASE4_DESIGN_PACKAGE.local.md:84-88）；RL-04 锚点的转账语义切片不在本批（原负证据前提已于 2026-08-18 纠正修订撤回——2/9 真实文件含 `投资理财` + `余额宝-*` 行，详见 §9），RL-04 路由延后至后续独立批次（D-10x），登记为开放问题（§8 第 2 项）。

## Authority And Boundary

本规格全部条款对齐以下权威（行号为当前本地 main 的 tracked 文件行号）：

- 批定义：WORK_PLAN.local.md:98（P4-05 Second Standard Source Integration，RL-04 子切片，阻塞于独立 provider/format/parser 证据门——该门已由本规格携带的证据完成并呈交裁决）、:108（P4-05 parser 技术门保持开放，由本批裁决）、:122-129（RL 维度到交付批次矩阵：RL-04 = P4-05 source/formalization 子切片 + P4-08 posting reconciliation + P4-09 全量闭合；P4-05 不得创建 mirror evidence link 或产生 reconciliation effect）；PHASE4_DESIGN_PACKAGE.local.md:44（P4-05 前独立选择第二个标准来源及其 format/parser 技术并提供有界证据；规划不预选 provider 或顺序）、:84-88（第二个获批标准来源复用共享合同，证明主干不是首个来源特化；provider/format/parser 技术独立过门，不能由 P4-03 的批准外推）、:128。
- 外部锚点：CORE_ACCEPTANCE_PLAN（.external/requirements/golden-ledger/，只读）:63 RL-04 行 `GL-A6F5A461E605`「支付宝余额转入余额宝」= 验证两条资产分录平衡、组合账户展示和分账户对账。本批不闭合该锚点任何验收点：两条资产分录平衡不在本批闭合（RL-04 路由延后至后续独立批次 D-10x，原负证据前提已于 2026-08-18 纠正修订撤回，详见 §9）；组合账户展示属产品 UI 维度（阶段 5）；分账户对账属 P4-08（WORK_PLAN.local.md:123）。本批交付 RL-04 的前提——第二个来源经同一 spine 合同完成 formalization。
- 证据门：docs/PROJECT_STATE.local.md:81-89「P4-05 证据门备忘」（2026-08-17：社区预研 + 9/9 用户真实导出取证；备忘本身非权威、未裁决，本规格是其格式事实的中立契约转换）；SOURCE_REFERENCES.md:23（deb-sig/double-entry-generator 支付宝账单格式行为证据登记）。全部格式事实标注为行为证据（D-099:1538 先例），本规格只冻结由此派生的中立契约，不复制任何外部实现。
- D-099:1530-1546（首个标准来源与 parser 技术）的先例条款：:1536 表头位置冻结、禁止扫描与漂移容差、五类事实映射的纪律；:1537 parser 技术六维证据模板（格式兼容/安全/跨平台/许可/维护/替换）与「CSV 库选择（支付宝门 P4-05）延后，本批不引入」——本批即该延后门的裁决；:1538 格式事实全部为行为证据；:1539 zip 解包与密码留平台适配层（解析器只接收解包后字节流）、类型范围 fail-closed 并登记后续批次；:1540 边界（无 matcher/evidence-link/reconciliation/dedup/产品 ID/Clock；复用 spine import_* 表）。
- D-097:1439-1473（normalized source 与类型化诊断验收合同）：五类事实/presence/completeness（:1447/:1451/:1453）、事实分层与 unknown token 政策（:1449/:1455）、金额精确十进制与时间语义（:1457）、稳定诊断 taxonomy（:1459）、安全 location（:1461）、重复执行结构确定（:1463）。
- D-098:1475-1528（spine 合同）：raw identity（:1483-1489）、哈希+元数据（:1491-1496）、candidate lifecycle（:1498-1503）、claim-first atomic confirmation（:1505-1511）、intake 幂等（:1518）、延后清单（:1519）。
- D-100:1548-1574（P4-04 契约）：本批复用其已落地的应用层合同——`ImportRecordKind`（本批恒 ORDINARY_FLOW_SOURCE、contract_version 1）、`ImportConfirmDecisionFields.OrdinaryFlow` 决策字段、commitOnce 共享决策端口与 `validateImportFormalBinding` 前置校验；本批对 ImportSpine.kt 合同与 SqlDelightImportSpineStore 零修订。D-099:1539 登记的类型转移在 P4-04 已完成，本批无任何跨规格冻结修订（§1.3 R-02）。
- D-092:1325-1340（方案 A 共享链，非 rgXX_ 前缀）。
- 前序规格：docs/specs/2026-08-13-p4-02-shared-import-spine-design.md（§1.2 候选冻结值、§1.3 Δ 形状、§2 receipt、§3 spine 诊断注册、§4 状态矩阵、§5 provenance 命名、§6 哈希与编码、§8 端口签名、§9 测试模式）；docs/specs/2026-08-14-p4-03-wechat-ordinary-flows-design.md（§1 匿名 fixtures 纪律、§2 解析契约形状、§3 类型范围矩阵、§5 解析级诊断码政策、§6 模块归属、§7 测试计划——本规格按同构方式冻结第二个来源）；docs/specs/2026-08-14-p4-04-transfer-formalization-slice-design.md（§2.3 kind 路由、§4.1 intake kind 判别、§4.2 OrdinaryFlow 确认链——本批全部原样复用，零修订）。
- ARCHITECTURE.md:61（支付宝/P4-05 门禁待决——由本批裁决 parser 技术）、:67（运行时 Clock 不得补写来源时间）、:102/:104（import-core 逻辑职责：格式解析与平台文件访问分离；fatal fail-closed 与 record 级隔离）、:108（source location 安全边界）、:152（CSV/XLSX 解析技术行：支付宝 CSV/P4-05「届时单独评估具体库或自研实现」——本批选择自研，§5）。
- 账务规则：docs/ACCOUNTING_RULES.md:7-23（核心结构、逐币种平衡）、:25-36（领域不变量：精确金额、分层、只有明确确认创建正式交易、重复导入零影响）、:40-50（普通支出/收入核心字段）、:52-60（转账边界——本批不触及，仅登记）、:166-168（支付钱包充值 = 内部转账不计消费——RL-04 转账切片的会计锚点，本批不实现）、:200-207（导入默认来源支付时间作统计时间）、:209-215（来源/候选/正式账目分层与待确认清单）。
- AGENTS.md 边界：退款与真实冲回是独立经济事件（退款类拒行登记）；对账状态属于真实账户 posting（P4-08）；金额精确十进制或整数最小单位；parser 只产生带 provenance 与 confidence 的候选，只有明确确认创建正式交易。

术语：`本批` = P4-05 实施批；`解析器` = 本批支付宝 CSV parser（无 I/O/Clock/随机/路径依赖的确定性纯函数，接收 CSV 字节流与 opaque synthetic input ref，返回归一化记录与类型化诊断）；`spine` = D-092 共享导入链；`取证` = 2026-08-17 证据门对 9 份用户真实导出的结构分析（行为证据）；`元数据区` = 0-based lines 0-22 的 23 行前置说明文本；`拒行` = 本批按冻结类型范围 fail-closed 拒绝的数据行（零 record、零写入）；`fail-closed` = 未知/未授权输入整体或逐行拒绝，绝不猜测或静默映射。

## 1. 匿名 fixtures 集

全部输入为合成、来源中立、provider-neutral 数据；不含真实 provider、真实个人数据、绝对路径、原文件名、真实商户、真实商品、真实单号或可识别交易。所有 ID 为合成字符串。金额一律精确整数最小货币单位，禁止二进制浮点。元数据区内的昵称形状文本为合成 PII 替身（仅用于证明跳过与不持久化，§1.3 P-18）。

### 1.1 基础状态

- ledger：`ledger-p405`（单账本）。
- catalog：自有真实资产账户 `account-asset-a`（CNY）；二级支出分类 `category-food`（隐藏费用账户 `expense-account-food`）；二级收入分类 `category-salary`（隐藏收入账户 `income-account-salary`）（沿用 P4-02 命名）。
- 输入引用（opaque synthetic input ref；非空、无控制字符、长度 ≤ 256）：`batch-p405-a`（主 CSV，GB18030 编码合成字节，数据行 A-01…A-16）、`batch-p405-b1`…`batch-p405-b4`（表头损坏变体族：缺列/多列/错位/差一字）、`batch-p405-c`（UTF-8 编码变体批，单行同 A-01 事实）、`batch-p405-d`（空输入）、`batch-p405-e`（行结构违例变体族 e1…e7）、`batch-p405-f`（全空行与 EOF 变体）、`batch-p405-g`（截断变体族：g1 = 10 行文件、g2 = 仅表头无数据行）、`batch-p405-h`（PK zip 容器）、`batch-p405-i`（超限输入）。
- 合同版本 `contract_version = 1`；record_kind = `ordinary_flow_source`；candidate rule = `ordinary_flow_source`、rule_version = 1（P4-02 §5 冻结常量）；confidence = valid_complete → `1.00`、valid_incomplete → `0.50`（P4-02 §1.2 沿用）；requires_confirmation 恒为 `formal_transaction_creation`。
- 合成 CSV 结构（§2 冻结契约）：元数据区 0-based lines 0-22（23 行，含导出时间、合成昵称等 PII 形状文本）；表头位于 0-based line 23 = 12 列 + 行尾逗号（13 字段）；数据行自 0-based line 24 起；`record_ordinal` = 数据行序号（首个数据行 = 0，row − 24）；行尾 = 元数据区 CRLF、表头 + 数据行 LF（冻结格式事实）。
- 有界输入约束（实现常量化）：`MAX_INPUT_BYTES = 10MB`、`MAX_DATA_ROWS = 20,000`（裕度设置，证据未给出单年分段行数上界，§8 第 5 项）；超限 → INPUT_UNSAFE_OR_OVER_LIMIT（D-097:1459）。

### 1.2 来源记录 A-01…A-16（全部合成值，批 A 数据行）

「单号列」指交易订单号/商家订单号两列；「两者合成」= 两列均为合成非空值（行含 2 个 tab）；「单号空」= 商家订单号空（行含 1 个 tab，§2.3）。金额列形状冻结为 `\d+\.\d{2}`（§2.5）。

| 记录 | 交易分类 | 收/支 | 金额 | 交易状态 | 时间格 | 单号列 | 预期解析结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A-01 | 网上支付 | 支出 | "128.50" | 交易成功 | 2026-08-01 12:30:45 | 两者合成 | facts (12850, CNY, 2), out, settled, valid_complete |
| A-02 | 扫码支付 | 支出 | "12.50" | 交易成功 | 2026-08-05 09:00:00 | 两者合成 | facts (1250, CNY, 2), out, settled, valid_complete |
| A-03 | 其他 | 收入 | "88.00" | 交易成功 | 2026-08-06 18:45:15 | 交易订单号合成 / 商家订单号空 | facts (8800, CNY, 2), in, settled, valid_complete |
| A-04 | 网上支付 | 不计收支 | "45.60" | 交易成功 | 2026-08-09 21:15:30 | 两者合成 | direction raw "不计收支" 保留、unresolved → valid_incomplete（REQUIRED_FACT_UNRESOLVED, direction） |
| A-05 | 网上支付 | 支出 | "20.00" | 交易关闭 | 2026-08-10 09:30:00 | 两者合成 | status raw "交易关闭" 保留、unresolved → valid_incomplete（REQUIRED_FACT_UNRESOLVED, status） |
| A-06 | 其他 | 支出 | "0.00" | 交易成功 | 2026-08-10 08:00:20 | 两者合成 | facts (0, CNY, 2), out, settled, valid_complete（零金额） |
| A-07 | 账户存取 | 不计收支 | "100.00" | 交易成功 | 2026-08-10 10:00:00 | 两者合成 | 拒行：SPINE_ALIPAY_UNSUPPORTED_TX_TYPE，零 record（RL-04 转账类） |
| A-08 | 转账红包 | 收入 | "8.80" | 交易成功 | 2026-08-11 09:09:09 | 两者合成 | 拒行：SPINE_ALIPAY_UNSUPPORTED_TX_TYPE，零 record（红包类） |
| A-09 | 网上支付 | 不计收支 | "128.50" | 退款成功 | 2026-08-11 11:00:00 | 两者合成 | 拒行：SPINE_ALIPAY_REFUND_UNSUPPORTED（判定顺序 1），零 record |
| A-10 | 信用借还 | 不计收支 | "500.00" | 还款 | 2026-08-11 12:00:00 | 两者合成 | 拒行：SPINE_ALIPAY_UNSUPPORTED_TX_TYPE，零 record（信用类 → P4-06） |
| A-11 | 亲友代付 | 支出 | "66.00" | 代付成功 | 2026-08-11 13:00:00 | 两者合成 | 拒行：SPINE_ALIPAY_UNSUPPORTED_TX_TYPE，零 record（代付类） |
| A-12 | 神秘交易分类 | 支出 | "9.90" | 交易成功 | 2026-08-12 08:45:00 | 两者合成 | 拒行：SPINE_ALIPAY_UNKNOWN_TOKEN，零 record |
| A-13 | 网上支付 | 支出 | "abc" | 交易成功 | 2026-08-12 07:30:00 | 两者合成 | FIELD_AMOUNT_INVALID（record_error/field），零 record |
| A-14 | 网上支付 | 支出 | "10.00" | 交易成功 | "不是时间" | 两者合成 | FIELD_TIME_INVALID（record_error/field），零 record |
| A-15 | 网上支付 | 支出 | "-10.00" | 交易成功 | 2026-08-12 09:15:00 | 两者合成 | FIELD_AMOUNT_INVALID（负号形状 fail-closed），零 record |
| A-16 | 网上支付 | 支出 | "10.5" | 交易成功 | 2026-08-12 09:20:00 | 两者合成 | FIELD_AMOUNT_INVALID（精度形状违例），零 record |

事实形状：`facts (amount_minor, currency_code, currency_precision), direction_token, status_token`；occurred_at 恒为 ISO-8601 offset datetime 文本（+08:00、秒粒度），如 A-01 = `2026-08-01T12:30:45+08:00`（§2.5）。A-04/A-05 是 D-097:1447/1453 边界内的 valid_incomplete：可靠来源事实已形成但 direction/status 事实未解析；金额/时间仍有效，不构成 record error。

候选冻结值沿用 P4-02 §1.2：candidate_kind = `ordinary_flow`；content_hash 由 intake 边界按 P4-02 §6 规则计算（解析器不计算、不持久化哈希）。候选命名（E 系列引用）：C1-C4 分别为 A-01/A-02/A-03/A-06 的候选；E-12 独立账本 `ledger-p405-batch` 使用 D 系列命名，按 intake 顺序 D1/A-01、D2/A-02、D3/A-03、D4/A-04、D5/A-05、D6/A-06（D4/D5 为 incomplete 候选）。

A-01'（intake 级合成 facts fixture）：input_ref 同 A-01（`batch-p405-a`）、record_ordinal 0、amount_minor=12851、其余 facts 同 A-01——不经过 CSV 解析，直接构造 ImportIntakeRequest，仅用于 E-08 的 SPINE_IDENTITY_COLLISION 断言（与 A-01 同一 raw identity、内容不等价）。

### 1.3 操作集（P-01…P-23 解析级；E-01…E-14 spine 端到端；R-01…R-02 回归）

Spine 对接（零修订声明）：本批 spine 对接全部原样复用 P4-02/P4-03/P4-04 已冻结合同——解析产出 = 每条 accepted record 的 ImportSourceFacts（P4-02 §8 形状）+ completeness + provenance + 类型化诊断集；应用层对每条 record 调用 ExecuteImportIntake（recordKind=ORDINARY_FLOW_SOURCE、claim-first）→ 候选默认 pending_confirmation、永不自动确认（D-073:895）；ConfirmImportCandidate 使用 `ImportConfirmDecisionFields.OrdinaryFlow`（P4-04 §4.2）；content_hash 由 intake 边界按 P4-02 §6 一次性计算；confirmed_at 只由显式确认事实提供（D-098:1509、D-081:1007）。本批对 ImportSpine.kt、SqlDelightImportSpineStore.kt、schema v22 零修订。

`零 record` = 该行不产生 normalized record；`零写入` = 不产生任何 source/evidence/candidate/request/formal 持久化行。诊断按冻结集合（multiset）比较，message 不比较（D-097:1459）。

解析级（P-01…P-16 逐记录；location 的 ordinal 见 §1.2）：

- P-01 A-01 → 1 条 record：facts (12850, CNY, 2)/out/settled，occurred_at `2026-08-01T12:30:45+08:00`，valid_complete，诊断空。
- P-02 A-02 → (1250, CNY, 2)/out/settled，valid_complete。
- P-03 A-03 → (8800, CNY, 2)/in/settled，valid_complete；商家订单号空值合法（单 tab 行形状，§2.3）。
- P-04 A-04 → valid_incomplete；direction_token = raw "不计收支" 保留（不猜测、不静默映射）；amount (4560, CNY, 2)/occurred_at/settled 事实仍可靠；诊断 {REQUIRED_FACT_UNRESOLVED (incomplete/field, field_role=direction)}。
- P-05 A-05 → valid_incomplete；status_token = raw "交易关闭" 保留、unresolved（未映射状态 token，不拒行）；诊断 {REQUIRED_FACT_UNRESOLVED (incomplete/field, field_role=status)}；其余事实可靠。
- P-06 A-06 → (0, CNY, 2)/out/settled，valid_complete（零金额、精度恒 2）。
- P-07 A-07 → 拒行 SPINE_ALIPAY_UNSUPPORTED_TX_TYPE（unsupported/record），零 record（账户存取 ∈ 拒绝集合，RL-04 转账类登记，§3）。
- P-08 A-08 → 拒行 SPINE_ALIPAY_UNSUPPORTED_TX_TYPE，零 record（转账红包 ∈ 拒绝集合，红包类登记）。
- P-09 A-09 → 拒行 SPINE_ALIPAY_REFUND_UNSUPPORTED（unsupported/record），零 record（状态含「退款」，判定顺序 1 优先于类型路由）。
- P-10 A-10 → 拒行 SPINE_ALIPAY_UNSUPPORTED_TX_TYPE，零 record（信用借还 ∈ 拒绝集合，信用类登记 → P4-06）。
- P-11 A-11 → 拒行 SPINE_ALIPAY_UNSUPPORTED_TX_TYPE，零 record（亲友代付 ∈ 拒绝集合，代付类登记）。
- P-12 A-12 → 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN（unsupported/record），零 record（分类 token 不在任何冻结集合）。
- P-13 A-13 → FIELD_AMOUNT_INVALID（record_error/field, field_role=amount），零 record（金额非数值；不降格 incomplete，D-097:1447）。
- P-14 A-14 → FIELD_TIME_INVALID（record_error/field, field_role=occurred_at），零 record（时间形状违例；不降格 incomplete）。
- P-15 A-15 → FIELD_AMOUNT_INVALID，零 record（负号形状：接受形状域冻结为非负，§2.5）。
- P-16 A-16 → FIELD_AMOUNT_INVALID，零 record（一位小数：形状冻结为恰两位小数，§2.5）。
- P-17 整批 batch-p405-a → outcome = `partial`；6 条 record（A-01/A-02/A-03/A-06 valid_complete + A-04/A-05 valid_incomplete）；诊断 multiset 钉死 12 条 = {A-04: REQUIRED_FACT_UNRESOLVED(direction)；A-05: REQUIRED_FACT_UNRESOLVED(status)；A-07、A-08、A-10、A-11: SPINE_ALIPAY_UNSUPPORTED_TX_TYPE；A-09: SPINE_ALIPAY_REFUND_UNSUPPORTED；A-12: SPINE_ALIPAY_UNKNOWN_TOKEN；A-13、A-15、A-16: FIELD_AMOUNT_INVALID；A-14: FIELD_TIME_INVALID}；拒行与 record_error 行零 record。
- P-18 元数据区（M-01）：lines 0-22 被整体跳过——解析输出、诊断、日志与任何持久化痕迹中不出现元数据区任何内容（含合成昵称）；断言 = 元数据区合成字符串集合与解析输出全部字符串集合不相交。
- P-19 表头不匹配（H-01）：line 23 任一列名 token、顺序或列数与冻结 12 列清单不符——缺列/多列/错位/差一字四类变体各用独立 input ref（`batch-p405-b1`…`batch-p405-b4`）→ 批次 `rejected`、零 record，诊断 {STRUCTURE_MISMATCH (fatal/structure, {input_ref})}；不触发任何行号扫描或漂移容差。截断变体（`batch-p405-g1`，10 行文件）→ 批次 `rejected`，{STRUCTURE_MISMATCH (fatal/structure, {input_ref})}。
- P-20 编码与容器：`batch-p405-c`（UTF-8 编码、单行同 A-01）→ 1 条 record，facts 与 A-01 逐值相等（UTF-8 变体容错，§2.1）；`batch-p405-d`（空输入）→ 批次 `rejected`，{INPUT_DECODE_FAILED (fatal/input, {input_ref})}；`batch-p405-h`（PK zip 容器）→ 批次 `rejected`，{INPUT_UNSUPPORTED (fatal/input, {input_ref})}；`batch-p405-i`（超 10MB）→ 批次 `rejected`，{INPUT_UNSAFE_OR_OVER_LIMIT (fatal/input, {input_ref})}。
- P-21 行结构变体（`batch-p405-e1`…`batch-p405-e7`，每个变体与一条合法 A-01 形状行共存）：e1 tab 计数 0 → 该行 STRUCTURE_MISMATCH（fatal，record 级，{input_ref, record_ordinal}）、零 record，其余记录保留；e2 tab 计数 3 → 同；e3 字段数 12（行尾逗号缺失）→ 同；e4 字段数 14 → 同；e5 第 13 字段非空 → 同；e6 数据行残留 `\r` → 同；e7 tab 出现在单号两列之外字段 → 同。
- P-22 全空行与 EOF（`batch-p405-f`）：数据区中间全空行不产出 record；record_ordinal 按 row − 24 绝对行序，序号不重排；EOF 尾空行同处置。`batch-p405-g2`（仅表头无数据行）→ outcome `complete`、零 record、诊断空。
- P-23 时间转换向量：`YYYY-MM-DD HH:MM:SS` 钉死向量（含 A-01…A-06 值）→ 精确 ISO 文本恒带 +08:00、秒粒度、确定性（同行两次解析逐字节相等）；形状违例（缺秒/月 13/空/非时间文本）→ FIELD_TIME_INVALID。

spine 端到端（沿用 P4-02 §1.3 的 Δ 形状与 claim-first 语义；候选命名见 §1.2）：

- E-01 intake A-01 @ req-a-intake → accepted。Δ 同 P4-02 O-01：request+1、source+1、evidence+1、candidate+1（C1 pending_confirmation）、status_history+1、receipt+1；formal 0/0/0。source contract_version=1、candidate_kind=`ordinary_flow`、rule=`ordinary_flow_source`、rule_version=1、confidence=`1.00`。
- E-02 同请求等价重放 E-01 → no_change（`equivalent_replay`），Δ 全零，receipt 逐值相同。
- E-03 confirm C1 @ req-a-confirm，decisionFields=OrdinaryFlow(categoryId=category-food, fundingAccountId=account-asset-a)，expectedContentHash=H(A-01)，explicitConfirmedAt=2026-08-17T10:00:00+08:00 → accepted。Δ 同 P4-02 O-05；formal：transaction+1、version+1、posting+2（category-food 隐藏费用账户 +128.50；account-asset-a −128.50，逐币种平衡——金额与 A-01 精确对应）。
- E-04 同请求等价重放 E-03 → no_change，原 receipt。
- E-05 confirm C1 @ req-a-confirm-2 → rejected（SPINE_CANDIDATE_NOT_PENDING），Δ 全零。
- E-06 setup intake：依次 intake A-02 @ req-b-intake → accepted（C2 pending_confirmation）、A-03 @ req-c-intake → accepted（C3 pending_confirmation）、A-06 @ req-d-intake → accepted（C4 pending_confirmation）；每条 Δ 同 P4-02 O-01 形状。
- E-07 reject C2 @ req-b-reject → accepted。Δ 同 P4-02 O-11（无 confirmation、formal 0/0/0）。
- E-08 intake A-01'（§1.2：同 input_ref `batch-p405-a`、ordinal 0、amount_minor 12851）@ req-a-intake-3 → rejected（SPINE_IDENTITY_COLLISION），Δ 全零。
- E-09 并发 E-01 ×2（同请求同内容）→ 1 accepted + 1 no_change；行数保持单组；IdSource 恰好一次。
- E-10 confirm 领域失败（两个独立子向量，均零残留；spine 不比较 violation 内容，P4-04 规格 §4.3）：(a) confirm C3（A-03 收入候选、正金额）@ req-c-confirm，OrdinaryFlow(categoryId=category-unknown, fundingAccountId=account-asset-a) → rejected（SPINE_DOMAIN_VALIDATION_FAILED，未知分类），Δ 全零（含 claim 回滚），C3 仍 pending，request identity 可用；(b) confirm C4（A-06 零金额候选）@ req-d-confirm，OrdinaryFlow(categoryId=category-food, fundingAccountId=account-asset-a) → rejected（SPINE_DOMAIN_VALIDATION_FAILED，领域正金额不变量先于分类校验），Δ 全零，C4 仍 pending。
- E-11 confirm C3 修正重试 @ req-c-confirm，OrdinaryFlow(categoryId=category-salary, fundingAccountId=account-asset-a)，explicitConfirmedAt=2026-08-17T11:00:00+08:00 → accepted；formal：transaction+1、version+1、posting+2（account-asset-a +88.00；category-salary 隐藏收入账户 −88.00，逐币种平衡）。
- E-12 全批 intake（独立账本 `ledger-p405-batch`，与 E-01~E-11 的幂等/replay 语义隔离）：batch-p405-a 解析后对 6 条 record 按工作簿顺序逐条 ExecuteImportIntake → 6 条候选（D1/A-01、D2/A-02、D3/A-03、D6/A-06 pending_confirmation + D4/A-04、D5/A-05 两条 incomplete）；拒行与 record_error 行（A-07…A-16 共 10 行）零 intake 调用、零写入；解析诊断 multiset 与 P-17 相同且不落盘。
- E-13 intake 失败注入（candidate 插入后）→ 异常 + 事务全回滚（P4-02 O-23 同构），零痕迹，同请求重试 → accepted。
- E-14 confirm 失败注入（formal 持久化后）→ 异常 + 全回滚（P4-02 O-24 同构），零痕迹，同请求重试 → accepted。

回归（R 系列）：

- R-01 P4-02 30-op oracle（O-01…O-30）逐值不变——本批零改动 spine 表、端口与状态迁移行为。
- R-02 P4-03 冻结 oracle（P-01…P-21、E-01…E-14，含 P4-04 已落地的三处修订）与 P4-04 全 oracle（P/E/迁移/binding/scale 系列）逐值不变。本批对共享代码、schema 与微信 parser 零改动，不存在任何跨规格冻结修订（与 P4-04 对 P4-03 的三处修订形成对照）。

## 2. 解析契约（fail-closed）

### 2.1 输入与解码

- 入参仅为 CSV 字节流 + input_ref。个人导出 = 加密 zip 内 CSV（格式事实）：加密 zip 解包与密码通道属平台适配层（D-099:1539 先例），解析器不访问文件系统、不解 zip；密码通道与分段形态上界仅用户可知，留平台适配门（§7 边界、§8 登记）。
- 字节预检（确定性顺序）：空输入 → INPUT_DECODE_FAILED（fatal/input）；超 MAX_INPUT_BYTES → INPUT_UNSAFE_OR_OVER_LIMIT（fatal/input）；PK zip 魔数（`PK\x03\x04`/`PK\x05\x06`/`PK\x07\x08`）→ INPUT_UNSUPPORTED（fatal/input，容器非纯文本 CSV）；OLE2 魔数 → INPUT_UNSUPPORTED。上述均为批次 `rejected`、零 record。
- 字符解码（冻结顺序，两路均确定性）：先尝试严格 UTF-8 解码（malformed 即失败）；失败 → GB18030 解码。证据登记：9/9 真实文件为 GBK/CP936 系（无 BOM、UTF-8 严格解码全失败）→ 主路径为 GB18030；社区另见 UTF-8 变体 → UTF-8 优先尝试提供变体容错（备忘「按 GBK 系 + 变体容错设计」）；GBK/CP936 字节级区分不必要（GB18030 容错即可，备忘）。解码策略不得依赖字节序标记：取证无 BOM，若解码文本含 U+FEFF 前缀，表头精确匹配将失败 → STRUCTURE_MISMATCH（fail-closed；BOM 容错未冻结，§8 第 3 项）。

### 2.2 行定位与表头

- 行分割 = `\n`；`\r` 不是分割字符。表头与数据行冻结不含 `\r`（格式事实：前导区 CRLF、表头+数据行 LF）；表头或数据行出现任何 `\r` → STRUCTURE_MISMATCH（表头 fatal/structure；数据行 fatal、record 级）。元数据区行尾的 CR 不参与任何检查（零读取）。
- 元数据区（0-based lines 0-22，23 行）零读取：解析器不对这些行做字段分割、token 匹配或任何内容传播；其内容不进解析输出、诊断、日志或任何持久化（M-01，§1.3 P-18）。
- 表头必须位于 0-based line 23（取证冻结）；文件不足 24 行 → STRUCTURE_MISMATCH（fatal/structure, {input_ref}），批次 `rejected`。表头行按逗号分割后必须恰为 13 字段，前 12 字段的 token 与冻结清单精确匹配（逐字符），第 13 字段必须为空（行尾逗号）。冻结 token 清单（顺序固定；2026-08-18 字节级复核 9 份真实导出后纠正，原清单 5 token 不字节匹配且含真实导出不存在的虚构列 `交易号`，详见 §9）：`交易时间`、`交易分类`、`交易对方`、`对方账号`、`商品说明`、`收/支`、`金额`、`收/付款方式`、`交易状态`、`交易订单号`、`商家订单号`、`备注`。任何缺失/多余/错位/错字/行尾逗号缺失 → STRUCTURE_MISMATCH（fatal/structure, {input_ref}），批次 `rejected`、零 record。
- 禁止表头行号扫描与漂移容差：表头位置是冻结契约，不探测、不 fallback（D-099:1536 语义先例）。2026-06 社区报告的格式漂移未在 9/9 取证中复现（备忘），本契约不为其预留容差。
- 数据行 = 0-based line 24 起的连续行；record_ordinal = row − 24（数据区绝对行序，0-based）。全空行（CR 剥离后为空串的行）不产出 record 但序号不重排（后续行 ordinal 不受影响）；EOF 尾空行同处置。无统计区（格式事实）：数据区延续至文件末尾。

### 2.3 字段分割与结构不变量

每数据行按逗号分割，结构不变量（冻结；任一违例 → 该行 STRUCTURE_MISMATCH（fatal，record 级，{input_ref, record_ordinal}），零 record，其余记录保留）：

1. 字段数恰为 13（12 列 + 行尾逗号）；第 13 字段（0-based 12）必须为空。
2. tab 不变量（取证冻结「每数据行恰 2 个 `\t`；商家订单号空值 = 单 `\t`」）：字段 9（交易订单号）形状 = 非空值 + 单个尾随 `\t`；字段 10（商家订单号）形状 = 非空值 + 单个尾随 `\t`，或完全空（空值不带 tab）；其余字段不得含 `\t`。等价表述：商家订单号非空行恰含 2 个 tab，商家订单号空行恰含 1 个 tab。
3. 字段 9/10 的尾随 tab 在形状校验后剥离；两列值本身仅作形状校验，绝不物化进解析输出、诊断或持久化（provider DTO 零引入）。交易订单号单值、商家订单号可一对多（拆单支付，取证）：字段 10 内部结构不解析、视为 opaque 文本。
4. 单号两列空值语义：字段 9 值空（无有效值）→ 结构违例（取证：交易订单号单值恒在）；字段 10 完全空 → 合法（A-03）。

### 2.4 列映射（normalized source facts）

每列 → normalized source facts 的冻结映射（列索引 0-based；未列出的列值永不进入事实或持久化）：

| 列 | 读取规则 | normalized source facts 映射 |
| --- | --- | --- |
| 0 交易时间 | 文本，形状 `YYYY-MM-DD HH:MM:SS` | occurred_at = 严格解析 + 冻结 offset `+08:00` → 精确 ISO-8601 offset datetime 文本，秒粒度、确定性（§1.3 P-23）；运行时不从文件任何区域读取时区声明（元数据区零读取；非 Clock 补齐，ARCHITECTURE.md:67 边界内）。**列索引纠正（2026-08-18，详见 §9）：** 真实布局时间位于 `fields[0]`，原冻结 `fields[4]` 错误 |
| 1 交易分类 | 文本 token | 路由列（不进五事实）：判定顺序见 §3；∈ 接受集合 → 继续；否则拒行 |
| 2 交易对方 | 文本 | 不持久化 |
| 3 对方账号 | 文本；`/` = 空值掩码（取证约定，仅此列出现，§3.3） | 不持久化。`/` 无方向/状态语义，不参与路由 |
| 4 商品说明 | 文本 | 不持久化（真实布局此列为商品说明；原冻结误标为交易时间，详见 §9） |
| 5 收/支（方向列） | 文本 token | direction_token：`收入`→"in"；`支出`→"out"；其余 token（含取证值 `不计收支`）→ 保留 raw + unresolved（D-097:1449/1455 unknown token 政策）。不推断、不符号翻转 |
| 6 金额 | 文本，形状 `\d+\.\d{2}`（冻结） | amount_minor = 精确整数值 × 100（禁止二进制浮点中转）；currency_code 恒 "CNY"；currency_precision 恒 2（格式冻结常数，不逐行派生——与微信按格派生不同，取证：金额形状恒两位小数） |
| 7 收/付款方式 | 文本（组合 token：`&` 连接 + 「机构名(####)」定长尾号模式，取证） | 不持久化、不解析（§3.4） |
| 8 交易状态 | 文本 token | status_token：`交易成功` → "settled"；含「退款」→ 拒行（§3 判定顺序 1）；其余 token → 保留 raw + unresolved（D-097:1449） |
| 9 交易订单号 | 形状校验后剥离尾随 tab | 不持久化 |
| 10 商家订单号 | 形状校验后剥离尾随 tab（或完全空） | 不持久化 |
| 11 备注 | 文本（可为空） | 不持久化 |

- 金额精度派生（冻结向量）：`"128.50"`→(12850, 2)；`"12.50"`→(1250, 2)；`"88.00"`→(8800, 2)；`"0.00"`→(0, 2)；`"12.5"`→invalid；`"88"`→invalid；`"12.500"`→invalid；`"-10.00"`→invalid；`"abc"`/空→invalid → FIELD_AMOUNT_INVALID（D-097:1447 无效金额语义，不降格 incomplete）。负号登记：社区格式域含可选负号（备忘 regex `-?\d+\.\d{2}`），但取证接受分类行无负金额先例 → 本批 fail-closed 冻结为非负；退款负金额即便存在也先经判定顺序 1 拒行。
- 状态接受映射子集（冻结）：`交易成功` → settled。冻结登记：该子集必须覆盖全部接受类型 fixtures 行（A-01…A-03、A-06 状态均落于子集内）；其余状态 token（含 2025-26 域扩张项 支付成功/等待确认收货 等）走 unresolved 保留（raw 保留），不拒行（D-097:1449）；映射子集扩张只经显式合同修订（§3.1、§8 第 4 项）。
- currency_code="CNY" 与 offset="+08:00" 的 provenance 登记：格式契约常量 + 行为证据来源（取证：个人导出恒境内 CNY、时间戳北京时间；运行时不从文件任何区域读取币种/时区声明）。rule `currency_v1`、version 1、confidence `exact`（D-097:1455 分层）。方向/状态映射的 provenance：rule `direction_token_v1` / `status_token_v1`、version 1、confidence `exact`（已映射）或 `unresolved`（保留 raw）。

### 2.5 记录级语义与批次 outcome（对齐 D-097）

- 五类事实齐（amount/currency/occurred_at/direction/status 均 present 且 direction/status 已映射）→ valid_complete。
- 拒行（§3）→ 类型化诊断 + 零 record + 零写入（不创建 source/candidate）。
- 未知 token 拆分（沿用 P4-03 §2.4 冻结）：分类列（路由列）未知 token ⇒ SPINE_ALIPAY_UNKNOWN_TOKEN 拒行（无法安全路由，fail-closed）；方向/状态列（事实列）未知 token ⇒ 保留 raw + unresolved → valid_incomplete（D-097:1449/1455，不猜测、不静默映射）。
- 空方向/状态 cell 边（防御性登记，本批 fixtures 不含该边）：方向或状态 cell 为空的行，解析器以 unresolved 标记产出；若 P4-02 intake 校验以 SPINE_INTAKE_INVALID 拒收，该行拒绝且零写入。
- 金额/时间无效 → record_error（FIELD_AMOUNT_INVALID / FIELD_TIME_INVALID）+ 零 record（不降格 incomplete，D-097:1447）。
- 其余必要事实 absent/explicit null/unresolved → valid_incomplete + REQUIRED_FACT_UNRESOLVED（或 REQUIRED_FACT_MISSING，incomplete/field）；record 保留。
- 批次 outcome：input/container fatal → `rejected`（零 record）；否则 `complete`（零诊断）或 `partial`（≥1 条 record 级诊断或拒行）；record 级错误相互隔离，可靠记录继续保留（D-097:1447）。
- 解析不产生 account/category/candidate/identity 字段；不做 dedup；相同输入重复执行结果结构确定，不依赖产品 ID、Clock 或本机路径（D-097:1463）。本批所有 accepted record 的 record_kind 恒 ORDINARY_FLOW_SOURCE（contract_version 1）；不产出 transfer kind（RL-04 转账切片阻塞，§8 第 2 项）。

## 3. 类型范围矩阵（本批冻结）

### 3.1 开放 token 域事实与设计响应

取证冻结的 token 域事实（备忘「开放域，封闭枚举必失效」）：

- 交易分类/交易状态/收/付款方式三列 token 随年份扩张：2024 交易状态仅 {交易成功, 交易关闭} → 2025-26 新增 退款成功/还款/代付成功/支付成功/等待确认收货 等；交易关闭跨三态（多种分类×方向组合均出现）。
- 收/付款方式 = 组合 token（`&` 连接多个支付腿）+「机构名(####)」定长尾号模式（机构名 + 掩码尾号 4 位）。
- 语义配对取证：退款成功=不计收支；还款=信用借还×不计收支；代付成功=亲友代付×支出。

设计响应（冻结）：对全 token 域的任何封闭枚举都会失效，因此本批不枚举全域，只冻结三个互斥集合——接受集合（已核实普通语义的分类 token）、拒绝集合（已核实非普通语义的分类 token，逐族登记后续批次）、退款标记（判定顺序 1）；三集合之外的分类 token 一律 UNKNOWN_TOKEN 拒行（fail-closed）。token 集合的任何扩张只经显式合同修订（行为证据 + 独立评审），实现不得静默接受新 token。

### 3.2 分类路由矩阵

| 交易分类 token | 本批处置 | direction/status 冻结规则 | 登记 |
| --- | --- | --- | --- |
| 网上支付 | 接受 | 支出 → out；交易成功 → settled | 普通支出 |
| 扫码支付 | 接受 | 支出 → out；交易成功 → settled | 普通支出 |
| 其他 | 接受 | 收入 → in / 支出 → out；交易成功 → settled | 普通收支 |
| 账户存取 | 拒行 SPINE_ALIPAY_UNSUPPORTED_TX_TYPE | — | RL-04 转账类；转账切片延后至后续独立批次（D-10x，§8 第 2 项、§9；原负证据前提已撤回）；取证：方向恒不计收支 |
| 转账红包 | 拒行 SPINE_ALIPAY_UNSUPPORTED_TX_TYPE | — | 红包类 → 未分配批次（微信红包先例，D-099:1539）；取证：收入方向、RL-04 最近似 token 之一 |
| 亲友代付 | 拒行 SPINE_ALIPAY_UNSUPPORTED_TX_TYPE | — | 代付类 → 未分配批次（取证：代付成功×支出，资金腿归属语义待合同决定） |
| 信用借还 | 拒行 SPINE_ALIPAY_UNSUPPORTED_TX_TYPE | — | RL-05 信用类 → P4-06（取证：还款×不计收支） |
| 分类或状态含「退款」（如状态 退款成功） | 拒行 SPINE_ALIPAY_REFUND_UNSUPPORTED（判定顺序 1） | — | 退款经济事件独立（AGENTS.md 边界），本批零写入；退款类 → P4-06（RG-07 退款边界复用） |
| 未知分类 token | 拒行 SPINE_ALIPAY_UNKNOWN_TOKEN | — | 无法安全路由 → fail-closed |

### 3.3 状态与方向处置

| token 列 | 冻结处置 |
| --- | --- |
| 交易状态 = 交易成功 | settled（本批唯一接受映射，§2.4） |
| 交易状态 = 交易关闭 | 保留 raw + unresolved → valid_incomplete（A-05；D-097 unknown token 政策，不拒行）。登记：RL-08 关闭记录的零分录显式预期属 P4-07 合同；本批由其不可确认性（incomplete ⇒ SPINE_CANDIDATE_INCOMPLETE）保证零正式分录 |
| 交易状态含「退款」 | 判定顺序 1 拒行（先于分类路由与 unresolved） |
| 其余状态 token（还款/代付成功/支付成功/等待确认收货/未来扩张项） | 保留 raw + unresolved → valid_incomplete；其常见分类宿主（信用借还/亲友代付）通常已先经分类路由拒行 |
| 收/支 = 收入/支出 | in/out（§2.4） |
| 收/支 = 不计收支 或其余 token | 保留 raw + unresolved → valid_incomplete（A-04；`/` 不出现在方向列——取证：`/` 仅作对方账号空值掩码） |

- 分类列判定顺序（冻结、确定性）：(1) 分类或状态含「退款」→ SPINE_ALIPAY_REFUND_UNSUPPORTED；(2) 分类 ∈ 拒绝集合 → SPINE_ALIPAY_UNSUPPORTED_TX_TYPE；(3) 分类 ∉ 接受集合 → SPINE_ALIPAY_UNKNOWN_TOKEN；(4) 其余按 §2.4 映射。
- 拒行不产生候选、不进入 spine intake、不持久化（D-099:1539 登记纪律）。

### 3.4 收/付款方式列处置

- 本批不持久化、不解析收/付款方式列（provider DTO 零引入，P4-02 §10、D-097:1449）：组合 token 结构与「机构名(####)」尾号携带资金账户身份与掩码账号信息，属隐私边界内（ARCHITECTURE.md:106-108）。
- 余额宝证据纠正（2026-08-18，详见 §9.4；原「9 文件无余额宝 token」负证据为假）：2/9 真实文件含 `投资理财` 交易分类 + `余额宝-*` 商品说明（列 4）行，此类行 收/支（列 5）恒为 `不计收支`（转账信号）。真实数据中出现的子类型 token：`余额宝-自动转入`、`余额宝-单次转入`、`余额宝-转出到余额`；未出现 `余额宝-转出到银行卡`、`余额宝-收益发放`（社区共识项，非 9 文件取证）。「余额转入余额宝」并非真实交易分类 token（真实分类为 `投资理财`），余额宝见于 商品说明 列（非仅收/付款方式列）。`投资理财` 不在本批 §3.2 接受/拒绝集合 → fail-closed `SPINE_ALIPAY_UNKNOWN_TOKEN` 拒行；RL-04 转账路由延后至后续独立批次（D-10x）在此纠正基础上定界，本批不设先例。

## 4. 解析级诊断码

按 D-097 taxonomy 同风格（code/severity/scope/安全 location；message 不稳定、不比较；不透出底层库异常文本，D-097:1459/1461）注册。severity 基础值域 = D-097 `fatal | record_error | incomplete` + P4-03 §5 已注册的 `unsupported`（仅 record scope；本批复用该先例，不新增 severity/scope）。安全 location 只由 {input_ref, record_ordinal, field_role} 构成；不含 raw value、表头、整行、元数据区内容或个人标识（D-097:1461）。

| code | severity | scope | 安全 location |
| --- | --- | --- | --- |
| INPUT_UNSUPPORTED（复用 D-097:1459） | fatal | input | {input_ref} |
| INPUT_UNSAFE_OR_OVER_LIMIT（复用 D-097:1459） | fatal | input | {input_ref} |
| INPUT_DECODE_FAILED（复用 D-097:1459） | fatal | input | {input_ref} |
| STRUCTURE_MISMATCH（复用 D-097:1459） | fatal | structure（数据行非法时标注 record 级） | {input_ref}（表头失配/截断）或 {input_ref, record_ordinal}（数据行非法） |
| SPINE_ALIPAY_UNSUPPORTED_TX_TYPE | unsupported | record | {input_ref, record_ordinal} |
| SPINE_ALIPAY_REFUND_UNSUPPORTED | unsupported | record | {input_ref, record_ordinal} |
| SPINE_ALIPAY_UNKNOWN_TOKEN | unsupported | record | {input_ref, record_ordinal} |
| FIELD_AMOUNT_INVALID（复用 D-097:1459） | record_error | field | {input_ref, record_ordinal, field_role=amount} |
| FIELD_TIME_INVALID（复用 D-097:1459） | record_error | field | {input_ref, record_ordinal, field_role=occurred_at} |
| REQUIRED_FACT_UNRESOLVED（复用 D-097:1459） | incomplete | field | {input_ref, record_ordinal, field_role} |
| REQUIRED_FACT_MISSING（复用 D-097:1459） | incomplete | field | {input_ref, record_ordinal, field_role} |

- 诊断码政策（沿用 P4-03 §5）：容器/输入/结构级诊断直接复用 D-097:1459 冻结码，不注册 provider 前缀同义码；SPINE_ALIPAY_* 仅保留 3 个 provider 专属码（类型路由、退款政策、未知 token——与 SPINE_WEIXIN_* 三码同构）；事实级 record_error/incomplete 语义复用 D-097 冻结码，不重复注册同义码。
- SPINE_ALIPAY_UNSUPPORTED_TX_TYPE：分类 token ∈ §3.2 拒绝集合（逐族登记后续批次）。
- SPINE_ALIPAY_REFUND_UNSUPPORTED：分类或状态含「退款」（判定顺序 1，优先于 UNSUPPORTED_TX_TYPE 与 UNKNOWN_TOKEN）。
- SPINE_ALIPAY_UNKNOWN_TOKEN：分类 token 不在任何冻结集合（无法路由）。
- STRUCTURE_MISMATCH：表头失配/文件截断 → location {input_ref}；数据行结构非法（§2.2/§2.3：行尾、字段数、tab 不变量、残留 CR）→ location {input_ref, record_ordinal}、scope 标注 record 级。
- REQUIRED_FACT_MISSING：防御性注册，本批 oracle 不触发（本批 fixtures 无 absent 事实路径）；触发路径留后续合同。
- CONFLICTING_SOURCE_FACTS（D-097:1459）：本批不触发（无 transfer 路由 ⇒ 无类型×方向矩阵）；码保留供后续转账切片合同使用。
- 每个拒行/record_error 恰好携带一个诊断码；parse 结果的诊断集合按 multiset 比较（P-17）。

## 5. 模块与依赖

- 解析器实现于 `ledger-application` jvm 源集（`com.unifiedledger.application.import.alipay`，与 wechat 包平行）：无 I/O/Clock/随机/路径依赖的确定性纯函数，不依赖 Android、网络、文件系统（加密 zip 解包与密码通道属平台适配层，§2.1）。`import-core` 是 ARCHITECTURE.md:102 定义的逻辑职责目标、仓库尚未建立该模块；本批 parser 落在 ledger-application，未来建立 import-core 模块时迁移（P4-03 §6 同款归属先例）。
- parser 技术门裁决（D-099:1537 六维模板；ARCHITECTURE.md:152「届时单独评估具体库或自研实现」）：**自研零依赖解析器**，本批不引入任何新第三方依赖：
  - 格式兼容：冻结契约 = 行分割（`\n`）+ 逗号分割 + tab 形状校验；无引号/转义/多行字段（格式事实）——通用 CSV 库的引号/转义机制对本格式是冗余表面；
  - 安全：零第三方解析表面；有界输入常量（§1.1）；纯文本容器无 zip-bomb/宏/公式表面（字节预检仅魔数判定）；
  - 跨平台：jvm-only 作用域有 D-099 POI 先例；GB18030/UTF-8 解码用 JDK 内建 charset；未来 Android target 的 GB18030 charset 可用性留平台适配门（§8 第 6 项）；
  - 许可：零依赖 = 无第三方许可证表面；
  - 维护：自研代码面由冻结契约限定（行/字段/形状三层），全量 oracle 钉死行为；
  - 替换：契约来源中立，未来如引入 CSV 库只需通过同一 oracle，代价趋近零。
- 候选库落选登记（备忘初筛三候选）：Commons CSV（Apache-2.0、jvm-only）、FastCSV（MIT、零依赖）、kotlin-csv（Apache-2.0、唯一 KMP 候选但 charset 处理仅 JVM）——三者均操作字符流，不解决 GBK 系字节解码（解码必须先于字段分割完成），且引号/转义机制对冻结格式冗余；自研在安全/许可/维护三维占优。备忘「需门时 OSV/GHSA 正式审计」因零新依赖而无审计对象，登记为空虚满足。
- 依赖传递面登记：本批新增依赖 = 空集；ledger-application 既有依赖（ledger-domain、kotlinx-serialization-json、poi-ooxml jvm-only）零改动。
- E2E 测试位于 ledger-data jvmTest（复用 store/注入器/direct SQL 唯一可行位，P4-02/P4-03 先例）；合成 CSV 由测试构建器生成 GB18030/UTF-8 编码字节，不携带真实文件。
- `ledger-data`/`ledger-domain` 零改动；ImportSpine.kt 应用合同零改动；无新表、无迁移、schema v22 不变（P4-04 轮 B 已落地 21.sqm，本批纯加法）；无新 Gradle 模块、无新端口、无产品 ID/Clock。

## 6. 测试计划

jvmTest（ledger-application 解析级；ledger-data E2E；合成 CSV 由测试构建器生成，不携带真实文件；模式沿用 P4-02 §9/P4-03 §7 的 in-memory/文件库/并发/失败注入/direct SQL 守卫）：

- T-01…T-16 对应 P-01…P-16 逐操作断言：facts 字段级、completeness、诊断 code/severity/scope/location、零 record/零写入。
- T-17 P-17：整批 partial outcome、6 条 record、12 条诊断 multiset 钉死。
- T-18 P-18/M-01：元数据区零读取与 PII 零泄漏（元数据区合成字符串集合与输出字符串集合不相交）。
- T-19 P-19：表头不匹配四类变体（缺列/多列/错位/token 差一字，独立 input ref 族 `batch-p405-b1`…`batch-p405-b4`）全部 → STRUCTURE_MISMATCH（fatal/structure, {input_ref}）、零 record、无行号扫描；截断文件（g1）→ STRUCTURE_MISMATCH fatal。
- T-20 P-20：UTF-8 变体批与 GB18030 主批 facts 逐值相等（解码顺序确定性）；空输入 → INPUT_DECODE_FAILED；PK zip 容器 → INPUT_UNSUPPORTED；超限 → INPUT_UNSAFE_OR_OVER_LIMIT；均 rejected、零 record。
- T-21 P-21：行结构变体 e1…e7（tab 计数 0/3、字段数 12/14、第 13 字段非空、残留 CR、tab 越位）→ STRUCTURE_MISMATCH（fatal、record 级）、该行零 record、其余保留。
- T-22 P-22：全空行跳过与 ordinal 不重排；EOF 尾空行；仅表头无数据行 → complete、零 record。
- T-23 P-23：时间 `YYYY-MM-DD HH:MM:SS`→ISO 转换钉死向量（确定性、+08:00、秒粒度）；形状违例 → FIELD_TIME_INVALID。
- T-24 金额形状与精度：恰两位小数冻结向量（含 "0.00" 零值）；负号/一位小数/三位小数/整数形/文本/空白 → FIELD_AMOUNT_INVALID；currency_precision 恒 2；断言金额路径无二进制浮点（实现自检/契约审查项）。
- T-25 枚举与未知 token：方向 2 值映射 + `不计收支`（A-04）unresolved；状态接受子集 {交易成功}；未映射状态 token（交易关闭 A-05、等待确认收货向量）unresolved 保留、不拒行；退款标记拒行（A-09，判定顺序 1）；拒绝集合四族拒行（A-07/A-08/A-10/A-11）；未知分类 token（A-12）→ UNKNOWN_TOKEN。
- T-26 单号列形状与 provider DTO 零引入：A-03 商家订单号空（单 tab）合法；tab 不变量双向断言；断言 facts 与持久化形状不含任何 交易对方/对方账号/商品说明/收/付款方式/交易订单号/商家订单号/备注 字段（真实布局无 交易号 列，详见 §9）；非持久化列的值集合与诊断/日志字符串不相交（隐私断言，对齐 M-01）。
- T-27 E-01…E-14 端到端：outcome、Δ、receipt 逐值、IdSource 消耗、状态迁移、setup intake（E-06）、并发（E-09）、领域失败双子向量（E-10，含零金额向量）、失败注入（E-13/E-14）、全批 partial intake（E-12，独立账本 `ledger-p405-batch`）、formal 逐币种平衡与金额精确对应（E-03 +128.50/−128.50；E-11 +88.00/−88.00）。
- T-28 R-01/R-02 回归：P4-02 30-op oracle 逐值不变；P4-03/P4-04 冻结 oracle（含 P4-04 已落地的三处 P4-03 修订）逐值不变——本批零改动共享代码与 schema。

测试 manifest 必须精确覆盖 P-01…P-23、E-01…E-14、R-01…R-02，无遗漏或重复 ID。

## 7. 边界断言（本批不含）

- 不含 dedup/duplicate 数据合同（P4-07）；不含 matcher/evidence-link、posting 匹配/绑定与 reconciliation（P4-08）；不含产品随机 ID 算法与产品/应用 Clock 端口（后续阶段）；不含加密 zip 解包与密码通道处理（平台适配层，§2.1；分段形态上界留平台适配门）。
- 不含支付宝转账子切片：RL-04 锚点 `GL-A6F5A461E605`（对应真实 `余额宝-自动转入` 行）已在 2/9 真实文件确认（§3.4、§9.4），原负证据前提已撤回；RL-04 转账路由不在本批，延后至后续独立批次（D-10x）；账户存取/转账红包等转账族 token 全部 fail-closed 拒行并登记（§3.2）；转账切片选项登记为开放问题（§8 第 2 项，用户决策）。
- RL-04 锚点三个验收点均不在本批闭合：两条资产分录平衡（RL-04 路由延后至后续独立批次 D-10x）、组合账户展示（产品 UI 维度，阶段 5）、分账户对账（P4-08）；本批只交付 RL-04 的前提——第二个来源经同一 spine 合同完成 formalization（主干非首来源特化）。不声明任何 RL 闭合（P4-09 门）。
- 不含新接受分类：冻结接受集合恰为 §3.2 三个普通 token；余额宝/理财族、红包族、代付族 fail-closed（未分配）；信用族 → P4-06；退款族 → P4-06；未知 token fail-closed。
- 不引入任何 CSV 库：Commons CSV/FastCSV/kotlin-csv 均于本门落选（§5）；自研零依赖，不引入 fastexcel-reader/iText/EasyExcel/kotlinx-csv（D-099:1537 红区与落选结论维持）。
- schema v22 不变、无新表、无迁移；ledger-data/ledger-domain 零改动；ImportSpine.kt/SqlDelightImportSpineStore.kt 零改动；golden 冻结契约与 `.external/` 零改动；RG 竖井零改动。
- provider DTO 零引入：交易对方/对方账号/商品说明/收/付款方式/交易订单号/商家订单号/备注的值与交易分类 token 不进入事实、候选或任何持久化（P4-02 §10、D-097:1449；真实布局无 交易号 列，详见 §9）；收/付款方式列的资金账户身份信息绝不回写成账户映射（§3.4）。
- 永不自动确认（D-073:895）；拒行与失败路径零残留；只有获胜首请求调用应用回调并消耗 ID。

## 8. 开放问题（供主代理/独立评审定夺）

1. 表头 12 列字节级 token 与普通接受分类集合 token：原按 9/9 真实文件取证 + 社区交叉验证枚举（§2.2/§3.2），属行为证据；推荐在门禁批准时由独立评审对照取证原件逐字复核（P4-03 `收/支` 拼写经主代理以已核实证据裁决的先例机制）。**2026-08-18 关闭（详见 §9）：** 该字节级独立复核已执行，发现原冻结表头 5 token 不字节匹配且含真实导出不存在的虚构列 `交易号`，已纠正为 9/9 真实导出一致规范表头（§2.2）；fail-closed 的逐字符表头/列 token 匹配保证任何拼写偏差只会在复核或实现期显式暴露，不会被静默接受；如需修订，仅移动 §2.2/§3.2 冻结清单与 fixtures 引用的同一常量点。
2. RL-04 转账子切片（备忘「用户决策」项）：原选项 (a) 等待用户含「余额转入余额宝」行为行的新导出后另起切片（推荐——证据闭环，RL-04 五事实映射有真实来源）；选项 (b) 按最近似 token（账户存取，不计收支）设计合成 fixture 先行验证两条资产分录平衡语义（需用户对合成证据定界 + 转账族语义证据）。**2026-08-18 前提纠正（详见 §9.4）：** 原负证据前提撤回——2/9 真实文件已含 `投资理财` + `余额宝-自动转入`/`余额宝-单次转入`/`余额宝-转出到余额` 行（收/支恒 `不计收支`），故选项 (a)「等待含余额宝行的新导出」前提不成立（行已存在）。RL-04 路由处置延后至后续独立批次（D-10x）在此纠正基础上重新定界；本批冻结范围仍为选项无关的普通收支子切片，两选项均不要求本批返工。
3. 编码变体容错策略：本规格冻结「严格 UTF-8 → GB18030 fallback」（§2.1，备忘「GBK 系 + 变体容错」）。备选：仅 GB18030 单路（UTF-8 变体将 mojibake → STRUCTURE_MISMATCH，fail-closed 但不容错）。推荐维持本规格冻结。BOM 容错不冻结（取证无 BOM；U+FEFF 走 fail-closed），如未来出现 BOM 变体另行修订。
4. 状态接受映射子集扩张：本批仅冻结 {交易成功}（2024 取证域）；2025-26 扩张 token（支付成功 等）保持 unresolved。推荐维持；扩张需行为证据 + 显式合同修订，否则 incomplete 候选噪音换取不了正确性。
5. 有界输入常量（10MB/20,000 行）：证据未给出单年分段行数上界，本值为裕度设置；推荐批准，取证补充后仅常量修订。
6. GB18030 charset 在未来 Android target 的可用性：JVM 内建无疑；Android 平台可用性留平台适配门（D-099:1537 POI「无官方 Android 声明」同款登记模式）。本批只冻结 JVM-only 编译范围。
7. 本文件放置（docs/specs/）与命名沿用 P4-02 §11 第 4 项先例；批准后状态标记由 proposal 改为 approved。
8. docs/PROJECT_MAP.md 是否为本文件增加导航条目：属既有文件修改，本批 writer 无权执行，由主代理裁决（P4-04 §11 第 9 项先例）。

## 9. 纠正修订（2026-08-18，corrective amendment）

本节是对已批准并发布（提交 985413a、D-101 已批准 2026-08-17）规格的纠正修订。本修订分两阶段完成：文档纠正先行冻结并经独立评审（本规格 §2.2/§2.4/§3.4/§7/§8 + §9），代码/测试纠正随后经用户批准于同一纠正实施批（2026-08-18）落地（§9.5(a)(b) 测试已实现）；修订前已批准的规格其余条款（解析契约形状、诊断码、模块与依赖、spine 对接零修订、批量 oracle 集合形状）不受影响。本修订是对 D-101 已批准决定的纠正修订，已在 D-101 登记修订 note。

### 9.1 缺陷（已发布规格与真实导出字节级不符）

已发布规格（提交 985413a）冻结的表头 token 清单与时间列索引，与所声称取证来源的 9 份真实用户导出（4 份「支付宝1」、3 份「支付宝2」、2 份「支付宝3」；真实文件位于仓库外，不在 tracked 文件引用其路径）字节级复核后不符：

1. **表头 token 清单不符**：冻结清单为 `交易号`、`交易分类`、`交易对方`、`商品名称`、`交易时间`、`收/支`、`金额(元)`、`收付款方式`、`交易状态`、`交易订单号`、`商家订单号`、`备注`，其中 5 个 token 不字节匹配真实表头，且 `交易号` 是任何真实导出都不存在的虚构列。原清单对应代码常量 `AlipaySourceTokens.HEADER_TOKENS`。
2. **时间列索引不符**：原 §2.4 列映射把时间列冻结为 `fields[4]`（对应当时误标为「交易时间」的第 4 列）。在真实布局中，第 4 列为 `商品说明`，时间位于 `fields[0]`。代码 `AlipayCsvParser.kt` 以 `parseTime(fields[4])` 读取时间，对真实导出读到的将是商品说明文本 → `FIELD_TIME_INVALID`/`STRUCTURE_MISMATCH`。
3. **后果（已发布 parser 不可用）**：`AlipayCsvParser.kt` 的表头精确匹配（`HEADER_TOKENS.forEachIndexed` 逐字符比对）在真实导出上于 index 0 即失配（冻结 `交易号` ≠ 真实 `交易时间`）→ `STRUCTURE_MISMATCH` → 整批 `rejected`、零 record。任何真实支付宝导出当前都无法通过 parser。26 条合成 parser 测试 + 5 条 E2E 测试通过的唯一原因是测试构建器复用了同一个错误的 `HEADER_TOKENS` 常量。
4. **吸烟枪**：D-101 行为证据补充（DECISIONS.md）记载本地参考解析器使用「时间列 0、金额列 6」——与真实布局一致；冻结规格却采用时间列 4，与所引用的行为证据自相矛盾。这证明 D-101 证据门在批准时未对 9 份真实文件执行字节级复核。

### 9.2 已验证字节级证据（2026-08-18，9/9 真实导出一致）

主代理独立对 9 份真实用户导出执行字节级复核（GBK 解码、0-based line 23、13 逗号字段 = 12 列 + 行尾空字段），9/9 文件表头逐字节完全一致，规范表头为：

`交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注,`

列→语义映射（0-based 索引）：

| 索引 | 规范列名 | 语义 |
| --- | --- | --- |
| 0 | 交易时间 | occurred_at（时间，真实布局时间列） |
| 1 | 交易分类 | 路由列 |
| 2 | 交易对方 | 不持久化 |
| 3 | 对方账号 | 不持久化 |
| 4 | 商品说明 | 不持久化（原误标为交易时间） |
| 5 | 收/支 | direction |
| 6 | 金额 | amount（与原索引同列，无变化） |
| 7 | 收/付款方式 | 不持久化 |
| 8 | 交易状态 | status |
| 9 | 交易订单号 | 形状校验后剥离 |
| 10 | 商家订单号 | 形状校验后剥离（可空） |
| 11 | 备注 | 不持久化 |

与原冻结清单的差异（5 token 拼写修正 + 1 列身份修正 + 移除虚构列）：

- 索引 0：原 `交易号`（虚构） → 真实 `交易时间`。
- 索引 3：原 `商品名称` → 真实 `对方账号`。
- 索引 4：原 `交易时间`（误标，对应错误时间列索引 4） → 真实 `商品说明`。
- 索引 6：原 `金额(元)` → 真实 `金额`。
- 索引 7：原 `收付款方式` → 真实 `收/付款方式`（含斜杠）。
- 索引 1/2/5/8/9/10/11（交易分类、交易对方、收/支、交易状态、交易订单号、商家订单号、备注）：原冻结与真实一致，无变化。

**唯一必需的列索引变更 = 时间：`fields[4]` → `fields[0]`。** 金额 `fields[6]` 巧合相同；分类 `fields[1]`、方向 `fields[5]`、状态 `fields[8]`、交易订单号 `fields[9]`、商家订单号 `fields[10]` 在两种布局中索引一致。tab 不变量（无 tab 字段 0-8、11；tab 字段 9、10）对真实布局结构正确。

### 9.3 根本程序缺口（procedure gap）

已发布规格声称取证来源为「9/9 真实用户导出」，但无任何自动化测试对冻结表头与真实导出做字节级比对。测试构建器与 parser 共享同一错误常量，形成自我一致闭环——合成测试无法发现真实文件不符。这是缺陷未在批准前暴露的根本程序缺口。本修订通过 §9.5 的真实文件一致性执行计划补上该缺口。

### 9.4 余额宝负证据纠正（原「9/9 无余额宝 token」为假）

原 §3.4 与 D-101 RL-04 负证据断言「9 文件中无『余额转入余额宝/余额宝』交易分类 token」，该断言为假。字节级复核确认：

- **2/9 真实文件含 `投资理财` 交易分类 + `余额宝-*` 商品说明（列 4）行**（9 行合计）。
- 真实数据出现的子类型 token：`余额宝-自动转入`、`余额宝-单次转入`、`余额宝-转出到余额`。
- 未出现：`余额宝-转出到银行卡`、`余额宝-收益发放`（社区共识项，非 9 文件取证）。
- 此类行 收/支（列 5）恒为 `不计收支`（转账信号）。
- `余额转入余额宝` 并非真实交易分类 token——真实交易分类为 `投资理财`，余额宝见于 商品说明 列（非仅 收/付款方式 列）。
- Golden 锚点 `GL-A6F5A461E605`（金额/时间见 `.external` 只读 fixture 注册值，来源 支付宝1，登记于 `.external/requirements/golden-ledger/golden_candidates.csv` 与 `golden_expected_fund_movements.csv`）对应一条真实 `余额宝-自动转入` 行——golden 证据有真实来源支撑，该断言不依赖具体数值。

**本批处置（不变）：** `投资理财` 不在 §3.2 接受/拒绝集合 → fail-closed `SPINE_ALIPAY_UNKNOWN_TOKEN` 拒行（零 routing 变更、零 schema 变更）。RL-04 转账路由是后续独立批次（D-10x）的事项，在此纠正基础上重新定界；本批不设先例。

### 9.5 真实文件一致性执行计划（2026-08-18 纠正实施批已落地：(a)/(b) CI 测试已实现，(c) 手工程序已文档化）

为补上 §9.3 的程序缺口，纠正实施批在代码纠正基础上落地以下一致性保障（(a)/(b) 已实现为 CI 测试、(c) 已登记为文档化人工门；列名 token 为通用字段标签、非个人数据，可入测试；真实导出含个人数据、位于仓库外，不进 CI）：

**原子协调强制（代码纠正必须一次性完成，不得分批）：** 同步更新 `AlipaySourceTokens.HEADER_TOKENS`（parser 表头匹配与合成测试构建器共享此常量）、`AlipayCsvParser` 的时间列读取（`fields[4]`→`fields[0]`）、以及合成测试构建器的数据行列布局；任一单独更新将使 (a)/(b) 测试与 parser 重新形成 §9.3 的自我一致闭环（测试与 parser 共享同一常量，单独纠正一侧只会在错误常量下重新自洽，无法暴露真实文件不符）。

(a) **合成表头字节级测试（CI）**：合成测试以 GBK 编码 §9.2 规范表头（仅列名 token，不含个人数据），断言 parser 表头精确匹配接受该字节流（`HEADER_TOKENS` 逐字符比对通过、不触发 `STRUCTURE_MISMATCH`）。

(b) **合成数据行事实抽取测试（CI）**：合成测试使用真实列布局构造数据行（时间位于索引 0、商品 商品说明 位于索引 4、金额位于索引 6、分类/方向/状态/单号按 §2.4），断言 parser 正确抽取事实：`occurred_at` 来自 `fields[0]`、`amount_minor` 来自 `fields[6]`、分类/方向/状态来自对应真实索引、`商品说明` 列值绝不进事实。

(c) **手工真实文件一致性程序（文档化，非 CI）**：解包一份真实导出、GBK 解码 line 23、与 `HEADER_TOKENS` 逐字符 diff；记录差异为零。此为人工程序，不进 CI（真实导出含个人数据且位于仓库外，AGENTS.md 隐私边界）。该程序登记为发布前人工一致性门。

### 9.6 本修订范围与不变量

- **本修订纠正范围**：§2.2 冻结表头 token 清单、§2.4 列→语义映射（时间列索引 4→0、列名拼写）、§3.4 余额宝负证据断言、§7/§8 中重复原前提的表述，以及 D-101 对应条款。
- **本修订不变量**：解析契约形状（行分割/逗号分割/tab 不变量/13 字段）、诊断码 taxonomy、模块与依赖（自研零依赖）、spine 对接零修订、schema v22、批量 oracle 集合形状（P-01…P-23/E-01…E-14/R-01…R-02 的事实值与诊断 multiset 不变——fixtures 使用合成值，列布局纠正后语义不变）、`投资理财` fail-closed UNKNOWN 路由、退款判定顺序、金额精度与币种/时区常量。
- **本修订不触及**：RL-04 转账路由实现（D-10x）、任何 schema/migration、spine/domain、§3.2 接受/拒绝集合成员（仅修正 账户存取 登记栏中对负证据前提的引用）。
- **批准路径**：本纠正修订由主代理字节级复核 9 份真实导出后授权执行（文档纠正先行冻结并经独立评审），代码/测试纠正经用户批准后随同一纠正实施批完成（独立 worktree、writer/reviewer/verifier 拓扑；§9.5(a)(b) 测试已实现）。

## 轮 B 建议文件布局（仅建议，不创建）

- `ledger-application/src/jvmMain/kotlin/com/unifiedledger/application/import/alipay/AlipayCsvParser.kt`（解析器、ParseResult、诊断类型）。
- `ledger-application/src/jvmMain/kotlin/com/unifiedledger/application/import/alipay/AlipaySourceTokens.kt`（§2/§3 冻结 token 表与映射、有界输入常量、证据冻结常量 CNY/+08:00）。
- `ledger-application/src/jvmMain/kotlin/com/unifiedledger/application/import/alipay/AlipayParserTypes.kt`（批次/行结果与诊断构造，WechatParserTypes 同构）。
- `ledger-application/src/jvmTest/kotlin/com/unifiedledger/application/import/alipay/AlipayCsvParserJvmTest.kt`（T-01…T-26；合成 CSV 构建器生成 GB18030/UTF-8 字节）。
- `ledger-data/src/jvmTest/.../ImportSpineAlipayEndToEndTest.kt`（T-27/T-28；复用 P4-02/P4-03/P4-04 spine 装配与 store/注入器）。
