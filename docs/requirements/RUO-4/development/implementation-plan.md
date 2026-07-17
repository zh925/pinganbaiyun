# 实施计划

- 原始需求：RUO-4
- 确认 commit：`2bf816cd93fe408de7d66ecc94d36463efa044cf`（确认 PR head：`bd8c03aad9c69bc8f99e868465cebd3166809028`）
- 开发 Issue：RUO-10
- 负责人：功能开发工程师

## 设计概览

从确认 commit 新建单 Activity Android 应用，支持 API 26–35。门禁配置通过 Android Keystore AES/GCM 加密为单一原子快照，配置列表与 `defaultId` 同步提交；密文损坏时保留原数据并要求二次确认后重置。进程级 `UnlockCoordinator` 持有全局单任务锁、不可变门禁快照、阶段超时与唯一终态；Launcher 进程冷启动 token 只消费一次，生命周期恢复不创建新自动任务。

BLE 采用 ADR-001 的已知 MAC 直连，不扫描。`AndroidGattTransport` 固定 Service UUID，按 UUID 排序后确定性选择 read/write/notify/indicate 特征，串行写 CCCD、读取 seed、构造并写入 20 字节帧；每个 API 发起结果和回调 status 均检查，失败、取消和超时均关闭 GATT。协议纯函数绑定 `SafeBaiyun@c4ff50888ef9244870a30304b3a5e6d0d5a96eac`，拒绝空或超过 6 字节的未证实 seed，写回调成功只展示中性“指令已发送”。

## 子任务与依赖

| 子任务 | Issue | 负责人 | 依赖 | 状态 |
|---|---|---|---|---|
| Android 工程、业务实现、协议与本地测试 | RUO-10 | 功能开发工程师 | 确认需求 merge commit | 已补强假 GATT/故障注入，待独立复验 |
| 覆盖矩阵与独立验证 | RUO-10 | 测试与持续集成工程师 | 可测试实现 commit | 首轮 FAIL，待新 head 复验 |
| 授权门禁与 Android 兼容矩阵验证 | 后续 QA | QA/发布组 | 授权设备、脱敏凭据、候选 build | 未开始 |

## 接口与数据变化

- 新增 `DoorConfig(id, doorName, mac, key)` 与独立 `DoorSnapshot.defaultId`；不新增网络、账号、数据库或导入导出接口。
- 本地存储为私有 `SharedPreferences` 中的 AES/GCM envelope，明文 JSON 只存在于进程内；Android Keystore alias 不含业务凭据。
- `SafeBaiyunProtocol.buildUnlockFrame` 是纯函数协议边界；GATT 仅暴露阶段、发送完成或脱敏失败信息。
- Manifest 只声明 API 26–30 legacy Bluetooth 权限和 API 31+ `BLUETOOTH_CONNECT`，未声明扫描、定位或网络权限。

## 兼容性与迁移

- `minSdk 26`、`targetSdk/compileSdk 35`，JDK 17、Android Gradle Plugin 8.7.3、Kotlin 2.0.21、Gradle 8.9。
- API 33+ 使用新 descriptor/characteristic write 与 read callback；API 26–32 保留 deprecated 兼容调用。
- 全新项目无旧数据迁移；存储 schema 带版本字段但本需求不虚构迁移输入。
- `allowBackup=false`，同时排除 cloud backup 与 device transfer。

## 测试策略

- JVM：字段规范化、16 位 key 拒绝、默认关系原子变更、重复 MAC、加密认证失败/悬空引用、协议黄金向量、假 GATT 顺序与同步/异步故障、单任务锁、逐阶段超时/释放、冷启动 token、权限/蓝牙续接、重建不重复和最新快照重试。
- 本地构建：`./gradlew testDebugUnitTest assembleDebug`。
- 后续假 GATT/仪器与真机：权限拒绝/撤回、蓝牙关闭、生命周期、存储故障、API 26–35、授权门禁及飞行模式。

## 风险与回滚

- 真实 seed 长度、特征组合、设备端 seed 新鲜度/幂等、防重放及物理开门结果均缺少授权真机证据；实现对此不作成功或安全保证。
- DES/ECB 是固定遗留协议，客户端加密存储不能补救链路密码学风险。
- 多厂商 GATT 回调/缓存差异仍需兼容矩阵验证；不增加未经确认的扫描或协议回退。
- 回滚为不合并或 revert 本开发 PR；本变更无服务端、数据库、生产数据或迁移副作用。

## 完成定义

- Android 工程可构建，JVM 单测与 debug APK 组装成功。
- CRUD、唯一默认、加密存储、手动/冷启动自动任务、权限/蓝牙前置、取消/超时/重试及协议链路有代码和测试映射。
- 实现与测试计划齐全；未执行的真机、仪器、兼容、物理效果与 CI 项如实记录。
- 主开发 PR 关联 RUO-10；实现与自测完成后由技术负责人协调独立验证和后续代码评审，不在本阶段合并。
