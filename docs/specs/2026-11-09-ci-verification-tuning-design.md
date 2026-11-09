# CI 验证调参设计（PR 侧范围、合并后去重、设备抖动路由）

状态：approved（2026-11-09 用户批准立项计划；实施范围 = T1–T5 分批落地，T6 保留现状并标注 historical，T7 仅评估）

日期：2026-11-09。批准依据：用户 2026-11-09 批准「CI 调参立项」计划（会话内），计划含 T1–T7 的推荐结论、成功判据与批次划分。本规格是该立项的范围、判据与不变量唯一记录处。

**本规格性质**：只冻结 CI 验证路由的范围与判据。产品代码、账务语义、schema、迁移均不在范围内。命令级事实（本地/CI 验证分工、job 名单、执行命令）只维护在 `docs/CONTRIBUTING.md`，本规格不复述。

**关联决定**：D-190（单遍 CI 与合并树守卫）、D-197（Android instrumented 设备信号）、D-200/D-201（scale preflight 与 APK 来源）、D-302（本规格的登记决定）。

---

## 1. 目标与成功判据

在不减少任何代码改动 PR 的验证面、不改动四个必过检查名的前提下，降低等待时间、红噪音与合并后重复算力。全部判据可测：

| # | 判据 | 现状基线 | 目标 |
| --- | --- | --- | --- |
| G-1 | 代码 PR 执行面 | 10 job，wall clock ≈9.0 min | 不减少、不变慢 |
| G-2 | 纯文档 PR | 10 job，≈9.0 min | 4 job，≤3.0 min |
| G-3 | 每次 squash 合并的 CI 全量次数 | 2（PR run + 守卫派发）| 1 |
| G-4 | scale preflight 假红进入 PR | 22% | 0 |
| G-5 | Windows 取消空跑占比 | 82% | 0 |
| G-6 | 四个必过 context 缺席次数 | 0 | 0 |

## 2. 实测基线

采样：2026-11-09，`gh run list --limit 300` 聚合 + 单 run 的 job 起止时间。G-2 的耗时由单 run 分 job 数据推得，非独立测量。

| workflow | 运行 | 红 | 取消 |
| --- | --- | --- | --- |
| CI | 131（`pull_request` 77 / `workflow_dispatch` 54）| 14 | 8 |
| Merge tree guard | 52 | 0 | 0 |
| Android scale preflight | 45 | 10（22%）| 4 |
| Android instrumented tests | 40 | 4 | 3 |
| Windows JVM tests | 17 | 0 | 14（82%）|
| 手工 rig（B6 8 / ENOSPC 6 / scale long 1）| 15 | 11 | 0 |

单次代码 PR（10 job，总 ≈9.0 min），最长五项：`ledger-data shard 2` 8.9 min、`Kotlin tests core` 8.1、`Trace scan` 6.2、`Android compile` 6.1、`Python shard 1` 4.5。

结构性事实：

1. 分支保护要求 `Kotlin tests`、`Android compile`、`Python tests`、`Trace scan` 四项且 strict up-to-date；context 名即 job 名，job 缺席等于检查永远待审。
2. `ci.yml` 无 paths 过滤，文档改动与代码改动执行同一组 job。
3. squash 提交在 `main` 上没有第二父，合并树守卫按设计对每次合并派发一次全量 CI，故每次合并消耗两次全量。
4. `kotlin-core`、`android`、`trace` 与两个 gate job 无 `timeout-minutes`；`ledger-data-shards` 60、`python-shards` 30。
5. 历史提交面（600 提交盘点）：42% 只碰 `docs/` 或 `local/`，`feat` 提交 8%。

## 3. 裁决（T1–T7）

### T1 文档快路径

**决定**：改动只落在 `docs/**` 与仓库根 `*.md` 的 PR 判为 `docs`，跳过 Kotlin 验证与 Android 编译的构建步骤；任何其他路径（含 `.github/**`、`tools/**`、各模块目录）判为 `code`，执行全量。分类器默认 `code`，即无法判断或输入为空时按代码处理。

**理由**：这类提交占 42%，与 Kotlin/Android 构建无因果关系。

**兜底**：必过 context 是 job 名，重活 job 保留在场上、仅条件化其步骤（`Android compile`）或整体跳过并由 gate 放行（`Kotlin tests`、`Python tests`）；gate 独立重算分类，与 `changes` job 不一致即红；分类器有合成测试；改分类器或工作流本身的 PR 必判 `code`，故其自身 PR run 即全量自检。

### T2 Trace scan 的 PR 范围

**决定**：PR 侧扫描 = 当前树全部 tracked 文件内容 + 本 PR 相对 base 新增提交的消息、路径与树；`main` 侧（守卫派发、手动 dispatch）保持全历史四类扫描。`TRACE_PATTERN` 与 agent 路径正则保持与 harness 逐字一致，不因本项改动。

**理由**：`Trace scan` 是文档 PR 的墙钟主项（6.2 min），其中历史消息/树扫描对 base 侧重复。

**兜底**：归纳前提是「`main` 上每个提交都已被全量扫描过」，而 `main` 的入口是 PR 合并或直推，两者都会触发全量。出现绕过该前提的例外时回退本项。

