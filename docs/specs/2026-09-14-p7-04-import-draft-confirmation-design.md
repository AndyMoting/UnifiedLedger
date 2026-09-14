# P7-04 导入与草稿确认实施规格（设计门）

状态：approved（设计门 DELTA CLOSURE APPROVE，2026-09-14；首轮独立规格评审 REQUEST-CHANGES P704SPEC-01..11 与附注已全部修复，delta 复验 CLOSURE APPROVE 并附 P704SPEC-12 按其固定裁决落入本版；Q08/Q09/Q10 裁决随本设计门冻结）。

**Revision:** draft-2 冻结版（2026-09-14 设计门 **DELTA CLOSURE APPROVE**）。delta 复验对 draft-2 全部修复逐项确认 **CLOSED、无回归**，评审附不阻断新 finding **P704SPEC-12**（P3）并按其固定裁决落入本版：批量处置「比较快照等价」不可作查询键（`comparison_fingerprint` 输入含 `subject_source_id`，每候选唯一，等值分组只能得单例组）——组键修订为 `kind='EXACT_BUSINESS_TUPLE'` AND subject source 的 `import_source_record.input_ref` = 本次接治会话文件选择句柄 AND 最新重复状态 `DEFERRED`；「比较快照等价」操作化为整组确认页逐项枚举呈现隐私安全比较快照的核验义务（§3.3.1）；读取面经 `input_ref` 参数化组查询/投影暴露不透明句柄（§4.5.2、Appendix A；零 DDL、D06 安全）；D03 族测试钉死组边界（§7）；§8 R-8/Appendix A/D-146 同步；四项实施批义务登记于 D-146（§9 只引用）。本版随设计门冻结（approved）。draft-2 = 首轮独立规格评审 REQUEST-CHANGES（**P1×1 + P2×2 + P3×8** + 评审附注）主代理逐条裁决后单批修正闭环：P704SPEC-01（P1）批量派发死端修复——§6.1 事件族增补 `ResumeImportBatchDispatch`/`AbandonImportBatch`、表 6.2a 增两行、§3.3.2/§7 D03/D04/PRODUCT_REQUIREMENTS 同步；P704SPEC-02（P2）ARCHITECTURE 分层矛盾修复（16 MiB 归文件选择与有界读取端口、10,000 归接治编排）；P704SPEC-03（P2）疑似重复批量处置由开放问题冻结为「UI 层逐项 core `ReviewImportDuplicateCandidate` 顺序循环」裁决（§3.2.1/§3.3.1/§8 R-8/D-146 同步）；P704SPEC-04 RFC 9522 → RFC 9562；P704SPEC-05 back 扩展点行号校正（`P503App.kt:1206/:1226`）；P704SPEC-06 duplicateIds 子行号按实测校正（size 复核 :224-226、逐匹配写 :227-240、NO_FUNDS 恰 1 :241-254、其余 0 :255-256；WechatBillParser `ZipInputStream` import 实测 :21——评审记 :22、以实测为准）；P704SPEC-07 `ImportIntakeIdSource` 端口形态改普通 interface（非 fun interface）+ 单方法默认抛；P704SPEC-08 Appendix A 增 `duplicateMatchCountForIntake`（无 sourceId 自排除参数）、删除「可在既有查询上实现」措辞、§4.5.1/Appendix A 增 candidate→source join 粒度注记；P704SPEC-09 UI 审核决定集冻结三值（`REJECTED` UI 不可达）；P704SPEC-10 §8 增 R-14 接治耗时披露；P704SPEC-11 D-146 关联决定补 D-111；评审附注：D-146 草稿 Q08-1 正文移除 `openStream()` 字面签名、改用闭包形状。draft-1 = 设计门首稿：主代理在用户常设授权「除不 push 外默认采用推荐方案」+ 2026-09-14 用户指示继续完成阶段 7 计划下，按推荐方案裁决 Q08/Q09/Q10（standing authorization），本稿将裁决逐条冻结为 R-Q08-1..4 / R-Q09-1..4 / R-Q10-1..4。**R-Q09-1 的 inputRef 方案经主代理治理裁决（2026-09-14）由「内容指纹派生」翻转为「每次文件选择的随机 UUIDv7 句柄」**——原推荐方案违反 D-098 领域 1.2/2.1 冻结条款，登记为已否决替代案（§3.2.1 裁决理由）。外部证据（Q08）已由只读取证（2026-09-14，官方文档/标准/AOSP 与 POI 源码级）完成并随 §2.2 落库为中立契约，证据等级如实标注。承接登记链：`docs/PHASE7_IMPLEMENTATION_PLAN.local.md` §6（P7-04 导入与草稿确认，:134-169，含 §6.2 执行拆分 A–E 与 §6.3 验收 D01–D06）、§7（Q08 :184 / Q09 :185 / Q10 :186 最迟决定点）、§8.1（P7-04 回归锚点，:205）、D06（:169，隐私注记，计划行号）；该计划源码基线增量核验于 `b490bea`（计划 :6）。本规格工作基线 = 分支 `UL-p7-04` @ `b490bea`（P7-03 merge，D-145），当前 schema = **v29**（`LedgerDatabaseMigrationTest.kt:307` 断言 `assertEquals(29, ...)`）。tracked 行号为工作基线 `b490bea` 实读行号；`.local.md` 以主 checkout 为准、只读。

**Scope:** 冻结「导入与草稿确认」实施批（P7-04）的契约面：有界文件读取端口与平台选择端口（双端）、格式能力矩阵（代码声明 + 诚实呈现）、有界限额分层（16 MiB 读取 / 10,000 候选行，绝不静默截断）、有界最小 XLSX 读取器与 POI 依赖收敛、生产 ID 端口修订（`ImportIntakeIdSource` 需求量参数化）、inputRef 每次文件选择的随机句柄方案（请求级重试幂等 + 同文件重选经疑似重复审核阻断防重）、导入候选/重复/恢复只读查询端口（fail-loud 默认）、批次逐项原子确认与 Unknown 处置（含继续/放弃出口）、候选呈现分类矩阵（缺用户决策 vs 来源事实不完整）、疑似重复先审后勾 UI 门（UI 决定集三值 + 同次文件选择 `EXACT_BUSINESS_TUPLE`/`DEFERRED` 组整组 `CONFIRMED_DUPLICATE` 批量处置，组键见 §3.3.1）、重开恢复；UI 导入面状态机/事件矩阵、D01–D06 验收矩阵与显式非目标。金额全程整数 minor units / 精确十进制，禁浮点；示例全部匿名合成；引用均带 file:line；不粘大段产品代码。**本批 schema 零 DDL（停留 v29，只新增只读命名查询，沿 P7-03 `Ledger.sq:8741-8745` 先例）；rgXX_ 竖井与 golden 零改动；四 parser 冻结契约零期望改动。**本文档只冻结设计；实施、Git 写操作与最终验收属后续独立 worktree 实施批。

## Authority And Boundary

本规格逐条对齐以下权威（tracked 文件行号为 worktree 基线 `b490bea` 的实读行号；`.local.md` 与 `.external/` 以主 checkout 为准、只读；外部研究以 §2.2 证据索引落库，不入 raw 笔记）：

- **阶段计划**：`docs/PHASE7_IMPLEMENTATION_PLAN.local.md:134-169`（P7-04 目标、§6.1 格式与平台证据门、§6.2 执行拆分 A–E、§6.3 验收 D01–D06、生产 ID 接线缺口 :152、inputRef 边界 :154、incomplete 边界 :156、批量语义 :158、重复门 :160）、`:184-186`（Q08/Q09/Q10 最迟决定点）、`:205`（P7-04 回归锚点 = 四来源 parser tests、`ImportSpineLifecycleEndToEndTest`、`P407DuplicateClosedFullStateOracleTest`、`O2PrecisionRescaleDataTest`）、`:169`（D06 脱敏边界）。
- **产品需求**：`docs/PRODUCT_REQUIREMENTS.md:16-19`（账单导入：标准账单文件、来源记录、标准化与重复检测、待确认草稿、逐项或批量审核、只有明确确认才入正式账目、信息不足/冲突/复杂项留队列、重复导入不得重复入账）。
- **架构**：`docs/ARCHITECTURE.md:118-130`（导入逻辑职责：平台文件访问与格式解析分离、有界接口、平台路径不泄漏为产品 schema）、`:134-148`（候选与确认边界：解析/去重/匹配只产候选，只有确认用例创建正式交易）、`docs/ARCHITECTURE.md:63-69`（运行时能力与时间：ID 在 claim 首请求 callback 内惰性物化、Clock 只供应处理/创建/确认/审计时间、不得补写来源时间）。
- **决定（已确认/已批准）**：D-098（共享导入链 raw identity `(inputRef, recordOrdinal)`、内容哈希只作诊断交叉校验、claim-first 原子确认、intake 幂等/碰撞语义）、D-099（P4-03 微信 XLSX 格式契约与 POI 技术门——本批修订其生产读取技术路径，见 §3.1.3 与 D-146 治理登记）、D-100/D-102（转账路由与银行侧方向门）、D-104/D-105（P4-07 重复候选与关闭记录：`CONFIRMED_DUPLICATE` 阻断、合法相似可保留、后到重复只追加 lineage）、D-107/D-108（信用/混合：混合确认时间必填门）、D-112/D-113（P4-08 证据投影与修正）、D-114（mixed null 确认时间类型化门——E13 已闭合）、D-116（BP-01 银行 parser：CMB CSV + CCB XLS 契约）、D-119～D-122（P5-03/三 Tab——本批扩展为四 Tab，见 §6.1）、D-125/D-138/D-139/D-140（编辑流语义不动）、D-143/D-144/D-145（P7-01/02/03 先例：目录、录入、看账读模型与 fail-loud 端口纪律）。
- **不可触碰面**：`.external/` 只读；`rgXX_` 竖井表与 golden fixtures/expected 零改动；四 parser 冻结契约（表头/限额/诊断）零期望改动；导入链写入语义（状态机、分支语义、持久化形状）不变（§4.4 的 ID 分配端口修订为机械性重构，可观测语义零变化）；`docs/ACCOUNTING_RULES.md` 本批不可写；schema 停留 v29。

## 1. 目标与范围

### 1.1 目标

- **双端手动导入闭环**：用户在 Android 与 Desktop 手动选择四来源标准账单文件（微信 XLSX、支付宝 CSV、招行 CSV、建行 XLS）→ 有界读取 → 解析 → 候选列表/详情 → 补用户决策 → 重复审核 → 逐项原子批量确认 → 结果查询（复用 P7-03 读模型与反向血缘）→ 重开恢复（计划 §6.2 P7-04.E）。
- **格式能力诚实呈现**（R-Q08-1..3）：UI 只显示格式能力矩阵声明可用的格式；建行 XLS 在 Android 上标记「待设备运行验证」、不显示为可用；GB18030 以运行时探测为界。
- **有界限额，绝不静默截断**（R-Q08-2）：读取字节上限 16 MiB、单文件接治候选行上限 10,000，超限 = 类型化批量失败（携带实收值）。
- **生产 XLSX 读取去 POI 化**（R-Q08-3）：微信 XLSX 生产读取改用新「有界最小 XLSX 读取器」（`java.util.zip` + `javax.xml.parsers` SAX 流式）；POI XSSF 退出生产读取路径、仅保留 jvmTest 造数。
- **生产 ID 端口修订**（R-Q09-2，E12 缺口闭合）：`ImportIntakeIdSource.next()` 修订为 `next(requiredDuplicateIds: Int)`，由 store 在获胜 claim 事务内按分支与实际匹配数确定需求后调用；UI 严禁预查数量预分配。
- **随机句柄身份与审核阻断**（R-Q09-1，治理翻转后方案）：inputRef = 每次文件选择生成的不透明 UUIDv7 句柄；同请求重试沿 spine 既有 intake 幂等；同文件重选/改名 = 新候选 + 疑似重复人工审核阻断（不产生第二次余额/报表影响经审核阻断达成）；零新增表（无 `29.sqm`）。
- **批次逐项原子 + 可见部分成功**（R-Q09-3）：一次明确授权、逐项原子提交、不承诺整批事务；重开恢复从既有持久化重建待确认清单（ledger-scoped、可跨文件）。
- **疑似重复先审后勾**（R-Q10-1）、**Unknown 暂停核对**（R-Q10-2）、**来源事实不完整不可确认且说明缺什么**（R-Q10-3）、**镜像与确认新交易在呈现上分离**（R-Q10-4）。

