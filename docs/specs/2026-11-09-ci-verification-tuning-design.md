# CI 验证调参设计（PR 侧范围、合并后去重、设备抖动路由）

状态：approved（2026-11-09 用户批准立项；实施范围 = T1–T5 分批落地，T6 保留现状并标注 historical，T7 仅评估）

日期：2026-11-09。批准依据：用户 2026-11-09 批准「CI 调参立项」计划，计划含 T1–T7 的推荐结论、成功判据与批次划分。本规格是该立项的范围、判据与不变量唯一记录处。

**本规格性质**：只冻结 CI 验证路由的范围与判据。产品代码、账务语义、schema、迁移均不在范围内。命令级事实（本地/CI 验证分工、job 名单、执行命令）只维护在 `docs/CONTRIBUTING.md`，本规格不复述。

**关联决定**：D-190（单遍 CI 与合并树守卫）、D-197（Android instrumented 设备信号）、D-200/D-201（scale preflight 与 APK 来源）、D-302（本规格的登记决定）。

---

## 1. 目标与成功判据

在不减少任何代码改动 PR 的验证面、不改动四个必过检查名的前提下，降低等待时间、红噪音与合并后重复算力。全部判据可测；G-1、G-6 是持续不变量，不归属单一批次。

| # | 判据 | 现状基线 | 目标 |
| --- | --- | --- | --- |
| G-1 | 代码 PR 执行面 | 10 job，wall clock ≈9.0 min | 不减少、不变慢（持续）|
| G-2 | 纯文档 PR | 10 job（Kotlin 4 + Android 1 + Python 2 + Trace 1 + 2 gate），≈9.0 min | PR 2 后：Kotlin 4 job 为 0、Android 构建步骤跳过（job 保留）、新增 `changes` 1，总 10 → 7 job；PR 3 后：wall clock ≤5.0 min（由 Python shard 1 的 4.5 min 主导）|
| G-3 | 每次 squash 合并的 CI 全量次数 | 2（PR run + 守卫派发）| 1（PR 4 后）|
| G-4 | scale preflight 假红进入 PR | 22% | 0（PR 5 后）|
| G-5 | Windows 取消空跑占比 | 82% | 0（PR 1 后）|
| G-6 | 四个必过 context 缺席次数 | 0 | 0（持续）|

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

1. 分支保护要求 `Kotlin tests`、`Android compile`、`Python tests`、`Trace scan` 四项且 strict up-to-date；context 名即 job 名。job 级跳过在 GitHub 上记为 success 并满足必过检查，真正让检查永远待审的是 workflow 级 path/branch 过滤不触发。因此「少跑」只能靠 job 级条件，而 gate 是唯一能证明「跳过是被允许的」的地方。
2. `ci.yml` 无 paths 过滤，文档改动与代码改动执行同一组 job。
3. squash 提交在 `main` 上没有第二父，合并树守卫按设计对每次合并派发一次全量 CI，故每次合并消耗两次全量。
4. `kotlin-core`、`android`、`trace` 与两个 gate job 无 `timeout-minutes`；`ledger-data-shards` 60、`python-shards` 30。
5. 提交面：近 150 提交中约 43%（64/150）只碰 `docs/**` 与仓库根 `*.md`；600 提交窗口按同一口径为 28.5%；`feat` 占 8.0%。`local/` 是 gitignored 路径，任何提交都不会碰它。

## 3. 裁决（T1–T7）

### T1 文档快路径

**决定**：改动只落在 `docs/**` 与仓库根 `*.md` 的 PR 判为 `docs`：Kotlin 验证 job 与 Android 构建步骤不执行，Python 两片与 `Trace scan` 照常执行（文档由 `project_docs` 校验）。任何其他路径（含 `.github/**`、`tools/**`、各模块目录）判为 `code`，执行全量。分类器默认 `code`，即无法判断或输入为空时按代码处理。

**理由**：这类提交占相当比例（见 §2 事实 5），与 Kotlin/Android 构建无因果关系。

