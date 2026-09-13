# 开屏 Tip：Android 工程实现规范

版本：1.0 · 编写日期：2026-09-12 · 面向：负责实现、测试与交付的工程 Agent

本文中的 MUST 表示必须实现，SHOULD 表示优先实现。模块名称与算法属于本项目设计；Android 平台事实在相应位置附官方来源。本文交付范围是工程规划，不代表 APK 或真机验证已经完成。

## 1. 目标与不可混淆的技术边界

产品原则：**未声明意图时处于限制模式；声明意图后进入无限制模式；两种模式都记录前台应用使用；下次打开手机先回顾上次。**

必须先接受下列工程事实：

| 能力 | 普通安装版 `consumer` | 受管设备版 `managed` |
|---|---|---|
| 首次设置白名单、开启 Tip、意图与历史记录 | 支持 | 支持 |
| 全屏 Gate、应用内无退出按钮 | 支持；系统手势仍可能显示系统栏 | 支持；可结合设备策略进一步限制 |
| Gate 中只提供白名单入口 | 支持 | 支持 |
| 系统范围禁止非白名单应用 | **不能保证**，通知、最近任务、其他 App 跳转等可绕过 | 使用 Device Owner + Lock Task；须验证目标设备和系统例外 |
| 每次系统解锁立即出现 Gate | **不能保证**，可能恢复锁屏前 App | 作为目标能力，M0 必须验证重新施加策略与前台切换，不能仅凭 API 存在就宣称支持 |
| UsageStats 使用统计 | 用户授权后可回溯；可能延迟、缺失 | 同样需要独立验证统计权限与事件完整性 |
| 用户下载 APK 即用 | 是，需要用户设置默认桌面等 | 否，需要受管设备部署，通常涉及新设备/恢复出厂后的配置 |

普通版使用 Launcher 构建自律入口，强限制版使用官方受管设备能力。**若验收要求严格满足“未输入意图只能用白名单”，只允许在通过 M0 的 managed 设备上验收；consumer 只能以明确告知的降级版本交付。不得把普通版标为完整满足该要求。**

