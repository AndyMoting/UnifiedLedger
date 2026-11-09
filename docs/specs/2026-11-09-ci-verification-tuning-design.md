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
| G-1 | 代码 PR 执行面 | 10 job，wall clock ≈9.0 min | 11 job（新增 `changes` 1，串行 ≤0.5 min 计为固定成本）；重活 job 一个不少、不更慢（持续）。§10 把分片 3→5 后为 13 个执行 job，Kotlin 侧 6 个 |
| G-2 | 纯文档 PR | 10 job（Kotlin 4 + Android 1 + Python 2 + Trace 1 + 2 gate），≈9.0 min | PR 2 后：Kotlin 4 job 与 `Android compile` 都以 job 级跳过执行，执行面 = `changes` + Python 两片 + `Trace scan` + 两个 gate（6 个），其余为 skipped 条目；PR 3 后：wall clock ≤5.5 min（Python shard 1 约 4.2 min + 串行开销）|
| G-3 | 每次 squash 合并的 CI 全量次数 | 2（PR run + 守卫派发）| 1（PR 4 后）|
| G-4 | scale preflight 假红进入 PR | 22% | 0（PR 5 后）|
| G-5 | Windows 取消空跑占比 | 82%（本次采样；全历史 105 次 PR 触发中 75 次取消 = 71%）| 0（PR 1 后）|
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

表内是一次采样，窗口随后续运行漂移（同一窗口重算时 Windows 行为 15/12/0）；判据按量级使用，不按精确值。

结构性事实：

1. 分支保护要求 `Kotlin tests`、`Android compile`、`Python tests`、`Trace scan` 四项且 strict up-to-date；context 名即 job 名。job 级跳过在 GitHub 上记为 success 并满足必过检查，真正让检查永远待审的是 workflow 级 path/branch 过滤不触发。因此「少跑」只能靠 job 级条件，而 gate 是唯一能证明「跳过是被允许的」的地方。
2. `ci.yml` 无 paths 过滤，文档改动与代码改动执行同一组 job。
3. squash 提交在 `main` 上没有第二父，合并树守卫按设计对每次合并派发一次全量 CI，故每次合并消耗两次全量。
4. `kotlin-core`、`android`、`trace` 与两个 gate job 无 `timeout-minutes`；`ledger-data-shards` 60、`python-shards` 30。
5. 提交面：近 150 提交中约 43%（64/150）只碰 `docs/` 与仓库根 `*.md`；600 提交窗口按同一口径为 28.5%；`feat` 占 8.0%。按本规格收紧后的 `.md` 口径略低于该值。`local/` 是 gitignored 路径，任何提交都不会碰它。

## 3. 裁决（T1–T7）

### T1 文档快路径

**决定**：改动只落在 `docs/**/*.md` 与仓库根 `*.md` 的 PR 判为 `docs`：Kotlin 验证 job 与 `Android compile` job 不执行（job 级跳过，GitHub 记为 success），Python 两片与 `Trace scan` 照常执行（文档由 `project_docs` 校验）。`docs/` 下的非 Markdown 文件判 `code`——`docs/migrations/golden-v2/` 与 `docs/examples/` 里的 golden 夹具由 Kotlin 与 Python 测试读取，T1 的「与构建无因果关系」对它们不成立。任何其他路径（含 `.github/**`、`tools/**`、各模块目录）判为 `code`，执行全量。分类器默认 `code`，即无法判断或输入为空时按代码处理。

**理由**：这类提交占相当比例（见 §2 事实 5），与 Kotlin/Android 构建无因果关系。

**兜底**：`changes` job 判定后由 gate 独立重算一次分类，不一致即红；分类器有合成测试；改分类器或工作流本身的 PR 必判 `code`，故其自身 PR run 即全量自检。

### T2 Trace scan 的 PR 范围

**决定**：PR 侧扫描 = 当前树全部 tracked 文件内容 + 本 PR 相对 base 新增提交的消息、路径与树。`main` 侧对每个落地提交都做扫描：守卫派发全量 CI 时覆盖全历史，零动作路径内由守卫自身扫描落地提交（见 T3）。`TRACE_PATTERN` 与 agent 路径正则保持与 harness 逐字一致，不因本项改动。

**理由**：`Trace scan` 是文档 PR 的墙钟主项（6.2 min），其中历史消息/树扫描对 base 侧是重复工作。

**兜底**：覆盖依赖「每个落地提交至少被扫描一次」，该义务写进 T3 与不变量 3；出现绕过它的直推时回退本项。

### T3 合并后去重

**决定**：合并树守卫增加零动作路径。判定链：`main` 新提交能唯一解析出 PR（标题末尾 `(#N)`，或 GitHub 的 commit→PR 关联恰好一个已合并 PR 且其 `merge_commit_sha` 等于该提交；显式 `gh pr merge --subject` 不追加编号，故关联回退是主路径，该关联是落地提交自身的服务端记录，不引入 D-196 拒绝的外部树基线）→ 该 PR 的 `mergeCommit` 等于落地提交 → `tree(PR head) == tree(落地提交)` → 该 PR 的 required checks 结论全为 success。四条全成立时不再派发全量 CI，否则维持现有派发行为。零动作路径内，守卫**仍执行落地提交的 trace 扫描**（该提交自身：消息、路径、树），merge commit 与 squash 两条零动作路径都如此，因为合并消息在合并时生成，PR 侧从未扫过。

