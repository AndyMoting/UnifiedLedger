# P7-04 A05IMPORT-INCOME-FACE-001 修复设计：导入候选决策面按方向渲染收入分类

状态：approved

更新：2026-09-19。基线：main `ba029cf`（schema v30）。零账务语义变更、零 schema/迁移、零新依赖——本批是**决策面呈现修复**：in 方向 ordinary 候选的分类选项从「只渲染支出分类（导致必然域校验拒绝）」改为「按方向渲染匹配的分类源」。**D-154** 登记（随批提交）。

## 1. 缺陷与根因（取证：`local/artifacts/d01-p704/defect-A05IMPORT-INCOME-FACE-001.md` + Explore 代码级调查）

- **现象**：导入候选（ordinary_flow，方向 in，如 CCB「银联入账」行）的决策表单分类区只渲染**支出分类**选项（`P503ImportReview.kt:778-784`，宿主接线 `P503App.kt:1863` 只传 `options.expenseCategories`）→ in 方向候选无法获得有效决策 → 批量提交被 spine 域校验拒绝（`OrdinaryIncome.kt:34-36 IncomeCategoryRequired`）→ **收入方向导入候选在产品路径上永远不可确认**。
- **根因**：决策面分类选项未按方向过滤——commit 工厂（`OrdinaryFlowFormalFactory.kt`）按方向分派 `createAssetPaidOrdinaryExpense`（out，要求 EXPENSE 分类）/`createAssetReceivedOrdinaryIncome`（in，要求 INCOME 分类），而表单只提供支出选项。
- **校验行为正确**：类型化拒绝（`SPINE_DOMAIN_VALIDATION_FAILED`）、零写入——缺陷在决策面不提供合法选项，非校验缺陷。

## 2. 修复设计（FIX-INCOME-FACE-1）

- **`P503ImportReview.kt` 决策表单**：新增 `incomeCategories: List<IncomeCategoryOption>` 参数；`分类` 块按**方向**渲染——方向 "out" → `expenseCategories`（现状不变）；方向 "in" → `incomeCategories`。方向的来源：候选 detail 数据的方向 token（`ImportCandidateDetailResult` 的行数据携带 'in'/'out'，设备 dump 证实「方向 in/out」文本存在）；由调用点传入（如 `directionIn: Boolean` 或直接传已选列表——writer 按代码实际结构选择最小改法）。
- **`P503App.kt` 宿主接线**：传入 `incomeOptions.incomeCategories`（既有提供器 `facade.incomeOptionsProvider.queryOptions()`，入口收入流已在用；`IncomeCategoryOption(categoryId, parentCategoryId, label, postingAccountId)`）。注意收入选项为**叶分类**（入口收入表单同源），满足 `SecondaryCategoryRequired` 校验。
- **transfer_flow 面**：不变（无分类 section）。
- **决策草稿/校验/commit 路径**：零改动（`ImportDecisionFieldUpdate.Category` 事件同型；commit 工厂已按方向分派）。
- **选项排序**：沿入口收入流同源顺序（不引入新排序）。

## 3. 测试要求

- `P503ImportReviewPresentationTest`（既有测试类扩写）：in 方向候选的分类区渲染收入选项（`●/○ A02FIX-SAL-C1` 型）、不渲染支出选项；out 方向渲染支出选项（现状回归）。
- 既有向量不回归；`:app-ui:jvmTest` 全绿 + `ktlintCheck` + 两编译。

## 4. 可写文件范围

- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503ImportReview.kt`
- `app-ui/src/commonMain/kotlin/com/unifiedledger/ui/P503App.kt`（仅决策面参数接线）
- `app-ui/src/commonTest/kotlin/com/unifiedledger/ui/P503ImportReviewPresentationTest.kt`
- `docs/specs/2026-09-19-p7-04-import-income-face-design.md`（本文件）
- `docs/DECISIONS.md`（追加 D-154）

不得触碰 ledger-data/ledger-domain/ledger-application/*.sq/迁移/依赖/清单。

## 5. 验收

- 自动：jvmTest 全绿（新增方向选项向量）+ ktlint + 两编译。
- verifier 断言：V1 决策面按方向渲染（in→income，out→expense）；V2 commit/校验/草稿路径零改动；V3 范围 5 文件；V4 测试真实；V5 隐私卫生。
- 设备（合并后）：CCB 样本导入 → in 方向候选（银联入账）决策区出现收入分类 → 以 A02FIX-SAL-C1 补全 → 批量确认入账 → DB 增 income posting → 首页当次会话反映（触发 (f)）。