Lock Task 面向受管专用设备；普通应用调用 `startLockTask()` 可能只是屏幕固定，不能把它当成受管白名单模式。[官方：专用设备](https://developer.android.com/work/dpc/dedicated-devices)、[官方：Lock Task](https://developer.android.google.cn/work/dpc/dedicated-devices/lock-task-mode?hl=en)

默认实现决策：Kotlin + Jetpack Compose + Room + Coroutines/Flow + WorkManager；`minSdk = 29`，面向手机、单用户主空间。compileSdk/targetSdk 在 M0 选定当前稳定 SDK 并锁定版本，不用降低 targetSdk 绕过系统限制。不依赖 Root、隐藏 API、Accessibility、通知内容读取或悬浮窗权限。工作资料、多用户、应用分身仅做“不支持完整覆盖”的提示。

## 2. 产品流程与界面合同

### 2.1 首次安装：先选白名单，再开启 Tip

1. 用户点击 Tip 图标，展示一句用途说明及本地数据说明。
2. 展示应用列表：图标、名称、搜索、多选。用户 MUST 先保存白名单；允许用户明确确认空白名单，不擅自把微信、浏览器等加入。
3. 设置关闭暗号，输入两次确认；说明暗号只关闭 Tip，不删除历史。白名单与暗号保存后才进入权限步骤。
4. 说明并引导使用情况访问权限；consumer 申请默认 HOME 角色。每次从系统设置返回都重新读取实际授权状态，不能根据“已点授权按钮”判断成功。
5. 显示实际能力：“普通桌面模式，存在系统入口绕过”或“已验证的受管限制模式”。天气城市可选、天气不可用不影响开启。
6. 用户点击“开启 Tip 模式”。只有白名单已确认、暗号已设置、必需权限满足且模式能力检查通过时才执行。写入启用状态，在当前已解锁状态创建第一次会话并显示 Gate。

未完成引导/取消授权：保持关闭状态，不自动启用，不自动恢复到某个“成功”页面。引导进度可保存，应用重开从未完成步骤继续。managed 的设备注册是部署前置条件，不伪装成普通运行时权限弹窗。

### 2.2 Gate 主界面

全屏、无应用内退出按钮。固定四部分：

1. 时间、日期、天气（城市、温度、天气文字；不可用时清楚标注）。
2. 上一次已结束会话：意图或“未填写意图，仅使用可用软件”、起止时间、总时长、限制/无限制时长、应用 Top 3；可展开查看完整列表。
3. “这次打开手机要做什么？”输入框与“进入手机”按钮。
4. 唯一“可用软件”文件夹入口，展开为图标网格。无独立“跳过”“退出”“仅使用”按钮。

交互要求：

- 空白或仅空格输入：不提交，显示“请输入本次意图”。普通意图 trim 后长度 1–200 个 Unicode 码点；不调用 AI 判定是否合格。
- 先检测暗号，再当成普通意图处理。输入期间不记录草稿、不打日志、不上报埋点；关闭学习/自动填充建议，但承认第三方输入法行为不可完全控制。
- 点击文件夹、查看上次详情不改变业务会话；打开白名单 App 后仍属限制阶段。
- 返回键：先关闭键盘，再关闭文件夹/详情，最后停留 Gate；不能通过应用自己的返回导航进入设置或完整桌面。
- 用 `WindowInsetsControllerCompat` 处理系统栏，用 Insets 处理键盘、刘海和大字体。沉浸模式不等于屏蔽系统手势。[官方：沉浸模式](https://developer.android.com/develop/ui/views/layout/immersive)
- 不替代系统 PIN/指纹；不在系统锁屏上假装解锁，不调用绕过安全锁的逻辑。
- 首次无历史显示“这是你的第一次使用”；统计尚在回补时显示缓存与“正在整理”，不能阻塞意图输入。

### 2.3 输入意图后

同一个会话从 RESTRICTED 阶段切换到 FULL 阶段，记录意图与切换时间。成功解除本应用施加的限制后展示完整应用抽屉，包含全部可启动应用与 Tip 图标。**不要求自动切回原厂桌面**：Tip 继续承担默认 HOME，提供普通启动器功能，避免反复申请切换默认桌面。

consumer 的“解除限制”表示开放 Tip 全部应用入口；managed 表示先确认退出受管限制，再开放完整应用入口。系统本身、其他管理员施加的限制不属于 Tip 可解除范围。

FULL 中按 Home 返回完整抽屉，不重新要求意图；锁屏/会话结束后才要求新意图。

### 2.4 无限制模式下修改白名单

FULL 时点击 Tip 图标进入管理页，可编辑白名单、天气城市和暗号、查看历史。保存白名单原子替换当前选择并递增 revision；立即影响下一次限制阶段，不中断当前 FULL 会话，也不修改旧历史的白名单快照。

Gate/RESTRICTED 通过 Tip 图标、深链或被恢复的管理 Activity 均不得进入编辑页；统一路由检查后返回 Gate。DISARMED 可以进入管理页编辑，但不会因此启用 Tip。

### 2.5 暗号关闭与手动重启

Gate 输入暗号并确认：结束当前会话，原因 `DISARMED_BY_SECRET`；不创建意图记录；停止后续采集和 Tip 限制；落盘 `enabled=false`。之后系统锁屏、解锁、开机、HOME 调用、天气任务和进程重建均不得重新启用。

Tip 仍是默认 HOME 时，关闭状态展示普通完整应用抽屉，这是可用手机的必要兜底。用户点击 Tip 图标进入管理页后，必须再主动点“开启 Tip 模式”；单纯打开管理页不启用。此处采用比“打开即启用”更明确的操作定义。

关闭不等于强制杀进程、卸载或清理历史。关闭前的统计可在下一次用户手动打开时回补，查询窗口必须截断在关闭时间，绝不把关闭期间使用写入会话。

## 3. 状态机：持久业务状态与界面分离

不要将“文件夹展开”“正在打开某个 App”定义为新的持久会话状态。它们是 UI/观察状态。

```text
持久状态：DISARMED | ARMED_IDLE | RESTRICTED | FULL
附加健康状态：OK | DEGRADED | RECOVERY_REQUIRED
临时转换状态：APPLYING_RESTRICTION | RELEASING_RESTRICTION

DISARMED --用户明确开启且检查通过--> RESTRICTED（设备已解锁）
DISARMED --用户明确开启且设备锁定--> ARMED_IDLE
ARMED_IDLE --确认屏幕可交互且系统已解锁--> RESTRICTED
RESTRICTED --普通意图提交且解除限制成功--> FULL
RESTRICTED --打开/返回白名单 App--> RESTRICTED
RESTRICTED / FULL --锁定或非交互事件--> ARMED_IDLE
RESTRICTED --暗号关闭成功--> DISARMED
任意启用状态 --致命恢复失败--> DISARMED + RECOVERY_REQUIRED
```

`ARMED_IDLE` 表示启用但当前无活动会话，一般是屏幕关闭/系统锁定。这里将“进入非交互状态”也作为结束边界，因此用户关闭安全锁或有延迟锁定时，重新点亮屏幕也开始新一轮。接近传感器等特殊熄屏必须按真实 interactive/keyguard 信号验证，不能仅凭显示面板熄灭结束通话会话。

| 事件 | 前置条件 | 数据变更 | 副作用 |
|---|---|---|---|
| EnableRequested | 引导及能力检查通过 | enabled=true、创建控制版本 | 已解锁则开会话，实施限制并显示 Gate |
| UnlockObserved | enabled=true 且无活动会话 | 创建 Session、RESTRICTED Segment | 可合法显示时进入 Gate |
| WhitelistLaunch | RESTRICTED 且目标允许 | 可记内部启动事件 | 明确组件启动；再次核验安装状态 |
| IntentSubmitted | RESTRICTED、非空、非暗号 | 保存待执行命令 | 解除成功后切阶段、保存意图、显示全部应用 |
| LockOrNonInteractive | 存在活动会话 | 关闭阶段及会话 | 准备下一次限制；去重重复屏幕事件 |
| SecretSubmitted | RESTRICTED、暗号匹配 | 停止采集边界、写关闭意图 | 恢复 Tip 策略，持久关闭 |
| Home/ManagementOpened | 任意 | 必要时回补事件 | 按状态路由；不隐式 Enable |
| ProcessRestored | 任意 | 回放事件及待执行命令 | 根据数据库和系统真实状态恢复 |
| EssentialCapabilityLost | 启用 | 结束为 CAPABILITY_LOST，标缺失 | 进入恢复流程；默认停用，用户修复后主动开启 |

必须遵守的不变量：

- 每个主用户最多一个 OPEN Session、每个 Session 最多一个 OPEN Segment。
- 一次会话只允许 RESTRICTED → FULL 一次；锁屏后才可创建下一次。
- `enabled=false` 永远优先于广播；没有用户 EnableRequested 就不能变回 true。
- Activity `onPause/onStop` 不等于手机锁定。
- FULL 中管理页、通知弹层和系统权限 UI 不切回 RESTRICTED。
- 来源事件按串行 reducer 消费；重复提交按钮、重复广播、并发 Worker 不得双写。

### 3.1 崩溃一致性与策略命令

Room 事务无法和 Android 系统策略组成一个原子事务。实现 `PolicyCommand` outbox：先落盘命令及转换状态，调用幂等适配器，查询实际策略，再提交业务转换。命令带 UUID、controlVersion、目标策略版本；旧回调不能覆盖新版本。

释放限制成功但提交 FULL 前崩溃：恢复时查待执行释放命令，验证已释放后补交意图与阶段边界。不可丢失到“手机已开放但仍显示成功限制”。限制实施失败：不标为已限制，普通版标降级，受管版进入恢复页。

暗号关闭优先落盘“停止记录”时间与禁用意图，再释放策略；释放失败不能声称已关闭成功，保留专用恢复界面及重试。完成后持久 DISARMED。恢复过程绝不重新开始统计。

## 4. 会话与统计口径

### 4.1 会话定义

Session 起点是 Tip 启用后的有效解锁/重新进入可交互状态；若当时才手动启用，则从启用时刻开始。结束于下一次锁定、非交互、关闭 Tip、关机/重启或必要能力失效。

“上一次”指当前会话之前最近的已结束 Session，包含仅限制使用的会话；不取“最近一个输入过意图的会话”，也不拿当前会话覆盖它。

示例：08:00 解锁 → 地图限制使用 2 分钟 → 08:02 输入“查题” → 浏览器 3 分钟 → 08:05 锁屏。保存一个 5 分钟会话、两个阶段，下一次 Gate 展示地图 2 分钟与浏览器 3 分钟，而不是只展示后 3 分钟。

### 4.2 明确的指标

- `sessionDurationMs`：会话起止区间时长；重启不跨 boot 计算。
- `restrictedDurationMs/fullDurationMs`：两个阶段的区间时长，包含该阶段 Gate/桌面停留。
- `appForegroundMs`：被 UsageEvents 支持的前台 Activity 使用时长；不表示读屏、实际注意力、后台音乐或屏幕内某功能时长。
- `gateAndTipMs`、`systemUiMs`、`unknownMs` 单列，不强行分摊到最后一个 App。
- 默认主列表仅显示实际识别到的应用，统计对象不限于白名单；普通版检测到绕过后的非白名单使用也记录并标“限制阶段内使用”。
- 原始毫秒入库；UI 小于一分钟显示秒，其余显示分秒。汇总用未舍入值计算，避免列表相加失真。

多窗口/PiP 不声称得到真实焦点：采用“最近一次 RESUMED 的候选应用独占归因”作为 MVP 估计口径；只在明确 PAUSED/STOPPED/锁屏边界上截断，候选冲突/无法确认的区间标 AMBIGUOUS 或 UNKNOWN，不对两个应用重复累计。报告展示“分屏时长为估计值”。统计切片应构成不重叠时间线；未知时间保留。

### 4.3 UsageStats 事件重建

UsageStats 是查询能力，不是全局应用切换监听服务。`queryEvents(begin,end)` 用于重建事件；不要用日级 `totalTimeInForeground` 相减冒充精确会话明细。系统事件保留时间有限，长时间未回补可能无法恢复；首次重启解锁前查询也可能不可用。[官方：UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager)

工程算法：

1. 查询窗口限定在本地启用区间，分块（建议每块 6 小时）；默认回看游标前 2 分钟接收迟到事件。当前/最近结束会话可完整重算，较旧回补最多尝试 72 小时，此数值是本项目上限，不是系统保留承诺。
2. 收集 `ACTIVITY_RESUMED/PAUSED/STOPPED`、`KEYGUARD_SHOWN/HIDDEN`、`SCREEN_INTERACTIVE/NON_INTERACTIVE`、`DEVICE_SHUTDOWN/STARTUP`。事件能力按 API 与设备核验。[官方：UsageEvents.Event](https://developer.android.com/reference/android/app/usage/UsageEvents.Event)
3. 将本地意图提交、启停、Gate 可见区间和系统事件合并为时间线。先处理明确结束边界，再处理同时间戳的状态开启；保留同时间戳原始事件顺序。同时间戳包/类切换无法判定时标歧义。
4. 不逐条累加旧结果。读取窗口起点前最近 checkpoint，重建受影响会话的全部切片，在一个事务中删除旧派生结果并替换，从而使回放幂等。无可信 checkpoint 则未知开头不能凭空补给第一款应用。
5. 原始系统事件没有通用可靠唯一 ID；可用 `(bootId,timestamp,type,package,class,instanceId,同签名序号)` 规范化。序号在完整查询结果内按顺序生成；遇到重叠窗口身份不稳定时重建窗口，不假定哈希永不冲突。
6. 维护 Activity 级候选状态，再归并到 package；同 App 内 Activity 跳转不重复计时。每个切片与 Session、Segment 做区间交集。
7. 锁屏/关闭/关机即截断全部开放切片。PAUSED 缺失时可在下一个明确前台事件截断，但区间质量标 ESTIMATED；无法确定的空白归 UNKNOWN。
8. 若服务不在，下一次 Gate 出现时从历史事件补出中间的多个解锁会话，按最近结束时间显示上一轮；未观察到的边界不能虚构为精确。无法可靠重建时关闭遗留会话为 INTERRUPTED，并展示数据不完整。

每次 Gate 恢复、管理页打开及用户启停前后触发回补。统计结果先展示缓存，异步更新；短暂事件迟到可安排进程内一次延迟重查及一次唯一补算任务，不无限忙轮询。

### 4.4 时钟与进程死亡

本地事件同时保存 Unix 毫秒、`elapsedRealtime()`、bootId。当前 boot 的实时持续时间优先使用单调时钟；UsageEvents 时间戳按其 wall time 解释，不能跨重启拿 elapsedRealtime 相减。

时间/时区变化只改变展示或创建 ClockAnchor；若手动调时导致时间线不连续，切断相关不确定区间并标 `CLOCK_CHANGED`，不简单取负数绝对值。不允许把关机期间计入使用。

进程被杀不等于会话结束；有可信历史事件时恢复原会话。强制停止后不得承诺继续采集或自动运行；用户再次打开时根据可读事件回补并提示中断。

## 5. Room 数据模型

核心业务状态与统计统一存 Room，避免 enabled 在 DataStore 而活动 Session 在 Room 造成跨存储不一致。DataStore 只存主题等非关键 UI 偏好。Room 负责实体、DAO、事务和迁移；它本身不提供数据库加密。[官方：Room](https://developer.android.com/training/data-storage/room)

统一约定：ID 使用 UUID/String；时间为 Long 毫秒；enum 以稳定字符串保存；区间使用 `[start,end)`；外键显式索引。以下为逻辑 schema，Agent 需落地 Entity/DAO/Migration 并导出 schema。

| 表 | 字段及约束 |
|---|---|
| `TipControl` | singletonId=1 PK；onboardingStep；whitelistConfirmed；enabled；mode(CONSUMER/MANAGED)；state；health；controlVersion；activeSessionId nullable FK；disabledAt nullable；disableReason nullable |
| `WhitelistEntry` | `(userSerial,packageName)` 联合 PK；preferredComponent nullable；labelCache；selectedAt；available；不把 label 当身份 |
| `WhitelistRevision` | revision Long PK；createdAt；保存每次提交版本 |
| `WhitelistRevisionEntry` | `(revision,userSerial,packageName)` PK，revision FK；历史快照，版本引用期间不能删除 |
| `Session` | id PK；userSerial；bootId；startWallMs；startElapsedMs nullable；endWallMs/endElapsedMs nullable；status(OPEN/CLOSED/INTERRUPTED)；intentText nullable；intentSubmittedAt nullable；endReason nullable；quality；qualityReasons；durationMs；statsRevision；createdAt |
| `SessionSegment` | id PK；sessionId FK CASCADE；kind(RESTRICTED/FULL)；startWallMs；endWallMs nullable；start/endElapsedMs nullable；whitelistRevision FK nullable（限制阶段必填）；durationMs |
| `UsageSlice` | id PK；sessionId、segmentId FK CASCADE；start/endWallMs；durationMs；packageName nullable；userSerial；labelSnapshot nullable；category(APP/TIP/SYSTEM/UNKNOWN)；quality；source；wasAllowlisted nullable |
| `SessionAppSummary` | `(sessionId,packageName,phase)` PK；durationMs；labelSnapshot；quality；由切片派生，可重建；未知时间另从 Slice 汇总 |
| `LocalEvent` | id PK；controlVersion；bootId；wallMs；elapsedMs nullable；type；sessionId nullable；非敏感 payload；意图仅存在 Session 或待提交命令，不重复写入通用日志 |
| `UsageCheckpoint` | `(userSerial,bootId)` PK；queriedThroughWallMs；anchorWallMs/anchorElapsedMs；replayState；schemaVersion |
| `PolicyCommand` | id PK；controlVersion；type；targetPolicyRevision；status(PENDING/APPLIED/FAILED)；requestedAt；appliedAt nullable；pendingIntentText nullable；errorCode nullable；成功或最终失败后清除敏感 payload |
| `AppliedPolicy` | singletonId PK；desiredRevision；verifiedRevision；lockTaskObserved；ownedChangesJson；lastVerifiedAt；用于恢复本 App 施加的策略 |
| `WeatherCache` | cityId PK；cityName；temperatureC nullable；conditionCode；observedAt；fetchedAt；provider；状态；不存精确位置历史 |

`SecretCredential` 使用应用私有独立存储，字段为算法、salt、workFactor、verifier、版本；不放进历史导出或备份。不是普通明文设置项。

DAO 至少提供：`observeControl()`、`observePreviousClosedSession(currentId)`、`createSessionIfAbsent()`、`switchToFull()`、`closeSessionIfOpen()`、`replaceWhitelist()`、`replaceDerivedTimeline()`、`claimPendingPolicyCommand()`、`deleteHistoryBefore()`。所有跨表改变必须 `withTransaction`。

单活动会话约束由 singleton control + 事务检查保证；可添加部分唯一索引约束 OPEN Session/Segment，并在建库与迁移中一致创建验证。Session.start、Session.end、UsageSlice.sessionId/start、PolicyCommand.status/requestedAt 建索引。不要 destructive migration；迁移测试覆盖已有 OPEN 会话、待执行关闭命令、空历史。

数据保留默认：会话/明细 90 天、临时原始回放数据 7 天，界面可清空历史；清理不能删除当前会话、待执行命令或仍被引用的白名单快照。清空历史不关闭 Tip，开启状态下应从清空时刻重置当前统计边界，避免回补把已删除历史重新导入。

## 6. 模块职责与接口

### 6.1 启动器与路由

分开 `HomeActivity`（MAIN/HOME/DEFAULT）与 `ManagementActivity`（MAIN/LAUNCHER）。HOME 入口永不隐式启用；图标入口进入管理流程。管理 Activity 在每次恢复和状态变更时校验访问，不只在首次 onCreate 检查。

consumer 使用 `RoleManager.ROLE_HOME`、`isRoleAvailable/isRoleHeld` 和用户确认的角色请求。不能静默替换用户桌面。[官方：RoleManager](https://developer.android.com/reference/android/app/role/RoleManager)

通过 LauncherApps/PackageManager 枚举当前主用户可启动 Activity，应用级去重，启动前重新解析；优先记录组件和包名。声明匹配 `MAIN + LAUNCHER` 的 `<queries>`，不默认申请 `QUERY_ALL_PACKAGES`。包可见性过滤与 UsageEvents 统计权限是不同问题，统计中无法解析名称时显示缓存名称或包名，不丢记录。[官方：包可见性](https://developer.android.com/training/package-visibility/declaring)

### 6.2 限制适配器

```kotlin
interface RestrictionController {
    suspend fun capability(): RestrictionCapability
    suspend fun applyRestricted(command: PolicyCommand): PolicyResult
    suspend fun releaseOwnedRestrictions(command: PolicyCommand): PolicyResult
    suspend fun inspectActualPolicy(): ObservedPolicy
}

interface UsageRepository {
    suspend fun reconcile(untilWallMs: Long): ReconcileResult
    fun previousSession(currentSessionId: String?): Flow<SessionReport?>
}
```

`ConsumerRestrictionController` 只限制自身启动入口；不得用 UsageStats 高频轮询抢前台来伪装系统拦截。`ManagedRestrictionController` 放在独立 flavor/module 中；运行前检查 `isDeviceOwnerApp`。

managed 实施计划：

- `setLockTaskPackages()` 配置 Tip 与当前白名单；必要系统组件单独列为经过验证的系统例外，不偷偷扩充用户白名单。
- 为限制期间的 Home、系统 Keyguard 等设定明确 `setLockTaskFeatures` 配置；必须保留真实锁屏与来电/紧急功能。不要为了“全屏”禁用系统安全锁。
- Gate 在适当任务中进入 Lock Task；启动白名单应用使用平台支持的受管任务路径，验证真实 `LOCK_TASK_MODE_LOCKED`，屏幕固定状态不算成功。
- FULL 转换由拥有该策略的组件解除 Tip 的 Lock Task。跨 App 当前任务下的退出、白名单清除后任务处理、Gate 控制任务保留均在 M0 验证。不得假定任意 Activity 都能替另一任务 `stopLockTask()`。
- 锁屏时重新进入限制是单独能力：即便 FULL 退出了 Lock Task，也必须有可存活/可恢复的事件接收与合法前台入口来再次施加限制。DPC 的存在不自动实现这一步。
- 专用设备可设置持久 HOME 关联，但必须记录 Tip 自己施加的变更并提供恢复。若启用包暂停等额外策略，须单独审查系统例外与回滚，MVP 不把它作为默认实现。

官方 API 路线依据：[Lock Task](https://developer.android.google.cn/work/dpc/dedicated-devices/lock-task-mode?hl=en)、[专用设备 Home 配置](https://developer.android.com/work/dpc/dedicated-devices/cookbook)。

### 6.3 系统事件与后台执行

监听层合并三类证据：进程存活时动态接收屏幕广播；HOME/管理 Activity 恢复时查询 `PowerManager.isInteractive` 与 `KeyguardManager.isKeyguardLocked`；回补 UsageEvents 历史。

`SCREEN_ON` 不等于解锁。`SCREEN_OFF/ON` 必须动态注册，不能只写 Manifest 就宣称持久在线。注册/注销符合生命周期；系统广播与包广播按目标 SDK 的接收器规则配置。[官方：Intent 屏幕事件](https://developer.android.com/reference/android/content/Intent#ACTION_SCREEN_OFF)

`BOOT_COMPLETED` 仅恢复标志及安排允许的补算，先看 enabled；首次用户解锁前不读凭据加密区数据库。MVP 不把 Room 迁到 Direct Boot 存储。关机广播可能丢失，重启通过 bootId 修复。屏幕广播、UsageEvents 和本地事件会重复表达同一边界，必须幂等归并。

后台启动 Activity 有系统限制；广播到达不代表能够弹出 Gate。普通版恢复不到 Gate 时，下一次 Home 展示 Gate，并在允许且已授权时发普通通知供用户点击。不能用全屏通知冒充闹钟/来电，也不能声明特殊权限绕过限制。[官方：Activity 后台启动限制](https://developer.android.com/guide/components/activities/secure-bal)

consumer 默认无需常驻前台服务，靠系统事件回溯统计。managed 若要提高实时锁屏恢复可靠性，M0 评估合规前台服务与设备管理例外：使用匹配实际用途的服务类型；没有适用类型就报告阻塞，不伪报 mediaPlayback/location/dataSync。`specialUse` 如适用需给出用途说明和发布审核依据，不能视为必然获批。服务被系统/用户停止仍需恢复验证。[官方：FGS 类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[官方：后台启动 FGS 限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

WorkManager 只用于天气、清理、延迟统计回补；周期任务最短间隔及调度延迟决定它不适合监听解锁或秒级拦截。[官方：WorkManager 调度](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)

### 6.4 时间与天气

Android 没有可直接作为本产品通用数据源的内置天气查询 API。工程定义 `WeatherProvider.fetch(cityId)`，联网源与 UI 解耦。

MVP：手动选城市，不申请定位；接口返回城市、摄氏温度、天气码、观测时间、来源。M0 选定一个可在目标地区访问且许可适用的数据提供方，记录官方 API、署名要求、额度和密钥部署方式；没有可用凭据时保留真实“天气暂不可用”状态，禁止把 mock 天气当上线数据。

刷新：Gate 打开且缓存超过 30 分钟时异步刷新，最长等待 5 秒；缓存超过 2 小时标“更新于…”，超过 24 小时不展示为当前天气。可用网络约束的每小时唯一 WorkManager 任务更新；失败指数退避、尊重服务端限流。天气失败不影响输入与白名单。收费密钥不能硬编码在 APK，必要时使用最小代理服务；意图和应用历史不发给天气服务。

## 7. 权限与数据保护

| 权限/角色 | 何时使用 | 拒绝或撤销的处理 |
|---|---|---|
| `PACKAGE_USAGE_STATS` 特殊访问 | 解释用途后跳转 Usage Access 设置，返回检查 AppOps | 首次不开启；运行中进入能力丢失恢复，不把缺失数据显示为 0 |
| 默认 HOME 角色 | consumer 开启前 | 未获授则保留设置；撤销后不再宣称限制有效 |
| `INTERNET`、`ACCESS_NETWORK_STATE` | 天气联网 | 离线缓存/不可用 |
| `POST_NOTIFICATIONS`（适用版本） | 需要提醒或实际使用 FGS 时按需申请 | 普通统计可运行但提醒不可达；不能声称通知一定展示 |
| `RECEIVE_BOOT_COMPLETED` | 启动后修复与安排合法工作 | 不构成自动启用授权 |
| FGS 及对应类型权限 | 仅经过验证的受管增强路径 | 无合适合法路径则关闭对应能力 |
| Device Owner | managed 部署前置，非普通权限 | 未配置时拒绝进入强限制模式 |

MVP 不申请 Accessibility、悬浮窗、读取通知、通讯录、短信、通话记录、精确定位、后台定位、所有文件访问、精确闹钟权限。启动电话 App 不等于读取通话记录，使用明确的外部启动入口即可。

暗号：MVP 用随机 salt（至少 16 字节）+ PBKDF2-HMAC-SHA256；初始工作因子建议 600,000，M0 在低端机测量后确定版本化参数，目标后台线程校验约 200–500 ms。常量时间比较，不存明文，不用普通 SHA256 单次哈希。必要加密材料由 Android Keystore 管理；不声称 Keystore 能保护已经被用户控制的整个设备。[官方：Android Keystore](https://developer.android.com/privacy-and-security/keystore)

暗号按设置时同一 Unicode NFC 规范化后精确比较，不 trim 暗号；设置时拒绝首尾空格以避免混淆。普通意图仅在确认不匹配暗号后才 trim 保存。用户忘记暗号时可以输入普通意图进入 FULL，再在管理页通过系统凭据确认重置；无系统安全锁时采用明确的本机确认流程。此产品是自律工具，暗号不是防止设备所有者绕过的安全边界。

数据库在应用私有凭据加密目录；备份规则排除意图、使用历史和暗号材料，避免换机恢复后带回启用状态。release 日志不输出意图、暗号或包名轨迹；诊断报告只含系统版本、能力状态、时间误差和匿名错误码。导出历史只能由用户明确发起。

## 8. 降级与恢复规则

| 情况 | 行为与用户可见结果 |
|---|---|
| 普通版解锁恢复原 App | 保持下一次 Home 的 Gate 逻辑；若可读取事件则记录真实使用，标“部分系统入口无法限制” |
| 通知/最近任务/分享跳转绕过 | consumer 记录后标记；managed 作为强限制失败用例，不用文案掩盖 |
| 使用权限被撤销 | 截断可确认统计，标部分数据缺失；能力失效停用并让用户修复后手动启用 |
| 默认桌面改变 | consumer 停用限制状态并提示；不强行重新夺回 HOME |
| 进程死亡/厂商清理 | 回补可获取窗口；缺失明确标注，绝不将锁屏期间计给最后 App |
| 电池限制 | 提供针对设备的可选设置说明；不自动要求关闭所有省电策略 |
| 白名单应用卸载/禁用 | 标不可用并移除可点入口；旧历史保留名称；下一版白名单提交更新 revision |
| 应用更新或组件变化 | 重新解析启动组件；不把不可启动当成取消权限 |
| 没有可用白名单应用 | 文件夹显示空态；输入意图仍可进入完整模式 |
| 分屏、PiP、系统 UI 不可识别 | 估计或 UNKNOWN，报告提示，不制造精确值 |
| 天气离线/拒绝城市设置 | 缓存带时间或“天气未设置”，Gate 仍立即可用 |
| 策略释放失败 | 显示恢复页、重试和已验证的设备恢复入口；不能宣称暗号关闭已完成 |
| 数据损坏或迁移失败 | 不无限循环 Gate；进入 RECOVERY_REQUIRED，尝试释放自有策略，允许明确重建本地数据 |
| 重启后首次解锁前 | 尊重系统锁屏，延迟数据库读取；是否能及时恢复 Gate 单独列入设备能力结果 |

紧急电话、来电、系统安全锁等必要系统路径必须验证可用。managed 的恢复流程包括开发阶段可用的测试设备管理命令和交付时可用的本机恢复操作，记录在随 APK 提供的部署说明中；不把用户困在失败的 Gate。生产版本不默认限制恢复出厂、卸载或安全启动来追求不可绕过。

## 9. 工程目录与依赖方向

```text
tip-android/
├── app/                         # Application、DI、flavors、Manifest
│   └── src/{main,consumer,managed}/
├── core/
│   ├── model/                   # 状态、事件、报告类型
│   ├── domain/                  # reducer、会话边界、统计纯算法
│   ├── database/                # Room、DAO、schema、migration
│   ├── platform/                # 时钟、系统信号、角色、包查询
│   ├── security/                # 暗号校验与敏感数据排除
│   └── designsystem/            # 主题及公共组件
├── feature/
│   ├── onboarding/
│   ├── gate/
│   ├── launcher/
│   ├── settings/
│   └── history/
├── data/
│   ├── usage/                  # 查询、回补、报表 repository
│   └── weather/                # provider、缓存、worker
├── enforcement/
│   ├── api/
│   ├── consumer/
│   └── managed/                # DPC、策略命令、恢复
├── testing/
│   ├── fixtures/               # 去标识事件轨迹
│   └── testapps/               # A/B Activity、跳转与分屏测试应用
├── docs/
│   ├── capability-matrix.md
│   ├── architecture-decisions.md
│   ├── deployment-managed.md
│   └── acceptance-report.md
└── gradle/libs.versions.toml
```

小团队可先按上述 package 分层，仅将 `core:domain` 与两个限制实现拆 Gradle 模块；不要为目录数量牺牲实现速度。UI → domain/use cases → repositories/interfaces；Android API 留在 adapters，domain 不依赖 Context。通过 DI 注入 Clock、UsageEventSource、RestrictionController、WeatherProvider，方便确定性测试。

Agent 必须锁定依赖版本，记录 JDK/Gradle/AGP/Kotlin/Compose/Room 兼容组合。每个 flavor 都要能编译；consumer 的合并 Manifest 不应包含 DPC 或没有使用的 FGS 声明。测试 mock 只在 debug/test，不进入 release 的实际天气或统计路径。

## 10. 测试计划

### 10.1 确定性单元测试

| 编号 | 输入 | 必须结果 |
|---|---|---|
| U01 | 开启→限制 A 120s→意图→B 180s→锁屏 | 一会话两阶段、A=120s/B=180s、总计300s |
| U02 | 解锁→仅白名单→锁屏→再解锁 | Gate 展示无意图的上一次会话 |
| U03 | 同一事件窗口回放两次/重叠回补 | Session、切片、时长完全一致 |
| U04 | 同包多 Activity、重复 resume/pause | 不双计，不产生负时长 |
| U05 | 重复锁屏/解锁广播、点击提交两次 | 不创建重复会话或意图 |
| U06 | 使用中进程死亡，之后拿到完整事件 | 恢复原会话并准确截断 |
| U07 | 无 pause、缺解锁、事件保留不足 | 标质量问题，不制造完整报告 |
| U08 | 暗号→重启/HOME/worker | enabled 仍 false，关闭后切片为零 |
| U09 | FULL 改白名单 | 当前 FULL 不变、历史快照不变、下次限制用新版本 |
| U10 | 时间回拨、跨午夜/时区、重启 | 不负值、不跨 boot 累计关机时长 |
| U11 | 分屏重叠与 PiP | 时间线不重叠且标估计，未知可解释 |
| U12 | 策略命令每一步注入崩溃 | 恢复幂等，过期命令不能重新启用 |
| U13 | 清空历史后回补 | 已删区间不重新生成 |

### 10.2 Room 与界面测试

- 迁移前后暗号关闭状态、OPEN 会话和 outbox 完整；外键和单 OPEN 约束生效。
- 首次安装必须先确认白名单，取消权限不启用；退出重进恢复引导位置。
- Gate 空意图无效；白名单文件夹、上次详情和键盘返回顺序正确；无退出按钮。
- FULL 管理页可改白名单；限制状态恢复旧管理 Activity/外部入口也不能编辑。
- 暗号不进入 Session.intentText、Logcat、崩溃附件或导出；错误但非空文本作为普通意图处理。
- 大字体、横竖屏、深浅色、软键盘、TalkBack 可读性测试；这里支持用户辅助功能，不意味着依赖 AccessibilityService。
- 天气失败、历史很长、无历史、名称解析失败时主按钮仍可用。

### 10.3 真机能力与验收矩阵

至少覆盖 API 29、31、33、34、35、36，以及 M0 选定的当前稳定最高 API（如超出上述范围）；较旧 API 可模拟器，目标用户的真实手机必须实测。设备至少包括 AOSP/Pixel 类、一台主流国产系统、一台低内存设备；managed 只对实际验证设备声明强限制。

每台记录型号、系统 build、targetSdk、导航方式、系统锁配置、前台服务配置、省电状态、结果及录屏。不能只写“Android 支持”。

必测入口：桌面、最近任务、通知、通知快捷操作、分享面板、深链、白名单 App 内外链、相机锁屏入口、输入法设置入口、来电、紧急呼叫、系统权限页、多窗口/PiP、Home/返回手势、卸载/更新、撤权限、强停、重启、杀进程、息屏后快速亮屏、未设 PIN、延迟锁定。

managed 连续至少 50 轮“FULL 在第三方 App→锁屏→解锁→Gate→白名单→意图→FULL”循环；任何出现非白名单可交互窗口均记失败，不能仅凭 Gate 后来覆盖判成功。测试使用专用测试设备，恢复策略步骤随测试一并验证。

性能目标（项目目标，非平台保证）：

- 中端测试机从 HomeActivity 开始显示到 Gate 首屏 p95 ≤ 500ms；不将这个指标冒充“系统解锁到 Gate”时间。
- 正常 30 分钟事件窗口回补 p95 ≤ 2s；大历史分块，不阻塞主线程。
- 完整单窗口测试轨迹中，每 App 时长误差 ≤ max(2s, 真实时长的 2%)；缺失/分屏轨迹不套用精确指标。
- 同条件对照测试后台额外耗电目标 ≤ 2 个百分点/8h；实测超出必须调整，不能用轮询换取表面及时性。
- Room I/O、网络、暗号 KDF 均不运行在主线程；无 ANR、无持续唤醒锁。

## 11. 实现里程碑与完成条件

| 阶段 | 工程任务 | 交付与准出条件 |
|---|---|---|
| M0 可行性先行 | 建最小 HOME/UsageStats/DPC 原型；核验 SDK、设备恢复、Lock Task 进出和每次解锁 Gate；选天气源 | capability-matrix、录屏、部署/恢复说明、锁定依赖。明确普通版边界。强限制链路失败必须作为阻塞记录，不得默默降规格 |
| M1 数据与状态 | Room、reducer、Clock、统计重建、outbox、暗号存储 | U01–U13 核心算法与迁移检查通过；进程重启不丢 enabled/会话 |
| M2 普通安装完整流程 | 引导、白名单、Gate、完整抽屉、管理页、暗号、历史 | consumer 可安装 APK；所有产品交互可实机走通，明确降级标识 |
| M3 统计与系统恢复 | UsageStats 接入、回补、系统信号、质量标记、包变更、天气 | 两阶段统计误差达标；撤权、强停、重启有真实结果；天气用真实源或明确 unavailable |
| M4 受管强限制 | DPC 策略适配、锁屏重施加、释放、故障恢复 | 在目标设备完成50轮循环与所有绕过入口检查；策略释放/紧急路径通过；不符合即不发布“强限制支持”声明 |
| M5 交付 | 性能、兼容性、隐私、构建与验收文档 | APK、源码、可复现构建说明、测试报告、已知限制、managed 部署恢复文档齐全 |

工程 Agent 执行顺序：先完成 M0 验证和事实记录，再实现 M1–M3；M4 只在已验证可行路径上推进。可以继续完成不依赖受管阻塞的普通版功能，但不得把它作为严格原需求的全部完成。本文不要求额外 AI 分析、云同步、社交功能或付费系统。

## 12. 最终验收清单

- [ ] 新安装按“保存白名单→设置暗号及权限→用户开启”完成，不自动开启。
- [ ] Gate 全屏且没有应用内退出按钮，四个内容区域齐全，文件夹只显示可用白名单入口。
- [ ] 未填意图使用白名单产生使用记录；普通版绕过数据也不遗漏或隐藏。
- [ ] 输入意图后同一会话切 FULL，全部可启动 App 入口可用，继续记录前台使用。
- [ ] FULL 可修改白名单，下次限制采用新列表，旧记录不改变。
- [ ] 下一次 Gate 展示上一次已结束会话，包括纯限制会话与两阶段会话。
- [ ] 暗号关闭不保存为意图，释放 Tip 策略，关闭后的使用不计入；重启不恢复启用。
- [ ] 用户必须手动打开 Tip 管理页并点开启，才重新启用。
- [ ] Room 数据完整、迁移可行、事件回放幂等；缺失统计有明确质量标记。
- [ ] 无 Accessibility 核心依赖，无伪造系统权限、后台弹屏或常驻服务能力。
- [ ] managed 在指定设备真正限制非白名单入口并通过锁屏后恢复测试；consumer 报告中明确此项不满足。
- [ ] 权限拒绝、天气失败、进程死亡、强停、重启、策略失败均按降级规则处理。
- [ ] 交付报告逐项标 PASS / FAIL / UNSUPPORTED，附证据；没有真机证据的项目不写“已验证”。

完成定义：不以“页面能点通”替代系统行为验收，不以“App 时长看起来合理”替代确定性统计测试。最终向产品方说明交付的是普通自律版、已验证受管版，还是仍存在强限制阻塞的版本。
