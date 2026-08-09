# 决定记录

## 编号规则

重要决定使用稳定的 `D-xxx` 编号。每项决定记录状态、结论、理由、影响和关联决定。决定被取代时保留原编号并指向新决定；详细产品行为和计算规则写入对应正式文档。

## D-001 统一的 3-in-1 账本

**状态：** 已确认

**决定：** 记账、账单导入和对账审计组成一个产品，共用同一正式账本。

**理由：** 三套独立结果无法稳定解释重复来源、账户余额和核验差异。

**影响：** 来源记录、候选、正式账目和对账记录必须分层保存。

**关联决定：** `D-044`

## D-002 开发顺序

**状态：** 已确认

**决定：** 先冻结规则和黄金案例，再完成导入与对账闭环，随后建立最小客户端并逐步完善体验。

**理由：** 页面和自动化不能替代账务正确性验证。

**影响：** 未通过当前阶段闸门的功能不得提前成为主线工作。

**关联决定：** `D-051`

## D-003 客户端策略

**状态：** 已确认

**决定：** Android 作为日常主客户端，Desktop 作为批量工作台，两端从一开始共享业务核心。

**理由：** 先完成一个客户端再移植会放大模型分叉和返工风险。

**影响：** 两端保持可运行外壳，但完整 UI 晚于核心闭环。

**关联决定：** `D-054`、`D-055`、`D-056`

## D-004 本地优先与可选扩展

**状态：** 原则已确认

**决定：** 本地账本是事实来源；同步和 AI 默认关闭，且不构成核心功能依赖。

**理由：** 财务数据应在离线和未配置外部服务时仍可完整处理。

**影响：** 原始凭证和敏感识别信息需要独立授权边界，具体同步协议后置。

**关联决定：** `D-052`

## D-005 平台、账户和分类分离

**状态：** 已确认

**决定：** 平台表示来源环境，账户表示资产或负债，分类表示资金用途，三者独立建模。

**理由：** 同一平台可能同时包含钱包、借贷和外部账户扣款。

**影响：** 平台名称不能决定资金方向或分类。

**关联决定：** `D-046`

## D-006 四大业务类型与分类

**状态：** 已确认，并由 `D-041` 补充

**决定：** 用户可见业务类型固定为支出、收入、转账和借贷，每类使用两级分类。

**理由：** 稳定业务类型便于录入和统计，分类名称不应承载资金方向。

**影响：** 行为使用稳定代码，分类允许自定义和改名。

**关联决定：** `D-022` 至 `D-029`、`D-041`

## D-007 简单交易与复杂关系

**状态：** 已由 `D-040` 完成确认

**决定：** 普通交易直接记录，复杂交易才建立用户可见的关联关系。

**理由：** 日常小额记录不应承担订单模型的操作负担。

**影响：** 具体关联组边界以 `D-040` 为准。

**关联决定：** `D-040`

## D-008 混合支付与合并付款

**状态：** 已确认

**决定：** 一笔业务可影响多个账户，多个业务也可共同关联一次实际付款。

**理由：** 业务拆分和外部资金流水并不总是一一对应。

**影响：** 不得为便于分配而伪造外部账户流水。

**关联决定：** `D-040`、`D-045`

## D-009 优惠口径

**状态：** 已确认

**决定：** 优惠默认只作说明，资产和收支以实际支付、负债和退款为准。

**理由：** 优惠本身不产生用户资金消耗。

**影响：** 原价和优惠可展示，但不生成虚假分录。

**关联决定：** `D-050`

## D-010 退款

**状态：** 已确认

**决定：** 退款关联原交易，并按实际到账的账户、金额和时间记录。

**理由：** 退款申请或平台状态不等于资金已经返还。

**影响：** 未到账退款不能提前改变余额。

**关联决定：** `D-047`

## D-011 借贷支付与还款

**状态：** 已确认

**决定：** 借贷消费在发生时确认消费和负债；偿还本金是资产到负债的内部流转，利息和费用另行归类。

**理由：** 将还款再次算作支出会重复统计消费。

**影响：** 本金、利息和费用必须能够区分。

**关联决定：** `D-038`、`D-039`

## D-012 定金与后付款

**状态：** 已确认

**决定：** 分阶段交易先记录应付总额，再分别记录实际定金和后续付款，履行状态由用户确认。

**理由：** 未支付尾款不是已经发生的账户变化。

**影响：** 取消、退款和定金损失保留原付款历史并按实际事件处理。

**关联决定：** `D-013`、`D-040`、`D-042`

## D-013 资金发生时间

**状态：** 已确认，并由 `D-043` 补充

**决定：** 余额和对账只按实际资金发生时间变化，不提前扣除未来付款。

**理由：** 订单约定时间不能替代账户真实变化时间。

**影响：** 订单、付款、扣款和统计时间需要分别表达。

**关联决定：** `D-042`、`D-043`

## D-014 首批来源范围

**状态：** 已确认

**决定：** 首批优先主要支付平台和电商订单；银行 PDF 解析保留为迁移能力，但不是首版硬依赖。

**理由：** 先覆盖高频来源，同时避免把难度较高的银行格式阻塞核心闭环。

**影响：** 来源适配按标准接口逐步接入。

**关联决定：** `D-020`、`D-033`

## D-015 部分对账

**状态：** 已确认

**决定：** 对账允许已核验、未核验、未知分配和完全核验并存。

**理由：** 现实证据经常只能解释一部分资金变化。

**影响：** 用户可补充资料后继续核验，系统不得强制补平。

**关联决定：** `D-035`、`D-045`

## D-016 冲突与证据等级

**状态：** 已确认

**决定：** 来源事实、用户确认、规则推断和 AI 建议分开保存，冲突由用户决定采用方式。

**理由：** 推断不能伪装成来源事实，修正也不能抹去证据。

**影响：** 每项候选保留来源、规则和置信度。

**关联决定：** `D-047`、`D-052`

## D-017 自动入账

**状态：** 已确认

**决定：** 自动入账按来源或规则单独授权且默认关闭；高风险或冲突交易保留为草稿。

**理由：** 自动化便利不能越过用户对正式账本的控制。

**影响：** 只有确定性高且明确授权的规则可以自动确认。

**关联决定：** `D-016`、`D-033`

## D-018 可授权参考实现

**状态：** 已确认，发布许可后置核验

**决定：** 已获二次修改许可的参考实现可用于功能入口、采集、导入导出和操作经验，不沿用不符合新核心的不精确金额与分散余额模型。

**理由：** 复用成熟能力能减少工作量，但不能继承错误的账务边界。

**影响：** 发布前必须归档授权凭证并确定许可范围。

**关联决定：** `D-053`

## D-019 闭源行为证据

**状态：** 已确认

**决定：** 闭源或反编译材料只用于提炼四大类型、分类、转账、借贷和余额等行为规格，不复制实现代码。

**理由：** 行为证据可帮助验证产品逻辑，但不构成可复用源码。

**影响：** 有效结论必须改写成中立规则和测试。

**关联决定：** `D-038`、`D-044`、`D-047`

## D-020 个人迁移引擎

**状态：** 已确认

**决定：** 现有个人迁移引擎中的解析、去重、镜像匹配、余额锚点、约束推断和测试基线按边界复用或重构。

**理由：** 这些能力已承载真实历史账务经验，但包含个人配置和旧结构耦合。

**影响：** 通用能力与私人配置必须拆分。

**关联决定：** `D-052`、`D-053`

## D-021 普通支出字段

**状态：** 已确认

**决定：** 金额、二级分类和付款账户为普通支出的核心字段，其他详情选填。

**理由：** 三项信息足以形成最小可统计账户变化。

**影响：** 商户、备注、凭证和标签不能成为入账前置条件。

**关联决定：** `D-022`

## D-022 支出分类深度

**状态：** 已确认

**决定：** 支出必须选择到二级分类。

**理由：** 混用一级和二级会造成统计口径不一致。

**影响：** 一级分类不可直接用于记账。

**关联决定：** `D-023`、`D-041`、`D-046`

## D-023 新建一级分类

**状态：** 已确认

**决定：** 创建可用的一级分类时必须同时创建至少一个二级分类。

**理由：** 一级分类本身不可直接记账。

**影响：** 创建流程需一次完成父分类和首个子分类。

**关联决定：** `D-022`

## D-024 二级分类同名

**状态：** 已确认

**决定：** 同一一级分类内二级名称唯一，不同一级分类下允许同名。

**理由：** 分类路径足以消除跨分组重名歧义。

**影响：** 搜索和报表在必要时显示完整路径。

**关联决定：** `D-027`

## D-025 多账本分类

**状态：** 已确认

**决定：** 每个账本独立维护分类，并可从其他账本复制全部或部分分类。

**理由：** 不同账本需要不同统计口径，又应减少重复配置。

**影响：** 复制后生成新分类，后续修改互不联动。

**关联决定：** `D-027`

## D-026 已使用分类的删除

**状态：** 已确认

**决定：** 已被历史账目使用的分类默认停用；彻底删除前必须迁移相关账目。

**理由：** 删除分类不能破坏历史账目和报表。

**影响：** 停用分类不再用于新记录，但仍可显示历史。

**关联决定：** `D-027`

## D-027 分类改名

**状态：** 已确认

**决定：** 交易关联稳定分类 ID，改名后历史记录显示当前名称并保留名称变更历史。

**理由：** 名称是可变展示信息，不应成为关联键。

**影响：** 分类改名不改变资金行为或统计归属。

**关联决定：** `D-024`、`D-046`

## D-028 普通收入字段

**状态：** 已确认

**决定：** 金额、二级收入分类和收款账户为普通收入的核心字段，其他详情选填。

**理由：** 三项信息足以形成最小可统计账户变化。

**影响：** 付款方、备注、凭证和标签不能成为入账前置条件。

**关联决定：** `D-029`

## D-029 收入分类深度

**状态：** 已确认

**决定：** 收入与支出一致，必须选择到二级分类。

**理由：** 收支采用一致分类约束可保持统计稳定。

**影响：** 新建收入一级分类时同时创建二级分类。

**关联决定：** `D-023`、`D-041`、`D-046`

## D-030 转账类型与组合账户

**状态：** 已确认

**决定：** 转账提供账户互转、信用还款、理财买入和赎回等显示类别，两端均可使用单账户或组合账户。

**理由：** 一次资金行为可能是一对多、多对一或多对多。

**影响：** 各组成账户必须产生独立真实变动并解释合计差额。

**范围修订（2026-07-15）：** 当前 core 与 `RG-03` v1 只冻结一对一账户互转。组合转账仍保留为 `future_draft` 和未来能力；原决定历史保留，不删除、不改写。

**关联决定：** `D-031`、`D-032`

## D-031 转账手续费

**状态：** 已确认

**决定：** 转账本金作为内部流转，手续费作为关联的独立支出。

**理由：** 手续费是对外费用，不属于转账本金。

**影响：** 组合转账中的每项差额必须明确归因。

**范围修订（2026-07-15）：** 手续费作为独立费用的规则当前适用。组合转账差额归因要求保留为 `future_draft` 约束，不作为 `RG-03` v1 必测范围。

**关联决定：** `D-030`

## D-032 转入信息不足

**状态：** 已确认

**决定：** 手工转账由用户选择两端；导入优先采用来源明确事实，信息不足时进入待确认且不得猜测。

**理由：** 转账另一端是余额解释的必要事实。

