# P4-03 微信普通收支实施批冻结规格（轮 A）

**Status:** approved — 规格/质量双评审完成（0 BLOCKER；spec 4 MAJOR + 12 MINOR、quality 2 MAJOR + 8 MINOR，合并修订全部落地），verifier 复核偏差经主代理裁决落地；轮 B 实施批已按本规格实现并验证。本文件自此约束实现，不再单独授权。

**Scope:** 冻结 P4-03 实施批（D-099「首个标准来源与 parser 技术」）的匿名 fixtures/解析契约/类型范围矩阵/spine 对接/解析级诊断码/模块与依赖/测试计划与边界断言。本文件只冻结规格，不含实现；实现只允许在本规格按项目评审拓扑冻结后，由后续实施批（轮 B）在独立 worktree 执行。

## Authority And Boundary

本规格全部条款对齐 D-099（docs/DECISIONS.md:1530-1546，2026-08-14 主代理研判批准，未推送），及其引用条款：

- D-099:1536 首个标准来源 = 微信支付账单（XLSX）；11 列表头 0-based row 17、五类事实映射、不构成来源顺序预选。
- D-099:1537 parser 技术 = Apache POI（poi-ooxml 5.5.x，Apache-2.0）六维证据与许可证红区。
- D-099:1538 格式事实全部为行为证据及证据源清单。
- D-099:1539 范围 = RL-01/RL-02 普通收支 formalization 子切片；普通收支类型集合随本规格冻结；拒绝类型 fail-closed；zip 解包与 6 位密码留平台适配层。
- D-099:1540 边界：无 matcher/evidence-link/reconciliation/dedup/产品 ID/Clock；schema 零变更（复用 spine import_* 表）；D-096:1421 首个来源部分关闭；D-093~D-095 暂停条款维持。
- D-098:1475-1528（spine 合同）与 P4-02 规格（docs/specs/2026-08-13-p4-02-shared-import-spine-design.md）：复用 import_source_record 五事实形状与 ImportSourceFacts（P4-02 §8）、claim-first intake、候选 lifecycle、confirm/reject 端口、30-op oracle 与诊断注册先例。
- D-097:1443-1473（normalized source 与类型化诊断验收合同）：五类事实/presence/completeness（:1451-1453）、事实分层与 unknown token 政策（:1449/:1455）、金额与时间语义（:1457）、稳定诊断 taxonomy（:1459）、安全 location（:1461）。
- D-092:1325-1340（方案 A 共享链，非 rgXX_ 前缀）。
- ARCHITECTURE.md:102/:104（import-core 目标职责：格式解析与平台文件访问分离）、:67（Clock 不补写来源时间）、:108（source location 边界）、:151-152（产品 ID 算法与 CSV/XLSX 解析技术表行——D-099 关闭首个来源部分，ARCHITECTURE/PROJECT_MAP 同步已随轮 B C 项落地）。
- 行为证据：微信导出格式事实（表头、行号、列语义、枚举、退款变体、元数据区）全部为行为证据（D-099:1538），本规格只冻结由此派生的中立契约，不复制任何外部实现。

术语：`本批` = P4-03 实施批；`解析器` = 本批微信 xlsx parser（无 I/O/Clock/随机/路径依赖的确定性纯函数，接收 xlsx 字节流与 opaque synthetic input ref，返回归一化记录与类型化诊断）；`spine` = D-092 共享导入链；`拒行` = 本批按冻结类型范围 fail-closed 拒绝的数据行（零 record、零写入）；`fail-closed` = 未知/未授权输入整体或逐行拒绝，绝不猜测或静默映射。

## 1. 匿名 fixtures 集

全部输入为合成、来源中立、provider-neutral 数据；不含真实 provider、真实个人数据、绝对路径、原文件名、真实商户、真实商品、真实单号或可识别交易。所有 ID 为合成字符串。金额一律精确整数最小货币单位，禁止二进制浮点。元数据区内的昵称形状文本为合成 PII 替身（仅用于证明跳过与不持久化，§1.3 M-01）。

### 1.1 基础状态

- ledger：`ledger-p403`（单账本）。
- catalog：自有真实资产账户 `account-asset-a`（CNY）；二级支出分类 `category-food`；二级收入分类 `category-salary`（沿用 P4-02 命名）。
- 输入引用（opaque synthetic input ref）：`batch-p403-a`（主工作簿）、`batch-p403-b1`…`batch-p403-b4`（表头损坏变体族，四类各一）、`batch-p403-c`（.xlsm 文件）、`batch-p403-d`（损坏容器）、`batch-p403-f`（行结构变体）、`batch-p403-g`（全空行变体）。
- 合同版本 `contract_version = 1`；record_kind = `ordinary_flow_source`；candidate rule = `ordinary_flow_source`、rule_version = 1（P4-02 §5 冻结常量）。
- 合成 xlsx 结构：元数据区 0-based rows 0-16（含导出时间、合成昵称等 PII 形状文本）；表头位于 0-based row 17 = 11 列；数据行自 0-based row 18 起；`record_ordinal` = 数据行序号（首个数据行 = 0）。
- 有界输入约束（实现常量化，轮 B 钉死）：文件大小上限与行数上限均以本批证据上界（<2MB、最大单文件 1780 行，D-099:1537）加裕度设置；超限 → INPUT_UNSAFE_OR_OVER_LIMIT（D-097:1459，scope=input 或 container）。

### 1.2 来源记录 W1–W14（全部合成值，workbook A 数据行）

时间格为 excel datetime 数值格；金额格为数值格，其十进制缓存文本即精度依据；「单号列」指交易单号/商户单号两列。

