# RG-06 Golden Schema v2 Gate 清单

## 用途与边界

本清单记录 `RG-06` 从冻结 v1 fixture 到 Golden Schema v2 expected、adapter、fixture rewrite 和 publication 的当前闸门状态。它只整理已有权威、工件和验证证据，不批准新的产品行为、schema 形状、迁移实现、fixture rewrite 或 publication target。

清单状态以当前仓库文件、测试和正式决定为准。后续实现存在不一致时，先关闭较早的闸门；不得用已经存在的 runtime 或 persistence 跳过 mapping、expected、fixture rewrite 或 publication 的独立授权。

## 当前结论

- 冻结输入与业务答案已经存在，`golden/rules/rg-06.json` 继续作为 v1 来源。
- Golden Schema v2 已有 RG-06 closed definitions、operation validator 和 semantic validator，Kotlin domain/application/persistence runtime 也已存在。
- normalized path inventory 已机械闭合：`1188` 个 source paths、`3610` 个 leaf occurrences、`1188/0` classified/unclassified。
- path-map 仍有 `770` 个 `requires_contract_amendment` dispositions 和 `5` 个 unresolved contract gaps，因此 `status=needs_contract_amendment`、`expected_output_gate=closed`。
- `docs/migrations/golden-v2/rg-06-expected.json` 不存在；adapter、fixture rewrite 和 publication 均未获当前清单授权。
- `D-077` 的影响段仍声明 schema v9、decoder、SQLDelight/store/adapter/migration 未获授权，但当前代码和状态文档记录这些实现已经存在。该 authority trace 必须在把 runtime/persistence 作为迁移验收证据前单独协调。

## Gate 总表

| Gate | 当前状态 | 已有证据 | 关闭条件 | 当前动作权限 |
| --- | --- | --- | --- | --- |
| `RG06-G00` 冻结输入 | 已完成 | `golden/rules/rg-06.json`、RG-06 设计、Golden 测试与 v1 Python tests | 冻结 fixture 持续通过结构和业务测试；不得原地改写 | 只读使用 |
| `RG06-G01` contract/schema/validator capability | 实现存在，验收未闭合 | `docs/GOLDEN_SCHEMA.md`、v2 JSON Schema、RG-06 contract/operation/semantic tests | 5 个 gap 的现有 contract capability 与 mapping targets 逐项核对；独立规格与质量审查通过 | 允许只读核对；不允许据此生成 expected |
| `RG06-G02` normalized path-map | 机械盘点完成，gate 未开 | `rg-06-mapping.md`、`rg-06-path-map.json`；`1188/0` classified/unclassified | 5 个 gap 关闭；全部 `requires_contract_amendment` paths 按批准契约改为可执行 disposition；gap references 双向闭合；独立审查和明确批准 | 允许编写 closure proposal；未经批准不得改 gate 状态 |
| `RG06-G03` expected v2 | 未开始，gate 关闭 | 当前无 `rg-06-expected.json` | `G01`、`G02` 关闭后生成 deterministic `draft_for_review`；schema/semantic/equivalence 全通过；独立审查和用户明确批准 | 不允许生成或批准 |
| `RG06-G04` adapter 与 fixture replay | 未授权 | Kotlin runtime/persistence 仅作现有实现证据，不等同于 v1-to-v2 adapter 或 replay 授权 | approved expected 存在；adapter/replay 边界单独获授权；使用现有 runtime/persistence 作为验收证据前关闭 `RG06-AUTH-01`；逐 operation 比较 outcome、returned IDs、完整 state、deltas、status changes；拒绝与 retry 原子性通过 | 不允许实现或宣称完成 |
| `RG06-G05` fixture rewrite | 未授权 | v1 fixture 保持原样 | approved expected 与 adapter/equivalence 通过；rewrite target、原子性、恢复、幂等、失败隔离和 hash 审计获明确授权并验证 | 不允许 rewrite |
| `RG06-G06` publication | 未授权 | `golden/rules-v2` 当前只有 RG-04、RG-07 和 manifest | publication target 单独明确；clean worktree release verification 通过；manifest 记录 source/expected/canonical/output hashes 和对象计数 | 不允许发布或修改 manifest |

## Contract Gap 清单

以下数量取自当前 `rg-06-path-map.json`。`affected paths` 可跨 gap 重叠，不能相加后当作独立路径总数。