**影响：** 未解释资金只作为异常状态，不作为常规入口。

**关联决定：** `D-033`

## D-033 来源转账草稿

**状态：** 已确认

**决定：** 来源已明确两端、金额和费用时生成完整草稿，确认后入账；另一端来源随后作为补充证据合并。

**理由：** 同一资金流的镜像来源不能生成第二笔转账或收入。

**影响：** 导入需要镜像识别和证据合并。

**关联决定：** `D-017`、`D-032`

## D-034 实际余额来源

**状态：** 已确认

**决定：** 余额由有效账户变动重放，不能无痕覆盖；差异通过可追溯调整处理。

**理由：** 可解释余额必须能回到每条明细。

**影响：** 待付款和未发生金额不影响实际余额。

**关联决定：** `D-035`、`D-037`

## D-035 目标余额与资料不足

**状态：** 已确认

**决定：** 用户可输入目标余额，但确认后生成调整明细；资料不足时允许保留待解释差额并挂起对账。

**理由：** 便捷修正不能牺牲审计历史，也不能强迫虚假闭合。

**影响：** 界面显示账面余额、目标余额和差额。

**关联决定：** `D-034`、`D-036`、`D-037`

## D-036 余额调整统计口径

**状态：** 已确认

**决定：** 余额调整影响余额和净资产，但不进入普通收支、消费、预算或分类统计。

**理由：** 调整表示资料缺口，不是已知经济活动。

**影响：** 调整在独立记录和审计视图中展示。

**关联决定：** `D-035`、`D-037`

## D-037 真实交易替代调整

**状态：** 已确认

**决定：** 找到真实交易后，按其已解释金额冲回关联调整；完全或部分替代均保留全部历史。

**理由：** 真实交易与原调整同时生效会重复影响余额。

**影响：** 未解释的调整余额继续保留。

**关联决定：** `D-034`、`D-047`

## D-038 借贷行为与结算金额

**状态：** 已确认

**决定：** 借贷固定为借入、借出、收款和还款；结算拆分本金、利息和费用，本金不得静默跨越未结余额。

**理由：** 只有本金改变借贷余额，利息和费用属于独立收支。

**影响：** 超出未结本金的款项必须形成新的借贷行为。

**关联决定：** `D-011`、`D-039`

## D-039 首版借贷归集

**状态：** 已确认

**决定：** 首版按往来对象维护借贷净余额，不实现合同级事项和还款分配。

**理由：** 个人轻量借贷优先保证易用和余额正确。

**影响：** 每条明细仍独立保留金额、时间、利息、费用和备注。

**关联决定：** `D-038`

## D-040 普通账目与复杂关联组

**状态：** 已确认

**决定：** 普通支出、收入和转账不创建显式订单；混合支付、合并付款和分阶段付款使用具体命名的关联组。

**理由：** 只在多笔业务与资金变化需要映射时引入关系模型。

**影响：** 用户无需理解底层关系结构。

**关联决定：** `D-007`、`D-008`、`D-012`

## D-041 分类层级上限

**状态：** 已确认

**决定：** 首版界面和底层分类均固定为一级与二级，不预埋无限层级。

**理由：** 当前没有更多层级的明确需求，提前泛化会增加复杂度。

**影响：** 只有出现真实需求后才评估结构迁移。

**关联决定：** `D-006`、`D-022`、`D-029`

## D-042 定金尾款统计时间

**状态：** 已确认

**决定：** 定金和尾款分别在实际付款日期进入消费统计，完成订单时不重复确认。

**理由：** 消费统计应与已经发生的付款一致。

**影响：** 未付款金额既不影响余额，也不进入消费统计。

**关联决定：** `D-012`、`D-013`

## D-043 账目时间与凭证时间

**状态：** 已确认

**决定：** 导入和自动记录默认采用来源支付时间；用户可修改账目统计时间，但原凭证时间独立保留。

**理由：** 报表归属可以调整，来源事实不能被覆盖。

**影响：** 手工账目保留修改历史，证据匹配继续参考原凭证时间。

**关联决定：** `D-013`、`D-047`、`D-048`

## D-044 三种入口与输出关系

**状态：** 已确认

**决定：** 手工记录、自动记录和账单导入是并列入口，均形成统一账目；余额与报表由有效账目推导，对账是并列的独立核验流程。

**理由：** 手工记录不依赖外部凭证，对账也不是报表之后的末端步骤。

**影响：** 导入和自动记录额外保留来源证据，对账状态不改变余额。

**关联决定：** `D-001`、`D-045`

## D-045 分录级对账

**状态：** 已确认

**决定：** 对账状态落在每条真实账户分录，交易状态由其分录汇总为待对账、部分对账或完全对账。

**理由：** 混合支付中的不同账户可能具有不同核验进度。

**影响：** 对账只描述证据状态，不改变交易金额或余额。

**关联决定：** `D-015`、`D-044`、`D-048`

## D-046 用户分类与内部账户

**状态：** 已确认

**决定：** 二级支出和收入分类分别对应隐藏费用与收入账户，一级分类只作分组；真实金融账户保持独立维度。

**理由：** 用户需要简单分类体验，核心需要可平衡的账户结构。

**影响：** 界面不展示专业账户术语，分类 ID 作为稳定关联。

**关联决定：** `D-005`、`D-027`、`D-041`

## D-047 录入修正与真实冲回

**状态：** 已确认

**决定：** 录入错误用版本替代，旧版本保留但失效；退款、撤销等真实反向资金变化才创建独立经济事件。

**理由：** 把编辑伪造成修改当天的冲回会制造错误时间和现金流。

**影响：** 正确版本按真实发生时间生效，凭证事实和版本历史均保留。

**关联决定：** `D-010`、`D-043`、`D-048`

## D-048 修正后重新对账

**状态：** 已确认

**决定：** 修改金额、真实账户或币种时，仅失效受影响分录的原匹配并自动重新核验；非资金字段修改不重置匹配。

**理由：** 修正可能改变证据对应关系，但不应破坏无关分录的核验结果。

**影响：** 原匹配历史保留，结果可显示重新核对、有差异或待补资料。

**关联决定：** `D-043`、`D-045`、`D-047`

## D-049 一次付款与跨期分摊

**状态：** 已确认，界面入口后置

**决定：** 已全额支付的长期消费可由用户启用按期分摊；付款日全额影响真实账户，后续只调整预付资产与费用期间。

**理由：** 现金流时间与消费归属期间可能不同。

**影响：** 核心保留预付资产和分摊计划，首版默认仍在付款日一次确认消费。

**关联决定：** `D-013`、`D-050`

## D-050 商户储值

**状态：** 已确认

**决定：** 默认在向商户充值时确认支出；用户也可主动建立受限资产账户，使充值成为转账、使用时确认消费。

**理由：** 简单用户需要直接口径，精细用户需要余额核对和逐笔消费。

**影响：** 两种口径不得叠加；到期损失、赠送权益和既有余额接入均需保留可追溯记录。

**关联决定：** `D-009`、`D-034`、`D-049`

## D-051 两层黄金测试

**状态：** 已确认

**决定：** 核心验收由 12 个公开匿名规则场景和 8 个仅本地运行的真实来源场景组成，并区分必需、预留和未来草案级别。

**理由：** 匿名场景适合持续验证规则，真实来源场景用于证明迁移和导入结果能够进入同一核心。

**影响：** 每个正式案例比较完整交易、分录、余额、报表、对账和证据，不只比较最终总额。

**关联决定：** `D-002`、`D-053`

## D-052 迁移引擎与 AI 的位置

**状态：** 已确认

**决定：** Python 迁移引擎用于旧账迁移、规则原型、余额重建和黄金基线；AI 首版只保留服务商无关边界，不实现具体功能。

**理由：** 确定性能力可以直接验证，AI 不应成为正式账本依赖。

**影响：** 解析器、约束求解和未来 AI 都只能生成带来源与置信度的候选。

**关联决定：** `D-004`、`D-020`、`D-053`

## D-053 Python 代码提炼路线

**状态：** 已确认

**决定：** 先用黄金测试冻结现有行为，再拆分通用核心、来源适配、私人配置和迁移工具，最后逐项移植已确认能力。

**理由：** 现有代码具有真实价值，但个人规则和单资金桶结构不能直接成为正式产品核心。

**影响：** 私人账户、别名、锚点和特殊修正不得进入公开核心；约束结果只能形成候选。

**关联决定：** `D-020`、`D-051`、`D-052`、`D-054`

## D-054 Kotlin Multiplatform 共享核心

**状态：** 已确认

**决定：** 使用 Kotlin Multiplatform 承载 Android 与 Desktop 共用的完整业务核心。

**理由：** 共享领域模型和用例可以避免双端账务行为分叉。

**影响：** 黄金测试通过后再建立生产核心，Python 仅作为迁移与验证基线。

**关联决定：** `D-003`、`D-053`

## D-055 双端最小外壳

**状态：** 已确认

**决定：** Android 与 Desktop 在核心开发早期均保持最小可运行、可持续构建的外壳。

**理由：** 尽早验证平台集成边界可降低后期移植风险。

**影响：** 外壳只负责组合与平台入口，不提前扩展完整 UI。

**关联决定：** `D-003`、`D-054`、`D-056`

## D-056 共享与平台边界

**状态：** 已确认

**决定：** 完整业务核心共享，平台系统能力和 UI 分别实现，并通过明确接口调用核心。

**理由：** 账务行为必须一致，而权限、文件、窗口和交互具有平台差异。

**影响：** 平台模块不得成为业务核心的依赖。

**关联决定：** `D-054`、`D-055`

## D-057 具体依赖选择时机

**状态：** 已确认

**决定：** 数据库、UI、导航、依赖注入、网络和同步等具体库在黄金测试与模块接口明确后再选择。

**理由：** 当前证据不足以证明具体依赖满足长期边界，提前锁定会反向塑造模型。

**影响：** 架构文档只记录决定门槛，不把未经比较的候选写成已采用技术。

**关联决定：** `D-002`、`D-054`、`D-056`

## D-058 混合支付的消费与现金流时点

**状态：** 已确认

**决定：** 混合支付在购买日按费用或业务总额确认一次消费，现金流只按当日实际减少的资产资金确认；信用负债融资不计购买日现金流出。以后偿还该负债本金时，按还款日确认资产现金流出并记录信用还款，但不再次确认消费、收入或净资产变化。

**理由：** 消费描述业务价值消耗，现金流描述资产资金的实际时点；把信用融资提前计为现金流或把本金还款再次计为消费都会重复统计同一经济事实。

**影响：** 例如消费 `120.00 CNY` 由资产 A 支付 `70.00`、信用负债 B 支付 `50.00` 时，购买日分录为费用 `+120.00`、资产 A `-70.00`、负债 B `-50.00`，消费 `120.00`、现金流出 `70.00`、净资产变化 `-120.00`。以后偿还本金 `50.00` 时分录为资产 A `-50.00`、负债 B `+50.00`，现金流出 `50.00`，消费、收入和净资产变化均为零。

**关联决定：** `D-008`、`D-030`、`D-040`、`D-044`、`D-045`、`D-046`

## D-059 合并付款的业务独立性与统计时点

**状态：** 已确认

**决定：** 两条用户可见的消费记录共享一次实际付款时，分别保留稳定 ID、二级分类和明细，并由同一笔平衡的正式交易承载；首版统一使用实际付款时间作为两条消费的统计时间。

