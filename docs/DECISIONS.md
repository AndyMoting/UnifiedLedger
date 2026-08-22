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

**决定：** 根据用户明确授权（2026-08-09 publication 阶段），批准 5 个 RG 的 v2 工件发布 target，均在 clean worktree 上执行 release verification（`verify-project.ps1 -Scope release`），发布工具必须满足原子性、幂等、失败隔离与 journal 恢复（参照 `rg09_publication.py` 先例）：

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

**决定：** 根据用户明确授权（RG-01/02 完整比较，2026-08-09），为 RG-01 与 RG-02 建立完整逐 operation oracle——outcome、returned IDs、完整 canonical state、formal/intake deltas、status changes、rejected/no-change baseline equality、retry equality（对齐 D-082/D-083 oracle 合同）。本决定 supersede D-069/D-070/D-071 影响段中"完整 state/report/reconciliation/delta comparison 不授权"的表述（supersede 先例：D-081 对 D-077）。expected 工件 `docs/migrations/golden-v2/rg-01-expected.json`（8 roots / 11 ops / 19 states）与 `rg-02-expected.json`（11 roots / 13 ops / 24 states）均已 approved 且自创建未改（D-075 审批标准），直接作为 oracle 批准基线，不重新生成、不修改。

**RG-01 oracle：** 覆盖 create、`transaction_note_update`（appendVersion 共享内核语义；复用现有共享 use case 与 commit port，不改写 commit 语义——note_update 的 deltas 必须与 expected 精确一致：transactions.changed + transaction_versions.added，无 posting 变化，status_changes=[]）、retry（no_change/idempotent_replay、returned_ids 等于前序 accepted）、distinct re-entry、7 个 invalid 拒绝。现有 `Rg01RawJsonEndToEndTest` 保留（raw 端到端覆盖），不改写。

**RG-02 oracle：** 覆盖 manual_income 主创建、同请求重试、两个独立变体、8 条零副作用拒绝；**`category_rename` 授权实现最小闭环**——v1 契约 `golden/rules/rg-02.json` 有完整 rename 期望（name_versions 追加、display_path、catalog change、returned_ids=[]），expected 的 accepted rename op 与 24 个 state 的 `category_name_history` 是 validator 硬性要求；D-071"不实现分类改名/name-version"的表述在本决定明确范围内 supersede。实现中发现未决语义（如 name 版本生命周期细节）时停止并升级，不静默推断。

**投影边界（零运行时改动方案）：** `posting_reconciliations`（pending 条目与 `goldenV2MigrationId` 派生 id）、`derived_statuses.reconciliation_summary`、`postings.role`/`reconciliation_eligible`、`confirmations.operation_id` 由投影器从 DB + 冻结 v1 catalog 确定性派生；runtime **不新增持久化、不改 schema、不改三个 commit port**（`SqlDelightConfirmedManualExpenseCommitPort` / `SqlDelightConfirmedTransactionNoteUpdateCommitPort` / `SqlDelightConfirmedManualIncomeCommitPort`）。金额为两位精确十进制文本、时间保留 v1 原文 `+08:00`（投影器不做时区归一化或金额文本变换）。投影查询在 `Ledger.sq` 新增（`selectRg01All*`/`selectRg02All*`）。

**身份与输入：** root/state/operation/confirmation/reconciliation id 全部沿用 `goldenV2MigrationId`/`goldenV2RootId` 单一生成器（D-074，与 Python 生成器对齐），各场景不得各自实现生成器；oracle 用冻结 v1（`golden/rules/rg-01.json`、`rg-02.json`）驱动执行，不反向读取 expected 生成 runtime input（D-081）。

**验证：** focused oracle 测试 + 受影响全套件（Kotlin + Python）+ 独立 spec/quality review + distinct verifier + 主代理 clean 复跑；补跑 `validate_golden_case_v2` 对两个 expected 工件的证据（D-075 标准）。

**不授权：** remote push（单独授权）、v1 fixture rewrite（`golden/rules/` 冻结）、RG-01/RG-02 publication（发布按 D-086 流程单独授权）、UI、其他字段修正语义、网络序列化选择。

**理由：** C-0 研究（RG_01_02 比较边界）确认 expected 已完整且 approved、runtime 共享 use case 完整、唯一缺口是 oracle 测试与投影层；D-071 的 rename 延期使 expected 的 accepted rename op 与 runtime unsupported 存在既有分歧，完整比较要求闭合该分歧；投影器派生方案保持产品行为零变化。

**影响：** 新增 `Rg01FullStateOracleTest`/`Rg02FullStateOracleTest`（预计 1000-1300 行）+ `Ledger.sq` 投影查询 + RG-02 rename 最小闭环；RG-01/02 oracle 达到与其他 RG 相同的完整比较标准。现有测试与产品构建不受影响。

**schema 边界补充（2026-08-09 实现确认）：** category_rename 最小闭环需要持久化 name history——新增 schema v17→v18 迁移（`17.sqm`，`rg02_category_name_history` 表，append-only：version 1 由冻结 v1 catalog 在 root 启动时 seed，accepted rename supersede 当前记录并追加下一版本），supersede 本决定"不改 schema"表述（其本意为 RG-01/02 现有行为的投影零运行时改动，不涉及 reconciliation 持久化；rename 的 name-history 持久化是其闭环的必要组成部分）。`SqlDelightRg03TransferStore`/`SqlDelightRg04Store`/`SqlDelightRg05Store` 各 +1 行不可达违规分支补全是 `CategoryRenameViolation` sealed 类型穷尽性的编译必需，语义中性。投影器从该表重建 catalog 分类名与 `category_name_history`，既有 commit port 与 reconciliation 持久化保持零改动。

**关联决定：** `D-047`、`D-048`、`D-069`、`D-070`、`D-071`、`D-074`、`D-081`、`D-082`、`D-086`
---

## D-088 异源审查确认缺陷修复授权（P0/P1/P2 批次）

**状态：** 已确认

**决定：** 根据用户明确授权（2026-08-10），对异源审查核实报告（2026-08-10，10 项确认缺陷 + 1 项部分成立 + 5 项残留）按 P0→P1→P2 三个批次修复，范围如下：

**P0（发布/持久化不变量，完整 high-risk 拓扑）**

- `PUB-001`~`PUB-005`：重构 `tools/python/golden_cases/{rg03,rg05,rg06,rg09,rg10,rg11,rg12}_publication.py` 七个同模板发布工具并补齐对应 `tests/python/test_*_publication.py` 测试。验收语义：提交点固定为 journal 删除（journal 存在 = 未提交可回滚，journal 删除 = 已提交）；恢复按相位动作表执行且相位感知（首发布无旧 output 时区分“删新 output”与“保留”）；journal 中 output/temp/backup/manifest 五路径 resolve 后强制父目录 == 发布目录且匹配文件名模式（containment）；journal 写入改为 temp + fsync + `os.replace` 原子替换；direct-v2（RG-11/12）强制 `source_bytes == expected_bytes`。每 phase 失败注入与崩溃窗口测试（首发布场景、backup 清理窗口）必须覆盖，且保持既有幂等、失败隔离与恢复语义。
- `RG12-001`：RG-12 持久化 guard 矛盾状态（PENDING fact + MATCHED history）与零 history match 洞闭合。已发布迁移文件（1.sqm~17.sqm）一字不改，修复以**新迁移边 `18.sqm`（v18→v19）**重建/追加 guard trigger，`Ledger.sq` fresh schema 同步，补顺序反例断言与 fresh-v19 vs 链式等价验证。runtime 写入顺序（history 先于 fact）保持不变，全部既有 RG-12 store/oracle 测试必须原样通过——本修复是持久化层防御加深，不改变任何运行时行为。

**P1（验证补齐，normal tracked）**

- `MIG-001`：Rg11/Rg12 store 失败注入/拒绝测试补齐 close→reopen 与完整 snapshot 相等比较（含全部 RG-12 owner 表，如 `rg12_transaction_version_metadata`/`rg12_report_period`/`rg12_consumption_record`/`rg12_returned_id`）。
- `MIG-002`：`LedgerDatabaseMigrationTest` 补 3→4、5→6 DDL 冲突注入（HIGH stage/rebuild 链）与 17→18、18→19 新边注入。测试补齐若暴露真实缺陷，停止并升级，不在本批内擅自修复。

**P2（契约硬化 + 卫生，分级）**

- `CONTRACT-001`（零迁移部分）：`CurrencyUnit` 增加 `init { require(code.isNotBlank()); require(precision in 0..18) }`、`Money.ofMinor` 同步受检、五处 decoder/replay 构造点统一解码边界校验。**前置 gate：冻结 fixture 全量 currency 值扫描确认无越界值**（含 precision 边界），扫描发现越界即停止升级。SQL CHECK 全量硬化不在本决定授权范围（需单独评估，见影响）。
  - 解码/构造路径的逐点校验由 `CurrencyUnit` domain 单点闸门吸收（全部构造路径经 init 校验，冻结 fixture 预扫描 GATE PASS），不逐点加码。
- `RG08-001`：按用户确认的方案 (b) 执行——以文档决定明确 RG-08 无 actualReceiptAt 时 statistics 的 fallback 契约，并微调两处 fallback 实现从 effectiveAt 对齐到 statisticsAt（`SqlDelightRg08Store.kt:357`、`Rg08Operations.kt:1972`），与 RG-11/12 的 statisticsAt fallback 语义一致；不新增 `statistics_at_text` 列、不新增迁移边。**强制条款：RG-08 publication（未来另行授权）前必须强制再评估该 fallback 契约。**
  - `effective_at_text` 列名承载 statisticsAtText 语义登记为已知设计债（`SqlDelightRg08Store.kt:615` 读取侧）。
- `TRACE-001a`：`docs/DECISIONS.md:1131` 与 `:1153` 的授权来源工具痕迹表述改为中性表述（如“根据用户明确授权（2026-08-09）”），保留授权事实与日期。
- `TRACE-001b`：`.gitignore` 增加 `*.journal.json` 忽略模式。
- `R1`：同步 `README.md`、`docs/CURRENT_STATE.md`、`docs/ROADMAP.md` 至 schema v18（及本决定新增迁移边落地后的实际版本），并登记 RG-01/02 完整比较已实现、category_rename 已实现。
- `R2`：`rg-11-closure-proposal.md` 与 `rg-12-closure-proposal.md` frontmatter 状态与 closure gate approved disposition 对齐（只回填事实，不改结论）。
- `R3`：RG-01/02 oracle 验证证据（review disposition、verifier 结果）登记到 tracked 工件（如 D-087 关联决定段落或 closure 登记文件）。

**附带约定：** 根据用户 2026-08-10 授权（修复批次附带约定），修复批次全部 commit 采用英文 Conventional Commits 规范（`feat:`/`fix:`/`docs:`/`test:`/`release:`/`merge:`/`ci:`/`chore:` 类型，正文英文），该约定写入 `docs/CONTRIBUTING.md`（batch-4 范围）。

**supersede 条款：**

- 本决定在**发布工具实现层面 supersede D-086 的发布工具执行条款**（先例：D-081 对 D-077、D-087 对 D-069/070/071）：D-086 批准的 5 个 publication target、manifest 条目与已发布工件保持原样不变；本决定只重构工具实现，使其真正满足 D-086 已要求的原子性、幂等、失败隔离与 journal 恢复，并闭合 PUB-001~005。
- **不 supersede D-087 的投影零运行时改动表述**：CONTRACT-001 的 `CurrencyUnit` 校验属于共享域代码的契约硬化，是新授权范围，不改变 D-087 的 RG-01/02 投影方案；`SqlDelightRg12Store` 写入顺序不因 RG12-001 改变。
- 重申 push 条款：push 需用户单独授权（D-085:1117 与 D-086），本决定不延续任何既往 push 授权。

**验证要求：**

- batch-1（H1）/batch-2（H2）：完整 high-risk 拓扑——单 writer 隔离 worktree → 独立 spec reviewer → 独立 quality reviewer → distinct verifier → 主代理复跑 critical diff 与受影响全套件（Python tests 全量 + `project_docs` + `gradlew` 相关模块，最终候选 `verify-project.ps1 -Scope full`）。批内任何 artifact 变更使既有独立证据失效时，按冻结候选 → delta handoff 闭合流程处理。
- batch-3（N1）：normal tracked 路由——单 writer + 一个 combined reviewer + distinct verifier + 主代理 rerun；新测试暴露真实缺陷时停止并升级。
- batch-4（N2/N3）：零迁移代码部分（CONTRACT-001、RG08-001 的两处 fallback 微调）走 N2，含冻结 fixture 预扫描 gate；文档/卫生部分（TRACE-001a/b、R1、R2、R3、本决定登记）走 N3（`project_docs` + `-Scope docs`）。
- 各批次不跑 release verification（本决定不授权任何发布）；下一次真实 publication gate 在 clean worktree 用修复后工具执行 `-Scope release`。

**不授权：**

- 不重发、不修改任何已发布工件：`golden/rules-v2/{rg-03,04,05,06,07,09,10,11,12}.json` 与 `manifest.json` 保持逐字节不变（发布工具不入 manifest，工具修复不影响已发布工件）。
- 不修改冻结契约 `golden/rules/rg-XX.json`、`GOLDEN_SCHEMA.md` 既有 normative 内容、`docs/ACCOUNTING_RULES.md` 与 `.external/`（保持只读）。
- 不修改已发布迁移文件 1.sqm~17.sqm 的既有内容；schema 变更只能新增 18.sqm 及以后的边。
- 不 push：修复批次合入 main 后停在本地，push 与 CI 触发需用户单独授权。
- 不 scope 扩展：不引入新 RG 功能、不做 RG-08/RG-01/02 publication、不修未列入的缺陷、不实施 `future_draft` 能力、不实现 CONTRACT-001 的 SQL CHECK 全量硬化（除非另行单独授权）。
- 不将任何修复批次的验证结果表述为“发布”，也不以本决定授权的验证代替发布行为。

**理由：** 核实报告确认 10 项缺陷均针对当前代码有效（涉及文件未变），8 项成功复现；已发布工件无污染，故修复不触及发布产物。发布工具属于 release/publication 高风险域（含 PUB-003 路径遍历安全面），RG-12 guard 属于 migration 域（已发布迁移链不可改），两者必须走完整 high-risk 拓扑；P1 为既有行为的测试补齐，P2 为契约硬化与文档卫生，按风险分级降级处理可避免流程空转，但任何测试暴露真实缺陷或预扫描发现越界值都必须停止升级。RG08-001 两处 fallback 微调的 N2 行为中性分级以冻结 fixture 三时间折叠（occurred/statistics/effective 相同）为前提——当前不可观察且预期零行为差异；该前提不成立时须升级。

