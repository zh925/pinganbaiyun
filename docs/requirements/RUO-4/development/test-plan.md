# RUO-4 开发测试计划

## 追踪基线

- 交付 Issue：RUO-10（RUO-4 Stage 4）
- 已确认需求 PR：https://github.com/zh925/pinganbaiyun/pull/3
- 用户确认的需求 PR head：`bd8c03aad9c69bc8f99e868465cebd3166809028`
- 已合并需求 commit：`2bf816cd93fe408de7d66ecc94d36463efa044cf`
- 协议参考：`dogproton/SafeBaiyun@c4ff50888ef9244870a30304b3a5e6d0d5a96eac`
- 测试对象：RUO-10 Draft PR #5 的精确 head SHA / 对应 debug 与 release 候选构建；每轮验证在 Issue 记录具体 SHA
- 当前执行状态：独立验证在旧 head `f7643f30e64eb86ddb22ebcfde018221a0a1939d` 判定 FAIL（关键本地集成/故障注入不足）；实现者已在后续修复工作树补强并本地复跑，最终精确 head 与复验结论以 Issue 最新记录为准
- CI/CD 状态：未接入；GitHub 无 checks 不代表任何命令通过

## 验收覆盖矩阵

| 验收 ID | 测试层级 | 测试项 | 命令/步骤 | 预期结果 | 当前结果 | 证据 |
|---|---|---|---|---|---|---|
| AC-01、FR-01 | 仪器/UI + 网络观察 | 全新安装空状态 | 清除应用数据后启动列表；观察网络与 BLE 调用 | 显示新增入口；网络请求、scan、connectGatt 均为 0 | 未执行 | 待独立仪器验证 |
| AC-02～AC-04 | 单元 + 参数化仪器 | 新增、规范化、掩码、非法输入、重复 MAC | 覆盖名称边界、MAC 大小写、16 位 hex、空值/非 hex/重复 MAC | 合法数据生成稳定 id 并加密保存；非法数据不落盘、不发 BLE | 实现者 JVM 部分通过；仪器未执行 | `DoorValidationTest`、`DoorRepositoryTest` |
| AC-05～AC-11、NFR-06 | 单元 + 仪器 | 编辑、删除、唯一默认、取消默认、悬空引用和重启恢复 | 对仓储执行事务/并发测试；重启进程并复核列表与 defaultId | id 稳定；任一时刻最多一个默认；删除默认原子清空且不改选 | 实现者 JVM 部分通过；并发/重启未执行 | `DoorRepositoryTest` |
| AC-12、NFR-07 | 故障注入单元 + 仪器 | 密文损坏、JSON 损坏、Keystore key 失效 | 注入错误 AES key/截断 envelope/悬空 defaultId；设备上再验证 Keystore 失效 | 认证失败不覆盖原密文；悬空引用清除且不改选 | JVM store core 通过；Android Keystore/仪器未执行 | `EncryptedSnapshotCodecTest` |
| AC-13～AC-15 | 状态机单元 + 假 GATT + 授权真机 | 已知 MAC 直连与 GATT 通道准备 | fake 校验完整调用顺序；真机复核无 scan、目标 Service 与 descriptor 串行回调 | 单 taskId；connect→discover→descriptor 队列→read→write；失败释放 | 纯 Kotlin 假 GATT 通过；Android API 映射仅编译，真机未执行 | `GattSessionTest`、`AndroidGattClient` |
| AC-16、NFR-10 | JVM 黄金测试 | SafeBaiyun 20 字节 DES 指令帧 | 使用固定非真实 MAC/key/seed 向量，逐字节对照固定参考 commit | 帧头尾、长度、MAC 段、DES 密文和校验完全一致 | 实现者 JVM 通过；待独立复核 | `SafeBaiyunProtocolTest` |
| AC-17～AC-19 | 参数化单元 + 假 GATT | seed/DES/GATT 发起与回调失败 | 注入空/>6 字节 seed、connect/discover/notify/descriptor/read/write 未发起与非成功 status | 唯一失败终态；不发送空/部分帧；关闭一次且忽略迟到回调 | JVM 假 GATT 通过；Android 真机未执行 | `GattSessionTest`、`SafeBaiyunProtocolTest` |
| AC-18 | 单元 + UI 文案 | 传输成功语义 | 注入 write callback `GATT_SUCCESS` | 仅显示“开门指令已发送，请确认门禁状态”，不宣称门已打开 | 实现者 JVM 通过；UI 未执行 | `UnlockCoordinatorTest` |
| AC-20～AC-25、NFR-01～NFR-03、NFR-11 | 状态机模型 + 虚拟时钟 + 并发仪器 | 单任务、取消/迟到回调、逐阶段超时、显式重试、资源释放 | 逐一进入连接/发现/准备通道/读 seed/写入后触发新 timeout；各 GATT 阶段取消 | 活跃任务 ≤1；timeout 每阶段重置；终态关闭一次且迟到成功无效 | JVM 协调器+假 GATT 通过；仪器/50 轮未执行 | `UnlockCoordinatorTest`、`GattSessionTest` |
| AC-24、AC-30 | 单元 + 仪器 | 任务快照和重试读取最新配置 | 权限挂起时保留快照；终态后按 id 读取最新配置重试 | 当前任务不变；重试读取最新配置；删除后不能重试 | JVM flow controller 通过；Activity 仪器未执行 | `UnlockFlowControllerTest` |
| AC-26～AC-30 | 生命周期协调单元 + 仪器 | 冷启动一次、挂起续接、热启动/重建/回前台不重触发 | 模拟 Activity 重建调用 process-scoped controller；设备上再执行 `am force-stop`/旋转 | 冷启动 token 一次；权限/蓝牙返回续接同一 pending；重建不新建自动任务 | JVM 协调层通过；Android framework 仪器未执行 | `UnlockFlowControllerTest` |
| AC-31～AC-33 | 前置条件单元 + API 兼容真机/设备池 | BLE 不支持、蓝牙关闭、权限拒绝/返回、legacy 权限 | JVM 驱动 permission→bluetooth→ready；设备执行权限矩阵 | 未满足条件不发 GATT；返回只继续 pending；无循环弹窗 | JVM 协调层通过；实际系统权限/API 26–35 阻塞 | `UnlockFlowControllerTest`；缺设备池 |
| AC-34、AC-35、NFR-05 | 授权真机 + 流量观察 | 飞行模式手动与默认自动路径 | 预存脱敏测试门禁；飞行模式下单独开启蓝牙；执行手动与冷启动路径 | 网络请求为 0；手动链路完成；自动链路最多一次并到可验证终态 | 阻塞 | 缺授权门禁、测试凭据和设备 |
| AC-36、NFR-04 | 自动扫描 + 人工安全检查 | UI/日志/异常/剪贴板/分享敏感信息泄漏 | 对 debug/release 的成功与失败路径采集日志和界面；扫描固定测试 secret、seed、完整帧 | 完整 key、seed、可重放帧泄漏数为 0；无复制/分享入口 | 实现者静态源码扫描通过；运行时未执行 | 本地 `rg`；待设备采证 |
| AC-37 | 静态检查 + 真机安全测试 | 加密落盘、备份禁用、卸载后不可恢复 | 审查 manifest/backup rules；检查私有存储；执行目标 API 可用的备份验证与卸载重装 | 私有存储无明文 key；备份禁用；卸载后 APP 不可恢复配置 | Manifest/源码审查通过；真机未执行 | `AndroidManifest.xml`、`data_extraction_rules.xml` |
| AC-38 | 本地 Gradle | 单元测试与 debug 构建 | `./gradlew testDebugUnitTest assembleDebug` | 两项成功，协议、存储、调度、假 GATT 与错误测试通过 | 修复工作树本地通过（23 tests）；新 head 独立验证未执行 | 本地 Gradle XML；精确 head 见 Issue |
| AC-39、NFR-12 | 真机兼容回归 | API 26、28、30、31/32、33、34、35 与目标门禁矩阵 | 在记录设备、Android、APP commit/build 的设备池执行核心成功/失败用例 | 每格有 PASS/FAIL 和脱敏证据；无未解释崩溃/永久挂起 | 阻塞 | 缺设备池、授权门禁与 build ID |
| AC-40 | 流程检查 | 需求门禁与开发起点 | 核对 PR #3、用户确认 head、merged commit 与 RUO-10 stage | 开发只基于 `2bf816c...` 开始 | 通过 | RUO-10 描述与 git 历史 |
| NFR-08 | StrictMode + 性能观察 | 主线程响应 | 开启 StrictMode；反复执行存储、加密、连接/取消路径并观察 ANR/jank | 无主线程磁盘/网络违规和可感知 ANR | 未执行 | 需设备环境 |
| NFR-09 | 人工可访问性 | TalkBack、焦点、文本与非颜色反馈 | 遍历 CRUD、进行中、成功及所有失败页面 | 关键控件有可读文本/焦点；状态不只依赖颜色；取消可达 | 未执行 | 需设备环境 |

