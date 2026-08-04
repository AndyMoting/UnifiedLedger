# RG-06 Golden Schema v2 Gate 清单

## 用途与边界

本清单记录 `RG-06` 从冻结 v1 fixture 到 Golden Schema v2 expected、adapter、fixture rewrite 和 publication 的当前闸门状态。`D-081` 已批准本轮用户明确批准的 1～5 gate；本清单仍不新增产品账务行为或 schema 语义。

清单状态以当前仓库文件、测试和正式决定为准。后续实现存在不一致时，先关闭较早的闸门；不得用已经存在的 runtime 或 persistence 跳过 mapping、expected、fixture rewrite 或 publication 的独立授权。

## 当前结论

- 冻结输入与业务答案已经存在，`golden/rules/rg-06.json` 继续作为 v1 来源。
- Golden Schema v2 已有 RG-06 closed definitions、operation validator 和 semantic validator，候选补充了只针对既有 `accepted`/`no_change`/`rejected` 零效果不变量的 append-only outcome gate；Kotlin domain/application/persistence runtime 也已存在。
- normalized path inventory 已闭合：`1188` 个 source paths、`3610` 个 leaf occurrences、`1188/0` classified/unclassified。
- path-map 已完成 closure：`1181` 个 `ready`、`7` 个 `test_only_exclusion`、`0` 个 unresolved contract gaps，`status=approved`、`expected_output_gate=approved`。
- `docs/migrations/golden-v2/rg-06-expected.json` 已批准，包含 `20` roots、`41` operations、`61` states；adapter/replay、fixture rewrite 和 publication 已按 `D-081` 验收，发布候选为 `golden/rules-v2/rg-06.json`。
- `D-081` 已关闭 `RG06-AUTH-01`：现有 schema v9 base、v11 additive migration、runtime/persistence 可作为 adapter/replay、fixture rewrite 和 publication 的验收证据；candidate confirmation 的两个冻结 `confirmed_at` 必须由 v1 provenance 显式提供并持久化。

## Gate 总表

| Gate | 当前状态 | 已有证据 | 关闭条件 | 当前动作权限 |
| --- | --- | --- | --- | --- |
| `RG06-G00` 冻结输入 | 已完成 | `golden/rules/rg-06.json`、RG-06 设计、Golden 测试与 v1 Python tests | 当前 source fixture 通过结构和业务测试；v2 artifact 不回写 v1 语义 | 只读使用 |
| `RG06-G01` contract/schema/validator capability | 已完成 | `docs/GOLDEN_SCHEMA.md`、v2 JSON Schema、RG-06 contract/operation/semantic tests、append-only outcome regression | 现有 closed definitions、operation effects、semantic invariants 与 closure targets 一致；非 accepted 状态不得产生 append-only effect | 允许生成 draft expected |
| `RG06-G02` normalized path-map | approved | `rg-06-mapping.md`、`rg-06-path-map.json`、39 个 mapping tests；`1188/0` classified/unclassified | 5 个 gap 已 `approved_implemented`；全部 entries 为 `ready` 或 test-only；无 planned target；独立复核和 D-081 批准 | 允许作为后续迁移输入 |
| `RG06-G03` expected v2 | approved | `rg-06-expected.json`、deterministic builder、expected regression tests；`20/41/61` | schema/semantic/equivalence 全通过；artifact-level 规格、质量和 distinct verifier 复核；D-081 批准 | 允许作为 adapter/replay oracle，不等同于 publication |
| `RG06-G04` adapter 与 fixture replay | 已完成 | D-081、typed adapter/replay、full-state projector、`Rg06RuntimeReplayTest` | 41 个 operation 的 outcome、returned IDs、完整 state、deltas、status changes；拒绝/retry 原子性；confirmed_at 显式来源和 reopen 恢复 | 只读复核 |
| `RG06-G05` fixture rewrite | 已完成 | D-081、v1 source baseline、approved expected、`rg06_publication.py` 及 3 个失败/幂等/隔离测试 | 临时副本、source/target hash、原子性、恢复、幂等、失败隔离全部通过；v1 未改写 | 只读复核 |
| `RG06-G06` publication | published candidate, clean release pending | D-081；`golden/rules-v2/rg-06.json`；`golden/rules-v2/manifest.json` | clean worktree release verification 通过；manifest 记录 source/expected/canonical/output hashes 和对象计数 | clean release 通过后关闭 |

## Contract Gap 清单

以下数量取自当前 `rg-06-path-map.json`。`affected paths` 可跨 gap 重叠，不能相加后当作独立路径总数。