### 1.2 范围（冻结）

**范围内：** app-ui commonMain 文件选择端口与有界读取类型、IMPORT 导入面（第四 Tab + 候选/详情/批量确认状态与事件族）；`ledger-application` commonMain 格式能力矩阵声明、导入接治编排端口与导入读取端口（fail-loud 默认）+ jvmMain 实现（四 parser 分发、有界读取、候选行上限、GB18030 探测、有界最小 XLSX 读取器）；`ImportIntakeIdSource` 端口修订与仓内实现方/测试 double 随批更新；`ledger-data` 只新增只读命名查询（零 DDL）与 intake 路径 ID 分配机械性重构（可观测语义零变化）；两端组合根接线（SAF/JFileChooser、生产 ID 源、LedgerClock）；`ledger-application/build.gradle.kts` POI 依赖收敛（`poi-ooxml` jvmMain→jvmTest、`poi` 显式留 jvmMain）；D01–D06 验收向量（D01 的 Android 运行证据为外部待办，见 §3.1.4）。

**范围外（本批明确不做，逐项冻结）：**

- **不做** PDF、其他银行、信用卡账单、微信外层加密 ZIP（不因「支持银行/微信」默认纳入；计划 §6.1）。
- **不做** 自动/监听导入、文件夹监听、后台同步导入。
- **不做** 文件留存与持久 URI 生命周期：不调用 `takePersistableUriPermission`、不保存文件副本、无临时文件生命周期产品（重解析 = 用户重新选文件；R-Q08-1）。
- **不做** 整批事务承诺（逐项原子 + 可见部分成功；R-Q09-3）。
- **不做** 来源事实编辑：UI 不得编辑来源记录事实；未来若允许编辑恢复，须另定义追加历史转换契约（R-Q10-3，计划 :156）。
- **不做** 镜像确认操作化：本批不提供镜像证据追加的操作入口；镜像证据只追加到既有经济事件（P4-08 既有语义），与「确认新交易」在呈现上分离（R-Q10-4）。
- **不引入**新库：fastexcel / poi-android / aalto 等一律不采用（§2.2 替代路线证据已评估并落库）。
- **不改** rgXX_ 竖井、golden fixtures/expected、导入链写入语义（状态机/分支语义/持久化形状）、`docs/ACCOUNTING_RULES.md`。
- **不做** schema 迁移：零 DDL，停留 v29；只新增 `Ledger.sq` 只读命名查询（沿 P7-03 `:8741-8745` 先例）。若实施中发现确需新列/新表/新索引，必须停下显式登记并给出 `29.sqm`→v30 影响评估送评审，不得静默引入（本设计判定不需要）。
- 真实金额/时间/锚点注册值与个人数据不入文；示例全部匿名合成（D06 脱敏边界）。

## 2. 现状与差距（file:line + 外部证据索引）

### 2.1 仓库现状（E11/E12/E13 及增量核验，基线 `b490bea`）

1. **平台依赖结构（E11）**：`ledger-application` 仅 `jvm()` target；POI `org.apache.poi:poi-ooxml:5.5.1` 在 jvmMain implementation（`ledger-application/build.gradle.kts:47-53`，注释明言 POI 不得入 commonMain）；`android-app` 无变体限定依赖 `:ledger-application`（`android-app/build.gradle.kts:45`，消费 JVM 变体）。四 parser 全部在 jvmMain：`import/wechat/WechatBillParser.kt`（XSSF import `:11-12` + `ZipInputStream` import `:21` 容器预检、字节上限门 `:38`）、`import/ccb/CcbBillParser.kt`（HSSF，`:8`）、`import/alipay/AlipayCsvParser.kt`（严格 UTF-8 优先 + GB18030 回退，`:80-84`）、`import/cmb/CmbBillParser.kt`（严格 UTF-8，`:20-30` KDoc）。可见/可编译不证明 Android 运行可用（计划 §6.1）。
2. **spine 用例已备（E12）**：`ImportSpine.kt:590`（`ExecuteImportIntake`）/:662（`ConfirmImportCandidate`）/:688（`RejectImportCandidate`）/:580-588（`ReviewImportDuplicateCandidate` + `ImportDuplicateReviewCommitPort`）；`ImportIntakeIdSource.next(): ImportIntakeIds` 无需求量参数（`:483-485`）。
3. **duplicateIds 分支语义（E12）**：`SqlDelightImportSpineStore.kt:208-257`——`VALID_COMPLETE + SETTLED` 分支先 `selectDuplicateMatches`（调用 `:210-223`，查询 `Ledger.sq:8296-8301`）取事务内实际匹配数，`ids.duplicateIds.size != existing.size` 即 fail-loud 整体回滚（size 复核 `:224-226`），逐匹配写 `EXACT_BUSINESS_TUPLE` 候选（`:227-240`）；`NO_FUNDS` 分支必须恰好 1 组（`:241-254`）；其余分支必须 0 组（`:255-256`）。当前 `allocateIds()` 在插入 source/evidence/candidate 之前调用（`:143`），需求量由调用方**预猜**——测试 double 因此硬编码组数；产品化后预猜即竞态（计划 :152），这是 R-Q09-2 修订的对象。
4. **mixed 缺确认时间早拒绝（E13）**：`SqlDelightImportSpineStore.kt:554-566`（先于 confirm 路径 `allocateIds()` `:582`），回归 `O2PrecisionRescaleDataTest.kt:643`（`mixedNullConfirmationTimeRejectsBeforeIdsOrFactoryAndSameRequestRetries`）。类型化早拒绝先于 ID 分配的不变量沿 D-114 闭合路径存在，本批扩展到全部早拒绝路径（R-Q09-2）。
5. **application 层无导入读取 API（E12 缺口）**：全部 import SELECT（`selectImportSourceByIdentity` `Ledger.sq:8368`、`selectImportCandidateCurrentStatus` `:8409`、`selectImportStatusHistoryByCandidate` `:8438`、`selectImportConfirmationByRequest` `:8444`、`selectDuplicateCurrentStatus` `:8318` 等）均为 store 内部；UI 无法取得候选列表/未确认字段/置信度/重复状态/恢复清单。
6. **import 表全清单（v29 已有，零新增）**：`import_request`（`Ledger.sq:7634`）、`import_source_record`（`:7640`，含 `content_hash` 诊断列）、`import_evidence`（`:7668`）、`import_candidate`（`:7677`，含 `confidence` 列）、`import_candidate_requires_confirmation`（`:7689`，值域现仅 `formal_transaction_creation`）、`import_candidate_status_history`（`:7698`，值域 `pending_confirmation/confirmed/rejected/incomplete`）、`import_candidate_decision_snapshot`（`:7711`）、`import_confirmation`（`:7767`，`operation_class='creation'` + `UNIQUE(ledger_id, candidate_id)`）、`import_receipt`（`:7784`）、`import_duplicate_candidate`（`:7870`，`EXACT_BUSINESS_TUPLE`/`CLOSED_OR_FAILED_NO_FUNDS` + guard 触发器禁改删）、`import_duplicate_status_history`（`:7885`，值域 `DEFERRED/CONFIRMED_DUPLICATE/CONFIRMED_DISTINCT/DISMISSED_LOOKALIKE/REJECTED`）、`import_duplicate_review_request/_snapshot/_receipt`（`:7892-7915`）。**无 import_denial 表**（拒绝走 `import_candidate_status_history.status='rejected'`）。
7. **决策字段通道已备**：`ImportConfirmDecisionFields` 六变体（`ImportSpine.kt:220-262`：`OrdinaryFlow`（分类+资金账户）/`TransferFlow`（转出+转入）/`CreditExpenseFlow`/`CreditExpenseRefundFlow`/`CreditRepaymentFlow`/`MixedPaymentFlow`（分类+资产腿+信用腿+两腿金额可空））；`ImportCandidateConfirmRequest`（`:272-279`）携带 `expectedContentHash`/`explicitConfirmedAt`/`decisionFields`。
8. **重开恢复与 replay 幂等已备**：`ImportSpineLifecycleEndToEndTest.kt:1093`（`acceptedOwnersSurviveReopenAndReplayOriginalReceipts`）；同 raw identity 等价内容 → `NoChange` 零写入、不等价 → 身份碰撞 hard reject（`SqlDelightImportSpineStore.kt:120-141`，D-098 intake 幂等语义）。
9. **P7-03 可复用资产**：四条只读命名查询（`Ledger.sq:8741-8832` 段：`ledgerEntryRowsForLedger` `:8747`、`importCreationConfirmationByTransaction` `:8778`、`manualCreationReceiptByTransaction` `:8785`、`transactionReconciliationLegs` `:8800-8832`）；`QueryTransactionDetail`（含导入创建入口反向血缘）、`QueryMonthlyActivity`、`QueryLedgerEntryRows`；fail-loud 端口默认先例（`LedgerCurrentStateReadPort.kt:109-157`，D-145 G6）。
10. **app-ui 无文件/IO 面**：`P503Tab` 仅 `HOME/ACCOUNTS/ANALYSIS`（`P503AppState.kt:208-212`）；commonMain 零 `java.*` import、无平台 source set（`app-ui/src/` 仅 commonMain/commonTest）——**这使裁决包中 `PickedImportFile.openStream(): InputStream` 的字面签名不可在 commonMain 声明（java.io 不进 commonMain），本规格按同语义适配为有界读取闭包（§4.1.1 形状适配披露）**。
11. **既有解析限额（冻结不动）**：四来源 `MAX_INPUT_BYTES = 10 MiB`（`WechatSourceTokens.kt:58`、`AlipaySourceTokens.kt:177`、`CmbSourceTokens.kt:70`、`CcbSourceTokens.kt:58`）与 `MAX_DATA_ROWS`（微信 10,000 `WechatSourceTokens.kt:59`；支付宝/招行/建行 20,000 `AlipaySourceTokens.kt:178`/`CmbSourceTokens.kt:71`/`CcbSourceTokens.kt:59`），均在 parser 内类型化批量拒绝（如 `WechatBillParser.kt:36-40`/`:99-105`）。
12. **ImportIntakeIdSource 仓内实现面**：`ledger-data` jvmTest 13 个测试文件（`ImportSpineWechatEndToEndTest`、`ImportSpineAlipayEndToEndTest`、`ImportSpineAlipayYuebaoTransferEndToEndTest`、`ImportSpineTransferEndToEndTest`、`ImportSpineBankEndToEndTest`、`ImportSpineLifecycleEndToEndTest`、`ImportSpineMigrationCoexistenceTest`、`O2PrecisionRescaleDataTest`、`P406CreditFullStateOracleTest`、`P407DuplicateClosedFullStateOracleTest`、`P408ProjectionSixKindMaterializationTest`、`P409PhaseClosureFullStateOracleTest`、`P409SiloSpineCoexistenceTest`）+ `ledger-application` jvmTest `ImportSpineUseCaseJvmTest`（`CountingIntakeIdSource`，`:80`）。rgXX golden/RG 回放测试（含 `Rg04ImportLifecycleEndToEndTest`）**不**实现该接口，零波及。

### 2.2 外部证据索引（Q08，只读取证 2026-09-14；等级如实标注）