**理由：** 业务项目需要保持可独立识别和分类，而资产账户只发生一次真实扣款；统一付款时点可在首版避免项目时间与单一资金时点产生不可解释的报表分歧。

**影响：** 正式交易包含每个消费项目各自的费用分录和唯一资产付款分录。项目原始时间作为不可变来源证据保留，但首版不允许项目级统计时间覆盖，也不得为拆分展示伪造第二条资金流水、清算账户或补平分录。

**关联决定：** `D-008`、`D-040`、`D-043`、`D-044`、`D-045`、`D-046`、`D-058`

## D-060 分阶段付款的付款进度与履约状态

**状态：** 已确认

**决定：** 分阶段付款分别保存由已确认实际付款推导的付款进度与由用户明确控制的履约状态。付款进度为未付款、部分付款或已付清：已付为零时必须是未付款，已付大于零且待付大于零时必须是部分付款，待付为零时才可以是已付清；履约状态为履约中或已履约，并允许已履约时仍有待付金额。两类状态必须分开显示，任何状态变化都保留历史，且不创建或改变正式交易、分录、余额、消费、现金流、收入、净资产或对账结果。

**理由：** 交付事实与资金事实可能发生在不同时间。把“已交付”解释成“已付清”，或让状态操作产生资金影响，都会造成含义混淆和重复入账。

**影响：** 界面分别显示例如“履约：已交付”和“付款：待付 220.00”；每笔定金或尾款仍以自己的实际付款交易、分录、统计时间和证据独立入账与对账，关联组只汇总应付、已付、待付和状态。

**关联决定：** `D-012`、`D-013`、`D-040`、`D-042`、`D-043`、`D-044`、`D-045`

## D-061 退款到账报告、累计上限与证据职责

**状态：** 已确认

**决定：** 退款申请、批准和处理中状态是关联原支出的非财务事实，只有明确确认实际到账后才按到账账户、金额和时间创建独立退款交易。退款在到账期冲减原交易的精确二级支出分类并记退款现金流入，不记普通收入，也不改写原交易或原统计期间。同币种有效关联退款的累计金额不得超过原交易已确认的可退费用；超额分配整体拒绝，未分配金额继续等待明确分类。商户退款通知只证明退款关系或状态，账户入账证据只核对精确到账资产分录。导入银行或钱包入账时，退款与原交易的关系由用户明确确认记录建立，入账来源不得被提升为退款关系证据；真正同时包含关系与到账事实的其他复合来源必须分别保存两个证据职责链接。

**理由：** 退款状态、资金到账、消费归属和账户核验是不同事实。提前冲减、回写原期、把退款列为普通收入、静默突破原费用上限，或用商户通知替代账户入账证据，都会造成余额、报告或审计含义错误。

**影响：** 退款可进入与原付款不同但有证据或经用户明确确认的自有资产账户。导入退款在原交易、精确分类与分配、到账账户和到账事实全部明确确认前保持待确认并产生零正式影响；未确认到账同样保持完整操作前状态。原付款分录的对账结果保持不变，到账资产分录独立对账。首版只支持同一 `CNY` 币种、单一原二级支出分类和自有真实资产到账。

**关联决定：** `D-010`、`D-013`、`D-043`、`D-044`、`D-045`、`D-047`

## D-062 借出收款的现金口径、上限、身份与证据职责

**状态：** 已确认

**决定：** 首版借贷继续按稳定往来对象 ID 维护个人级净本金余额，不分配到合同或某笔借出。借出本金在实际支付日记借贷本金现金流出，但不记消费、费用、收入或净资产变化；收款在实际到账日按明确拆分分别记录本金现金流入和利息现金流入，只有实际收到的利息进入普通利息收入并增加净资产，预计或应计利息只作元数据。`RG-08` v1 要求费用组成被明确确认为 `0.00`，不冻结任何非零费用的资金方向、账户或报告语义。收款本金不得超过该往来对象的未结应收本金，也不得跨越零点；超额分配整笔保持待明确重新分配，不自动截断，也不猜测为收入、借入、普通收款或清算分录。往来对象改名不改变余额或历史归属，重复对象合并必须另行执行明确且可审计的操作。

银行或钱包入账证据只证明金额、到账账户和其实际提供的入账时间，不证明借贷行为、往来对象或本金、利息与费用拆分。借款协议或收据可以证明往来对象和借贷关系，但不能替代到账资产分录证据。导入收款只有在借贷行为、稳定往来对象、到账账户、本金、利息与费用以及实际到账时间全部被明确确认后才正式入账；预计利息和名称匹配不能自动确认拆分或身份。每项证据使用明确职责链接到现有精确目标，到账资产分录独立对账，证据状态不改变余额或报表。

**理由：** 本金是资产形态变化，利息是实际实现的收益，二者的报告和余额含义不同。按现金事实确认、对本金执行原子上限、使用稳定身份并分离证据职责，可以避免提前确认收入、静默跨零、错误归人以及用关系材料伪造银行核验。

**影响：** 例如从资产 A 借出 `100.00 CNY` 时，应收往来对象资产为 `+100.00`、资产 A 为 `-100.00`，借贷本金现金流出为 `100.00`，消费、费用、收入和净资产变化均为零。以后资产 B 实收 `45.00 CNY` 并明确拆为本金 `40.00`、利息 `5.00`、费用 `0.00` 时，资产 B 为 `+45.00`、应收资产为 `-40.00`、精确利息收入账户为 `-5.00`；本金现金流入为 `40.00`、利息现金流入为 `5.00`、总现金流入为 `45.00`、普通利息收入和净资产增加均为 `5.00`，剩余应收本金为 `60.00`。负数费用和任何正数非零费用均拒绝并保持完整基线；首版不覆盖合同级借贷、应计利息、抵押品、外汇、豁免、往来对象合并、税务、非零结算费用会计，以及超出明确组成金额的催收或费用生命周期。

**关联决定：** `D-011`、`D-038`、`D-039`、`D-043`、`D-044`、`D-045`、`D-058`

## D-063 余额调整对手账户、有效时间、解释触发与核验语义

**状态：** 已确认

**决定：** 目标余额调整必须使用系统管理且普通界面隐藏的专用“余额调整”权益账户作为唯一对手账户，不得使用收入、费用、期初权益、暂记资产或悬空账户。调整交易在目标余额的观察时点生效；以后确认能够解释差额的真实交易时，真实交易仍按自己的实际发生时间入账，只有用户明确确认精确交易、目标账户、实际时间、币种和解释分配后，才为每个分配原子创建一笔链接原调整的反向调整。反向调整与原调整在同一目标观察时点生效，使该历史目标继续成立；发现时间和确认时间只进入审计历史，不改变资金期间。匹配器、导入置信度或数值相似不能自动建立解释或触发反向调整。

真实交易确认与解释链接确认是两个独立的明确操作。前者只创建真实交易、自己的版本和确认历史；后者必须以已经正式存在的精确真实交易为输入，只创建解释分配、唯一反向调整和其审计链接。手工和导入入口均不得把两次确认合并，任何目标账户、实际发生时间、币种、金额、解释金额和确认布尔事实都必须由当前操作明确提供，不得从候选、名称、金额相似或先前操作推断。

交易的 `occurred_at`、`statistics_at` 和 `effective_at` 表示经济时间；交易、版本和审计事件的 `created_at` 表示实际创建或确认时间。反向调整的经济时间仍是原目标观察时点，但其创建时间必须是解释确认时间；预览变更导致的历史交易也必须保留其历史发生时间，并使用晚于预览变更的创建时间。

余额数值与目标一致但仍有非零调整余额时，核验状态必须是“余额一致但仍有未解释调整”，不能显示为完全核验。剩余调整为零只表示解释分配已经完整；只要任一必需真实账户分录证据尚未精确匹配，核验仍必须保持不完整。每条真实账户证据链接是独立操作，只有最后一个缺失链接完成且全部目标类型、账户、币种、金额和方向一致时，才可以汇总为完全核验。核验、证据链接和状态变化永远不改变交易、余额或报告。

**理由：** 专用权益对手账户把资料缺口与真实收入、费用和期初接入分开；目标时点的同日反向调整既保留真实交易的实际时间，又避免后来补录造成历史余额重复。明确触发和严格核验语义可以防止高置信候选静默改账，也避免把“数值碰巧一致”误报为证据完整。

**影响：** 单账户同币种调整按分配累计推导为 `open`、`partially_explained` 或 `fully_explained`。解释金额必须与原差额方向、目标账户和币种一致，真实交易时间不得晚于目标观察时点，累计解释绝对值不得超过剩余调整；任何冲突都整项拒绝。第二笔有效真实交易和分配可以解释剩余差额并使调整为 `fully_explained`，但在所有实际资产分录证据齐全前仍不得完全核验。原调整、真实交易、反向调整、分配、观察、证据和历史全部追加保存，每个已确认分配恰好对应一笔反向调整，不允许删除或改写。

**关联决定：** `D-034`、`D-035`、`D-036`、`D-037`、`D-043`、`D-044`、`D-045`、`D-047`

## D-064 储值资产、赠送权益、批次与证据

**状态：** 已确认

**决定：** 已启用的商户受限储值账户采用面值资产口径。充值以实际付款账户流出、储值资产按商户确认的可用面值流入，实付与到账差额进入专用赠送权益收益；充值批次保留实付、赠送、有效期、载入时间和来源关系。实付和到账必须为正的精确金额，赠送必须为零或正的精确金额，并满足到账等于实付加赠送。后续消费只减少储值资产并进入明确的二级费用分类。没有商户分配证据时，批次按最早到期日、载入时间和稳定批次 ID 的确定顺序消耗，但不推断实付与赠送组成；商户提供分配时保存其分配结果。

**理由：** 储值资产必须能与商户余额核对，同时不能把赠送额伪装成现金收入或把充值再次计作消费。批次顺序需要可重放，组成未知时必须保留未知而不是制造 paid-first 或 bonus-first 假设。

**影响：** 账户余额、消费、现金流、特殊非现金收益和到期损失分开推导。RG-10 v1 不适用预算执行，充值、消费和到期的预算影响均为零，只有实际消费产生分类影响。到期提醒、日期和状态不改变账务；只有用户明确确认实际失效的金额才生成到期损失。用户确认或商户失效事实不等于金融对账证据，除非有精确账户证据，储值资产失效分录保持待核验。银行付款证据与商户余额证据分别按职责核验对应真实分录，任何一方不能替代另一方。

**关联决定：** `D-034`、`D-035`、`D-036`、`D-037`、`D-043`、`D-044`、`D-045`、`D-047`、`D-049`、`D-050`

## D-065 目标时点重放指纹

**状态：** 已确认

**决定：** RG-09 目标时点重放指纹只投影目标时点及以前的当前有效分录，投影容器固定为 `{"postings":[...]}`，每项字段固定为交易 ID、当前版本 ID、`effective_at`、分录 ID、账户 ID、币种和金额。`postings` 数组按 `(effective_at, transaction_id, current_version_id, posting_id, account_id, currency, amount)` 字典序排序，再使用 RFC 8785 JCS 规范化并计算 SHA-256；`created_at`、证据、对账、报表、派生状态和状态 ID 均不进入投影。

**理由：** 指纹必须只代表确认所依据的经济事实，才能稳定识别预览与确认之间真正影响目标余额的变化，而不被审计时间或非经济状态扰动。

