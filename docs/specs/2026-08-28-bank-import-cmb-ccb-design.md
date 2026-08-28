# BP-01 银行 parser 门承接批（招商银行网银 CSV + 建设银行网银 XLS）设计契约

**Status:** approved — 本文件为 BP-01 设计契约（D-096/IMPORT-001 契约优先纪律：先定契约，后写代码）。经独立规格评审（B-1/M-1..M-3/m-1..m-6 全部闭环，终局 APPROVE）与用户 2026-08-28 裁决后批准；主代理登记 D-116。**当前冻结说明：本次契约修订仅冻结 E-11 双形状门（银行原始解析事实保留 `+08:00`；进入既有 `confirmLink` 前由镜像适配层规范化为 UTC `Z` 形），仍为 approved；修订后的冻结 SHA-256 已在 D-116 契约修订登记中更新。** 本文件只写设计，不含实现；实现由后续实施批在独立 worktree 执行。

**Scope:** 冻结 BP-01（D-109 O-8「银行 parser 门」延期维度的承接批）的设计契约：两个来源格式（招商银行网银 CSV、建设银行网银 XLS）的中性格式契约、五类事实映射、方向判定 fail-closed 规则、类型路由矩阵（普通收支 / 明确转账提现 / 其余 fail-closed）、余额镜像（余额锚点/对账维度）、匿名 fixture 规格、oracle 面（解析级 + spine 端到端代表路径 + 余额镜像断言）、实现边界与 schema 预期，以及决策草案 D-116 附录。用户 2026-08-28 裁决的范围 = 普通收支 formalization + 余额镜像 + 明确转账/提现路由；其余类型 fail-closed 拒绝并登记。

## Authority And Boundary

本文件全部条款对齐以下权威（tracked 文件行号为当前 worktree 基线 `779d529` 的行号；`.external/` 只读）：

- **承接契约**：D-109 O-8（docs/DECISIONS.md:1738，RL-07 按平台侧适用子集收口、银行侧真实镜像维度显式登记延期至银行 parser 门）；D-099:1540（银行 PDF 门仍开——本批承接该门，交付 CMB/CCB 两个**网银导出**格式，不涉及 PDF 解析）；D-112（DECISIONS.md:1779-1812，evidence projection 第二批——镜像物化时机、六 kind 广播、TP-18 两批之间门）。
- **外部验收锚点**（只读）：CORE_ACCEPTANCE_PLAN.md:66 RL-07 行 `GL-0DCF5FCDB9BA`「银行流水与平台侧镜像证据：找到同一资金流的平台侧证据；两端只形成一笔正式转账，第二来源作为补充证据」；DISCOVERY_DECISION_LOG.md D-014（银行解析保留为复用资产、非首版硬依赖）、D-020（复用/重构微信、支付宝、招商银行解析，去重、镜像匹配、**余额锚点**、约束推断——本批的余额镜像维度来源）。真实金额/时间/账号注册值不复制入文（P405FIX-QUAL-001 隐私先例）。
- **spine 与解析合同**：D-098（spine：raw identity / retention / candidate lifecycle / atomic confirmation）；D-097（normalized source、五类事实、completeness、typed diagnostics taxonomy、安全 location、unknown token 政策）；D-100（P4-04 转账切片：`transfer_flow_source`/`transfer_flow_source_missing_leg` kind、`TransferFlow(fromAccountId,toAccountId)` 确认契约、方向门「支出 → 钱包=from、收入 → 钱包=to」、永不从交易对方文本推导任何腿、手续费本金-only）；D-101/D-102（P4-05 支付宝解析与 RL-04 余额宝子类型精确匹配先例——「冻结子类型精确匹配、任何值不落盘」）；D-103（matcher 契约，本批不引入任何 matcher 新语义）；D-104/D-105（P4-07 duplicate/closed，`CONFIRMED_DUPLICATE` formalization 阻断先例）。
- **先例规格**：docs/specs/2026-08-13-p4-02-shared-import-spine-design.md（fixtures/操作集/receipt/诊断码/状态矩阵/端口）；docs/specs/2026-08-14-p4-03-wechat-ordinary-flows-design.md（解析契约形状、类型范围矩阵、诊断码政策、模块归属）；docs/specs/2026-08-17-p4-05-alipay-ordinary-flows-design.md（开放 token 域处置、**格式事实以字节级证据为准**的 §9 教训）；docs/specs/2026-08-26-p408-evidence-projection-implementation-design.md（evidence_projection、materialization、READY 门、V-7 同事务物化、TP-01..TP-18）；docs/specs/2026-08-27-p408-correction-successor-invalidation-design.md（successor link 单活性、correction 族）。
- **账务规则**：docs/ACCOUNTING_RULES.md:40-50（普通收支核心字段）、:52-60（转账一对一、手续费独立）、:200-207（来源支付时间作统计时间、来源事实不可覆盖）、:209-215（来源/候选/正式账目分层）、:239-245（对账在 Posting 级、四要素、不得自动补平）。
- **边界纪律**：AGENTS.md（金额精确十进制/最小货币单位；解析只生成带 provenance/confidence 的候选；只有明确确认创建正式交易；`.external/` 只读）；CONTRIBUTING.md（docs/specs 状态标记约定、文档不含本机绝对路径与个人账务数据）。

术语：`本批` = BP-01；`解析器` = 本批 CMB/CCB 解析器（无 I/O/Clock/随机/路径依赖的确定性纯函数，接收字节流 + opaque synthetic input ref）；`spine` = D-092 共享导入链 source → evidence → candidate → confirmation → 正式账务；`拒行` = 按冻结类型范围 fail-closed 拒绝的数据行（零 record、零写入）；`fail-closed` = 未知/未授权输入整体或逐行拒绝，绝不猜测或静默映射；`余额镜像` = 银行流水行内声明余额（CMB 列 4 / CCB 列 6）只作证据与对账维度，不进正式交易金额、不创建任何新余额/正式账语义。

## 1. 范围与目标

1. **批定义**：BP-01 = D-109 O-8 银行 parser 门承接批。交付 CMB 网银 CSV 与 CCB 网银 XLS 两个来源的：容器/行/列解码 → 五类事实映射 → spine intake（候选 pending）→ 明确确认 → 正式账务；并携带余额镜像维度。该批证明共享 spine 承接真实银行来源（D-020 复用方向），并关闭银行 parser 门的两个网银导出格式部分（PDF 门继续开放）。
2. **用户 2026-08-28 裁决范围（冻结）**：
   - 普通收支 formalization：样本中可识别为普通消费/收款的类型 token 路由到既有 `OrdinaryFlowFormal` 语义（`ImportConfirmDecisionFields.OrdinaryFlow`：category + fundingAccount，P4-04 §4.2 原样复用）。
   - 明确转账/提现路由：样本中可识别的自有钱包/理财 ↔ 银行的自转 token 路由到 P4-04/P4-05 已批准 transfer 语义（`ImportRecordKind.TRANSFER_FLOW_SOURCE` 完整腿 kind + `TransferFlow` 确认 + `createOwnAssetPrincipalTransfer`；用户 2026-08-28 裁决，D-102 余额宝先例）；银行侧方向门变体独立登记（§3.4）。
   - 余额镜像：每行声明余额作余额锚点/对账维度（连续性校验 + 期末锚点比较），**不进正式交易金额**，不引入任何新的余额/正式账语义（RL-07 `GL-0DCF5FCDB9BA`：两端只形成一笔正式转账、第二来源作为补充证据）。
   - 其余类型（退款、未知 token、非授权形态）fail-closed 拒绝并登记（退款 → P4-06 维度，与 P4-03/P4-05 先例一致）。
3. **非目标（批内不做）**：不做 PDF 解析、不做其他银行、不做信用卡/贷款账单；不引入 matcher 新语义（D-103 组合不变）；不引入产品 Clock/随机 ID；不改任何既有正式语义；不实现余额观察持久化（见 §7 schema 预期与 §8 待决问题）。

## 2. 来源格式契约（冻结）

格式事实全部为**行为证据**（D-099:1538 先例），来源 = 本地真实导出文件的结构分析（主代理 2026-08-28 提供样本，仓库外）。本文件只冻结由此派生的中立契约；具体编码/列名 token 为格式契约常量，允许出现在 tracked 文档；账号、户名、金额、商户名、真实日期与订单标识等个人值一律不入文（AGENTS.md 隐私边界）。冻结依据为字节级证据；任何与本契约不符的真实导出变化一律 fail-closed（P4-05 §9 教训：不得以近似描述代替字节级冻结）。