| 记录 | 交易类型 | 收/支 | 金额(元) 格文本 | 当前状态 | 时间格 | 单号列 | 预期解析结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| W1 | 商户消费 | 支出 | "128.50" | 支付成功 | 2026-08-01 12:30 | 交易单号空 / 商户单号合成 | facts (12850, CNY, 2), out, settled, valid_complete |
| W2 | 扫二维码付款 | 支出 | "12.5" | 支付成功 | 2026-08-05 09:00 | 两者合成 | facts (125, CNY, 1), out, settled, valid_complete |
| W3 | 二维码收款 | 收入 | "88" | 已存入零钱 | 2026-08-06 18:45 | 交易单号合成 / 商户单号空 | facts (88, CNY, 0), in, settled, valid_complete |
| W4 | 赞赏码 | 收入 | "3.00" | 已到账 | 2026-08-08 10:00 | 两者合成 | facts (300, CNY, 2), in, settled, valid_complete |
| W5 | 其他 | 支出 | "45.6" | 支付成功 | 2026-08-09 21:15 | 两者空 | facts (456, CNY, 1), out, settled, valid_complete |
| W6 | 商户消费 | / | "0.00" | 支付成功 | 2026-08-10 08:00 | 两者合成 | direction raw "/" 保留、unresolved → valid_incomplete（REQUIRED_FACT_UNRESOLVED, direction） |
| W7 | 零钱提现 | 支出 | "100.00" | 提现已到账 | 2026-08-10 09:30 | 两者合成 | 拒行：SPINE_WEIXIN_UNSUPPORTED_TX_TYPE，零 record |
| W8 | 商户消费-退款 | 收入 | "128.50" | 已退款¥128.50 | 2026-08-11 11:00 | 两者合成 | 拒行：SPINE_WEIXIN_REFUND_UNSUPPORTED，零 record |
| W9 | 商户消费 | 支出 | "10.00" | 已退款(10.00) | 2026-08-11 12:00 | 两者合成 | 拒行：SPINE_WEIXIN_REFUND_UNSUPPORTED，零 record |
| W10 | 商户消费 | 出账 | "20.00" | 支付成功 | 2026-08-11 13:00 | 两者合成 | direction raw "出账" 保留、unresolved → valid_incomplete（REQUIRED_FACT_UNRESOLVED, direction） |
| W11 | 商户消费 | 支出 | "abc"（文本格） | 支付成功 | 2026-08-12 07:30 | 两者合成 | FIELD_AMOUNT_INVALID（record_error/field），零 record |
| W12 | 神秘交易类型 | 支出 | "9.90" | 支付成功 | 2026-08-12 08:45 | 两者合成 | 拒行：SPINE_WEIXIN_UNKNOWN_TOKEN，零 record |
| W13 | 商户消费 | 支出 | "10.00" | 支付成功 | "不是时间"（文本格） | 两者合成 | FIELD_TIME_INVALID（record_error/field），零 record |
| W14 | 商户消费 | 支出 | "7.00" | 交易关闭 | 2026-08-12 09:00 | 两者合成 | status raw "交易关闭" 保留、unresolved → valid_incomplete（REQUIRED_FACT_UNRESOLVED, status） |

事实形状：`facts (amount_minor, currency_code, currency_precision), direction_token, status_token`；occurred_at 恒为 ISO-8601 offset datetime 文本（+08:00），如 W1 = `2026-08-01T12:30:00+08:00`（§2.3）。W6/W10 是 D-097:1447/1453 边界内的 valid_incomplete：可靠来源事实已形成但 direction 事实未解析；金额/时间/状态仍有效，不构成 record error。

候选冻结值沿用 P4-02 §1.2：confidence = valid_complete → `1.00`、valid_incomplete → `0.50`；requires_confirmation 恒为 `formal_transaction_creation`；content_hash 由 intake 边界按 P4-02 §6 规则计算（解析器不计算、不持久化哈希；P4-02 §8 轮 B 勘误仅注记 digest 调用点，计算规则以 §6 为准）。

W1'（intake 级合成 facts fixture）：input_ref 同 W1（`batch-p403-a`）、record_ordinal 0、amount_minor=12851、其余 facts 同 W1——不经过 xlsx 解析，直接构造 ImportIntakeRequest，仅用于 E-08 的 SPINE_IDENTITY_COLLISION 断言（与 W1 同一 raw identity、内容不等价）。

候选命名（E 系列引用）：C1-C5 分别为 W1-W5 的候选；C6/W6、C7/W10、C8/W14 为三条 incomplete 候选（仅在 E-12 的独立账本 `ledger-p403-batch` 出现，编号按 E-12 的 intake 顺序）。

### 1.3 操作集（P-01…P-21 解析级；E-01…E-14 spine 端到端；R-01 回归）

`零 record` = 该行不产生 normalized record；`零写入` = 不产生任何 source/evidence/candidate/request/formal 持久化行。诊断按冻结集合（multiset）比较，message 不比较（D-097:1459）。

解析级（P-01…P-13 逐记录；location 的 ordinal 见 §1.2）：