**影响：** 目标时点及以前的经济事实发生任何变化都必须改变指纹，纯证据或纯对账变化不得改变指纹。确认时指纹过期必须原子拒绝整个操作，并返回包含 `preview_ledger_fingerprint`、`current_ledger_fingerprint`、`recomputed_replay_amount` 和 `recomputed_delta` 的重新计算诊断，不得使用旧预览部分入账或静默继续。

**关联决定：** `D-034`、`D-035`、`D-037`、`D-043`、`D-044`、`D-063`

## D-066 储值账户与分类目录契约

**状态：** 已确认

**决定：** 账户可选且至多拥有一个封闭的 `stored_value` 配置对象；对象存在即表示账户具备储值能力，其字段固定为 `enabled`、`merchant_restricted` 和 `merchant_id`，禁止用多个可能互相矛盾的布尔字段表达同一能力。`system_role` 注册 `stored_value_bonus_right_income`、`stored_value_expiry_loss` 和 `stored_value_pre_activation_adjustment`；规范交易 token 继续遵循 `D-064` 与 `GOLDEN_SCHEMA`，不得静默改名。分类继续使用 `posting_account_id` 和 `parent_id`，数值 `level` 只从父链派生，不单独存储。

**理由：** 单一封闭配置与稳定系统角色可以消除能力表达冲突，并使储值专用账户职责可验证；分类身份必须由稳定父子关系决定，不能由可失真或缺少身份信息的层级数字决定。

**影响：** 旧数据只有 `level` 而没有父分类身份时，必须保持待确认并要求明确的分类映射，不得猜测 `parent_id`。目录迁移、校验和展示均从父链计算层级，现有规范交易类型名称保持兼容。

**关联决定：** `D-041`、`D-046`、`D-050`、`D-064`

## D-067 储值追溯重建与唯一经济归属

**状态：** 已确认

**决定：** 建立独立的 `stored_value_reconstruction` 领域实体，由其持有原调整 ID、重建交易 ID 集合、取值为 `adjustment` 或 `reconstructed` 的 `active_mode`，以及只追加的历史；关系对象不持有状态。原调整与重建实体永久保留，任一时刻必须且只能有一个经济归属方生效，并由类型化审计链接表达替代端点。

**理由：** 重建是经济影响归属的可审计切换，不是对原记录的覆盖编辑，也不应把生命周期状态塞入仅用于连接身份的关系。

**影响：** 禁止原地改写、原调整与重建交易重复计入，以及跨重建组替代。任何模式变化都追加历史并保留原端点与重建端点，使余额重放能够确定唯一有效经济归属。

**关联决定：** `D-037`、`D-047`、`D-050`、`D-063`、`D-064`

## D-068 储值赠送与到期证据职责

**状态：** 已确认

**决定：** 储值领域事实使用 `stored_value_bonus_component` 和 `stored_value_expiry_event` 两个子类型。赠送事实不可变；到期事件持有只追加的 `reminder`、`confirmed` 生命周期。`reminder` 的正式影响为零，只有 `confirmed` 才允许创建到期损失交易。新增赠送与到期 evidence-link role token 固定为 `stored_value_bonus_component` 和 `stored_value_expiry_confirmation`，分别指向精确的 `stored_value_bonus_component` 与 `stored_value_expiry_event` 领域事实；`stored_value_expiry_event` 只作为领域子类型，不作为 link role。只有这两类新增证据链接不得指向交易或分录、不得复用 `lot-fact` 链接，也不得改变分录对账状态；现有 `stored_value_asset_posting` 继续指向精确储值资产分录并承担其证据与对账职责。

**理由：** 赠送组成、到期状态、经济确认、证据核验和金融分录对账是不同职责；分离其所有者可以防止提醒提前入账、证据状态代替用户确认或领域证据污染账户核验。

**影响：** 证据核验与经济确认必须作为独立操作保存和判断。新增或变更证据只能更新对应领域事实的核验结果，不能自行创建到期损失、改变正式账目，或使任何真实账户分录变为已对账。

**关联决定：** `D-043`、`D-044`、`D-045`、`D-047`、`D-050`、`D-064`

## D-069 RG-01 Golden JSON 解码边界

**状态：** 已确认

**决定：** `ledger-application/commonMain` 使用 Apache-2.0 的 `kotlinx-serialization-json 1.11.0` runtime-only 解析 RG-01 v1 raw JSON，不启用 serialization compiler plugin，不引入 Ktor。decoder 在 JSON tree 映射前拒绝 RFC 8259 重复对象名，包括转义后同名；受支持对象使用显式 closed-key 和类型校验，不暴露 library exception 文本。金额必须是 JSON string，并以原文交给现有精确金额 parser。raw UTF-8 输入最大 1 MiB、JSON 嵌套最大 64 层，超限返回类型化资源拒绝。

**理由：** RG-01 fixture 是冻结的结构化输入，tree API 足以支持小范围手工映射，无需生成 serializer 或网络栈；前置重复键检查和封闭字段映射避免 parser 覆盖歧义与静默接受扩展字段。

**影响：** 当前执行范围固定为 v1 create、retry 与 distinct re-entry 经现有 typed adapter、application use case 和 SQLDelight port；7 个 invalid outcomes 在 typed adapter 前置拒绝，必须保持 application strict 路径、commit port 与数据库零变化。两类结果均只与 approved v2 operations 事后比较，approved output 不配置执行 ID。`note_update`、完整 state/report/reconciliation/delta comparison、v1 fixture rewrite、migration publication、其他 RG adapter 和网络序列化选择均不由本决定授权。

**关联决定：** `D-051`、`D-054`、`D-057`

## D-070 RG-01 备注修正执行边界

**状态：** 已确认

**决定：** RG-01 `note_update` 接受严格的 raw request，并从已批准的 mapping 派生明确 confirmation。持久化使用专用 schema v3 的 request、receipt 和 confirmation owner，不抽象为通用 operation 表。同一 request 且等价 snapshot 返回 `NoChange`；同一 request 但 snapshot 不同返回 conflict；当前版本已变化时，CAS 必须返回类型化拒绝且零写入。成功修正以只追加的版本替代实现，复用原交易的分录与经济时间，不产生新的资金影响。v1 执行输入与冻结的替代版本身份来自 v1，confirmation 独立派生；approved v2 仅作为 operation 结果的事后 oracle。

**理由：** 备注修正是非资金字段的录入修正，应保留版本历史与请求语义，同时不能让输出 oracle 反向配置执行身份，或以宽泛的 operation 表预设其他场景尚未确认的生命周期。

**影响：** 本切片只验证 operation、版本、分录、经济时间和余额的零资金影响；不授权完整 state、report、reconciliation 或通用 delta 比较，也不授权 v1 fixture rewrite、其他字段修正或其他 RG adapter。实现继续遵循 `D-047` 的版本替代和 `D-048` 的非资金字段修正语义。

**关联决定：** `D-047`、`D-048`、`D-069`

## D-071 RG-02 手工普通收入执行边界

**状态：** 已确认

**决定：** RG-02 以严格 raw JSON 执行 `manual_income` 的最小闭环：主创建、同请求重试、红包钱包和项目款银行卡两个独立变体，以及冻结 v1 中的八条拒绝路径。目录增加专用 `CategoryKind`；收入请求只接受 active 的二级收入分类，并使用其关联的隐藏收入账户。领域增加 `INCOME` 正式交易：真实收款资产分录为正、隐藏收入账户分录为相同金额的负值，逐币种平衡；`occurred_at` 同时作为该 slice 的 occurred、statistics 和 effective 经济时间。

持久化使用专用 schema v4 的 manual-income request、receipt 和 confirmation owner，不泛化为通用 operation 表。相同 request 且等价 snapshot 原子重放原 receipt，不创建第二笔交易、版本或分录；相同 request 但 snapshot 不同原子返回 conflict，且不得留下部分状态。确认、领域验证和持久化失败同样不得留下 request claim 或正式账务副作用。

v1 是唯一执行输入与正式身份来源；approved v2 只能在执行完成后比较 operation 结果、类型化拒绝和 returned IDs，不能反向配置执行 ID。`category_rename` 仍按 closed raw JSON 结构严格解码，但本 slice 返回 unsupported；不实现目录改名或名称版本生命周期。

**理由：** 普通收入与支出共享明确确认、严格输入和请求语义，却要求收入专属的分类方向、隐藏账户和正式交易语义。专用 owner 既能保证回放与原子边界，又不预设后续规则的生命周期或表结构；把 v2 限为事后 oracle 防止输出成为执行配置。

**影响：** 本 slice 验证 accepted income 的 transaction/version/posting、三种经济时间、逐币种平衡、重放/冲突与八条零副作用拒绝；不授权完整 state、report、reconciliation 或 delta 比较，不实现 transaction correction、CAS、分类改名/name-version、导入、来源证据、对账、预算或通用 operation owner。实现继续遵循 `D-028`、`D-029`、`D-041`、`D-046` 的收入分类与隐藏账户规则，并复用 `D-069` 已确认的严格 JSON 原语。

**关联决定：** `D-028`、`D-029`、`D-041`、`D-046`、`D-069`、`D-070`

## D-072 RG-04 手工混合支付执行边界

**状态：** 已确认

**决定：** RG-04 以严格 raw JSON 执行 18 个手工 operation：`manual_mixed_expense` 和 `credit_principal_repayment` 各自的 accepted creation 与同请求 `no_change`，以及冻结的 14 条 `manual_mixed_expense` 拒绝路径。v1 是唯一执行输入与稳定正式身份来源；approved v2 只在执行完成后比较这 18 项的 operation projection，不得反向配置执行 ID 或输入。八个 `ingest_mixed_payment_source`、`confirm_mixed_payment_candidate` 和 `merge_mixed_payment_mirror_evidence` operation 只识别为 unsupported，不实现其运行时合同。

严格 JSON 共享抽取只限中性的前置检查：1 MiB UTF-8 上限、64 层容器上限、转义等价名称的重复键检测，以及语法和根对象检查；RG-01、RG-02、RG-03 既有错误类型与精确路径保持不变，不建立通用 tree mapper 或场景 adapter。领域新增 `CREDIT_REPAYMENT`，不得以 `ACCOUNT_TRANSFER` 代替。混合消费创建一笔费用 `+120.00`、资产资金 `-70.00`、信用负债资金 `-50.00` 的平衡交易；本金还款创建资产 `-50.00`、信用负债 `+50.00` 的平衡交易，不重复确认消费。

持久化使用 schema v6，并提供 v5 到 v6 迁移。RG-04 专属 owner 保存 request snapshot、confirmation、receipt、posting semantics、结算说明事实、混合支付组成和初始分录对账；不得泛化 `rg03_*` 表。`mixed_payment` 关系只持有一个购买交易成员和两条既有资金分录成员，系统管理、显示名、总额及恰好两个正向资金组成由 RG-04 专属 payload 持有。每条真实资金或还款分录初始为 `PENDING`，费用分录不创建对账记录。相同 request 的重放采用类型化、规范化、逐字段等价比较；action 或任一语义字段变化返回 conflict。accepted 持久化必须原子完成，领域拒绝及正式账务、关系、对账或 receipt 写入阶段的任一失败都不得留下残留状态。

**理由：** 手工混合支付和信用本金还款已经具有冻结的账务、身份、关系与初始对账答案，可以复用现有精确金额、目录、平衡分录、版本、余额重放和原子请求模式；导入候选、镜像证据与对账状态迁移仍需要独立闭环。保持场景专属 owner 和受限共享抽取可避免提前固化错误的跨场景抽象。

