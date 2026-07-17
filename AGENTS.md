# Agent 工作协议

本文件适用于仓库内所有人类成员和 AI Agent。任何更深目录中的 `AGENTS.md` 可以补充局部规则，但不得放宽本文件的质量、安全和人工审批门禁。

## 强制启动步骤

开始任何任务前必须：

1. 阅读触发任务的 Multica Issue、父 Issue、相关评论、已确认需求版本和关联 PR。
2. 阅读 `docs/workflow/README.md`、当前阶段规范及 `docs/templates/README.md`。
3. 检查仓库现状和已有改动，保留不属于当前任务的用户修改。
4. 确认当前 Issue 的阶段、输入、输出、验收标准、依赖与人工门禁。
5. 若缺少已确认的需求 commit、必要权限、仓库上下文或人工批准，停止有副作用的操作并在 Issue 中说明阻塞。

## 通用工作规则

- 一个任务必须有一个主 Multica Issue；子任务使用父子 Issue 和 stage 表达依赖。
- `backlog` 是停车场。未满足前置门禁的后续任务必须保持 `backlog`。
- 需求、开发、评审、QA、UAT、发布与发布后验证不得相互替代。
- Agent 不得代替用户确认需求、选择评审建议、批准 UAT 或批准生产发布。
- 所有文档必须使用对应模板；复制模板到目标目录后再填写，不得直接修改模板源文件来交付某个需求。
- 高风险、不可逆、生产数据、权限、Secret、迁移和回滚操作必须获得明确人工批准。
- Secret 只能存在于批准的 Secret/CI 系统，不得写入代码、日志、Issue、PR 或文档。
- CI/CD 尚未接入。不得声称已自动构建、部署或验证；必须记录实际执行方式和证据。

## 门禁执行责任

- 当前交付 Issue 的 assignee 是该 Issue 的门禁执行者。到达关键人工门禁时必须主动发布预填充门禁卡，用户只需回复一行批准或拒绝口令；收到有效口令后必须执行授权动作，不能只记录结论。
- 父 Issue 的 leader 是阶段推进者。收到 stage barrier 完成通知后，必须检查全部子 Issue，再把最低一个未完成 stage 中满足依赖的 `backlog` Issue 推进到 `todo`。
- 门禁执行者与阶段推进者职责分离：前者合并当前交付、核验并关闭当前 Issue；后者只在整个 stage 终态后推进下一 stage。
- 用户只确认三个关键节点：最终需求基线、开发 PR 最终合并、QA/UAT 后生产发布。PRD、原型等中间需求 PR 由产品负责人内部评审后合并，不重复打扰用户。
- 没有与当前门禁卡匹配的 `批准 GATE-...` 口令时不得执行关键门禁；没有明确版本、结论或授权动作时不得推断用户意图。
- 合并失败、head SHA 变化、PR 不可合并或检查不满足时，不得把 Issue 标为 `done`，应保留 `in_review` 或标为 `blocked` 并报告具体原因。
- 高风险数据库迁移、生产数据变更、不可逆操作和回滚不适用简化口令，必须保留完整批准范围、版本、窗口和回滚信息。

## 固定需求目录

每个需求使用以下结构，其中 `<ISSUE-KEY>` 示例为 `RUO-123`：

```text
docs/requirements/<ISSUE-KEY>/
├── README.md
├── prd.md
├── acceptance-criteria.md
├── decisions/
├── prototype/
│   ├── README.md
│   ├── assets/
│   └── screenshots/          # 可选，缺少浏览器能力时不阻塞交付
├── development/
├── qa/
└── release/
```

可运行的 HTML/CSS/JavaScript 原型是原型交付的事实来源。README 必须提供运行方式、入口、状态覆盖和验收标准映射；截图仅用于 PR 快速预览或版本快照，不得因运行环境没有浏览器能力而阻塞原型进入内部评审。未执行的视觉或交互检查必须如实记录为风险。

## Git 与 PR

- 默认分支为 `main`，禁止未经明确授权直接提交到 `main`。
- 分支建议：`agent/<issue-key>-<short-description>`。
- PR 必须使用 `.github/pull_request_template.md`。
- PR 必须记录原始需求、确认 commit、关联 Issue、验收标准映射、测试、数据库/基础设施影响、风险和回滚。
- 只对本次 PR 合并后确实应该完成的当前交付 Issue 使用 `Closes RUO-123`；其他关联项写入关联表。需求、原型、开发等阶段 PR 都可以关闭各自的阶段 Issue，但不得顺带关闭父需求。
- 修改评审意见期间将 PR 置为 Draft；完成修改并自检后标记 Ready，触发新一轮评审。
- 请求人工批准前，交付者必须确保 PR 正文已有正确的 close intent，并报告 PR URL、当前 head SHA、检查状态和建议合并策略。
- 获得与门禁卡匹配的一行批准口令后，门禁执行者必须重新核验 head SHA，执行合并，确认 GitHub PR 已为 `merged`，再核验 Multica Issue 是否为 `done`；Webhook 未自动关单时可在确认合并后手动置为 `done`。

## 完成定义

Agent 声明完成前必须给出：

- 实际产物或变更文件；
- 执行过的检查及结果；
- 未执行的检查与原因；
- 风险、遗留问题和需要人工决定的事项；
- PR URL（有代码或版本化文档变更时）；
- 对应 Multica Issue 的状态与下一门禁。
