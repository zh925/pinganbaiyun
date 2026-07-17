# 研发工作流

## 生命周期

```text
需求录入
→ 需求分析与原型
→ 需求内部评审
→ 用户确认需求版本
→ 开发与开发测试
→ PR 代码评审
→ 用户选择评审建议
→ 开发修改与重新评审
→ Staging QA
→ 用户 UAT
→ 生产发布批准
→ 生产发布
→ 发布后验证
→ 关闭原始需求
```

## 核心原则

- 父 Issue 是需求的唯一追踪入口。
- 阶段通过子 Issue 和 stage 表达；未开始阶段保持 `backlog`。
- 每次人工决策都必须记录对象、版本、结论和决策人，不能靠含糊评论推断。
- PR、QA、发布验证必须绑定不可变的 commit SHA 或 build ID。
- 任何失败都回流到产生修复的阶段，并重新经过受影响的后续门禁。

详细规范：

- [生命周期与 Issue 结构](lifecycle.md)
- [角色与职责](roles.md)
- [门禁与状态](gates.md)
- [批准执行与阶段推进](approval-execution.md)
- [GitHub 与代码评审](github-review.md)
- [QA 与发布](qa-release.md)
- [新人接入](onboarding.md)
