# 截图待办

当前任务环境的内置浏览器运行时返回“无可用浏览器”，因此未执行实际渲染截图与自动可视化 QA，也不使用其他无关工具伪造页面截图。截图仅为可选预览产物，不是本阶段完成门禁。

浏览器恢复后可在同一原型版本补充并检查：

1. `01-door-list.png`：1440×1000，门禁列表与唯一默认门禁。
2. `02-auto-unlock-progress.png`：1440×1000，`?scene=progress&stage=read`。
3. `03-command-sent.png`：1440×1000，`?scene=success`，确认文案未宣称物理门已开。
4. `04-permission-required.png`：1440×1000，`?scene=permission`。
5. `05-mobile-empty.png`：390×844，`?scene=empty`，检查窄屏布局与新增入口。

每张截图生成后应核对：无横向溢出、文字不截断、状态不只依赖颜色、按钮可读、MAC/key/seed/完整帧不泄漏。
