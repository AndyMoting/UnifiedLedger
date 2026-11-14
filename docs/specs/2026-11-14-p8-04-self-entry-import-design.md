# P8-04 自填资料导入（SELF_ENTRY_XLSX 第 5 格）设计门——模板生成、填写、导入、逐项审核确认全链

**状态：** **approved v1**（2026-11-14；独立评审 APPROVE 无 P1——F-1 由 GitHub 侧证实 PR #348 MERGED 闭合、F-2/F-3/F-4 随本修订落死；批准依据 = 用户 grilling 一轮 15 条裁决 + 推荐项即批准。按计划 `:46`「新增第 5 格来源与 schema 变更均须独立批准」：本文批准即第 5 格来源的独立批准，schema 走零 DDL 路径（§1.3/§7），无迁移授权需求）
**Revision：** v2（2026-11-14；v1 首稿 + 评审后修订：D-15/§1 合并证据改为 GitHub 证实、D-18 补 funding facts/status_token 钉死、V-4 补跨格式腿）

**指针：** 计划 `docs/PHASE8_ANDROID_PLAN.local.md` `:34`（竞品按切片只读 iCost §二/§七）、`:46`（P8-04 行：出口 = 匿名模板正常/缺列/错列/乱码/空行/超限/重复/部分失败类型化 + 确认前零正式效果 + 重放无重复入账 + Android SAF 全链与大样本）、`:69`（R8-05：先裁字段/身份/隐私再定文件格式——D-2/D-7/D-16 即该顺序的落点）；决定 D-004（本地优先）/D-016（来源事实·用户决定·推断三向分存）/D-017（不得自动入账）/D-021/D-022/D-029（最小字段与分类深度）/D-024（同名完整路径消歧）/D-032（不得猜测）/D-041（固定两级）/D-043（来源时间即凭证时间）/D-047（版本修正承担回滚）；D-146（P7-04 格式矩阵与 intake 管线）、D-276（`StorageUnavailable` 中批语义）、D-097 族（行级诊断）；P8-00 `OpenImportSurface` 已交付（`P503UiEvent.kt:678`、`P8ImportSurfaceReducerTest.kt`）；证据草案 `local/p8-04-design/DESIGN-DRAFT.md`（本文是其 §8 裁决的规格化）；iCost 行为证据 `ICOST_I8_IMPORT.md` §二（`:30-81`）/§七（`:338-376`）——只作行为对照，不复制实现、不搬私有约定。

本文不改写上述任何既有裁决：不改 D-097 诊断纪律、不改 spine 幂等与指纹契约、不改两粒度错误隔离、不改 R-Q08-3 诚实可用性、不改候选确认面语义。

---

## 1. 现状与复用边界（全部 file:line 由本写手亲读复核，2026-11-14，main = `9f5820a8` = D-322 squash，PR `#348` GitHub 侧 MERGED 已证实；评审 F-2 的 HEAD 漂移随本修订闭合）

### 1.1 模块拓扑与管线

- **模块拓扑（关键前提）**：`ledger-application` 是仅 jvm 目标的 KMP 模块（`build.gradle.kts:22-29`）——`jvmMain` 即 Android 与桌面共享的生产源集；POI `poi:5.5.1` 仅 jvmMain（CCB HSSF，`:52-56`），`poi-ooxml` 仅 jvmTest（`:58-62`，注释明文 Android 不得继承 POI classpath）。因此 jvmMain 新增解析臂天然双端共享。
- **入口与编排**：`P503App.kt:2336-2340` `startImportFilePick` 写 `pendingImportFormat` 单槽后 launch；租约内 intake + `importIntakeStatisticsRefresh()`（`:2402`）+ 一页清单落地（P7-04 D-4）；分享闭环同槽复用（`:2560`）。
- **intake 契约（commonMain `ImportFileIntake.kt`）**：会话身份 per-ordinal memoized requestId（`:19-47`）；L2 `IMPORT_INTAKE_MAX_ACCEPTED_RECORDS = 10_000`（`:54`）；`ImportFormatUnavailableReason.PENDING_DEVICE_VERIFICATION`（`:57-68`，KDoc `:59-63` 明文为「未来以 pending 态入矩阵的格式」保留该分支）；批失败四值（`:81-117`）；行摘要 D-097 安全位置五元组（`:140-147`）；折叠空集补遗——仅表头空文件 = 空 Accepted、永不 `NoChangeAll`（`:209-227`）。
- **spine 幂等**：`SqlDelightImportSpineStore.kt:120-169` `commitIntake` claim-first——等价重放零写入（`:144-148`）、非等价 `SPINE_IDENTITY_COLLISION`（`:149-156`）；内容指纹 `digest(recordKind, facts, paymentProfile)`（`:129`）**不含格式**。「重放无重复入账」由此免费获得。
- **SAF 面**：`AndroidImportFilePickPort`（android `ImportFilePick.kt:40-98`，一次性有界读、无持久权限无拷贝）；`AndroidSafLaunchSlot`（`:114-132`）——`launchCreateDocument`（`:119`）是备份导出在用的 CreateDocument 先例，第 5 格模板写出口直接复用该槽位形态。L0 16 MiB（app-ui `ImportFilePick.kt:6`）。

