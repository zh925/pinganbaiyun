# SafeBaiyun 协议字段与代码追溯

## 来源边界

- 仓库：`https://github.com/dogproton/SafeBaiyun`
- 审计 commit：`c4ff50888ef9244870a30304b3a5e6d0d5a96eac`
- 所有“代码事实”均绑定上述 commit；未在该 commit 出现的能力不标为协议事实。
- `SafeBaiyun` 是客户端参考实现，不是设备厂商规范。代码事实不等于协议正确性、安全性或设备兼容性保证。

## 字段映射

| RUO-4 字段 | 类型/规则 | SafeBaiyun 依据 | 结论 |
|---|---|---|---|
| `id` | APP 生成的稳定不透明 ID，非空、不可因编辑变化 | 无 | 业务字段，用于默认关系与编辑定位，不下发设备 |
| `doorName` | trim 后 1–80 字符 | 无 | 业务显示字段，不下发设备；长度是提议值 |
| `mac` | 规范化大写 `XX:XX:XX:XX:XX:XX` | `DataRepo.kt:20-32` 保存；`UnlockRepo.kt:50-56` 用 `BluetoothAdapter.checkBluetoothAddress`；`LockBiz.kt:66-72` 解析 6 字节 | 协议/连接必填字段 |
| `key` | 推荐恰好 16 个十六进制字符，大写 | `DataRepo.kt:20-32` 保存；`LockBiz.kt:12-39` 参与求和和 DES；`FDes.kt:25-28` 只构造 8 字节 DES key | 协议敏感字段；必须加密落盘、默认掩码 |
| `bluetoothName` | 可选兼容字段 | 无 | 不属于 SafeBaiyun 基线；只有保留现有扫描协议时才需要 |
| `isDefault` | 由独立 `defaultId` 推导 | 无 | 业务关系，不复制到每条配置、不下发设备 |

`SafeBaiyun` 的提取说明还把原应用数据库 `t_device.MAC_NUM` 映射为 MAC、`PRODUCT_KEY` 映射为 key（`extract.md:17-22`）。RUO-4 只允许用户自行录入合法持有的信息，不提供提取、越权访问或绕过第三方保护的功能。

## 连接与 GATT 时序

| 顺序 | 行为 | 代码依据 | 必须补强的失败处理 |
|---|---|---|---|
| 1 | 读取本地 MAC、key 并校验 MAC | `UnlockRepo.kt:45-56` | 缺失、非法或解密失败时不得发起连接 |
| 2 | `getRemoteDevice(mac)` 后 `connectGatt(autoConnect=false, TRANSPORT_LE)` | `UnlockRepo.kt:68-83` | 捕获非法地址/权限/适配器异常；定义连接超时 |
| 3 | 连接成功后 `discoverServices()` | `UnlockRepo.kt:90-97` | 检查发起返回值、GATT status、断连和超时 |
| 4 | 查找 Service `14839ac4-7d7e-415c-9a42-167340cf2339` | `UnlockRepo.kt:30,100-105` | 未找到即失败，不回退到未经确认的 Service |
| 5 | 按 properties 选择 read(2)、write(8)、notify(16)、indicate(32) 特征 | `UnlockRepo.kt:150-176` | 缺任一必需特征应失败；多个候选的确定性规则需记录 |
| 6 | 尝试启用通知/指示并读取 read 特征 | `UnlockRepo.kt:179-196` | 参考实现未正确给 CCCD descriptor 赋值，也未串行等待 descriptor 回调；RUO-4 不照搬缺陷，需按 Android GATT 异步规则实现和验证 |
| 7 | 读取 seed 后构造指令帧 | `UnlockRepo.kt:107-129,198-205`、`LockBiz.kt:12-63` | 空 seed、异常长度、读失败和超时均终止 |
| 8 | write 特征发送帧 | `UnlockRepo.kt:202-205` | 检查 API 发起结果和写回调状态；取消后迟到回调不得改写 UI |
| 9 | 写回调 GATT_SUCCESS | `UnlockRepo.kt:131-144` | 仅标记“指令已发送”；不能据此声称物理门已打开 |

## 指令帧逐字段映射

`LockBiz.encryptData(inputData=seed, headerData=MAC 六字节, keyString=key)` 生成帧：

