# 当前状态

## 已完成

- 建立 `ledger-domain`、`ledger-application` 与 `ledger-data` 三个可构建的 Kotlin Multiplatform 共享库模块；账务核心继续保持无 Android、网络、同步或 AI 依赖。
- SQLDelight schema 当前为 v21（v1→v21 共 20 个迁移文件）。v10 以 dedicated normalized owners 保存 RG-06 与 RG-07 operation、relation/lifecycle/installment、source/evidence/candidate 与 reconciliation；v11 保存 RG-06 candidate confirmation provenance；v12 保存 D-082 批准的 RG-09 normalized owners；v13 保存 D-083 批准的 RG-10 normalized owners；v14 从 RG-09 current adjustment owner 移除由 immutable original delta、allocations 与 latest history 推导的重复金额/状态列；v15 保存 D-084 批准的 RG-08 lending owners 并接入 `LEND`/`COLLECT` canonical kinds；v16 保存 D-085 批准的 RG-11 owners 并接入 `PREPAID_PURCHASE`/`PREPAID_RECOGNITION` canonical kinds（含共享 `appendVersion` 与 `transaction_version.confirmation_id`）；v17 保存 RG-12 posting-facts correction owners，并把 confirmation guard 扩展到 `EXPENSE`；v18 保存 D-087 批准的 RG-02 `category_rename` append-only name history（17.sqm）；v19 为 D-088 的 RG-12 guard 修复新边 18.sqm（重挂 rg12 guard trigger，纯 DDL）；v20 为 DATA-001（D-091）的 schema 统一新边 19.sqm（共享 `formal_transaction_metadata` 表，RG-08/09/10 私表精简为 source-only、RG-11/12 私表删除）；v21 为 P4-02（D-098）的共享导入 spine 新边 20.sqm（9 张非 rgXX_ `import_*` 表与不可变/追加-only/状态迁移守卫触发器）。逐版本 populated migration、child FK preservation、原子拒绝/回滚、fresh-v21 与 v1→v21 metadata equality 均有 JVM 测试和 migration verifier。
- `RG-01` create、retry、distinct、7 个拒绝及 `note_update` runtime 已实现。备注替代保留 posting set 与经济事实，并覆盖 replay、request identity conflict、stale CAS 零写入和 operation oracle。完整 state/delta/status 比较已按 `D-087` 实现（`Rg01FullStateOracleTest`，8 roots/11 ops/19 states，merge `f9da4b6`）。
- `RG-02` 完成 `D-071` 批准的 manual-income 最小 slice：create、retry、2 个独立变体与 8 个拒绝；`category_rename` 已按 `D-087` 实现最小闭环（append-only name history，17.sqm）。完整 state/delta/status 比较已实现（`Rg02FullStateOracleTest`，11 roots/13 ops/24 states）。
- `RG-03` 当前冻结范围已实现：13 roots、20 operations 的 outcome、returned IDs、完整 state、deltas 与 status changes 均与 approved expected 精确比较；mapping gate 已 approved，v2 工件已发布（`dec854e`，D-086；13 roots/20 ops/33 states）。
- `RG-04` 的 26 个 raw v1 operations 均有 runtime。全 26 项完整 state/report/reconciliation/delta 比较已合入（`Rg04FullStateOracleTest`，`88c9bfa`，17 roots/26 ops/43 states）。import lifecycle、ownership 与 reconciliation 另有深入持久化测试，approved v2 已发布。
- `RG-05` 的领域、应用与持久化 runtime、schema v8 及相关迁移和测试已经进入共享库。`D-075` 已批准 17 roots、25 operations、42 complete states 的 expected；共享 `GoldenV2Oracle` 与 `Rg05FullStateOracleTest` 对全部 25 operations 比较完整 state、deltas 和 status changes；v2 工件已发布（`dec854e`，D-086；17 roots/25 ops/42 states）。
- `RG-06` 的 Golden Schema v2 契约与语义校验、领域 aggregate、catalog-free validated snapshot rehydration、八 action 应用/原子 commit-port 契约、schema v9 base/v11 migration、SQLDelight adapter、fixture replay 和 publication 已完成。41 个 operation 比较 outcome、returned IDs、完整 state、deltas 与 status changes；发布工件为 `golden/rules-v2/rg-06.json`，manifest 保存 source/expected/canonical/output hashes 与 20/41/61 对象计数。手工付款建立 pending 分录对账；导入候选只在原子精确确认时直接建立 matched 对账；镜像合并不改变对账。
- `RG-07` 的 contract、expected、adapter、fixture replay、schema v10 migration 与 Kotlin runtime 已完成；49 个 operation 比较 outcome、returned IDs、完整 state、deltas 与 status changes，v2 工件已发布到 `golden/rules-v2/rg-07.json`。
- `RG-08` 已有冻结 v1、Python 测试与逐路径 mapping（4969 路径、2964 requires_contract_amendment）；`D-084` 已关闭 4 个 gap（`RG08-GAP-01..04`）并完成完整 lending runtime、44-op oracle 与 schema v15 持久化，mapping gate 已 approved（`rg-08-closure-proposal.md`）；v2 expected 工件（44 ops，accepted 6/no_change 13/rejected 25，18 roots/62 states，SHA-256 `b3434dfc849fffb3c22511577bd0090158a3173d2b1cabfbe6bf9c20d866bca7`）与 publication 已按 `D-089` B 批完成（`golden/rules-v2/rg-08.json`，D-090 LF 验收通过）。`RG-10` 的 13 actions 领域、应用与持久化 runtime、44-op 完整状态 oracle与 schema v13 SQLDelight persistence 已按 `D-083` 批准范围实现并合入 main（`22f3141`），mapping gate 已 approved，closure proposal 已登记，v2 工件已发布（`7cf419a`；44 ops，accepted 12/no_change 10/rejected 22，14 roots/58 states）。RG-09 的 D-082 gaps 已由 closure overlay 标记 closed；50-operation v1 oracle、严格 9-root/50-operation/59-state v2 runtime oracle、D-065 proof、schema v14 migration 与 deterministic publication 均已完成，mapping gate 已 approved。`RG-11`、`RG-12` 为 direct-v2（无 mapping/adapter，expected=冻结契约字节副本，契约批准于 `efbb13a`）；`D-085` 已实现完整 Kotlin runtime、oracle 与 schema v16/v17 持久化，closure gate 已 approved（`rg-11-closure-proposal.md` 与 `rg-12-closure-proposal.md`），v2 工件已发布（`dec854e`；RG-11 22 ops/3 roots/25 states，RG-12 12 ops/3 roots/15 states）。
- golden v2 publication 集合已完整：RG-01 至 RG-12 共 12 case 全部发布（`golden/rules-v2/` 工件 + manifest 登记；RG-01/02 按 `D-089` A 批、RG-08 按 `D-089` B 批，A 批经 `D-090` LF 验收闭合，B 批 gate 与 raw-byte hash 核验通过）。`D-090` publication LF integrity 契约已落地：`.gitattributes` 3 条 EOL 覆盖、共享 pre-publish integrity gate（所有 publisher 无条件接线）、11 case raw-byte hash metadata 重算（canonical 不变）。
- `D-097` 已批准 contract-only P4-01 normalized source、typed diagnostics 与匿名 acceptance，并已登记为表示中立的逻辑 oracle：contract v1 的 complete/partial/rejected、record multiset、完整性与 presence、source/derived rule trace、稳定 diagnostic taxonomy、安全 location、exact decimal/time、九项 synthetic fixtures 和 permutation 规则。P4-01 本身不实现运行时行为；后续实现分别受各批决定约束。
- `D-098` 已批准并交付 P4-02 shared import spine：共享 source/evidence/candidate/confirmation owner、最小 raw identity/retention/provenance、candidate lifecycle 与 atomic confirmation 已进入共享库；schema v21 新增 9 张非 `rgXX_` 的 `import_*` 表，30-operation oracle、迁移、原子失败与重开验证已完成（merge `d756391`）。
- `D-099` 已批准并交付 P4-03 第一标准来源普通收支切片：微信 XLSX parser 采用 Apache POI JVM，fail-closed 解析契约、匿名 synthetic XLSX fixtures 与 P-01～P-21/E-01～E-14 oracle 已通过 shared import spine；该批不创建 matcher/evidence link 或 reconciliation effect（merge `18fae64`）。

