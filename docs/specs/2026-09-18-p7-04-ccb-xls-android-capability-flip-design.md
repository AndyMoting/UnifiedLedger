# P7-04 A-04.2 批：建行 XLS Android 能力矩阵翻转设计（+ 平台门测试同批修订）

状态：approved（冻结规格 v3 忠实转写；规格评审两轮——v1 REQUEST-CHANGES 的 A042SPEC-01/02/03 MAJOR 与 04/05 MINOR 已吸收，v2 delta 复核确认吸收并新出 A042SPEC-10 MAJOR（决策表单补全子步）+ 11/12/13（计数口径/月份钉准/示例更正），冻结版全部吸收。源码基线 main `b66bdf3`（A-04.1 已收口），schema v30。本 tracked 版由冻结规格 v3 按本目录既有 design 文档组织风格正式落盘，全部命题、交付内容、验收与纪律逐条忠实保留。）

**Revision:** v3（冻结）。依据：`docs/PHASE7_REMAINING_IMPLEMENTATION_PLAN.local.md` §2 A-04 行（:34 含「再用产品路径选文件→解析→候选→明确确认→详情→重开」——设备验收 D2 不缩减该路径）；D-146 冻结矩阵（R-Q08-3）；A-04.1 绿裁决（connectedDebugAndroidTest 11/11，X-4 证实，PROGRESS_LOG 2026-09-18 续8）；A-04.1 tracked spec §3.3 预告。本批为产品行为变更（能力可用性翻转）——High-Risk Acceptance Topology。

## Authority And Boundary

- **不可触碰面**：`.external/` 只读；本批不触碰 `CcbBillParser` 与 `CcbSourceTokens`；不动两级读取上限（`IMPORT_FILE_PICK_MAX_READ_BYTES` 16 MiB 与 `CcbSourceTokens.MAX_INPUT_BYTES` 10 MiB）；不动 CI（connected 面保持手动门）；A-04.1 androidTest 直调 parser 零矩阵引用，翻转不影响（评审证实），零触碰。
- **本批不做**：新增 parser/改解析行为/改接治语义/改 D-146 其余矩阵单元。

## 1. 命题与边界

A-04.1 已用设备 instrumented 证据证实 HSSF 在 Android 真实可执行且与 JVM oracle 逐字段等价。本批把该证据兑现为产品可用性：

- **矩阵翻转**：`ImportFormatCapabilities` 中 `CCB_XLS.availability[ANDROID]` 从 `PENDING_DEVICE_VERIFICATION` → `AVAILABLE`（D-146 冻结矩阵中该单元的唯一允许翻转；DESKTOP 单元已 AVAILABLE 不动；其余三格式双平台不动）。
- **产品路径设备验收（A-04.3 并入本批设备窗口）**：真实产品入口 SAF 选 CCB XLS → 解析 → 候选 → 明确确认（写入正式账本）→ 详情 → 重开（计划 :34 全链，不缩减）。

## 2. 交付内容

### 2.1 矩阵翻转最小 diff

- `ImportFormatCapabilities.kt`：ANDROID 单元 → `ImportFormatAvailability.AVAILABLE`（唯一代码值变更）。
- **仅注释授权编辑集（逐一枚举，代码零 diff；A042SPEC-04 吸收）**——以下失准注释更新为翻转后现实（引用 A-04.1 设备证据，不再用悬空 X-4 表述）：
  - `ImportFormatCapabilities.kt` 文件级 KDoc（原 CCB pending 断言）与 `CCB_XLS` KDoc；
  - `ImportFileIntake.kt` PENDING reason KDoc（泛化：值域保留供未来格式复入 pending 态）；
  - `ImportFileIntakeOrchestrator.kt` (a) 门 KDoc 的「CCB XLS on Android」专属表述——改为泛化描述 + 登记该分支当下不可达的保留语义；
  - `app-ui/.../P503ImportReviewPresentation.kt` `importFormatEntries` KDoc；
  - `app-ui/.../P503CatalogManagementScreen.kt` 切换账本诚实后缀注释（泛化）。
- 除上述 6 处注释与 ANDROID 单值外，零其他 main 源集改动。UI 代码零改动（`P503ImportReview.kt` 的不可用条件渲染自然不再出现 pending 行，`P503ImportReviewPresentation.kt` 的 PENDING 文案分支保留——值域保留下 exhaustive when 编译强制，未格式从未删除）。