### 1.2 解析器与格式矩阵

- **矩阵**：`ImportFormatCapabilities.kt`（commonMain 根包）冻结四格式（`:53-106`）、`ALL`（`:109`）、fail-loud 查找（`:116-118`）；可用性只能经设备验证门翻转（KDoc `:9-14`），UI 对 pending 恒显诚实文案（`P503ImportReview.kt:521-528`，R-Q08-3）。
- **XLSX 读取器**：`BoundedXlsxReader.kt`（jvmMain `import/wechat/`，object `:74-672`）——最小语义面 KDoc `:19-73`：workbook 首 sheet、sharedStrings、A1 坐标（缺 r 顺序回退）、缺单元格不伪造、数值单元格取缓存 `<v>` 精确文本「never routed through binary floating point」（`:103`）；zip 炸弹 100x（`:76`）；XML 加固双保险（`:585-617`/`:633-649`）；**带命名空间前缀的文档在最小面外**（`:67-72`）。
- **CSV 先例**：`AlipayCsvParser.kt` 表头字节级拒（`:43`）、空行跳过不重编号（`:51`）、严格 UTF-8→GB18030 解码（`:82-85`）。
- **候选确认面**：格式条目区 `P503ImportReview.kt:512-533`；`INCOMPLETE_SOURCE_FACTS` 缺事实说明位（`:713`/`:814`，R-Q10-3 形态）；分类选项按 face×方向选源（`:943`）；会话决策草稿与六类决策表单见草案 §1.1（`:244-255` 一带，p804-scout 已核）。

### 1.3 schema：零 DDL 的依据

- `24.sqm:88-115` `import_source_record`：六 kind CHECK（`:94`）、kind→contract_version 绑定（`:114`）、身份 UNIQUE `(ledger_id, input_ref, record_ordinal)`（`:109`）、valid_complete 事实 CHECK（`:112`）。`Ledger.sq:8091-8102` `import_candidate`：六 kind CHECK（`:8095`）、`rule`（`:8097`）、**`rule_version = 1` CHECK（`:8098`）**；重复候选 `rule_id = 'p407_exact_business_tuple_v1'`（`:8289`）+ `ImportSpine.kt:124` `EXACT_BUSINESS_TUPLE`；决策快照（`:8125-8137`）。迁移序列至 `36.sqm`（计划 `:4` v36）。
- **推论（R8-05 杠杆）**：第 5 格只要把行映射进既有六 kind + `ImportSourceFacts`（`ImportSpine.kt:133-143`）即零 DDL；格式不是指纹入参，跨格式重放不命中 spine 身份 → 由重复候选族兜底（D-20）。

---

## 2. 目标与非目标

**目标**

1. SELF_ENTRY_XLSX 第 5 格全链：导入 Tab 获取模板（运行时生成 + SAF `CreateDocument` 写出）→ 用户在任意表格应用填写 → SAF pick 导入 → 逐项审核/补齐/确认，管线与确认面全部复用（§1 全部资产）。
2. 9 列模板契约冻结且模板生成与解析同源对称（round-trip 钉住，D-17/V-1）。
3. 名称匹配产候选与匹配依据展示，不产决定（D-017 原样）；跨格式重复以既有重复候选族兜底并显式声明（D-20）。