- P-01 W1 → 1 条 record：facts (12850, CNY, 2)/out/settled，occurred_at `2026-08-01T12:30:00+08:00`，valid_complete，诊断空。
- P-02 W2 → (125, CNY, 1)/out/settled，valid_complete。
- P-03 W3 → (88, CNY, 0)/in/settled，valid_complete；商户单号空值合法（§2.3 列 9）。
- P-04 W4 → (300, CNY, 2)/in/settled，valid_complete。
- P-05 W5 → (456, CNY, 1)/out/settled，valid_complete；两单号列空值合法。
- P-06 W6 → valid_incomplete；direction_token = raw "/" 保留（不猜测、不静默映射）；amount (0, CNY, 2)/occurred_at/settled 事实仍可靠；诊断 {REQUIRED_FACT_UNRESOLVED (incomplete/field, field_role=direction)}。
- P-07 W7 → 拒行 SPINE_WEIXIN_UNSUPPORTED_TX_TYPE（unsupported/record），零 record。
- P-08 W8 → 拒行 SPINE_WEIXIN_REFUND_UNSUPPORTED（unsupported/record），零 record（类型含「退款」变体 `商户消费-退款` 优先判定，§3）。
- P-09 W9 → 拒行 SPINE_WEIXIN_REFUND_UNSUPPORTED，零 record（状态含「退款」变体 `已退款(10.00)`）。
- P-10 W10 → valid_incomplete；direction_token = raw "出账" 保留、unresolved；其余事实可靠。
- P-11 W11 → FIELD_AMOUNT_INVALID（record_error/field, field_role=amount），零 record（金额格为文本；不降格 incomplete，D-097:1447）。
- P-12 W12 → 拒行 SPINE_WEIXIN_UNKNOWN_TOKEN（unsupported/record），零 record（类型 token 未知）。
- P-13 W13 → FIELD_TIME_INVALID（record_error/field, field_role=occurred_at），零 record（时间格为文本；不降格 incomplete）。
- P-14 整批 workbook A → outcome = `partial`；8 条 record（W1-W5 valid_complete + W6/W10/W14 valid_incomplete）；诊断 multiset 钉死 9 条 = {W6（direction）、W10（direction）、W14（status）: REQUIRED_FACT_UNRESOLVED；W7: UNSUPPORTED_TX_TYPE；W8、W9: REFUND_UNSUPPORTED；W11: FIELD_AMOUNT_INVALID；W12: UNKNOWN_TOKEN；W13: FIELD_TIME_INVALID}；拒行与 record_error 行零 record。
- P-15 元数据区（M-01）：rows 0-16 被整体跳过——解析输出、诊断、日志与任何持久化痕迹中不出现元数据区任何 cell 值（含合成昵称）；断言 = 元数据 cell 值集合与解析输出全部字符串集合不相交。
- P-16 表头不匹配（H-01）：row 17 任一列名 token、顺序或列数与冻结 11 列清单不符——缺列/多列/错位/差一字四类变体各用独立 input ref（`batch-p403-b1`…`batch-p403-b4`）→ 批次 `rejected`、零 record，诊断 {STRUCTURE_MISMATCH (fatal/structure, {input_ref})}；不触发任何行号扫描或漂移容差。行结构变体（H-04，`batch-p403-f`）：数据行列数 12（> 11）或列 0-7 中任一必需列缺失（10 列变体）→ 该行 STRUCTURE_MISMATCH（fatal，record 级，{input_ref, record_ordinal}）、零 record，其余记录保留；列 8/9/10 尾随空 cell 缺失不触发（§2.2）。
- P-17 宏容器（H-02，`batch-p403-c`）：.xlsm 内容 → 批次 `rejected`、零 record，诊断 {INPUT_UNSAFE_OR_OVER_LIMIT (fatal/container, {input_ref})}；即便内含合法形状行也不解析。
- P-18 损坏容器（H-03，`batch-p403-d`）：非合法 OOXML/ZIP 内容 → 批次 `rejected`、零 record，诊断 {INPUT_DECODE_FAILED (fatal/input, {input_ref})}。
- P-19 时间转换向量：excel datetime serial 钉死向量（含 W1-W6 值）→ 精确 ISO 文本恒带 +08:00、minute 粒度、确定性（同 serial 两次解析逐字节相等）；serial 负值/非日期文本 → FIELD_TIME_INVALID。
- P-20 W14 → valid_incomplete；status_token = raw "交易关闭" 保留、unresolved（未映射状态 token，不拒行）；诊断 {REQUIRED_FACT_UNRESOLVED (incomplete/field, field_role=status)}；其余事实可靠。
- P-21 全空行跳过（H-05，`batch-p403-g`）：数据区中间插入全空行 → 空行不产出 record；record_ordinal 按 row − 18 绝对行序，序号不重排（后续行 ordinal 不受空行影响）。

spine 端到端（沿用 P4-02 §1.3 的 Δ 形状与 claim-first 语义；候选命名见 §1.2）：

