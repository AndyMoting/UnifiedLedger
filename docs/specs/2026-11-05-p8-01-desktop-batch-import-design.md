# P8-01 桌面批量导入入口与流程设计（阶段 8 第一件）

状态：proposed
Revision: v0.3（2026-11-05 按独立评审重写：吸收了 PB-1…PB-5、PM-1…PM-8、PN-1…PN-2；v0.1/v0.2 的接口形状作废）

**依据与勘察（实读，含评审修正）：**

- `docs/ROADMAP.md:76-81` 阶段 8 完成条件；`:90-92` 阶段顺序约束。
- **批量入口真的不存在**：`desktop-app/.../ImportFilePick.kt:98-118` 是 `JFileChooser.FILES_ONLY` + `isMultiSelectionEnabled = false`，返回 `File?`；另一个桌面选择器（恢复预检，`DesktopRestorePreflightPorts.kt:88`）也是单选；无拖放、无 CLI 导入入口。
- **选择端口是 fire-and-forget**：`interface ImportFilePickPort` 只有 `launch`（`app-ui/.../ImportFilePick.kt:121-124`），且契约明写结果「flows back through the composition root's result channel as `ImportFilePickResult`, **never through this method's signature**」（同文件 `:101-109`）。桌面结果路径只有那条通道：`onResult = importPickChannel::deliver`（`Main.kt:931-937`）→ `importPickResultChannel`（`Main.kt:1129`）→ 宿主唯一入口 `handleImportPickResult`（`P503App.kt:2002-2029`）。
- **桌面跑的是共享 `P503App`**（`Main.kt:346`），IMPORT 页也是共享 app-ui（`P503ImportReview.kt:389-403`，唯一 pick 钩子是 `onStartFilePick(ImportFormatId)`）。
- **「接洽单飞」是拒绝不是排队**：`importIntakeInFlight`（`P503HostCoordinator.kt:224`）对第二次 `Picked` 返回 `AlreadyInFlight`（`:560-576`），宿主给可见 Busy、文件被丢弃（`P503App.kt:2026`、`P503HostCoordinator.kt:940-946`）；标记只在宿主的 landing hop 清除（`P503App.kt:1954-1957`）。
- **格式是显式用户选择、单一槽位、用后清空**：`startImportFilePick` 里 `pendingImportFormat = format`（`P503App.kt:1885-1891`），首次使用时读取并清空（`:1914`、`:1926`），空格式在非分享路径**静默 return、不 dispatch**（`:1918-1925`）；且冻结条款禁止从文件名/路径/内容推断格式（`ImportFormatCapabilities.kt:31`）。
- **重复组卡的键不是元组**：`comparison_fingerprint` 永不作为分组键（P704SPEC-12），处置组句柄是来源的 `input_ref`（`Ledger.sq:9431-9433`、`ImportReviewReadPort.kt:21-26`）；`EXACT_BUSINESS_TUPLE` 是**候选种类**（`ImportSpine.kt:124`），不是元组。
- **共享面的既有形状可复用**：`ImportIntakeRecordDisposition` 的四类计数（`ImportFileIntake.kt:120-132`）与页面文案（`P503ImportReviewPresentation.kt:200-206`）；A-05 的设备读数（`DECISIONS.md:6649`）。
- **分享导入的多流是「拒绝」不是资产**：`ACTION_SEND_MULTIPLE` → `MULTIPLE_STREAMS`（`ExternalNavigationIntake.kt:204-210`），列表 `EXTRA_STREAM` → `MULTIPLE_STREAMS`（`:211-219`）。v0.1 把它写成「可借用的多文件语义」是错的，v0.3 删除。

**设计（v0.3）**

### 1. 批次怎么进宿主（PB-1）

- **不新增返回值端口**。批次作为**共享结果类型的一个新成员**流动：`ImportFilePickResult.PickedBatch(files: List<PickedImportFile>, source: PickSource, format: ImportFormatId)`，经同一条 `ImportFilePickResultChannel` 交付。
- `ImportFilePickPort.launch` 签名**逐字节不变**；桌面根只把新的多选对话框接到现有的 `onResult` 通道上。
- 批次入口**在共享 IMPORT 页按 `platform == DESKTOP` 门控**（该页已收到 `platform`，`P503ImportReview.kt:392`）；Android 侧行为与文案**完全不变**。

