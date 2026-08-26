# O-2 精确金额精度重标定

状态：approved（2026-08-23 用户批准；独立 O-2 小批，P5 不在范围内）

## 目标

真实来源记录可以逐行使用不同的小数位数，而正式账户的 `CurrencyUnit` 固定精度。确认装配必须把来源金额安全地表达为目标账户金额：允许等精度、向更高精度补零，以及向更低精度的精确整除；无法无损表达的金额必须拒绝。

## 契约

1. 来源事实中的 `amountMinor`、`currencyCode`、`currencyPrecision`、指纹和持久化 source row 原样保留。归一化只发生在确认装配产生正式分录时。
2. 目标币种和精度来自确认字段引用的显式账户（transfer 使用 from/to 的共同 `CurrencyUnit`；credit/refund 使用信用负债账户；mixed 使用资产腿账户，并要求两腿最终由领域校验为同一币种）。
3. 来源币种代码与目标代码不相同，或来源金额不能精确表达为目标 precision，返回 `AmountNotRepresentableInCurrency`。
4. 目标 precision 高于来源 precision 时只乘以 `10^difference`；目标 precision 低于来源 precision 时只在 minor units 能被 `10^difference` 整除时除法。没有舍入、截断或浮点转换。
5. 乘法或后续分录算术溢出返回 `DomainViolation.ArithmeticOverflow`，不伪装成精度不可表示。高于 Long 可计算范围的非零降精度金额不可表示；任意高 source scale 的零金额仍精确归一化为零。
6. 正式 posting 使用目标 `CurrencyUnit` 和归一化 minor units。mixed 的 `assetLegMinor` / `creditLegMinor` 是确认字段，冻结为已经按目标账户 minor units 表达；本批不为 leg 增加独立 scale。mixed 的持久化 total 使用归一化后的正式 total。
7. 工厂失败由现有 `commitOnce` 事务映射为 `SPINE_DOMAIN_VALIDATION_FAILED` 并回滚 claim、snapshot、status、formal rows 和余额变化；候选保持可修正重试。来源原始行和 fingerprint 不被改写。
8. O-2 不改变 P4-08 matcher/reconciliation 的 precision 相等规则，也不建立 Phase-5 Android 或正式 app composition root。普通收支只提供可复用 application factory/helper；现有来源测试装配可逐步采用它。
9. 指纹金额编码在 source precision `0..18` 保持既有带精度位的小数文本；超过 18 位时使用精确且有界的 `minorUnits` + `e-` + `precision` 文本。`currency_precision` 仍是独立字段，因此该编码只解决证据编码的有界性，不改变 source row 的原始事实。

## 覆盖矩阵

| 类别 | 覆盖 |
| --- | --- |
| 归一化 | `99` scale 0 -> `9900` scale 2；`0.5` scale 1 -> `50`；等精度；精确降精度；余数拒绝；负/超高 precision zero；乘法溢出；币种不符 |
| 工厂 | transfer、ordinary helper、credit expense/repayment/refund、mixed total/legs |
| 事务 | domain validation 失败零正式写入；修正 source/decision 后可重试成功；source raw facts/fingerprint 保持不变 |