**理由**：squash + strict up-to-date 下落地树与 PR 验证树同树，派发是重复执行；当前占 CI 全部 run 的 41%。

**兜底**：fail-closed（任一环节不可读、解析不到或树不等即派发，等价现状）；合成仓库测试覆盖「四条件齐备 → 零动作且 trace 执行」「树不等 → 派发」「API 不可读 → 派发」三态。PR 4 落地后，零动作合并不再自动产生该 SHA 的整树 APK bundle；手动 rig 需要时按 `docs/CONTRIBUTING.md`「本机与 CI 的验证分工」给出的 `gh workflow run ci.yml --ref main` 手动派发一次，作为该 SHA 的 bundle 生产者路径（该命令作用于 `main` 的当前 tip，只在该 SHA 仍是 tip 时产出对应 bundle）。

### T4 scale preflight 的设备半边外移

**决定**：`android-preflight.yml` 不再由 PR 触发；模拟器链路改为合并后（`push:main`）+ 夜间定时 + 手动。该文件的 `apks` job 条件相应改写，使新触发下仍产出或复用整树 APK bundle；PR 侧的确定性部分由 `ci.yml` 的 `Android compile` 产出的 bundle 承担（该 workflow 已是受信 producer）。手动 rig（B6 / ENOSPC / P706-A06 / scale long）继续消费同一来源。

**理由**：22% 红全部是模拟器启动失败，历史零真代码问题；它在 PR 上的信号价值低于其红噪音与 30 min 机会成本。

**取代与修订关系**：本项取代 D-200 第 2 条「相关 PR 自动运行非 required 的设施预检」；并修订该条第 4 条——其要求预检覆盖驱动、校验与 workflow 改动的**覆盖内容保留**，运行位置改为合并后；该条末句「不新增定时任务或 API 矩阵」中「定时任务」由本项解除（新增的夜间运行即本项的信号落地位置），「API 矩阵」仍有效。

**兜底**：设备信号保留在合并后与夜间，不消失；每次手动 rig 仍绑定精确 SHA 与 bundle 来源。

### T5 Windows JVM tests

**决定**：删除 `pull_request` 触发，保留夜间定时与手动 dispatch。

**理由**：本次采样 17 run 中 14 次被后续推送取消（82%）；全历史 105 次 PR 触发中 75 次取消（71%），7 次真红集中在历史批次。该信号非必过，PR 上的完成率不足两成，故 PR 触发不承担门禁价值。

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
4. 代码改动 PR 的执行面不减少：`code` 分类下与现状逐 job 一致，另加一个 `changes` 分类 job（串行 ≤0.5 min，不是验证面）。
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
- PR 2 后：第一个纯文档 PR 上 Kotlin 4 个 job 与 `Android compile` 均为 skipped，执行面为 6 个 job；同一分支追加代码文件后变为全量。
- PR 3 后：PR 侧 `Trace scan` ≤2.0 min；纯文档 PR wall clock ≤5.5 min；合成测试证明旧提交中的禁止串在该模式外仍会被捕获。
- PR 4 后：一次真实 squash 合并守卫零动作且落地提交扫描通过；合成测试三态齐备；手动 rig 的生产者指令（`gh workflow run ci.yml --ref main`）在文档中可达。
- PR 5 后：连续 20 个 PR 无模拟器红。

证据落 `local/artifacts/ci-tuning/<batch>/`（gitignored）。

## 7. 范围外

产品代码与 UI、账务语义、schema 与迁移、四处手工 rig 的删除、merge queue 实施（评估结论：当前同时只有少数 PR 在飞，队列带来的收益不足以抵消新 workflow 面与合入流程改写，暂不实施）、Python 分片的按路径裁剪（会缩小文档改动对 Python 测试的覆盖面，另行立项）、分片数量与并行度的重新装箱（现有机制已具备，仅在实测漂移时另行立项）。

## 8. 回退

每批一个 PR，回退即 revert 对应 PR；无数据、无不可逆步骤。T2 的回退触发条件是出现绕过「`main` 每个落地提交至少扫描一次」的直推；T3 的回退触发条件是出现任何一次「零动作但有红」的落地。
## 9. 实施记录与实测验收（2026-11-09）

批次落地提交（全部 squash 进 `main`）：登记 `f70c7d91`、卫生 `a9a4b332`、文档快路径 `d417ab85`、trace 范围 `dfa2bb3d`、设备路由 `1eaf405c`、合并后去重 `7ea8397b`。