## 验证证据

三个 Kotlin 模块的 JVM 测试与 Python 套件在最近一次完整验证中全部通过，零 failure、零 error。逐模块计数随测试增删而变，不在此复制；验证命令见 [README](../README.md)，实际结果以 `build/test-results` 下的报告为准。

- `RG-01`/`RG-02` 完整比较 oracle 验证证据（R3 登记，D-088）：`Rg01FullStateOracleTest`（8 roots / 11 ops / 19 states）与 `Rg02FullStateOracleTest`（11 roots / 13 ops / 24 states）经独立 spec/quality review 双 PASS，distinct verifier 11/11 PASS（D-087，merge `f9da4b6`，2026-08-10 验证记录）；数字以测试文件头注释与 D-087 登记为准。验证记录见 `docs/PROJECT_STATE.local.md`（2026-08-10）。

## 当前环境

- Kotlin Multiplatform 插件版本为 `2.4.10`，Gradle Wrapper 版本为 `9.5.0`，JVM 工具链为 JDK 21。
- 三个 KMP library 的 JVM 测试和根项目 Gradle 检查可在 Windows 上运行；`ledger-data` 的 SQLDelight 迁移验证与 Android target 编译也可独立运行。
- Python 核心测试和文档验证可在 Windows 上运行。
- `ledger-data` 已有 Android 编译目标，但 Android app 与 Desktop app 尚未建立，因此没有应用运行命令。
- SQLDelight `2.3.2` 与 Android system SQLite 已确定用于当前正式持久化边界；当前 schema 为 v21。UI、导航、依赖注入、同步和更广泛查询方案尚未选择。

