# P4-08 matcher 实施批（首批）

**Status:** proposal（纯 matcher slice；持久化与 reconciliation 后续另行评审）

## Scope

本批承接已批准的 D-103，只实现 RL-07 的首个可验收 **纯 matcher proposal slice**：保真保留来源时间事实、资金事实符号归一化、±2 自然日窗、唯一命中/无命中/同窗多命中候选提议。matcher 只返回候选和待补资料状态，不写 evidence link、posting reconciliation、正式交易或人工决定。共享表、append-only link/history、人工确认和 v22→v23 migration 留待下一份实施规格。

## Frozen Behavior

- matcher 使用 `amount_minor`、`currency_code`、`currency_precision`、`direction`、真实 `account_id` 五个资金事实门；posting 使用有符号金额，`out` 对应负 posting、`in` 对应正 posting。
- 来源时间保留原始文本、时间 kind、机械解析 components 和 offset presence；只接受已登记的 temporal kind，token/components/kind 不一致、缺少 offset 或无法解析时返回 `UNRESOLVED`，不由 Clock 补写。
- 首版窗口固定为 posting 实际资金时间前后 ±2 个自然日，使用明确登记的 `+08:00` 自然日边界；不按小时比较。
- 零候选和时间事实不完整均返回 `UNRESOLVED`；唯一且不存在时间未决竞争者时返回 `PROPOSED_MATCH`；多可比较候选返回 `AMBIGUOUS`。所有结果都是 proposal，不写 link 或 reconciliation。
- posting 候选必须属于同一 ledger，且其 transaction ownership 同属该 ledger；只有 current、eligible real-account posting 可进入资金事实比较。
- 人工决定是独立后续操作；任何 `evidence_link` 必须由显式确认携带 evidence role、match basis、human decision 四要素后再持久化。

## Persistence Shape

本批不修改 schema。下一份实施规格必须在人工确认、append-only link/history、qualified real-account posting、request identity/receipt、report reconciliation projection 和 v22→v23 migration 全部明确后，才可进入 SQLDelight writer 阶段。

## Acceptance

1. 纯 matcher 测试覆盖唯一命中、窗口边界、资金事实不一致、无命中和多命中 defer。
2. 纯 matcher 测试验证缺少 offset、时间 kind 和 posting 符号归一化。
3. 本批明确不声称 link/reconciliation/migration/retry 持久化验收；这些验收留给后续实施批。

## Non-Goals

不实现 UI、人工确认、evidence link、posting reconciliation、SQLDelight migration、P4-07 duplicate、跨来源 offset registry、报表 projection 或 RG 竖井迁移。
