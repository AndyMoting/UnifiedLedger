# P7-04 A05IMPORT-INCOME-FACE-001 修复设计：导入候选决策面按方向渲染收入分类

状态：approved

更新：2026-09-19（同日评审修正：选项源按 face×方向匹配，方向依赖仅限 ordinary_flow 面，见 §2）。基线：main `ba029cf`（schema v30）。零账务语义变更、零 schema/迁移、零新依赖——本批是**决策面呈现修复**：in 方向 ordinary 候选的分类选项从「只渲染支出分类（导致必然域校验拒绝）」改为「按 face×方向渲染匹配的分类源」。**D-154** 登记（随批提交）。

## 1. 缺陷与根因（取证：缺陷报告与代码级链路调查）

- **现象**：导入候选（ordinary_flow，方向 in，如 CCB「银联入账」行）的决策表单分类区只渲染**支出分类**选项（`P503ImportReview.kt:778-784`，宿主接线 `P503App.kt:1863` 只传 `options.expenseCategories`）→ in 方向候选无法获得有效决策 → 批量提交被 spine 域校验拒绝（`OrdinaryIncome.kt:34-36 IncomeCategoryRequired`）→ **收入方向导入候选在产品路径上永远不可确认**。
- **根因**：决策面分类选项未按方向过滤——commit 工厂（`OrdinaryFlowFormalFactory.kt`）按方向分派 `createAssetPaidOrdinaryExpense`（out，要求 EXPENSE 分类）/`createAssetReceivedOrdinaryIncome`（in，要求 INCOME 分类），而表单只提供支出选项。
- **校验行为正确**：类型化拒绝（`SPINE_DOMAIN_VALIDATION_FAILED`）、零写入——缺陷在决策面不提供合法选项，非校验缺陷。

## 2. 修复设计（FIX-INCOME-FACE-1）

- **`P503ImportReview.kt` 决策表单**：新增 `incomeCategories: List<IncomeCategoryOption>` 参数；`分类` 块按 **face×方向** 渲染（2026-09-19 评审 F1 修正：方向匹配仅适用于方向依赖的 ordinary_flow 面，不适用于全部 requiresCategory 面）。四个 requiresCategory 面的选项源矩阵：
  - `ordinary_flow`：按结构化方向 token（`ImportCandidateDetailRow.row.directionToken`，与 commit 工厂读取同字段）——"in" → `incomeCategories`（`OrdinaryFlowFormalFactory` in → 收入入账 commit，要求 INCOME 分类）；"out"/null → `expenseCategories`（现状不变）。
  - `credit_expense`（直付 profile）：恒 `expenseCategories`（两方向皆然）。
  - `credit_expense`（`credit_expense_refund` profile；退款行方向 token 硬编码 "in"）：恒 `expenseCategories`——退款 commit（`CreditFlowFormalFactory` → `createCreditRefundReceipt`）要求原支出的精确二级 EXPENSE 分类（`RefundReceipt` 校验 kind != EXPENSE → `InvalidRefundReceipt`），方向 token 不得翻转其选项源。
  - `mixed_payment`：恒 `expenseCategories`（两方向皆然）。
  - `transfer_flow` 面（转出/转入）无分类 section，不在矩阵内。
  - kind 与方向 token 均取自屏内既持有的候选 detail 行（`row.candidateKind` / `row.directionToken`），不跨宿主调用点。
- **`P503App.kt` 宿主接线**：传入 `incomeOptions.incomeCategories`（既有提供器 `facade.incomeOptionsProvider.queryOptions()`，入口收入流已在用；`IncomeCategoryOption(categoryId, parentCategoryId, label, postingAccountId)`）。注意收入选项为**叶分类**（入口收入表单同源），满足 `SecondaryCategoryRequired` 校验。
- **transfer_flow 面**：不变（无分类 section）。
- **决策草稿/校验/commit 路径**：零改动（`ImportDecisionFieldUpdate.Category` 事件同型；commit 工厂已按方向分派）。
- **选项排序**：沿入口收入流同源顺序（不引入新排序）。

## 3. 测试要求

- `P503ImportReviewPresentationTest`（既有测试类扩写）：ordinary in 方向候选的分类区渲染收入选项（`●/○ A02FIX-SAL-C1` 型）、不渲染支出选项；ordinary out 方向渲染支出选项（现状回归）；credit_expense 退款面 + "in" 方向 token 恒渲染支出选项（评审 F1 回归向量：退款 commit 要求原支出 EXPENSE 分类，方向 "in" 不得翻转选项源）；credit_expense 直付与 mixed_payment 两方向恒支出选项（face×方向矩阵）。
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