| # | 证据 | 来源与等级 | 结论 |
| --- | --- | --- | --- |
| X-1 | POI 官方组件页：「The OOXML jars require a stax implementation, but now that Apache POI requires Java 8, that dependency is provided by the JRE」 | POI 官方文档（官方；对 Android 沉默） | stax（`javax.xml.stream`）要求由 JRE 满足——桌面 JDK 成立，Android 无官方背书 |
| X-2 | `poi-ooxml 5.5.1` POM compile 依赖 `xmlbeans 5.3.0`；POI `XMLHelper.java` 与 XMLBeans `Locale.java` 源码直接 `import javax.xml.stream.*` | POM（官方）+ POI/XMLBeans 源码（源码级） | XSSF 运行路径硬依赖 `javax.xml.stream` |
| X-3 | AOSP libcore（main）`javax/xml/` 仅 datatype/namespace/parsers/transform/validation/xpath 六子包 + `XMLConstants`，**无 stream**；官方 desugaring 范围不含 `javax.xml.stream` | AOSP 源码（源码级；无 per-API-level 官方承诺） | 未适配的 XSSF 路径在 Android 运行时将缺类失败——**此为推论，非官方逐字声明**（证据等级：源码级推论） |
| X-4 | HSSF：官方组件表将 xmlbeans/commons-compress/stax 要求限定在 poi-ooxml 侧；poi core 仍含 StAX 引用（XMLHelper）与 `java.awt` 触点 | POI 官方文档 + 源码（官方+源码级） | HSSF 在 Android **无官方可运行背书**，仅社区佐证（centic9/poi-on-android 已跟进 5.5.1、SUPERCILEX/poi-android 已归档——社区级）⇒ 建行 XLS 在 Android 诚实标记「待设备运行验证」（R-Q08-3） |
| X-5 | GB18030：Android 经 ICU 提供扩展字符集（AOSP `CharsetFactory` → `NativeConverter`；ICU 数据含 `gb18030-2022.ucm`） | AOSP 源码（源码级；无 per-API-level 官方承诺） | 运行时 `Charset.isSupported` 探测为界；不支持时类型化失败（R-Q08-3） |
| X-6 | SAF 官方路径：`ACTION_OPEN_DOCUMENT`（API 19+）/ `ActivityResultContracts.OpenDocument`；URI 权限默认至设备重启；`takePersistableUriPermission` 可持久但文档被移动/删除后失效；读取 = `ContentResolver.openInputStream/openFileDescriptor`（官方示例即流式，注明后台线程）；Android 11+ 禁选 `Android/data` 与 `Android/obb`；**官方无文件大小限制声明** | Android 官方文档（官方） | 平台选择端口与一次性读取的依据；不持久 URI、不保存副本（R-Q08-1） |
| X-7 | OOXML 官方定义：ECMA-376 / ISO-IEC 29500，「based on well-known technologies: ZIP and XML」 | ECMA/ISO 标准（官方标准） | 有界最小 XLSX 读取器以 ZIP+XML 标准为据（§3.1.3） |
| X-8 | 替代路线：fastexcel-reader 持续维护（2026-06 发版 0.20.2）但同样依赖 `javax.xml.stream`（同 XSSF 边界）；Android SDK 自带 `org.xmlpull` 与 `java.util.zip`；**`javax.xml.parsers`（SAX/DOM）在 Android libcore 与桌面 JDK 双端均存在**（AOSP `javax/xml` 六子包含 parsers；JDK 原生） | 项目发布记录（官方）+ AOSP/JDK 源码（源码级） | 不采用 fastexcel；新读取器选用 `java.util.zip` + `javax.xml.parsers`（双端存在，X-8） |
| X-9 | D-099 技术门曾登记「XSSF 读取路径不依赖 java.awt，Android minSdk 34 可行」 | 仓库决定（D-099 第 2 条） | 该论断未被 X-2/X-3 证据支持（`javax.xml.stream` 缺失被漏检）——本批按 R-Q08-3 修正生产读取路径并登记 D-099 修订（§3.1.3、D-146 治理登记） |

**Android 运行证据门（外部待办）**：四格式（含 CSV 的 GB18030 路径）的「目标 Android 运行证据」整体登记为外部阻塞待办（ALas 停止 + 设备）；D01 向量保留为该门判据（§3.1.4）。本批设计侧证据 = X-1..X-8 官方平台 API 证据 + JVM 双端语义等价测试（§3.1.3 等价判据）+ 矩阵诚实呈现。

## 3. 裁决（R-Q08 / R-Q09 / R-Q10 逐条冻结）

> 以下裁决由主代理在用户常设授权下按推荐方案作出，本规格逐条冻结；实施批必须逐条落入，不得自行更改语义。标「注册解读」的条款为规格对裁决的操作化（形状适配、分层读法、呈现分类），随本规格一并送评审确认；与本节冲突的既有登记以本节与仓库现实为准并显式披露（§8 R-1/R-2/R-9）。

### 3.1 Q08 文件端口、格式矩阵与有界限额（P7-04.A）

- **R-Q08-1 文件端口**：app-ui commonMain 定义有界读取端口（拟议 `ImportFilePickPort`：选择 → `PickedImportFile`（display name + size 元数据 + 有界读取），display name 仅用于显示诊断，绝不入身份）；Android 实现 = SAF `ActivityResultContracts.OpenDocument`（MIME 按矩阵过滤）+ `ContentResolver.openInputStream`；Desktop 实现 = Swing `JFileChooser`（JDK 自带，无新依赖）+ `FileInputStream`。**不调用 `takePersistableUriPermission`、不保存文件副本、无临时文件生命周期产品**（重解析 = 用户重新选文件）。注册解读（形状适配，评审确认）：`openStream(): InputStream` 的字面签名不可在 app-ui commonMain 声明（§2.1 第 10 条：commonMain 无 java.io、无平台 source set），适配为 `PickedImportFile` 携带惰性有界读取闭包 `readBoundedBytes(): BoundedFileRead`（`Bytes(ByteArray)` / `ExceedsLimit(actualBytes)` / `ReadFailed`），由组合根实现并在派发线程执行；语义等价（惰性、有界、超限携带实收值、读取失败类型化），见 §4.1。
- **R-Q08-2 有界限额**：读取字节上限冻结 **16 MiB**、单文件解析候选行数上限冻结 **10,000**——超限 = 类型化批量失败（携带实收值），**绝不静默截断**；逐行 malformed 诊断沿既有 parser 语义。注册解读（分层读法，评审确认）：两项新限额分别是「平台读取层」与「接治编排层」的界限；parser 层既有冻结限额（10 MiB 字节、微信 10,000/其余 20,000 物理行）原样保留（四 parser 测试零期望改动），三层叠加见表 3.1.2——例如 12 MiB 文件通过读取层后在 parser 层得到既有类型化拒绝；15,000 已接受记录的支付宝文件通过 parser 层（≤20,000）后在接治层得到新类型化批量失败。「解析候选行数」操作化为**将被接治为候选的已接受记录数**（被拒行不计入）。
- **R-Q08-3 格式能力矩阵**：代码声明、双端编译期/运行期判别；UI 只显示矩阵声明可用的格式（表 3.1.1）。微信 XLSX 生产读取改用新「有界最小 XLSX 读取器」（§3.1.3）；POI XSSF 退出生产读取路径、仅保留 jvmTest 造数（fixtures 继续用 XSSF 构造）；`poi-ooxml` 依赖从 jvmMain implementation 收敛到 jvmTest（`poi` 显式留 jvmMain 供建行 HSSF）。建行 XLS：桌面可用；**Android 诚实标记「待设备运行验证」、不显示为可用**——Android 上选择 .xls 得到类型化「该格式在 Android 待运行验证」结果；待 Android 人工门（外部阻塞解除后）按 D01 验证，通过则后续批次翻转矩阵，失败则另提方案，不静默删除或宣布完成。支付宝 CSV 的 GB18030 以运行时 `Charset.isSupported` 探测为界，缺则类型化 Unavailable（探测点见 §4.2.4）。
- **R-Q08-4 Android 运行证据门**：四格式（含 CSV 的 GB18030 路径）的「目标 Android 运行证据」整体登记为外部阻塞待办（ALas 停止 + 设备），D01 向量保留为该门判据；本批以官方平台 API 证据（§2.2）+ JVM 双端语义等价测试 + 矩阵诚实呈现为设计侧证据。

#### 3.1.1 格式能力矩阵（R-Q08-3 冻结实现）

矩阵为 application commonMain 代码声明的不可变清单（拟议 `ImportFormatCapabilities`），每项携带：格式标识、显示名、MIME 过滤集、逐平台可用性（`AVAILABLE` / `PENDING_DEVICE_VERIFICATION`）。组合根注入平台种类（ANDROID/DESKTOP），UI 据矩阵过滤显示；运行期判别（GB18030 探测、容器预检）不改变矩阵声明。

| 格式 | 桌面 | Android | 运行期判别 | 依据 |
| --- | --- | --- | --- | --- |
| 支付宝 CSV | 可用 | 可用 | GB18030 运行时 `Charset.isSupported` 探测；缺 → 类型化 Unavailable（携带格式名） | `AlipayCsvParser.kt:80-84`；X-5 |
| 招行 CSV | 可用 | 可用 | 严格 UTF-8（无额外探测） | `CmbBillParser.kt:20-30` |
| 微信 XLSX | 可用 | 可用 | 新有界最小 XLSX 读取器（`java.util.zip` + `javax.xml.parsers`，双端存在） | §3.1.3；X-7/X-8 |
| 建行 XLS | 可用 | **待设备运行验证（不显示为可用）** | Android 上选择 → 类型化「该格式在 Android 待运行验证」；桌面走 HSSF（`poi` jvmMain） | X-4；R-Q08-3 |

#### 3.1.2 有界限额分层表（R-Q08-2 注册解读）

| 层 | 限额 | 超限行为 | 状态 |
| --- | --- | --- | --- |
| L0 平台读取（新，组合根闭包） | 16 MiB 字节 | 类型化失败，携带实收字节数（size 元数据先行判断；元数据缺失/失真时以 16 MiB+1 读界判定）；零解析 | 本批新增 |
| L1 parser（既有冻结） | 10 MiB 字节；微信 10,000 / 其余 20,000 物理行 | 既有类型化批量拒绝（`unsafeOrOverLimit` 族），逐行 malformed 沿既有诊断语义 | 零改动（§2.1 第 11 条） |
| L2 接治编排（新） | 单文件 ≤10,000 已接受记录 | 类型化批量失败，携带实收已接受记录数；零接治写入、零截断 | 本批新增 |

#### 3.1.3 有界最小 XLSX 读取器与 D-099 修订（R-Q08-3 注册解读）

- **新读取器（拟议 `BoundedXlsxReader`，`ledger-application` jvmMain）**：`java.util.zip`（ZIP 容器流式解包）+ `javax.xml.parsers`（SAX 流式解析）实现，无新依赖（X-8：双端 libcore/JDK 均有）。覆盖微信账单 XLSX 读取所需的最小语义面：`xl/sharedStrings.xml` 共享字符串解析、行/列引用（`r` 属性的 A1 制式）、cell type `s`（共享串）/`str`（公式串缓存）/`n`（数值，精确十进制文本解码，禁浮点中间量）/`b`（布尔）/`e`（错误）、inline string（`is/t`）、缺格（该 cell 视为缺席，不虚构空值）。容器/结构异常沿 D-097 诊断 taxonomy 类型化（复用既有微信 parser 诊断码语义）。
- **等价判据（冻结）**：现有 `WechatBillParserJvmTest` **全部 oracle 期望零修改保持绿**（换读取器后原样通过 = 等价证明）；另增读取器级单测（sharedStrings / inline string / 数值精确文本 / 缺格 / 上限与容器异常），全部匿名合成 fixtures。
- **POI 依赖收敛（`ledger-application/build.gradle.kts`）**：jvmMain `implementation("org.apache.poi:poi:5.5.1")`（显式声明，供 `CcbBillParser` HSSF，版本不变）；jvmMain 移除 `poi-ooxml`；jvmTest `implementation("org.apache.poi:poi-ooxml:5.5.1")`（XSSF 仅用于 `WechatBillParserJvmTest` 造数，全仓唯一 XSSF 使用点，§2.1 第 1 条）。`WechatBillParser` 生产路径内部换用新读取器（允许改动面：wechat 读取器内部实现，期望不变）；支付宝/招行/建行 parser 零改动。
- **D-099 修订登记（治理）**：D-099 第 2 条「Parser 技术 = Apache POI（XSSF）」的**生产读取**路径由本批修订为有界最小 XLSX 读取器；XSSF 收缩为 jvmTest 造数工具。D-099 第 1 条格式契约（表头 0-based row 17、11 列 token、五类事实映射、fail-closed 拒绝集）**不变**。该修订随 D-146 显式登记（主代理裁决项），对齐 X-9 漏检结论。