### 2.1 招商银行网银 CSV（CMB）

**容器与编码**（冻结）：
- 纯文本 CSV，严格 UTF-8 解码（带 BOM；证据两文件均以 `EF BB BF` 开头；解码后 U+FEFF 前缀剥离）。
- 行分隔符 = CRLF（证据两文件 100% CRLF，无裸 CR）。解码失败/非 UTF-8 → `INPUT_DECODE_FAILED`。
- 有界输入：`MAX_INPUT_BYTES = 10MB`、`MAX_DATA_ROWS = 20,000`（裕度设置，证据最大 47KB / 436 数据行）；超限 → `INPUT_UNSAFE_OR_OVER_LIMIT`。

**注释头（零读取）**：
- 第 1-6 行（1-based）为引号包裹的 `#` 注释行（标题/导出时间/账号（含掩码尾号）/币种/起始-终止日期/过滤设置）；第 7 行为空引号行 `""`。该块整体零读取、零校验内容，不进解析输出、诊断、日志或任何持久化（P4-03 元数据区先例）。
- 数据表头固定在第 8 行（1-based，即 0-based row 7），禁止表头行号扫描与漂移容差（D-099:1536 语义）。冻结 token 清单（顺序固定，7 列，全部引号包裹）：`交易日期`,`交易时间`,`收入`,`支出`,`余额`,`交易类型`,`交易备注`。任何缺失/多余/错位/错字 → `STRUCTURE_MISMATCH`（fatal/structure, {input_ref}），批次 `rejected`、零 record。
- 证据注记：任务描述「`#` 注释头约 1-5 行」与字节级证据（6 行注释 + 1 空行）不一致；以字节级证据为准冻结（P4-05 §9 纪律）。注释块行数变化是否存在于其他导出版本登记为开放问题（§8 第 8 项）。

**数据行**（自第 9 行起）：
- 每行按 RFC-4180 双引号规则解析，恰 7 字段（字段内不出现未转义引号；证据字段内无逗号）。
- **前置制表符 quirk（冻结）**：列 0（交易日期）、列 1（交易时间）、列 6（交易备注）的字段值以单个制表符 `\t` 开头（引号内），解析后剥离；列 2/3/4/5 不带制表符。剥离后语义解码。
- 行结构不变量：字段数恰为 7；列 0/1 为日期/时间文本；列 2/3 金额或空；列 4 余额必填；列 5 类型 token 非空；列 6 备注可为空（证据恒非空但契约允许空）。任一违例 → 该行 `STRUCTURE_MISMATCH`（fatal，record 级，{input_ref, record_ordinal}），零 record，其余记录保留。
- 数据区中的非 7 字段行：单字段空行（`""`）或单字段 `#` 前缀行（引号包裹的尾部汇总）整体跳过、零读取；其他形态 → `STRUCTURE_MISMATCH`（record 级）。
- 尾部块（零读取、跳过）：最后一个数据行之后可有空引号行与至多两条引号包裹的 `#` 汇总行（`# 收入合计: …` / `# 支出合计: …`），两者顺序不定（字节证据两文件均为「空引号行在前、两条汇总行随后」），任意排列均整体跳过、零读取；其聚合数值为派生事实、不持久化（通道级汇总只作诊断，D-095 先例）。
- `record_ordinal` = 数据区绝对行序（row − 8，0-based）；空行/汇总行不产出 record 且不重排序号（P4-03 §2.2 先例）。

**字段语义**（列索引 0-based，未列出列不进入事实或持久化）：

| 列 | 读取规则 | 映射 |
| --- | --- | --- |
| 0 交易日期 | `\t` 剥离后 `YYYYMMDD`（8 位，无分隔符） | occurred_at 日期部分 |
| 1 交易时间 | `\t` 剥离后 `HH:MM:SS` | occurred_at 时间部分 |
| 2 收入 | 空 或 精确 2 位小数非负金额 | direction=in 时取收入值 |
| 3 支出 | 空 或 精确 2 位小数非负金额 | direction=out 时取支出值 |
| 4 余额 | 必填，精确 2 位小数非负金额 | 余额镜像（§2.3 第 3 条）；不进入五类事实 |
| 5 交易类型 | 文本 token（冻结集合见 §3.1） | 路由列（不进五类事实） |
| 6 交易备注 | 文本（`\t` 剥离后） | 不持久化（provider DTO 零引入） |

- 金额精度冻结：恒为 2 位小数（`\d+\.\d{2}`，证据 100% 符合）；`currency_precision = 2` 常量（支付宝 P4-05 §2.4 先例，非逐行派生）。非 2 位小数/负号/非数值 → `FIELD_AMOUNT_INVALID`（record_error/field，不降格 incomplete，D-097:1447）。
- 日期/时间无效（月份/日期越界、时间越界、形状违例）→ `FIELD_TIME_INVALID`（record_error/field）。
- **行序（冻结 quirk）**：数据行按（交易日期, 交易时间）**降序**（新在前）；证据两文件严格降序（含少量同日同时刻并列行）。解析器不假设行序只用于解码；余额连续性校验按文件顺序使用 §2.3 的降序不变量。
- 方向判定：见 §2.3 第 2 条（收/支分列互斥）。
- currency_code = `CNY`、offset = `+08:00`：格式契约常量（证据冻结，境内银行导出；非运行时默认、非 source_declared）；provenance rule `currency_v1`/`bank_offset_v1`、version 1、confidence `exact`。

### 2.2 建设银行网银 XLS（CCB）

**容器与编码**（冻结）：
- OLE2/BIFF `.xls`（非 .xlsx）单工作表 `Sheet0`。证据经 xlrd 2.0.2 读取；产品解析技术（POI HSSF vs 自研 BIFF 读取）留实施批证据门（§6 第 3 条）。
- 有界输入：文件大小与行数上界常量同 CMB（10MB / 20,000 行）。

**标题区（零读取）**：
- 第 1 行：标题文本（工作表标题，跨列）。
- 第 2 行：「标签+值」文本单元格（如 `卡号/账号:<值>`、`客户名称:<值>`、`起始日期:<YYYYMMDD>`、`结束日期:<YYYYMMDD>`——标签与值在同一文本单元格内，非裸日期）。
- 第 3 行：单个文本单元格（约 41 字符）同时含两组收支合计数字（总支出与总收入）。
- 以上三行整体零读取、零校验内容，不进解析输出、诊断、日志或持久化（含账号、户名）。

**数据表头**（第 4 行，冻结 token 清单，9 列）：`序号`,`摘要`,`币别`,`钞汇`,`交易日期`,`交易金额`,`账户余额`,`交易地点/附言`,`对方账号与户名`。任何缺失/多余/错位/错字 → `STRUCTURE_MISMATCH`（fatal/structure, {input_ref}），批次 `rejected`、零 record。证据注记：任务描述的表头清单缺第 9 列 `对方账号与户名`；以字节级证据为准冻结（9 列）。
- 文件行数不足 5 行（无表头）→ `STRUCTURE_MISMATCH`，批次 `rejected`。

**数据行**（自第 5 行起）：
- 证据全部单元格为**文本字符串**（含金额、余额、日期）；契约冻结为文本单元格解码（不依赖数字单元格）。
- 行结构不变量：9 字段；列 0 序号为整数文本（允许跳号、不要求连续；跳号与非递增均不判错，仅要求整数形状与非空，非整数或缺失 → `STRUCTURE_MISMATCH`）；列 2 币别、列 3 钞汇、列 4 日期、列 5 金额、列 6 余额必填；列 1/7/8 可为空文本。任一违例 → `STRUCTURE_MISMATCH`（record 级）。
- 行序：按交易日期**升序**（证据符合）；同日多行并列合法。
- `record_ordinal` = 数据区绝对行序（row − 5，0-based）。

**字段语义**（列索引 0-based）：