**影响：** 本切片不授权 evidence matching、对账状态迁移、完整 state/report/reconciliation/delta comparison、v1 fixture rewrite、publication，也不实现八个延期 operation。详细行为和字段所有权继续以 RG-04 正式设计、approved v2 mapping 与 expected oracle 为准。

**关联决定：** `D-008`、`D-011`、`D-015`、`D-017`、`D-040`、`D-043`、`D-044`、`D-045`、`D-058`、`D-069`、`D-071`

## D-073 RG-04 导入确认与分录级对账执行边界

**状态：** 已确认

**决定：** RG-04 在 `D-072` 的 18 个手工 operation 之外，以严格 raw v1 执行其余 8 个 operation：完整来源 intake、明确候选确认、负债镜像证据合并和缺失资金腿 intake 各自的首次接受与等价重放。完整来源和缺失资金腿都只创建来源、证据与待确认候选；缺失资金腿保留已知 `70.00 CNY` 和缺失 `50.00 CNY`，不得猜测缺失账户或创建平衡分录。只有明确候选确认可以复用混合消费领域用例创建费用 `+120.00`、资产 `-70.00`、信用负债 `-50.00` 的正式交易与 `mixed_payment` 关系。资产证据匹配后该分录为已核验、负债分录保持待核验；后续负债镜像只追加来源、证据与匹配事实，使其成为已核验，不得改变正式交易、版本、分录、关系、余额、报表或候选绑定。

持久化升级为 schema v7，并保留 schema v6 的手工 owner。导入使用独立的 request/snapshot/receipt、source、evidence、candidate/status、confirmation 和 append-only evidence-match owner，不扩展要求 transaction/confirmation 身份的手工 receipt，也不依赖 `rg03_*` 表或进程内生命周期状态。镜像目标必须沿持久化的 candidate、confirmation、transaction current version 和 posting ownership 解析。相同请求与等价快照返回原稳定身份；action、快照或 owner 身份冲突返回原子 conflict；已确认候选的新确认请求返回 `candidate_not_pending`；目标缺失、歧义、字段不匹配或对账前置条件失败均返回类型化原子拒绝。

**理由：** 来源事实、候选推断、用户确认和分录核验具有不同生命周期与写入权限。分离 owner 并以只追加匹配事实派生核验结果，可以完成混合支付导入闭环，同时防止证据合并重复入账、自动补平或依赖进程状态。

**影响：** RG-04 raw v1 的 26 个 operation 均具有运行时边界；v1 继续是执行输入与身份来源。完整 state/report/reconciliation/delta 比较、v2 oracle 接受、fixture publication 和其他规则场景不在本决定范围内，不能据此宣称 RG-04 已全部关闭。

**关联决定：** `D-008`、`D-015`、`D-017`、`D-040`、`D-043`、`D-044`、`D-045`、`D-058`、`D-069`、`D-072`

## D-074 RG-05 合并付款执行边界

**状态：** 已确认

**决定：** RG-05 以严格 raw v1 执行四个 action。`manual_merged_payment` 在明确确认下创建一笔费用交易：两条正向费用分录与一条自有真实资产 `payment_asset` 负向分录，两条独立 consumption_record 与 item_allocation，一个 `merged_payment` 关联组绑定该交易、唯一付款分录与两条 allocation。`ingest_merged_payment_facts` 只创建来源、证据与一个待确认候选，零正式、余额、报表与对账效果。只有 `confirm_merged_payment_candidate` 可以创建正式结果并追加 confirmed 候选历史；分摊低于付款总额拒绝为 `allocation_incomplete`，高于则拒绝为 `allocation_conflict`，两者都保持候选待确认且零效果。`merge_item_receipt_evidence` 只追加来源、证据与一条 allocation 证据链接，不创建任何正式实体。

持久化升级为 schema v8 并保留既有 owner。财务对账只属于唯一自有真实 `payment_asset` 分录，两条费用分录不具备对账资格；关联组的 `item_evidence_completeness` 由精确 receipt-to-allocation 链接独立派生，其变化不改变正式交易、版本、分录、余额、报表或财务对账。相同请求与等价快照返回原稳定身份，快照不一致返回原子 identity conflict。

确定性身份必须由契约 v2 生成器产出：命名空间、name 拼接、entity kind、source locator 与 occurrence discriminator 均与 `tools/python/golden_cases/v2.py` 一致，运行时产出与已冻结 expected 输出逐字相等。各场景不得各自实现该生成器。拒绝结果的 `field` 使用契约输入节点粒度。

**理由：** 来源事实、候选推断、用户确认与分录核验具有不同生命周期与写入权限。分离 owner 可以在不重复入账、不自动补平、不依赖进程状态的前提下完成合并付款闭环。身份由单一契约生成器产出，可以防止各场景各写一份实现而在命名空间或 locator 上悄然分叉。

**影响：** RG-05 raw v1 的 25 个 operation 均具有运行时边界，并有 outcome、新增实体身份与 returned ID 比较。本决定不蕴含 expected 审批、v2 oracle 接受或 publication；RG-03 等级的完整 state、delta 与 status-change 比较仍未完成，因此不能据此宣称 RG-05 已全部关闭。

**关联决定：** `D-008`、`D-040`、`D-043`、`D-044`、`D-045`、`D-058`、`D-059`、`D-069`、`D-072`、`D-073`

## D-075 RG-05 expected 批准

**状态：** 已确认

**决定：** `docs/migrations/golden-v2/rg-05-expected.json` 的 `approval_status` 由 `draft_for_review` 改为 `approved`。该工件为 17 roots、42 states、25 operations、146 个确定性身份，自创建以来一字未改。批准依据是：JSON Schema 与完整语义 validator（delta 复算、余额 replay、报表重算、derived status、no-change replay、拒绝与 identity-conflict 专项、时间合成禁令）、检入文件与生成器的深度相等、15 条拒绝理由与字段直接对照 v1，以及 Kotlin 运行时对全部 25 个 operation 的 outcome、新增实体身份与 returned ID 比对和 8 个冻结身份锚点。

本决定仅覆盖 expected 工件本身。adapter 实现、fixture 迁移与 publication 各自仍需单独授权，不随本决定发生。

**理由：** 该工件的验证覆盖已明显超过既有场景获批时的实际标准：RG-04 的 expected 在仓库尚无任何该场景运行时代码时即获批准，其 oracle 仅为生成器自洽测试；RG-02、RG-03 的 expected 在创建时即为 `approved`。继续以更高门槛扣留 RG-05，既无先例依据，也会连锁推迟其后全部场景。

**影响：** RG-05 的 expected 闸门关闭。仍未关闭的是：完整 state、delta 与 status-change 比较（RG-03 已有、RG-04 与 RG-05 尚无），该比较应在 RG-06 开工前以共享方式建立，避免再出现第四套 oracle 标准。批准不改变 `GOLDEN_SCHEMA.md` 的 sequential gates 对其余场景的约束。

**关联决定：** `D-008`、`D-040`、`D-043`、`D-044`、`D-045`、`D-058`、`D-059`、`D-074`

## D-076 验证证据不以文档内手工计数表达

**状态：** 已确认

**决定：** 正式文档不记录逐模块测试计数一类随测试增删而变的数字，也不为其建立自动校验。`docs/CURRENT_STATE.md` 改为陈述最近一次完整验证的结论并指向验证命令，实际数字以 `build/test-results` 下的报告为准。此前为守护该计数而加入 `project_docs` 的证据校验一并移除。

**理由：** 该计数是易变的手工副本，在短期内多次漂移；而为它建立的校验把 Gradle 的易失输出目录当作持久证据，使得规范要求的 focused 测试步骤会让仓库无故变红，并且在措辞漂移、报告缺失或损坏时静默通过。三轮独立审查中该校验自身产出的缺陷约占总数四成，其防护价值远低于维护与误报代价。正确的做法是不在文档中复制易变事实，而不是为复制品加装校验。

**影响：** `python -m project_docs .` 回到纯文档卫生校验，不读取任何构建输出，可在任意工作状态下运行。文档不再声称具体测试数量；需要数字时直接读报告。本决定不放宽任何验证要求：完整套件仍须按 README 运行并全绿。

**关联决定：** `D-075`

## D-077 RG-06 导入对账与领域恢复边界

**状态：** 已确认

**决定：** RG-06 手工 installment 创建其精确 `payment_asset` 分录的 `pending` 对账，后续精确手工证据链接只把该分录改为 `matched`。导入 intake 在确认前没有正式分录或对账；只有原子校验不可变导入证据与明确 relation/role/category/account 精确绑定的候选确认，才创建正式 installment，并为已绑定证据的精确 `payment_asset` 分录直接建立 `matched` 对账。候选 `confirmed` 状态本身不是授权，镜像合并保持 evidence link 与对账不变；所有对账变化的余额、报告和现金流效果均为零。

`StagedPayment` 增加公开、结构化、catalog-free 的 snapshot rehydration factory。snapshot 保留 raw relation member rows，并用 transaction/version/posting-set/posting DTO 接收尚未验证的正式账务图；重复 relation row 在构造领域 set 前按第二次出现的 row index 拒绝。领域以 RG-06 专属 `InvalidSnapshot` problem/index 失败确定性校验 relation、lifecycle checked arithmetic/history、installment 角色/身份/金额/时间、来源 instant/text 结构和正式交易当前链；每个 posting set 先通过 `PostingSet.create`，再通过 `FormalTransaction.create` 重建，任一 factory 拒绝均映射为对应 row 的 `FORMAL_TRANSACTION`。formal rows 必须与 `[deposit, final]` installment rows 同序且逐项一一对应；snapshot 的嵌套正式账务图和恢复后 aggregate 的对外图均防御性复制。不扩展 shared `DomainViolation`。恢复不 replay command、不查询当前 catalog、不使用 opaque aggregate serialization，也不允许 adapter 重写不变量。合法历史在 category/account catalog 漂移后仍可恢复，恢复后的新命令继续使用当前 catalog 准入；现有合法多版本正式交易只在 current posting set 仍匹配 installment 的不可变 posting identity 时有效。

**理由：** 导入证据在精确确认时已经完成该资产分录的核验，创建后再标记待对账会与冻结黄金答案分叉；同时 persistence 需要一个由领域拥有且可验证的恢复边界，避免 command replay、当前目录漂移或 adapter 逻辑改变历史事实。

**影响：** `golden/rules/rg-06.json` 保持不变，Kotlin commit-port 契约与 Python operation/semantic validator 对齐其既有答案。schema v9、decoder、SQLDelight/store/adapter/migration、fixture/expected/path-map rewrite、golden publication、UI、金额或账户修正均未获授权；RG-06 persistence/publication 与 RG-07 以后 runtime 继续未完成。

**关联决定：** `D-008`、`D-040`、`D-042`、`D-043`、`D-045`、`D-058`、`D-069`、`D-076`

## D-078 RG-07 Golden Schema v2 契约修订

**状态：** 已确认

**决定：** 在不生成 expected v2、不实现 adapter 或 fixture rewrite 的前提下，登记 RG-07 的 closed Golden Schema v2 contract。每笔退款使用一个 identity-only `refund` relation 关联一笔有效原 `expense` 与至多一笔独立 `refund_receipt`；与该 relation 一一对应的 `refund_relationship` domain entity 拥有金额、原精确二级分类、到账账户、互不替代的时间与追加式历史。当前退款状态是由最后一条历史重算的 `refund_status` derived status，不在 relation payload 或 evidence link 中复制。