## 未完成门槛

- `D-075` 只批准 RG-05 expected，不授权 adapter 实现或 fixture 迁移；RG-05 的 v2 发布由 `D-086` 另行授权。
- RG-01、RG-02、RG-08 已全部发布（`D-089` A/B 批；A 批经 `D-090` LF 验收闭合，B 批含 LF gate 与 raw-byte hash 核验）；完整比较证据见 `D-087` 与 `D-088` R3 登记。
- RG-05 完整状态 oracle、RG-06 publication 与 RG-07 closure 不表示全部黄金场景或正式账务核心已完成；RG-01、RG-02 的完整 state/delta/status 比较已实现（`D-087`，`f9da4b6`）且 v2 publication 已按 `D-089` A 批完成；RG-08 的 runtime 与 mapping gate 已按 `D-084` 完成（4 个 gap 已关闭），v2 expected 与工件已按 `D-089` B 批发布。
- 其他 RG 的 v1 rewrite、v2 publication 和未完成完整比较仍分别受其现有 gate 约束；RG-03/05/10/11/12 已按 `D-086` 发布，RG-01/02 与 RG-08 已按 `D-089` 发布（`D-090` LF 验收闭合），12 case 集合完整；RG-09 已发布且历史 gaps 已 closed，mapping gate 已 approved，独立高风险 closure review 证据已记录。
- P4-01、P4-02 与 P4-03 已分别由 `D-097`、`D-098` 与 `D-099` 闭环。P4-04 transfer formalization slice 的轮 A 曾于 2026-08-16 登记批准，但后续质量复核确认 `P404-QUAL-001` BLOCKER（source scale 被误作账户币种精度）、`P404-QUAL-002` MAJOR（完整 report oracle 缺预算与对外现金流）和 `P404-QUAL-003` MAJOR（pre-persist validator 未绑定应用分配的完整 ID 集）。纠偏修订已于 2026-08-17 经两轮独立复审、distinct verification 与主代理重新批准（提交 41c1a8d），轮 B 实施授权恢复、尚未启动。`D-096` 中第二来源 parser、duplicate、matcher、产品 ID 与 Clock 等其余门继续待决；整文件默认不保存，若提议保存则须先批准生命周期、加密、导出和删除。

## 唯一下一步

按重新批准的 D-100 契约（spec Status: approved，2026-08-17）启动 P4-04 轮 B 实施批：独立 worktree 唯一 writer，按规格 §1–§9 实现 ledger-application/ledger-data 源码、schema v21→v22（21.sqm）与测试，并按高风险拓扑复审验证；`D-096` 其余门维持待决。