| 判据 | 实测 | 证据 |
| --- | --- | --- |
| G-1 代码 PR 执行面 | 11 job（新增 `changes`），重活 job 一个不少；四必过全绿 | 各批 PR 的 required checks |
| G-2 纯文档 PR | 执行 6 个 job，Kotlin 4 个与 `Android compile` 为 skipped；wall clock 4m10s；四必过全绿且分支保护判 CLEAN | run 42581428709（本 PR #270）|
| G-3 合并后去重 | 零动作、未派发全量；守卫步骤 1.5 秒 | run 42581062153：`squash evidence complete` → 落地提交扫描 → `zero action`，无 CI 派发 |
| G-4 设备信号后移 | PR 上不再出现 preflight；合并后 push 触发并成功 | run 42578401565（push, `1eaf405c`, success）|
| G-5 Windows 取消空跑 | PR checks 列表无 `Windows JVM tests` | 各 PR 的 checks |
| G-6 必过 context | 每个 PR 四项齐备 | 各 PR `gh pr checks --required` |

残余（登记接受）：commit→PR 关联请求未分页（30 条上限，超出即按证据不全派发，fail-closed）；守卫的 `gh` 调用任何失败都退回派发全量；`docs/` 下非 Markdown 文件判 `code`，故 golden 夹具改动仍跑全量。

## 10. 墙钟调优（D-303）

目标：代码 PR 的 CI 墙钟 p50 从 ≈9.0 min 降到 ≈5.0–5.5 min，验证面不变。判据：

| # | 判据 | 基线 | 目标 |
| --- | --- | --- | --- |
| W-1 | 最慢 `ledger-data` 分片 | p50 8.4 / p90 9.4 min | p50 ≤5.5 / p90 ≤6.5 min |
| W-2 | 分片覆盖 | 3 片，按类数接近、按耗时不均（775–1037 类秒）| 5 片，ids 1..5、每类恰好一次，每片精确执行清单份额（实测类秒 631/568/681/505/575）|
| W-3 | `Android compile` | p50 6.4 min（`--max-workers=1`，无构建缓存）| p50 ≤5.0 min |
| W-4 | 代码 PR 墙钟 | p50 ≈9.0 min | p50 ≤5.5 min |

瓶颈证据（2026-11-09，近 30 run）：分片墙钟与类秒总和不成正比（969 s→488 s、1037 s→409 s、775 s→362 s），而 `LedgerDatabaseMigrationTest` 单类 251.5 s / 48 例独占一个 fork；`Android compile` 内部 6.4 min 全串行；`Kotlin tests core` 的 5.9 min 里单条 `check` 占 5.8 min。

批次：

| 批次 | 范围 | 预期 |
| --- | --- | --- |
| A | 5 片按实测类秒重排 + `--tsv` 计时工件 | 最慢分片 8.4 → ≈5.0–5.5 |
| B | 拆 `LedgerDatabaseMigrationTest` 为三类（`MigrationTestSupport` 基类），并以 `test_migration_split_inventory` 守住 48 例；清单按拆后首个 run 的 timing 工件重排 | 分片再降 ≈0.5–1.0；清单 116 → 118 类；三类实测类秒 124.9 / 154.9 / 26.8 |
| C | `android` 构建步骤改 `--max-workers=4 --build-cache`，单元测试步骤不带缓存（必过检查必须真跑测试）；`kotlin-core` 只加 `--profile` 并上传 `gradle-profile-core`（六 fork 试验超订四 vCPU，已撤回）| Android 6.4 → ≤5.0（取每个 PR 首次推送、冷缓存采样）；聚合任务提升 <0.5 min 时按 profile 另批拆分 |
| D | 按最新 timing 工件重排清单（最重五类分散）；`android` 两条装配命令合并为一条 gradle 调用；`test_shard_packing.py` 守住装箱不变量 | 旧清单最重片预测 690.3 → 633.3 s；实测分片 3.8–5.5 min、run 墙钟 7.0–7.5 min |
| E | 迁移校验 `verifyCommonMainLedgerDatabaseMigration`（profile 单任务 3m27s）拆成独立并行 job，core 用 `-x` 排除；收口 job 纳入该依赖 | core 预期 7.0 → 5.5–6.3 min；整轮预期 7.5 → 6.2–6.8 min（上限受最慢分片 5.5 约束）|

批 A 实测（三次 run）：最慢分片 5.75 / 6.60 / 6.27 min（p50 6.27，基线 8.77–9.07），run 墙钟 7.14 / 7.15 / 6.60 min。W-1 尚未达成——剩余瓶颈是迁移大测试（278.5 s 仍独占一个 fork，归批 B）与 `Android compile`（5m04–6m59，归批 C）。

不变量：四个必过 context 名与分支保护零改动；gradle 任务集合与测试集合不变（B 只改类边界）；APK producer 契约不变（不拆 `Android compile`）；每批一个 PR、独立回退。

范围外：merge queue、按子项目拆分 `check`（待 C 批 profile 数据）、instrumented 挪位、文档 PR 的 Python 再收窄。