`refund_receipt` 在实际到账时以 `destination_asset` 正分录和继承原二级分类的 `expense` 负分录平衡；报表仅使用 canonical `consumption`、`cash_inflow`、`cash_outflow`、`income` 与 `net_worth_change`。v1 `refund_cash_inflow` 映射到 `cash_inflow`，`ordinary_income` 映射到 `income`，两个 v1 token 都不进入 v2 report registry。

RG-07 来源、候选、证据和镜像 lineage 分别由 closed source/evidence/candidate payload 拥有。通用 evidence link 仍严格为 `id,evidence_id,target_kind,target_id,role`；账户入账状态只属于 posting reconciliation，镜像 source/evidence/link lineage 不得作为通用 link 字段。关系确认类型固定为 `refund_relationship_confirmation`，subject 固定为具体 `relation`，payload 只保存 `original_transaction_id`。

RG-07 登记 `record_refund_request_status`、`ingest_refund_status_source`、`confirm_manual_refund_receipt`、`attach_original_payment_evidence`、`attach_refund_destination_evidence`、`attach_refund_dual_role_evidence`、`confirm_refund_receipt`、`allocate_refund_receipt`、`ingest_refund_credit_source`、`confirm_imported_refund`、`merge_refund_mirror_evidence` 和 `validate_refund_receipt` 的 strict action inputs、精确 operation class、原子拒绝与原 action 重放。`attach_original_payment_evidence` 只登记或等价重放原付款的 `bank_debit` source、`asset_debit` evidence 和指向原支出精确 `payment_asset` 分录的 evidence link，并把该分录从 `pending` 核验为 `matched`；它不扩展 `manual_expense` ownership。上限、原交易、分类、到账账户和明确确认均由语义校验器重算；Schema 通过不得降级为只检查路径存在。

**理由：** `D-061` 已冻结退款的业务与账务语义，但现有 Golden Schema v2 仍将 `refund_relationship` payload 保留为未登记。如果只让 path map、Schema 或 validator 自行补齐，会把状态、证据职责、镜像 lineage、报表 token 和确认 subject 的序列化选择错当成已批准事实。

**影响：** RG-07 通过 contract/schema/validator 与 per-RG mapping 门后，只能请求下一个 expected-output 决定。本决定不批准 expected v2、adapter、v1 fixture rewrite、`golden/rules-v2` 工件、Kotlin/runtime/persistence、publication 或 Git 集成；也不扩展跨币种、负债/储值到账、多分类、超额补偿或修正语义。

**关联决定：** `D-010`、`D-013`、`D-043`、`D-044`、`D-045`、`D-047`、`D-061`、`D-076`

## D-079 RG-07 contract closure implementation

**状态：** 已确认

**决定：** 在 `D-078` contract amendment 基础上，批准 RG-07 expected v2、strict adapter、fixture replay、schema v10 persistence/migration 与 Kotlin domain/application/data runtime。runtime 必须逐 operation 比较 outcome、returned IDs、完整 state、deltas 与 status changes，并保持 source/evidence/reconciliation ownership、精确 identity 与原子拒绝边界。

**影响：** RG-07 mapping gate 与 expected output gate 为 approved；schema v9 到 v10 的迁移保留 v9 formal rows 并新增 RG-07 owners。该决定不批准 `golden/rules-v2` publication 或 release target；publication 仍需单独明确 target 并在 clean worktree 上执行 release verification。

**关联决定：** `D-061`、`D-078`

## D-080 RG-07 v2 publication target

**状态：** 已确认

**决定：** RG-07 approved v2 expected output is published as the exact byte-preserving artifact `golden/rules-v2/rg-07.json`, copied from `docs/migrations/golden-v2/rg-07-expected.json`.

**影响：** The publication target is explicit and release verification must continue to run on a clean worktree. The artifact is recorded in the v2 manifest with its source, expected, canonical, and output hashes; this decision does not authorize publication of other RG cases or any remote push.

**关联决定：** `D-079`

## D-081 RG-06 closure migration and publication approval

**状态：** 已确认

**决定：** 根据用户 2026-08-05 明确批准的 RG-06 1～5 gate，批准 RG-06 mapping closure 与 expected output、`RG06-AUTH-01` authority trace、严格 v1-to-v2 adapter/replay、fixture rewrite、publication 和 clean release verification。`golden/rules/rg-06.json` 是只读的冻结 v1 source；adapter 必须以 v1 operation/input、returned IDs、outcome、完整 state、deltas 和 status changes 为输入和比较对象，不能反向读取或改写 v2 expected 来驱动执行。accepted、no_change 和 rejected 均须逐 operation 比较；no_change/rejected 必须证明零正式写入、零余额/report/status 变化，失败和重放必须保持原子性、幂等和失败隔离。

mapping closure 的五个 resolved gaps、`docs/migrations/golden-v2/rg-06-expected.json` 和 path-map gate 可标记为 `approved`。fixture rewrite 只能生成明确的 v2 publication candidate，必须保留临时副本、source/target hash、失败恢复、幂等和失败隔离证据；publication target 固定为 `golden/rules-v2/rg-06.json`，manifest 记录 source、expected、canonical 和 output hash 以及对象计数。只在 clean worktree 上完成 release verification；本决定不授权远程 push。

RG-06 candidate confirmation 的 `confirmed_at` 是明确的 provenance 字段，不得从 `source_payment_at`、`actual_payment_at`、operation time 或运行时当前时间推导。adapter/fixture 必须从冻结 v1 `confirmation_provenance.confirmed_at` 显式提供它；当前冻结值为 `2026-04-28T10:05:00+08:00` 和 `2026-05-03T16:35:00+08:00`。runtime persistence 以 additive migration 保存该字段，并在 reopen/replay 后原样恢复；manual installment confirmation 没有该来源时保持 `null`。该字段参与确认结果一致性检查，但不得扩展 v2 operation input 的业务语义。

本决定确认 D-077 的 RG-06 领域/账务边界仍然有效；D-077 影响段中“未获授权”的 schema v9、decoder、SQLDelight/store、adapter、migration、fixture、expected 和 publication 表述由本决定在上述明确范围内 supersede。D-081 不扩展 RG-06 的产品账务行为、金额规则、跨币种范围或 UI。

**理由：** mapping/expected、adapter/replay、fixture rewrite 与 publication 是相互依赖但权限不同的 gate；显式 authority trace 可以消除旧影响段与现有实现的冲突。确认时间属于 provenance，不属于支付发生时间，必须持久化以避免 replay 或 fixture rewrite 静默改变审计事实。

**影响：** RG-06 schema/store/adapter/replay/migration、fixture rewrite 和 publication 可以在本决定的 acceptance topology 下实施；publication 仍受 clean release verification 和 manifest/hash 审计约束。`golden/rules/rg-06.json` 不得原地修改，`.external/` 保持只读。

**关联决定：** `D-008`、`D-013`、`D-015`、`D-017`、`D-043`、`D-045`、`D-058`、`D-060`、`D-077`、`D-080`

## D-082 RG-09 contract closure and implementation approval

**状态：** 已确认

**决定：** 在 `D-063`、`D-065` 和 RG-09 设计的基础上，批准 RG-09 三项 mapping gap 的 contract amendment、完整 fixture oracle、Kotlin runtime 和正式 SQLDelight persistence。`RG09-GAP-01` 关闭为完整 action registry：预览、零差额观察、过期拒绝、导入来源/候选、缺失确认、真实转账确认、解释分配、四个精确真实分录证据链接、无效尝试和原 action retry 都必须有闭合的 operation class、严格 input 或 sparse attempted_input、typed outcome、完整 baseline/result snapshot、returned IDs、formal/intake/reconciliation deltas 和 status changes；retry 保留原 action，不引入 generic retry action。所有 rejected、stale、incomplete 和 no-change 路径保持零正式账务效果，并逐字段保留操作前状态。

`RG09-GAP-02` 关闭为强制 D-065 输入和 stale contract。预览从目标时点及以前的 current-version postings 重新生成 RFC 8785 JCS + SHA-256 fingerprint；候选和确认只接受该真实 digest，历史 fixture 中的 `sha256:rg09-ledger-v1/v2` 仅作迁移来源 token，不能作为正式 digest。确认过期时返回 `preview_ledger_fingerprint`、`current_ledger_fingerprint`、`recomputed_replay_amount` 和 `recomputed_delta`，且不写入 transaction、version、posting、adjustment、confirmation、allocation 或 evidence link。金额使用精确 minor units，输入 decimal 必须为闭合的两位十进制文本；target timestamp 必须保留带 `+08:00` 的原始文本并通过 `Asia/Shanghai` boundary validation。

`RG09-GAP-03` 关闭为正式 source/candidate/evidence ownership。导入只保存 immutable source、evidence、candidate 和 pending/incomplete status；source payload digest、observed/actual time、account、amount 和 currency 不从名称、匹配、置信度或候选推导。明确确认必须逐字段提供真实交易、目标账户、实际时间、币种、金额和解释分配；目标余额证据只链接 observation，真实账户证据只链接精确 real-account posting，四条 posting evidence link 分别确认，审计 link 不代替 evidence link。

正式 persistence 由 `ledger-data` 的 `SqlDelightRg09Store` 独占，formal transaction chain 继续使用共享 `ledger_transaction`、`transaction_version`、`posting_set` 和 `posting` owner；RG-09 专用表、immutable/sequence/target-type guards 和 v11→v12 additive migration 保存其 intake、operation、candidate、evidence、adjustment、allocation、audit 和 reconciliation facts。成功操作的 identity claim、领域校验、formal writes、derived history 和 receipt 在一个 transaction 内完成；失败回滚后 reopen/readback 必须保持完整 baseline。旧 schema rows 不重写，`canonical_kind` bridge 保持现有 legacy kind 兼容。

完整 oracle 必须逐 operation 比较 outcome、returned IDs、完整 canonical state、formal/intake deltas、status changes、rejected/no-change baseline equality 和 retry equality；独立 verifier 必须用冻结 v1 输入驱动执行，不能反向读取 expected 来生成 runtime input。`.external/`、冻结 `golden/rules/rg-09.json` 和 publication target 不在本决定中被修改或发布。

**理由：** 现有 D-065 只定义计算基础，候选仍缺失完整输入、导入 provenance、负面路径、逐 operation oracle 和数据库 owner。把这些边界一次性登记为 closed contract，才能使 mapping closure、runtime、migration 和 review 使用同一套可审计事实，同时保留确认才产生正式账务的边界。

**影响：** RG-09 mapping gate 可在 closure proposal、完整 oracle、focused persistence/migration tests 和独立 review 证据齐全后标记 `approved`。本决定不授权修改 v1 fixture、`golden/rules-v2` publication、remote push 或任何真实用户数据库；publication 仍需单独授权并在 clean worktree 上执行 release verification。

**关联决定：** `D-063`、`D-065`、`D-077`、`D-079`

---

## D-083 RG-10 contract closure and implementation approval

**状态：** 已确认