| 偏移 | 长度 | 内容 | 代码依据 |
|---|---:|---|---|
| 0 | 1 | `0xA5` 帧头 | `LockBiz.kt:45` |
| 1 | 1 | 总长度；当加密块为 8 字节时为 `0x14`（20） | `LockBiz.kt:43-46` |
| 2 | 1 | `0x05` | `LockBiz.kt:47` |
| 3..6 | 4 | MAC 第 3–6 字节，即 MAC 字节下标 2..5 | `LockBiz.kt:14-18,48-51` |
| 7 | 1 | `0x00` | `LockBiz.kt:52` |
| 8 | 1 | `0x01` | `LockBiz.kt:53` |
| 9 | 1 | `0x07` | `LockBiz.kt:54` |
| 10..17 | 8 | DES 密文块 | `LockBiz.kt:39,55` |
| 18 | 1 | 全帧初始字节和按位取反后的低 8 位 | `LockBiz.kt:56,58-62` |
| 19 | 1 | `0x5A` 帧尾 | `LockBiz.kt:57` |

### DES 明文块

1. 将 seed 所有字节和 key 的所有已解析字节求无符号和。
2. 前两字节依次写入和的低字节、高字节。
3. 随后写 seed。
4. 末尾补 `0x00` 到 8 字节倍数。
5. 使用 `DES/ECB/NoPadding` 加密；DES key 固定 8 字节，输入 key 短则补零、长则只取前 8 字节。

依据：`LockBiz.kt:21-39`、`FDes.kt:15-29`。

### 需要真机证据的歧义

- `LockBiz` 只复制首个 8 字节密文块，却用“密文块长度 + 12”固定生成 20 字节帧。seed 超过 6 字节时后续密文会被丢弃，必须以真实设备 seed 长度验证。
- 校验算法是把包含占位校验字节 `0x00` 的整帧求和后取反低 8 位；不是 CRC，也没有接收端校验代码。
- 参考实现没有解析设备业务回执，因此无法从该仓库确认“成功/门已开/密码错误”等状态码。
- 参考实现只列一个 Service UUID，没有 Characteristic UUID 白名单；属性动态选择在多候选设备上可能选错。

## 安全分析

| 风险 | 证据 | 影响 | RUO-4 要求 |
|---|---|---|---|
| 静态 key 明文存储 | `DataRepo.kt:16-32` 使用普通 SharedPreferences | 备份、root、调试环境或本地取证可恢复开门凭据 | 不照搬；Keystore 支持的加密存储，禁止系统备份 |
| 系统备份开启 | `AndroidManifest.xml:9-13` `allowBackup=true` | 配置可能进入备份/迁移面 | RUO-4 默认 `allowBackup=false` |
| 单 DES | `FDes.kt:15-29` | 56 位有效密钥，不能抵抗现代离线穷举；ECB 无随机化/完整性保护 | 明示遗留风险；客户端不得宣称安全认证；推动协议方升级 |
| 无防重放证据 | 指令由静态 key 和设备 seed 构造，但仓库无 seed 新鲜度检查 | 若设备 seed 可预测/复用，抓包可能重放 | 真机验证连续 seed；日志不得输出完整 seed/帧；协议方确认 |
| 写成功误报开门成功 | `UnlockRepo.kt:131-144` | 用户误判物理状态；自动流程可能重复操作 | 文案降级为“指令已发送”，物理结果由用户确认或设备回执证明 |
| 异常吞噬 | `FDes.kt:15-22` 加密异常返回空数组 | 可能发送畸形帧或给出错误结果 | 加密失败必须终止并给内部错误，不发送空帧 |
| 日志泄密 | `LockBiz.kt:19-20,40-42` 输出 header、明文块和密文 | 日志可帮助还原/重放协议 | release 禁止；debug 也必须脱敏 key、seed、完整帧和 MAC |
| 无 key 校验 | `EditDialog.kt:47-55` 原样保存输入 | 奇数/非 hex 可崩溃，短 key 被补零，长 key 静默截断 | 保存前严格校验，推荐恰好 16 hex；拒绝静默截断/补零 |

## 与当前仓库协议的采用规则

- 可直接复用：BLE/GATT、目标 Service `14839ac4-...`、按属性选 read/write/notify/indicate、DES/ECB/NoPadding、MAC 尾四字节帧头、20 字节帧构造思路。
- 需要修正后复用：异步 descriptor/characteristic 操作、逐阶段超时、取消和迟到回调隔离、日志脱敏、错误分类。
- 不得默认采用：广播名、扫描匹配、`0734594a-...` Service、通信密钥/时间同步命令、`00/02` 回执语义。它们需要用户选择另一协议基线或提供设备级证据。