**兜底**：`changes` job 判定后由 gate 独立重算一次分类，不一致即红；分类器有合成测试；改分类器或工作流本身的 PR 必判 `code`，故其自身 PR run 即全量自检。

### T2 Trace scan 的 PR 范围

**决定**：PR 侧扫描 = 当前树全部 tracked 文件内容 + 本 PR 相对 base 新增提交的消息、路径与树。`main` 侧对每个落地提交都做扫描：守卫派发全量 CI 时覆盖全历史，零动作路径内由守卫自身扫描落地提交（见 T3）。`TRACE_PATTERN` 与 agent 路径正则保持与 harness 逐字一致，不因本项改动。

**理由**：`Trace scan` 是文档 PR 的墙钟主项（6.2 min），其中历史消息/树扫描对 base 侧是重复工作。

**兜底**：覆盖依赖「每个落地提交至少被扫描一次」，该义务写进 T3 与不变量 3；出现绕过它的直推时回退本项。

### T3 合并后去重

**决定**：合并树守卫增加零动作路径。判定链：`main` 新提交标题含 PR 号 → 该 PR 的 `mergeCommit` 等于落地提交 → `tree(PR head) == tree(落地提交)` → 该 PR 的 required checks 结论全为 success。四条全成立时不再派发全量 CI，否则维持现有派发行为。零动作路径内，守卫**仍执行落地提交的 trace 扫描**（该提交自身：消息、路径、树），因为 squash 消息在合并时生成，PR 侧从未扫过。

**理由**：squash + strict up-to-date 下落地树与 PR 验证树同树，派发是重复执行；当前占 CI 全部 run 的 41%。

**兜底**：fail-closed（任一环节不可读、解析不到或树不等即派发，等价现状）；合成仓库测试覆盖「四条件齐备 → 零动作且 trace 执行」「树不等 → 派发」「API 不可读 → 派发」三态。PR 4 落地后，零动作合并不再自动产生该 SHA 的整树 APK bundle；手动 rig 需要时按 `docs/CONTRIBUTING.md`「本机与 CI 的验证分工」给出的 `gh workflow run ci.yml --ref main` 手动派发一次，作为该 SHA 的 bundle 生产者路径。

### T4 scale preflight 的设备半边外移

**决定**：`android-preflight.yml` 不再由 PR 触发；模拟器链路改为合并后（`push:main`）+ 夜间定时 + 手动。该文件的 `apks` job 条件相应改写，使新触发下仍产出或复用整树 APK bundle；PR 侧的确定性部分由 `ci.yml` 的 `Android compile` 产出的 bundle 承担（该 workflow 已是受信 producer）。手动 rig（B6 / ENOSPC / P706-A06 / scale long）继续消费同一来源。

**理由**：22% 红全部是模拟器启动失败，历史零真代码问题；它在 PR 上的信号价值低于其红噪音与 30 min 机会成本。

**取代与修订关系**：本项取代 D-200 第 2 条「相关 PR 自动运行非 required 的设施预检」；并修订该条第 4 条——其要求预检覆盖驱动、校验与 workflow 改动的**覆盖内容保留**，运行位置改为合并后；同一条第 4 句「不新增定时任务」由本项解除，新增的夜间运行即本项的信号落地位置。

**兜底**：设备信号保留在合并后与夜间，不消失；每次手动 rig 仍绑定精确 SHA 与 bundle 来源。

### T5 Windows JVM tests

**决定**：删除 `pull_request` 触发，保留夜间定时与手动 dispatch。

**理由**：17 run 中 14 次被后续推送取消、0 真红、非必过；PR 上的完成率不足两成。

**兜底**：Windows 特有风险面仍在 24 小时内被夜间运行覆盖；需要即时信号时手动 dispatch。

### T6 手工设备 rig

**决定**：保留 B6、ENOSPC、P706-A06、maximum-scale long 四个手动 rig 与全部 evidence 绑定逻辑，只在文档层标注为历史验收工具（PR 1 执行该标注）。删除与否需单独裁决。

**理由**：它们是已收口向量的可重跑证据，删除的收益（少四个手动入口）低于失去复验能力的代价。