**决定：** 在 `D-034`、`D-035`、`D-036`、`D-037`、`D-043`、`D-044`、`D-045`、`D-047`、`D-050`、`D-063`、`D-064`、`D-066`、`D-067`、`D-068` 与 RG-10 设计（`docs/specs/2026-07-16-rg-10-stored-value-design.md`）、v2 mapping（`docs/migrations/golden-v2/rg-10-mapping.md`）的基础上，批准 RG-10 六项 mapping gap 的 contract amendment、完整 Kotlin runtime、50-operation fixture oracle 与正式 SQLDelight persistence。

`RG10-GAP-01` 关闭为完整 action registry：13 个 action（`confirm_stored_value_recharge`、`confirm_stored_value_spend`、`ingest_stored_value_recharge_candidate`、`ingest_stored_value_spend_candidate`、`confirm_imported_stored_value_recharge`、`confirm_imported_stored_value_spend`、`record_expiry_reminder`、`confirm_stored_value_expiry_loss`、`reconcile_merchant_credit`、`reconcile_bank_payment`、`apply_merchant_lot_allocation`、`confirm_stored_value_activation_balance`、`rename_stored_value_labels`）都必须有闭合的 operation class、严格 input 或 sparse attempted_input、typed outcome、完整 baseline/result snapshot、returned IDs、formal/intake/reconciliation deltas 和 status changes；retry 保留原 action，不引入 generic retry action。充值（paid+bonus=credited）、消费、到期损失、接入调整的经济效果完整实现；所有 rejected、stale、incomplete 和 no-change 路径保持零正式账务效果并逐字段保留操作前状态。

`RG10-GAP-02` 关闭为完整 lifecycle payload owner：stored-value lot（面值、实付/赠送组成、到期日、载入时间、来源）、consumption、allocation（商户分配覆盖默认批次顺序）、expiry（reminder 零影响、confirmed 才生成损失）的完整 payload 与效果。

`RG10-GAP-03` 关闭为 import provenance owner：充值/消费导入保持 `pending_confirmation` 且正式影响为零，直到模型、账户、实付/到账/赠送、实际时间、批次事实、消费分类、分配和显式确认全部逐字段闭合；imported 不自动确认。

`RG10-GAP-04` 关闭为 activation boundary + replace-not-append replay：`D-067` 的 `stored_value_reconstruction` 实体在 runtime 中实现，`active_mode` 取 `adjustment` 或 `reconstructed` 且任一时刻唯一经济归属生效；原调整与重建交易均追加保留，不原地改写、不重复计入。

`RG10-GAP-05` fail-closed：`D-066` 已决定不得猜测 `parent_id`；该 path（numeric `level`）保持待确认，不映射、不推断。

`RG10-GAP-06` 部分关闭 + fail-closed：bonus/expiry evidence roles（`D-068` 的 `stored_value_bonus_component` / `stored_value_expiry_confirmation`）在 runtime 中可执行；legacy link status 无 owner，fail-closed 不映射到 posting reconciliation 或 domain lifecycle 状态。

新增 `TransactionKind`：`STORED_VALUE_RECHARGE`、`STORED_VALUE_SPEND`、`STORED_VALUE_EXPIRY_LOSS`、`STORED_VALUE_PRE_ACTIVATION_BALANCE_ADJUSTMENT`。

正式 persistence 由 `ledger-data` 的 `SqlDelightRg10Store` 独占；formal transaction chain 继续使用共享 `ledger_transaction`、`transaction_version`、`posting_set`、`posting` owner；RG-10 专用表、immutable/sequence/target-type guards 与 additive schema migration 保存其 intake、candidate、lot、consumption、allocation、expiry、reconstruction、evidence、audit 与 reconciliation facts。成功操作在一个 transaction 内完成；失败回滚后 reopen/readback 保持完整 baseline。

完整 oracle 必须逐 operation 比较 outcome、returned IDs、完整 canonical state、formal/intake deltas、status changes、rejected/no-change baseline equality 和 retry equality；独立 verifier 用冻结 v1 输入驱动执行，不反向读取 expected 生成 runtime input。`.external/`、冻结 `golden/rules/rg-10.json` 和 publication target 不在本决定中被修改或发布。

**理由：** 现有决定只授权领域身份、audit topology 与 structural registry；runtime、经济效果、oracle、persistence 与 import/replay owners 缺失。把这些边界一次性登记为 closed contract，使 mapping closure、runtime、migration 和 review 使用同一套可审计事实，同时保留确认才产生正式账务、证据与确认分离、replace-not-append 的边界。

**影响：** RG-10 mapping gate 可在 closure proposal、完整 oracle、focused persistence/migration tests 和独立 review 证据齐全后标记 `approved`。本决定不授权修改 v1 fixture、`golden/rules-v2` publication、remote push 或任何真实用户数据库；publication 仍需单独授权并在 clean worktree 上执行 release verification。

**关联决定：** `D-034`、`D-035`、`D-036`、`D-037`、`D-043`、`D-044`、`D-045`、`D-047`、`D-050`、`D-063`、`D-064`、`D-066`、`D-067`、`D-068`

---

## D-084 RG-08 lending settlement contract amendment and implementation approval

**状态：** 已确认

**决定：** 在 `D-062` 与 RG-08 v2 mapping（`docs/migrations/golden-v2/rg-08-mapping.md`，4969 条规范化 source path、2964 条 `requires_contract_amendment`、4 个 unresolved gap）的基础上，批准 RG-08 四项 mapping gap 的 contract amendment、完整 Kotlin runtime、44-operation fixture oracle 与正式 SQLDelight persistence。fixture oracle 规模以冻结 fixture 独立清点为准，参照 RG-10 对 D-083 "50-operation" 字面数的处置先例：`golden/rules/rg-08.json` 冻结 fixture 实为 32 个 v1 操作案例加 12 个 retry，共 44 个操作，分布为 accepted 6（5 formal + 1 intake）、rejected 25、no_change 13（1 + 12）。

`RG08-GAP-01` 关闭为完整 lending action registry：`validate_lending_event`、`validate_lending_settlement`、`confirm_imported_lending_collection`、`allocate_lending_collection` 与 `retry_idempotent_input` 五个 operation class 都必须有闭合的 operation class、严格 input、typed outcome、完整 baseline/result snapshot、returned IDs、formal/intake/reconciliation deltas 和 status changes。冻结 fixture 的 12 个 retry 以 generic `retry_idempotent_input` 表达，显式偏离 D-083 的 "retry 保留原 action" 规则；本决定以 RG-08 冻结 fixture 语义为准并记录该差异理由。所有 rejected、incomplete 和 no-change 路径保持零正式账务效果并逐字段保留操作前状态；本金上限拒绝（`principal_exceeds_outstanding_position`）原子完成，`pending_explicit_reallocation` 不自动截断。

`RG08-GAP-02` 关闭为 lending domain payload/lifecycle owner：position（`person_level_net_position`、`contract_allocation_enabled=false`、`receivable_account_id`、只追加 history）与 settlement（`linked_position_id`、`allocated_lend_transaction_id` 首版恒为 null、components 为 principal/interest/fee 且 fee 固定 `0.00`、`confirmed_at`、只追加 history）完整 payload；relation 实例承载 `counterparty_lending_relationship` 与稳定身份；behavior_codes 4 条登记 `principal_effect` 与 settlement 布尔。

`RG08-GAP-03` 关闭为 lending intake/provenance owner：source_record（`booking_at`、`value_at`、`immutable_payload_hash`、`original_source_payload_hash`、kind）、candidate（`pending_confirmation` 生命周期与 status_history、六字段 requires_confirmation、不自动确认标志）、confirmation_provenance、typed evidence-link 角色 3 个（`destination_asset_posting`、`funding_asset_posting`、`counterparty_lending_relationship`）与 typed audit-link from/to（`mirror_of_evidence_id`、`merged_into_evidence_link_id`，不作为 evidence-link 字段）；imported 不自动确认，mirror/merge 只追加 source/evidence/link 且零 formal 效果。

`RG08-GAP-04` 关闭为 economic receipt/effective time owner：`actual_receipt_at`、`proposed_actual_receipt_at` 与 `lend.request.actual_at` 是独立精确经济时间字段；fail-closed，绝不从 `created_at` 或 `confirmed_at` 推导；`occurred_at` 与 `statistics_at` 保持经济/报表语义；实际到账时间未确认的候选不得入账。

新增 `TransactionKind`：`LEND`、`COLLECT`。

正式 persistence 由 `ledger-data` 的 `SqlDelightRg08Store` 独占；formal transaction chain 继续使用共享 `ledger_transaction`、`posting_set`、`posting` owner；RG-08 专用表、immutable/sequence/target-type guards 与 additive v14→v15 migration 保存其 intake、candidate、position、settlement、evidence、audit 与 reconciliation facts。成功操作在一个 transaction 内完成；失败回滚后 reopen/readback 保持完整 baseline。

完整 oracle 必须逐 operation 比较 outcome、returned IDs、完整 canonical state、formal/intake deltas、status changes、rejected/no-change baseline equality 和 retry equality；独立 verifier 用冻结 v1 输入驱动执行，不反向读取 expected 生成 runtime input。`.external/`、冻结 `golden/rules/rg-08.json` 和 publication target 不在本决定中被修改或发布。

**理由：** 现有 `D-062` 只定义现金口径、本金上限、身份与证据职责；runtime、经济效果、oracle、persistence 与 import/replay owners 缺失。RG-10 的 D-083 提供同构的 gap disposition 结构与以冻结 fixture 独立清点为准的 oracle 规模先例；把这些边界一次性登记为 closed contract，使 mapping closure、runtime、migration 和 review 使用同一套可审计事实，同时保留确认才产生正式账务、证据与确认分离的边界。与 D-083 的 generic retry 规则差异在本决定中显式声明。

**影响：** RG-08 mapping gate 可在 closure proposal、完整 oracle、focused persistence/migration tests 和独立 review 证据齐全后标记 `approved`。本决定影响 schema v14→v15、`TransactionKind` 枚举、共享 formal chain（`ledger_transaction`、`posting_set`、`posting`）与 RG-08 独占表。本决定不授权修改 v1 fixture、`golden/rules-v2` publication、remote push 或任何真实用户数据库；publication 仍需单独授权并在 clean worktree 上执行 release verification。

**关联决定：** `D-062`、`D-083`

---

## D-085 RG-11 periodic allocation and RG-12 reconciliation correction runtime approval（direct-v2）

**状态：** 已确认

**决定：** RG-11 与 RG-12 为 direct-v2 场景：冻结契约 `golden/rules/rg-11.json` 与 `golden/rules/rg-12.json`（contract_version 2.0.0，批准于提交 `efbb13a`）无 v1 mapping、无 adapter——direct-v2 设计使然。基于设计文档 `docs/specs/2026-07-18-rg-11-periodic-allocation-design.md` 与 `docs/specs/2026-07-18-rg-12-reconciliation-correction-design.md` 及冻结 Python 语义测试（`test_rg11_golden_v2.py` 11 测试 / `test_rg12_golden_v2.py` 5 测试），批准两场景完整 Kotlin runtime、oracle 与 SQLDelight persistence。实现顺序为 RG-11 先行、RG-12 串行：RG-11 将 `correct_transaction_version` 以 `statistics_time` 语义落地共享内核，完整闭合合并后再开 RG-12（`posting_facts` 扩展）；WORK_PLAN 串行瓶颈约束（`TransactionKind` enum / `DomainResult` / schema migration）要求每个主线 RG 完成自身全流程并合并至 main 后方可开始下一个。