| Gap | Affected paths | 当前证据 | 关闭证据 |
| --- | ---: | --- | --- |
| `RG06-GAP-01` operation registry | 71 | 八个 action 的 strict accepted/no-change/rejection contract、18 个 fixture invalid replay 和 mapping tests | 已 `approved_implemented`；action、operation class、strict input/attempted input、returned IDs、delta 和 retry identity 均有 candidate evidence |
| `RG06-GAP-02` source/candidate payload | 147 | source/candidate closed definitions、显式 null role、confidence、confirmation binding、manual absence 和 source/evidence redirect tests | 已 `approved_implemented`；source time、candidate history、pending/confirmed ownership 和 provenance 均指向 current owners |
| `RG06-GAP-03` relation/lifecycle/installment topology | 508 | identity-only relation、lifecycle entity、installment payment、formal posting binding、relation and expected tests | 已 `approved_implemented`；relation identity、entity payload、member cardinality、交易/分录引用、金额与时间均闭合 |
| `RG06-GAP-04` status/history | 170 | payment progress、fulfillment、group reconciliation、ordered lifecycle history 与 status mapping tests | 已 `approved_implemented`；三种独立 status owner、history continuity、current projection、zero-effect transitions 和 retry invariants 均闭合 |
| `RG06-GAP-05` evidence/mirror lineage | 72 | source/evidence discriminators、posting-level evidence link、mirror lineage、source-time和 reconciliation tests | 已 `approved_implemented`；原始/镜像 source time、金额币种、exact `payment_asset` target、merge lineage 和零财务副作用均闭合 |

## Authority Trace 待协调项

### `RG06-AUTH-01` D-077 与现有实现范围不一致（已关闭）

已确认事实：

- `D-077` 批准 RG-06 导入对账与领域恢复边界，并明确 `golden/rules/rg-06.json` 保持不变。
- `D-077` 的影响段同时写明 schema v9、decoder、SQLDelight/store/adapter/migration 未获授权。
- 当前 tracked README、CURRENT_STATE、ROADMAP 及代码记录 RG-06 domain/application/persistence 与 schema v9 已存在。

关闭证据：`D-081` 明确批准 RG-06 schema/store/adapter/replay/migration、fixture rewrite 和 publication 的范围，并保留 D-077 的账务边界；candidate confirmation 的 `confirmed_at` 必须来自冻结 v1 provenance，不能由支付时间推导。

## Evidence Reconciliation 生命周期

- 报告路径固定为 ignored 本地文件 `docs/RG_06_GATE_EVIDENCE.local.md`，不进入 Git，不构成产品、账务、schema、mapping 或迁移权威。
- 只有 `docs/PROJECT_STATE.local.md` 精确点名且文件声明 `状态：active` 时才可执行该报告；未点名、marker 缺失或 `archived` 时只能作为历史证据。
- 每轮报告必须记录 base commit，以及 `rg-06-mapping.md`、`rg-06-path-map.json`、Golden Schema、validator tests 和被引用 runtime/persistence 文件的 SHA-256。任一输入变化立即使该轮结论失效。
- 失效报告改为 `状态：archived`；新的报告重新记录身份并由 checkpoint 路由。正式关闭结果只能进入拥有该事实的 mapping、path-map、决定、测试或状态文档，不能从本地报告直接获得 authority。

## 执行顺序

1. 冻结 `rg-06-mapping.md`、`rg-06-path-map.json`、closure proposal、expected、fixture、builder 和 tests 的 candidate identity。
2. 对 frozen candidate 完成独立规格审查、独立质量审查和 distinct verifier；任何 artifact change 使相关证据失效。
3. 主 agent 复核 critical diff，重跑 mapping、expected、RG-06 contract/operation/semantic、`project_docs`、full Python 和 Harness checks。
4. expected 已由 D-081 批准；adapter/replay 必须先冻结候选并完成逐 operation/full-state 比较。
5. fixture rewrite 和 publication 继续分别保留 hash、失败恢复、幂等、失败隔离与 clean release evidence。

## 验证矩阵

| Claim | 最小验证 |
| --- | --- |
| RG-06 contract/operation/semantic capability 当前通过 | `$env:PYTHONPATH="tools\python"; python -m unittest tests.python.test_golden_v2_rg06_contract tests.python.test_golden_v2_rg06_operations tests.python.test_golden_v2_rg06_semantics -v` |
| path inventory、gap references 与 mapping hygiene 当前通过 | `$env:PYTHONPATH="tools\python"; python -m unittest tests.python.test_golden_v2_mappings -v` |
| 正式文档结构和链接有效 | `$env:PYTHONPATH="tools\python"; python -m project_docs .` |
| closure candidate 的完整受影响验证 | 按 `docs/CONTRIBUTING.md` 运行完整 Python tests、`project_docs` 和 `.\gradlew.bat check --rerun-tasks --warning-mode all` |
| publication candidate 可发布 | publication target 明确后，在 clean worktree 按 `docs/CONTRIBUTING.md` 完成全套仓库验证，校验 manifest 的 source/expected/canonical/output hashes 与对象计数，再由 active local Harness 执行其 `verify-project.ps1 -Scope release` 路由 |

Harness release verification 是本地执行路由，不是仓库内工具或产品构建依赖；其脚本位置和环境配置不得写入 tracked 工件。

## 唯一下一动作

冻结包含 published output 与 manifest 的最终候选，完成 artifact-level 规格 reviewer、质量 reviewer 和 distinct verifier closure；随后在 clean worktree 运行 release verification 并关闭 `RG06-G06`。