**非目标**：CSV 版模板（D-1）；多币种开放（D-5）；v3 kind/借贷/信用消费（D-3）；列名映射 UI（iCost §九亦未做，UL 明确不搬）；导入回滚面（D-047 版本修正承担）；Rig 线预算/marker/stage 预算改动；任何 DDL/迁移/新依赖。

---

## 3. 裁决

| # | 裁决 | 内容（每条以可查界收口） |
| --- | --- | --- |
| D-1 | 仅 XLSX（Q1） | 编码面整个消失（iCost §七 `:342-343`：xlsx 结构上不存在乱码失败模式）；CSV 版留待真实需求另立裁决，届时必须严格解码阶梯 + 显式拒绝，禁止 Latin1 类永不失败兜底（§七教训，承接 R-P804-1）。 |
| D-2 | 9 列最小集（Q2+Q7+Q8） | 类型/日期时间/金额/币种/一级分类/二级分类/账户1/账户2/备注；列契约由我们发布的模板冻结，不做列名映射 UI；标签列裁掉（`#前缀连写` 是竞品私有约定）；备注进模板但只落候选详情展示位、不持久化（`import_source_record` 无备注位）。解析语义 = D-16 附表。 |
| D-3 | 类型三值（Q3） | 支出/收入/转账 → ordinary_flow_source（既有 directionToken 值域区分收支）/transfer_flow_source/transfer_flow_source_missing_leg；v3 kind 不进首版（决策快照 XOR 复杂度高、自填收益低）。 |
| D-4 | 转账缺账户2 → missing_leg 候选（Q4） | 进确认面补齐、不拒行（D-032 不得猜测）；缺账户1 → `VALID_INCOMPLETE` 候选说明缺资金腿。 |
| D-5 | 币种保守（Q5） | 币种列进模板：空格 = 账本默认币种（推断，进候选详情可见）；非空且 = 默认 → 正常；非空且 ≠ 默认 → 该行 `VALID_INCOMPLETE` + 说明「币种 X 暂不支持」（行级、非拒、非静默换币）；多币种开放属 future_draft，本批不碰。 |
| D-6 | 名称匹配失败/歧义 → incomplete 候选（Q6） | 详情面说明缺什么（R-Q10-3 形态，`:713` 先例）；匹配依据须在详情可见（D-19）。 |
| D-7 | 备注展示位（Q7） | 备注列内容随会话候选行携带、仅候选详情展示、零持久化——与草案推荐 (b) 不同，按用户裁决保留展示位形态；草案 §7 接缝与 §9 影响 2（备注列 schema）相应作废。 |
| D-8 | 标签裁掉（Q8） | UL 标签语义未裁决前模板不携带（并入 D-2）。 |
| D-9 | 方向矛盾行级拒（Q9） | 类型↔金额符号不一致（如「支出」填正数）→ 行级 PARSER_REJECTED，新增 D-097 族 code `SELF_ENTRY_DIRECTION_CONFLICT`（fieldRole=amount）；禁止静默重定向符号（Q9 选项 b 违反不得静默猜测）。 |
| D-10 | 两粒度错误隔离（Q10） | 全部按既有先例，无第三粒度：批级 = 表头错列/缺列/错序 `ParserRejected`（`AlipayCsvParser.kt:43` 形态）、超 L2 `BatchExceedsLimit(actual)` 零写入、超 L0 `ReadExceedsLimit`、仅表头空文件 = 空 Accepted（`:209-227`）、存储异常 `StorageUnavailable`（D-276）；行级 = 必填来源事实（类型/日期时间/金额）缺格或不可解析拒、空行跳过不重编号（`:51`）；重放幂等见 V-4。 |
| D-11 | 模板运行时生成 + SAF 写出（Q11） | 生成器在 jvmMain 以 `java.util.zip` 拼最小 OOXML（形状 = D-17），版本与代码恒一致、无资产漂移；导入 Tab 新增「获取模板」入口 → SAF `CreateDocument`（先例 `ImportFilePick.kt:119`），建议名 `unifiedledger-import-template-v1.xlsx`；写出失败类型化（复用备份导出错误族）；写出后可经分享闭环回导，零新管道。 |
| D-12 | 复用 BoundedXlsxReader（Q12） | 模板冻结在读取器最小语义面（D-17 保证）；零新增依赖；`poi-ooxml` 维持 jvmTest-only（`:58-62` 现状不变）。 |
| D-13 | 第 5 格以 pending 入矩阵（Q13） | `SELF_ENTRY_XLSX` 描述符 ANDROID=`PENDING_DEVICE_VERIFICATION`/DESKTOP=`AVAILABLE`（桌面 JVM CI 全契约可达）；pending 期间 UI 诚实文案（`:521-528` 先例）；翻转腿 = i4 设备验证后改声明，矩阵与管线机制零改（pending 分支为此保留，`:59-63`）。 |
| D-14 | 版本化 = 文件名 + 表头字节匹配（Q14） | 建议文件名携带 `-v1`；解析器对 9 个表头串做字节级匹配（sharedStrings 序列恒等），缺列/多列/错序/改写 → 批级 `ParserRejected`；列契约变更 = 第 5 格升 id，不兼容变更不复用格式 id。 |
| D-15 | 实施批排后于 D-322（Q15） | PR `#348`（squash `9f5820a8`）已于 2026-11-13 21:40 UTC 合并——GitHub 侧证实（state=MERGED），本地 main 已 ff 至该提交（评审 F-1 的 P2 项闭合）；冲突解除。i3 触碰的 `P503ImportReview.kt` 属 Rig 线 task-1 写范围，实施批按写范围登记申报、不并行抢写。 |

