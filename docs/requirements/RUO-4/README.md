# RUO-4：蓝牙门禁离线维护与默认门禁自动开门

## 文档状态

- 阶段：Stage 1 需求分析，待内部评审与用户确认
- 父 Issue：RUO-4
- 分析 Issue：RUO-5
- 需求基线：尚未确认；不得据此推进开发
- 唯一交付仓库：`zh925/pinganbaiyun`
- 指定协议参考：`dogproton/SafeBaiyun@c4ff50888ef9244870a30304b3a5e6d0d5a96eac`
- 现状审计基线：`zh925/pinganbaiyun@24032ced4866998dad66a00b23b7386cce8d62a2`

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

## 门禁声明

本目录是未确认需求草稿。只有用户按流程提供需求产物 PR URL、确认 commit SHA 和明确“通过”结论后，后续开发阶段才可从 `backlog` 推进。
