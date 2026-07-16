# RUO-4 现状审计与差距

## 审计范围与基线

- `pinganbaiyun`：`24032ced4866998dad66a00b23b7386cce8d62a2`
- `SafeBaiyun`：`c4ff50888ef9244870a30304b3a5e6d0d5a96eac`
- 审计日期：2026-07-17
- 本文只评估现有成果与 RUO-4 的差距，不确认既有需求、不修改 Android 实现，也不覆盖 `docs/` 下已有成果。

## 已有成果

| 领域 | 现状 | 可复用性 | RUO-4 差距/风险 |
|---|---|---|---|
| Android 工程 | Kotlin、minSdk 26、target/compileSdk 35，已有 NFC、BLE、维护页和单元测试 | 工程骨架、页面和部分纯函数可复用 | 当前检出基线编译失败，不能视为可交付版本 |
| 门禁模型 | `DoorConfig` 含稳定 `id`、`doorName`、`mac`、`key`、`bluetoothName` | `id`、显示名、MAC、key、规范化思路可复用 | `bluetoothName` 来自另一参考实现，不是 `SafeBaiyun` 协议字段；key 校验允许 16–32 位，和单 DES 实际有效 8 字节存在歧义 |
| 本地存储 | `EncryptedSharedPreferences` + Keystore；应用禁止系统备份 | 比 `SafeBaiyun` 明文 `SharedPreferences` 更安全，可作为推荐方向 | 当前 `DoorConfigStore` 只有 `currentId`，没有 UI 调用的 `defaultId()/setDefault()`；默认关系未落库 |
| CRUD | 已有列表、新增/编辑、删除、手动开门入口 | 交互和表单可复用 | 需按最终字段模型调整；需覆盖重复 MAC、默认项删除、腐坏数据和安全展示 |
| 默认门禁 UI | 列表已出现设为/取消默认、替换确认、删除默认提示 | 视觉与文案可作为原型输入 | 仅 UI 分支，存储接口缺失；没有冷启动自动调度器、进程级单任务锁或冷却持久状态 |
| 手动开门 | `DevicesFragment` 可调用 `BleUnlockManager`，进行中阻止同页重复点击 | 状态反馈、取消/重试框架可复用 | 锁只在 Fragment 实例内，不能覆盖 NFC/默认流程或多 Activity；Fragment 销毁即停止任务 |
| 自动开门 | 旧文档写了冷/热启动和回前台触发 | 可作为被审计需求输入 | `NfcRouterActivity` 未实现默认门禁自动开门；旧文档触发范围过宽，物理副作用和重复风险高 |
| BLE 连接 | 当前实现先扫描、五路匹配、候选 Service、动态特征、通知组包 | 错误分类、超时、日志脱敏可选择性复用 | `SafeBaiyun` 是已知 MAC 直接 `getRemoteDevice().connectGatt()`，没有扫描、广播名或五路匹配；必须先决定基线 |
| 协议 | 当前实现来自 `BaiyunKeys-preview`，含额外命令和回执语义 | DES 与 20 字节帧的一部分和 `SafeBaiyun` 可交叉验证 | 第二 Service、通信密钥、时间同步、`00/02` 回执均不能由指定 `SafeBaiyun` 证明 |
| 权限 | Manifest 同时声明扫描、连接、Android 8–11 定位 | 若最终保留扫描则大体方向可复用 | 若采用 SafeBaiyun 直连，扫描/定位权限可删除，减少权限面；当前 `PermissionUtils.hasBlePermissions()` 未把 API 26–30 定位纳入开门前检查 |
| 安全 | release 禁止调试日志面板，密钥 UI 掩码，备份关闭 | 推荐保留 | debug 可复制/分享协议日志，仍需测试不含完整 key、种子和可重放帧；DES 和静态设备 key 本身抗攻击能力弱 |
| 测试 | 有协议、匹配、组包、回执、字段校验 JVM 测试 | 测试骨架可复用 | 黄金向量来源为另一仓库；缺 `SafeBaiyun` 代码对照向量、默认唯一性/删除/迁移/自动调度/并发/无网仪器测试 |

## 可复现检查

执行：

```text
ANDROID_HOME=/Users/zheng/Library/Android/sdk ./gradlew testDebugUnitTest assembleDebug
```

结果：失败于 `:app:compileDebugKotlin`。`DevicesViewModel.kt` 调用 `DoorConfigStore.defaultId()` 与 `setDefault()`，而当前基线没有这两个方法。由于编译阶段失败，单元测试与 APK 组装均未完成。

## 现有实现与 SafeBaiyun 的关键偏差

| 项目 | SafeBaiyun 代码事实 | 当前 pinganbaiyun | 需求处理 |
|---|---|---|---|
| 输入字段 | MAC、Key | 门名、MAC、Key、广播名 | 门名是本地业务字段；广播名降为兼容字段，是否保留待 ADR-001 |
| 找设备 | 已知 MAC 直接取远端设备 | BLE 扫描后五路匹配 | 不得同时把两条链路写成必选；待 ADR-001 |
| Service | 仅 `14839ac4-...` | 另加 `0734594a-...` 候选 | 第二 UUID 需独立设备证据，否则不属于基线 |
| 特征 | 属性动态选择 read/write/notify/indicate | 同类动态选择 | 可保留，但必须逐步检查每个异步 API 的发起返回值与回调状态 |
| 指令 | 读到 seed 后构造单个 20 字节帧并写入 | 握手、通知解析及额外命令能力 | 20 字节帧可追溯；额外命令/回执待设备证据 |
| 成功 | `onCharacteristicWrite(GATT_SUCCESS)` 显示开门成功 | 通知帧解析后显示成功/已开 | 前者只能证明写入完成；后者尚无指定参考证据。默认使用中性成功态 |
| 超时 | 连接前后单个 10 秒任务，连接成功即取消 | 扫描/连接/seed/ACK/全流程多超时 | 多阶段超时是合理增强，但具体时长属于产品/设备验证项 |

## 范围边界

### 本需求保留

- 本地门禁列表 CRUD、唯一默认门禁、手动开门、冷启动自动开门。
- 无后端、无账号、无网络依赖。
- 以指定代码版本可证明的 BLE/GATT + DES 帧为协议基线。
- 全失败路径、并发防护、取消/显式重试、权限与版本兼容、安全保护和可验证验收。

### 本需求不覆盖

- NFC 读写卡流程、桌面快捷方式和小组件。
- 云端同步、登录、远程开门、门禁分享、批量导入导出。
- 修改门禁固件、证明设备端幂等/防重放、破解或提取第三方 APP 数据。
- 未经确认采用 `BaiyunKeys-preview` 的额外协议命令、第二 Service 或回执语义。

## 进入 Stage 2 前置条件

1. 用户确认 ADR-001 至 ADR-003 的选择。
2. 需求 PR、不可变 commit SHA 和明确通过结论齐全。
3. 原型必须对“连接/写入成功”和“物理门已打开”使用不同语义。
4. 开发开始前另行修复或选定一个可编译基线；本 Stage 不实施该修复。