### 2. 队列与准入（PB-2）

- **准入一批一次**：批首在宿主的 landing hop 取一次 admission，批内第 k 个文件接洽完成（`ImportFileIntakeResult` 落地）后再起第 k+1 个；**队列属主 = 共享 `P503App`（平台门控）**，不是桌面本地驱动。
- 由此「第二个文件被 Busy 丢掉」**在构造上不可能发生**——那正是 v0.1 的缺陷。
- **批内重入**：批次进行中用户再次点导入 → 拒绝并给可见 Busy（沿用既有「Busy 可见、不静默」纪律）；不排队、不打断。

### 3. 格式契约（PB-3）

- **批次是同格式的**：用户在某个格式入口（例如「支付宝账单（CSV）」）发起的批次，**该格式就是整批格式**；目录选择入口也因此**按格式各提供一个**，不做全局目录入口。
- 每个文件接洽前**重新 seed `pendingImportFormat`**，避免「首文件消费后清空」导致的静默丢弃。
- 空格式不再是静默 return：**类型化可见失败**（`IMPORT_FORMAT_UNAVAILABLE` 级文案），并计入该文件失败。
- **永不从扩展名/路径/内容推断格式**（冻结条款）。

### 4. 文件级失败的真实分类（PM-1）

用真实失败族，不用 v0.1 的三分法：

| 族 | 来源 | 用户可见语义 |
|---|---|---|
| 格式不可用（闸门，零读取） | `ImportIntakeBatchFailure.FormatUnavailable` | 该文件未读取 |
| 超上限（10,000 记录） | `BatchExceedsLimit`（`ImportFileIntake.kt:54,71-77`） | 该文件被整份拒绝，零写入 |
| 解析拒绝 | `ParserRejected` | 该文件零写入 |
| **存储中途失败** | `StorageUnavailable`（`:95-108`、`:173-183`） | **必须报「已提交前 N 条，第 M 条中止」**，不得报「该文件未写入」，也不得承诺重试必成 |
| 读侧失败 | `ExceedsLimit` / `ReadFailed`（`P503ImportReviewPresentation.kt:212-216`） | 读取阶段失败，零写入 |
| 域内拒绝 | `Rejected(failure)`（**不带记录列表**） | 计数需另读（见下） |

- 因为 `Rejected` 不带记录，四类计数的数值**由会话作用域的账本读**提供（`input_ref` → 候选/回执计数），不假装从结果里能拿到。

### 5. 批内顺序（PM-4）

- 排序键 = **端口处的绝对路径**（转成 `PickedImportFile` 之前）；`PickedImportFile` 本身不含路径（`:53-57`）。
- **顺序有语义**：重复检测是账本级（`Ledger.sq:8711-8716`），因此**排序靠后的文件**承载跨文件重复候选。规格明写这条不对称。
- 回执里的可观测顺序 = **`input_ref` 序列**（显示名/路径不得作为身份或持久化，D06）。

### 6. 跨文件重复视图（PB-4）——只读、非处置

- 聚合键 = **元组投影**（规范化 JSON，排除两侧来源 id，`ImportContentFingerprint.kt:61-81`），**仅用于只读视图**。
- **明写**：它不是分派/确认的分组键；`input_ref` 仍是唯一处置句柄；该视图**不可处置、不可确认**。
- 需要一条新读：把重复行连到 possible-existing source 的 `input_ref`，才能回答「这个重复是否落在本批的某个会话里」。
- **范围决定**：本视图**移出 ① 实施批**，并入 ③（差异处理与可追溯结果）——它依赖新读且与 ① 的入口/流程无耦合。

### 7. 整批回执（PB-5 / PM-8）

- **选零 schema 方案**：回执**只活在本会话**，以 `input_ref` 为每文件键；**删除 v0.1 的「重开不丢」验收**（导入脊没有批次分组，`Ledger.sq:8048-8330`；持久批次身份需要新表/新列 + schema 版本 + 迁移 + 独立设计门，留作 ③ 的候选）。
- 回执**在宿主侧累积**（`P503App`/协调器字段），不依赖 reducer 状态：`ImportFileIntakeResult` 只在 `OverviewEmpty` 生效、其余状态被吸收（`P503UiEvent.kt:452-458`），而批确认进行中的状态是 `ImportBatchSubmitting`（`P503App.kt:2661-2669`）——靠 reducer 存会在那个窗口丢行。
- **与批确认的互锁**：批导入进行中**禁用**批确认入口，反之亦然；两者是独立单飞标记，规格明写「不交错」。