## 单元测试

实现至少提供以下可在无真实凭据、无 BLE 设备环境运行的自动化：

- 门禁字段校验与规范化：名称边界、MAC 合法/非法与大小写、key 长度/hex、重复规范化 MAC。
- 仓储原子性：稳定 id、CRUD、defaultId 唯一替换/取消、删除默认、悬空引用、并发写入、损坏数据保留。
- 本地密钥封装：加解密成功、密钥失效、认证失败、异常不覆盖密文；测试夹具只能使用公开假 key。
- 协议黄金向量：固定参考 commit、20 字节长度、各偏移、DES/ECB/NoPadding、校验、空/过长 seed、加密失败不输出帧。
- 开门状态机：每阶段成功/失败/超时、任务快照、唯一终态、单任务锁、取消与迟到回调、显式重试和资源释放。
- 冷启动协调器：token 单次消费、无默认/非法默认不触发、权限和蓝牙续接原 taskId、热启动/重建不重触发。

命令：

```bash
./gradlew testDebugUnitTest
```

## 集成与 E2E

- 假 GATT 集成：严格校验 `connectGatt → discoverServices → descriptor 队列 → read seed → write command`，覆盖同步发起失败和异步非成功 status。
- Android 仪器：CRUD、加密持久化、唯一默认、进程重启、权限结果、生命周期重建、冷启动 token、防连点与文案。
- 授权门禁 E2E：API/厂商/权限/蓝牙/门禁/网络矩阵；只记录测试编号，真实 MAC、key、完整 seed 和帧不得进入仓库、日志或 Issue。
- 真实门禁成功判断止于 GATT 写成功和“指令已发送”；物理效果单列人工观察，不作为 APP 可证明回执。

