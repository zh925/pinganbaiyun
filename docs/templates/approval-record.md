# 人工批准与门禁执行记录

## 门禁卡

- 门禁编号：GATE-<ISSUE-KEY>-<版本短标识>
- 门禁类型：最终需求基线 / 开发 PR 最终合并 / 生产发布
- 确认对象：PR URL
- 确认版本：完整 head SHA
- 检查摘要：
- 评审与遗留风险：
- 拟执行动作：
- 默认策略：

## 提示用户

```text
批准 GATE-<ISSUE-KEY>-<版本短标识>
拒绝 GATE-<ISSUE-KEY>-<版本短标识>：原因
```

## 人工决定

- 决策人：
- 决策口令：
- 决策时间：

## 执行前核验

- [ ] 评论作者具有决策权
- [ ] Gate ID 唯一且仍有效
- [ ] PR URL 与当前 Issue 一致
- [ ] 当前 head SHA 与确认版本完全一致
- [ ] PR Ready 且可合并
- [ ] checks 已通过，或已如实记录未配置/豁免
- [ ] `Closes` 仅指向当前交付 Issue

## 执行结果

- PR 最终状态：
- Merged commit：
- 当前 Issue 最终状态：
- Stage barrier 状态：
- 下一阶段 Issue：
- 异常与未执行项：