**机制裁决（本规格新增）**

| # | 裁决 | 内容 |
| --- | --- | --- |
| D-16 | 列解析契约 | 见 §3.1 附表；表头 9 串为**单一常量源**（生成器与解析器同源引用，防漂移）。 |
| D-17 | 模板生成器形状 | 最小 OOXML 六 part：`[Content_Types].xml`、`_rels/.rels`、`xl/workbook.xml`、`xl/_rels/workbook.xml.rels`、`xl/sharedStrings.xml`、`xl/worksheets/sheet1.xml`；单 sheet、默认命名空间（无前缀）、无公式无富文本无样式表依赖；表头 + 3 示例行（支出/收入/转账各一，覆盖 D-16 全形态）；全部文本走 sharedStrings（读取器语义面内，`:19-73`）。可查界：产物被自家解析器读回（V-1）且设备表格应用可打开（V-6）。 |
| D-18 | 名称匹配候选生成 | 匹配在解析臂内以纯函数完成：分类名（含二级的完整路径）→ catalog 稳定 ID、账户名 → 账户 ID；D-024 同名走完整路径消歧，歧义（无唯一解）按无匹配处理。匹配成功 → 候选照常生成，匹配结果以「预填决策草稿 + 匹配依据展示值」随会话候选行下发（决策草稿会话内存纪律原样）；失败/歧义 → `VALID_INCOMPLETE` + 说明位（D-6）。落 `import_candidate.rule` 区分串（`self_entry_v1`），`rule_version` 保持 1（CHECK `:8098` 不动）——关闭草案 §9 影响 6 的 CHECK 演进点。确认仍走勾选门 + 决策表单（D-017）。**funding facts 钉死（评审 F-3，i2 前闭合）**：完整转账行 `fundingState = SETTLED`；缺账户2 行 `fundingState = NO_FUNDS`（`ImportSourceFacts:140-142` 无默认，漏钉则重复族静默不发）；`status_token = settled`（与 §1.2 meta 正则先例一致）。 |
| D-19 | 详情面匹配依据区 | 候选详情新增只读展示区：来源单元格文本 → 匹配目标显示名 + 匹配方式（精确/路径消歧）；备注展示位（D-7）同区。数据随会话候选行携带、不持久化（重启后与决策草稿同失，既有纪律）；零 schema。触碰 `P503ImportReview.kt`（Rig 线写范围）→ 按 D-15 串行。 |
| D-20 | 跨格式等价声明 | 同一笔业务既经自填模板又经官方账单导入时，`selectImportSourceByIdentity` 不命中（不同 input_ref，指纹不含格式）；等价性由重复候选族兜底——`EXACT_BUSINESS_TUPLE`（`ImportSpine.kt:124`，`Ledger.sq:8289`）产生待处置重复候选，**不自动合并、不自动拒**，用户经既有三值重复审核处置。此为规格显式声明，衔接 RL-07「两端只形成一笔正式转账，第二来源作补充证据」口径。 |