#### 3.1.4 Android 运行证据门（R-Q08-4）

「目标 Android 运行证据」= 在目标 Android 设备/模拟器上以固定 APK 完成 D01 全向量（四格式合成样本解析、取消、权限撤回、GB18030、损坏 XLS/XLSX、超限、partial 行错误）。该门整体为**外部阻塞待办**（ALas 停止 + 设备），不阻塞本批设计与实施；矩阵在建行 XLS（Android 列）与 GB18030（运行期探测）上的诚实呈现即本批对该门的承接方式。门通过后：建行 XLS Android 列翻转矩阵为可用（后续批次）；任何格式失败则另提方案，不静默删除原定格式或宣布完成（计划 :184）。

### 3.2 Q09 身份、生产 ID 与批次（P7-04.B/D）

- **R-Q09-1 身份（主代理治理翻转裁决，2026-09-14，取代原推荐方案）**：inputRef = **每次文件选择生成全新不透明 UUIDv7 句柄**（沿仓内 UUIDv7 id source 既有先例；不含 URI、文件名、内容或任何个人标识）。同一请求（同 inputRef）的重试沿 spine 既有 intake 幂等（内容等价 → NoChange 零新写入；不等价 → 身份碰撞 hard reject）——请求级重试安全不变。重选同一文件/改名文件 = 新 inputRef → 新候选；spine 既有 duplicateIds/`EXACT_BUSINESS_TUPLE` 匹配（`SqlDelightImportSpineStore.kt:208-257`）自动将其呈现为疑似重复 → P7-04.C 审核界面人工处置（`CONFIRMED_DUPLICATE` 阻断正式化，沿 P4-07）；**不产生第二次余额/报表影响经由此审核阻断达成**。`ImportContentFingerprint` 仍按既有角色在 intake 计算（内容诊断哈希，只作交叉校验，不构成身份、不参与去重）。**零新增表（无 `29.sqm`）**：恢复完全依赖 v29 既有 request/candidate/snapshot/receipt/confirmation 持久化 + 新增只读查询。
- **R-Q09-2 生产 ID 端口修订（E12 缺口闭合）**：`ImportIntakeIdSource.next()` 修订为 `next(requiredDuplicateIds: Int)`，由 store 在获胜 claim 事务内、按分支与实际匹配数（`:208-257` 语义）确定需求后调用；UI 严禁预查数量预分配（竞态禁止）。所有类型化早拒绝先于 ID 分配（沿 E13 不变量扩展到全部早拒绝路径）；拒绝/重试不消耗不应分配的正式 ID。接口默认 fail-loud（沿 P7-03 G6 纪律）。仓内实现方与测试 double 随批更新；**rgXX_/golden 竖井零改动**。
- **R-Q09-3 批次概念**：批次 = UI 会话内对勾选快照的一次明确授权；**逐项原子提交、可见部分成功**（不承诺整批事务）；无批次表——重开恢复 = 从持久化的逐候选 status/decision_snapshot/receipt 重建待确认清单（ledger-scoped，可跨文件）。
- **R-Q09-4 确认时间**：确认时间 = 用户确认动作时间（注入 `LedgerClock`），来源记录时间不由处理 Clock 填补（沿 D-043 与既有 spine 语义）。

#### 3.2.1 inputRef 方案：每次文件选择的随机句柄（R-Q09-1；内容派生方案已否决）

**裁决理由（主代理治理翻转，2026-09-14）**：原推荐方案（inputRef 由内容指纹确定性派生，使「同内容 = 同身份」成立、同文件重选零审核幂等）经治理评审翻转否决——D-098 领域 1 第 2 条明文「规范化内容哈希……只作诊断、不构成身份、不参与去重」（`docs/DECISIONS.md:1486`），领域 2 第 1 条明文「任何以该哈希参与 dedup、折叠或身份的行为继续禁止（dedup 留 P4-07）」（`docs/DECISIONS.md:1493`）；计划 §6.2 亦冻结「内容诊断哈希也不能被悄然升级为身份或自动去重键」（`docs/PHASE7_IMPLEMENTATION_PLAN.local.md:154`）。内容派生 inputRef 使身份成为内容的函数，直接违反冻结合同；随机句柄方案不触碰上述边界。

- **inputRef 方案（冻结）**：每次文件选择（用户每次触发平台选择器并成功取回文件）生成**一个**全新不透明 UUIDv7 句柄（沿仓内 UUIDv7 id source 既有先例，P5-02 纪律；不含 URI、文件名、内容或任何个人标识）；本次选择产生的全部已接受记录共用该句柄作为 `inputRef`，以 `recordOrdinal` 区分——raw identity = `(inputRef, ordinal)` 组合（D-098 领域 1.1 语义不变）。`ImportContentFingerprint` 既有 `canonicalJson`/`digest`（`ImportContentFingerprint.kt:25-70`）照旧在 intake 计算，`content_hash` 列照旧存 `digest`——诊断交叉校验角色不变。
- **请求级重试幂等（沿 spine 既有路径，零新语义）**：同一次文件选择的接治会话内，记录 intake 失败重试以同 requestId + 同 `(inputRef, ordinal)` 重入 → `selectImportSourceByIdentity` 命中 + 内容等价 → `NoChange` 零新写入；不等价 → 身份碰撞 hard reject（`SqlDelightImportSpineStore.kt:120-141`；D-098 intake 幂等语义；`acceptedOwnersSurviveReopenAndReplayOriginalReceipts` 锚点覆盖重开与 replay）。
- **同文件重选/改名（新句柄 → 审核阻断）**：重选同一文件（含改名/换路径/重启后再选）= 新 inputRef → 新 source/candidate；与既有记录业务元组相同者由 spine 既有 duplicateIds/`EXACT_BUSINESS_TUPLE` 匹配（`SqlDelightImportSpineStore.kt:208-257`）自动生成疑似重复候选 → P7-04.C 审核界面人工处置：`CONFIRMED_DUPLICATE` 阻断正式化（core 既有门，D-104/D-105），`CONFIRMED_DISTINCT`/`DISMISSED_LOOKALIKE` 保留为多笔独立交易。**「不产生第二次余额/报表影响」经由此审核阻断达成**（绝不自动去重、绝不自动入账）；同请求 replay = 请求幂等 `NoChange`。
- **随机方案下的后果边界（取代前稿登记）**：前稿（内容派生方案）登记的「跨文件同内容同序 NoChange 去重效应」与「同 ordinal 不同内容身份碰撞」两项后果在随机句柄方案下**不存在**——同内容跨选择不再命中同 raw identity（每次选择新句柄），跨选择无同 `(inputRef, ordinal)` 路径。身份碰撞 hard reject 仅在同一次文件选择的接治路径内可达（重试不等价或编排构造错误时 spine 兜底拒绝，D05 保留该向量）。
- **已否决替代案（登记，不复活）**：`inputRef = "p704-import:" + sha256hex(canonicalJson(recordKind, facts, paymentProfile))` 内容派生方案，收益为同文件重选零审核负担幂等；否决原因 = 违反 D-098 领域 1.2/2.1 冻结条款（上引 `:1486`/`:1493`）与计划 §6.2 `:154`。
- **疑似重复批量处置（已由主代理裁决冻结，原开放问题闭合）**：审核界面提供批量处置动作 = UI 层对逐项 core `ReviewImportDuplicateCandidate` 的顺序循环，每项独立 requestId/reviewId（claim-gated、可 replay、逐项 receipt，`ImportSpine.kt:580-588` 既有形状）；core 语义零改动、无整组原子性承诺、可见部分成功；批量入口仅限「同一次文件选择句柄 + `EXACT_BUSINESS_TUPLE` + 最新重复状态 `DEFERRED`」组内整组标记 `CONFIRMED_DUPLICATE`（组键与呈现义务全文见 §3.3.1，P704SPEC-12；§8 R-8 缓解）。

#### 3.2.2 生产 ID 端口修订（R-Q09-2 冻结实现）

- **端口签名（拟议）**：`ImportIntakeIdSource` 修订为**普通 interface（非 fun interface）**，单方法 `fun next(requiredDuplicateIds: Int): ImportIntakeIds`，接口默认抛 `UnsupportedOperationException`（沿 `LedgerCurrentStateReadPort.kt:109-157` G6 先例；`fun interface` 无法为唯一抽象方法提供默认实现体，fail-loud 默认因此要求普通 interface——无中性默认，杜绝「空组数假装成功」）（`ImportSpine.kt:483-485` 修订）。
- **调用契约**：store 在获胜 claim 事务内、于全部类型化早拒绝（结构校验、replay/碰撞判定、E13 mixed 门等）之后调用，恰好一次；`requiredDuplicateIds` 按分支确定——`VALID_COMPLETE + SETTLED` = 事务内 `duplicateMatchCountForIntake` 计数（**新增只读命名查询**，Appendix A：SELECT-only、按 `:ledger_id` 限定、谓词 = `selectDuplicateMatches`（`Ledger.sq:8296-8301`）的匹配条件、**无 sourceId 自排除参数**——计数在插入新 source 前执行，被计数行全部为既有 source；事务隔离内无竞态；零 DDL）；`NO_FUNDS` = 恰好 1；其余分支 = 0。既有 `size != existing.size` fail-loud 不变量（`:224-226`）保留为防御性复核。写入行集与分支语义逐字节等价（§5.4 等价性约束）。
- **生产实现（P7-04.B 接线）**：组合根注入 UUIDv7（RFC 9562，沿 P5-02 产品 ID 纪律）实现；`ImportIdSource`（confirm，`ImportSpine.kt:501-503`）与 `ImportStatusIdSource`（`:506-508`）签名不变、同批接线；重复审核 id（`ImportDuplicateReviewRequest.reviewId/historyId`，`:182-193`）沿既有请求形状（调用方生成、claim-gated 持久化，replay/冲突路径不持久即不消耗）。
- **竞态禁止**：任何 UI/编排层不得先查匹配数再预分配（预猜即竞态）；需求量判定唯一归属 store 事务内。
- **早拒绝零 ID 不变量**：intake 与 confirm 两路径的全部类型化早拒绝先于 `next()`/`allocateIds()` 调用；拒绝与重试路径 id 源调用次数为 0（`CountingIntakeIdSource` 模式断言扩展，D05）。
- **随批更新面**：§2.1 第 12 条所列 14 个测试文件的 intake id-source double 签名随端口修订；全部 oracle 期望不变。rgXX_/golden 零改动（golden 回放不经该接口）。

#### 3.2.3 批次与恢复（R-Q09-3 冻结实现）

- **批次 = 一次明确授权**：用户对勾选快照执行一次确认动作（`AuthorizeImportBatch`）；授权时刻以注入 `LedgerClock` 取样一次（R-Q09-4），该时间戳经 `explicitConfirmedAt` 通道随本授权下全部逐项派发复用（同一用户动作的语义）；mixed 候选必填（E13 门），其余类型同值传入（显式 provenance，`insertImportConfirmation.confirmed_at`，`SqlDelightImportSpineStore.kt:757-766`）。
- **逐项原子**：每个勾选项 = 一次独立 spine confirm 请求（新 requestId UUIDv7/项，沿 P7-02「每个新意图分配新 requestId」）；claim-first 原子提交；不承诺整批事务，逐项结果（成功/领域拒绝/未知）分别呈现（§6）。
- **无批次表（零 DDL）**：会话内授权快照为 UI 态；持久层只有既有逐候选行。**重开恢复 = 清单读**：因每项为单事务，进程重启后不存在歧义项——已提交项读回 `confirmed`、未提交项读回 `pending_confirmation`，恢复清单即权威（§3.3.2 Unknown 仅存在于会话内）。
- **恢复清单（ledger-scoped、可跨文件）**：`importReviewRowsForLedger`（Appendix A）按 ledger 重建全部未决候选（pending/incomplete/重复未审/已确认/已拒绝分组呈现），不区分来源文件（文件名不落库，D06）。

### 3.3 Q10 重复先审、Unknown 与 incomplete 边界（P7-04.C/D）

