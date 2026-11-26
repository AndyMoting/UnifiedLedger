# 当前状态

## 现在

- 阶段 8（Android 功能与界面深化）已按 `D-367`（2026-11-26）收口；规划顺序为 P8-00～P8-07（D-300）。
- P8-00 视觉与导航基线的规格已按 D-301 批准，D-305 以验收向量 8/8 收口；仅余 TalkBack 仅取语义树一项。
- P8-01a 的四个实施批已交付，V-10 由 CI 模拟器上的 instrumented 链路关账；P8-01b 已按 D-309 批准并交付（PR #305 squash = `254a21d7`）。
- P8-02 已按 D-311 收口：V-1…V-12 全 PASS 含披露。
- P8-03 已按 D-313 收口：V-1…V-19 全 PASS 含披露。
- P8-04 已交付（六批）；P8-05 已交付（设计门 D-331 + 三批）。
- P8-06 已按 `D-364` 收口：V-1…V-19 全 PASS（V-12 于 2026-11-24 由设备 run `45233075019` 取证）；批 1–6、报表/日历呈现、身份与余额修订均已落地，最后落地 `412c9dbc`。
- P8-07 已按 `D-367` 收口：三条跨切片流程、Q3 补证与 Q5 两组规模读数均已在固定候选上取得（固定候选身份、八组读数与残余见 `D-367`）；设计批准仍按 `D-363`，与阶段验收分列。
- schema 当前为 v38；v1→v38 共 37 个迁移文件。
- golden v2 的 RG-01～RG-12 共 12 个 case 已全部发布（`golden/rules-v2/`）。
- 阶段 7 已于 2026-11-05 按 D-296 收口，验收向量账 59 / 62 ≈ 95.2%。

## 验证证据

四个 Kotlin 模块的 JVM 测试与 Python 套件在最近一次完整验证中全部通过，零 failure、零 error。逐模块计数随测试增删而变，不在此复制；验证命令见 [README](../README.md)，实际结果以 `build/test-results` 下的报告为准。

- `RG-01`/`RG-02` 完整比较 oracle 验证证据（R3 登记，D-088）：`Rg01FullStateOracleTest`（8 roots / 11 ops / 19 states）与 `Rg02FullStateOracleTest`（11 roots / 13 ops / 24 states）经独立 spec/quality review 双 PASS，distinct verifier 11/11 PASS（D-087，merge `f9da4b6`，2026-08-10 验证记录）；数字以测试文件头注释与 D-087 登记为准。验证记录见 `docs/PROJECT_STATE.local.md`（2026-08-10）。

## 当前环境

- Kotlin Multiplatform 插件版本为 `2.4.20`、Gradle Wrapper 版本为 `9.5.0`、JVM 工具链为 JDK 21；Compose Multiplatform `1.12.0`、Android Gradle Plugin `9.3.1`（D-141 技术栈升级批）。
- 四个 KMP library 的聚焦 JVM 测试可在 Windows 本机运行；聚合 `check`、ktlint、migration verifier 与 Android/KMP 编译归 CI（验证分工见 `docs/CONTRIBUTING.md`「本机与 CI 的验证分工」）。
- 当前 16 GB Windows 主机上 Gradle/Kotlin 验证必须串行，使用单 worker 和 1 GB heap，并在每次运行后停止 Gradle daemon；具体命令见 `docs/CONTRIBUTING.md`。
- Python 核心测试和文档验证可在 Windows 上运行。
- `ledger-data` 已有 Android 编译目标；共享模块现含新增 `app-ui`（P5-03，androidTarget + jvm，compose）；`desktop-app` 与 `android-app` 两个组合根模块按 P5-02 建立、按 P5-03 只保留平台入口与组合根装配（共享 UI 由 `app-ui` 承载）。桌面应用经 `.\gradlew.bat :desktop-app:run` 启动；Android 调试 APK 经 `.\gradlew.bat :android-app:assembleDebug` 构建（本机 R-9 OOM 时经 CI artifact 获取）；模拟器安装/启动人工门已于 2026-08-30 关闭。
- SQLDelight `2.3.2` 与 Android system SQLite 已确定用于当前正式持久化边界；当前 schema 为 v38。CMP 栈与桌面驱动已确定（D-117），导航、依赖注入、同步与更广泛查询方案仍未选择。

## 未完成门槛

- `P706-A06` 真实掉电腿：需真机的验收例外，保持 BLOCKED/SIMULATED，不转 PASS。
- A03-8b 会话内瞬态读失败→重试缺证（D-275 第 2 条：终态，不记 PASS、分母保留、不排期）。
- P8-01a：V-10 证明端口链路与账务不变量，不证明屏渲染与导航。
- P8-04：V-6 设备链缺确认腿与写出失败类型化；V-7 只取得 1,000 行设备读数，maximum 档未取得。
- P8-05：已交付但未无条件收口——V-8 TalkBack 语音腿（registry R2）与 V-9 真机 Excel/WPS 打开腿（registry R1）各缺一项设备证据。
- P8-06：无阻断门槛（19/19 按 `D-364` 收口）；残余（索引、快照边界、期间控件歧义、单连接语句数、日历色阶与性能阈值）见 `D-364` 残余段，均不并入 PASS。
- P8-07：无阻断缺证；`P8-05` R1/R2 为已登记例外（缺证、不记 PASS；取得合格真机证据后按原向量闭合，见 `D-367`）。
- A-PERF 遗留观察：`OBS-APERF-INPUT-FREEZE` 与 50k 冷读 31.62 s 无闭合记录（见归档「未完成门槛」旧条目）。

## 唯一下一步

P8-07：按 [验收设计](specs/2026-11-22-p8-07-stage-acceptance-design.md) 与 `D-363` 启动阶段验收，实施尚未开始；派发规则见 `docs/PROJECT_STATE.local.md`。

## 历史

历史记录见 [状态历史归档](ARCHIVE_STATUS_HISTORY.md)。