### 2.2 平台门测试同批修订（A042SPEC-01 吸收）

- `JvmImportFileIntakeJvmTest.kt` 的 `pendingDeviceVerificationFormatIsTypedWithZeroRead`：**删除**（其钉死状态被本批裁决翻转）。同位置新增 `ccbXlsOnAndroidNowDispatchesToTheParser`：CCB 格式 + ANDROID 平台 + `tests/fixtures/batch-bp01-ccb-a.xls` 真实字节（`repositoryRoot()` 机制可达，先例 `CcbBillParserJvmTest.kt:42-48`）→ 走到 parser dispatch（`RecordingIntakePort` 先例 `JvmImportFileIntakeJvmTest.kt:65-88`），不再 FormatUnavailable；期望 = P-49 oracle 的 13 accepted / 5 parser-rejected 分发面（port.snapshots 13 条、PARSER_REJECTED 计 5——改一位会红）。
- **PENDING (a) 门守卫改写（不引入 main 改动）**：翻转后 4 格式 × 2 平台全部 AVAILABLE，(a) 门分支当下不可达（`byIdentifier` 对未知 id 抛 IAE，无注入缝隙——评审已核实 `ImportFileIntakeOrchestrator.kt:71,76-84`；原草案「合成 PENDING descriptor 走 orchestrator」方案作废，评审 MAJOR 成立）。守卫改为三层保留性断言，不改任何 main 源码：
  1. **类型/文案保留**：`typedBatchFailuresCarryTheirFrozenCopy`（`P503ImportReviewPresentationTest.kt:505-543`）PENDING 文案断言原样保留（已存在，本批不动）。
  2. **reason 值域保留**：既有 CHARSET_UNSUPPORTED 家族测试 + `importIntakeFailureText` exhaustive when 编译强制（未删任何分支）。
  3. **tracked spec 登记声明**：(a) 门 PENDING 分支为「当下不可达的保留代码」（防未来格式复用），在本 tracked spec 写明该不可达性与其保留理由（即本节）。
- `P503ImportReviewPresentationTest.kt` 的 `androidShowsAllFourEntriesButCcbXlsIsNeverAvailable`：修订为 `androidShowsAllFourEntriesAllAvailable`（四条目全 AVAILABLE）；`desktopOffersAllFourFormatsAsAvailable` 保持零改动。`P503ImportReviewRenderItemsTest` 只断条目数不断 availability（翻转不破坏，零改动）。

### 2.3 tracked spec

即本文件。隐私钉死（A042SPEC-05 吸收）：不得含 AVD 名/设备身份/本机绝对路径（先例：A-04.1 tracked spec 用「AVD、隔离 adb 端口、归属核实」泛称）；只写清单不写实测值（实测值归本地证据 + PROGRESS_LOG）。desktop 平台不受影响的显式声明：DESKTOP 单元原本已 AVAILABLE，翻转只触碰 ANDROID 单元；desktop 侧由同提交 CI `check` 聚合覆盖（ci.yml:38-39；A042SPEC-07 吸收）。

### 2.4 明确不做

- 不删 `ImportFormatAvailability.PENDING_DEVICE_VERIFICATION` 值域与 `ImportFormatUnavailableReason.PENDING_DEVICE_VERIFICATION` reason。
- 不改 `IMPORT_FILE_PICK_MAX_READ_BYTES`（16 MiB，`app-ui/.../ImportFilePick.kt:6`）与 `CcbSourceTokens.MAX_INPUT_BYTES`（10 MiB）。
- 不动 CI（connected 面保持手动门）。

## 3. 验收

### 3.1 Writer 侧（Gradle 串行）

1. `:ledger-application:jvmTest --rerun-tasks` 全绿（含 2.2 修订；删一测试 + 增一测试，类内计数不变，模块总数以实测为准）。
2. `:app-ui:jvmTest --rerun-tasks` 全绿（presentation 测试修订后计数不变）。
3. `:android-app:assembleDebug` 成功 + 受影响模块 ktlintCheck 零违例 + project_docs exit 0。

### 3.2 主代理设备窗口（AVD、隔离 adb 端口、归属核实；APK SHA 记录）

重建 APK 后逐向量执行并截图/记录（uix.py 先例；设备库现存大量候选与重复记录——各向量以当刻库状态记录基线与结果计数）：