| 列 | 读取规则 | 映射 |
| --- | --- | --- |
| 0 序号 | 整数文本（允许跳号、非递增不判错；非整数/缺失 → STRUCTURE_MISMATCH） | 不持久化（仅结构校验） |
| 1 摘要 | 文本 token（冻结集合见 §3.2） | 路由列（不进五类事实） |
| 2 币别 | 文本（证据恒「人民币元」） | 不持久化；currency_code 由格式常量 `CNY` 给出（§2.1 同款 provenance） |
| 3 钞汇 | 文本（证据恒「钞」；值域 {钞, 汇}） | 不持久化（provider DTO 零引入）；值域外 → `STRUCTURE_MISMATCH`（record 级） |
| 4 交易日期 | `YYYYMMDD` | occurred_at 日期部分（时间缺失 → 冻结午夜填充，见下） |
| 5 交易金额 | 带符号精确 2 位小数文本（`-?\d+\.\d{2}`） | amount 与方向（§2.3 第 2 条）；负号 = out，非负 = in |
| 6 账户余额 | 必填，非负 2 位小数 | 余额镜像；不进入五类事实 |
| 7 交易地点/附言 | 自由文本 | 不持久化（含商户名等个人值）；仅特定类型行做冻结渠道 token 精确匹配子路由（§3.3 开放问题 1） |
| 8 对方账号与户名 | 自由文本（掩码账号/户名形状） | 不持久化；零读取内容 |

- occurred_at：日期 + 冻结午夜填充 `T00:00:00+08:00`（格式无时间维度；确定性机械填充，非 Clock 补写；作为设计决策登记，§8 第 6 项）。时间戳精确到秒、offset 恒 `+08:00`。
- 金额精度冻结：恒 2 位小数；`currency_precision = 2` 常量。非 2 位小数/非数值 → `FIELD_AMOUNT_INVALID`。
- 零金额（`0.00` / `-0.00`）：方向语义未定义（符号不能表达方向）→ 方向 unresolved → `valid_incomplete`（REQUIRED_FACT_UNRESOLVED, direction），不可确认（fail-closed，不猜测）。证据无零金额行。
- 日期无效 → `FIELD_TIME_INVALID`；金额列缺失/空 → `FIELD_AMOUNT_INVALID`；余额列缺失/空 → `SPINE_BANK_BALANCE_MISSING`（§2.3 第 4 条）。

### 2.3 五类事实映射、方向判定与余额镜像（两格式共享）

1. **五类事实映射**（D-097:1451）：amount（整数 minor units + currency_code + currency_precision=2）、occurred_at（ISO-8601 offset datetime 文本，+08:00）、direction、status 全部 present 且已知 → `valid_complete`。currency_code/offset 为格式契约常量。
   - **status 事实（冻结）**：两种银行格式均无交易状态列；银行入账流水在格式语义上即为已清算记账（无未决状态维度）。冻结 status_token = `settled`，provenance rule `bank_statement_cleared_v1`、version 1、confidence `exact`（格式契约常量，非推断；登记为设计决策，§8 第 7 项）。
2. **方向判定（fail-closed 规则）**：
   - CMB：收/支分列。收入列非空且支出列空 → `in`；支出列非空且收入列空 → `out`；**两列同时非空 → `CONFLICTING_SOURCE_FACTS`（record_error/record），零 record**；**两列同时为空 → 金额事实缺失，`FIELD_AMOUNT_INVALID`（record_error/field），零 record**。方向只来自列选择，不来自类型 token（证据：`银联快捷支付` 同时出现 in/out 方向）。
   - CCB：单金额列带符号。负号 → `out`；非负 → `in`（机械符号解码，provenance rule `amount_sign_direction_v1`、version 1、confidence `exact`）。**方向不来自摘要语义**（证据归纳：`充值` 恒 out、`银联入账` 恒 in、`支付机构提现` 恒 in，但符号是唯一机械来源；摘要只决定类型族）。零金额方向 unresolved（见 §2.2）。
   - 两格式均无「方向推断后符号翻转」需求（无负金额来源先例；CMB 分列、CCB 带号）。
3. **余额镜像（余额锚点/对账维度，冻结）**：
   - 每行声明余额（CMB 列 4 / CCB 列 6）是银行侧声明观察值，只作证据与对账维度，**永不进入五类事实、正式交易金额、evidence_projection 的金额或任何正式语义**（RL-07 边界；D-020 余额锚点复用方向的承接）。
   - **连续性校验（解析级诊断）**：
     - 连续性按**原始行（路由前）**口径逐行计算，与类型路由结果无关：拒行行（如 CMB 主批 R9/R10 及 `batch-bp01-cmb-h` 的 ZDFF 行）的声明余额仍参与链式断言（拒行只是不产生候选，余额列仍是银行真实声明）；record_error 行（金额/时间非法，如 R12-R15）因无法计算合法 delta 不参与连续性计算，其后续行链重锚定（§4.1 注记）。
     - CMB（文件序 = 日期降序）：`declared_balance[i] = declared_balance[i-1] + expense[i-1] − income[i-1]`（证据两文件 100% 成立）。
     - CCB（文件序 = 日期升序）：`declared_balance[i] = declared_balance[i-1] + signed_amount[i]`（证据 100% 成立）。
     - 失配 → `SPINE_BANK_BALANCE_CONTINUITY`（**非阻断诊断**，severity `note`，record scope，{input_ref, record_ordinal}；D-098:1516 同风格追加注册，§2.4）；该行五类事实仍有效时**记录仍正常产出 valid_complete**（余额不是正式事实），失配只降低余额锚点可信度，不阻断路由与确认。
   - **期末/时点锚点校验（对账维度）**：账本对该资金账户在语句期间的有效正式分录重放余额与语句声明余额比较；差异只作诊断，不改账（§5.3 oracle，限定到连续确认段）。余额不触发任何正式调整语义（D-035/D-063 目标余额调整属 RG-09 场景，本批不接入）。
4. **余额列异常形态**：CMB 列 4 / CCB 列 6 缺失或非 2 位小数 → `SPINE_BANK_BALANCE_MISSING`（**非阻断诊断**，severity `note`，field scope，field_role=balance）；该行五类事实有效时记录仍产出 valid_complete（余额镜像缺失仅降低锚点维度）。显式边界：余额族诊断是 `note` 级非阻断诊断，行正常产出 record；D-097 `record_error` 语义（无效金额/时间 → 该行零 record）不受影响、不降级。

### 2.4 无效/未知行分类与诊断码族（沿用 P4-03/05 失败分类学）

诊断码按 D-097 taxonomy 同风格（code/severity/scope/安全 location；message 不稳定、不比较）。severity 值域 = D-097 `fatal | record_error | incomplete` + P4-03/05 已注册的 `unsupported`（record 级）+ 本批注册的 `note`（非阻断诊断，record/field 级，D-098:1516「同风格追加注册」授权先例）。安全 location 只由 {input_ref, record_ordinal, field_role} 构成，不含 raw value、表头、整行、标题区内容或个人标识。**本批提议的诊断码集合（具体编码随实施批冻结，D-098:1516 同风格追加注册先例）：**

| code | severity | scope | 安全 location | 触发 |
| --- | --- | --- | --- | --- |
| INPUT_UNSUPPORTED / INPUT_UNSAFE_OR_OVER_LIMIT / INPUT_DECODE_FAILED / STRUCTURE_MISMATCH | fatal | input/container/structure | {input_ref} 或 {input_ref, record_ordinal} | 复用 D-097 冻结码（§2.1/§2.2 各容器与结构路径） |
| SPINE_CMB_UNSUPPORTED_TX_TYPE | unsupported | record | {input_ref, record_ordinal} | CMB 交易类型 ∈ 拒绝集合（§3.1） |
| SPINE_CMB_REFUND_UNSUPPORTED | unsupported | record | {input_ref, record_ordinal} | CMB 类型含「退款」或退款 token（判定顺序 1） |
| SPINE_CMB_UNKNOWN_TOKEN | unsupported | record | {input_ref, record_ordinal} | CMB 交易类型不在任何冻结集合 |
| SPINE_CCB_UNSUPPORTED_TX_TYPE | unsupported | record | {input_ref, record_ordinal} | CCB 摘要 ∈ 拒绝集合（§3.2） |
| SPINE_CCB_REFUND_UNSUPPORTED | unsupported | record | {input_ref, record_ordinal} | CCB 摘要或附言含「退款」（判定顺序 1） |
| SPINE_CCB_UNKNOWN_TOKEN | unsupported | record | {input_ref, record_ordinal} | CCB 摘要不在任何冻结集合 |
| SPINE_BANK_BALANCE_CONTINUITY | note（非阻断） | record | {input_ref, record_ordinal} | 余额连续性失配（§2.3 第 3 条；记录仍产出 valid_complete） |
| SPINE_BANK_BALANCE_MISSING | note（非阻断） | field | {input_ref, record_ordinal, field_role=balance} | 余额列缺失/形状违例（§2.3 第 4 条；记录仍产出 valid_complete） |
| FIELD_AMOUNT_INVALID / FIELD_TIME_INVALID | record_error | field | {input_ref, record_ordinal, field_role} | 复用 D-097 冻结码（金额/时间无效） |
| REQUIRED_FACT_UNRESOLVED / REQUIRED_FACT_MISSING | incomplete | field | {input_ref, record_ordinal, field_role} | 复用 D-097 冻结码（事实缺失/未决，如 CCB 零金额方向） |
| CONFLICTING_SOURCE_FACTS | record_error | record | {input_ref, record_ordinal} | 复用 D-097 冻结码（CMB 收/支双填） |

