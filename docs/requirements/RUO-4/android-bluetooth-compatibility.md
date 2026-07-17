# Android / Bluetooth 版本、权限与兼容性矩阵

## 基线

- RUO-4 为全新 Android 工程提议支持范围：Android 8.0（API 26）至 target/compileSdk 35。
- 门禁通信为 BLE GATT client。
- 下表区分两种互斥连接方案：A 为 `SafeBaiyun` 已知 MAC 直连；B 为仅在补充设备证据后才可选择的 BLE 扫描匹配。最终只实现 ADR-001 随需求基线确认的方案。
- Android 官方依据：[Bluetooth permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)、[BluetoothGatt API](https://developer.android.com/reference/android/bluetooth/BluetoothGatt)、[Find Bluetooth devices](https://developer.android.com/develop/connectivity/bluetooth/find-bluetooth-devices)。查阅日期：2026-07-17。

## 权限矩阵

| 设备版本 | 方案 A：已知 MAC 直连 | 方案 B：BLE 扫描后连接 | 运行时/产品行为 |
|---|---|---|---|
| Android 8–11 / API 26–30 | Manifest 声明 `BLUETOOTH`；不扫描则不因蓝牙扫描申请定位 | `BLUETOOTH`、`BLUETOOTH_ADMIN`、`ACCESS_FINE_LOCATION`；定位是运行时权限，且部分设备需定位开关开启才返回扫描结果 | 缺少必要条件时停在“需要权限/开启服务”，不得创建 GATT 任务 |
| Android 12–15 / API 31–35 | `BLUETOOTH_CONNECT`，运行时“附近设备”授权 | `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`，均为运行时“附近设备”授权；不以扫描推断位置时可声明 `neverForLocation` | 永久拒绝时提供系统设置入口；取消授权后立即终止，不循环弹窗 |
| 未来版本（运行在更高 API） | 以当时 targetSdk 与官方迁移说明复核 | 同左，且重新验证扫描过滤和 GATT deprecated API | 不以当前矩阵承诺未来版本兼容；升级 targetSdk 时建立兼容任务 |

说明：官方文档明确，对已知 MAC 可在不执行 discovery 的情况下发起连接；target Android 12+ 的 GATT 通信需要 `BLUETOOTH_CONNECT`。`BLUETOOTH_SCAN neverForLocation` 可能过滤部分 BLE beacon，因此若选择方案 B，必须用目标门禁真机验证过滤不会导致漏设备。

## Bluetooth/GATT API 行为矩阵

| 能力 | API 26–32 | API 33–35 | 验收关注 |
|---|---|---|---|
| `connectGatt` | 使用带 `TRANSPORT_LE` 重载 | 同左 | `autoConnect=false`；连接发起异常、非零 status、超时、断连都收敛为一次终态 |
| Characteristic read 回调 | 使用旧回调并从 `characteristic.value` 读取 | 使用带 `value: ByteArray` 的新回调 | 两条回调路径用同一 seed 校验与状态机；不得重复消费 |
| Characteristic write | 旧版先设置 value/writeType 再调用 | API 33+ 使用带 value/writeType 的重载 | 同时检查“调用是否成功发起”和 `onCharacteristicWrite` status |
| Descriptor write | 旧版设置 descriptor value 再调用 | API 33+ 使用带 value 的重载 | GATT 操作必须串行；等待每个 descriptor 回调再进行下一步 |
| 蓝牙关闭/权限撤回 | 可能同步抛异常或异步断连 | 同左，权限撤回更常见 | 捕获 `SecurityException`，清理 GATT，展示可操作原因 |

## 应用生命周期与任务矩阵

| 场景 | 是否自动开门 | 状态要求 |
|---|---|---|
| 进程不存在，用户点击 Launcher 冷启动 | 有有效默认门禁且前置条件满足时，最多一次 | 创建冷启动 token；显示准备→连接→读取→发送→结果 |
| 进程仍在、Activity 重建/旋转 | 否 | 恢复现有任务 UI，不创建第二任务 |
| 权限弹窗/蓝牙开启页返回 | 否（继续原被挂起任务） | 原 token 继续或终止，不重新触发 |
| 从后台回前台 | 本稿推荐否 | 只恢复状态；若用户另行选择自动触发，需定义最小后台时长和冷却 |
| 热启动点击 Launcher | 本稿推荐否 | 不产生物理副作用；用户可手动开门 |
| 进程被系统杀死 | 运行中的连接自然终止 | 下次真正冷启动可新建一次；不持久化“进行中”并盲目续传 |

## 设备与厂商兼容性

至少选择以下真机覆盖；模拟器不能替代 BLE 门禁验收：

| 维度 | 最低覆盖 |
|---|---|
| Android 版本 | API 26/28、30、31/32、33、34、35 各至少一台或等价设备池 |
| 厂商 | AOSP/Pixel 类、主流国产定制系统至少两类 |
| 权限状态 | 首次允许、拒绝、永久拒绝、授权后撤回 |
| 蓝牙状态 | 开启、关闭、流程中关闭、设备不支持 BLE |
| 门禁状态 | 可达、不可达、错误 key、Service 缺失、特征缺失、连接中断、写入失败、迟到回调 |
| 网络状态 | 飞行模式下按测试需要单独开启蓝牙；无 SIM、无 Wi-Fi、后端不可达 |

## 无网络验收设置

1. 预先保存测试门禁，彻底结束 APP。
2. 开启飞行模式，再按设备能力手动开启蓝牙；关闭 Wi-Fi 与移动数据。
3. 用系统或测试代理确认 APP 没有依赖网络请求；本需求不要求联网探测。
4. 冷启动 APP，验证默认门禁只创建一次任务；手动路径验证任一非默认门禁。
5. 分别执行成功、设备不可达、错误 key、权限缺失、蓝牙关闭、取消和显式重试。
6. 记录 APP 版本、commit、设备型号、Android 版本、门禁测试编号、时间与结果；不得记录真实 key、完整 seed 或可重放帧。

## 已知兼容性风险

- Android BLE GATT 回调顺序和缓存行为存在厂商差异；状态机必须容忍重复/迟到回调并在终态后忽略。
- 已知 MAC 直连能减少扫描权限和耗电，但部分门禁/系统组合可能要求先发现或设备地址可能随机化；需要目标真机验证。
- 扫描方案受位置权限、系统开关、`neverForLocation` 过滤、后台限制和 MAC 随机化影响，且当前五路匹配不由 SafeBaiyun 证明。
- `SafeBaiyun` 没有定义完整的服务发现、读取、写入与业务回执超时；RUO-4 的具体秒数需要真机基线后确认。