- E-01 intake W1 @ req-a-intake → accepted。Δ 同 P4-02 O-01：request+1、source+1、evidence+1、candidate+1（C1 pending_confirmation）、status_history+1、receipt+1；formal 0/0/0。
- E-02 同请求等价重放 E-01 → no_change（`equivalent_replay`），Δ 全零，receipt 逐值相同。
- E-03 confirm C1 @ req-a-confirm，decision=confirm，expectedContentHash=H(W1)，category=category-food，funding=account-asset-a，explicitConfirmedAt=2026-08-13T10:00:00+08:00 → accepted。Δ 同 P4-02 O-05；formal：transaction+1、version+1、posting+2（category-food 隐藏费用账户 +128.50；account-asset-a −128.50，逐币种平衡——金额与 W1 精确对应）。
- E-04 同请求等价重放 E-03 → no_change，原 receipt。
- E-05 confirm C1 @ req-a-confirm-2 → rejected（SPINE_CANDIDATE_NOT_PENDING），Δ 全零。
- E-06 setup intake（E-07/E-10/E-11 的前置）：依次 intake W2 @ req-b-intake → accepted（C2 pending_confirmation）、W3 @ req-c-intake → accepted（C3 pending_confirmation）、W4 @ req-d-intake → accepted（C4 pending_confirmation）、W5 @ req-e-intake → accepted（C5 pending_confirmation）；每条 Δ 同 P4-02 O-01 形状（request/source/evidence/candidate/status_history/receipt 各 +1；formal 0/0/0）。
- E-07 reject C2（W2 候选）@ req-b-reject → accepted。Δ 同 P4-02 O-11（无 confirmation、formal 0/0/0）。
- E-08 intake W1'（§1.2：同 input_ref `batch-p403-a`、ordinal 0、amount_minor 12851）@ req-a-intake-3 → rejected（SPINE_IDENTITY_COLLISION），Δ 全零。
- E-09 并发 E-01 ×2（同请求同内容）→ 1 accepted + 1 no_change；行数保持单组；IdSource 恰好一次。
- E-10 confirm C4（W4 收入候选）@ req-d-confirm，category=category-unknown → rejected（SPINE_DOMAIN_VALIDATION_FAILED），Δ 全零（含 claim 回滚），request identity 可用。
- E-11 confirm C4 修正重试 @ req-d-confirm，category=category-salary，explicitConfirmedAt=2026-08-13T11:00:00+08:00 → accepted；formal：transaction+1、version+1、posting+2（account-asset-a +3.00；category-salary 隐藏收入账户 −3.00，逐币种平衡）。
- E-12 全批 intake（独立账本 `ledger-p403-batch`，与 E-01~E-11 的幂等/replay 语义隔离）：workbook A 解析后对 8 条 record 逐条 ExecuteImportIntake → 8 条候选（C1-C5 pending_confirmation + C6/W6、C7/W10、C8/W14 三条 incomplete）；拒行与 record_error 行（W7/W8/W9/W11/W12/W13 共 6 行）零 intake 调用、零写入；解析诊断 multiset 与 P-14 相同且不落盘。
- E-13 intake 失败注入（candidate 插入后）→ 异常 + 事务全回滚（P4-02 O-23 同构），零痕迹，同请求重试 → accepted。
- E-14 confirm 失败注入（formal 持久化后）→ 异常 + 全回滚（P4-02 O-24 同构），零痕迹，同请求重试 → accepted。
- R-01 回归：P4-02 30-op oracle（O-01…O-30）逐值不变——本批零改动 spine 表、端口与状态迁移行为。

## 2. 解析契约（fail-closed）

### 2.1 容器与格式

- 仅接受 .xlsx（OOXML）；.xlsm → INPUT_UNSAFE_OR_OVER_LIMIT（fatal/container，宏容器拒绝）；不支持格式（非 xlsx 类型容器）→ INPUT_UNSUPPORTED（fatal/input）；非合法 OOXML/ZIP（含伪装扩展名、损坏 ZIP）→ INPUT_DECODE_FAILED（fatal/input）；上述均为批次 `rejected`、零 record。格式以容器实际结构判定，扩展名仅作提示，不作唯一依据。
- 宏不执行；公式不求值——不启用 FormulaEvaluator，只读缓存值（D-099:1537）；公式格缓存缺失 → 对应字段按 §2.3 判为无效（FIELD_AMOUNT_INVALID / FIELD_TIME_INVALID）。
- POI 内建 ZipSecureFile zip-bomb 防护参数保持默认，实现不得放宽或关闭；防护触发或超上限 → INPUT_UNSAFE_OR_OVER_LIMIT（D-097:1459，scope=input 或 container；§1.1 有界输入）。
- 解析器不访问文件系统、不解 zip 密码（平台适配层职责，D-099:1539）；入参仅为 xlsx 字节流 + input_ref。

### 2.2 表头与行定位

- 表头必须位于 0-based row 17（D-099:1536 行为证据）；11 列的顺序与列名 token 必须与冻结清单精确匹配（字节级）。冻结 token 清单（顺序固定）：`交易时间`、`交易类型`、`交易对方`、`商品`、`收/支`、`金额(元)`、`支付方式`、`当前状态`、`交易单号`、`商户单号`、`备注`——方向列拼写已经主代理以已核实证据裁决为 `收/支`。任何缺失/多余/错位/错字 → STRUCTURE_MISMATCH（fatal/structure, {input_ref}），批次 `rejected`、零 record。
- 禁止表头行号扫描与漂移容差：表头位置是冻结契约，不探测、不 fallback（D-099:1536 语义）。
- 数据行 = 0-based row 18 起的连续行；行宽度语义（冻结）：行列数按「最后一个已定义 cell 索引 + 1」计算且必须 ≤ 11；列 8/9/10（交易单号/商户单号/备注）缺失的尾随空 cell 视为空值、不判 STRUCTURE_MISMATCH；列 0-7 任一缺失或列数 > 11 → 该行无法建立可靠 record boundary → STRUCTURE_MISMATCH（fatal，record 级，{input_ref, record_ordinal}），零 record。
- record_ordinal = row − 18（数据区绝对行序，0-based，§1.1）；全空行不产出 record 但序号不重排（后续行的 ordinal 不受空行影响）。
- rows 0-16（元数据区）零读取：解析器不读取、不解析这些行；其内容不进解析输出、诊断、日志或任何持久化（M-01）。

### 2.3 单元格读取与列映射

每列 → normalized source facts 的冻结映射（列索引 0-based；未列出的列值永不进入事实或持久化）：