- 诊断码政策（沿用 P4-03 §5/P4-05 §4）：容器/输入/结构级复用 D-097 冻结码；银行专属码限类型路由（每 provider 3 码）+ 余额族 2 码；事实级复用 D-097 冻结码。余额族两码为 `note` 级**非阻断诊断**（D-098:1516 同风格追加注册），行正常产出 record；`note` 不进入 record_error 语义——D-097 无效金额/时间 → 该行零 record 的行为不变。每个拒行/record_error 恰好携带一个诊断码；parse 结果诊断按 multiset 比较。
- 批次 outcome（对齐 D-097）：input/container fatal → `rejected`（零 record）；否则 `complete`（零诊断）或 `partial`（≥1 条 record 级诊断或拒行）。

## 3. 类型路由映射

类型路由 = 路由列（CMB 交易类型 / CCB 摘要）token → 类型族；方向始终来自 §2.3 方向规则（**类型 token 永不决定方向**）。token 集合为行为证据冻结的封闭集合（开放域处置沿用 P4-05 §3.1：集合外一律 UNKNOWN 拒行，扩张只经显式合同修订）。

### 3.1 CMB 交易类型矩阵（样本证据：两文件共 579 数据行）

| 交易类型 token | 样本方向 | 本批处置 | 正式语义 |
| --- | --- | --- | --- |
| 网联协议支付 | out（294 行） | 接受 | 普通支出（OrdinaryFlow：category + funding） |
| 银联快捷支付 | out（64）/ in（4） | 接受 | 普通支出（out）/ 普通收入（in）；in 变体语义登记（§8 第 4 项） |
| 网联付款交易 | in（61） | 接受（普通收入） | 普通收入；其附言可见自有钱包渠道（如「财付通…微信零钱」）→ RL-07 镜像相关（§8 第 1 项） |
| 银联代付 | in（34） | 接受 | 普通收入 |
| 银联在线支付 | out（1） | 接受 | 普通支出 |
| 数字人民币随用随充消费 | out（9） | 接受 | 普通支出（数字人民币钱包消费） |
| 支付鼓励金 | in（1） | 接受 | 普通收入（激励金） |
| 账户结息 | in（1） | 接受 | 普通收入（利息） |
| 汇入汇款 | in（5） | 接受（普通收入） | 普通收入；来源方是否自有账户无法从银行侧证明（§8 第 1 项） |
| 数字人民币充值 | out（12） | 接受 | 转账（`transfer_flow_source`；银行→数字人民币钱包；out → 银行=from，钱包=to） |
| 数字人民币存银行 | in（51） | 接受 | 转账（`transfer_flow_source`；数字人民币钱包→银行；in → 银行=to，钱包=from） |
| 朝朝宝购买 | out（1） | 接受 | 转账（`transfer_flow_source`；银行→理财账户） |
| 朝朝宝赎回 | in（4） | 接受 | 转账（`transfer_flow_source`；理财账户→银行） |
| 网联退款 | in（35） | 拒行 `SPINE_CMB_REFUND_UNSUPPORTED` | 退款族 → P4-06 维度（AGENTS.md 边界；与 P4-03/P4-05 退款拒行先例一致） |
| STZF / ZDFF | out / in（各 1） | 拒行 `SPINE_CMB_UNKNOWN_TOKEN` | 未知 token fail-closed（未登记形态） |
| 任何其他 token | — | 拒行 `SPINE_CMB_UNKNOWN_TOKEN` | 无法安全路由 → fail-closed |

- 备注：`银联快捷支付` in 变体与 `支付鼓励金` 样本稀少；`支付鼓励金` 是否属退款/激励收入族登记为开放问题（§8 第 4 项）。

### 3.2 CCB 摘要矩阵（样本证据：8 数据行）

| 摘要 token | 样本方向 | 本批处置 | 正式语义 |
| --- | --- | --- | --- |
| 消费 | out（3） | 接受 | 普通支出 |
| 银联入账 | in（1，附言=自有钱包提现） | 接受（普通收入，默认） | 普通收入；RL-07 镜像敏感（§8 第 1 项：推荐附言渠道 token 子路由提升为转账） |
| 充值 | out（2） | 接受 | 转账（`transfer_flow_source`；银行→支付钱包；out → 银行=from） |
| 支付机构提现 | in（1） | 接受 | 转账（`transfer_flow_source`；支付钱包→银行；in → 银行=to） |
| 数字人民币兑出 | out（1） | 接受 | 转账（`transfer_flow_source`；银行→数字人民币钱包；out → 银行=from） |
| 摘要或附言含「退款」 | — | 拒行 `SPINE_CCB_REFUND_UNSUPPORTED` | 退款族 → P4-06 维度 |
| 任何其他 token | — | 拒行 `SPINE_CCB_UNKNOWN_TOKEN` | 无法安全路由 → fail-closed |

### 3.3 判定顺序与路由实现（两格式统一）

1. 退款判定（优先）：类型/摘要 token 为退款 token，或（CCB）附言含「退款」→ 对应 `*_REFUND_UNSUPPORTED` 拒行。
2. 拒绝集合判定：token ∈ 非授权拒绝集合 → `*_UNSUPPORTED_TX_TYPE` 拒行（本批两格式均无独立拒绝集合除退款族；未来批次扩展时在此登记）。
3. 未知判定：token ∉ 任何冻结集合 → `*_UNKNOWN_TOKEN` 拒行。
4. 接受路由：token ∈ 接受集合 → 按矩阵进入普通收支或转账族；五类事实映射（§2.3）。
- 转账族行 → `record_kind = transfer_flow_source`、`contract_version = 2`、`candidate_kind = transfer_flow`（**完整腿 kind、可确认**；用户 2026-08-28 裁决，D-102 余额宝先例：`AlipayCsvParser` 将余额宝自转行路由为 `TRANSFER_FLOW_SOURCE`）。银行侧自转行用完整 kind 的依据：交易类型/摘要 token 已把对方归为自有钱包/理财族（数字人民币钱包、朝朝宝、支付钱包），确认时用户显式提供两端账户（`TransferFlow(fromAccountId,toAccountId)`），无需 missing-leg 降级；**银行侧只证明银行腿**，钱包/理财腿仍由确认时用户显式选择，**永不从附言/备注/对方文本推导任何腿**（D-100 §7；D-032 信息不足不猜测）。方向门变体见 §3.4。
- 普通收支族行 → `record_kind = ordinary_flow_source`、`contract_version = 1`、`candidate_kind = ordinary_flow`；确认用 `OrdinaryFlow(categoryId, fundingAccountId)`（P4-04 §4.2 原样复用）。
- 手续费：两格式样本均无独立手续费行；手续费独立支出语义（D-031）不在本批（若未来出现手续费形态，经冻结集合扩张修订，不作静默映射）。

### 3.4 银行侧方向门变体（独立登记契约修订）

