# RG-12 对账修正产品设计

## 1. 依据与范围

本设计依据 D-015、D-043、D-045、D-047、D-048 及 unified v2 contract，冻结可复用的中性产品规则。修正是新交易版本，不是删除、覆盖或现金冲回。

## 2. 中性领域实体

- 通用实体 `reconciliation_match` 负责 `posting_id`、`evidence_id`，以及追加式 `status_history`。
- `status_history` 允许 `matched` 后追加 `invalidated`；失效不删除既有事实、证据或历史记录。
- 通用审计链接 `posting_replacement` 从旧 posting 指向 replacement posting，并保存关闭的 `reconciliation_effect`：`preserved`、`invalidated` 或 `not_applicable`。
- 通用 `correct_transaction_version` 的 `correction_kind` 扩展为 `statistics_time`（RG-11 既有语义）或 `posting_facts`。
- `posting_facts` 输入必须拥有完整且关闭的 `replacement_postings` 数组。每项包含 `source_posting_id`、`account_id`、`amount`、`currency`、`role`，可选 `category_id`。
- 系统不自动补齐平衡项，也不推断账户或金额；所有 replacement posting 必须由用户明确提供。

## 3. RG-12 业务生命周期

匿名交易原始当前版本：expense `+120.00`、real asset `-70.00`、real liability `-50.00`，同一 currency 且闭合。两个真实 posting 各自拥有 matched evidence；它们可独立到达，因此中间状态必须表达为 partial，第二个到达后原交易才是 fully matched。

用户明确确认 `posting_facts` 修正：liability leg 改为 `-40.00`，expense 改为 `+110.00`，asset leg 保持 `-70.00`。保持不变的 asset leg 也必须在完整 replacement set 中显式出现。

对账相关事实发生变化时，asset 与 liability leg 适用完全对称的 invalidation/rematching 规则；上述 liability 变化只是代表性案例，不能把 asset leg 视为特殊的不可修正分录。

系统在原经济/统计时间追加 transaction version 2，不产生 correction-day reversal 或 cash flow。当前 postings 为 expense `+110.00`、asset `-70.00`、liability `-40.00`。

asset replacement 沿 `posting_replacement` 继承对账关系，关闭效果为 `preserved`；liability replacement 为 pending，旧 liability match 的历史追加 `invalidated`，其旧 evidence、旧 version 和旧 postings 均保留。新的当前摘要为 partial/rematching；现金流出仍为 `70`，原期间 consumption 为 `110`，不自动填差额。

相同 correction 重试按规范化输入幂等处理，返回 `no_change`，并保持 transaction version、posting、replacement link 和 match history 的稳定 ID。

## 4. 明确拒绝边界

拒绝以下输入：

- replacement set 不完整、重复 `source_posting_id`，或未覆盖原交易全部 posting；
- posting 集合不平衡；未知、非本账本所有或 currency 不匹配的 account；
- 未显式输入却改变 unaffected matched leg；
- 缺少用户明确 confirmation；
- 修改旧 version、evidence 或既有 history；
- numeric/float amount，或以隐式计算代替精确金额。

## 5. Golden 交付约束

- canonical direct-v2 draft 路径：`golden/rules/rg-12.json`。
- `approval_status` 固定为 `draft_for_review`。
- 不使用 v1、mapping 或 adapter 路径；不引入 rg12 contract tokens。
- 本设计只冻结产品行为与审计语义，不授权自动入账、自动平衡或证据替换。