**影响：** schema v18→v19（batch-2，新边 18.sqm）；RG08-001 按 (b) 执行，不产生 v19→v20 迁移边；`Ledger.sq` fresh schema 同步并更新 `LedgerDatabaseMigrationTest`（fresh 版本测试、等价测试、新边 DDL 失败注入）。共享域 `Values.kt` 校验影响全部 RG replay/store 的 CurrencyUnit 构造点——以冻结 fixture 预扫描无越界为前提，否则停止。文档同步：本决定登记、R1（schema 版本与 RG-01/02/rename 状态）、R2（frontmatter）、R3（oracle 证据）、TRACE-001a/b。发布工具 7 文件同模板一致性由同一批次统一保证；下一次 publication gate 将使用修复后工具并重新经历 clean release verification。

**关联决定：** `D-080`、`D-081`、`D-083`、`D-084`、`D-085`、`D-086`、`D-087`

---

## D-089 RG-01/RG-02 与 RG-08 golden v2 publication 授权

**状态：** 已确认（登记时）

**决定：** 根据用户明确授权（2026-08-10），批准以下 golden v2 publication（此前 D-087/D-088 未授权项），分两批执行：

**A 批（RG-01、RG-02）**：
- expected 工件已存在且已 approved（docs/migrations/golden-v2/rg-01-expected.json：8 roots/11 ops/19 states；rg-02-expected.json：11 roots/13 ops/24 states；D-087 approved）——**冻结不动，不重新生成、不修改**。
- 新建发布工具 tools/python/golden_cases/rg01_publication.py 与 rg02_publication.py（以 rg03_publication.py 修复后模板为基底：提交点=journal 删除、相位感知恢复、六路径 containment、原子 journal、v1→v2 迁移类 source 校验 schema_version==1，**无 direct-v2 字节强制**——仅 RG-11/12 适用）。
- 对应测试 tests/python/test_rg01_publication.py 与 test_rg02_publication.py（模板克隆改写）。
- 发布产出：golden/rules-v2/rg-01.json、rg-02.json + manifest.json 登记（格式照 D-086:1141：approval_status approved、discovery.comparison "11-operation full comparison"/"13-operation full comparison"、四组 hash、object_counts、operation_status_counts）。

**B 批（RG-08）**：
- 前置：D-088 强制条款再评估落档——用冻结 fixture 重验三时间折叠（occurred/statistics/effective 相等；已实证 rg-08.json effective_at 零出现），在 D-089 或 closure 登记中落档。
- tools/python/golden_cases/v2.py 扩展：supported_transaction_types 增加 RG-08（opening_balance/lending_disbursement/lending_collection）、_RG08_ACTIONS 注册表、_ACCEPTED_ACTION_ENTITY_COUNTS 条目、_validate_rg08_action_effects 与 field-path mirror（12 条投影 registry 约束，含 2 个 rejected field_path 改名：negative-interest→principal、guessed-split→components）。
- schemas/golden-case-v2.schema.json 的 operationBase.action_type enum 增加 RG-08 动作（先例：RG-09 53440c4、RG-10 7cf419a），GOLDEN_SCHEMA.md 同步。
- 新建 expected builder（test_rg08_v2_expected.py 等价物，44 ops，确定性生成）→ 产出 docs/migrations/golden-v2/rg-08-expected.json（operation_status_counts 6 accepted/13 no_change/25 rejected；object_counts 以 builder 与 oracle 为准）。
- 新建 tools/python/golden_cases/rg08_publication.py + tests/python/test_rg08_publication.py（模板克隆，44-operation full comparison）。
- 发布产出：golden/rules-v2/rg-08.json + manifest.json 登记。

**验证要求（两批均 H 级 publication/release 域）**：
- 完整 high-risk 拓扑：单 writer 隔离 worktree → 独立 spec reviewer（对照 D-086 格式/工件哈希语义与 D-089 范围）→ 独立 quality reviewer → distinct verifier → 主代理复跑 critical diff 与受影响全量套件。
- 发布执行：clean worktree（不得 -AllowDirty）跑 verify-project.ps1 -Scope release，用修复后工具执行 publish（D-088:1217 先例）；manifest 四组 hash 与工件文件一致性由 verifier 独立核验。
- 两批均不跑"发布即 push"；push 与 CI 触发需用户单独授权。

**不授权：**
- 不修改/重发任何已发布工件（golden/rules-v2/{rg-03,04,05,06,07,09,10,11,12}.json 与 manifest 既有条目逐字节不变）。
- 不修改冻结契约 golden/rules/rg-XX.json（只读输入）。
- 不修改已发布迁移文件 1.sqm~18.sqm（本决定不涉及 schema 迁移）。
- 不 push；不 scope 扩展（不引入新 RG 功能、不修改 expected 已批准内容、不做 RG-08 之外的 schema 演进）。
- RG-08 的 expected 生成后必须经独立审查确认与 Kotlin oracle 双侧重合（expectedFieldPath 12 条投影），不得直接发布未经审查的生成产物。

**理由：** RG-01/02 的 expected 已由 D-087 批准且 validator 实测通过，发布仅需工具与登记；RG-08 mapping gate 已 approved（rg-08-closure-proposal.md）且 D-088 修复完成（含 statisticsAt fallback），剩余为 v2 支持扩展与 expected 构建。已发布工件零污染（manifest 四组 hash 一致），本决定不改动任何既有工件。

**影响：** golden/rules-v2 新增 3 个工件（rg-01/02/08.json）+ manifest 3 条新条目；v2.py 与 golden-case-v2.schema.json 扩展（RG-08 支持）；docs/migrations/golden-v2 新增 rg-08-expected.json；文档同步（README/CURRENT_STATE/ROADMAP/GOLDEN_V2_INVENTORY）；发布集合从 9 case 增至 12 case。

**关联决定：** `D-084`、`D-086`、`D-087`、`D-088`

---

## D-090 RG-08 rejected field path 与 publication LF integrity 契约

**状态：** 已确认

**决定：** 根据用户明确批准（2026-08-10），关闭 D-089 实施中发现的 RG-08 rejected field-path 冲突与跨平台 publication raw-byte hash 缺陷，并据此修正 A 批验收门。

**RG-08 field-path 修正：** D-089 B 批所述 12 条 rejected field-path 投影中，冻结 v1 `guessed-split` 的 `field: "components"` 必须映射到 canonical `$.attempted_input.split_source`，与 active `rg-08-closure-proposal.md`、`Rg08FullStateOracleTest.expectedFieldPath` 和 `Rg08Operations` 的 `ATTEMPTED_SPLIT_SOURCE` 一致；D-089 的“`guessed-split`→`components`”单处措辞在此范围内被 supersede。`negative-interest` 仍映射到 canonical `$.attempted_input.principal_amount`，本决定不改变该项。此修正只校准 v2 validator/builder 的 rejected field-path mirror，不修改冻结 v1、runtime、Kotlin oracle 或 closure 已批准语义。

**publication LF integrity 契约：** 参与 publication raw-byte hash 的 repository bytes 统一以 **UTF-8 + LF** 为规范域。现有 11 个 manifest case（RG-01/02/03/04/05/06/07/09/10/11/12）必须按 Git LF blob 重新计算并仅修正 raw-byte hash 元数据：顶层 `source_byte_sha256`、兼容别名 `source_sha256`、`expected_byte_sha256`，以及 `hashes.source_sha256`、`hashes.expected_sha256`、`hashes.output_sha256`；`canonical_sha256` 及 `hashes.canonical_sha256` 保持不变。`source`、`expected` 与 `output` 的既有 Git blob 内容、对象计数、operation 状态计数和经济语义均不得改变。

在仓库根 `.gitattributes` 增加且仅增加 publication hash 所需的精确覆盖：`golden/rules/*.json text eol=lf`、`docs/migrations/golden-v2/*-expected.json text eol=lf`、`golden/rules-v2/*.json text eol=lf`。实现一个独立、可复用的 pre-publish integrity gate，并在各现有 publisher 入口做调用该 gate 所必需的最小接线；本决定不授权重构 9 个 publisher 的常规发布、journal、恢复或事务逻辑。

该 gate 必须直接读取 filesystem bytes，以 strict UTF-8 解码并要求 LF-only（拒绝 CRLF 与裸 CR），不运行 Git，也不静默 normalize。它在读取并计算供验证的数据后，对参与本次发布的 source/expected 与既有 manifest 全量登记工件核验 raw-byte hash、expected/output byte equality、canonical hash、路径与登记关系；任一不符必须 fail closed。Git LF blob 只用于本次 11 条 manifest raw-hash metadata 重算，以及 fresh-checkout/release proof，不是 publisher 运行时输入；陈旧 CRLF checkout 必须 fail closed，并由 publisher 外部刷新工作树后重试。

**publisher 时序：** 若不存在 publication journal，直接运行全 manifest integrity gate；若存在合法 journal，则必须先按 D-088 既有 containment、ownership 与 phase-aware recovery 语义完成恢复。恢复完成后、stale dotfile sweep 或任何新 publication transaction/mutation 之前，运行全 manifest integrity gate。integrity 失败不得产生任何新的 publication mutation，也不得阻止、撤销或破坏已经完成的合法恢复；非法 journal 仍按 D-088 fail closed，不能绕过 journal 校验去运行 integrity gate 或开始发布。

**回归与验收：** 新增全 manifest pre-publish integrity 回归，逐 case 独立核验 source/expected/output raw-byte hash、expected/output byte equality、canonical hash、路径与登记关系；新增 LF 成功、CRLF/裸 CR 拒绝且零 publication mutation、fresh checkout 复算一致和重复执行幂等回归。RG-01/RG-02 A 批此前生成的工件与 manifest 登记仅是候选，跨平台 acceptance 重新打开；只有上述 11-case metadata 修正、EOL contract、回归测试、独立 spec/quality review、distinct verifier、主代理复核和 clean release gate 全部通过后，A 批才可认定完成。RG-08 B 批继续受 D-089 已批准 publication target 约束，完成 B-1/B-2 及同等 release gate 前不得发布。

**supersede 边界：** 本决定仅就 11 个 case 的 manifest raw-byte hash metadata、必要的 LF `.gitattributes` contract、独立 pre-publish integrity gate、各 publisher 入口最小接线及其测试，supersede D-088 中 manifest “逐字节不变”、“不重发/不修改 manifest”以及“不修未列入缺陷/不 scope 扩展”的相反限制，并在同一窄范围内 supersede D-089 对既有 9 个已发布工件 manifest 条目“逐字节不变”的限制。这不是重新发布既有 case：9 个既有 output 工件本身仍逐字节不变，RG-01/RG-02 的 source、approved expected 和已生成 output 工件同样保持 Git blob 内容不变。D-088 的 publisher 安全、原子性、journal/recovery 契约与 D-089 的 publication 授权其余部分继续有效；本决定不授权 push，也不因 metadata 修正自动满足验收或 clean release gate。

**不授权：** 不修改任何冻结 `golden/rules/rg-XX.json` 的 Git blob，不修改既有 `docs/migrations/golden-v2/*-expected.json` 或 `golden/rules-v2/rg-XX.json` 工件内容，不修改 runtime、Kotlin oracle、closure 语义、schema migration 或 `.external/`；不执行 RG-08 publication，不 push，不以本决定替代后续 clean release verification。

**理由：** 当前 11 个 case 的 33 组 source/expected/output raw-byte hash 全部匹配 Windows CRLF 工作树而不匹配 Git HEAD 的 LF blob，canonical hash 11/11 仍匹配；因此缺陷属于 publication byte-domain 与 manifest metadata，不是 JSON 语义或工件内容变化。固定 repository LF bytes、用 `.gitattributes` 消除 checkout 歧义并在 publisher mutation 前 fail closed，才能让 fresh checkout、跨平台验证和幂等发布共享同一可复算事实。RG-08 的 `guessed-split` 冲突则由现有 closure、oracle 与 runtime 三方一致证据直接裁决为 split-source canonical path。

**影响：** 后续 writer 可在本决定的窄范围内修复 manifest 11 个 case 的 raw-byte hash 元数据、增加 `.gitattributes`、实现一个共享 pre-publish integrity gate、在各 publisher 入口做最小 gate wiring 与增加 publication integrity 回归，并将 RG-08 B-1 mapping 固定为 `$.attempted_input.split_source`。任何 publisher 常规逻辑重构、工件 JSON 内容、canonical hash、计数或经济结果变化都超出授权并必须停止升级。

**关联决定：** `D-084`、`D-086`、`D-088`、`D-089`

---

## D-091 DATA-001 Cross-RG loader 统一方案裁决

**状态：** 已确认

**决定：** 采用方案 1A（schema 统一）解决跨 RG loader 硬失败问题。新建共享 `formal_transaction_metadata` 表，合并 RG-08/09/10/11/12 五张私表的公共列（ledger_id、transaction_id、created_at、statistics_at_text），RG-11/12 私表整表删除，RG-08/09/10 私表仅保留 `source_record_id` 列。迁移顺带统一 RG-08 的 `effective_at_text` 命名偏差（列值即 statistics 值，迁移即语义重命名）。

**理由：** 五个 `selectRgXXFormalTransactions` 已按 ledger_id 全量取数（`replayBalances` 需要全账本余额），"统一账本"是既有 SQL 设计已内嵌的方向。1A 使该方向落地为一致契约，消除私表-全取查询的结构性矛盾。单账本单 RG 约束不符合产品实际（同一银行卡可同时涉及借贷、储值、分期），会将技术限制倒逼产品形态。当前处于底层阶段，一次性工程成本可接受。

**影响：**
- 新建 `19.sqm`（v19→v20）迁移文件，重建五私表 + 共享表 + 守卫触发器迁移。
- 五个 Store 的读写侧全部切换到共享表并统一为 statistics 语义。
- RG-11/12 私表整表删除，守卫语义转移到共享表。
- RG-08 的 `effective_at_text` 命名偏差顺带闭合。
- 需新增混存验收测试（同一账本 manual + RG-08~12 交易，各 Store commit/reopen/snapshot 相等）。

**实施登记：** 已实施并合入 main（2026-08-12，commit `79241fe`；后续已随阶段 4 提交推送 origin）：`19.sqm` v19→v20 六阶段迁移落地（共享表、回填、fail-closed 数据守卫、RG-08/09/10 私表精简重建、RG-11/12 私表删除、共享表守卫触发器）；五个 Store 读写侧切换共享表并统一 statistics 语义（RG-09/10 写侧两步：共享表 + source 私表，读侧双表；RG-08 写侧共享表 + 恒 null source 占位行；RG-11/12 单步共享表 + UPDATE 改名 `updateFormalTransactionStatisticsAtText`）；RG-09 读侧以共享表 `statistics_at_text` 重建 `effectiveAtText`（共享列恒持有原 `effective_at_text` 的精确字节，D-065 指纹字节不变由等值保证）；混存验收测试（`MultiRgStoreCoexistenceTest`）与 v19→v20 迁移测试（数据保持 + 守卫 + 原子回滚）已完成。该批交付时 schema 为 v20；P4-02 后续将当前 schema 推进至 v21。