- P4-04 冻结的方向门是**钱包视角**（D-100 §7）：微信 `零钱提现`（支出）→ 钱包=from、`零钱充值`（收入）→ 钱包=to；钱包腿由来源方向证明。该语义原样冻结、本批零改动。
- 本批新增**银行侧方向门变体**（D-100 §7 风格契约修订，独立登记）：银行视角 out = 钱出银行 → **银行=from、钱包=to**；in = 钱进银行 → **银行=to、钱包=from**。两视角是同一资金流的两侧视图、互为镜像：同一笔钱包↔银行自转在微信/支付宝侧显示为钱包视角方向，在银行侧显示为银行视角方向（RL-07 场景）。
- 适用范围与不变量：该变体只作用于本批银行侧自转/镜像路由（§3.1/§3.2 转账族 token）；不改动、不污染 P4-04 钱包视角语义；P4-04 冻结 oracle 对钱包视角流逐值不变（§5.5 R-01）。两端合并时（E-11）由既有 P4-08 镜像链保证只形成一笔正式转账，第二来源作为补充证据（RL-07 `GL-0DCF5FCDB9BA`）。

## 4. 匿名 fixture 设计（规格）

全部输入为合成、来源中立数据；金额精确整数最小货币单位；无真实账号、户名、商户、真实日期序列（日期用合成 YYYYMMDD）。具体 fixture 文件由实施批生成（本批只冻结规格）。fixture 规模：CMB 主批 17 行 + 变体批 12 个 input ref（含 M-3 扩展 token 覆盖批 `batch-bp01-cmb-h` 19 行）；CCB 主批 18 行 + 变体批 4 个 input ref。接受集合内每个 token ≥2 例（仅 1 例的已按 M-3 补足），覆盖边界行。

### 4.1 CMB 主批 `batch-bp01-cmb-a`（文件序 = 日期降序；余额按 §2.3 降序不变量生成，除注明外）

| # | 日期 | 时间 | 收 | 支 | 余额 | 类型 | 预期 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| R1 | 20260825 | 09:00:00 | | 128.50 | 500.00 | 网联协议支付 | ordinary expense valid_complete |
| R2 | 20260824 | 10:30:00 | | 12.50 | 628.50 | 银联快捷支付 | ordinary expense valid_complete |
| R3 | 20260823 | 11:15:00 | 88.00 | | 641.00 | 银联代付 | ordinary income valid_complete |
| R4 | 20260822 | 21:00:00 | 3.00 | | 553.00 | 账户结息 | ordinary income valid_complete |
| R5 | 20260821 | 08:05:00 | | 100.00 | 550.00 | 数字人民币充值 | transfer（`transfer_flow_source`，out） |
| R6 | 20260820 | 18:40:00 | 200.00 | | 650.00 | 数字人民币存银行 | transfer（`transfer_flow_source`，in） |
| R7 | 20260819 | 12:00:00 | | 500.00 | 450.00 | 朝朝宝购买 | transfer（`transfer_flow_source`，out） |
| R8 | 20260818 | 14:25:00 | 510.30 | | 950.00 | 朝朝宝赎回 | transfer（`transfer_flow_source`，in） |
| R9 | 20260817 | 09:30:00 | 30.00 | | 439.70 | 网联退款 | 拒行 SPINE_CMB_REFUND_UNSUPPORTED |
| R10 | 20260816 | 16:45:00 | | 9.90 | 409.70 | STZF | 拒行 SPINE_CMB_UNKNOWN_TOKEN |
| R11 | 20260815 | 07:20:00 | | 10.00 | 419.61（期望 419.60） | 网联协议支付 | valid_complete + SPINE_BANK_BALANCE_CONTINUITY |
| R12 | 20260814 | 13:10:00 | | 12.5（1 位小数） | 429.61 | 网联协议支付 | FIELD_AMOUNT_INVALID，零 record |
| R13 | 20260813 | 19:00:00 | | 7.00 | 436.61 | 网联协议支付 | FIELD_TIME_INVALID（日期 20251313），零 record |
| R14 | 20260812 | 22:00:00 | 5.00 | 5.00 | 429.61 | 网联协议支付 | CONFLICTING_SOURCE_FACTS，零 record |
| R15 | 20260811 | 06:00:00 | | | 429.61 | 网联协议支付 | FIELD_AMOUNT_INVALID（收/支双空），零 record |
| R16 | 20260810 | 15:30:00 | 99999999.99 | | 100000000.59 | 银联代付 | ordinary income valid_complete（超大额边界） |
| R17 | 20260809 | 08:00:00 | | 0.00 | 0.60 | 网联协议支付 | ordinary expense valid_complete（零金额） |

- 余额注记：R1-R11 按不变量链式断言（R11 为刻意失配；其声明余额 419.61 仍参与后续链）；R12-R15 因金额/事实非法不参与连续性计算（余额值仍给出，仅供结构解码）；R16 为链重锚定（前驱 R15 无合法金额、连续性检查不可计算，余额显式给定），R17 按不变量自 R16 推出并断言。开户种子与锚点断言分段（段 A = R1..R8、段 B = R16..R17）见 §5.3 B-02/B-03；M-3 扩展 token 覆盖行集独立落在变体批 `batch-bp01-cmb-h`（§4.2，自持独立余额链）。

### 4.2 CMB 变体批（input ref 族）

- `batch-bp01-cmb-b1..b4`：表头四类损坏（缺列/多列/错位/差一字）→ STRUCTURE_MISMATCH（fatal/structure），批次 rejected、零 record。
- `batch-bp01-cmb-c`：注释块行数变体（如 4 条注释 + 空行，表头仍位于第 8 行校验）→ 表头位置非第 8 行 → STRUCTURE_MISMATCH（fatal）。
- `batch-bp01-cmb-d`：无尾部汇总块（文件止于最后数据行）→ complete、零诊断（尾块可缺）。
- `batch-bp01-cmb-e1..e3`：行结构变体（字段数 6 / 字段数 8 / 非 tab 列含制表符）→ STRUCTURE_MISMATCH（record 级）、该行零 record、其余保留。
- `batch-bp01-cmb-f`：空输入 → INPUT_DECODE_FAILED；非 UTF-8 字节 → INPUT_DECODE_FAILED。
- `batch-bp01-cmb-g`：数据行余额列空 → SPINE_BANK_BALANCE_MISSING（非阻断）、记录 valid_complete。
- `batch-bp01-cmb-h`（M-3 扩展 token 覆盖批）：自持独立余额链（锚定 3000.00、日期降序 20260808..20260727、无失配、无重锚定；余额按 §2.3 不变量生成并于实施批钉死）的 19 条合成行，覆盖接受集合补足 ≥2 例与 fail-closed 归类确认：
  - 普通收入：网联付款交易 in ×2（其一附言含自有钱包渠道标记）、汇入汇款 in ×2、支付鼓励金 in ×2、银联快捷支付 in ×2、账户结息 in（第 2 例）；
  - 普通支出：银联在线支付 out ×2、数字人民币随用随充消费 out ×2、银联快捷支付 out（第 2 例）；
  - 转账：数字人民币存银行 in（第 2 例）、朝朝宝赎回 in（第 2 例）、数字人民币充值 out（第 2 例）、朝朝宝购买 out（第 2 例）；
  - 拒行：ZDFF in 1 例（SPINE_CMB_UNKNOWN_TOKEN，fail-closed 归类确认，与主批 STZF 对应）。
  逐行断言连续性与路由预期。**一致性声明**：该 19 行链（锚定 3000.00、降序、无失配）必须同时保证 E-07b/E-07c 引用的网联付款交易行金额 30.00 与数字人民币存银行行金额 50.00 成立；实施批以此链与两引用值同时成立为钉死约束。
- 全空行与 EOF 尾空行：不产出 record、ordinal 不重排。

### 4.3 CCB 主批 `batch-bp01-ccb-a`（文件序 = 日期升序；余额按 §2.3 升序不变量生成）