### 3.1 D-16 附表：列解析契约（数据行语义）

1. **类型**：`支出|收入|转账` 三值字节匹配；缺格/未知值 → 行级拒（recordKind 无法成立，必填来源事实）。
2. **日期时间**：文本形态 `yyyy-MM-dd HH:mm:ss`（示例行即填写说明）；只接受 STRING/inlineStr 单元格，NUMERIC 序列值 → 行级拒（首版不做 1900 序列换算，避开浮点与日期系统坑）；解析经固定 Asia/Shanghai 转 ISO 存储（D-138 时区先例；D-043 凭证时间独立）。
3. **金额**：带符号十进制文本（STRING 或 NUMERIC 的缓存 `<v>` 文本均可），BigDecimal 文本解析、禁二进制浮点；小数位 > 所落币种 precision → 行级拒 `SELF_ENTRY_AMOUNT_PRECISION`（AGENTS 金额精确）。
4. **币种**：D-5 语义。
5./6. **一级/二级分类**：文本原样作匹配输入（不入 source facts，D-016 三向归属）；二级空格 = 不适用，按一级名匹配；深度纪律由既有候选/确认面承担（D-022/D-029/D-041）。
7./8. **账户1/账户2**：文本原样作匹配输入；语义 = D-4/D-18。
9. **备注**：任意文本，仅展示位（D-7）。

---

## 4. 实施切片与可写范围（每片零 DDL、独立 PR）

**i1 模板生成器 + 下载入口**：`ledger-application` jvmMain 新增 `import/selfentry/SelfEntryTemplateGenerator.kt`（9 串常量单一源在此）；app-ui 导入面「获取模板」行 + 下载端口契约（commonMain 新 port）；android-app CreateDocument 接线（`AndroidSafLaunchSlot` 槽位已有）。测试：`SelfEntryTemplateGeneratorJvmTest`（结构 + round-trip 前半）、`P804TemplateDownloadReducerTest`（app-ui commonTest）、Android 导出 seam 测试。

**i2 解析器 + 矩阵第 5 格 + 编排器分支**：jvmMain 新增 `SelfEntryXlsxParser.kt`（D-16 全契约 + D-9/D-5 行级语义）；`ImportFormatCapabilities.kt` 追加第 5 描述符（D-13 pending 形态）；`ImportFileIntakeOrchestrator.kt:108-115` when 增一臂。测试：`SelfEntryXlsxParserJvmTest`（对抗样本全族 + 与生成器 round-trip）、`ImportFileIntakeSelfEntryTest`、矩阵测试扩条。

**i3 名称匹配 + 匹配依据区 + 备注展示位**：解析臂内匹配纯函数（含 D-024 消歧）+ `rule=self_entry_v1` 落点；`P503ImportReview.kt` 详情面匹配依据区 + 备注位（Rig 线写范围，D-15 串行）。测试：`SelfEntryNameMatchJvmTest`（同名歧义反例必含）、`P804MatchBasisPresentationTest`。

**i4 设备验证翻转腿 + 大样本**：androidTest 自填模板 SAF 全链（下载→pick→intake→确认，沿 CCB A-04.1 仪器化先例，草案 §1.2）；翻转 ANDROID 可用性声明（单行 diff + 矩阵断言改）；L2 边界 JVM 大样本 + 设备读数登记（不碰 Rig 预算/marker）。测试：`SelfEntryAndroidInstrumentedTest`、`SelfEntryL2BoundaryJvmTest`。