**关联决定：** `D-084`、`D-088`

## D-092 DATA-002 阶段 4 统一 source/evidence/candidate 所有权模型（方案 A）

**状态：** 已确认

**决定：** 采用方案 A：新建共享导入链（非 `rgXX_` 前缀共享表：source → evidence → candidate → confirmation → 正式账务），12 套 RG 竖井原样冻结为 jvmTest 验证语料，首版退役零张，某 RG 的 store 指向共享 owner 后按 19.sqm 模板逐个退役。否决一次性合并（原 `DATA002_DECISION.local.md` 提案已归档）。

**理由：** ROADMAP 阶段 4 前置条款"跨场景可复用范围限于严格解析、明确确认、request snapshot 与正式账务链；不得提前泛化专项 DTO、表或业务 owner"；竖井是 D-072/D-074 已批准的设计决定而非技术债；阶段 4 完成条件（标准来源走到正式账目）由新建共享链满足，不需要先合并竖井。代码现实：domain 层 37 文件零 `RgXX` 前缀（已共享），共享表 14 张，产品路径缺口在解析层、共享 import 链、去重与发号。

**影响：**
- 新建共享 import 链表（新迁移号 v20 → v21+），不动已发布迁移与 golden 工件。
- 共享链只被产品路径写；竖井只被 jvmTest 写；共存测试证明互不引用、互不污染。
- 首版退役零张竖井；退役走 19.sqm 模板。

**实施登记：** 未实施（2026-08-12 决策登记；实施按 `PHASE4_DESIGN_PACKAGE.local.md` 行动画面第 2/4 步推进）。

**关联决定：** `D-072`、`D-074`、`D-091`

## D-093 RUNTIME-001 运行时 ID 与时钟端口

**状态：** 已确认方向；争议实施条款暂停实施并由 `D-096` 重新审议，未实施

**暂停实施与重新审议：** 保留且可继续作为批准方向的只有应用层可注入 ID/时钟能力，以及 Golden 路径注入冻结 ID/时间以保持确定性。以下旧正文条款暂停实施：把 `AndroidLedgerDatabase` 指定为生产组合根；抽取或冻结 `goldenV2UuidV5`/UUIDv5 原语作为产品技术；以 data Store 构造/消费端口模式决定产品生成策略或装配边界；运行时 ID 的命名空间化/序号方案。历史正文保留用于审计，但不能授权实现；替代细节经 D-096 或后续决定获用户明确批准前，本项实施保持阻塞。本决定历史文本从未批准产品复用 Golden 命名空间或名字布局。

**决定：** 产品路径新增两个可注入端口：ID 生成端口（形式化现有 IdSource 模式——`ConfirmedManualExpenseIdSource` 等先例；抽取 `GoldenV2Identity.goldenV2UuidV5` 的 SHA-1/UUIDv5 骨架为通用原语，golden 名字布局原样保留）+ 时钟端口（全新能力，commonMain 现零 `Clock`/`now()` 用法）。golden 回放路径注入确定性实现（fixture 驱动 ID 与文本时间），产品路径注入运行时实现；生产装配点扩展 androidMain `AndroidLedgerDatabase`。

**理由：** golden 回放确定性是硬约束（manifest sha256 字节级锁定，fixture 文本时间 + `+08:00` 偏移），现有全部 ID/时间由调用方传入（`Rg04ImportOperations` 等），产品路径无人供应；data 层已有 `IdentitySource` 端口消费端（Rg03/04/05/07），application 已有 IdSource 先例，形式化既有模式即可，非新发明。

**影响：**
- 端口契约在 application 层（先例位置）；data 层 store 消费端按 `private constructor` + 端口参数模式扩展。
- 时钟端口必须保证 golden 路径注入 fixture 时间，否则字节级 golden 比对立即失败。
- 运行时 ID 实现（UUID 命名空间化/序号）实施时按平台能力定。

**实施登记：** 未实施（设计包行动画面第 1 步）。

**关联决定：** `D-092`

## D-094 IMPORT-001 来源解析适配器契约

**状态：** 已确认方向；争议实施条款暂停实施并由 `D-096` 重新审议，未实施

**暂停实施与重新审议：** 保留且可继续作为批准方向的只有来源格式 adapter、raw/source facts 与派生字段分层、类型化诊断，以及 provenance/confidence 随候选保存。以下旧正文条款暂停实施：以个人 Python `Txn`/`TransactionFact` 形状定义产品中间表示或共享 source 表；把所有解析失败统一送入待补资料队列；冻结微信 XLSX/支付宝 CSV 的先后顺序、编码/宽容解析细节或“免依赖 XLSX”技术；把私人 Python 枚举直接提升为产品契约。历史正文保留用于审计，但不能授权实现；替代契约和技术经用户明确批准前，本项实施保持阻塞。

**决定：** 解析适配器契约：适配器接口（平台格式语义隔离在 adapter）+ 归一化中间表示（raw 原文保留 + 派生字段双轨 + 丢弃原因枚举 + 证据分级 origin×confidence 从第一天带上）；中间表示与共享 source 表形状一起设计；首适配器微信导出文件（xlsx，免依赖读取，参考淘宝 zipfile+sharedStrings 直读先例），支付宝 CSV（gb18030）紧随；解析失败不猜测，进待补资料队列（D-032）。

**理由：** Python 引擎（D-020/D-053 复用方向）已有完整行为基线：表头行扫描定位、编码回退链（gb18030 / utf-8-sig→utf-8→gb18030）、金额宽容解析（strict/lenient 双模式）、方向词表映射 + 负金额翻转、Txn 20 字段统一形状、证据分级枚举（EvidenceOrigin/Confidence/SourceKind）；设计缺口（丢弃原因记录、跨平台逐笔镜像配对、对方名称归一化）由新契约补齐。

**影响：**
- 移植只移格式语义与通用规则；私人配置（姓名表、关键词表、锚点值）外置私有空间，不进 tracked 文件。
- 解析输出字段集以 Python 基线为准（Txn/TransactionFact 形状）。
- 中间表示契约与共享 source 表列设计联合产出。

**实施登记：** 未实施（设计包行动画面第 3/4 步）。

**关联决定：** `D-020`、`D-032`、`D-053`、`D-092`

## D-095 IMPORT-002 去重与镜像键推导

**状态：** 已确认方向；争议实施条款暂停实施并由 `D-096` 重新审议，未实施

**暂停实施与重新审议：** 保留且可继续作为批准方向的只有同一 `request_id` 精确 replay 幂等、重复来源不得产生第二次余额或报表影响，以及通道总额比较仅作诊断展示。以下旧正文条款暂停实施：Python 键集/SHA1-16 业务指纹作为事实级身份、共享 source 内容指纹或破坏性去重依据；先到先得折叠；状态/支付方式/备注等字段的固定排除；把通道总额对碰当作镜像合并完成条件；逐笔配对、名称归一化和 matcher 基数的任何默认方案。历史正文保留用于审计，但不能授权实现；来源身份、重复候选与逐笔 matcher 的替代细节经用户明确批准前，本项实施保持阻塞。

**决定：** 双层去重：批次级 `request_id` 幂等（`commitOnce` 先例）+ 事实级业务指纹（键集按 Python 基线 `[account, platform, time, direction, amount, category, counterparty, item, order_id, merchant_order_id]`，`status`/`payment_method`/`note` 不参与；存共享 source 表内容指纹列）。镜像首版采用通道级总额对碰（谓词选择器 + 精确到分求和 + 差额展示），逐笔配对与对方名称归一化后置（无现成行为基线）。

**理由：** RL-08（同一来源重复导入不产生第二次余额或报表影响）是 `core_required` 硬验收，只做批次幂等不满足；Python `dedupe_transactions`（SHA1-16 业务指纹 + 先到先得）与 `mirror_checks`（通道总额对碰、无时间窗、无模糊匹配）为已验证行为基线。

**影响：**
- 共享 source 表含内容指纹列（参考 rg07/08/09/10 `immutable_payload_hash` 先例）。
- 镜像差额只展示不自动处理（D-015/D-035 挂起语义）。
- 指纹键集与 Python 基线对齐测试。

**实施登记：** 未实施（设计包行动画面第 6 步）。

**关联决定：** `D-015`、`D-033`、`D-035`、`D-092`

## D-096 阶段 4 运行时能力、解析、来源身份与镜像匹配边界修订

**状态：** 提案，待用户明确批准；不授权实施（2026-08-13 更新：D-098 已接受，关闭本提案中 raw identity 组合/碰撞处置、raw retention persistence（整文件除外）及 D-097:1467 所列四项的 P4-02 部分；其余设计门仍待后续批次，见 D-098 处置段。）

**提案目的：** 根据正式需求、架构边界和当前源码/测试现实，记录 `D-093` 至 `D-095` 中需要纠正的来源事实，并提出后续身份、解析和 matcher 契约的设计门。`D-092` 的共享导入链方向不在本提案中重开。除下列“已确认依据”外，所有“提议”与“待批准设计门”均不是已批准产品行为；用户明确批准前不得开始依赖 D-096 的实现。

### 运行时 ID 与时间

**已确认依据（ID）：** ID 能力属于应用用例边界；当前 `ConfirmedManualExpense` 先例把 `idSource.next()` 放在传给 `commitOnce` 的 factory callback 内。持久化实现先原子 claim 请求并判断 replay/conflict，只有赢得首请求的路径才调用应用提供的 callback。因此 ID 在原子首请求路径内惰性物化一次；精确 replay、identity conflict 和并发失败方不得消耗 ID。持久化负责 claim、事务、冲突和 callback 调用，不选择生成策略。既有 RG 专用 Store/IdentitySource 保持冻结回放语料，不构成产品装配先例。

**已确认依据（Clock）：** Clock 是应用能力，来源发生、支付、入账、起息和观察时间是来源事实，Clock 不能补写或覆盖这些事实；Golden 回放继续注入冻结 ID 与文本时间。当前源码没有产品 Clock 端口或实现，不能证明 Clock 的读取位置、消费次数、retry/concurrent-loser 语义或审计时间戳分配策略。

**已确认依据（装配与 Golden）：** 当前 `createAndroidLedgerDatabase` / `AndroidLedgerDatabaseHandle` 只封装 Android SQLDelight driver、database 和既有 commit port，不是 app 组合根。`GoldenV2Identity` 的命名空间和名字布局只属于 Golden v2 冻结契约，不能被解释为产品 ID 契约。

**待批准设计门：** 产品 ID 算法、命名空间、版本与迁移策略仍未选择。若后续提议抽取底层散列/UUID 原语，必须单独证明兼容性、碰撞、离线、跨端和替换成本；D-096 本身不批准任何 UUIDv5 或其他算法。Clock 的读取/消费时机、retry 与并发失败方是否读取时间、以及处理/创建/确认/审计时间戳如何分配，均待 D-096 或后续决定明确批准。

### 解析契约与技术边界

- 归一化来源契约由产品需求、账务规则和匿名验收合同拥有，不能以个人 Python 的 `Txn`、`TransactionFact` 或其他历史内部结构作为产品字段集。Python 仅提供行为证据、迁移基线和差异比较；可移植内容必须改写为中立契约与合成测试。
- 平台层负责文件选择、权限，以及按适配器需求打开有界 stream 或 random-access source；格式 adapter 负责 CSV 字段语义，以及 XLSX 的 ZIP/OPC/XML 解码和验证。只有后续证据证明多个格式需要独立 archive 能力时，才另行评估 archive port；本提案不预设该端口。
- 不支持的文件类型、损坏或超限容器、编码失败、结构不兼容和无效行返回带来源位置的类型化诊断，不伪装成有效来源事实。文件或行已被可靠读取、但形成账务候选所需事实不足时，才保存来源事实并进入待确认或待补资料流程；任何推断字段继续与 raw/source facts 分层并携带 provenance、confidence 和待确认字段。
- raw 保留遵守最小化与隐私边界：保存可复核所需的原始字段、来源定位、格式/规范版本和完整性标识；是否保留整文件及其生命周期由后续隐私、容量、导出和删除需求决定。诊断和 tracked 测试不得包含个人标识、真实订单号、账户锚点或完整账单。
- 首批 XLSX/CSV 的具体库、免依赖实现或自研读取器均未冻结。选型前必须用受限大小、压缩炸弹/路径穿越、XML 实体或资源耗尽、日期与数字格式、shared strings、合并/缺失单元格、编码、跨平台兼容、许可证、维护与替换成本的有界证据证明可接受；否则保持技术决定暂缓。

### 幂等、来源身份、重复候选与镜像证据

**已确认依据：** 批次/命令 `request_id`、raw source record identity、duplicate-candidate detection 和 mirror/evidence matching 是四个不同问题，业务指纹不能同时替代四种契约。用户分类、分类建议、账户映射、用户配置映射和对方归一化等可变结果不能成为权威 raw identity；业务相似指纹只能提出候选，不能破坏性删除来源。通道级总额对碰只作诊断，不能链接某条证据、改变 posting reconciliation、压制候选或交易，也不能满足 RL-07 的逐记录镜像验收。

**已确认依据：** 任何会改变 evidence link 或 posting reconciliation 的镜像处理都必须先解析到精确且具备资格的真实账户 `Posting`，并遵守对应场景已经批准的证据职责、确认和对账规则。精确请求 replay 返回原结果且零新写入；同一经济事件的后到补充来源/证据可以按已批准场景追加 lineage，但不得创建第二笔正式交易，也不得重复既有 link 或 reconciliation effect；与排他性目标冲突的请求类型化拒绝且零写入。一个 posting 可接受多少 evidence、一个 evidence 可支持多少目标，以及何时属于“补充”或“冲突”，由场景合同决定，本提案不建立全局一对一基数。

**待批准设计门：** raw source record identity 尚未确定采用 provider ID、来源 locator、canonical raw identity、occurrence discriminator 或何种组合，也未批准 fallback 算法、domain separation 或碰撞处置。duplicate candidate 需要保存哪些 provenance、confidence、规则版本和人工处置字段，逐来源 matcher 的字段、时间窗、歧义模型与基数，以及 RL-08 是否扩展为跨批次、合法 lookalike、映射变化和碰撞/歧义矩阵，均须形成备选方案与匿名验收后由用户批准。