| 列 | 读取规则 | normalized source facts 映射 |
| --- | --- | --- |
| 0 交易时间 | excel datetime 数值格；缓存值；不求值公式 | occurred_at = 精确 serial→ISO-8601 offset datetime 文本，offset 恒 `+08:00`（证据冻结常量，D-099:1536 行为证据；运行时不从文件任何区域读取时区声明；非 Clock 补齐，ARCHITECTURE.md:67 边界内）；minute 粒度、确定性（§1.3 P-19） |
| 1 交易类型 | 文本 token | 路由列（不进五事实）：判定顺序见 §3；∈ 接受集合 → 继续；否则拒行 |
| 2 交易对方 | 文本 | 不持久化（provider DTO 禁止：P4-02 §10、D-097:1449） |
| 3 商品 | 文本 | 不持久化（同上） |
| 4 `收/支`（方向列） | 文本 token，值域 {收入, 支出, /} | direction_token：`收入`→"in"；`支出`→"out"；`/`→保留 raw token、ordinary-flow 无映射 → unresolved；值域外 token（如 W10 "出账"）→ 保留 raw + unresolved（D-097:1449/1455 unknown token 政策）。不推断、不符号翻转 |
| 5 金额(元) | 数值格缓存值；必须以精确十进制文本读取（禁止二进制浮点中转） | amount_minor = 精确值 × 10^precision；currency_code 恒 "CNY"（格式契约常量 + 行为证据来源，D-099:1536；非运行时默认、非 source_declared）；currency_precision = 格文本小数位数 |
| 6 支付方式 | 文本 | 不持久化 |
| 7 当前状态 | 文本 token | status_token：∈ 本批接受映射子集 → "settled"；含「退款」→ 拒行（§3）；其余 token（含 15+ 枚举中未映射项）→ 保留 raw + unresolved（D-097:1449） |
| 8 交易单号 | 文本（可为空） | 不持久化；空值合法、不影响事实 |
| 9 商户单号 | 文本（可为空） | 不持久化；空值合法、不影响事实 |
| 10 备注 | 文本 | 不持久化 |

- 金额精度派生（冻结向量）：格文本 `"128.50"`→(12850, 2)；`"12.5"`→(125, 1)；`"88"`→(88, 0)；`"0.00"`→(0, 2)；`"0.0"`→(0, 1)；`"0"`→(0, 0)。金额格非数值（文本/空白）→ FIELD_AMOUNT_INVALID；负金额无来源先例 → FIELD_AMOUNT_INVALID（本批 fail-closed 范围 + D-097:1447 无效金额语义）。
- 状态接受映射子集（冻结）：`支付成功` → settled；`已存入零钱` → settled；`已到账` → settled。冻结登记：该映射子集必须覆盖全部接受类型 fixtures 行（W1-W5 状态均落于子集内）；其余状态 token（含 15+ 枚举中未映射项）走 unresolved 保留（raw 保留），不拒行；含「退款」的 token 仍先于 unresolved 判定拒行（§3）。
- currency_code="CNY" 的 provenance 登记：格式契约常量 + 行为证据来源（D-099:1536），非运行时默认、非 source_declared；运行时不从文件任何区域读取币种声明。rule `currency_v1`、version 1、confidence `exact`（D-097:1455 分层）。
- 方向/状态映射的 provenance：rule `direction_token_v1` / `status_token_v1`、version 1、confidence `exact`（已映射）或 `unresolved`（保留 raw）（D-097:1455）。
- 证据冻结常量：offset `+08:00` 与 currency `CNY` 均为证据冻结常量（D-099:1536 行为证据登记），运行时不从文件任何区域（含元数据区）读取时区/币种声明；元数据区 rows 0-16 零读取（§2.2）。

### 2.4 记录级语义与批次 outcome（对齐 D-097）

- 五类事实齐（amount/currency/occurred_at/direction/status 均 present 且 direction/status 已映射）→ valid_complete。
- 拒行（§3）→ 类型化诊断 + 零 record + 零写入（不创建 source/candidate）。
- 未知 token 拆分（冻结，已经主代理批准）：类型列（路由列）未知 token ⇒ SPINE_WEIXIN_UNKNOWN_TOKEN 拒行（无法安全路由，fail-closed）；方向/状态列（事实列）未知 token ⇒ 保留 raw + unresolved → valid_incomplete（D-097:1449/1455 unknown token 政策，不猜测、不静默映射）。
- 空方向/状态 cell 边（防御性登记，本批 fixtures 不含该边）：方向或状态 cell 为空（absent）的行，解析器以 unresolved 标记产出；若 P4-02 intake 校验以 SPINE_INTAKE_INVALID 拒收（present 且空的 token 非法），该行拒绝且零写入。
- 金额/时间无效 → record_error（FIELD_AMOUNT_INVALID / FIELD_TIME_INVALID）+ 零 record（不降格 incomplete，D-097:1447）。
- 其余必要事实 absent/explicit null/unresolved → valid_incomplete + REQUIRED_FACT_UNRESOLVED（或 REQUIRED_FACT_MISSING，incomplete/field）；record 保留。
- 批次 outcome：input/container fatal → `rejected`（零 record）；否则 `complete`（零诊断）或 `partial`（≥1 条 record 级诊断或拒行）——「拒行计入 partial」属 D-099:1539 fail-closed 冻结的显式扩展（D-097:1447 的 partial 原语义为 record 级错误隔离）；record 级错误相互隔离，可靠记录继续保留（D-097:1447）。
- 解析不产生 account/category/candidate/identity 字段；不做 dedup；相同输入重复执行结果结构确定，不依赖产品 ID、Clock 或本机路径（D-097:1463）。

## 3. 类型范围矩阵（本批冻结）