- **R-Q10-1 疑似重复先审后勾**：有重复匹配（`EXACT_BUSINESS_TUPLE`）或 `DEFERRED` 重复状态的候选，必须先完成明确审核（`CONFIRMED_DUPLICATE` 阻断 / 合法相似保留）才可勾选确认——这是 **UI 层新增门**（core 现状只有 `CONFIRMED_DUPLICATE` 阻断 formalization，规格如实声明该门为本批新增呈现层契约，不改 core 状态机）。
- **R-Q10-2 Unknown 处置**：逐项提交后未知结果 → 暂停后续派发，按完整 snapshot/receipt 回读核对（沿 P7-02 B04 语义），不自动重试、不换 ID。
- **R-Q10-3 incomplete 补事实边界**：区分「缺用户决策」（分类/账户/混合确认时间等——经 requires_confirmation 通道补决策后可确认）与「来源事实不完整」（转账腿缺失、混合腿不全、来源未解、关闭/失败无资金——**不可确认**，呈现须说明缺什么来源事实）；UI 不得编辑来源事实；未来若允许编辑恢复，须另定义追加历史转换契约，不在本批。
- **R-Q10-4 镜像分离**：镜像证据只追加到既有经济事件（P4-08 既有语义），与「确认新交易」在呈现上明确分离，绝不混为同一操作；本批不提供镜像证据追加的操作入口。

#### 3.3.1 候选呈现分类矩阵（R-Q10-1/R-Q10-3 注册解读，评审确认）

UI 对每个候选按持久化字段（candidate_kind、completeness、funding_state、候选最新状态、重复候选存在性与最新重复状态）分类呈现；分类为纯投影、零写入：

| 呈现分类 | 判定（持久化字段） | 可勾选 | 呈现要点 |
| --- | --- | --- | --- |
| 待确认——缺用户决策 | 候选状态 `pending_confirmation` 且无未审重复候选 | 是（须先补决策字段） | 按 candidate_kind 的决策字段表单（§4.5.3）；mixed 需两腿金额与确认时间 |
| 疑似重复——待审核 | 存在 `EXACT_BUSINESS_TUPLE` 重复候选且最新重复状态 `DEFERRED`（或无处置） | **否（R-Q10-1 门）** | 显示隐私安全比较快照（P4-07 冻结投影）；审核动作 → core `ReviewImportDuplicateCandidate` |
| 重复已确认——阻断 | 最新重复状态 `CONFIRMED_DUPLICATE` | 否（core 门，既有） | 呈现阻断原因；该候选不产生正式效果（D-104/D-105） |
| 可保留的相似记录 | 最新重复状态 `CONFIRMED_DISTINCT`/`DISMISSED_LOOKALIKE` 且候选 `pending_confirmation` | 是 | 合法相似可明确保留（不同 raw identity 各自独立，D03） |
| 来源事实不完整——不可确认 | 候选状态 `incomplete`（含 `transfer_flow_missing_leg`、来源未解、`NO_FUNDS`/关闭失败） | **否（R-Q10-3）** | 说明缺什么来源事实（按 candidate_kind + completeness/funding_state 推导文案）；无编辑入口 |
| 已确认 / 已拒绝 | 候选状态 `confirmed` / `rejected` | 否 | 历史结果呈现；`confirmed` 项可经 P7-03 详情查看正式交易（反向血缘） |

`CLOSED_OR_FAILED_NO_FUNDS` 重复候选（kind 列）随其 subject 候选归入「来源事实不完整」呈现（零经济效果，D-105）。

**UI 审核决定集（冻结）**：审核动作值域 = `CONFIRMED_DUPLICATE` / `CONFIRMED_DISTINCT` / `DISMISSED_LOOKALIKE` 三值；`REJECTED` 保留于 core 值域（`import_duplicate_status_history` 状态 CHECK，`Ledger.sq:7887`）但 **UI 不可达**（§6.1 `SubmitImportDuplicateReview` 值域约束锁定）。

**疑似重复批量处置（已冻结裁决；原 §3.2.1 开放问题闭合；P704SPEC-12 组键修订落入）**：批量处置动作 = UI 层对逐项 core `ReviewImportDuplicateCandidate` 的顺序循环，每项独立 requestId/reviewId（claim-gated、可 replay、逐项 receipt，`ImportSpine.kt:580-588` 既有形状）；core 语义零改动、无整组原子性承诺、可见部分成功（逐项失败类型化呈现，已成功项经等价 replay 幂等不重复处置）。**组键（P704SPEC-12 固定裁决）** = `import_duplicate_candidate.kind = 'EXACT_BUSINESS_TUPLE'` AND subject source 的 `import_source_record.input_ref` = 本次接治会话的文件选择句柄 AND 最新重复状态 = `DEFERRED`；批量入口仅限该组内整组标记 `CONFIRMED_DUPLICATE`，不提供跨选择/跨 kind/混合决定/非 `DEFERRED` 的批量入口。**「比较快照等价」不是查询键**（`comparison_fingerprint` 输入含 `subject_source_id`，每候选唯一，等值分组只能得单例组）——操作化为**呈现义务**：整组确认页逐项枚举并展示隐私安全比较快照（P4-07 冻结投影）供人工核验，确认动作即对逐项处置的授权。**读取面**：清单投影暴露 subject source 的不透明 `input_ref` 句柄（随机值、不含个人标识，D06 安全；零 DDL），批量分组查询经该句柄参数化（Appendix A 注记）。**组边界由 D03 族测试钉死**（同次选择 + `EXACT_BUSINESS_TUPLE` + `DEFERRED` 入组；异次选择/异 kind/非 `DEFERRED` 出组）。

#### 3.3.2 Unknown 处置（R-Q10-2 冻结实现）

- 会话内派发返回未知（提交交接后异常、结果不可判）→ 该项呈现「未知」、**暂停后续派发**（授权快照内未派发项保持待派发）；不自动重试、不换 requestId。
- 用户以「核对」动作触发**等价 replay**：同 requestId + 等价 snapshot 重入 confirm 用例 → spine 既有 `resolveConfirm` 返回原 receipt（判成功）/ 冲突（判冲突）/ 仍未知；核对结果落回该项呈现。
- 核对完成后用户显式选择继续或放弃：继续 = `ResumeImportBatchDispatch`（同授权快照内继续派发未派发项，复用同次 `LedgerClock` 取样与既有 requestId，已核对项不重复派发）；放弃 = `AbandonImportBatch`（**授权快照解散，剩余项即普通待确认清单项**——不引入隐藏中间 UI 态，`PRODUCT_REQUIREMENTS` 措辞为权威读法（D-146 实施批义务③）；转 `OverviewEmpty`（IMPORT）保留结果摘要；其持久状态保持 `pending_confirmation`，可再授权——新授权 = 新意图、新 requestId 与新时钟取样）。两事件在任何状态不抛 ISE（表 6.2a）。
- 进程重启后无歧义项（§3.2.3）：恢复清单读即权威；重启前会话内的「未知」项由持久化状态直接判定（已提交 → `confirmed`；未提交 → `pending_confirmation`）。

## 4. 领域与应用变更

### 4.1 文件选择与有界读取端口（app-ui commonMain + 组合根）

1. **端口（拟议）**：`ImportFilePickPort.launch(request: ImportFilePickRequest)`（平台发起选择；request 携带格式标识 + MIME 过滤集，来自矩阵）；结果由组合根以事件回送共享协调器（`ImportFilePicked(PickedImportFile)` / `ImportFilePickCancelled` / 平台失败事件）。`PickedImportFile`（commonMain 值类型）= `displayName: String`（仅显示诊断，绝不入身份/持久化，D06）+ `sizeBytes: Long?`（SAF/文件系统元数据，可空）+ `readBoundedBytes: () -> BoundedFileRead`（惰性有界读取闭包，组合根实现，派发线程执行）。
2. **有界读取语义（R-Q08-2 L0）**：`BoundedFileRead`（sealed，commonMain）= `Bytes(bytes: ByteArray)` / `ExceedsLimit(actualBytes: Long)` / `ReadFailed(diagnostic)`。读取实现：`sizeBytes > 16 MiB` 时直接 `ExceedsLimit(sizeBytes)`（不读）；否则流式读取至多 16 MiB+1 字节，触及界即中止并 `ExceedsLimit(实收计数)`；IO 异常 → `ReadFailed`（类型化，不泄底层异常文本，D06）。读取与后续解析/接治一律在非 UI 线程（沿宿主协调器既有异步模式）。
3. **平台实现（组合根）**：Android = `ActivityResultContracts.OpenDocument`（MIME 按矩阵）+ `ContentResolver.openInputStream`（X-6；一次性读取，不 `takePersistableUriPermission`、不保存副本）；Desktop = Swing `JFileChooser`（JDK 自带）+ `FileInputStream`。app-ui commonMain 不接触平台 API（模块边界表：app-ui 不负责平台窗口/Activity API）。
4. **取消语义**：用户取消选择 = `ImportFilePickCancelled`（非失败，零诊断噪声）；权限撤回/读取失败 = 类型化失败呈现。

### 4.2 格式能力矩阵与接治编排（ledger-application）

1. **矩阵声明（commonMain，拟议 `ImportFormatCapabilities`）**：表 3.1.1 的代码声明；组合根注入平台种类，UI 与编排据矩阵判别。
2. **接治编排端口（commonMain 接口 + jvmMain 实现）**：parsers 在 jvmMain（§2.1 第 1 条），commonMain 无法直接引用——拟议 `ImportFileIntakePort`（commonMain）：`fun intake(input: ImportFileIntakeInput): ImportFileIntakeOutcome`；`ImportFileIntakeInput` = 格式标识 + 平台种类 + 有界字节（`ByteArray`）+ 注入依赖（id 源、fingerprint、spine 用例引用由实现持有）；`ImportFileIntakeOutcome`（sealed）= `Accepted(逐记录结果摘要, 新增候选 id 集)` / `NoChangeAll(会话级等价重派发摘要)` / `Rejected(类型化批量失败，携带实收值)`。jvmMain 实现（拟议 `JvmImportFileIntake`）按 §4.2.3 流程编排。
3. **编排流程（冻结）**：(a) 矩阵判定——格式对当前平台不可用（建行 XLS @ Android）→ 类型化「该格式在 Android 待运行验证」；(b) GB18030 探测（仅支付宝 CSV）——`Charset.isSupported("GB18030")` 为假 → 类型化 Unavailable（携带格式名；探测在 jvmMain 编排层，parser 零改动）；(c) 分发至对应 parser（ByteArray 输入，沿既有签名）；(d) 接治上限——已接受记录数 > 10,000 → 类型化批量失败（携带实收数），零接治写入；(e) 生成本次文件选择的不透明 UUIDv7 句柄（§3.2.1），逐已接受记录构造 `ImportIntakeRequest`（`ImportSpine.kt:145-154`）：`inputRef` = 该句柄（全部记录共用）、`recordOrdinal` = 记录序、requestId = 新 UUIDv7/记录（接治会话内重试复用同 requestId，沿 §3.2.1 请求级幂等）；(f) 逐记录 `ExecuteImportIntake`（注入修订后 id 源）；(g) 聚合为 `ImportFileIntakeOutcome`（会话级重派发下全部 NoChange → `NoChangeAll`；任一 Accepted → `Accepted`；结构上被 parser 整批拒绝 → `Rejected`）。
4. **诊断聚合**：逐行 malformed 沿既有 parser 诊断语义原样透传（计数 + 脱敏位置，不泄原始行/文件名之外内容）；接治层新增诊断仅两类（候选行超限、格式平台不可用/字符集不可用），按 D-097 taxonomy 风格追加注册。

### 4.3 有界最小 XLSX 读取器（jvmMain，R-Q08-3）

§3.1.3 冻结内容的落位：`WechatBillParser` 内部读取实现由 XSSF 换为 `BoundedXlsxReader`（允许改动面：wechat 读取器内部实现；表头 0-based row 17、11 列 token、五类事实映射、拒绝集、诊断码语义全部不变——`WechatBillParserJvmTest` oracle 零修改即等价证明）。读取器级新增单测覆盖：sharedStrings 解析、inline string、数值精确十进制文本（禁浮点中间量）、缺格、行/列引用、上限与容器异常。依赖收敛见 §3.1.3（`build.gradle.kts`：jvmMain `poi`、jvmTest `poi-ooxml`）。