**理由：** 当前源码已经证明应用用例消费 ID source、持久化适配器实现 `commitOnce`/request snapshot 原子边界，Android 文件只提供 database handle；Golden UUIDv5 明确绑定冻结 v2 命名空间和名字布局。RG-04 导入 runtime 也已经证明镜像证据需要在精确 posting 候选中处理 target missing、mismatch、ambiguity 和 reconciliation precondition，成功时追加 evidence link 而不创建第二笔正式交易。正式需求同时要求来源事实、候选、确认与正式账目分层，逐真实账户 posting 对账，重复导入零重复经济影响。个人 Python 指纹和通道汇总只能作为行为证据，不能覆盖这些产品契约。

**影响：** `AndroidLedgerDatabaseHandle`、Golden v2 identity、个人 Python 字段形状和总额诊断不得被误用为生产组合根、产品身份、产品 schema 或 reconciliation 写入授权。活动本地 checkpoint、计划和设计材料必须与本提案及其未决门禁对齐；D-096 获得用户明确批准前，依赖其新身份、解析或 matcher 契约的实施继续阻塞。

**未决：** 产品 ID 算法与版本策略；首批 XLSX/CSV 库或自研实现；逐来源 raw identity 与碰撞/歧义处置；整文件保留、加密、导出与删除生命周期；duplicate candidate 的数据合同、阈值和人工审核交互；逐来源 mirror matcher 的字段、时间窗口、基数和验收矩阵。以上事项必须在对应需求、隐私、技术证据或匿名验收明确后另行裁决，不能由实现默认值冻结。

**关联决定：** `D-001`、`D-015`、`D-016`、`D-020`、`D-032`、`D-033`、`D-043`、`D-044`、`D-045`、`D-048`、`D-051`、`D-052`、`D-053`、`D-056`、`D-073`、`D-085`、`D-092`、`D-093`、`D-094`、`D-095`

## D-097 P4-01 normalized source 与类型化诊断验收合同

**状态：** 已确认；仅批准 contract-only P4-01，不授权实现或 P4-02

**决定：** 根据用户对 P4-01 推荐组合 `B/A/A/B` 的明确批准，冻结来源中立的 normalized source、类型化诊断和匿名验收语义。P4-01 是逻辑合同交付批次，不是 Kotlin API、JSON 格式、JSON Schema、数据库 schema、parser、provider 或实现授权；正式登记完成即关闭 P4-01 合同交付，下一门是 P4-02 的 raw identity/retention/provenance、candidate lifecycle 与 atomic confirmation。

**NormalizationResult 逻辑投影：** `contract_version = 1`。`outcome` 值域为 `complete | partial | rejected`：存在任一 record error 时为 `partial`，此时可以有零条或多条可靠 normalized record；只有 fatal input/container/structure failure，或无法建立可靠 record boundary 时才为 `rejected`。`records` 按语义 multiset 比较并保留重复计数，`diagnostics` 按冻结集合比较。该投影是逻辑 oracle，不规定 Kotlin 类型、JSON 或 schema。

**批次与错误语义：** input/container 级 fatal failure 必须 fail-closed，整个 normalization result 为 `rejected` 且不产生 normalized record；record 级错误相互隔离，可靠记录继续保留，混合成功与记录错误的批次返回 `partial`。无效行只产生脱敏 typed diagnostic，不生成 normalized record。只有行已被可靠读取并形成来源事实、但后续必要事实不足时，才形成 `valid_incomplete` normalized record；无效 amount/time 属于 record error，不得降格为 incomplete。

**v1 合同宽度：** P4-01 v1 只冻结 ordinary expense 与 ordinary income 的最小 normalized source 核心，并通过显式合同版本后续扩展。不预设 transfer、credit、refund 的 superset，不预设 provider DTO，也不要求第一版字段容纳后续全部交易类型。未知 direction/status token 必须保留可复核的 raw/source token，同时 normalized 语义保持 unresolved；不得猜测、默认或静默映射为已知值。

**normalized record 逻辑投影：** 每条 record 包含安全 `source_location`、`record_kind = ordinary_flow_source`、`completeness = valid_complete | valid_incomplete`、`unresolved_required_facts`，以及 amount/currency/occurred_time/direction/status 五类 source fact。每类 source fact 都有 `presence = absent | explicit_null | present`；present 只携带匿名验收所需 source token 与机械 parsed value。derived 投影只包括 `normalized_direction`、`normalized_status` 和 `ordinary_flow`，每项记录 `rule_key`、`version = 1`、input roles 与 `confidence = exact | unresolved`。本合同不加入 account、category、candidate 或 identity 字段。

**完整性：** `valid_complete` 要求 exact amount、currency、带 offset 的 source time、known direction 与 known status 均可靠。可靠 record/source facts 已形成，但任一必要项 absent、explicit null 或 unresolved 时为 `valid_incomplete`，并列出 `unresolved_required_facts`。无效 amount/time 不产生 normalized record。

**事实分层：** source facts 与 derived facts 必须分层。对输入内容的机械、可复核 decode 可以作为 source fact，provenance 为 `source_declared + mechanical_decode`；方向推断、默认币种、符号翻转、账户映射、分类、交易类型、重复判断和 mirror 判断均为 derived fact 或后续批次职责，不能回写成来源事实。已知映射使用 `direction_token_v1`、`status_token_v1` 或 `ordinary_flow_v1` 的 version 1 rule trace 与 `exact` confidence；未知映射保留 source token并使用 `unresolved` confidence。

**金额与时间：** amount 比较精确十进制值、currency 与 source scale，禁止 binary floating point。occurred time 比较 source token、parsed temporal kind（`offset_datetime | local_datetime`）、机械解析的 components 与 offset presence；缺少 offset 时保持 unresolved，不使用 Clock 补齐。

**稳定诊断 taxonomy：** diagnostic 比较 `code`、`severity = fatal | record_error | incomplete`、`scope = input | container | structure | record | field`、安全 location 与可选 field role；message 不稳定且不比较。固定映射为：`INPUT_UNSUPPORTED`→fatal/input；`INPUT_UNSAFE_OR_OVER_LIMIT`→fatal/input 或 container；`INPUT_DECODE_FAILED`→fatal/input 或 container；`STRUCTURE_MISMATCH`→fatal/structure（无法建立可靠 record boundary）；`FIELD_AMOUNT_INVALID`→record_error/field；`FIELD_TIME_INVALID`→record_error/field；`CONFLICTING_SOURCE_FACTS`→record_error/record 或 field；`REQUIRED_FACT_MISSING`→incomplete/field；`REQUIRED_FACT_UNRESOLVED`→incomplete/field。

**安全 location 与隐私：** source location 只用于 diagnostic/provenance，不构成 raw identity。它只能由有界 opaque synthetic input reference、record ordinal 与 field role 组成；不得包含绝对路径、原文件名、worksheet 名、原始 header、raw value、完整行或个人标识。diagnostic message、日志、异常和测试失败输出遵守相同边界，不得透出底层库 exception 文本。

**重复、顺序与副作用：** P4-01 不做 dedup。输入中两个相同或业务近似的可靠记录仍分别产生两条 normalized record；语义 records 按 multiset 比较并保留 multiplicity，location/provenance 按具体 fixture 坐标比较。额外 permutation assertion 在重映射 fixture coordinates 后比较同一 semantic multiset，不能要求原 locator 在重排后不变。来源身份、duplicate candidate 与人工处置留给后续决定。P4-01 对 candidate、confirmation、formal transaction、posting、evidence link、reconciliation、balance 和 report 的创建或改变计数全部为零。相同逻辑输入重复执行必须产生结构确定的 normalization result，不依赖产品 ID、Clock 或本机路径。

**匿名验收：** `GOLDEN_TESTS.md` 中的 P4-01 acceptance 至少覆盖 valid ordinary expense、valid ordinary income、valid incomplete、invalid amount、invalid time、unknown token unresolved、mixed partial、lookalike preserved twice 和 input/container fatal rejected。每项冻结 normalization outcome、normalized records、diagnostics、absent/null/present、source/derived/provenance/confidence 以及精确金额和时间语义；不冻结具体实现表示。

**未授权与后续门：** 本决定不批准源码、测试实现、schema、migration、Golden 工件、parser 技术、provider 或来源顺序，也不批准 P4-02。`D-096` 中 raw identity、raw retention persistence、candidate lifecycle、atomic confirmation、parser、duplicate、matcher、产品 ID 与 Clock 的其余问题继续待决。整文件默认不保存；若后续 P4-02 提议保存整文件，必须先批准生命周期、加密、导出和删除合同。

**理由：** 该边界允许先冻结可机器验证且来源中立的 normalization 行为，同时避免把个人 Python 结构、某个 provider、某个解析库或后续交易类型提前固化为产品 schema。逐记录隔离保留可靠事实，fatal fail-closed 防止不可信容器伪装成部分成功；unknown token unresolved 与脱敏诊断共同防止猜测和隐私泄漏。

**实施登记：** 未实现。P4-01 只有正式合同与 acceptance 登记；不得据此开始 P4-02 或其他阶段 4 实现。

**关联决定：** `D-016`、`D-020`、`D-032`、`D-043`、`D-053`、`D-092`、`D-094`、`D-096`

## D-098 P4-02「Shared Import Spine」契约

**spine 释义：** spine（= D-092 共享导入链 source → evidence → candidate → confirmation → 正式账务，docs/DECISIONS.md:1329）。

**状态：** 已确认。用户于 2026-08-13 明确接受 P4-02 契约（四选项 A/A/A/A 与全部细节条款）；按交付形态 A 授权实施共享 spine 最小实现。

**决定：** 根据用户于 2026-08-13 对 P4-02 gate 四选项 A/A/A/A 的明确批准，将四选项与下列细节推荐值固化为逻辑合同：领域 1 raw identity 采用组合确定性身份（选项 A）、领域 2 raw retention/provenance 采用哈希+元数据（选项 A）、领域 3 candidate lifecycle 采用共享 spine + 全状态值域（选项 A）、领域 4 采用 atomic confirmation 契约 + spine 最小实现交付（选项 A）。用户已于 2026-08-13 明确接受并登记；按交付形态 A 授权实施共享 spine 最小实现。

**领域 1 — Raw identity（选项 A：组合确定性身份）：**

1. 主身份 = D-097 来源定位的记录级分量投影 `(input ref, record ordinal)` 的确定性组合；field role 只作 diagnostic/provenance 定位，不进入 raw identity。来源定位整体维持 D-097「只用于诊断/溯源、不构成身份」的边界（docs/DECISIONS.md:1461、docs/ARCHITECTURE.md:106）；raw identity 只是该定位器在记录粒度上的投影，不含 raw value、绝对路径、原文件名、header、整行或个人标识。
2. 规范化内容哈希作交叉校验：RFC 8785 canonical JSON 规范化（沿用 `Rg09Fingerprint.kt:7-10` 文档注释先例：string-only JCS 子集，非完整 canonicalizer；D-065，docs/DECISIONS.md:791）。哈希在 intake 时由入站字节一次性计算（原文不落盘、事后不重算）；与 golden publication canonical hash 的跨实现可比性不成立（docs/GOLDEN_SCHEMA.md:528-530），编码为小写 `sha256:<hex>`（先例 docs/GOLDEN_SCHEMA.md:529）。只作诊断、不构成身份、不参与去重。
3. provider ID 若存在仅作诊断字段，不作为身份依据（隐私边界）。
4. 碰撞处置：hard reject（fail-closed；碰撞处置先例 docs/GOLDEN_SCHEMA.md:524；该区间的 UUIDv5 机制不采纳，见 D-093 暂停）；不采用 D-095 已暂停的『先到先得折叠』。
5. 与产品运行时 ID 分离：raw identity 是确定性语义键，不是产品随机 ID；产品 ID/Clock 算法仍留待后续阶段决定（docs/DECISIONS.md:1413、docs/ARCHITECTURE.md:149）；不复活 D-093 暂停条款（UUIDv5 抽取、运行时命名空间化/序号方案）。

**领域 2 — Raw retention/provenance（选项 A：哈希+元数据）：**

1. 保存：规范化内容哈希（immutable）+ 完整性标识 + 来源定位 + 格式/规范版本；不存原文。沿用 rg08_source_record 先例（Ledger.sq:6073-6091：original_source_payload_hash / immutable_payload_hash / mirror_of_source_id）。本合同的规范化内容哈希列仅作完整性/诊断用途，不构成对 D-095 暂停条款（内容指纹作去重依据）的复活；任何以该哈希参与 dedup、折叠或身份的行为继续禁止（dedup 留 P4-07）。该先例及其余 rgXX 形状（Ledger.sq:502-508、:1801-1822 模式、SqlDelightRg04ImportStore 顺序）仅作形状/模式先例；产品承载一律为 D-092 方案 A 的非 rgXX_ 前缀共享表（docs/DECISIONS.md:1329, 1335），不得复用或挂接竖井表；竖井保持冻结回放语料。
2. 整文件默认不保存（docs/DECISIONS.md:1467）；生命周期/加密/导出/删除合同整体后置到独立门禁。
3. 来源事实/派生事实分层不变（docs/DECISIONS.md:1455）；运行时 Clock 不得补写来源时间（docs/ARCHITECTURE.md:67）；confirmed_at 是明确 provenance 字段（D-081）。
4. 每层保留前一层引用（分层保存 docs/ACCOUNTING_RULES.md:31；每层保留前一层引用 docs/ARCHITECTURE.md:94）。

**领域 3 — Candidate lifecycle（选项 A：共享 spine + 全状态值域）：**

1. D-092 方案 A 方向：非 rgXX_ 前缀共享表（docs/DECISIONS.md:1329, 1335-1336）。
2. 候选至少携带：来源引用、provenance rule、confidence、requires_confirmation 清单（docs/ACCOUNTING_RULES.md:213；D-094 保留方向 docs/DECISIONS.md:1365-1367；RG-04 字段先例 Ledger.sq:502-508）。provenance rule 命名沿用 golden 先例 `rule`/`rule_version`（docs/GOLDEN_SCHEMA.md:130）；产品字段名随实施批冻结，且只允许在 golden `rule`/`rule_version` 与 RG-04 `provenance_rule`/`provenance_rule_version`（Ledger.sq:504-505）两套既有先例命名中选取，不得引入第三套。
3. 状态值域作为**产品契约**登记（不是直接采用 golden）：pending_confirmation / confirmed / rejected / incomplete；rgXX 竖井的 UPPER token（如 Ledger.sq:511 `'PENDING_CONFIRMATION'`）是冻结回放语料拼写，不构成产品拼写先例。本产品状态契约版本 = 1，后续扩展经显式合同修订。status_history 追加-only，每项 {id, sequence>=1, status}（形状参照 docs/GOLDEN_SCHEMA.md:113；docs/GOLDEN_SCHEMA.md:448 仅引「实体状态 ≠ 操作结果」语义，不引状态拼写）。incomplete 为产品契约状态（golden 未采用此表示，docs/GOLDEN_SCHEMA.md:131 以 pending+requires_confirmation 表达缺失事实）；incomplete 候选不得直接确认，补全必要事实的明确操作与状态迁移矩阵随实施批冻结，历史始终追加-only。
4. 语义：导入候选默认 pending_confirmation、永不自动确认（D-073）；confirmed 状态本身不授权、不创建正式分录（D-077，docs/DECISIONS.md:951）；重复确认已确认候选返回 candidate_not_pending（D-073，docs/DECISIONS.md:897）；rejected = 人工处置终态、无正式效果；incomplete 仅按 D-097 边界（可靠来源事实已形成但后续必要事实不足；无效 amount/time 是 record error、不降格为 incomplete，docs/DECISIONS.md:1447, 1453）。

