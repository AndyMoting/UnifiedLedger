# P8-05 批 c 设备证据登记簿（V-8 TalkBack 语音腿 / V-9 真机编辑器腿）

**状态：** active（2026-11-16，批 c 实施登记）。本文是规格 `2026-11-15-p8-05-readable-export-design.md` §6.3 设备证据登记纪律（P8-04 规格 :123 先例）的独立登记文件——**规格本体一字不动**，登记项按「写出器扩 part、契约不变」同族纪律落在这里。

**依据：** 任务书批 c 落点 3（Excel/WPS 兼容腿按 §6.3 登记纪律处理）；D-331 批 c 实施批（本批）。

---

## R1. V-9 真机 Excel/WPS 可打开腿 → 登记为后续设备证据项

**登记内容：** 「产物在设备 Excel/WPS 可打开且行数/列数正确」的真机编辑器腿（V-9 的完整证据形态）**未在本批落地**，登记为后续设备证据项。

**本批先钉的 CI 腿（已完成）：** `P805ReadableExportDeviceChainInstrumentedTest#theSafFullChainExportsCommitsAndStreamsTheExactRows` 在设备上以生产 `BoundedXlsxReader`（内容解析打开器先例，`javax.xml.parsers` SAX + `java.util.zip`，双平台 JDK 面）对提交产物做结构化打开 round-trip：CI 腿断言的 sheet/行/列/NUMERIC/逐格事实（行数 = 表头 + N、列数恒 10、D 列 `CellType.NUMERIC`、inlineStr 文本逐格相等；六 part 恒等断言在批 a 的 `ReadableLedgerXlsxWriterJvmTest`）——产物结构被证明可被内容解析打开（§6.3「CI 腿先钉产物结构可被内容解析打开」的钉腿形态）。

**缺口（登记的后续项）：** 真机 Excel/WPS 编辑器打开产物（应用可启动、工作簿可渲染、行数/列数在编辑器 UI 中正确）需要设备侧装有 Excel 或 WPS 的真机证据；CI AVD（API 36 google_apis）不带任何电子表格编辑器，仓库亦无第三方应用驱动的仪器化先例。后续落地路径 = 真机（用户设备或专用测试机）手工/半自动打开 `unifiedledger-export-*.xlsx` 产物，截图/朗读记录入证据；登记项随 P8-05 收尾批或独立设备证据批闭合。

**写出器扩 part 纪律（§6.3 预案）：** 若真机编辑器要求 `styles.xml` 等额外 part 才可打开，按「写出器扩 part、契约不变」处理——`ReadableLedgerXlsxStreamWriter.STATIC_PARTS` 扩表 + `ReadableLedgerXlsxWriterJvmTest` 的「part 清单恒等六 part」断言同步更新，列契约/结果族/入口接线零改动；该次扩面落批 c 后续修，不回改本批已钉的六-part 断言（届时断言数字随断言本体一起改）。

**当前风险面（§6.1 既有估算）：** inlineStr + 数值单元格混合包是 ECMA-376 最小合法形态；`BoundedXlsxReader`（双平台 JDK 面）与 JVM 模板生成器产物（已在产、同族 inlineStr/sharedStrings 语义面）为其可打开性提供了离线证据下限。真机编辑器证据是 V-9 的完整闭合，不是本批 CI 腿的前置。

## R2. V-8 TalkBack 语音腿 → CI 结构腿已钉，宣告/朗读证据登记为后续设备证据项

**登记内容：** 「横幅 liveRegion 宣告、进度行可朗读」的 TalkBack **语音**证据（TalkBack 开启时的 utterance log）未在本批落地，登记为后续设备证据项。

**本批先钉的 CI 结构腿（已完成）：** `P805ReadableExportDeviceChainInstrumentedTest#theReadableExportSurfaceMarkersArePresentInTheAccessibilityTree` 在真 Activity + 真组合根上以平台 a11y 树断言：抽屉入口（menu contentDescription `打开菜单` → 导出账目 wired 行可点、无 暂未开放 后缀）、导出面全部冻结标记（标题、U4 用途说明、D-E11 一致性声明、开始导出/取消导出/返回 均可见可点可启）、跑前无任何类型化横幅（D-E10 诚实空态）。结构面是 TalkBack 可朗读的前提（每个标记都在树上 = 屏幕阅读器可达）。

**缺口（登记的后续项）：** 两个语义证据未在 CI 语音面证明：
1. **liveRegion 宣告**：横幅文本变化时 TalkBack 主动播报（Compose `liveRegion = Assertive` 的系统行为，`P503ReadableExportScreen.kt` 已按备份面先例接线）；
2. **进度行可朗读**：`正在导出… N / M 笔`（`liveRegion = Polite`，逐页刷新）被 TalkBack 读出。

**后续落地路径：** 沿 P7-09 A-03/A-VIS 的 host 侧先例（`A11yFocusProbeService` + TalkBack 开启的真机 session，`adb logcat -s ULA11Y` 取 utterance log；TalkBack 开启时 instrumentation 连接语义见 `ImportScaleTraversal` 类注释——需 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` 形态的连接共存），在用户真机上跑一次导出成功/失败流程，取 TalkBack utterance log + 截屏入证据，登记横幅宣告与进度朗读两项语音证据。

**1.3× 字号腿：** 规格 V-8 的「1.3× 字号不溢出」同族出口条件由 shell 批的 drawer 行 `heightIn(min = 48.dp)` 防裁断（`P503TabShell.kt` V-8 注释）承载本批相关面；导出面整页 `verticalScroll`（`P503ReadableExportScreen.kt`）保证放大字号可滚动可达。专门的 1.3× 设备证据沿用 P8-00 出口条件既有登记路径，不在本批重复登记。

---

## 登记台账

| # | 向量 | 本批落地 | 登记缺口 | 闭合路径 |
| --- | --- | --- | --- | --- |
| R1 | V-9 设备 Excel/WPS 可打开 | CI 内容解析 round-trip 钉腿（sheet/行数/列数/数值格） | 真机编辑器打开证据 | 真机手工/半自动打开产物 + 证据记录（后续批） |
| R2 | V-8 TalkBack 宣告/朗读 | CI a11y 树结构腿（入口/标记/空态） | liveRegion 播报 + 进度朗读 utterance log | 真机 TalkBack session + `ULA11Y` 形态 transcript（后续批） |

**关联决定：** D-331（P8-05 设计门）；规格 §5 V-8/V-9、§6.3（兼容性无本地测试可钉；设备证据登记纪律 + P8-04 规格 :123 先例）。