- **D1 格式条目可用性**：导入 Tab 四格式条目，CCB XLS 不再显示「待设备运行验证，暂不可用」，选择文件可点。
- **D2 正常样本产品路径（计划 :34 全链，不缩减）**：
  1. SAF 选 CCB 正常样本（≡ `batch-bp01-ccb-a.xls`，SHA 相同；oracle P-49 钉死 18 行 = 13 accepted + 5 parser-rejected，A042SPEC-02 吸收）→ 结果行「接治完成：新增 13，等价重放 0，解析拒绝 5，接治拒绝 0。」（大库存在同指纹碰撞可能：首导该 fixture 于现库若发生等价重放，如实记录实际结果行并与 oracle 逐项核对差异原因——不事后改口径）。
  2. **计数口径钉准（A042SPEC-11 吸收）**：最近导入会话块显示 18 条逐项结果行（13「已接治（新增候选）。」+ 5「单行无法解析」）；待确认候选列表新增 13 条候选行（5 条 parser-rejected 零写入、永不成为候选）。确认前断言：正式零写入（首页 2026-08 月度与当刻库基线一致——fixture 交易日期 2026-08-25/28，A042SPEC-12 吸收）。
  3. **明确确认（A042SPEC-03/10 吸收）**：先打开目标候选详情 → 补全决策表单（ordinary_flow：分类 + 资金账户，至「决策已补全。」提示，`P503ImportReview.kt:703-704`；空 draft 授权必得「跳过 1 项」——表单补全是「已入账 1 项」的必要前置）→ 勾选该候选（PENDING_USER_DECISION 且 selectable；不可勾选示例为 B08 ordinal 7 VALID_INCOMPLETE，A042SPEC-13 吸收更正）→「进入批量确认」→ 授权页核对快照 → 确认 → 批量结果行「已入账 1 项」→ 列表重读该候选呈已确认（权威回读）。
  4. 点开该已确认候选详情（关键内容可见）→ 返回。
  5. force-stop → am start 重开：候选已确认状态持久、切至 2026-08 月度出现对应交易（如 B1：支出 12.80 CNY，A042SPEC-12 吸收）——正式写入已发生。
- **D3 损坏样本**：SAF 选 CCB 损坏样本 → 结果行「文件解析失败（诊断码 INPUT_DECODE_FAILED），本次导入已中止。」+ 本次会话零新增候选（待确认组计数与导入前基线一致，A042SPEC-08 吸收钉法）。
- **D4 超限样本（双级口径钉准）**：SAF 选 >16 MiB 合成 OLE2 魔数前缀 .xls（d01 工具生成，可通过 MIME 过滤）→ 管线级文案「文件超过读取上限（16 MiB），实际 N 字节；本次未解析、未接治。」；若用 10-16 MiB 文件则触发 parser 级「文件解析失败（诊断码 INPUT_UNSAFE_OR_OVER_LIMIT）」——按实际文件大小预先钉准期望，不事后改口径（两级常量与 D01 先例 #9/#10 一致）。
- **D5 权限/异常路径不在本批**（D01 权限撤回维持 PARTIAL 登记）。
- 全程 logcat 记录 POI/HSSF 异常（预期零）、ANR/OOM（零容忍）。

### 3.3 红绿裁决与拓扑

- 全绿 → merge --no-ff → clean trace → push → 同提交 CI 三 job；A-04.2+A-04.3 记 PASS。
- 任何产品路径向量红 → 回归证伪（与 A-04.1 隔离入口证据矛盾）→ 提交缺陷与风险，不合并。

拓扑：writer（隔离 worktree）→ 规格评审与质量评审（分开两个独立 reviewer）→ distinct verifier → 主代理设备窗口 → 主代理终检合并。

## 4. 风险与纪律

- D2 在大库上执行（隐含复验 A-PERF 修复在产品路径持续有效）；确认后正式写入 1 笔交易进大库不影响后续批（P7-05 修正/作废批次承接）。
- SAF 操作在列表顶部格式区，不受滚动基建限制。
- 隐私：设备截图仅合成数据；本地证据不入 tracked；tracked spec 隐私钉死见 §2.3。
- 单 writer 单 worktree；Gradle 串行（writer 阶段与设备窗口互斥）。