**领域 4 — Atomic confirmation + 交付形态（选项 A：契约 + spine 最小实现）：**

1. 共享确认端口 confirmCandidate(identity, snapshot)，commitOnce 语义（ConfirmedManualExpense.kt:83-112）：首请求最多一次回调；失败零残留且身份可用；成功 all-or-nothing。
2. claim-first：INSERT ... ON CONFLICT DO NOTHING + changes()，输家不调回调（Ledger.sq:1801-1822）；ID 惰性分配仅发生在回调内（ConfirmedManualExpense.kt:133-141）。
3. 校验顺序（RG-04 先例 SqlDelightRg04ImportStore.kt:94-185；产品相对先例收紧：证据/绑定校验前置，对账前置校验步骤留 P4-08 不实现）：claim → 候选存在且 status = pending_confirmation → 快照等价比对 → 证据/绑定校验 → 回调创建正式（领域用例，逐字段明确、不推断）→ 状态行 confirmed → 确认行 → receipt；同一事务。本批『证据/绑定校验』的范围 = 候选与其来源/evidence 引用的存在性与一致性校验、候选快照等价校验（stale 指纹按候选快照等价判定）；D-065 目标时点账本指纹投影、posting 匹配/绑定均不在本批范围（P4-08）。确认行必须携带创建它的操作关联：登记操作类（按实际效果，候选确认默认 `creation`，参照 docs/GOLDEN_SCHEMA.md:438）与操作引用（产品侧以本次确认请求/claim 标识为操作引用，形状先例 Ledger.sq:534-541 的 request_id 关联，`UNIQUE (ledger_id, request_id)` 与 `UNIQUE (ledger_id, candidate_id)` 语义保留）；等价 replay 必须返回原确认行与原操作引用，不得新建操作或确认行。确认行形状 candidate_confirmation：空 payload、subject=candidate；`confirmed_at` 仅在确认事实显式记录实际时间时存在，不得由来源时间、支付时间、operation time 或运行时时钟推导（docs/GOLDEN_SCHEMA.md:139-142、D-081 docs/DECISIONS.md:1007）。
4. replay/并发：等价快照 → 原 receipt 原子重放；不等价 → RequestIdentityConflict 零写入；stale 指纹 → 整体拒绝（D-065）；并发失败方不消耗 ID（docs/DECISIONS.md:1407、docs/ARCHITECTURE.md:63）。
5. 交付形态：合同接受并登记后，实施共享 spine 最小实现（source intake 身份+哈希+元数据、候选创建与状态历史、共享确认端口与实现、receipt/replay/并发测试），本批验收。

**范围与延后（明确边界）：**

- 本批包含：上述 spine 最小实现；验收标准沿用 D-097 匿名 fixtures + 诊断 taxonomy 风格，具体 fixture 清单随实施批冻结。
- receipt 与诊断契约：receipt 形状（至少含操作/请求引用、确认行引用与正式效果引用）随实施批冻结；等价 replay 必须返回与首次确认同标识、同内容的原 receipt。spine 新增诊断（identity collision、candidate_not_pending、RequestIdentityConflict、stale 指纹等）以 D-097 稳定 taxonomy 同风格（code/severity/scope/安全 location）追加注册，具体编码随实施批冻结。
- evidence 节点：共享链 evidence 节点按 D-092（docs/DECISIONS.md:1329）已批准链在 intake 创建来源时同事务创建对应 evidence 节点（先例 D-073:895『完整来源…只创建来源、证据与待确认候选』）；evidence 匹配/绑定到 posting 的语义仍属 P4-08，本批不写任何 evidence link 或 reconciliation 状态。
- intake 幂等：同一 raw identity 的重复 intake：内容哈希与来源事实等价 → 返回既有 source/candidate 引用、零新写入；不等价 → 身份碰撞 hard reject、零写入。后到补充来源/镜像证据不在 P4-02 范围（P4-08），本批不做任何 evidence link、reconciliation 或第二笔正式交易。
- 延后：provider/parser ports 与来源顺序（P4-03 各格式 evidence gate）；dedup/duplicate 数据合同（P4-07）；mirror/evidence matching 与 reconciliation（P4-08）；产品随机 ID 算法（docs/ARCHITECTURE.md:149 暂缓决定）与产品/应用 Clock 端口（应用能力，docs/ARCHITECTURE.md:63/67：仅供应处理/创建/确认/审计事件自身时间）留待后续阶段决定；确认用例的 confirmed_at 由显式确认事实提供（D-081），不依赖产品 Clock 端口；整文件保留生命周期合同（独立门禁）；RL-01~RL-08 全量闭合（P4-09）。
- golden 冻结契约与 .external/ 不动；所有新表/端口遵循不可变或追加-only、失败零残留的既有不变量。

**D-096 处置：** 本决定关闭：D-096:1429 的 raw identity 组合、domain separation 与碰撞/歧义处置（碰撞 hard reject，不设 fallback 算法）；D-096:1420 raw retention persistence 的持久化合同（整文件除外）；以及 D-097:1467 所列未决中 raw identity、raw retention persistence、candidate lifecycle、atomic confirmation 四项的 P4-02 部分。仍开：D-096:1421 parser 技术选择（P4-03）；D-096:1429/1435 duplicate candidate 数据合同与 RL-08 扩展（P4-07）；D-096:1427/1429/1435 逐来源 matcher 与 evidence/mirror 基数（P4-08）；D-096:1413 产品 ID 算法/命名空间/版本/迁移与 Clock 读取/消费时机、retry/并发失败方时间读取、审计时间戳分配（后续阶段）；D-096:1420/1435 整文件保留生命周期（独立门禁）。D-093~D-095 暂停条款维持暂停，本合同不复活其中任何暂停项（内容哈希列的诊断用途划界见领域 2 第 1 条）。

**理由：** 组合确定性身份把身份与可变产品 ID、provider ID 和内容推断分离；哈希+元数据满足隐私与复核需求而不落盘原文；全状态值域把产品候选生命周期显式登记为产品契约；claim-first 原子确认复用既有 commitOnce 先例并保持失败零残留。四选项与细节推荐值共同构成可机器验证的逻辑合同；用户已于 2026-08-13 明确接受并登记，按交付形态 A 授权实施。

**实施登记：** 已实施并合入、推送 main（2026-08-14，commit `d756391`）：shared import spine 最小实现、schema v21（20.sqm）、30-operation oracle 与迁移/原子失败/重开验证完成；范围保持本决定的 P4-02 边界。

**关联决定：** `D-065`、`D-073`、`D-077`、`D-081`、`D-092`、`D-093`、`D-094`、`D-095`、`D-096`、`D-097`

## D-099 P4-03 首个标准来源与 parser 技术（微信账单 XLSX + Apache POI）

**状态：** 已确认。按用户常设授权（研判后全权批准推荐计划，不可逆动作才停），主代理于 2026-08-14 研判批准；已随实施提交推送 main。

**决定：** P4-03 provider/format/parser 证据门按以下五组条款登记：

1. **首个标准来源 = 微信支付账单（XLSX）**。理由：用户账单体量最大（~4.4K 行）；标准 OOXML、表头 0-based row 17、11 列（`交易时间`、`交易类型`、`交易对方`、`商品`、`收/支`（单列，3 值）、`金额(元)`、`支付方式`、`当前状态`、`交易单号`、`商户单号`、`备注`）、无宏无公式；表头位置（0-based row 17）与 11 列 token 为冻结契约，禁止表头扫描与漂移容差；五类事实映射可行（amount 数值格按格定精度、occurred_at excel datetime 且文件注明 UTC+08:00、direction 显式单列、status 15+ 枚举、currency 隐式 CNY）→ valid_complete；与 D-098 spine 的 intake→候选→确认链对接。**不构成对支付宝或其他来源的顺序预选**（P4-05 独立门，PHASE4_DESIGN_PACKAGE 门规）。
2. **Parser 技术 = Apache POI（org.apache.poi:poi-ooxml 5.5.x，Apache-2.0）**。六维：格式兼容（xlsx 标准 API）；安全（内建 ZipSecureFile zip-bomb 防护默认值、不自动求值公式——仅读缓存值、宏不执行、只接受 .xlsx 拒绝 .xlsm）；跨平台（纯 Java，XSSF 读取路径不依赖 java.awt，Android minSdk 34 可行；无官方 Android 声明已登记）；许可（Apache-2.0 绿区）；维护（活跃，5.5.1）；替换（生态标准 API，代价 ~8MB 体积——登记为已知成本，APK 阶段再议）。备选 fastexcel-reader（Apache-2.0、流式、~1.5MB）落选理由：无文档化 zip 防护需自建、生态小；本批文件 <2MB、最大单文件 1780 行，流式非必需。**红区**：iText（AGPL-3.0）不采用；EasyExcel 已归档；kotlinx-csv 不存在；CSV 库选择（支付宝门 P4-05）与 PDF 库（银行批次）均延后，本批不引入。
3. **证据门结论**：微信/支付宝个人账单导出格式无公开可抓取的官方文档（kf.qq.com/cshall.alipay.com 为 JS 渲染，2026-08 实测）——格式事实全部标注为行为证据；证据源：本地账单文件结构分析、开源 beancount 脚本与微信账单解析项目文档（行为证据，具体清单登记于本地 SOURCE_REFERENCES）、本地克隆的记账应用源码仓库（无 LICENSE，仅行为证据不复制代码）、闭源记账应用（官方 import 文档作行为证据）。外部证据门已完成：SOURCE_REFERENCES、.external/DISCOVERY_DECISION_LOG（D-014 银行 PDF 非首版硬依赖、D-020）、CORE_ACCEPTANCE_PLAN 均已读。
4. **范围**：本批交付 RL-01/RL-02 普通收支 formalization 子切片——微信 xlsx 严格解析 → normalized source facts → spine intake（candidate pending）→ 明确确认 → formal。类型范围：**普通收支类型**（商户消费、扫二维码付款、二维码收款、赞赏码、其他——最终集合随批次规格冻结）；转账/群收款/零钱提现/零钱充值（转账/提现/充值类）与红包/退款类（红包、`<商户>-退款` 变体及状态含「退款」的行）本批 fail-closed 拒绝并登记为后续批次类型：转账/提现/充值类 → P4-04（Transfer Slice）维度；红包类 → 未分配批次维度，留待后续合同决定；退款类 → P4-06（RG-07 退款边界复用）。zip 解包与 6 位密码留平台适配层（解析器只接收 xlsx 字节流；文件访问属平台职责）。
5. **边界**：不引入 matcher/evidence-link/reconciliation（P4-08）、dedup（P4-07）、产品 ID/Clock（后续阶段）；schema 本批预期零变更（复用 spine import_* 表）；D-096:1421 parser 技术门对首个来源关闭，其余（第二个来源、银行 PDF）仍开；D-093~D-095 暂停条款维持。

**理由：** 证据体量与 valid_complete 可行性指向微信为首个来源；POI 在六维上全面满足且内建防护；本决定把格式事实限定为行为证据并锁死许可证红区。

**实施登记：** 已实施并合入、推送 main（2026-08-14，commit `18fae64`）：冻结规格、微信 XLSX fail-closed parser、Apache POI JVM 接线、匿名 synthetic fixtures、P-01～P-21/E-01～E-14 oracle 与 shared spine 对接完成；schema 保持 v21，且未引入 matcher/evidence-link/reconciliation、dedup 或产品 ID/Clock。

**关联决定：** `D-014`、`D-020`、`D-092`、`D-096`、`D-097`、`D-098`

## D-100 P4-04 Transfer Formalization Slice 契约

**状态：** 已批准（2026-08-17 重新批准）。纠偏修订（提交 41c1a8d）经两轮独立 spec/quality review（全部 finding CLOSED）、distinct verification V1–V5 与主代理关键检查后由主代理重新批准，轮 B 实施授权恢复。2026-08-16 批准因 `P404-QUAL-001` BLOCKER、`P404-QUAL-002`/`-003` MAJOR 重开的历史保留于实施登记。

**决定：** P4-04 实施批（RL-03 转账 formalization 子切片，PHASE4_DESIGN_PACKAGE.local.md:77-83、WORK_PLAN.local.md:97-98）契约由 `docs/specs/2026-08-14-p4-04-transfer-formalization-slice-design.md`（Status: approved — 2026-08-17 重新批准）提出。提案条款：