| # | 摘要 | 币别 | 钞汇 | 日期 | 金额 | 余额 | 附言（合成） | 预期 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| B1 | 消费 | 人民币元 | 钞 | 20260825 | -12.80 | 100.00 | 合成商户 | ordinary expense valid_complete |
| B2 | 消费 | 人民币元 | 钞 | 20260825 | -33.50 | 66.50 | 合成商户 | ordinary expense valid_complete |
| B3 | 银联入账 | 人民币元 | 钞 | 20260825 | 10.00 | 76.50 | 微信零钱提现 | 见 §8 第 1 项（推荐：普通收入；备选：附言渠道子路由 → 转账 transfer_flow_source） |
| B4 | 充值 | 人民币元 | 钞 | 20260826 | -1.00 | 75.50 | 合成钱包渠道 | transfer（`transfer_flow_source`，out） |
| B5 | 支付机构提现 | 人民币元 | 钞 | 20260826 | 7.50 | 83.00 | 微信零钱提现 | transfer（`transfer_flow_source`，in） |
| B6 | 数字人民币兑出 | 人民币元 | 钞 | 20260827 | -1.01 | 81.99 | 钱包充值 | transfer（`transfer_flow_source`，out） |
| B7 | 消费 | 人民币元 | 钞 | 20260828 | -0.01 | 81.98 | 合成商户 | ordinary expense valid_complete |
| B8 | 消费 | 人民币元 | 钞 | 20260828 | 0.00 | 81.98 | 合成商户 | direction unresolved → valid_incomplete（REQUIRED_FACT_UNRESOLVED, direction） |
| B9 | 未知摘要 | 人民币元 | 钞 | 20260828 | -1.00 | 80.98 | 合成 | 拒行 SPINE_CCB_UNKNOWN_TOKEN |
| B10 | 消费 | 人民币元 | 钞 | 20260828 | 10.00 | 90.98 | 合成含「退款」 | 拒行 SPINE_CCB_REFUND_UNSUPPORTED |
| B11 | 消费 | 人民币元 | 钞 | 20260828 | -7.50 | 83.49（期望 83.48） | 合成商户 | valid_complete + SPINE_BANK_BALANCE_CONTINUITY |
| B15 | 充值 | 人民币元 | 钞 | 20260828 | -2.00 | 81.49 | 合成钱包渠道 | transfer（`transfer_flow_source`，out） |
| B16 | 支付机构提现 | 人民币元 | 钞 | 20260828 | 20.00 | 101.49 | 微信零钱提现 | transfer（`transfer_flow_source`，in） |
| B17 | 数字人民币兑出 | 人民币元 | 钞 | 20260828 | -0.05 | 101.44 | 钱包充值 | transfer（`transfer_flow_source`，out） |
| B18 | 银联入账 | 人民币元 | 钞 | 20260828 | 8.80 | 110.24 | 合成普通入账 | 普通收入（默认路由，§8 第 1 项） |
| B12 | 消费 | 人民币元 | 钞 | 20260828 | 12.5（1 位小数） | 未断言 | 合成 | FIELD_AMOUNT_INVALID，零 record |
| B13 | 消费 | 人民币元 | 钞 | 20251313 | -7.00 | 未断言 | 合成 | FIELD_TIME_INVALID，零 record |
| B14 | 消费 | 人民币元 | 钞 | 20260828 | （空） | 未断言 | 合成 | FIELD_AMOUNT_INVALID（金额缺失），零 record |

- 余额注记：B1-B10 按不变量链式断言；B11 紧接 B10 之后构造失配（期望 = B10 余额 + B11 金额 = 83.48，声明 83.49；其声明余额 83.49 仍参与后续链）；B15-B18 按不变量自 B11 声明余额链式断言；B12-B14 因金额/日期非法不参与连续性断言（余额值不给出）。
- 文件序（显式）：数据行文件序 = B1..B11、B15..B18、B12..B14——B15-B18 紧随 B11 之后、B12-B14 位于文件尾部，与表序及余额注记一致（B15-B18 自 B11 声明余额的链式断言要求 B15 紧邻 B11）。

### 4.4 CCB 变体批（input ref 族）

- `batch-bp01-ccb-b`：表头 token 差一字 → STRUCTURE_MISMATCH（fatal）；行数 < 5 → STRUCTURE_MISMATCH（fatal）。
- `batch-bp01-ccb-c`：钞汇 = 汇（值域合法变体）→ 记录 valid、钞汇不持久化。
- `batch-bp01-ccb-d`：序号跳号/非递增 → 不判错（仅结构校验，§2.2 一致）；序号缺失或非整数 → STRUCTURE_MISMATCH（record 级）。
- `batch-bp01-ccb-e`：金额/余额/日期单元格为数字单元格（非文本）→ 数字单元格形状解码等价（记录 valid；解码为精确十进制文本，禁浮点）。

## 5. 操作集与期望结果（oracle 面）

与 spine 同构（P4-02 先例）：intake → candidate → confirm/reject；replay/identity 冲突/失败注入语义沿用 P4-02 §1.3/§8。oracle 分解析级（P 系列）与端到端代表路径（E 系列），并含余额镜像断言（§5.3）。

### 5.1 解析级 oracle（P 系列）

- P-01…P-17（CMB 主批 R1-R17 逐行）：facts 字段级、completeness、诊断 code/severity/scope/location、零 record/零写入；R11 同时断言 valid_complete + 连续性诊断（非阻断）；R12-R15 零 record 与诊断码钉死；R9/R10 拒行零 record。
- P-18 整批 CMB 主批：outcome = `partial`；11 条 valid_complete 记录（R1-R8、R11、R16、R17）；拒行行（R9/R10）与 record_error 行（R12/R13/R14/R15）零 record；诊断 multiset 钉死 = {R9: REFUND、R10: UNKNOWN、R11: BALANCE_CONTINUITY、R12: FIELD_AMOUNT_INVALID、R13: FIELD_TIME_INVALID、R14: CONFLICTING_SOURCE_FACTS、R15: FIELD_AMOUNT_INVALID}（7 条）。
- P-19…P-30：CMB 变体批（§4.2 各 input ref，含 `batch-bp01-cmb-h` 的 19 行逐行路由与整批 partial 断言）。
- P-31…P-49：CCB 主批 B1-B18 逐行 + 整批 outcome（`partial`；拒行 B9/B10 与 record_error B12/B13/B14 零 record；B11 valid_complete + SPINE_BANK_BALANCE_CONTINUITY 并存；诊断 multiset 钉死 = {B8: REQUIRED_FACT_UNRESOLVED、B9: UNKNOWN、B10: REFUND、B11: BALANCE_CONTINUITY、B12: FIELD_AMOUNT_INVALID、B13: FIELD_TIME_INVALID、B14: FIELD_AMOUNT_INVALID}）+ CCB 变体批（§4.4）。
- P-50…P-53：CCB 变体批（§4.4）。
- P-54 余额连续性向量：CMB 降序不变量与 CCB 升序不变量分别对全部合法行断言（零失配，除刻意失配行；拒行行仍按原始行口径参与、record_error 行不参与，§2.3 第 3 条）；日期边界（月初/月末/闰日）与同日多行时序向量。
- P-55 隐私断言：标题区/注释块（含掩码账号、户名）、附言、备注、对方账号与户名等非持久化列的值集合与解析输出/诊断/日志字符串集合不相交（对齐 P4-03 M-01/P4-05 P-18）。

### 5.2 spine 端到端代表路径（E 系列）

沿用 P4-02 Δ 形状与 claim-first 语义（O-01…O-30 先例）。ledger 与 catalog：`ledger-bp01`、自有真实资产账户 `account-asset-bank`（CNY）、自有真实钱包资产账户 `account-asset-wallet`（CNY）、二级支出分类 `category-food`、二级收入分类 `category-salary`。