### 8. 选择器与目录入口（PM-2 / PN-2）

- **新增**一个多选对话框函数（`getSelectedFiles`），**既有 `showSwingOpenFileChooser` 保持单选不变**——否则共享单文件路径会「返回第一个、静默忽略其余」，是给旧入口引入缺陷。
- 目录入口：只取**顶层**，扩展名精确集合 `csv` / `xls` / `xlsx`，**大小写不敏感**；列表按绝对路径字典序；不递归、不跟随符号链接。

### 9. 成本上界（PM-3）

- 每次接洽都会跑一次**全库 `ANALYZE;`**（`Main.kt:1135-1138` → `QueryStatisticsOptimize.kt:107-112`）。N 个文件 = N 次全库分析 + N 次列表重读 + 最多 N×10,000 条记录。
- **规格给出批上界**：单批 **≤ 20 个文件**（超出时入口直接拒绝并给可见文案），并把「N 次全库分析的成本」写成实施批的**测量义务**，不是「可能有点慢」的审美描述。

### 10. 共享面增长的边界（PM-7）

可检验的边界，替代 v0.1 那条做不到的「不改共享 UI」：

- 共享 app-ui **只增**：① 一个带默认实现的 `ImportFilePickPort` 成员（Android 不实现也照常编译、行为不变）；② 一个 `ImportFilePickResult` 成员；③ 一处 `platform == DESKTOP` 门控的 affordance。
- Android 的 pick 通道测试必须**原样通过**，不得修改断言。

**验收（① 实施批；每条都点名可观测面）**

1. **顺序**：选 3 个文件 → 回执里 `input_ref` 序列 === 端口处绝对路径字典序（测试注入两个已知路径证明）；
2. **文件级失败隔离**：中间文件用坏夹具 → 它的失败族正确、其余两文件接洽摘要齐全、**已接洽条目保留**；
3. **重选等价重放**：同一文件二次导入 → 候选总数不变（request identity 语义）；
4. **格式不推断**：把 `.csv` 放进「微信账单（XLSX）」批 → 该文件以「格式不可用/解析拒绝」失败，不静默；
5. **目录入口**：夹具含子目录 → 只取顶层三个文件，子目录零读取；
6. **上限**：21 个文件的批次被入口拒绝并给可见文案；
7. **互锁**：批导入进行中点批确认 → 入口不可用（反之亦然）；
8. **Android 不变**：Android pick 通道套件**未改动**且绿；
9. **测量义务**：给出 20 文件批的总耗时与「全库 ANALYZE 次数 = 文件数」的证据。

**非目标**

- 不做目录递归、通配符、压缩包；不做后台/定时导入；不做并行（先正确后快）。
- **不做跨文件重复视图与持久化批次回执**（两者移入 ③，见 §6/§7）。
- 不推断格式；不把显示名/路径当身份或持久化。

**残余（登记）**

1. 真实 Swing 选择器**从未有测试覆盖**（`DesktopImportFilePickPortTest.kt:20-26`：CI 无显示）。实施批必须为**多选选择器**与**目录列举器**各加一个可注入 seam 并做无头断言——「断言恰好还绿」在这里是失败模式，不是标准。
2. `StorageUnavailable` 的「已提交前 N 条」语义需要实现批明确从账本读回，不得从失败对象猜。
3. 多项目混合成功/失败的确认路径仍无设备证据（D-295 残余 3），批回执会坐在那条路径上——实施批不得声称它已被证明。
4. v0.1/v0.2 的接口形状（返回 `PickedImportFiles` 的端口方法、`deliver` 循环）**已作废**，实现批若照旧稿写会直接撞 PB-1/PB-2。

**关联：** D-296（阶段 8 入口核对与启动序）、D-297（设计门）、D-280/D-284（批内重复组卡与 B6 门，键规则来源）、D-295（intake 四类计数与设备读数）、D-298（本 v0.3 的登记）。
