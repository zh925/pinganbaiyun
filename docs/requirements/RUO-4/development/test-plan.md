# 开发测试计划

| 验收 ID | 测试层级 | 测试项 | 命令/步骤 | 结果 | 证据 |
|---|---|---|---|---|---|
| AC-02–05 | JVM/代码审查 | 校验规范化、稳定 id、重复 MAC、编辑 | `./gradlew testDebugUnitTest` | PASS（可自动覆盖部分） | `DoorValidationTest`、`DoorRepositoryTest` |
| AC-06–11 | JVM/代码审查 | 删除、唯一默认、悬空引用读取清理 | `./gradlew testDebugUnitTest` + 存储审查 | PASS（悬空引用待仪器补证） | `DoorRepositoryTest`、`EncryptedDoorStore` |
| AC-14–18 | JVM/代码审查 | 直连基线、确定性通道、黄金帧、seed 边界、中性终态 | `./gradlew testDebugUnitTest` | PASS（真机部分待测） | `SafeBaiyunProtocolTest`、`AndroidGattTransport`、`UnlockCoordinatorTest` |
| AC-19–25 | JVM/代码审查 | 单任务、取消、迟到回调、有限超时、显式重试 | `./gradlew testDebugUnitTest` | PASS（假 GATT/仪器待补） | `UnlockCoordinatorTest` |
| AC-26–30 | 代码审查 | 进程冷启动 token、无 onResume 新触发、任务快照 | 审查 `MainActivity` / `PingAnBaiYunApp` | 实现完成；仪器待测 | 源码 |
| AC-31–35 | 代码审查 | BLE/权限/蓝牙前置、无扫描与无网络权限 | Manifest 与源码审查 | 实现完成；真机待测 | Manifest、`MainActivity` |
| AC-36–37 | 静态/代码审查 | 脱敏、Keystore AES/GCM、禁止备份 | `rg` 敏感输出与 Manifest 审查 | PASS（运行时/备份待真机） | 源码、Manifest |
| AC-38 | 本地构建 | JVM 单测及 debug 构建 | `./gradlew --no-daemon testDebugUnitTest assembleDebug` | PASS，10 tests | Gradle 控制台与测试报告 |
| AC-39 | 真机矩阵 | API 26–35 与目标门禁 | 见兼容矩阵 | 未执行 | 缺授权设备、凭据和设备池 |

## 单元测试

- 协议黄金向量明确绑定 SafeBaiyun commit 和源文件范围；空/过长 seed 失败且不生成部分帧。
- 校验覆盖 MAC/key 大写规范化与非法 key 拒绝。
- 仓库覆盖重复 MAC、编辑保持默认、删除默认同步清除。
- 协调器覆盖全局锁、取消胜过迟到成功、阶段超时释放、中性发送终态。

## 集成与 E2E

- 本轮未配置 Android 仪器测试框架或假 GATT 设备层；GATT API 分支通过编译和代码审查验证。
- 真实权限、蓝牙设置返回、Activity 重建、飞行模式、存储损坏与目标门禁交互必须在后续授权设备上执行。

## 静态检查与构建

- 本地 JDK 17、Android SDK 35 执行 `testDebugUnitTest assembleDebug` 成功。
- Manifest 无 `INTERNET`、`BLUETOOTH_SCAN`、定位权限；`allowBackup=false` 且 transfer/backup 均排除。
- CI/CD 尚未接入，本结果是本地手动检查，不代表远端 CI。

## 未执行项及原因

- API 26/28/30/31–35 真机与厂商矩阵：无设备池。
- 授权真实门禁、真实 MAC/key、seed 新鲜度、错误 key、物理效果与防重放：未提供授权测试环境；不得使用或记录真实凭据。
- 运行时日志/私有存储/系统备份取证、TalkBack、StrictMode、长时与网络观察：需安装候选 APK 的设备环境。
- QA、UAT、生产发布：属于后续门禁，本阶段不执行。

## 已知不稳定测试

当前 10 个 JVM 测试均为确定性测试，未发现不稳定项。后续 BLE 真机用例需按设备、系统版本、APP commit/build 和脱敏证据记录，不能用模拟器替代。