1. **批界**：仅 RL-03 子切片。完整腿候选经明确确认形成平衡 asset transfer，外部收支与报表效应为零；缺腿候选保持 pending 的可解释候选，不猜测另一端、不提前创建正式转账；mirror/evidence-link（P4-08）、dedup（P4-07）、产品 ID/Clock（后续阶段）不在本批（PHASE4_DESIGN_PACKAGE.local.md:79-82）。
2. **类型范围**：恰为 D-099:1539 登记的转账/群收款/零钱提现/零钱充值四类，不新增接受类型；红包类 fail-closed（未分配批次）、退款类 fail-closed（→ P4-06）、未知 token fail-closed（SPINE_WEIXIN_UNKNOWN_TOKEN）。
3. **会计锚点与本批收窄**：一般 core 转账规则允许用户自己的资产或负债账户一对一互转（docs/ACCOUNTING_RULES.md:52-60），但 P4-04 只冻结 `createOwnAssetPrincipalTransfer` 支持的 self-owned real asset → self-owned real asset；负债腿不在本批，不能由 D-100 推断实现。手工转账两端由用户显式选择、导入信息不足 pending 不猜测（D-032:387）；来源已明确两端才生成完整草稿、另一端后到仅作补充证据（D-033:399；合并属 P4-08）；钱包充值 = 内部转账不计消费（docs/ACCOUNTING_RULES.md:164-168）；RG-09 主例转账零收入/费用/消费报告语义先例（docs/ACCOUNTING_RULES.md:227）；RL-03 anchor（CORE_ACCEPTANCE_PLAN `GL-A3CB7F3D48BC`）= 两条资产分录平衡、不进入对外收支。
4. **类型处置**：零钱提现（支出）/零钱充值（收入）= 钱包↔银行 self-transfer 候选（来源经方向证明零钱腿，另一端经显式确认补全）；转账/群收款 = 来源无第二自有腿 → 缺腿候选，本片确认门关闭，reject 可作人工处置终态。
5. **确认契约与强绑定**：扩展现有 ConfirmImportCandidate 端口为 decision-kind 判别确认请求（TransferFlow 携带 fromAccountId+toAccountId、无 category；OrdinaryFlow 字段不变）；单一 commitOnce 端口。`ImportRequestIdentity.ledgerId` 是 candidate decision/formalization 唯一 ledger 来源；application `ImportCandidateDecisionSnapshot` value object 不再重复携带 ledgerId，持久化 decision row 的 ledger_id 仍存在但只能取 identity。同一 immutable formalization input（identity ledger + persisted source facts + frozen decisionFields）同时供 callback、pre-persist binding validator 与 decision snapshot 使用，factory 不再捕获 legs。commit port 在 claim 获胜并完成 candidate/source 校验后单独调用 `allocateIds`，把该次实际分配的 `ImportCommitIds` 与同一 input 一并传给 factory；validator 在任何 formal INSERT 前以 `(input, allocatedIds, created)` 逐项绑定 confirmation/status/transaction/version/posting-set/两条 posting ID，以及 created transfer 的 ledger/kind/from/to/amount/currency/完整单版本 graph。callback 忽略获配 IDs、反转 legs、替换 ledger 或用另一 ledger identity 查找 candidate，均须零残留拒绝。等价重放按 decision/candidate/category/funding/from/to/confirmed_at 七项检查；方向门、领域 violation、formal graph mismatch 与 ID mismatch 各有独立向量。
6. **Schema v21→v22（21.sqm）**：扩展 `import_candidate_decision_snapshot`（+from_account_id/+to_account_id、XOR CHECK 重写）而非新表；record_kind/candidate_kind/contract_version CHECK 扩展；八张依赖表按 3.sqm 模板重建；fresh=migrated、reopen、冻结 rg03/rg04/rg08 竖井共存、八表 append-only 与 status_history transition 守卫逐项复核。late-stage failure oracle 必须在真实 migration 全部语句与 user_version=22 已位于同一 outer transaction、commit 前注入失败，证明关闭重开仍为完整 v21、无 stage/guard 残留、foreign_keys=1、foreign_key_check=0，且可重试成功；既有 `createOwnAssetPrincipalTransfer` 会计行为不变，ledger-domain 只允许增加通用的 `AmountNotRepresentableInCurrency` violation token。
7. **腿建模**：确认请求显式携带两端（from+to 用户选择）；方向与钱包腿一致（支出 → 钱包=from、收入 → 钱包=to），违约类型化拒绝；永不从交易对方文本推导任何腿（provider DTO/隐私/D-032）。
8. **手续费**：本金-only；手续费类行不在冻结集合 → 现有 fail-closed 路由（D-031 手续费为独立支出，本批不实现）。
9. **Kinds、normalized contract 版本与 source scale**：record_kind ∈ {`transfer_flow_source`, `transfer_flow_source_missing_leg`}、candidate_kind ∈ {`transfer_flow`, `transfer_flow_missing_leg`}（P4-02 命名纪律）；解析器由交易类型路由列派生 provider-neutral `ImportRecordKind`，`WechatRowResult.Accepted` 增加该字段。D-097 v1 ordinary 边界通过版本化扩展处理：ordinary kind 保持 contract_version=1（既有行不重写，新 ordinary 行也不升级），两个 transfer kind 固定 contract_version=2；kind→version 封闭派生且 DDL 配对 CHECK 拒绝错配。`currency_precision` 继续表示来源十进制文本 scale，只保存来源事实，绝不直接构造账户 `CurrencyUnit`；类型 token 不落盘（provider DTO 零引入）。
10. **方向未决**：raw token 保留 → valid_incomplete + REQUIRED_FACT_UNRESOLVED（D-097）；候选 incomplete、不可确认。
11. **confirmed_at**：仅显式确认事实（D-098:1509、D-081:1007）。
12. **兼容载体技术债**：本批为保持既有会计原语行为不变，方向门失败暂沿用 `DomainViolation.InvalidOrdinaryIncome`，但该名称不表达转账语义。实现返回点必须用注释声明兼容原因并引用规格 §11 第 7 项；下一次获批且允许扩展 transfer violation 的批次必须重新评估专用 violation，当前载体不得成为长期语义先例。
13. **P4-03 回归处置**：D-099 已登记的类型转移只修订三处既有冻结断言：W7/P-07 由拒行变 transfer v2 record；P-14 记录数 8→9、诊断 9→8；E-12 full-batch intake 由 8→9 个 record/candidate、拒行 6→5、诊断 9→8。P4-02 全部断言与 P4-03 其余断言逐值不变。
14. **Complete state oracle**：每个 E 操作及复合操作的 setup/failure/retry checkpoint 都比较独立构造的 complete canonical state：九张 spine 表全部行列、五张 formal chain 表全部行列、status history、完整余额与 internal transfer/external income/external expense/external cash inflow/external cash outflow/consumption/budget/category/net-worth report projection、operation result/receipt/returned IDs，以及由完整 pre/post state 独立求差得到的 14 表 canonical delta。正式 self-transfer 除 internal transfer 本金外，其余 report 维度全部为零；NoChange、Rejected、异常和并发输家必须与 pre-state 逐值相同，selected fields/counts 只能作辅助。
15. **精确金额归一化**：factory 先从显式 from/to 账户取得唯一目标 `CurrencyUnit`，要求两端币种完整相等且 source currency code 相同，再把 `(source amount_minor, source scale)` 精确换算为目标 precision。升 scale 使用 checked power-of-ten multiplication；降 scale 必须余数为零；禁止舍入。不可精确表示返回 `AmountNotRepresentableInCurrency`，乘法溢出返回 `ArithmeticOverflow`，均由 spine 映射为 SPINE_DOMAIN_VALIDATION_FAILED 并原子回滚；不得用 source scale 构造 `CurrencyUnit`。

**理由：** 转账两端的余额解释必须完整且显式（D-032），导入只能证明零钱腿；self-transfer 与缺腿的确认门区别冻结防止猜测入账；共享单一确认端口与单张决策快照保持 replay/冲突/失败注入证明面最小；复用既有转账原语与 RG-09 报告先例，但 source decimal scale、应用分配 ID 和完整 report projection 必须在 formal persistence 前独立绑定，不能由测试 fixture 的两位小数或可信 factory 假设代替合同。

**实施登记：** 未实施。2026-08-16 曾按当时记录的 spec closure P404-SPEC-001…017、quality 0 BLOCKER / 0 MAJOR（5 MINOR）、verifier V1–V5 与 A/A/A/A 裁决登记批准；后续质量复核确认 `P404-QUAL-001…003`，证明该批准证据不完整。轮 A 已重新打开，轮 B 实施授权暂停；历史批准记录不删除，但不得继续作为实现依据。2026-08-17：纯文档纠偏候选（提交 41c1a8d；冻结 SHA-256：README `87d5f35d…`、CURRENT_STATE `96773d19…`、DECISIONS `540b2015…`、spec `7bea76c4…`）经独立复审全 finding 闭合与 V1–V5 验证后由主代理重新批准（轮 2 拓扑 A/A/A/A）；轮 B 可按本契约启动。

**关联决定：** `D-030`、`D-031`、`D-032`、`D-033`、`D-073`、`D-077`、`D-081`、`D-092`、`D-096`、`D-097`、`D-098`、`D-099`

## D-101 P4-05 第二个标准来源与 parser 技术（支付宝 CSV 自研解析器）

**状态：** 已批准（2026-08-17 用户批准）。2026-08-18 纠正修订：表头 token、时间列索引与「9/9 无余额宝 token」负证据经字节级复核 9 份真实导出后纠正（详见下文「修订（2026-08-18）」与本决定对应规格 §9）。

**决定：** P4-05 实施批（第二个标准来源 = 支付宝个人交易明细导出 CSV）契约由 `docs/specs/2026-08-17-p4-05-alipay-ordinary-flows-design.md`（Status: approved）提出。用户决策：RL-04 转账切片选项 A（提供新导出含「余额转入余额宝」行）；表头 token 字节级独立重验批准。

**批界**：RL-04 维度的来源整合与普通收支 formalization 子切片。本批证明共享 spine 不是首个来源特化（PHASE4_DESIGN_PACKAGE.local.md:84-88）；RL-04 锚点 `GL-A6F5A461E605` 的转账语义切片不在本批（原负证据前提已于 2026-08-18 纠正修订撤回——2/9 真实文件含 `投资理财` + `余额宝-*` 行），RL-04 路由延后至后续独立批次（D-10x），登记为开放问题。

**parser 技术裁决**（D-099:1537 六维证据模板）：自研零依赖解析器（ledger-application jvm 源集）。拒绝 Commons CSV（Apache-2.0，jvm-only，但引入依赖）、FastCSV（MIT，活跃，零依赖，但引入依赖）、kotlin-csv（Apache-2.0，唯一 KMP 但 charset 仅 JVM）。理由：格式简单（13 字段，无引号/转义/多行），自研零依赖满足 OSV/GHSA 审计义务，与 ARCHITECTURE.md:152「自研在评估域内」一致。

**格式事实**（行为证据，D-099:1538 先例）：个人导出 = 加密 zip 内 CSV、单次最长 1 年分段；9/9 真实文件为 GBK/CP936 系（无 BOM）；前置 23 行元数据后表头固定在第 23 行（0-based）；13 字段 = 12 列 + 行尾逗号；无统计区、无引号/转义/多行字段；前导区 CRLF、表头+数据行 LF；交易时间 `YYYY-MM-DD HH:mm:ss`、金额 `-?\d+\.\d{2}`。

**行为证据补充**（本地参考项目，行为证据 only，不复制代码）：两个本地记账 app 的 Alipay 解析实现显示不同表头检测策略——一个使用固定列索引（时间列 0、金额列 6），另一个使用动态表头检测（查找含"交易时间"、"收/支"、"金额"的行）+ BOM 容错 + 数据区结束标记。本规格按 9/9 真实文件取证冻结（固定第 23 行表头），行为证据仅作格式演化参考，不影响冻结契约。**2026-08-18 纠正（详见修订 note）：** 此处「时间列 0、金额列 6」与真实布局一致，但原冻结规格误采时间列 4，与所引用行为证据自相矛盾——证明 D-101 证据门在批准时未对 9 份真实文件执行字节级复核；已纠正为时间列 0（金额列 6 不变）。

**RL-04 负证据**：~~9/9 真实文件无「余额转入余额宝」交易分类 token~~ **（2026-08-18 纠正：原断言为假，详见修订 note 与规格 §9.4）**。已验证事实：2/9 真实文件含 `投资理财` 交易分类 + `余额宝-*` 商品说明行（子类型 `余额宝-自动转入`、`余额宝-单次转入`、`余额宝-转出到余额`；收/支恒 `不计收支`）；`余额转入余额宝` 并非真实交易分类 token（真实分类为 `投资理财`）。`投资理财` fail-closed `SPINE_ALIPAY_UNKNOWN_TOKEN` 拒行不变；RL-04 路由延后至后续独立批次（D-10x）。原用户决策选项 A「等待含余额宝行的新导出」前提不成立（行已存在），需在纠正基础上重新定界。最近似 token（转账红包收入、账户存取不计收支）登记保留供 D-10x 参考。

**schema**：v22 不变；ledger-data/ledger-domain/ImportSpine.kt 零改动（纯附加批次，无跨规格冻结修订）。

**实施登记：** 已实施并发布（提交 985413a；`AlipaySourceTokens.kt`、`AlipayCsvParser.kt` + 合成测试）。**2026-08-18 缺陷发现（详见修订 note）：** 发布后字节级复核 9 份真实导出确认冻结表头 5 token 不字节匹配且含虚构列 `交易号`、时间列索引错误（4 应为 0）、parser 表头精确匹配在真实导出 index 0 即失配 → 整批 `STRUCTURE_MISMATCH`、零 record、无任何真实支付宝导出可通过；26 合成 parser 测试 + 5 E2E 测试通过仅因测试构建器复用同一错误 `HEADER_TOKENS` 常量。代码与测试纠正已于 2026-08-18 纠正实施批同批完成（独立 worktree、writer/reviewer/verifier 拓扑；§9.5(a)(b) 测试已实现）。

**修订（2026-08-18，corrective amendment to an approved decision）：** 本修订是对已批准（2026-08-17 用户批准）并发布（提交 985413a）D-101 的纠正修订；本修订分两阶段执行：文档纠正（规格 §9 + 本决定对应条款）先行冻结并经独立评审，代码/测试纠正随后经用户批准于同一纠正实施批完成（§9.5(a)(b) 测试已实现）。修订依据 = 主代理对 9 份真实用户导出（4「支付宝1」/3「支付宝2」/2「支付宝3」，仓库外）的字节级独立复核（GBK 解码、0-based line 23、13 字段）：

1. **表头 token 清单纠正**：原冻结清单 `交易号/交易分类/交易对方/商品名称/交易时间/收/支/金额(元)/收付款方式/交易状态/交易订单号/商家订单号/备注`（5 token 不字节匹配 + 虚构列 `交易号`）→ 真实规范表头 `交易时间/交易分类/交易对方/对方账号/商品说明/收/支/金额/收/付款方式/交易状态/交易订单号/商家订单号/备注`（9/9 真实导出逐字节一致；对应规格 §2.2、§9.2）。
2. **时间列索引纠正**：原 `fields[4]` → 真实 `fields[0]`（金额 `fields[6]`、分类 `fields[1]`、方向 `fields[5]`、状态 `fields[8]`、单号 `fields[9]/[10]` 索引不变；唯一必需变更 = 时间；对应规格 §2.4、§9.2）。此纠正与「行为证据补充」记录的本地参考解析器「时间列 0、金额列 6」一致——原冻结规格与所引用行为证据自相矛盾，证明证据门在批准时未对 9 份真实文件执行字节级复核。
3. **RL-04 负证据纠正**：原「9/9 无余额宝 token」为假 → 2/9 真实文件含 `投资理财` + `余额宝-*` 行（子类型 `余额宝-自动转入`/`余额宝-单次转入`/`余额宝-转出到余额`，收/支恒 `不计收支`）；`投资理财` fail-closed UNKNOWN 路由不变，RL-04 路由延后至 D-10x（对应规格 §3.4、§9.4）。
4. **根本程序缺口**：无任何自动化测试对冻结表头与真实导出做字节级比对（测试与 parser 共享同一错误常量 → 自我一致闭环）；纠正实施批已以合成字节级表头测试 + 合成真实布局数据行测试 + 文档化手工真实文件 diff 程序补上（§9.5(a)(b) 已实现、(c) 已文档化；对应规格 §9.3、§9.5）。