### 4.4 生产 ID 端口修订（commonMain + data，R-Q09-2）

§3.2.2 冻结内容的落位：`ImportSpine.kt:483-485` 签名修订（普通 interface + 单方法默认抛，§3.2.2）；`SqlDelightImportSpineStore` intake 路径重构为「早拒绝 → 分支判定 → 事务内 `duplicateMatchCountForIntake` 计数 → `next(requiredDuplicateIds)` → 插入 → 防御性 size 复核」；`ImportSpineUseCaseJvmTest` 与 §2.1 第 12 条 14 个测试文件 double 随批更新签名、期望不变。`ExecuteImportIntake` 用例语义不变（T-26 族断言扩展 requiredDuplicateIds 传递）。

### 4.5 导入读取端口（commonMain，fail-loud 默认）

1. **端口（拟议 `ImportReviewReadPort`，沿 `LedgerCurrentStateReadPort.kt:109-157` G6 先例）**：`loadImportReviewRows(ledgerId)`（清单）、`loadImportCandidateDetail(ledgerId, candidateId)`（详情）、`loadImportDuplicateReviews(ledgerId, candidateId)`（重复比较）——接口默认一律抛 `UnsupportedOperationException`（未实现端口绝不以空表给出「无候选/无重复」等看似确定的错误结论）；实现方映射为类型化 `Unavailable`。**粒度注记**：端口参数以 candidateId 表达，而重复候选的持久化粒度为 subject source（`import_duplicate_candidate.subject_source_id`）；adapter 以 `import_candidate.source_id` 做 candidate→source join 解析（每候选恰一 source，`UNIQUE (ledger_id, source_id)`，`Ledger.sq:7686`），Appendix A 查询同此语义。
2. **清单投影（拟议 `ImportReviewRow`）**：candidateId、candidate_kind、来源事实摘要（金额 minor units/币种/精度/方向 token/发生时间/状态 token）、completeness、funding_state、content_hash（= expectedContentHash 来源）、候选最新状态、requires_confirmation、confidence、重复状态（最新重复处置或无）、payment profile 摘要（v3 记录）、subject source 的不透明 `input_ref` 句柄（批量处置组键分量，§3.3.1/P704SPEC-12；随机值、不含个人标识，D06 安全）。全部只读。
3. **决策字段推导（纯函数）**：按 candidate_kind 推导 UI 决策表单面（`OrdinaryFlow` → 分类+资金账户；`TransferFlow` → 转出+转入；`CreditExpenseFlow` → 分类+信用账户；`CreditExpenseRefundFlow` → 分类+信用账户+原交易；`CreditRepaymentFlow` → 资产+信用账户；`MixedPaymentFlow` → 分类+资产腿+信用腿+两腿金额+确认时间必填）——映射沿 `ImportConfirmDecisionFields`（`ImportSpine.kt:220-262`）既有形状，零新语义；目录选项经既有权威目录投影（D-143，与录入流同源）。
4. **详情投影**：清单行字段 + 候选状态历史计数 + 重复比较（隐私安全比较快照、可能存在的既有来源事实投影、最新审核处置与理由 token）。

### 4.6 失败码族及可达性（沿 P7-03 §4.3 式说明）

| code（拟议族，映射既有类型） | 触发 | 结果 |
| --- | --- | --- |
| `ImportFormatUnavailable`（类型化） | 格式对当前平台声明不可用（建行 XLS @ Android）；GB18030 探测失败（支付宝 CSV） | 零读取/零解析；UI 呈现格式名与原因（「待设备运行验证」/字符集不可用） |
| `ImportReadExceedsLimit(actualBytes)`（类型化） | L0 读取 > 16 MiB | 携带实收字节数；零解析、零接治 |
| `ImportBatchExceedsLimit(actualCount)`（类型化） | L2 接治 > 10,000 已接受记录 | 携带实收记录数；零接治写入、零截断 |
| parser 既有诊断族（原样） | L1 字节/行数超限、容器/表头/逐行 malformed | 沿 `unsafeOrOverLimit`/`unsupportedInput`/逐行诊断语义透传 |
| spine 既有结果族（原样） | intake/confirm/reject/review 的 Accepted/NoChange/Rejected（含 `SPINE_DUPLICATE_NOT_CONFIRMABLE`、身份碰撞、stale 指纹、`candidate_not_pending`） | 沿 D-098/D-104/D-105 冻结语义；UI 类型化呈现 |
| `Unknown`（会话内） | 逐项派发后结果不可判 | R-Q10-2：暂停 + 等价 replay 核对，不自动重试、不换 ID |
| `Unavailable` / `InvalidState`（沿 P7-03 族） | 导入读取端口异常/装配失败；投影一致性失败 | 类型化读失败；不以空清单/零候选掩盖（G6 + R-Q06-4 同族纪律） |

可达性说明：`ImportReadExceedsLimit`/`ImportBatchExceedsLimit` 在产品路径可达（用户可选任意文件）；`ImportFormatUnavailable` 在 Android 选建行 XLS/极端字符集环境可达；spine 拒绝族沿既有测试覆盖（D05）；读失败不得清空已渲染清单（保留上一成功载荷 + 显式失败条，沿 P7-03 F1 纪律）。

### 4.7 领域层（`ledger-domain`）

**零改动。** 本批不新增领域规则；金额/时间/交易类型沿既有值对象；接治编排与呈现分类为 application 纯函数（消费领域与 spine 类型，不反向上推）。

## 5. 持久化（`ledger-data`）

1. **只新增命名查询，零 DDL**：不改任何表列、不建索引、不新增迁移——schema 停留 **v29**（`LedgerDatabaseMigrationTest.kt:307` 断言保持）；新查询清单见 Appendix A，沿 P7-03 段风格（`Ledger.sq:8741-8745` 先例）落位为独立注释段；全部按 `:ledger_id` 限定（沿 P7-03 §5.2 纪律，杜绝跨账本泄漏）。
2. **只读不变量**：本批在 `ledger-data` 零 INSERT/UPDATE/DELETE 新增语句族（Appendix A 全部为 SELECT）；对账表、metadata 表、rgXX_ 表零读写改动。
3. **intake 路径 ID 分配重构（§4.4）的等价性约束**：重构仅重排「需求量判定 → id 分配」的次序与回调签名；**写入行集、分支语义（`:208-257`）、状态机、诊断码逐字节等价**——等价性证据 = §8 R-7 锚点测试（含 `ImportSpineLifecycleEndToEndTest`、`P407DuplicateClosedFullStateOracleTest`、`O2PrecisionRescaleDataTest`）在 double 签名更新后期望零修改保持绿；若实施中发现无法保持等价，停下显式登记送评审，不得静默改语义。
4. **写入路径零语义改动**：confirm/reject/review 与候选状态迁移全部走既有 spine 语句（本批只是新增调用方）；候选/正式分离原则（解析匹配只产候选，确认才入正式）不变。

## 6. UI 契约（状态机/事件矩阵）

### 6.1 状态机扩展（保留既有状态与支出/编辑语义）

- **既有状态集与字段语义不变**（`P503AppState.kt:27-206`；D-125/D-138/D-139/D-140 编辑语义、P7-01 目录管理、P7-03 月度/详情语义原样保留）。
- **Tab 扩展（拟议）**：`P503Tab` 增 `IMPORT`（第四 Tab，`P503AppState.kt:208-212` 扩展）；D-122 三 Tab 契约由本批扩展为四 Tab（底部布局语义不变），登记于 D-146。`OverviewEmpty` 新增可选 `importReview: ImportReviewView?`（默认 null，既有构造点零改动；沿 `catalogSnapshot` 模式）——IMPORT tab 的权威投影：候选清单（按 §3.3.1 分类分组）+ 格式入口（矩阵过滤）+ 最近导入会话摘要（文件显示名 + 逐项结果）。
- **新增顶层状态（拟议，全部新增）**：`ImportCandidateDetail`（候选详情 + 决策字段表单 + 重复比较与审核 + 勾选操作；仅可从 IMPORT 清单行进入）；`ImportBatchConfirm`（勾选快照确认页：逐项决策摘要 + 确认时间说明）；`ImportBatchSubmitting`（逐项派发中；**拦截返回**，沿 `Submitting` 语义）。批量结果不设独立顶层态——逐项结果内联于 `importReview`（成功/拒绝/未知三态 + Unknown 核对入口）。
- **新增事件（拟议，全部只读或经既有用例）**：`StartImportFilePick(format)`、`ImportFilePicked(picked)`、`ImportFilePickCancelled`、`ImportFilePickFailed(diagnostic)`、`ImportFileIntakeResult(outcome)`、`RefreshImportReview`、`ImportReviewResult(payload)`、`SelectImportCandidate(candidateId)`、`CloseImportCandidateDetail`、`UpdateImportDecisionField(field, value)`（纯表单）、`ToggleImportCandidateSelection(candidateId)`（受 §3.3.1 门：不可勾选项 absorbed）、`SubmitImportDuplicateReview(decision, reasonToken)`（decision 值域冻结 = `CONFIRMED_DUPLICATE`/`CONFIRMED_DISTINCT`/`DISMISSED_LOOKALIKE` 三值，§3.3.1；`REJECTED` UI 不可达）、`ImportDuplicateReviewResult(result)`、`RequestImportBatchConfirm`、`CancelImportBatchConfirm`、`AuthorizeImportBatch`、`ImportItemResult(item, outcome)`、`ResumeImportBatchDispatch`（派发暂停后同授权快照内继续未派发项，复用同次 `LedgerClock` 取样与既有 requestId）、`AbandonImportBatch`（授权快照解散、剩余项回归普通待确认清单，回 IMPORT 保留结果摘要）、`ImportUnknownItemCheck(candidateId)`、`ImportUnknownItemCheckResult(outcome)`。

### 6.2 事件 × 状态矩阵（沿 P7-02 §6.2a / P7-03 表 6.2a 语义；G-B 纪律原样适用）

**规则**：既有 reducer 未被 `when` 列出的 `(state, event)` 抛 `IllegalStateException`（P7-02 §6.2 兜底纪律，测试锁定不变）；本批**只为新增事件**定义语义，既有事件一律保持现状转换，未列即继续 ISE，不得反转任何既有 ISE。新事件在任何状态都不抛 ISE（absorbed 兜底）。

**表 6.2a：P7-04 新增事件 × 状态**（`effect` = 正常转换；`absorbed` = 明确吸收、状态不变、不崩溃）：