| Gap | Affected paths | 当前证据 | 关闭证据 |
| --- | ---: | --- | --- |
| `RG06-GAP-01` operation registry | 71 | 八个 action 的 strict accepted/no-change/rejection contract 和 operation validator tests 已存在 | 对照所有 71 个 mapping entries 核验 action、operation class、strict input/attempted input、returned IDs、delta 和 retry identity；形成 closure proposal 并通过独立审查 |
| `RG06-GAP-02` source/candidate payload | 147 | staged-payment source/candidate closed definitions、显式 null role、confidence、confirmation binding 和 semantic tests 已存在 | 对照 147 个 entries 核验 source time、candidate history、pending/confirmed ownership、manual source absence 和 provenance；不得从 candidate status 推导正式授权 |
| `RG06-GAP-03` relation/lifecycle/installment topology | 508 | identity-only relation、lifecycle entity、installment payment、formal posting binding 和 rehydration tests 已存在 | 对照 508 个 entries 核验 relation identity、entity payload、member cardinality、交易/分录引用、金额与时间；不得把业务 payload 复制到 relation |
| `RG06-GAP-04` status/history | 170 | payment progress、fulfillment、group reconciliation 与 ordered lifecycle history 的 schema/semantic tests 已存在 | 对照 170 个 entries 核验三种独立 status owner、history continuity、current projection、zero-effect transitions 和 retry invariants |
| `RG06-GAP-05` evidence/mirror lineage | 72 | RG-06 source/evidence discriminators、posting-level evidence link、mirror lineage 和 reconciliation tests 已存在 | 对照 72 个 entries 核验原始/镜像 source time、金额币种、exact `payment_asset` posting target、merge lineage 和零财务副作用 |

## Authority Trace 待协调项

### `RG06-AUTH-01` D-077 与现有实现范围不一致

已确认事实：

- `D-077` 批准 RG-06 导入对账与领域恢复边界，并明确 `golden/rules/rg-06.json` 保持不变。
- `D-077` 的影响段同时写明 schema v9、decoder、SQLDelight/store/adapter/migration 未获授权。
- 当前 tracked README、CURRENT_STATE、ROADMAP 及代码记录 RG-06 domain/application/persistence 与 schema v9 已存在。

关闭条件：在把现有 runtime/persistence 用作 adapter/replay、fixture rewrite 或 publication 的验收证据前，明确记录其 authority 状态。可接受的结果只有两类：正式决定确认其批准范围；或者明确把它们标为非迁移 gate 证据并重新走适用审查。该项不阻塞只依赖已批准 contract/schema/validator 的 path-map closure 或 draft expected 生成；清单不替用户选择其中一类。

## Evidence Reconciliation 生命周期

- 报告路径固定为 ignored 本地文件 `docs/RG_06_GATE_EVIDENCE.local.md`，不进入 Git，不构成产品、账务、schema、mapping 或迁移权威。
- 只有 `docs/PROJECT_STATE.local.md` 精确点名且文件声明 `状态：active` 时才可执行该报告；未点名、marker 缺失或 `archived` 时只能作为历史证据。
- 每轮报告必须记录 base commit，以及 `rg-06-mapping.md`、`rg-06-path-map.json`、Golden Schema、validator tests 和被引用 runtime/persistence 文件的 SHA-256。任一输入变化立即使该轮结论失效。
- 失效报告改为 `状态：archived`；新的报告重新记录身份并由 checkpoint 路由。正式关闭结果只能进入拥有该事实的 mapping、path-map、决定、测试或状态文档，不能从本地报告直接获得 authority。

## 执行顺序

1. 按上述生命周期记录 `rg-06-mapping.md`、`rg-06-path-map.json`、Golden Schema/validator tests 和可选 RG-06 runtime/persistence evidence 的 commit/hash，逐 gap 生成只读 reconciliation 报告。
2. 依据已批准 contract/schema/validator 提出 5 个 gap 的 mapping closure candidate；不得在证据核对阶段修改 fixture、expected、schema 或 runtime。`RG06-AUTH-01` 可并行协调，但只有使用 runtime/persistence 作为后续验收证据时才是前置条件。
3. 对 closure candidate 分别完成独立规格审查、独立质量审查和独立验证；任何修改使旧审查证据失效。
4. 只有 gap 数为零、全部 path dispositions 与当前批准契约一致且 mapping gate 明确批准后，才请求生成 `draft_for_review` expected。
5. expected 获独立审查和用户明确批准后，分别请求 adapter/replay、fixture rewrite 和 publication 权限；三者互不自动授权。

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

先在 `docs/PROJECT_STATE.local.md` 精确登记 `docs/RG_06_GATE_EVIDENCE.local.md`，创建带 `状态：active`、base commit 和输入 SHA-256 的 ignored 报告；随后执行 `RG06-GAP-01` 至 `RG06-GAP-05` 的只读 evidence reconciliation，同时保持 `expected_output_gate=closed`。