计划命令：

```bash
./gradlew connectedDebugAndroidTest
```

## 静态检查与构建

开发候选至少手动执行：

```bash
./gradlew lintDebug
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

实现者在修复工作树执行上述三项；最终结果、测试数和精确 head 以 Issue 回报为准。独立验证必须在推送后的新 head 重新执行并记录结果。CI/CD 未接入，GitHub 无检查不等于上述命令通过。

## 测试数据与证据规则

- 自动化仅使用公开、不可用于真实门禁的固定假 MAC/key/seed。
- 授权真机凭据只进入批准的本地 Secret 载体，不写入源码、Gradle 属性、截图、日志、Issue 或 PR。
- 每次验证记录：主开发 PR URL、精确 head SHA、构建变体/build ID、JDK/Gradle/Android SDK、设备型号/API、命令退出码和脱敏报告路径。
- 失败不得通过删除、跳过或放宽断言掩盖；独立缺陷按 QA 规范创建 Issue 并分级。

## 未执行项及原因

- 独立 Gradle/lint/build/自动化：测试工程师尚未基于集成测试计划后的新 PR head 执行。
- Android 仪器与生命周期：当前没有已启动的模拟器/设备验证证据。
- BLE/GATT 与物理效果：未提供授权测试门禁、测试 MAC/key、Secret 载体和脱敏采证方式。
- API 26–35/厂商兼容矩阵：未提供设备池或等价服务。
- CI/CD：仓库明确尚未接入；本计划不声称任何自动流水线已运行或通过。

## 已知不稳定测试

- 当前实现者执行的 23 个 JVM 测试均为确定性测试，未发现不稳定项；独立验证尚未确认。
- 后续真实 BLE 用例不得用无界重试“消除”波动；需区分设备不可达、系统 GATT 波动、协议失败与产品缺陷，并保存每次原始结果。
