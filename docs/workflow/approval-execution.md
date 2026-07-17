# 批准执行与阶段推进

本规范解决“用户已经确认，但 PR、Issue 和下一阶段没有继续变化”的责任空档。

## 两类责任人

- **门禁执行者**：当前交付 Issue 的 assignee。负责核验人工批准、合并当前 PR、核验关单。
- **阶段推进者**：父 Issue 的 leader。负责在整个 stage 结束后推进最低一个未完成 stage。

两者不得互相替代。门禁执行者不能跳过 stage，阶段推进者不能在交付未合并时提前启动后续任务。

## 关键人工门禁

用户只在以下节点作决定：

1. PRD、原型、内部一致性评审完成后的最终需求基线。
2. 代码评审必须项闭环后的开发 PR 最终合并。
3. QA PASS、指定版本 UAT 和发布计划完成后的生产发布。

需求中间阶段由需求组内部评审合并。高风险数据库迁移、生产数据、不可逆操作和回滚必须单独完整审批。

## 门禁卡与一行口令

门禁执行者必须预填充所有上下文，不得要求用户重复整理：

```text
门禁编号：GATE-<ISSUE-KEY>-<版本短标识>
门禁类型：最终需求基线 / 开发 PR 最终合并 / 生产发布
确认对象：<PR URL / release>
确认版本：<完整 head SHA / build ID>
检查摘要：<checks、QA、UAT>
评审与遗留风险：<必须项、建议项、已知风险>
拟执行动作：<合并 / 发布>
默认策略：<squash / 发布计划>

请回复其一：
批准 GATE-<ISSUE-KEY>-<版本短标识>
拒绝 GATE-<ISSUE-KEY>-<版本短标识>：原因
```

版本短标识优先取完整 SHA 的前 7 位；非 Git 交付使用 build/release ID 的稳定短标识。Gate ID、完整版本和执行动作共同构成一次授权。`看过了`、`可以`、`评审通过`只能视为反馈，不能执行关键门禁。

## 门禁执行状态机

```text
收到人类评论
├─ 不是批准/拒绝口令 → 保持 in_review，重新提示可用口令
├─ Gate ID 不存在/有歧义 → 保持 in_review，重新发卡
├─ head SHA/build 已变化 → 旧卡失效，重新发卡
├─ PR Draft/冲突/检查失败 → 保持 in_review 或 blocked，报告原因
├─ 拒绝口令 → 记录原因并回到修改阶段
└─ 有效批准口令
   → 确保 Closes 当前交付 Issue
   → 按门禁卡动作执行
   → 核验 GitHub state=merged
   → 核验 Multica Issue=done
   → Webhook 未关单时手动置 done
   → 评论记录 merged commit 和结果
```

禁止在 GitHub 尚未确认 `merged` 时把 Issue 标为 `done`。

## Stage 推进状态机

```text
收到 stage 完成通知
→ 重新读取父 Issue 全部 children
→ 验证当前最低 stage 全部为 done/cancelled
→ 找到最低一个未完成 stage
→ 检查每个子 Issue 的显式依赖
→ 只把依赖满足的 backlog Issue 改为 todo
→ 评论记录本次推进和仍停留 backlog 的原因
```

如果没有下一 stage，阶段推进者把父 Issue放入其流程定义的下一门禁；不得仅因子 Issue 完成就关闭父需求。

## 幂等与失败恢复

- 重复收到同一批准时，先读取 PR 状态；已经 merged 时不得重复合并，只完成缺失的关单/推进核验。
- 每个 Gate ID 只对应一个完整版本；版本变化必须生成新 Gate ID，旧口令永久失效。
- GitHub Webhook 延迟时，以 GitHub 的真实 merged 状态为前提，再手动补 Multica 状态。
- 合并失败时记录失败原因，不重复盲试；权限、冲突、保护规则或检查失败需要人工/开发处理。
- 父 leader 被重复唤醒时，已是 `todo/in_progress/done` 的下一阶段 Issue 不得再次触发或重建。
