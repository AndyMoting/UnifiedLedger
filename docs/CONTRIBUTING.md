# 开发规范

## 当前环境

- Git
- Python 3.12
- PowerShell 7

Android 与 Desktop 工程尚未建立。首次引入 Gradle Wrapper 时，必须在同一提交中补充构建、测试和运行命令。

## 完整测试

从仓库根目录执行：

```powershell
$env:PYTHONPATH="tools\python"
python -m unittest discover -s tests -t . -v
```

## 文档验证

```powershell
$env:PYTHONPATH="tools\python"
python -m project_docs .
```

## 文档规则

- 正式文档以中文为主，代码类型、文件名、命令和 API 名称保留英文。
- 确认后的产品行为、账务规则和架构变化必须同步更新对应文档。
- `CURRENT_STATE.md` 只保留当前检查点、阻塞和唯一下一步。
- 文档不得包含本机绝对路径、个人账务数据或临时讨论记录。

## 分支与提交

- `main` 始终保持测试通过。
- 重要功能和跨文件迁移使用短期任务分支。
- 一个提交只表达一个可独立理解的逻辑变化。
- 代码行为变化时，实现、测试和必要文档在同一工作项中更新。
- 重要分支默认通过 merge commit 合入，合并后删除任务分支。

## 提交前检查

1. 运行聚焦测试和完整测试。
2. 运行正式文档验证。
3. 检查暂存 diff 和工作树状态。
4. 确认没有个人数据、本机信息、外部实现或开发过程署名。