| 交易类型 token | 本批处置 | direction/status 冻结规则 |
| --- | --- | --- |
| 商户消费 | 接受 | 支出 → out；支付成功 → settled |
| 扫二维码付款 | 接受 | 支出 → out；支付成功 → settled |
| 二维码收款 | 接受 | 收入 → in；已存入零钱/已到账 → settled |
| 赞赏码 | 接受 | 收入 → in；已存入零钱/已到账 → settled |
| 其他 | 接受 | 支出 → out；支付成功 → settled |
| `<任意>-退款` 变体（如 商户消费-退款） | 拒行 SPINE_WEIXIN_REFUND_UNSUPPORTED（判定顺序 1） | 退款经济事件独立（AGENTS.md 边界），本批零写入；登记为后续批次类型：退款类 → P4-06（RG-07 退款边界复用） |
| 状态含「退款」的行（已退款/已退款¥<金额>/已退款(<金额>)/已全额退款/退款成功 等） | 拒行 SPINE_WEIXIN_REFUND_UNSUPPORTED（判定顺序 1） | 即便类型在普通集合内也一律拒绝、零写入（D-099:1539）；登记为后续批次类型：退款类 → P4-06（RG-07 退款边界复用） |
| 转账 | 拒行 SPINE_WEIXIN_UNSUPPORTED_TX_TYPE | 登记为后续批次类型：P4-04（Transfer Slice）维度（转账/提现/充值类） |
| 微信红包 | 拒行（同上） | 登记为后续批次类型：红包类 → 未分配批次维度，留待后续合同决定 |
| 群收款 | 拒行（同上） | 登记为后续批次类型：P4-04（Transfer Slice）维度（转账/提现/充值类） |
| 零钱提现 | 拒行（同上） | 登记为后续批次类型：P4-04（Transfer Slice）维度（转账/提现/充值类） |
| 零钱充值 | 拒行（同上） | 登记为后续批次类型：P4-04（Transfer Slice）维度（转账/提现/充值类） |
| 未知类型 token | 拒行 SPINE_WEIXIN_UNKNOWN_TOKEN | 无法安全路由 → fail-closed |

- 类型列判定顺序（冻结、确定性）：(1) 类型或状态含「退款」→ SPINE_WEIXIN_REFUND_UNSUPPORTED；(2) 类型 ∈ 拒绝集合 → SPINE_WEIXIN_UNSUPPORTED_TX_TYPE；(3) 类型 ∉ 接受集合 → SPINE_WEIXIN_UNKNOWN_TOKEN；(4) 其余按 §2.3 映射。
- 拒行不产生候选、不进入 spine intake、不持久化；登记为后续批次类型：转账/提现/充值类 → P4-04（Transfer Slice）维度，红包类 → 未分配批次维度（留待后续合同决定），退款类 → P4-06（RG-07 退款边界复用）（D-099:1539）。

## 4. Spine 对接

- 解析产出 = 每条 accepted record 的 ImportSourceFacts（P4-02 §8 形状）+ completeness + provenance（rule/rule_version/confidence，§1.2）+ 类型化诊断集。拒行/record_error 行只出现在诊断集，不产生 ImportSourceFacts。
- 应用层对每条 record 调用 ExecuteImportIntake（claim-first，P4-02 §8）→ 候选默认 pending_confirmation、永不自动确认（D-073:895）；随后 ConfirmImportCandidate / RejectImportCandidate 复用 P4-02 端口与校验顺序，本批无新 schema、无新表、无迁移（schema v21 不变，D-099:1540）。
- content_hash 由 intake 边界按 P4-02 §6 规则对入站 facts 一次性计算（解析器不计算、不持久化；P4-02 §8 的轮 B 勘误仅注记 digest 调用点，计算规则以 §6 为准）。
- confirmed_at 只由显式确认事实提供（E-03/E-10 的 explicitConfirmedAt），不由来源时间或运行时时钟推导（D-098:1509、ARCHITECTURE.md:67）。
- oracle 操作集 = §1.3（P-01…P-21、E-01…E-14、R-01）；E 系列逐操作断言 Δ、receipt 逐值、IdSource 消耗、状态迁移与失败零残留（P4-02 §1.3 形状）。

## 5. 解析级诊断码

按 D-097 taxonomy 同风格（code/severity/scope/安全 location；message 不稳定、不比较；不透出底层库异常文本，D-097:1459/1461）注册。severity 基础值域 = D-097 `fatal | record_error | incomplete`；本批按 P4-02 §3 的 spine 显式扩展先例追加注册 `unsupported`（仅 record scope，语义 = 格式合法但属本批 fail-closed 范围外的行）——该扩展属 D-098:1516「同风格追加注册」授权的显式扩展（P4-02 §3 先例），已经主代理批准。scope 值域 = D-097 `input | container | structure | record | field`。安全 location 只由 {input_ref, record_ordinal, field_role} 构成；不含 raw value、表头、整行、元数据区内容或个人标识（D-097:1461）。

| code | severity | scope | 安全 location |
| --- | --- | --- | --- |
| INPUT_UNSUPPORTED（复用 D-097:1459） | fatal | input | {input_ref} |
| INPUT_UNSAFE_OR_OVER_LIMIT（复用 D-097:1459） | fatal | input 或 container | {input_ref} |
| INPUT_DECODE_FAILED（复用 D-097:1459） | fatal | input | {input_ref} |
| STRUCTURE_MISMATCH（复用 D-097:1459） | fatal | structure（数据行非法时标注 record 级） | {input_ref}（表头失配）或 {input_ref, record_ordinal}（数据行非法） |
| SPINE_WEIXIN_UNSUPPORTED_TX_TYPE | unsupported | record | {input_ref, record_ordinal} |
| SPINE_WEIXIN_REFUND_UNSUPPORTED | unsupported | record | {input_ref, record_ordinal} |
| SPINE_WEIXIN_UNKNOWN_TOKEN | unsupported | record | {input_ref, record_ordinal} |
| FIELD_AMOUNT_INVALID（复用 D-097:1459） | record_error | field | {input_ref, record_ordinal, field_role=amount} |
| FIELD_TIME_INVALID（复用 D-097:1459） | record_error | field | {input_ref, record_ordinal, field_role=occurred_at} |
| REQUIRED_FACT_UNRESOLVED（复用 D-097:1459） | incomplete | field | {input_ref, record_ordinal, field_role} |
| REQUIRED_FACT_MISSING（复用 D-097:1459） | incomplete | field | {input_ref, record_ordinal, field_role} |