- E-01 intake R1 @ req-a-intake → accepted（C1 pending_confirmation；Δ = request/source/evidence/candidate/status_history/receipt 各 +1，formal 0/0/0）。
- E-02 同请求等价重放 → no_change（`equivalent_replay`），Δ 全零。
- E-03 confirm C1 @ req-a-confirm，OrdinaryFlow(category-food, account-asset-bank)，explicitConfirmedAt=合成确认时间 → accepted；formal：transaction+1、version+1、posting+2（费用账户 +128.50、account-asset-bank −128.50，逐币种平衡）。
- E-04 setup intake R2、R5-R8（主批）及 `batch-bp01-cmb-h` 的网联付款交易行、数字人民币存银行行（共七条候选：一条普通支出、四条转账、一条网联付款交易、一条存银行，均 pending）→ 逐条 accepted。
- E-05 confirm R5 候选 @ req-b-confirm，TransferFlow(from=account-asset-bank, to=account-asset-wallet)，explicitConfirmedAt=合成时间 → accepted；formal：`createOwnAssetPrincipalTransfer` 平衡两腿（account-asset-bank −100.00、account-asset-wallet +100.00），internal transfer 本金，对外收支与报表效应为零（P4-04 锚点语义；out → 银行=from 方向门变体）。
- E-06 reject C2（R2 候选）@ req-b2-reject → accepted、无 confirmation、formal 0/0/0（reject 为人工处置终态，P4-02 O-11 同构）。
- E-07 confirm R4 候选 @ req-c-confirm，OrdinaryFlow(category-salary, account-asset-bank) → accepted；formal：account-asset-bank +3.00、收入账户 −3.00（利息收入入普通收入）。
- E-07b confirm `batch-bp01-cmb-h` 网联付款交易候选 @ req-c2-confirm，OrdinaryFlow(category-salary, account-asset-bank) → accepted；formal：account-asset-bank +30.00、收入账户 −30.00（网联付款交易普通收入；附言渠道标记不落盘）。
- E-07c confirm `batch-bp01-cmb-h` 数字人民币存银行候选 @ req-c3-confirm，TransferFlow(from=account-asset-wallet, to=account-asset-bank)，explicitConfirmedAt=合成时间 → accepted；formal：`createOwnAssetPrincipalTransfer`（account-asset-wallet −50.00、account-asset-bank +50.00）——in → 银行=to 方向门变体断言。
- E-08 同请求不同内容 → SPINE_REQUEST_IDENTITY_CONFLICT 零写入；expectedContentHash 失配 → SPINE_STALE_FINGERPRINT 零写入；重复确认已确认候选 → SPINE_CANDIDATE_NOT_PENDING。
- E-09 失败注入：intake 在 candidate 插入后注入失败 → 全回滚零残留、同请求重试 accepted；confirm 在 formal 持久化后注入失败 → 全回滚、重试 accepted（P4-02 O-23/O-24 同构）。
- E-10 并发：同请求同内容 intake ×2 → 1 accepted + 1 no_change；同候选并发确认 → 单赢家，IdSource 恰一次。

### 5.3 余额镜像断言（本批 oracle 的余额维度）

- B-01 连续性：解析级按**原始行（路由前）**口径对全部数据行断言连续性不变量（§2.3 第 3 条）；刻意失配行（CMB R11 / CCB B11）触发 SPINE_BANK_BALANCE_CONTINUITY（非阻断，记录仍产出）；拒行行（CMB 主批 R9/R10 及 `batch-bp01-cmb-h` 的 ZDFF 行）声明余额仍参与链式断言；record_error 行（R12-R15、B12-B14）不参与连续性计算；其余零失配。
- B-02 期末锚点（限定到连续确认段）：**开户种子** = 语句最老数据行声明余额按不变量前向一步推导 = `declared_balance[oldest] − delta[oldest]`（delta = 该行收入−支出 / 带符号金额；语句覆盖账户全历史时种子为 0）。**连续确认段** = 一段连续数据行，段内每行 delta 均有对应已确认正式分录。断言：`replay_balance(account-asset-bank, 段末) == declared_balance[段末]`，段起点账本余额基准 = 段时间序最前行声明余额的前向推导值（或开户种子）。CMB 主批两段：段 A = R1..R8（基准 = declared[R8] − delta[R8] = 950.00 − 510.30 = 439.70；断言段末 R8 = 950.00）、段 B = R16..R17（R16 链重锚定；基准 = declared[R17] − delta[R17] = 0.60 − 0.00 = 0.60；断言段末 R17 = 0.60）。CCB 段示例：确认 B4-B7（充值/支付机构提现/数字人民币兑出/消费），基准 = 段前一行（B3）声明余额 76.50，断言段末 B7 = 81.98。段外行（拒行/无效/失配/未确认）不影响段内断言；差异只作诊断，不产生任何正式或对账写入。
- B-03 时点锚点（限定到连续确认段）：对段内每个已确认行边界，`replay_balance(account-asset-bank, 行日期) == declared_balance[行]`（段 A：R8..R1 各边界；段 B：R17、R16）；段外行不参与。
- B-04 隐私：余额值只出现在解析中间结果与诊断（形状不落盘）；不进入 source facts、evidence_projection 或任何持久化列。

### 5.4 RL-07 镜像代表路径（E 系列延伸，复用既有 P4-08 链）

- E-11（两端一笔正式转账，双形状门）：在独立账本 `ledger-bp01-mirror`，先按 P4-04 语义确认微信侧零钱提现行（具名 fixture：`零钱提现` 支出 `10.00`、occurred_at = `20260824T09:00:00+08:00`——与 CCB B3（银联入账 +10.00、交易日期 20260825）镜像同额，且落在 P4-08 matcher 默认 ±2 自然日窗内；wallet=from、bank=to）→ 形成一笔正式转账（bank posting 已存在）；再 intake 银行侧对应行（CCB B3 形状：银联入账 + 附言 微信零钱提现）→ 银行原始 parser 事实按来源契约原样保留 `occurred_at = 20260825T00:00:00+08:00`，不由 parser 改写；原始事实直接进入既有 P4-08 `confirmLink` 时，按 P4-08 既有语义因 `P408_POSTING_TIME_UNRESOLVED` 拒绝且零写入；仅由镜像适配层在进入既有 `confirmLink` 前，将同一时刻规范化为 UTC `Z` 形（`20260824T16:00:00Z`），随后走既有 `confirmLink` + D-112 READY projection 完整链（同事务首步惰性物化 READY evidence projection + 显式目标绑定 = account-asset-bank）→ evidence link 建立、转账的 bank posting 对账状态推进为 CHECKED；**零第二笔正式转账、零第二笔收入**；等价重放 no_change。P4-08 既有语义与实现零改动。
- E-12（镜像后候选处置，拒绝/完整双路径）：对 E-11 的银行侧候选，原始 `+08:00` 形状直接进入既有 `confirmLink` 的路径以 `P408_POSTING_TIME_UNRESOLVED` 拒绝且零写入；镜像适配层在确认前规范化为 UTC `Z` 形后，走既有 `confirmLink` + D-112 READY projection 完整链并成功建立 evidence link、推进 CHECKED。完整路径的 formalization 仍阻断（P4-07 duplicate 阻断 `SPINE_DUPLICATE_NOT_CONFIRMABLE`）或允许用户显式 reject；两种处置均断言正式交易计数为 1、零第二笔正式转账、零第二笔收入，posting 对账状态与 evidence link 行集合与 P4-08/P4-09 RL-07 平台侧锚点同构（`P409PhaseClosureFullStateOracleTest.rl07PlatformSideMirrorSubsetZeroSecondTransaction` 先例）。

### 5.5 回归（R 系列）

- R-01：P4-02 30-op oracle 逐值不变；P4-03/P4-04/P4-05（含 RL-04）冻结 oracle 逐值不变——本批零改动 spine 表、端口、状态迁移、matcher 与 projection 行为（P4-05 §1.3 R-02 声明同款）。其中 P4-04 冻结 oracle 对**钱包视角流**（零钱提现/零钱充值）逐值不变；本批 §3.4 的银行侧方向门变体是独立登记的契约修订，不属于对 P4-04 的隐性改动，不改变任何既有冻结断言。
- R-02：P4-08 确认/投影/修正 oracle（TP-01..TP-18 与 correction TP 系列）逐值不变——本批不触碰 P4-08 表与语义。

## 6. 实现边界

1. **范围**：只做 CMB 网银 CSV 与 CCB 网银 XLS 两个格式的解析 + spine 对接。不做 PDF、不做其他银行、不做信用卡/贷款账单（未提供样本；PDF 门继续开放，D-099:1540）。
2. **matcher 语义**：不引入任何 matcher 新语义（D-103 O-1..O-6 组合不变）；镜像匹配复用既有 P4-08 matcher/confirmLink，不新增字段、时间窗、基数或 eligibility。
3. **parser 技术**：CMB CSV 与 CCB XLS 的读取技术（自研零依赖 vs POI HSSF）按 D-099:1537 六维模板在实施批过证据门（D-096:1421 技术门）；本批只冻结格式契约与「金额/时间全程整数 minor units/精确十进制、禁浮点」纪律。证据读取所用 xlrd 仅为 Python 行为证据工具，不是产品依赖。
4. **镜像/duplicate 集成深度**：银行侧镜像候选的 formalization 阻断机制（P4-07 duplicate 候选 vs 用户显式 reject）为实施批决定；本批契约只冻结「两端只形成一笔正式转账、第二来源作为补充证据」（RL-07）与 E-11/E-12 oracle 形态。
5. **产品 Clock/随机 ID**：不引入。confirmed_at 只由显式确认事实提供（D-098:1509、D-081:1007）；解析器不依赖产品 ID、Clock 或本机路径（D-097:1463）。
6. **provider DTO 零引入**：交易日期/时间与五类事实之外的任何列值（备注、附言、对方账号与户名、币别、钞汇、序号）不进事实、候选、evidence_projection 或任何持久化；路由列（交易类型/摘要）token 只用于类型路由、不落盘（P4-02 §10、D-097:1449；D-102「冻结子类型精确匹配、任何值不落盘」先例）。
7. **隐私**：标题区/注释块零读取（含掩码账号、户名）；余额值不落盘；诊断安全 location 只含 {input_ref, record_ordinal, field_role}。
8. **既有行为零改动**：spine 表/端口/状态迁移、P4-08 表与语义、RG 竖井、golden 冻结契约与 `.external/` 零改动。

