# RUO-4：蓝牙门禁离线维护与默认门禁自动开门

## 文档状态

- 阶段：Stage 3 内部一致性评审，待形成最终需求基线
- 父 Issue：RUO-4
- 分析 Issue：RUO-5
- 需求基线：Stage 1 分析版本已合并；包含原型与一致性修正的最终版本尚未确认，不得据此推进开发
- 唯一交付仓库：`zh925/pinganbaiyun`
- 指定协议参考：`dogproton/SafeBaiyun@c4ff50888ef9244870a30304b3a5e6d0d5a96eac`
- 仓库基线：2026-07-17 重新初始化后的 `main`；没有可继承的 Android 工程或旧实现

## 产物索引

- [PRD](prd.md)
- [验收标准](acceptance-criteria.md)
- [现状审计与差距](current-state-audit.md)
- [SafeBaiyun 协议字段与代码追溯](protocol-traceability.md)
- [Android / Bluetooth 版本、权限与兼容性矩阵](android-bluetooth-compatibility.md)
- [ADR-001：协议基线与连接方式](decisions/ADR-001-protocol-baseline.md)
- [ADR-002：自动开门触发与防重复](decisions/ADR-002-auto-unlock-trigger.md)
- [ADR-003：本地密钥保护与备份策略](decisions/ADR-003-local-secret-protection.md)

## 评审必须回答的问题

1. 是否确认以 `SafeBaiyun` 的“已知 MAC 直连 + 单 Service UUID + 读种子后写 20 字节 DES 帧”为 RUO-4 协议基线，并删除“扫描、广播名、第二 Service、最终回执解析”等未被该仓库证明的必选要求？
2. “进入 APP”是否仅指进程级冷启动？本稿推荐仅冷启动自动开门；热启动、Activity 重建、权限页返回和后台回前台均不自动触发。
3. 是否接受“GATT 写入成功”只能展示“开门指令已发送”，不能宣称物理门已打开？若必须确认物理结果，需要设备协议方提供可验证回执。
4. 是否确认门禁密钥输入为恰好 16 位十六进制（8 字节 DES key）？`SafeBaiyun` 对更短 key 补零、对更长 key 截断，容易造成误配。
5. 是否允许系统备份迁移门禁配置？本稿推荐禁止备份；如需跨设备迁移，必须另立受保护导入/导出需求。

## Stage 3 内部一致性评审

| 检查项 | 结论 | 闭环 |
|---|---|---|
| 产物与模板 | PRD、验收标准、3 份 ADR、协议追溯、权限矩阵和可运行原型齐全 | 通过 |
| 仓库现状 | 移除对已删除旧 Android 工程、旧实现和旧构建失败的依赖性表述 | 已修正 |
| 功能与异常 | CRUD、唯一默认、手动/冷启动自动开门、失败、取消、重试和数据损坏在 PRD、验收与原型中对齐 | 已修正原型单任务/冷启动 token |
| 协议与安全 | 固定 SafeBaiyun commit；区分 GATT 写成功与物理开门；保留 DES、防重放、密钥存储风险 | 通过，仍需真机证据 |
| 范围与门禁 | 移除全新项目不存在的 NFC 竞争假设；未声称 CI/CD、Android 构建、真机 BLE 或用户最终确认已完成 | 通过 |

内部评审不代替用户确认。最终需求基线必须以本轮需求产物 PR 的不可变 head SHA 为对象，通过门禁卡获得精确批准后方可合并并推进开发。

## 门禁声明

本目录是待最终确认的需求基线候选。只有用户对最终需求产物 PR 的指定 head SHA 按门禁卡给出完全匹配的批准口令，且该 SHA 合并完成后，后续开发阶段才可从 `backlog` 推进。