| 新事件 | OverviewEmpty | ImportCandidateDetail | ImportBatchConfirm | ImportBatchSubmitting | 其余全部既有状态（Ready/Editing/AwaitingConfirmation/Submitting/RequestIdentityConflict/DomainRejected/InfrastructureFailure/UnknownCommit/Created/NoChange/Recovered/TransactionDetail） |
| --- | --- | --- | --- | --- | --- |
| `StartImportFilePick` | effect（经宿主发起平台选择器；不切态） | absorbed | absorbed | absorbed（派发中禁新导入） | absorbed |
| `ImportFilePicked` / `ImportFilePickCancelled` / `ImportFilePickFailed` | absorbed（宿主异步读取/接治后以 `ImportFileIntakeResult` 回送；选择本身不切态） | absorbed | absorbed | absorbed | absorbed |
| `ImportFileIntakeResult` | effect（成功更新 `importReview` 清单与会话摘要；类型化失败 → 失败条 + 保留上一清单） | absorbed | absorbed | absorbed | absorbed |
| `RefreshImportReview` / `ImportReviewResult` | effect（成功更新清单；失败 → 类型化读失败，保留上一成功清单） | absorbed（清单经详情关闭后刷新） | absorbed | absorbed | absorbed |
| `SelectImportCandidate` | effect（→ `ImportCandidateDetail`，仅 IMPORT 清单行可达） | absorbed | absorbed | absorbed | absorbed |
| `CloseImportCandidateDetail` | absorbed | effect（→ `OverviewEmpty`，保留 selectedTab=IMPORT 与清单载荷） | absorbed | absorbed | absorbed |
| `UpdateImportDecisionField` | absorbed | effect（更新表单草稿，纯 reducer） | absorbed | absorbed | absorbed |
| `ToggleImportCandidateSelection` | effect（按 §3.3.1 门更新勾选集；不可勾选项 absorbed） | effect（详情内勾选同门） | absorbed | absorbed | absorbed |
| `SubmitImportDuplicateReview` | absorbed | effect（经宿主调 core 审核用例，结果事件回送；期间禁重复提交） | absorbed | absorbed | absorbed |
| `ImportDuplicateReviewResult` | effect（成功刷新清单/详情重复状态；拒绝类型化呈现） | effect（同语义，详情内刷新） | absorbed | absorbed | absorbed |
| `RequestImportBatchConfirm` | effect（勾选集非空 → `ImportBatchConfirm`；空集 absorbed） | effect（携详情决策进入确认页） | absorbed | absorbed | absorbed |
| `CancelImportBatchConfirm` | — | absorbed | effect（→ 原 `OverviewEmpty`，保留勾选集与清单） | absorbed | absorbed |
| `AuthorizeImportBatch` | absorbed | absorbed | effect（取样 `LedgerClock` → `ImportBatchSubmitting`，开始逐项派发） | absorbed | absorbed |
| `ImportItemResult` | absorbed | absorbed | absorbed | effect（更新逐项结果；`Unknown` → 暂停后续派发并置核对入口；全部项终态后 → `OverviewEmpty`（IMPORT），保留结果摘要） | absorbed |
| `ResumeImportBatchDispatch` | absorbed | absorbed | absorbed | effect（同授权快照内继续派发未派发项，复用同次 `LedgerClock` 取样与既有 requestId；全部项终态后 → `OverviewEmpty`（IMPORT）） | absorbed |
| `AbandonImportBatch` | absorbed | absorbed | absorbed | effect（授权快照解散，剩余项即普通待确认清单项（无隐藏中间 UI 态）；转 `OverviewEmpty`（IMPORT）保留结果摘要，未派发项持久状态保持 `pending_confirmation`） | absorbed |
| `ImportUnknownItemCheck` / `ImportUnknownItemCheckResult` | effect（核对入口在 IMPORT 结果摘要内） | absorbed | absorbed | effect（核对结果更新该项；仅全部项终态后可离开） | absorbed |

- **Back/系统返回**：`ImportCandidateDetail`/`ImportBatchConfirm` 纳入 `isBackEnabled`/`isBackDispatchSafe`（扩展点 = `P503App.kt:1206`（isBackEnabled）与 `:1226`（isBackDispatchSafe）的 when），返回 = 关闭当前导入子态回 IMPORT overview（保留清单/勾选集）；`ImportBatchSubmitting` 拦截并吞返（沿 `Submitting` 语义，防提交中退出；显式退出经 `AbandonImportBatch`）；结果摘要无独立态、随 IMPORT tab 呈现。
- **既有事件在新态**：全部 absorbed（新态不发起编辑/管理/月度流；新增入口仅存在于 overview）；编辑流（`StartNewExpense` 等）在导入子态 absorbed，不入编辑。
- **表单字段保留**：`ImportCandidateDetail` 表单草稿在详情关闭再进（同候选）时保留于会话内；进程重启丢失（无持久化，可接受——决策字段为轻量用户输入，非账务事实）。
- **读失败不篡改**：`ImportReviewResult` 失败保留上一成功清单 + 显式失败条（沿 P7-03 F1 纪律）；重试沿 `RefreshImportReview`。

### 6.3 既有语义保留（硬约束）

- 编辑流全链（D-125/D-131/D-138/D-139/D-140）不动；`Created/NoChange/Recovered` 后权威刷新恒回首页不变。
- P7-01 目录管理与 P7-03 月度/详情语义不动；导入候选决策表单的目录选项经同一权威目录投影（D-143）。
- `Exit` 事件维持现状未使用/ISE（沿 P7-02 §6.2b，不借口加 absorbed）。

### 6.4 TalkBack 与数值呈现

- 全部金额以 `formatMinorUnits` 精确呈现并保留方向语义（支出/收入/转账腿分列）；TalkBack 朗读精确数值与币种。
- 候选清单/详情/批量确认页的逐项状态（待确认/待审核/不可确认/已确认/已拒绝/未知）均有可读文案与可操作入口；「来源事实不完整」呈现缺什么（§3.3.1），不以通用错误掩盖。
- 文件显示名仅出现于当次会话摘要与失败诊断（D06：不落库、不入身份）；诊断不包含原始行、URI、个人标识或底层异常文本（沿 D-097/D-098/D-099 脱敏边界）。

## 7. 验收矩阵（D01–D06 可测断言；全部匿名合成数据）

| # | 步骤 | 期望（可测断言） | 关联裁决 |
| --- | --- | --- | --- |
| D01 | 四格式合成样本在**目标 Android** 解析；取消选择、权限撤回、GB18030 样本、损坏 XLS/XLSX、超限文件、partial 行错误 | 四格式解析与类型化失败全部安全可解释；矩阵外/待验证格式得到「待设备运行验证」类型化结果；超限携带实收值、零截断。**该向量整体为 Android 运行证据门 = 外部阻塞待办（ALas 停止 + 设备），本批以 JVM 双端语义等价 + 矩阵诚实呈现为设计侧证据（R-Q08-4）** | R-Q08-1..4；§3.1.4 |
| D02 | 解析 → 生成候选 → 勾选预览全程；缺分类、缺转账腿、混合腿不全、来源未解、关闭/失败无资金候选尝试批量确认 | 全程零正式交易产生；缺用户决策项经表单补决策后可确认；来源事实不完整项（incomplete）不可勾选且呈现缺什么来源事实；任何路径不得借批量确认绕过 | R-Q10-3；§3.3.1；候选/正式分离 |
| D03 | 同文件重复选择/改名/重启后再选；同请求重试（同 inputRef replay）；不同 raw identity 的合法相似记录；重复候选各处置（含批量处置与组边界） | 同文件重选/改名/重启后再选 = 新句柄 → 新候选 + `EXACT_BUSINESS_TUPLE` 疑似重复，经 `CONFIRMED_DUPLICATE` 人工处置阻断正式化 → **零第二次余额/报表效果**（经审核阻断达成，绝不自动去重）；同请求重试 = spine 既有请求幂等 `NoChange` 零新写入（原 receipt 重放）；合法相似记录可明确保留为多笔独立交易（`CONFIRMED_DISTINCT`/`DISMISSED_LOOKALIKE`）；同次文件选择句柄 + `EXACT_BUSINESS_TUPLE` + `DEFERRED` 组可整组批量标记 `CONFIRMED_DUPLICATE`（组键 §3.3.1/P704SPEC-12；逐项独立 requestId/reviewId、claim-gated、可见部分成功，整组确认页逐项枚举比较快照供核验；**组边界测试钉死**：同次选择入组，异次选择/异 kind/非 `DEFERRED` 出组）；镜像证据只追加到既有交易、零第二笔（P4-08 既有语义，本批无镜像操作入口） | R-Q09-1；§3.2.1/§3.3.1；R-Q10-1/R-Q10-4 |
| D04 | 多条勾选一次授权：混合 成功/领域拒绝/提交未知/进程中断；Unknown 核对后继续或放弃 | 逐项原子、可见部分成功（不误报整批成功/失败）；已成功项恰好一次（重启后清单读回 confirmed）；Unknown 暂停后续派发、等价 replay 核对（原 receipt 判成功/冲突判冲突）、不自动重试、不换 ID；暂停后 `ResumeImportBatchDispatch` 复用同次 `LedgerClock` 取样与既有 requestId 继续未派发项，`AbandonImportBatch` 置回会话待派发并保留结果摘要（未派发项持久状态保持 `pending_confirmation`，可再授权）；半提交不被误报 | R-Q09-3；R-Q10-2；§3.2.3/§3.3.2 |
| D05 | duplicateIds 并发匹配数、`NO_FUNDS` 恰好 1 组/其余 0 组分支、重复审核、stale 快照、身份碰撞、事务回滚、重开读取；拒绝与重试路径 | 修订后端口：需求量 = 事务内实际匹配数（并发下不预猜）；分支语义逐字节等价（锚点期望零修改）；拒绝/重试路径 id 源调用次数 = 0（不消耗不应分配的正式 ID）；全部既有 spine 语义保持 | R-Q09-2；§3.2.2/§4.4 |
| D06 | 确认后经 P7-03 详情/月汇总查询与数据库核对；诊断内容巡检 | 确认后交易在 P7-03 详情显示「导入创建」（反向血缘）且月度数据一致；脱敏诊断不泄露原文件名（会话显示除外）、URI、整行、个人标识或底层异常；display name 不落库；E13 mixed 时间/精度拒绝回归保持通过 | R-Q09-1；§6.4；D06 计划边界 |

## 8. 风险与披露

| # | 风险/披露 | 影响 | 缓解/登记 |
| --- | --- | --- | --- |
| R-1 | **inputRef 方案已治理翻转（2026-09-14 主代理裁决）**：原推荐方案（inputRef 由内容指纹确定性派生、同文件重选零审核幂等）经评审否决——违反 D-098 领域 1.2/2.1 冻结条款（`docs/DECISIONS.md:1486`/`:1493`）与计划 §6.2 `:154`「内容诊断哈希不能悄然升级为身份或自动去重键」；冻结方案 = 每次文件选择随机 UUIDv7 句柄 | 同文件重选的防重入账改经疑似重复审核阻断达成 | §3.2.1 裁决理由与已否决替代案登记；D-146 同步；内容哈希诊断角色全程不变；请求级重试幂等不变 |
| R-2 | **id 端口修订波及面**：14 个测试文件 double 签名更新（§2.1 第 12 条） | 实施批改动面广、漏改即编译失败（fail-loud） | 波及面全清单入规格；全部 oracle 期望零修改；rgXX_/golden 零波及（已核验不实现该接口） |
| R-3 | **微信读取器替换风险**：新读取器语义面不足或与 XSSF 观察行为有偏差 | 微信导入解析错误 | 等价判据冻结：既有 oracle 零修改保持绿 + 读取器级单测（§3.1.3）；容器/结构异常沿 D-097 taxonomy |
| R-4 | **D-099 技术门修订**：XSSF 退出生产读取（X-9 漏检结论） | 既有决定修订 | §3.1.3 治理登记：格式契约不变、仅生产读取技术路径变更；D-146 显式登记主代理裁决 |
| R-5 | **Android 运行证据缺失**：四格式无目标 Android 运行证据（X-3 为源码级推论） | Android 实机可能出现未预见失败 | R-Q08-4：外部阻塞待办 + D01 判据保留；矩阵诚实呈现（建行 XLS 不显示可用）；不宣布完成 |
| R-6 | **16 MiB 有界读取的内存占用**：低配 Android 上 16 MiB ByteArray 峰值 | 内存压力 | 上限冻结 16 MiB（低于 parser 10 MiB 拒绝界的文件即常规路径）；超限零读取/零解析；与既有 parser 全量 ByteArray 架构一致 |
| R-7 | **既有锚点必须保持绿且期望零修改**：四 parser 测试（6 类）、`ImportSpineLifecycleEndToEndTest`、`P407DuplicateClosedFullStateOracleTest`、`O2PrecisionRescaleDataTest`、P7-03 的 22 锚点与四只读查询 | 回归 | 允许改动面仅限：wechat 读取器内部实现（期望不变）、id-source double 签名随端口修订（oracle 期望不变）、新增文件；实施批逐类复跑（§9/验证路由） |
| R-8 | **同文件重选的审核负担**：随机句柄方案下重选（含误重选/改名/重启后再选）使全部与既有记录同元组的条目成为疑似重复候选，需人工处置 | 大文件误重选产生大批审核项 | 安全优先（防重入账经 `CONFIRMED_DUPLICATE` 审核阻断达成，绝不双计）；缓解 = **已冻结批量处置**（§3.3.1/P704SPEC-12：同次文件选择句柄 + `EXACT_BUSINESS_TUPLE` + `DEFERRED` 组键整组标记 `CONFIRMED_DUPLICATE`，整组确认页逐项枚举比较快照供核验，逐项独立 core 请求、claim-gated、无整组原子性、可见部分成功）；未确认候选不产生任何效果，用户可在审核前整批放弃 |
| R-9 | **UI 重复门为呈现层新增**（R-Q10-1）：core 仍只有 `CONFIRMED_DUPLICATE` 阻断 | 呈现层与 core 门强度差异被误读为 core 语义 | 规格如实声明（§3.3 原文）；core 状态机零改动；呈现层门随 UI 测试锁定 |
| R-10 | **无整批事务承诺**：部分成功为既定结果 | 用户预期管理 | 授权确认页明示逐项提交语义；结果逐项呈现；D04 断言 |
| R-11 | **GB18030 探测位置**：探测在 jvmMain 编排层而非 parser（parser 零改动约束） | 支付宝路径多一层判别 | 探测失败 = 类型化 Unavailable（携带格式名）；桌面 JDK 恒通过（现有测试不受影响） |
| R-12 | **隐私（D06）**：display name/诊断不落库个人标识 | 泄露 | §6.4 冻结；display name 仅会话内显示；诊断沿 D-097/D-098/D-099 脱敏边界；D06 巡检向量 |
| R-13 | **四 Tab 对 D-122 的扩展**：三 Tab 契约修订 | 既有布局语义回归 | 底部布局语义不变，仅增 Tab 项；D-146 登记；两端启动回归向量（P7-04.E） |
| R-14 | **接治耗时**：单文件 10,000 接治上限对应最多 10,000 次独立 spine intake 事务（每次 claim-first 原子提交） | Android 实机最坏情形导入耗时可感知 | 已知代价（设计不变：逐项原子与上限语义不为此放宽）；实施批须在 D01 Android 门内实测量化并披露（不预设阈值；D-146「实施批义务」④）；结果摘要呈现进度，超时不得静默截断或放弃已接治记录 |