## 7. Schema 预期（实施批决定）

基于既有 `import_*` 共享 spine 与 `evidence_projection` 的承载能力，**本批预期零 schema 变更**（理由与边界）：

- 普通收支：`ordinary_flow_source`（contract_version 1）+ `OrdinaryFlow` 确认完全由 P4-02/P4-03/P4-05 既有表与端口承载。
- 转账：`transfer_flow_source`（contract_version 2，完整腿可确认）+ `TransferFlow` 决策 + 决策快照 from/to 列自 P4-04（v21→v22）已承载。
- 余额镜像：余额不持久化（零 schema）；连续性为解析级诊断；期末/时点锚点为 E2E 断言与诊断。**余额观察的持久化（余额锚点证据载体）不在本批**——若未来需要，倾向最小加性表或复用既有观察语义，另行独立批裁决（§8 第 2 项）。
- evidence_projection：银行侧确认与镜像已由 D-112 六 kind 广播 + 既有 READY 门承载（E-11 复用 confirmLink 同事务物化，v26/v27 schema 零改动）。
- 预期若实施中发现既有表无法承载（如余额锚点需要持久化、附言渠道子路由需要新 kind），实施批必须先提出契约修订与评审门，不得静默改表（D-096:1435 未决不由实现默认冻结）。

## 8. 待决问题清单（供主代理/独立评审/用户定夺；每条给推荐）

1. **CCB 银联入账 / CMB 网联付款交易、汇入汇款 的入账方向族语义**（CCB 金额符号 × 摘要组合语义的核心裁决）。样本显示此类 in 方向行是自有钱包提现/充值的银行侧镜像（RL-07 相关）。推荐方案 A：默认路由普通收入候选（银行侧无法证明来源方自有，D-032/D-100 不猜测），RL-07 镜像经既有 P4-08 confirmLink 合并（E-11）；备选方案 B：对入账族做冻结附言/备注渠道 token 精确匹配子路由（D-102 余额宝先例），匹配即提升为转账（`transfer_flow_source`）。请裁决 A 或 B（fixture B3 与 E-11 按裁决结果落 oracle）。
2. **余额镜像的证据载体选型**。推荐：本批零持久化（解析级诊断 + E2E 锚点断言，§5.3）；余额锚点观察的持久化载体（最小加性表 vs 复用既有观察语义 vs 保持纯诊断）留后续独立批。请确认零持久化边界。
3. **status 事实冻结**：两种银行格式无状态列，冻结 `settled`（`bank_statement_cleared_v1`，格式契约常量，§2.3 第 1 条）。请确认该常量冻结不违反 D-097「unknown/absent token 不猜测」纪律（推荐理由：银行入账流水是已清算记账形态，无未决维度）。
4. **CMB 银联快捷支付 in 变体与支付鼓励金**：样本稀少（各 1-4 行）。推荐按方向分别路由普通收支候选；`支付鼓励金` 是否属退款/激励族、`银联快捷支付 in` 是否可能承载退款形态，登记为后续 token 集合修订观察项，本批不另设分支。
5. **类型/摘要 token 集合冻结形态**：推荐封闭集合 + UNKNOWN fail-closed（P4-05 §3.1 先例），token 集合扩张只经显式合同修订（行为证据 + 独立评审）。
6. **CCB 日期-only 的 occurred_at**：推荐冻结午夜填充 `T00:00:00+08:00`（确定性机械填充、非 Clock；若评审认为应保留 date-only temporal kind，需在实施批规格修订五类事实映射）。
7. **CCB 零金额方向**：推荐方向 unresolved → `valid_incomplete`（不可确认，fail-closed）。
8. **CMB 注释块行数变体**：证据仅两文件（均为 6 注释 + 1 空行、表头第 8 行）；若真实导出存在其他注释行数版本，推荐维持「表头必须第 8 行」冻结并登记变体证据，不静默容差（P4-05 §9 纪律）。
9. **parser 技术与依赖**：CMB 自研 CSV 读取 vs 库；CCB POI HSSF vs 自研 BIFF8 读取——实施批按 D-099:1537 六维模板裁决并登记许可证（本批冻结格式契约，不预选技术）。
10. **镜像候选 formalization 阻断深度**：E-12 中银行侧镜像候选的阻断机制（P4-07 duplicate 候选 vs 用户 reject）——推荐复用既有 P4-07 `CONFIRMED_DUPLICATE` 阻断（`SPINE_DUPLICATE_NOT_CONFIRMABLE`），具体接线为实施批决定。

## 附录 A：决策草案 D-116（供主代理在批准后登记，本批不改动 DECISIONS.md）

- **标题**：D-116 BP-01 银行 parser 门承接批（招商银行网银 CSV + 建设银行网银 XLS）契约
- **状态**：提案（proposal；用户批准后由主代理登记为已批准，并同步本规格 Status 转 approved）
- **裁决链（登记时写明）**：完整 kind `transfer_flow_source` + 银行侧方向门变体 = 用户 2026-08-28 裁决（独立评审 B-1 推荐项选项 A 原文，归因准确无误）；余额族非阻断诊断级（`note`）= 用户 2026-08-28 裁决（独立评审 M-1 选项 A）。
- **批准内容摘要**：交付 CMB 网银 CSV 与 CCB 网银 XLS 的解析契约、类型路由矩阵（普通收支 → OrdinaryFlow 语义；明确转账/提现 → P4-04/P4-05 transfer 语义（`transfer_flow_source` 完整腿可确认）+ 银行侧方向门变体；退款 → fail-closed 登记 P4-06；未知 token fail-closed）、余额镜像维度（连续性诊断 + 期末/时点锚点断言，余额不进正式金额、零 schema）、oracle 面（解析级 + spine 端到端代表路径 + RL-07 镜像代表路径复用 P4-08 confirmLink 与 D-112 evidence projection）与匿名 fixture 规格；格式事实以字节级证据冻结（P4-05 §9 纪律）。
- **边界**：不做 PDF/其他银行/信用卡；不引入 matcher 新语义（D-103 组合不变）；不引入产品 Clock/随机 ID；provider DTO 零引入；余额不产生任何新余额/正式账语义；schema 预期零变更（实施批决定）；不改既有 spine/P4-08/RG 竖井/golden/.external 行为。
- **关联决定**：`D-014`、`D-020`、`D-031`、`D-032`、`D-096`、`D-097`、`D-098`、`D-099`、`D-100`、`D-101`、`D-102`、`D-103`、`D-104`、`D-105`、`D-109`、`D-111`、`D-112`

## 边界断言（本批不含）

- 不含 PDF 解析、其他银行格式、信用卡/贷款账单（未提供样本；D-099:1540 PDF 门仍开）。
- 不含 matcher 新语义、mirror/evidence-link/reconciliation 写入面（复用既有 P4-08/D-112 链，零改动）；不含 dedup/duplicate 新合同（复用 P4-07 既有阻断语义）。
- 不含产品 Clock/随机 ID；不含整文件保留生命周期；不含余额观察持久化；不含余额调整/目标余额观察语义（RG-09 场景不接入）。
- 不含任何新余额/正式账语义；余额只作证据与对账维度。
- schema 预期零变更（实施批决定）；若实施发现承载缺口，先契约修订与评审门，不静默改表。
- 本文件为设计契约草案：未经独立评审与用户批准不构成实施授权；实施仍在独立 worktree、单一 bounded writer、独立规格/质量评审、distinct verifier 与主代理最终验收之下（AGENTS.md 变更路由）。真实金额/时间/账号注册值不复制入文；`.external/` 只读未触碰；`docs/specs/` 现有文档零改动（本批唯一写入 = 本新文件）。