### T7 Merge queue

**决定**：只出评估结论，不实施；交付物是本规格 §7 的一句结论，无代码批次。合入串行与 update-branch 的痛点留待合并队列评估单独立项。

## 4. 不变量

1. 四个必过 context 名不变，分支保护配置零改动。
2. 大 job 的跳过只允许出现在分类为 `docs` 且 gate 独立重算一致时；其余情形一律执行。
3. 隐私门不弱化：`main` 每个落地提交都被 trace 扫描——要么在守卫派发的全量 run 内，要么在守卫的零动作路径内；PR 侧覆盖全树内容与本 PR 新增提交。
4. 代码改动 PR 的执行面不减少：`code` 分类下与现状逐 job 一致。
5. 零产品代码、零账务语义、零 schema、零迁移改动。

## 5. 落地批次

| 批次 | 范围 | 路线 |
| --- | --- | --- |
| PR 0 | 本规格 + D-302 | 文档，普通 |
| PR 1 | T5 + T6 的历史标注 + 补 `timeout-minutes` + CONTRIBUTING §Windows/§CI 配置 同步 | 普通 |
| PR 2 | T1：分类器 + 合成测试 + 分片名单 + `ci.yml` gate 语义 + CONTRIBUTING §CI 配置 同步 | 高风险 |
| PR 3 | T2：`trace-scan.sh` 范围模式 + 合成测试 + `ci.yml` 传 base + CONTRIBUTING §Trace scan 同步 | 高风险 |
| PR 4 | T3：`verify-merge-tree.py` 扩展 + 合成测试 + 守卫 workflow（含零动作路径的落地提交扫描）+ `CONTRIBUTING` §合并/§本机与 CI 验证分工 同步 | 高风险 |
| PR 5 | T4：preflight 触发与 `apks` 条件改写 + bundle 复用 + CONTRIBUTING §Android scale preflight 同步 | 普通 |

高风险批次走独立 writer → 规格评审与质量评审分开 → 独立 verifier → 主代理复跑；普通批次走一个评审 + 独立 verifier。每批单独 PR、单独回退；新增 Python 测试模块必须同批登记进分片名单，否则分片覆盖守卫会红。

## 6. 验收证据

- 每批：四项必过全绿，且该批自身 PR run 显示新配置按预期执行（改动 `ci.yml` 的 PR 必为 `code` 分类）。
- PR 1 后：`Windows JVM tests` 不再出现在 PR 的 checks 列表；四个手动 rig 文件头出现 historical 标注。
- PR 2 后：第一个纯文档 PR 的 Kotlin 4 个 job 为 0、Android 构建步骤跳过（job 保留）、新增 `changes` job、总 7 job；同一分支追加代码文件后变为全量。
- PR 3 后：PR 侧 `Trace scan` ≤2.0 min；纯文档 PR wall clock ≤5.0 min；合成测试证明旧提交中的禁止串在该模式外仍会被捕获。
- PR 4 后：一次真实 squash 合并守卫零动作且落地提交扫描通过；合成测试三态齐备；手动 rig 的生产者指令（`gh workflow run ci.yml --ref main`）在文档中可达。
- PR 5 后：连续 20 个 PR 无模拟器红。

证据落 `local/artifacts/ci-tuning/<batch>/`（gitignored）。

## 7. 范围外

产品代码与 UI、账务语义、schema 与迁移、四处手工 rig 的删除、merge queue 实施（评估结论：当前同时只有少数 PR 在飞，队列带来的收益不足以抵消新 workflow 面与合入流程改写，暂不实施）、Python 分片的按路径裁剪（会缩小文档改动对 Python 测试的覆盖面，另行立项）、分片数量与并行度的重新装箱（现有机制已具备，仅在实测漂移时另行立项）。

## 8. 回退

每批一个 PR，回退即 revert 对应 PR；无数据、无不可逆步骤。T2 的回退触发条件是出现绕过「`main` 每个落地提交至少扫描一次」的直推；T3 的回退触发条件是出现任何一次「零动作但有红」的落地。
