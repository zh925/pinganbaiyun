# GitHub 与代码评审

## 分支与关联

- 默认分支：`main`
- 工作分支：`agent/<issue-key>-<short-description>`
- PR 标题、正文或分支必须包含 `RUO-<数字>`。
- PR 使用固定模板，并列出所有相关 Issue。
- 只有合并后应完成的当前交付 Issue 使用 `Closes`；不要对父需求和所有子 Issue 批量使用关闭关键字。
- 需求文档、原型、开发等 PR 都应关闭各自的阶段 Issue，例如需求 PR 使用 `Closes RUO-5`，而不是关闭父需求 `RUO-4`。

## 评审轮次

1. 开发完成自检后，将 PR 标记 Ready。
2. 评审负责人按 `repo + PR number + head SHA` 建立唯一轮次。
3. 专项评审意见使用 `R1`、`R2` 编号，等级为“必须修改、建议修改、讨论项”。
4. 评审负责人通过 `gh` 提交 COMMENT review，不代替用户批准或拒绝。
5. 用户明确选择建议后，开发组只实施被接受的项目。
6. 修改期间 PR 转为 Draft；修改完成并自检后重新 Ready，使用新 head SHA 开始新一轮评审。
7. 最终合并必须由有权限的人类明确给出 PR URL、head SHA、通过结论和 `允许合并：是`。
8. 当前交付 Issue 的 assignee 执行合并；代码评审负责人只汇总意见和结论，不凭评审结果自行扩大合并授权。

## 合并与关单

门禁执行者在合并前后必须分别读取 GitHub 与 Multica 的真实状态：

```text
批准评论
→ 核验 PR URL/head SHA/权限/checks
→ 确保 Closes 当前交付 Issue
→ 执行合并
→ 核验 PR state=merged
→ 核验当前 Issue=done；必要时在已确认合并后手动关单
→ stage barrier 唤醒父 leader
→ 父 leader 推进下一 stage
```

PR 仅被关联不会自动合并，也不会产生 close intent。`Closes` 只负责“合并后关单”，不负责执行合并本身。

## 意见质量

每条意见应包含：位置、问题、影响、证据、建议和严重程度。禁止仅给风格偏好、没有上下文的泛泛建议或无法执行的结论。

## 当前限制

CI/CD 尚未接入，PR 检查必须如实记录手动执行的命令和结果。不得把“未配置检查”解释为“检查通过”。
