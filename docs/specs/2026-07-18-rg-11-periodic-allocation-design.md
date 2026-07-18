# RG-11 周期性分摊设计

## 目标与边界

Unified Golden Schema v2 将“先付款、后按期确认”的预付业务建模为不可变领域事实。规范只定义周期性分摊，不引入 v1、mapping 或 adapter；canonical direct-v2 draft 路径为 `golden/rules/rg-11.json`。

## 领域 owner

- `periodic_allocation_schedule` 明确拥有 `payment_transaction_id`、`prepaid_account_id`、`category_id`、`total_amount`/`currency`、`cadence`、`start_at` 和 `anchor`。
- `periodic_allocation_revision` 明确拥有 `schedule_id`、`revision_number`、`recognized_through` 边界、`remaining_amount` 和精确的 `installment_ids`；已生效版本不可覆盖。
- `periodic_allocation_installment` 不可变，仅拥有 `schedule_id`、`revision_id`、`sequence`、`scheduled_at`、`amount`/`currency`；确认结果不回写为 payload 状态。

三类 owner 均使用确定性 ID，引用只指向稳定身份；展示字段改名不改变身份。每个正式交易按币种平衡。

## 正式交易与动作

正式交易类型仅为 `prepaid_purchase` 与 `prepaid_recognition`：

- `prepaid_purchase` 在实际付款日记录资产减少 100.00，并转入预付资产；现金流为 `cash_outflow=100.00`，`consumption=0`、`budget=0`、`income=0`、`net_worth=0`。
- `prepaid_recognition` 在每期确认日将预付资产转入费用；现金与收入均为 `0`，`consumption`、对应分类消费和 `budget` 均等于该期金额，`net_worth` 减少该期金额。
- `create_periodic_allocation` 需明确付款账户、金额、币种、实际付款 `occurred_at`、分摊 `start_at`、锚点、`installment_count >= 1` 与确认；付款交易的 `occurred_at`、`statistics_at`、`effective_at` 均等于输入的实际付款时间，成功后生成付款交易和初始计划。
- `recognize_periodic_allocation_installment` 只能确认当前有效版本中未确认的单期，并生成 `prepaid_recognition` 正式交易及其指向该 installment 的 typed link；不得用 payload 状态变更表示确认，重复请求返回原结果。
- `revise_periodic_allocation` 明确 `remaining_installment_count >= 1`，生成新 revision，不修改既有版本、已确认单期或历史交易。
- `correct_transaction_version` 针对事实错误追加交易版本及其 operation confirmation，保留旧版本及审计关系；除了新 ID、连续版本号、`statistics_at` 和 confirmation ownership，其他字段必须与前一版本完全相同。

## 金额与时间规则

付款金额冻结为 `100.00`，三期初始分配按总额闭合为 `33.33 / 33.33 / 33.34`，尾差进入最后一期。任意有效期数均按最小币种单位等分，前 `count-1` 期取整数商，全部余数进入最后一期。支持月末锚点和 day-of-month 1..28 锚点；`start_at` 自身必须命中锚点，每个 revision 的 installment 按 case timezone 的本地日历逐月连续，不允许固定天数近似、日期漂移、缺月、倒序或早于起点。确认日不得早于计划起点，已确认期不得重复确认。

首次确认后修订时，已确认金额保持不变；示例剩余 `66.67` 与 `remaining_installment_count=3` 必须重新分配为 `22.22 / 22.22 / 22.23`，仍由最后一期承接尾差。revision 1 的 `recognized_through` 必须为 `null`；后续 revision 的边界必须来自同 schedule 的直接前一 revision，并等于 recognition links 形成的最新连续已确认前缀。存在确认缺口、隐藏更晚已确认期或跨 revision 边界均拒绝。新 `remaining_amount` 等于计划总额减去所有此前不可变已确认金额。修订不得改变原付款现金流、已发生消费或已结预算。

若事实错误是统计时间，`correct_transaction_version` 仅追加交易版本并将统计时间改为新值；旧版本、原始时间和审计关系保留，不修改 installment 或原付款交易。

## 身份、幂等与拒绝

ID 由账本、业务 owner、版本、期序号及规范化输入确定性生成；相同请求、来源指纹或幂等键必须返回相同结果，不得重复交易、分录、现金流或确认。每个 accepted action 只返回契约规定且由该动作拥有的有序 IDs：创建返回 purchase transaction 与 schedule，确认返回 recognition transaction，修订返回新 revision，修正返回新 transaction version；重放返回原 accepted response。金额必须为正、币种一致、分配精确闭合；周期、锚点、起止边界、账户、版本状态、日期和重复确认任一无效即整笔拒绝，保持零正式账务影响。

Rejected operation 使用同 action 的 closed `attempted_input`，`operation_class=rejection`，由 validator 按稳定优先级重算 `reason_code`/`field_path`。其 result state 具有独立 state ID 和 operation ownership，但语义 payload 与 baseline 完全相同，所有 entity/value/status delta 与 returned IDs 均为空。direct-v2 rejection root 覆盖 malformed number、零/负数、unsupported/mismatched currency、invalid anchor、already-recognized installment、exceeds remaining prepaid 以及 invalid revision boundary/count。

## Direct-v2 closure

预付账户是 owned、non-real、`hidden:true` asset；分类分录账户是 non-owned、non-real expense account。`periodic_allocation_recognition` audit link 使用空 payload，并且只从 exact installment 指向 exact `prepaid_recognition` transaction。

`allocation_status` 是 derived status，不写入三类 domain payload。schedule 为 `active` 或 `recognized`；installment 为 `pending`、`recognized` 或 `superseded`。最高 revision 决定未确认未来 installment 的有效集合，已有 recognition link 始终保留其历史事实。revision 创建时只追加新 revision 和 installment；新 revision 自己记录 exact `recognized_through`，既有 revision 保持字节级不变。