**顺序**：i1 → i2 → i3 → i4（i3 依赖 i2 解析臂；i4 依赖全部）。

---

## 5. 验收向量

| # | 向量 | 证据类型 |
| --- | --- | --- |
| V-1 | round-trip：生成器产物被自家解析器读回，表头 9 串与 3 示例行语义逐项等价；POI XSSF（jvmTest）侧可打开作 Excel 兼容对照 | jvmTest |
| V-2 | 类型化逐类，每类一条断言：表头缺列/多列/错序/改写 → 批 `ParserRejected`；DOCTYPE/外部实体/命名空间前缀/zip 炸弹样本 → 类型化容器拒（读取器既有语义）；空行跳过不重编号；仅表头 → 空 Accepted；超 L0 → `ReadExceedsLimit`；方向矛盾 → `SELF_ENTRY_DIRECTION_CONFLICT`；超精度 → `SELF_ENTRY_AMOUNT_PRECISION`；类型/日期时间/金额缺格或不可解析 → 行级拒；NUMERIC 日期 → 行级拒；转账缺账户2 → missing_leg 候选；名称无匹配/歧义 → `VALID_INCOMPLETE` + 说明位；币种非默认 → `VALID_INCOMPLETE` 说明（非拒非换币） | jvmTest |
| V-3 | 确认前零正式效果：intake 只产候选与证据；确认走勾选门 + 决策表单 + 快照 XOR（既有） | 既有用例 + 新臂扩展 |
| V-4 | 重放无重复入账：同会话重放 → `NoChangeAll`（spine `:144-148`）；跨会话同文件 → 新候选 + 重复候选族，断言零第二次入账；**跨格式腿（评审 F-4）**：自填行落在已入账官方行之上 → `EXACT_BUSINESS_TUPLE` 候选生成且三值重复审核处置可达，断言不自动合并 | jvmTest（真库） |
| V-5 | 版本化：改一列表头的样本被批级拒且格式 id 不变；契约升级 = 新 id 的判定按 D-14 测试钉住 | jvmTest |
| V-6 | SAF 全链（设备）：CreateDocument 写出成功/失败类型化；pending 期间第 5 格诚实文案（R-Q08-3）；i4 翻转后 pick→intake→确认全链通过 | 仪器化测试 + run 证据 |
| V-7 | L2 边界：10,000 行 → Accepted；10,001 行 → `BatchExceedsLimit(actualAcceptedRecords=10001)` 且零写入 | jvmTest（真库）+ 设备大样本读数 |

---

## 6. 风险与承接

1. **跨格式等价**：D-20 已声明为「重复候选兜底、不自动合并」；残余 = 用户教育成本，落在文案与重复审核呈现，不新增机制。
2. **`rule_version` CHECK 演进点**：D-18 以 rule 串区分关闭；未来匹配语义实质变更须新裁决，不在本批。
3. **模板/解析器对称维护**：9 串单一常量源（i1 持有、i2 同源引用）+ V-1 round-trip 硬门；任一侧手抄字符串即红。
4. **Excel/WPS 兼容性**：最小 OOXML 包在真实编辑器的可打开性无本地测试可钉，V-6 设备证据兜底；若编辑器要求 styles.xml 等额外 part，按「生成器扩 part、读取器语义面不变」处理并登记（实施期登记纪律）。
5. **Latin1 教训声明（R-P804-1）**：XLSX 无编码面，本批不涉及；未来任何 CSV 版禁止永不失败兜底解码，入口在 D-1。
6. **NUMERIC 日期被拒**是可见行为面：示例行引导文本形态，误用收行级说明而非静默错值（D-16.2）。

---

## 7. 边界

入：本文、i1–i4 的 tracked 变更、随之的决定条目。

出：**零 DDL/零迁移**（迁移序列停在 `36.sqm`）；不碰多币种开放（D-5）；不碰 Rig 线预算/marker/stage 预算；`.external/` 只读（iCost 证据仅行为对照，不复制实现、私有约定不搬）；不新增依赖（D-12）；不改既有四格式任何行为；不碰列名映射 UI 与导入回滚面（§2 非目标）；v3 kind 不进首版（D-3）。