### T3 合并后去重

**决定**：合并树守卫扩展一条零动作路径：`main` 新提交为线性/squash 形态时，若能从标题解析 PR 号、取到该 PR 的 head OID、`tree(head) == tree(新提交)`、且四项 check-run 在该 head SHA 上全部为 success，则零动作、不派发。任一环节不可读或不等，维持现有派发行为。

**理由**：squash + strict up-to-date 下落地树与 PR 验证树同树，派发是重复执行；当前占 CI 全部 run 的 41%。

**兜底**：fail-closed（不可判定即派发，等价现状）；合成仓库测试覆盖「树相等且全绿 → 零动作」「树不等 → 派发」「API 不可读 → 派发」三态。

### T4 scale preflight 的设备半边外移

**决定**：`android-preflight.yml` 不再由 PR 触发；模拟器链路改为合并后（`push:main`）+ 夜间定时 + 手动。PR 侧保留的确定性部分由 `ci.yml` 的 `Android compile` 产出的整树 APK bundle 承担（该 workflow 已是受信 producer）。手动 rig（B6 / ENOSPC / P706-A06 / scale long）继续消费同一 bundle 来源。

**理由**：22% 红全部是模拟器启动失败，历史零真代码问题；它在 PR 上的信号价值低于其红噪音与 30 min 机会成本。

**兜底**：设备信号保留在合并后与夜间，不消失；每次手动 rig 仍绑定精确 SHA 与 bundle 来源。

### T5 Windows JVM tests

**决定**：删除 `pull_request` 触发，保留夜间定时与手动 dispatch。

**理由**：17 run 中 14 次被后续推送取消、0 真红、非必过；PR 上的完成率不足两成。

**兜底**：Windows 特有风险面仍在 24 小时内被夜间运行覆盖；需要即时信号时手动 dispatch。

### T6 手工设备 rig

**决定**：保留 B6、ENOSPC、P706-A06、maximum-scale long 四个手动 rig 与全部 evidence 绑定逻辑，只在文档层标注为历史验收工具。删除与否需单独裁决。

**理由**：它们是已收口向量的可重跑证据，删除的收益（少四个手动入口）低于失去复验能力的代价。

### T7 Merge queue

**决定**：只出评估结论，不实施。合入串行与 update-branch 的痛点留待合并队列评估单独立项。

## 4. 不变量

1. 四个必过 context 名不变，分支保护配置零改动。
2. 大 job 的跳过只允许出现在分类为 `docs` 且 gate 独立重算一致时；其余情形一律执行。
3. 隐私门不弱化：`main` 侧 trace 永远全量；PR 侧覆盖全树内容与本 PR 新增提交。
4. 代码改动 PR 的执行面不减少：`code` 分类下与现状逐 job 一致。
5. 零产品代码、零账务语义、零 schema、零迁移改动。

## 5. 落地批次

| 批次 | 范围 | 路线 |
| --- | --- | --- |
| PR 0 | 本规格 + D-302 | 文档，普通 |
| PR 1 | T5 + 补 `timeout-minutes` + CONTRIBUTING 同步 | 普通 |
| PR 2 | T1：分类器 + 合成测试 + 分片名单 + `ci.yml` gate 语义 | 高风险 |
| PR 3 | T2：`trace-scan.sh` 范围模式 + 合成测试 + `ci.yml` 传 base | 高风险 |
| PR 4 | T3：`verify-merge-tree.py` 扩展 + 合成测试 + 守卫 workflow | 高风险 |
| PR 5 | T4：preflight 触发与 bundle 复用 + CONTRIBUTING 同步 | 普通 |

高风险批次走独立 writer → 规格评审与质量评审分开 → 独立 verifier → 主代理复跑；普通批次走一个评审 + 独立 verifier。每批单独 PR、单独回退；新增 Python 测试模块必须同批登记进分片名单，否则分片覆盖守卫会红。

## 6. 验收证据

- 每批：四项必过全绿，且该批自身 PR run 显示新配置按预期执行（改动 `ci.yml` 的 PR 必为 `code` 分类）。
- PR 1 后：`Windows JVM tests` 不再出现在 PR 的 checks 列表。
- PR 2 后：第一个纯文档 PR 的 checks 为 4 个、wall clock ≤3.0 min；同一分支追加代码文件后变为全量。
- PR 3 后：PR 侧 `Trace scan` ≤2.0 min，且合成测试证明旧提交中的禁止串在该模式外仍会被捕获。
- PR 4 后：一次真实 squash 合并守卫零动作；合成测试三态齐备。
- PR 5 后：连续 20 个 PR 无模拟器红。

证据落 `local/artifacts/ci-tuning/<batch>/`（gitignored）。

## 7. 范围外

产品代码与 UI、账务语义、schema 与迁移、四处手工 rig 的删除、merge queue 实施、分片数量与并行度的重新装箱（现有机制已具备，仅在实测漂移时另行立项）。

## 8. 回退

每批一个 PR，回退即 revert 对应 PR；无数据、无不可逆步骤。T2 的回退触发条件是出现绕过「`main` 全量扫描」前提的直推；T3 的回退触发条件是出现任何一次「零动作但有红」的落地。