- 诊断码政策（已经主代理批准）：容器/输入/结构级诊断直接复用 D-097:1459 冻结码（INPUT_UNSUPPORTED、INPUT_UNSAFE_OR_OVER_LIMIT、INPUT_DECODE_FAILED、STRUCTURE_MISMATCH），不注册 provider 前缀同义码；SPINE_WEIXIN_* 仅保留 3 个 provider 专属码（SPINE_WEIXIN_UNSUPPORTED_TX_TYPE、SPINE_WEIXIN_REFUND_UNSUPPORTED、SPINE_WEIXIN_UNKNOWN_TOKEN——类型路由与退款政策）。事实级 record_error/incomplete 语义复用 D-097 冻结码（FIELD_AMOUNT_INVALID、FIELD_TIME_INVALID、REQUIRED_FACT_UNRESOLVED、REQUIRED_FACT_MISSING），不重复注册同义码。
- SPINE_WEIXIN_UNSUPPORTED_TX_TYPE：类型 token ∈ §3 拒绝集合（登记后续批次类型）。
- SPINE_WEIXIN_REFUND_UNSUPPORTED：类型或状态含「退款」（判定顺序 §3；优先于 UNSUPPORTED_TX_TYPE 与 UNKNOWN_TOKEN）。
- SPINE_WEIXIN_UNKNOWN_TOKEN：类型 token 不在任何冻结集合（无法路由）。
- STRUCTURE_MISMATCH：表头失配 → location {input_ref}；数据行结构非法（§2.2：列数 > 11 或列 0-7 缺失）→ location {input_ref, record_ordinal}、scope 标注 record 级。
- REQUIRED_FACT_MISSING：防御性注册，本批 oracle 不触发（本批 fixtures 无 absent 事实路径）；触发路径留后续合同。
- 每个拒行/record_error 恰好携带一个诊断码；parse 结果的诊断集合按 multiset 比较（P-14）。

## 6. 模块与依赖

- 解析器实现于 `ledger-application` jvm 源集：无 I/O/Clock/随机/路径依赖的确定性纯函数，不依赖 Android、网络、文件系统（文件访问与 zip/密码属平台适配层，D-099:1539）。`import-core` 是 ARCHITECTURE.md:102 定义的逻辑职责目标、仓库尚未建立该模块；本批 parser 落在 ledger-application，未来建立 import-core 模块时迁移。该归属为主代理 2026-08-14 显式架构裁决；ARCHITECTURE.md:61/:110/:152 与 PROJECT_MAP 导航同步已随轮 B C 项落地（2026-08-14）。
- 已核实 `ledger-application/build.gradle.kts`（2026-08-14）：kotlin multiplatform 插件、仅 `jvm` target（jvmToolchain 21）、源集 commonMain/commonTest/jvmTest、commonMain 依赖 ledger-domain 与 kotlinx-serialization-json。本批依赖写法：`org.apache.poi:poi-ooxml`（冻结 5.5.x 线，实现钉 5.5.1，D-099:1537）只声明在 jvm 编译作用域（jvmMain/jvmTest 或等效 jvm-only 依赖块），绝不进 commonMain——未来新增 Android target 时 POI 不随 common 源集传播；解析器源码置于 jvm 源集（轮 B 需新建 jvmMain 源集，commonMain 保持 POI 无关）。
- 依赖传递面登记（已登记，jvmCompileClasspath 实测）：poi 5.5.1、poi-ooxml-lite 5.5.1、xmlbeans 5.3.0、commons-compress 1.28.0、commons-io 2.21.0、commons-collections4 4.5.0、log4j-api 2.24.3、commons-codec 1.20.0、commons-math3 3.6.1、commons-lang3 3.18.0。处置登记：全库依赖管理沿用仓库既有惯例（无 dependencyLocking）；传递版本由直接声明 poi-ooxml 5.5.1 传递解析，本清单随升级同步更新；Apache-2.0 NOTICE 与分发许可义务在 app 打包批次（后续阶段）履行并登记（D-099:1537 许可绿区）；解析器零日志、诊断零 cell 值（§5 安全 location 边界）已满足。
- Android 目标适配（minSdk 34 可行但无官方 Android 声明，D-099:1537）留平台适配门，本批只冻结 JVM-only 编译范围。
- 轮 B 实现注记（已按本规格实现）：公式格一律判无效（FIELD_AMOUNT_INVALID/FIELD_TIME_INVALID 按角色；比缓存值条款更严、fail-closed，微信文件无公式）；.xlsm 判定依据 = 容器含 `xl/vbaProject.bin` part；容器预扫描以 ZipInputStream 逐 entry 枚举，entry 内容膨胀与 zip-bomb 防护由 POI 内建 ZipSecureFile 默认值在打开阶段执行、未放宽；10MB 输入上限约束体积（本地工具场景风险低）；E2E 测试位于 ledger-data jvmTest（复用 store/注入器/direct SQL 唯一可行位，P4-02 先例）；E-09 的 IdSource 恰一次由单批 IdSource 二次消费即抛异常隐式保证。
- `ledger-data`/`ledger-domain` 零改动；无新表、无迁移、schema v21 不变；无新 Gradle 模块。

## 7. 测试计划