**RG-11 runtime**：domain 三类实体（`periodic_allocation_schedule` / `periodic_allocation_revision` / `periodic_allocation_installment`）与 audit link（`periodic_allocation_recognition`）+ application 四类操作（`create_periodic_allocation` 8 / `recognize_periodic_allocation_installment` 10 / `revise_periodic_allocation` 3 / `correct_transaction_version` 1）——**22-operation fixture oracle**，规模以冻结契约独立清点为准（accepted 11 / no_change 1 / rejected 10；root-main 6 + root-revision 6 + root-z-rejections 10；拒绝 root 需 seed opening + purchase + recognition 基线），参照 D-084 先例。

**RG-12 runtime**：domain 新类型（`ReconciliationMatch` 只追加 `status_history` / `PostingReplacement` 三值 `reconciliation_effect` / `PostingReconciliation` / `ExplicitOperationConfirmation` / `CorrectTransactionVersionViolation`）+ application `correct_transaction_version` 的 `posting_facts` 语义（完整替换 postings、`matched_unaffected_posting_must_be_preserved`、`historical_facts_immutable` 等 10 个拒绝 reason）——**12-operation fixture oracle**（accepted 1 / no_change 1 / rejected 10；拒绝链 baseline==result 全等）。

**共享内核**：`correct_transaction_version` 通用实现——`appendVersion` 域原语（generalize RG-01 的 `replaceNote`）；`statistics_time` 为 RG-11 基础语义，共享原 `posting_set`；`posting_facts` 为 RG-12 扩展，新建 `posting_set`；`explicit_operation_confirmation` 与 `idempotent_replay` 规则共享；`correction_kind` 分派。

**TransactionKind**：新增 `PREPAID_PURCHASE`、`PREPAID_RECOGNITION`（对照 D-083 行 1057 / D-084 行 1085 先例）；RG-12 无需新 kind——契约事务类型恒为 `expense`。持久化走 `canonical_kind` 映射（Ledger.sq 的 `kind` CHECK 仅含 5 旧 kind，新 kind 走 CASE 映射，先例 Ledger.sq 1725-1740）。

**persistence**：RG-11 schema v15→v16 additive migration（`SqlDelightRg11Store` 独占；formal chain 共享 `ledger_transaction` / `transaction_version` / `posting_set` / `posting`；专用表保存 schedule / revision / installment / audit_link）；RG-12 schema v16→v17 additive migration（`SqlDelightRg12Store` 独占；`reconciliation_match` / `posting_reconciliation` / `posting_replacement` / confirmation 表）。成功操作单事务完成，失败回滚后 reopen/readback 保持完整 baseline（对齐 D-084 条款）。

**closure gate**（对齐 D-084:1093）：每个 RG 的 closure proposal、完整 oracle、focused persistence/migration tests 与独立 review 证据齐备后置 gate；direct-v2 无 mapping gate，状态记录于 closure proposal。

**不授权**：修改冻结契约（`golden/rules/rg-11.json`、`golden/rules/rg-12.json`）、v2 publication、remote push——push 已由用户单独授权一次，本决定不自动延续，按用户指示执行。

**理由：** D-047/D-048 研究依据（`RG_07_12_RESEARCH.local.md`）+ direct-v2 契约冻结（`efbb13a`）+ 设计文档 + 共享 `correct_transaction_version` 内核（v2.py action registry 已按 `correction_kind` 分支）。

**影响：** schema v15→v16→v17、`TransactionKind` 枚举 +2、共享 `appendVersion` 原语（generalize `replaceNote` 需回归 RG-01 note_update）、`SqlDelightRg11Store` / `SqlDelightRg12Store`。

**关联决定：** `D-047`、`D-048`、`D-083`、`D-084`

---

## D-086 RG-03/05/10/11/12 golden v2 publication target（统一发布 gate）

**状态：** 已确认

**决定：** 根据用户 /goal 授权（2026-08-09 publication 阶段），批准 5 个 RG 的 v2 工件发布 target，均在 clean worktree 上执行 release verification（`verify-project.ps1 -Scope release`），发布工具必须满足原子性、幂等、失败隔离与 journal 恢复（参照 `rg09_publication.py` 先例）：

- **RG-03**：`golden/rules-v2/rg-03.json` ← `docs/migrations/golden-v2/rg-03-expected.json`（approval_status approved；13 roots / 20 ops / 33 states）
- **RG-05**：`golden/rules-v2/rg-05.json` ← `docs/migrations/golden-v2/rg-05-expected.json`（D-075 已批准；17 roots / 25 ops / 42 states）
- **RG-10**：`golden/rules-v2/rg-10.json` ← 待生成的 `docs/migrations/golden-v2/rg-10-expected.json`。expected 由冻结 v1 契约 `golden/rules/rg-10.json`（44 ops，accepted 12 / no_change 10 / rejected 22，D-083 清点）+ `tests/fixtures/rg10-runtime-input.json` 确定性生成 v2 形状，扩展 `validate_golden_case_v2` 支持 RG-10 事务类型；生成的 expected 工件与 builder 测试须经独立 spec/quality review 与 distinct verifier 通过后方可发布
- **RG-11**：`golden/rules-v2/rg-11.json` ← `docs/migrations/golden-v2/rg-11-expected.json`
- **RG-12**：`golden/rules-v2/rg-12.json` ← `docs/migrations/golden-v2/rg-12-expected.json`

**direct-v2 expected 形态（RG-11/12，仓库无先例，本决定明确）：** expected 工件 = 冻结契约 `golden/rules/rg-11.json` / `golden/rules/rg-12.json` 的逐字节副本——direct-v2 契约即期望基线（oracle 为契约 1:1 镜像，D-085:1101）。manifest 中 `source_sha256 == expected_byte_sha256`，`canonical_sha256` 按 `sort_keys` + `(",",":")` UTF-8 序列化单独计算；direct-v2 发布工具的 source/expected 前置校验使用 `validate_golden_case_v2`（契约 `contract_version 2.0.0` 已批准于 `efbb13a`），不复用 `schema_version == 1` 断言。

**manifest 登记：** 每 RG 发布时在 `golden/rules-v2/manifest.json` 追加条目（`approval_status: approved`、`discovery.comparison` 为 `"NN-operation full comparison"`（RG-11 22 / RG-12 12 / RG-03 20 / RG-05 25 / RG-10 44）、4 组 hash、`object_counts`、`operation_status_counts`），cases 按 case 名排序，`output_sha256 == expected_byte_sha256`。

**影响：** RG-03/05/11/12 的 expected 已冻结或已批准，直接进入发布执行；RG-10 先完成 expected 生成与审查再发布。发布后同步 `README.md`、`docs/CURRENT_STATE.md`、`docs/ROADMAP.md` 与 `docs/GOLDEN_V2_INVENTORY.md`。本决定不授权 remote push（push 单独授权，D-085:1117）；`.external/` 与冻结 `golden/rules/rg-XX.json` 一律只读。

**关联决定：** `D-075`、`D-080`、`D-081`、`D-083`、`D-085`

---

## D-087 RG-01/RG-02 完整 state/delta/status 比较执行边界

**状态：** 已确认

**决定：** 根据用户 /goal 授权（RG-01/02 完整比较，2026-08-09），为 RG-01 与 RG-02 建立完整逐 operation oracle——outcome、returned IDs、完整 canonical state、formal/intake deltas、status changes、rejected/no-change baseline equality、retry equality（对齐 D-082/D-083 oracle 合同）。本决定 supersede D-069/D-070/D-071 影响段中"完整 state/report/reconciliation/delta comparison 不授权"的表述（supersede 先例：D-081 对 D-077）。expected 工件 `docs/migrations/golden-v2/rg-01-expected.json`（8 roots / 11 ops / 19 states）与 `rg-02-expected.json`（11 roots / 13 ops / 24 states）均已 approved 且自创建未改（D-075 审批标准），直接作为 oracle 批准基线，不重新生成、不修改。

**RG-01 oracle：** 覆盖 create、`transaction_note_update`（appendVersion 共享内核语义；复用现有共享 use case 与 commit port，不改写 commit 语义——note_update 的 deltas 必须与 expected 精确一致：transactions.changed + transaction_versions.added，无 posting 变化，status_changes=[]）、retry（no_change/idempotent_replay、returned_ids 等于前序 accepted）、distinct re-entry、7 个 invalid 拒绝。现有 `Rg01RawJsonEndToEndTest` 保留（raw 端到端覆盖），不改写。

**RG-02 oracle：** 覆盖 manual_income 主创建、同请求重试、两个独立变体、8 条零副作用拒绝；**`category_rename` 授权实现最小闭环**——v1 契约 `golden/rules/rg-02.json` 有完整 rename 期望（name_versions 追加、display_path、catalog change、returned_ids=[]），expected 的 accepted rename op 与 24 个 state 的 `category_name_history` 是 validator 硬性要求；D-071"不实现分类改名/name-version"的表述在本决定明确范围内 supersede。实现中发现未决语义（如 name 版本生命周期细节）时停止并升级，不静默推断。

**投影边界（零运行时改动方案）：** `posting_reconciliations`（pending 条目与 `goldenV2MigrationId` 派生 id）、`derived_statuses.reconciliation_summary`、`postings.role`/`reconciliation_eligible`、`confirmations.operation_id` 由投影器从 DB + 冻结 v1 catalog 确定性派生；runtime **不新增持久化、不改 schema、不改三个 commit port**（`SqlDelightConfirmedManualExpenseCommitPort` / `SqlDelightConfirmedTransactionNoteUpdateCommitPort` / `SqlDelightConfirmedManualIncomeCommitPort`）。金额为两位精确十进制文本、时间保留 v1 原文 `+08:00`（投影器不做时区归一化或金额文本变换）。投影查询在 `Ledger.sq` 新增（`selectRg01All*`/`selectRg02All*`）。

**身份与输入：** root/state/operation/confirmation/reconciliation id 全部沿用 `goldenV2MigrationId`/`goldenV2RootId` 单一生成器（D-074，与 Python 生成器对齐），各场景不得各自实现生成器；oracle 用冻结 v1（`golden/rules/rg-01.json`、`rg-02.json`）驱动执行，不反向读取 expected 生成 runtime input（D-081）。

**验证：** focused oracle 测试 + 受影响全套件（Kotlin + Python）+ 独立 spec/quality review + distinct verifier + 主代理 clean 复跑；补跑 `validate_golden_case_v2` 对两个 expected 工件的证据（D-075 标准）。

**不授权：** remote push（单独授权）、v1 fixture rewrite（`golden/rules/` 冻结）、RG-01/RG-02 publication（发布按 D-086 流程单独授权）、UI、其他字段修正语义、网络序列化选择。

**理由：** C-0 研究（RG_01_02 比较边界）确认 expected 已完整且 approved、runtime 共享 use case 完整、唯一缺口是 oracle 测试与投影层；D-071 的 rename 延期使 expected 的 accepted rename op 与 runtime unsupported 存在既有分歧，完整比较要求闭合该分歧；投影器派生方案保持产品行为零变化。

**影响：** 新增 `Rg01FullStateOracleTest`/`Rg02FullStateOracleTest`（预计 1000-1300 行）+ `Ledger.sq` 投影查询 + RG-02 rename 最小闭环；RG-01/02 oracle 达到与其他 RG 相同的完整比较标准。现有测试与产品构建不受影响。

**关联决定：** `D-047`、`D-048`、`D-069`、`D-070`、`D-071`、`D-074`、`D-081`、`D-082`、`D-086`