**关联决定：** `D-097`、`D-098`、`D-099`、`D-100`、`D-092`

## D-102 RL-04 余额宝转账路由 契约

**状态：** 已批准（2026-08-18 用户批准）。

**决定：** RL-04 余额宝转账路由批（D-10x）契约由 `docs/specs/2026-08-18-p4-05b-rl04-yuebao-transfer-routing-design.md`（Status: approved，2026-08-18 用户批准）提出。提案条款：

1. **批界**：仅支付宝 `投资理财` 交易分类 + `余额宝-*` 商品说明（列 4）子类型判别 → P4-04 转账语义路由（`ImportRecordKind.TRANSFER_FLOW_SOURCE`、`ImportConfirmDecisionFields.TransferFlow` 确认契约、`TransferFlowFormalFactory`、ACCOUNT_TRANSFER 方向门——全部已在 main 实现，本批零新增域原语、零 schema/spine 改动、零新诊断码）。证明 spine 非首来源特化（PHASE4_DESIGN_PACKAGE.local.md:84-87）+ RL-04 一对一转账/formalization 子切片（WORK_PLAN.local.md:123）；mirror/evidence-link 与 posting reconciliation 留 P4-08。
2. **路由矩阵（冻结）**：`余额宝-自动转入`（交易成功，7/9 样本）→ TRANSFER_FLOW_SOURCE、方向 out（余额→余额宝，wallet=from）；`余额宝-转出到余额`（交易成功，1/9 样本）→ TRANSFER_FLOW_SOURCE、方向 in（余额宝→余额，wallet=to）；`余额宝-单次转入`（唯一样本 交易关闭，无成功样本）→ **本批不冻结路由**（fail-closed `SPINE_ALIPAY_UNKNOWN_TOKEN` 拒行），登记待真实成功样本；`余额宝-转出到银行卡`（无样本）→ 不冻结（UNKNOWN 拒行），登记缺腿；`余额宝-收益发放`（无样本）→ 不冻结，**硬性负向登记为收入（RL-05），禁止路由到 TransferFlow**。状态门：路由要求 交易状态=`交易成功`；冻结子类型 + 非成功状态 → status raw 保留 + unresolved → valid_incomplete（A-05 先例），不可确认 ⇒ 零正式分录。
3. **判定顺序**：退款（不变）→ 余额宝路由分支（子类型 ∈ 冻结集合 + 状态门）→ 拒绝集合（不变）→ 未知 token（不变）→ 事实映射（不变）；`投资理财` 由 P4-05 的 UNKNOWN 移入冻结转账族，其余 fail-closed 语义不变。该修订登记为 P4-05 §3.2/§3.4 与 D-101 条款的跨规格登记修订（P4-05 oracle 无 `投资理财` fixture 行 ⇒ 零 oracle 影响，与 P4-04 对 P4-03 的三处修订形成对照）。
4. **方向语义**：子类型 → 方向（自动转入→out / 转出到余额→in）；收/支列（取证恒 `不计收支`）不参与方向判定（P4-04 微信 self-transfer token 族 → 方向先例）；方向事实来源 = 冻结子类型映射，provenance rule `yuebao_subtype_direction_v1`、confidence exact（D-097:1455 分层）。
5. **账户建模**：余额宝为组合账户下的独立资产账户（golden 锚点语义）；确认契约 `TransferFlow(fromAccountId, toAccountId)` 双账户显式提供 ⇒ parser/spine 不物化余额宝账户 ID；方向门以 walletAccountId = 支付宝余额 账户装配（应用层注入，P4-04 §4.3 先例）。
6. **复用与隐私**：金额/时间/币种/精度复用 P4-05 已冻结列映射（时间 `fields[0]`、金额 `fields[6]`、CNY、精度 2）；收/付款方式列不解析（P4-05 §3.4 冻结维持），`账户余额`/`余额` 通用 token 仅行为证据、不进持久化；商品说明列仅对 `投资理财` 行做冻结子类型精确匹配、任何值不落盘（provider DTO 零引入）。
7. **零新增诊断码**：解析级复用 P4-05（SPINE_ALIPAY_UNKNOWN_TOKEN/REFUND_UNSUPPORTED/UNSUPPORTED_TX_TYPE、REQUIRED_FACT_UNRESOLVED 等）；spine 级复用 P4-04（SPINE_DOMAIN_VALIDATION_FAILED 方向门、SPINE_CANDIDATE_INCOMPLETE、SPINE_DECISION_KIND_MISMATCH、SPINE_TRANSFER_NOT_CONFIRMABLE 不触发等）。

**理由：** 证据链：主代理字节级复核 9 份真实导出，9 行余额宝行全部 交易分类=`投资理财`、收/支=`不计收支`（`余额宝-自动转入` ×7 全部 `交易成功`、`余额宝-单次转入` ×1 `交易关闭`、`余额宝-转出到余额` ×1 `交易成功`）；9/9 行收/付款方式无银行卡 ⇒ 全部两腿（余额↔余额宝），无缺腿样本。golden 锚点 `GL-A6F5A461E605`（`.external/requirements/golden-ledger/golden_expected_fund_movements.csv:94-95`）为来源账户 −X → 目标账户 +X 平衡两腿（组合账户展示下 支付宝余额 与 余额宝 为两个独立资产账户）；CORE_ACCEPTANCE_PLAN.md:63 验收点 = 两条资产分录平衡、组合账户展示、分账户对账。负向登记：`余额宝-收益发放` 为收入（RL-05）、`余额宝-转出到银行卡` 为缺腿，均禁止本批路由，fail-closed（真实数据无此两类样本，社区共识项非 9 文件取证）。金额/时间见 .external 只读 fixture 注册值，不复制进 tracked 文件（P405FIX-QUAL-001 隐私先例）。

**实施登记：** 已实施（2026-08-18）。2026-08-18 经用户批准后在独立 worktree `feat/rl04-yuebao-transfer` 落地：`AlipaySourceTokens.kt`（冻结子类型集合/方向映射/rule 常量 + 负向登记）、`AlipayCsvParser.kt`（判定顺序第 2 步 `parseInvestmentRow`，单一事实源 `YUEBAO_TRANSFER_SUBTYPES` + 方向 `getValue` fail-fast）、RL-04 解析级测试 17 条（Y-01…Y-15/P-01…P-18/T-01…T-22 + 畸形字段防御）+ E2E 9 条（E-01…E-12/R-02）全绿；P4-05 既有 28+5 保持绿（R-01）。经独立 spec 评审（3 findings 闭合）、独立 quality 评审（2 MINOR 闭合）、distinct verification（8/8 PASS）、主代理全量回归后闭环。主代理负责 Git 写操作与合并推送。

**关联决定：** `D-092`、`D-097`、`D-098`、`D-099`、`D-100`、`D-101`

## D-103 P4-08 matcher 契约 前置门

**状态：** 已批准（2026-08-19 用户批准 O-1…O-6 全部方案 A）；本决定批准 matcher 契约，不授权实施代码。

**决定：** P4-08 matcher 契约前置门（WORK_PLAN.local.md:110 五项 = matcher fields、time window、ambiguity model、scenario cardinality、RL-07 acceptance matrix）由 `docs/specs/2026-08-19-p4-08-matcher-contract-design.md`（Status: approved，2026-08-19 用户批准）提出并固化。批准条款如下：

1. **门定义**：本批对照 WORK_PLAN:110 五项与 D-096:1429/1435，产生可裁决的中立契约文本（spec）；批准后 spec 转为 approved，作为 P4-08 实施批的 matcher 契约。备选方案与权衡保留为决策审计记录。
2. **现状基线**（spec §1，证据侦察）：产品路径零 matcher/evidence-link/posting-reconciliation 写入——import_evidence 无 UNIQUE(source_id)（P4-02 spec:187）、import_candidate 保留 UNIQUE(ledger_id,source_id)（:188、多候选走加性迁移）；evidence 唯一消费点 = SqlDelightImportSpineStore.kt:289/456/617（引用完整性校验与 intake 返回 ID，非匹配语义）；report 九维度不含 reconciliation 维度（P4-04 spec:138）；schema 当前 v22。竖井表 rg03_evidence_link（Ledger.sq:267-273）/rg04_import_evidence_match（:548-554）均 1:1，只作冻结语料与行为证据先例（D-096:1431），不构成产品表形状先例（D-092:1329/1335；D-098:1493 纪律）。
3. **已知约束摘要**（spec §2 九条，逐条带出处）：Posting 级对账与四要素（外部证据/证据职责/匹配依据/人工决定）；实际资金时间 = 主要时间锚且运行时 Clock 不补写来源时间；不建立全局 1:1（场景合同决定）；业务相似指纹（D-095 十键）与通道总额只作候选/诊断、不能满足 RL-07 逐记录验收；排他性冲突类型化拒绝零写入、后到镜像只追加 lineage 不重复 link/effect 不建第二笔交易；资料不足允许待补/部分核对、可补充后重匹配、不得自动补平；金额精确十进制 + source scale、时间按 source token/kind/components/offset、缺 offset 保持 unresolved；P4-03/04/05（含 RL-04 路由）零 evidence-link/reconciliation、状态变更语义首现于 P4-08。
4. **O-1..O-6 已批准裁决（全部 A）**：O-1 资金事实核心门，必选 `amount`（精确十进制及 source scale）、`currency`、`direction`、真实 `account`；`occurred_at` 参加时间窗；`status` 仅可选诊断/置信度；`order_id`/`counterparty`/`category`/`item` 不参与身份。O-2 有界自然日窗，以 posting 实际资金时间为锚，首版固定默认 **±2 个自然日**，允许账户/来源配置能力；来源级结算偏移不在本批准组合中启用。O-3 同窗多命中默认 defer 人工，保持 `待补资料`/`有差异` 上界，零 link、零 reconciliation effect。O-4 场景显式登记基数：RL-07 evidence:posting 各 1:1、evidence:transaction 多对一；RL-03/04 同源 1:1；拆单/混合支付 1:N 由场景合同显式登记；不设全局默认基数。O-5 RL-07 逐记录验收，通道总额仅诊断。O-6 P4-08 引入最小加性 reconciliation 面（v22→v23、非 `rgXX_` 共享表）并纳入 report reconciliation 维度与 canonical oracle；产品状态枚举固定为 `待对账`/`部分匹配`/`有差异`/`待补资料`/`已核对`，具体列形状和迁移 SQL留实施批规格登记。任何 matcher 实现默认值不得超出本登记（D-096:1435）。
5. **验收锚点**：首版 RL-07 锚点 = `GL-0DCF5FCDB9BA`（银行流水与平台侧镜像证据、只形成一笔正式转账并合并证据，GOLDEN_TESTS.md:176；CORE_ACCEPTANCE_PLAN:66/31/91）；真实注册金额/时间只在 .external 只读 fixture，不复制进 tracked 文件（P405FIX-QUAL-001 隐私先例）。

**理由：** 证据链：GOLDEN_TESTS.md:172/:176 与 CORE_ACCEPTANCE_PLAN:63-67/:66/:31/:91 确立 RL-07 验收锚点与对账状态断言体系；ACCOUNTING_RULES.md:239-253 对账专章确立 Posting 级、状态枚举、部分核对、四要素与补充重匹配语义（另 :202 实际资金时间主锚、:233 fully_reconciled）；D-096:1425（通道总额只诊断、不能满足 RL-07 逐记录镜像验收）、:1427（一 posting/一 evidence 基数由场景合同决定、不建全局 1:1）、:1429（matcher 字段/时间窗/歧义模型/基数须备选方案+匿名验收后批准）、:1435（未决不能由实现默认值冻结）与 IMPORT-002 暂停项（WORK_PLAN.local.md:61-65「any default matcher cardinality 被否决、matcher 语义是 P4-08 gate」）共同把 matcher 语义锁定为 P4-08 前置门；产品路径现状侦察（spec §1.1：零 matcher/evidence-link/reconciliation、状态语义首现 P4-08，WORK_PLAN:129）证明本批只冻结契约、不实施。

**实施登记：** 已实施并合入、推送 main（实施提交 `406fb8d`/`0f03ce8`/`0ca8688`/`875e011`，merge `fd57808`，2026-08-19）：matcher proposal/runtime 与最小加性 reconciliation 面（v22→v23：evidence link、posting reconciliation 及 append-only history）、confirm 链路 claim-first replay/conflict 与零写入拒绝、report reconciliation 维度、canonical 与 migrated-v23 测试覆盖。correction/successor invalidation 按实施规格明确延期，P4-08 不因此视为全量闭环。

**关联决定：** `D-085`、`D-092`、`D-094`、`D-095`、`D-096`、`D-097`、`D-098`、`D-099`、`D-100`、`D-101`、`D-102`

## D-104 P4-07 重复候选与关闭记录契约

**状态：** 已批准（2026-08-19 用户批准）；不授权实施代码。

**决定提案：** 由 `docs/specs/2026-08-19-p4-07-duplicate-closed-records-design.md` 冻结 RL-08 的 duplicate candidate 数据合同、身份边界、同批次/跨批次行为、合法相似记录、关闭/失败记录、碰撞/歧义矩阵及匿名验收锚点。候选必须保存稳定引用、候选类型、provenance、confidence、rule/version、比较快照和人工处置历史；候选永远不能替代 raw identity，不能破坏性删除或折叠来源。

同 request 或等价 raw identity replay 按既有合同返回稳定结果并零写入；不同 raw identity 的相似记录只生成候选，默认待人工处置；合法 lookalike 必须允许明确区分并保留两条来源。关闭、失败或状态未知记录若无可靠资金变化事实，正式资金分录数为零；退款、冲回和修正仍是独立经济事件。碰撞 hard reject，歧义 defer，后到重复来源仅追加 lineage/candidate，不创建第二笔交易、分录、余额、报表或对账效果。通道总额只作诊断。

**理由：** 对齐 D-096 对 duplicate contract 的前置要求、D-098 raw identity/collision 已批准边界、ACCOUNTING_RULES.md 的来源/候选/正式账目分层与零副作用规则，以及 PRODUCT_REQUIREMENTS.md/GOLDEN_TESTS.md 的 RL-08 验收要求。该提案避免把业务指纹误当身份，也避免把关闭状态误当退款或成功。