## 9. 实施拆分（收编 `docs/PHASE7_IMPLEMENTATION_PLAN.local.md` §6.2，:144-150）

| 子项 | 具体工作及接口责任 | 完成条件 |
| --- | --- | --- |
| P7-04.A 平台与格式门 | app-ui 文件选择端口与有界读取类型；组合根 SAF/JFileChooser 实现；格式能力矩阵；16 MiB/10,000 限额；有界最小 XLSX 读取器；POI 依赖收敛 | Q08 已裁决（R-Q08-1..4）；每种宣称支持格式有目标 Android 运行证据（外部门）或诚实标记待验证；超限类型化失败携带实收值；wechat oracle 零修改保持绿 |
| P7-04.B 身份与生产接线 | inputRef 随机句柄方案（每次文件选择一个 UUIDv7）；接治编排端口与 jvmMain 实现；生产 ID 端口修订与组合根接线（UUIDv7）；确认时间 LedgerClock 取样 | raw identity/内容指纹/疑似重复/镜像职责分离（§3.2.1）；获胜 claim 事务内确定需求量后才分配正式 ID；早拒绝零 ID；D05 通过 |
| P7-04.C 候选与重复审核 | 只读命名查询（Appendix A）+ `ImportReviewReadPort`（fail-loud）；候选清单/详情/决策字段投影；重复比较与审核操作（UI 决定集三值 + 同次文件选择 `EXACT_BUSINESS_TUPLE`/`DEFERRED` 组整组 `CONFIRMED_DUPLICATE` 批量处置，组键与呈现义务见 §3.3.1）；§3.3.1 分类矩阵 | 不完整/冲突/关闭无资金项有解释（缺什么来源事实）；确认重复阻断、合法相似可明确保留；疑似重复先审后勾门锁定；批量处置逐项独立请求、可见部分成功、组边界 D03 族测试钉死 |
| P7-04.D 批量确认与恢复 | 授权快照确认页；逐项原子派发；部分成功呈现；Unknown 暂停与等价 replay 核对 + `ResumeImportBatchDispatch`/`AbandonImportBatch` 出口（§3.3.2）；重开恢复清单 | 成功/拒绝/未知分别呈现；已成功不重复提交；未知暂停后续派发、不换 ID；继续复用同次取样与既有 requestId、放弃保留结果摘要；Q09/Q10 已裁决（R-Q09-3/R-Q10-2）；D03/D04/D06 通过 |
| P7-04.E 移动闭环 | Android 组合根接线（SAF + 探测 + IMPORT Tab）；固定 APK 全链（选择→解析→列表→补决策→审核→确认→详情/月汇总→重开） | 四来源及重复/部分失败/中断场景过门（D01 Android 部分 = 外部待办）；离线可用；无未确认正式效果；两端启动回归保持 |

**实施批义务（登记于 D-146，此处只引用不重复）**：四项（① P704SPEC-12 分组键落法 + D03 组边界测试 + 整组确认页逐项枚举呈现；② `duplicateMatchCountForIntake` 与 `selectDuplicateMatches` 的 D05 族等价回归；③ `AbandonImportBatch` 不引入隐藏中间 UI 态；④ R-14 接治耗时 D01 Android 门内实测量化披露）完整登记于 D-146 登记的「实施批义务」段，实施批必须逐项闭合并在实施登记中报告。

## 10. 本批不做（逐项）

PDF/其他银行/信用卡/微信外层加密 ZIP；自动/监听导入；文件留存与持久 URI 生命周期；临时文件产品；整批事务承诺；来源事实编辑（含追加历史转换契约）；镜像确认操作化；新库引入（fastexcel/poi-android/aalto 等）；rgXX_/golden 改动；导入链写入语义改动（ID 分配端口修订为机械性重构、可观测语义零变化）；schema 迁移（零 DDL，停留 v29，只新增只读命名查询）；多账本；导出/分享；搜索；自动去重/自动确认。（另见 §1.2。）

## 11. 验证路由（对齐计划 §8.1）

- **高风险路由**（根 `AGENTS.md` 变更路由：账务/架构/隐私/平台集成批）：单 bounded writer 于隔离 worktree + 独立规格评审 + 独立质量评审 + distinct verifier + 主代理关键 diff 复核与验收复跑；本规格为该路由的设计门产物。
- **本机聚焦（串行执行，沿计划 §8.1 CONTRIBUTING 约定）**：先聚焦后全套——聚焦例：`:ledger-application:jvmTest --tests "*WechatBillParserJvmTest"`（读取器等价）、`--tests "*ImportSpine*"`（端口修订）、`:ledger-data:jvmTest --tests "*ImportSpineLifecycleEndToEndTest" --tests "*P407DuplicateClosedFullStateOracleTest" --tests "*O2PrecisionRescaleDataTest"`（锚点期望零修改）；随后受影响模块全套 `:ledger-application:jvmTest` / `:ledger-data:jvmTest` / `:app-ui:jvmTest`，组合根变化增加 `:desktop-app:jvmTest` 与 `:android-app:testDebugUnitTest`；再 `ktlintCheck` 与 `project_docs`；`:ledger-data:verifyCommonMainLedgerDatabaseMigration` 保持 PASS 佐证零 DDL。
- **回归锚点（计划 §8.1 :205）**：四来源 parser tests、`ImportSpineLifecycleEndToEndTest`、`P407DuplicateClosedFullStateOracleTest`、`O2PrecisionRescaleDataTest` 全部不修改期望保持绿（§8 R-7）；必增覆盖 = D01–D06（D01 的 Android 运行部分为外部待办）。
- **聚合门**：完整 `check`、Android/KMP 编译、APK 组装、Desktop build、完整 Python suite 以**精确提交的 CI** 为权威证据（本机受约束资源不重复聚合门；CI 成功不替代 Android 人工运行证据）。
- **Android 人工门**：固定 APK + 隔离 adb 协议（`ANDROID_ADB_SERVER_PORT=5038`、仅操作本会话自行启动且核实 AVD 的设备、不碰 MuMu/ALas、不 `adb kill-server`）；执行 D01 运行证据门（§3.1.4，外部阻塞待办：ALas 停止 + 设备）。
- **推送前**：clean `verify-project -Scope trace`；推送须用户显式授权。

## 边界断言

- 本文档为设计门 **冻结规格（approved，2026-09-14 DELTA CLOSURE APPROVE）**：首轮独立规格评审 REQUEST-CHANGES（P704SPEC-01..11 + 附注）修复闭环与 delta 复验 CLOSURE APPROVE（含 P704SPEC-12 按固定裁决落入本版）后构成本批实施契约；实施在单一 bounded writer、独立规格/质量评审、distinct verifier 与主代理最终验收之下（根 `AGENTS.md` 变更路由）；四项实施批义务按 D-146「实施批义务」段逐项闭合。
- 实施批必须保持本规格冻结的：零 DDL（schema 停留 v29）、零导入链写入语义改动（ID 端口修订可观测等价）、四 parser 冻结契约与全部锚点期望零修改、rgXX_/golden 零改动、限额与矩阵、inputRef 方案、批次/恢复/Unknown 语义、§3.3.1 分类矩阵与 UI 门；任何变更即重开评审门。
- Android 运行证据门（R-Q08-4）为外部阻塞待办，不以本批设计侧证据替代；未过门不得在建行 XLS Android 列翻转矩阵或宣布完成。
- 真实金额/时间/锚点注册值不复制入文；示例全部匿名合成；`.external/` 只读未触碰；`docs/ACCOUNTING_RULES.md` 本批未改（本批无账务规则变更需求；若评审认定需要，回主代理走高风险路由）。

## Appendix A. 拟议新增命名查询清单（实施批以评审后文本为准；全部零 DDL、SELECT-only、按 `:ledger_id` 限定；沿 `Ledger.sq:8741-8745` 段风格落位）

```text
importReviewRowsForLedger         -- ledger-scoped 候选清单：import_candidate + import_source_record（来源事实摘要
                                   -- + content_hash）+ 最新候选状态（沿 selectImportCandidateCurrentStatus :8409
                                   -- 行语义按 ledger 收窄）+ requires_confirmation + confidence
                                   -- + 该 source 的最新重复处置（沿 selectDuplicateCurrentStatus :8318 行语义）
                                   -- + v3 payment profile 摘要（selectImportCandidatePaymentProfile :8335）
importCandidateDetailByCandidate  -- 单候选详情：清单行全部字段 + 候选状态历史计数
importDuplicateReviewsForSource   -- 候选 source 的重复候选集：kind/comparison_snapshot（P4-07 冻结隐私安全投影）
                                   -- /最新重复状态/审核处置与理由 token + possible_existing_source 的事实投影
duplicateMatchCountForIntake      -- intake 需求量计数（R-Q09-2）：SELECT-only、按 :ledger_id 限定；谓词 =
                                   -- selectDuplicateMatches(:8296-8301) 的匹配条件；无 sourceId 自排除参数——
                                   -- 计数在插入新 source 前执行，被计数行全部为既有 source
-- 粒度注记：读端口与 importDuplicateReviewsForSource 以 candidateId 表达、重复表按 subject source 键
-- （import_duplicate_candidate.subject_source_id）；查询以 import_candidate.source_id 做 candidate→source join
-- 解析（每候选恰一 source，UNIQUE (ledger_id, source_id) :7686）
-- 批量处置分组（P704SPEC-12）：组键 = import_duplicate_candidate.kind='EXACT_BUSINESS_TUPLE'
-- AND subject source 的 import_source_record.input_ref = 本次文件选择句柄 AND 最新重复状态='DEFERRED'；
-- 经清单投影暴露的不透明 input_ref 句柄参数化（随机值、不含个人标识，D06 安全；零 DDL）。
-- comparison_fingerprint 不作查询键（其输入含 subject_source_id 每候选唯一，等值分组只能得单例组）；
-- 比较快照为整组确认页逐项枚举呈现的核验义务，不是分组条件
-- 复用既有查询（不新增）：selectImportSourceByIdentity(:8368)、selectImportReceiptByRequest(:8433)、
-- selectImportConfirmationByRequest(:8444)、P7-03 四查询(:8747-8832)
-- Unknown 核对/重开恢复不新增查询：等价 replay 走既有 resolveConfirm；恢复 = importReviewRowsForLedger 清单读
-- 零 DDL：无新索引/新列/新表；schema 停留 v29
```