jvmTest（ledger-application；合成 xlsx 由测试构建器生成，不携带真实文件；模式沿用 P4-02 §9 的 in-memory/文件库/并发/失败注入/direct SQL 守卫）：

- T-01…T-13 对应 P-01…P-13 逐操作断言：facts 字段级、completeness、诊断 code/severity/scope/location、零 record/零写入。
- T-14 P-14：整批 partial outcome、8 条 record、9 条诊断 multiset 钉死。
- T-15 P-15/M-01：元数据区零读取与 PII 零泄漏（cell 值集合与输出字符串集合不相交）。
- T-16 P-16：表头不匹配四类变体（缺列/多列/错位/token 差一字，独立 input ref 族 `batch-p403-b1`…`batch-p403-b4`）全部 → STRUCTURE_MISMATCH（fatal/structure, {input_ref}）、零 record、无行号扫描；行结构变体（列数 12、缺列 0-7 的 10 列变体）→ STRUCTURE_MISMATCH（fatal、record 级，{input_ref, record_ordinal}）、该行零 record、其余保留；P-21 全空行跳过与 ordinal 不重排。
- T-17 P-17/P-18：.xlsm → INPUT_UNSAFE_OR_OVER_LIMIT（fatal/container）；损坏容器 → INPUT_DECODE_FAILED；均 rejected、零 record。
- T-18 P-19：serial→ISO 转换钉死向量（确定性、+08:00、minute 粒度）；非法时间 → FIELD_TIME_INVALID。
- T-19 精度与金额：0/1/2 位小数混存向量（§2.3，含 "0"/"0.0"/"0.00" 零值向量）；金额文本/空白/负值 → FIELD_AMOUNT_INVALID；断言金额路径无二进制浮点（实现自检/契约审查项）。
- T-20 枚举与未知 token（P-20）：方向 3 值 + "/"（W6）与值域外 token（W10）unresolved；状态接受子集映射；未映射状态 token（W14）unresolved 保留、不拒行；退款变体拒行（W8/W9）；未知类型 token（W12）→ UNKNOWN_TOKEN。
- T-21 空单号列：W1（交易单号空）/W3（商户单号空）/W5（两者空）正常通过；断言 facts 与持久化形状不含任何 order-id 字段（provider DTO 禁止）；非持久化列（交易对方/商品/支付方式/交易单号/商户单号/备注）的值集合与诊断/日志字符串不相交（隐私断言，对齐 M-01）。
- T-22 E-01…E-14 端到端：outcome、Δ、receipt 逐值、IdSource 消耗、状态迁移、setup intake（E-06）、并发（E-09）、失败注入（E-13/E-14）、全批 partial intake（E-12，独立账本 `ledger-p403-batch`）、formal 逐币种平衡与金额精确对应（E-03 +128.50/−128.50；E-11 +3.00/−3.00）。
- T-23 R-01 回归：P4-02 30-op oracle 逐值不变（本批零改动 spine 行为）。

## 8. 边界断言（本批不含）

- 不含 dedup/duplicate 数据合同（P4-07）；不含 matcher/evidence-link、posting 匹配/绑定与 reconciliation（P4-08）；不含产品随机 ID 算法与产品/应用 Clock 端口（后续阶段）；不含 zip 解包与 6 位密码处理（平台适配层，D-099:1539）。
- 不含支付宝与 CSV 库选择（P4-05 门）；不含银行 PDF 库（后续批次门）；不引入 fastexcel-reader/iText/EasyExcel/kotlinx-csv（D-099:1537 红区与落选结论）。
- schema v21 不变、无新表、无迁移；ledger-data/ledger-domain 零改动。
- provider DTO 零引入：交易对方/商品/支付方式/交易单号/商户单号/备注的值不进入事实、候选或任何持久化（P4-02 §10、D-097:1449）。
- 永不自动确认；拒行与失败路径零残留；RL-01/RL-02 之外不闭合任何 RL（P4-09 门）。

## 9. 开放问题（供主代理/独立评审定夺）

原第 1-5 项已由主代理 2026-08-14 裁决关闭并写入正文冻结（§2.2 表头 token、§2.4 未知 token 拆分、§5 severity 扩展、§2.3 状态映射子集、§3 延后维度）；原第 7 项（ARCHITECTURE/PROJECT_MAP 同步）已随轮 B C 项完成并删除本条目。剩余开放问题如下：

6. POI 依赖 jvm-only 放置与未来 Android 目标适配（无官方 Android 声明）：本批冻结 JVM-only（§6），Android 侧留平台适配门。
8. excel datetime serial 转换的精确实现（无浮点漂移、确定性）与钉死向量留轮 B；本规格只冻结语义（+08:00、minute 粒度、确定性）。
9. 本文件放置（docs/specs/）与命名沿用 P4-02 §11 第 4 项先例；批准后状态标记由 proposal 改为 approved。

## 轮 B 建议文件布局（仅建议，不创建）

- `ledger-application/src/jvmMain/kotlin/com/unifiedledger/application/import/wechat/WeChatXlsxParser.kt`（解析器、ParseResult、诊断类型；jvmMain 源集由轮 B 新建，commonMain 保持 POI 无关）。
- `ledger-application/src/jvmMain/kotlin/com/unifiedledger/application/import/wechat/WeChatSourceTokens.kt`（§2/§3 冻结 token 表与映射）。
- `ledger-application/src/jvmTest/kotlin/com/unifiedledger/application/import/wechat/WeChatXlsxParserJvmTest.kt`（T-01…T-21）。
- `ledger-application/src/jvmTest/kotlin/com/unifiedledger/application/import/wechat/WeChatSpineEndToEndTest.kt`（T-22/T-23；合成 xlsx 构建器与 P4-02 spine 装配复用）。