**实施登记：** 已实施并合入 main（2026-08-22，merge `e1ab7d4`；候选 `a4cb795`）：D-105 实施批交付 duplicate candidate 与 append-only 处置历史、专属 review claim/replay、`CONFIRMED_DUPLICATE` formalization 阻断（`SPINE_DUPLICATE_NOT_CONFIRMABLE`）、`CLOSED_OR_FAILED_NO_FUNDS` 零经济效果、v23→v24 加性迁移（`23.sqm`：重建 `import_source_record` 保留全部后代 + 5 张 duplicate 表与状态机/所有权/receipt 一致性触发器）及 `P407DuplicateClosedFullStateOracleTest` canonical full-state oracle。经独立规格增量复审（6 findings 修复后 CLOSURE APPROVE）、独立质量复审、全量 `:ledger-data:jvmTest`、Python 全套 806 tests、迁移 verifier、Android 编译与独立 verifier 6/6 后验收合并。

**关联决定：** `D-073`、`D-092`、`D-096`、`D-097`、`D-098`、`D-103`

## D-105 P4-07 实施授权

**状态：** 已批准（2026-08-19 用户批准）。

**决定：** 授权按照 `docs/specs/2026-08-19-p4-07-duplicate-closed-records-implementation-design.md`（Status: approved）实施 D-104 的 P4-07 边界：非破坏性 duplicate candidate、专属 review claim/replay、`CONFIRMED_DUPLICATE` formalization 阻断、显式 `NO_FUNDS` 零经济效果，以及 v23 -> v24 加性持久化与匿名 RL-08 验证。

**边界：** 本授权不改变 D-104 已批准的产品合同，不授权 P4-06、P4-08 matcher/evidence-link/reconciliation 写入、provider status token 映射、产品 Clock/ID 算法或整文件保留。

**实施门：** 实现须保持 source/candidate/formal 分层、原子 claim/replay 和迁移事务性，并完成独立规格、质量与验证路径后才可接受。

**实施登记：** 已实施并合入 main（2026-08-22，merge `e1ab7d4`）。书面偏差登记（闭环复审 OBS-001，行为惰性已证明）：现有 WeChat/Alipay parser 对 `VALID_INCOMPLETE` 行中继 `SETTLED`/`legacy-settled-v1`（与实施前默认行为一致）；该状态不进入任何 funding 门控路径，可观察行为与 `UNRESOLVED` 等价。provider token 的 funding 映射仍按本决定边界禁止，待来源契约修订后独立批准。

**关联决定：** `D-104`、`D-098`、`D-103`

## D-106 P4-06 信用与混合支付契约

**状态：** 已批准（2026-08-22 用户批准 O-1..O-8 全部方案 A）；本决定批准契约，不授权实施代码。

**决定提案：** 由 `docs/specs/2026-08-22-p4-06-credit-mixed-payment-contract-design.md`（Status: approved，2026-08-22 用户批准 O-1..O-8；独立规格评审 findings P406-SPEC-001..007 已修复闭合）冻结 RL-05 信用生命周期与 RL-06 混合支付的核心语义契约：收/付款方式列白名单解冻（仅提取支付腿种类 token，掩码账号/尾号不提取不持久化，白名单+UNKNOWN fail-closed，fixture 全合成）；恰三个新 kind 固定 contract_version=3（信用消费含退款变体、信用还款、混合支付，kind→version 封闭派生）；信用负债账户必须先由用户显式持有、不得猜测或自动建户、共享负债属迁移配置；三腿生命周期（消费 费用+/负债余额转负零现金流出、还款独立 `CREDIT_REPAYMENT` 资产−/负债+、退款独立经济事件 费用−/负债冲减并关联原交易、利息/手续费/逾期费 future_rule）；混合支付候选默认 `pending_confirmation`、缺腿保持资料不足不补平、`constraint_solved` 反推只作人工确认输入建议、禁止基线与调整重复计入；evidence 基数一行来源 ↔ 一笔交易、混合支付交易内 1 evidence:N postings；全部持久化恰一次 v24→v25 加性迁移，复用 RG-04/RG-07 合同语义而不复制 `rg04_*`/`rg07_*` 竖井 owner 表。

**O-1..O-8 已批准裁决（2026-08-22，全部方案 A）：**

- **O-1：** 契约一次冻结；实施分两片（RL-05 信用先、RL-06 混合后），各需独立实施授权。
- **O-2：** 收/付款方式列白名单解冻——仅提取支付腿种类 token（花呗、余额宝、银行卡机构类等冻结集合），掩码账号/尾号不提取不持久化；腿种类集合按开放域原则冻结（封闭枚举失效、白名单+UNKNOWN fail-closed）；fixture 全合成。
- **O-3：** spine 显式 contract_version=3 新增最小 kind 集：信用消费、信用还款、混合支付三 kind；具体列形状/诊断码留实施规格，本契约冻结核语义。
- **O-4：** 信用负债账户必须先由用户显式持有（候选确认时指定）；不得猜测/自动建户；共享负债按 `D-053` 属迁移配置。
- **O-5：** 三腿生命周期——消费=费用+/负债余额转负即欠款增加（如无资产腿则零现金流出）；还款=本金两腿 资产−/负债+，独立 `CREDIT_REPAYMENT` 交易类型（禁 `ACCOUNT_TRANSFER` 代）；退款=独立经济事件冲减负债（费用−、欠款减少）并关联原交易；利息/手续费/逾期费登记 future_rule。
- **O-6：** 混合支付候选默认 `pending_confirmation`；腿金额完整方可确认多腿分录；缺腿保持资料不足，不猜测不补平；`constraint_solved` 反推只作为人工确认的输入建议（带 provenance/confidence），不自动入账；禁止基线与调整重复计入（同一经济效果只允许一套最终分录）。
- **O-7：** evidence 基数登记（`D-103` O-4 首个场景）：一行来源 evidence ↔ 一笔交易；交易内混合支付 1 evidence:N postings（资产腿+负债腿）；信用还款/退款行各自 1:1。
- **O-8：** 实施将用 v24→v25 单个加性迁移（多腿 decision snapshot、信用/混合 candidate、`mixed_payment` 关联组产品表）；复用 RG-04/RG-07 合同语义、不复制 `rg04_*`/`rg07_*` owner；列形状留实施规格。

**理由：** 证据链：GOLDEN_TESTS.md:174-175（RL-05/RL-06 定义）、:76-84（RG-04 混合支付与信用本金还款语义）、:106-114（RG-07 退款语义）；ACCOUNTING_RULES.md:42（信用账户支付=费用增加且负债增加）、:98-108（混合支付专章）、:104（还款两腿、现金流时点与不重复消费）、:196（分摊与分期不得混用）；D-072（`CREDIT_REPAYMENT` 交易类型、禁 `ACCOUNT_TRANSFER` 代、混合/还款分录方向与费用分录不对账）、D-073（缺腿候选不猜测不补平）、D-078（RG-07 退款关联组与合同语义）、D-097:1449（unknown token 保留 raw 不映射、v1 合同宽度与版本化扩展纪律）、D-100 第 9 条（transfer v2 kind/contract_version 配对 CHECK 先例）、D-103 O-4（不设全局默认基数、场景显式登记）；现状 `AlipaySourceTokens.kt:48-51`（信用借还现类型化拒行，登记 → P4-06）与 P4-05 规格 §2.4 列 7「不持久化、不解析」冻结、§3.1 组合 token 取证（`&` 连接 + 机构名+掩码尾号模式）、§3.2 信用借还 → P4-06 登记；外部验收计划（CORE_ACCEPTANCE_PLAN.md，外部只读需求树）:64-65 的 RL-05/RL-06 验收点（补费用/付款资产/退款关联分录、验证负债方向与共享负债账户边界、占位释放 → 最终有效分录+独立推断证据）由本契约承接。

**边界：** 排除项按 spec §8 登记：分期付款（多期未来扣款）future_rule（`D-049`；分摊边界按 ACCOUNTING_RULES.md:196 区分）；微信侧负证据登记与「信用卡还款」形态 future_rule；拆单支付（商家订单号 1:N）与亲友代付=未分配批次（`D-040` 关联组框架覆盖，无锚点）；不实现 P4-08 matcher 写入之外的对账新语义（`D-103` 边界维持）；不引入产品 Clock/随机 ID；利息/手续费/逾期费分录 future_rule；仅资产腿退款与信用借还族其余形态维持类型化拒行（未分配批次）。

**实施登记：** 分片推进中：片 1（RL-05 信用）已按 D-107 实施并合入 main（2026-08-22，merge `7cf9b79`；明细见 D-107 实施登记）；片 2（RL-06 混合支付激活）未实施，需独立实施授权与规格（契约冻结的混合结构建而不用，fail-closed）。

**评审闭合：** 2026-08-22 独立规格评审 APPROVE with findings（P406-SPEC-001..008），全部已修复于本提交。

**关联决定：** `D-008`、`D-011`、`D-032`、`D-040`、`D-049`、`D-053`、`D-058`、`D-072`、`D-073`、`D-078`、`D-096`、`D-097`、`D-100`、`D-102`、`D-103`、`D-104`、`D-105`

## D-107 P4-06 片 1（RL-05 信用）实施授权

**状态：** 已批准（2026-08-22 用户常设授权 + 契约 D-106；本段授权片 1 实施）。

**决定：** 授权按照 `docs/specs/2026-08-22-p4-06-slice1-credit-implementation-design.md`（Status: approved，2026-08-22 用户常设授权 + 契约 D-106；独立评审 findings P406S1-SPEC-001..009 已修复闭合）实施 D-106 的片 1（RL-05 信用）边界：收/付款方式列白名单解冻与信用腿路由（含首批冻结白名单、三类括注剥离、判定顺序与 `SPINE_ALIPAY_UNKNOWN_PAYMENT_LEG`/`SPINE_ALIPAY_MIXED_PAYMENT_UNSUPPORTED`/`SPINE_ALIPAY_CREDIT_INCOME_UNSUPPORTED` 诊断码）、恰三个 contract_version=3 kind（`credit_expense_source` 含退款变体、`credit_repayment_source`、`mixed_payment_source`）的候选与确认、信用负债账户显式持有前置、三腿生命周期中的信用消费/还款/退款变体正式化、evidence 基数场景登记（§5）、匿名 RL-05 锚点验收，以及承载契约 §6 冻结的完整 v24→v25 单个加性迁移（多腿 decision snapshot 扩展、信用/混合 candidate、`mixed_payment` 关联组产品表；混合结构建而不用，片 2 零 schema 变更）。

**边界：** 本授权不含片 2 混合行为激活（混合腿行维持类型化拒行，fail-closed）；不含营销腿/非资金标注腿剥离语义（含此类腿的行拒行并登记已知限制）；不含 provider token 映射扩展（ordinary 状态映射子集不动，族内映射不外溢）；不改变 D-097/D-100/D-104/D-105 已批准行为，不写 P4-08 evidence link/reconciliation，不引入产品 Clock/随机 ID、默认或共享负债账户映射、微信侧信用路由。

**实施门：** 实现须保持 source/candidate/formal 分层、原子 claim/replay 与迁移事务性（append-only 守卫触发器、fresh = migrated、funding 列不回写）；领域扩展纯加性（新增 `createCreditExpense`/`createCreditRefundReceipt` 与 posting role，不修改既有冻结函数）；全部 fixture 全合成且不落盘掩码尾号/括注原文；按规格 §8 顺序完成独立规格评审、质量评审、distinct verifier 与完整受影响套件后方可接受。

**评审闭合：** 2026-08-22 独立规格评审 findings P406S1-SPEC-001..009（3 MAJOR——其中 SPEC-001 为数据填充修订类、2 MINOR、3 LOW、1 INFO）经主代理逐项裁决后全部修复，spec Status 转 approved；闭环终审 CLOSURE APPROVE。

**实施登记：** 已实施并合入 main（2026-08-22，merge `7cf9b79`；实施候选 `9435b8f`，28 文件 +3021/-111；评审补测 `d5442f5`，+161/-9 仅该测试文件；规格/评审链提交 `bfe1be0`→`33b049e`→`075741f`）：交付收/付款方式列白名单解冻与信用腿路由、三类 contract_version=3 kind（`credit_expense_source` 含退款变体、`credit_repayment_source`、`mixed_payment_source`）候选/确认生命周期、v24→v25 单个加性迁移 `24.sqm` 及 `P406CreditFullStateOracleTest` canonical full-state oracle。实施双评审（暂停前完成）规格增量+质量均 APPROVE with findings：P406S1-SPEC-010..021 全 LOW 登记接受；QUAL-001（Medium，v3 失败注入回滚未测）与 QUAL-002（Medium，P4-08 空表断言偏弱）由补测 `d5442f5` 闭环——两个失败注入回滚+重试 oracle 测试（注入点 `INTAKE_AFTER_CANDIDATE` 与 `CONFIRM_AFTER_FORMAL`，与产品事务边界一致，重试接受+全状态比对）、assertFullState 显式 evidence_link/posting_reconciliation 空断言与 P4-08 所有权边界注释、`Expected.confirm` 的 requestPrefix/candidatePrefix 命名空间分离（candidate id 由接入批次生成，status-2/confirmation/tx id 由提交批次生成，默认参数保持既有调用点逐值不变）。补测过程缺陷已修复入 `d5442f5`、登记不隐瞒：4 个既有调用点曾漏适配新签名（编译失败）；请求级 token 曾误改为 `confirm`（已恢复 `confirm_candidate`，与产品 store :315、schema CHECK Ledger.sq:7385、P407 oracle :550 一致）。闭环 delta 评审（2026-08-22，独立 reviewer）APPROVE with findings，4 条全 Low 登记接受、不改代码：P406S1-CLO-001（intake 注入测试中 hashExpense 声明未用）、CLO-002（intake 注入注释称 receipt 回滚，实际注入点在 receipt 插入前，证据核心应为 claim 行回滚）、CLO-003（显式空断言缺第三条 reconciliation_request，强度等价但与澄清意图不对称）、CLO-004（登记性：本 oracle 仅读 P4-08 7 表中 3 表，全表覆盖归 P408 canonical oracle；片 1 产品零 P4-08 写入，风险纯理论）。验证（冻结候选 `d5442f5`）：聚焦 `P406CreditFullStateOracleTest` 7/7（writer 跑、verifier 在全量套件内实际执行、主代理复跑，三轮）；全量 `:ledger-data:jvmTest` 实际执行 28m2s，56 类 374/374，0 失败/0 错误/0 跳过。片 2（混合支付激活，RL-06）未开始；D-106 契约中混合结构建而不用（fail-closed）维持。

**关联决定：** `D-106`（主）、`D-072`、`D-073`、`D-078`、`D-097`、`D-100`、`D-102`、`D-104`、`D-105`
