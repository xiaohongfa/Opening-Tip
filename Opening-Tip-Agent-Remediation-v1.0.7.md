# Opening-Tip 全项目修复任务书（给 Coding Agent）

> 审计对象：`xiaohongfa/Opening-Tip`  
> 静态审计基线：`main @ 774875fe0bcbc04aeab91d88ca08741852d28999`（v1.0.7）  
> 审计日期：2026-09-13  
> 目标：先修复安全、状态一致性和真实运行链路，再处理性能、文档与工程质量。  
> 本任务书基于完整仓库树的静态审计；**真机/OEM 行为与 APK 构建结果仍需你执行验证。**

---

## 0. 执行总则

你是负责修复此 Android 项目的 Coding Agent。不要只修表象 UI，也不要把 README/验收报告当作真实实现；以当前源码行为、Android 平台约束和可重复测试为准。

开始前必须：

```bash
git status
git rev-parse HEAD
git log -5 --oneline
```

若当前 HEAD 不再是：

```text
774875fe0bcbc04aeab91d88ca08741852d28999
```

先执行：

```bash
git diff 774875fe0bcbc04aeab91d88ca08741852d28999..HEAD -- .
```

重新审查所有被后续提交改过的相关区域，然后再套用本任务书。不要覆盖用户在基线之后的新修改。

### 不可破坏的产品约束

1. 保持 **100% 本地运行**：不要新增 `INTERNET` 权限、云端 API、遥测、埋点或远程配置。
2. 用户数据、意图、使用统计默认仅保存在本机。
3. 不得为了“防逃逸”加入阻止卸载、偷偷修改系统设置、绕过系统安全控制等行为。
4. Accessibility 只能做用户明确知情并授权的自律辅助；必须保留系统层面的关闭/卸载能力。
5. 不要自动生成并提交新的 Release 私钥、JKS、密码或密钥材料。
6. 修复优先级按 **P0 → P1 → P2**；P0 未完成前不要把精力花在视觉微调上。
7. 每个高风险修改都要配测试；不要用“看起来能工作”替代状态不变量验证。

---

# 1. P0：立即处理

## OT-P0-001：Release 签名材料已经泄漏

### 现状

仓库当前包含：

```text
tip-android/app/openingtip.jks
```

同时：

```text
tip-android/app/build.gradle.kts
```

将 Release keystore 密码/alias 密码直接写在源码中，并且 Debug 也使用 Release signing config。

`.gitignore` 也没有忽略 `*.jks` / `*.keystore` / 本地 signing properties。

### 风险

这把私钥必须视为 **已泄漏**。仅删除当前文件不能撤销已经进入 Git 历史或被 clone 的副本。

### Agent 要做

1. 从当前工作树移除 `openingtip.jks`。
2. 删除 Gradle 中所有硬编码密码。
3. Debug 恢复 Android 默认 debug signing，不再使用 Release key。
4. Release signing 改为读取：
   - CI secret / environment variables；或
   - 本地、被 `.gitignore` 排除的 `keystore.properties`。
5. `.gitignore` 至少增加：
   ```gitignore
   *.jks
   *.keystore
   keystore.properties
   signing.properties
   ```
6. README 增加“本地 Release 签名配置”说明，但绝不写真实秘密。
7. 增加 secret scan / CI 检查，至少阻止 JKS、keystore、常见 signing password 再次提交。

### 不要擅自做

不要简单生成一把新 key 然后假装完成密钥轮换，因为这可能破坏已安装 APK 的升级链路。

需要在修复报告里明确提示仓库所有者：

- 若从未向外分发过使用该 key 签名的 APK：发布前换新 key。
- 若已有真实用户安装该签名 APK：必须根据实际分发方式制定签名迁移方案（例如 Play App Signing / 平台支持的 key rotation / 重新分发策略）。
- Git 历史清洗只能降低未来暴露，不能让已泄漏私钥重新变安全。

### 验收

- Git 当前树不含私钥文件。
- `git grep` 找不到 signing 密码。
- Debug 可以不依赖任何私有密钥编译。
- Release 在未提供 signing secret 时明确失败或跳过签名，而不是退回硬编码密钥。
- CI 能阻止新的 key material 进入仓库。

---

## OT-P0-002：修复 Accessibility 主路径导致的“Gate 有 UI、Session 没建立”

### 现状

`GateGuardService.launchGateActivityInternal()` 当前逻辑：

```kotlin
val a11y = TipAccessibilityService.instance
if (a11y != null) {
    a11y.launchGateDirectly()
    return
}
```

而 `startGuardWatcher()` 与 `ensureActiveSession()` 在这个 `return` 后面。

结果：推荐开启 Accessibility 后，正好跳过 Session 初始化和 watcher 启动。

`GateActivity` 随后可能：

- `activeSessionId == null`
- 仍调用 `GateGuardService.markSessionUnlocked()`
- 仍把 `tip_control.state` 写成 `FULL`
- 但没有合法 Session / FULL segment

这会破坏项目最核心的“开屏 → 会话 → 意图 → 统计”链路。

### Agent 要做

不要只把 `return` 删除了事。把“会话准备”和“UI 拉起方式”拆开：

```text
unlock observed
    ↓
ensure / recover canonical active session (transaction)
    ↓
state == RESTRICTED and activeSessionId valid
    ↓
start watcher
    ↓
choose launch transport:
    accessibility / normal startActivity / notification fallback
```

必须保证：

1. **任何 Gate 展示路径之前**，数据库已经存在唯一、有效的 OPEN Session。
2. `tip_control.activeSessionId` 必须引用真实存在的 Session。
3. Accessibility 只是“Activity launch transport”，不能改变业务状态机。
4. 重复 SCREEN_ON / USER_PRESENT / Accessibility window event 不能重复创建 Session。
5. 并发事件下仍最多一个 OPEN Session。

### 验收测试

新增测试覆盖：

- Accessibility connected + unlock → 创建且仅创建一个 OPEN Session。
- `activeSessionId` 对应真实 row。
- 重复解锁事件 10 次 → 仍只有一个 OPEN Session。
- Accessibility disconnected 路径得到完全相同的业务状态，只是 Activity 拉起渠道不同。

---

## OT-P0-003：消灭 phantom session / 原子化 Session 创建

### 现状

`SessionDao.createSessionIfAbsent()`：

- 先查已有 OPEN Session。
- 有就不 insert。
- 无则 insert 新 Session。

但多个调用方随后无条件执行：

```kotlin
updateActiveSessionId(newlyGeneratedId)
```

因此如果数据库已经有一个 OPEN Session，调用方生成的新 UUID 可能根本没有 insert，却被写进 `tip_control.activeSessionId`。

受影响路径至少包括：

- `GateGuardService.ensureActiveSession()`
- `ManagementActivity.enableTipModeFirstTime()`
- `ManagementActivity.enableTipModeExisting()`

### Agent 要做

建立一个**唯一的 Session orchestration transaction**，例如：

```kotlin
@Transaction
suspend fun getOrCreateRestrictedSession(...): ActiveSessionResult
```

它必须：

1. 查询现有 OPEN Session。
2. 若存在，返回其真实 ID，并修复 control 指针。
3. 若不存在，创建 Session + initial segment。
4. 在同一个事务内写 `tip_control.activeSessionId/state`。
5. 返回数据库最终采用的 canonical sessionId，而不是调用方事先猜测 ID。
6. 最好增加数据库层面的唯一性保护，避免多个 OPEN Session。
   - SQLite partial unique index 可通过 migration 手工创建；
   - 或建立等价的事务约束与一致性自检。

### 验收

构造数据库中已有 OPEN Session A，然后再次请求创建 B：

- 不产生 B；
- `activeSessionId == A.id`；
- 不出现悬空引用；
- 并发两次请求最终也只有一个 OPEN Session。

---

# 2. P1：核心运行可靠性与安全

## OT-P1-001：生产路径与 `SessionStateMachine` 是两套状态机

### 现状

仓库中已经有：

```text
core/domain/SessionStateMachine.kt
core/domain/PolicyOutboxCoordinator.kt
enforcement/consumer/ConsumerRestrictionController.kt
enforcement/managed/ManagedRestrictionController.kt
```

以及 U01~U13 测试。

但真实 App 主路径仍在以下类中直接修改 Room 和内存状态：

```text
GateGuardService
GateActivity
ManagementActivity
```

因此：

- 单元测试验证的是“设计状态机”；
- App 真正运行的是另一套手写状态机；
- 两者已经明显漂移。

例如 consumer controller 文档宣称“不采用高频轮询抢前台”，但实际 `GateGuardService` 就在轮询 UsageStats 并强拉 Gate。

### Agent 要做

选一个真源，不允许继续双轨。

推荐方案：

建立 `SessionCoordinator` / `TipRuntimeCoordinator`：

```text
Android event
  → DomainEvent
  → SessionStateMachine
  → transactional persistence
  → policy/outbox side effects
  → UI/service effect
```

至少把这些事件统一进入 coordinator：

- enable
- unlock
- screen off / noninteractive
- intent submit
- secret submit
- whitelist launch
- process restore
- capability lost

如果短期无法完整接入纯领域状态机，那么就反过来：

- 删除/标记未使用的“伪生产”状态机与测试；
- 为真实 Room/service orchestration 建集成测试。

但不能继续让“测试全绿”代表一套未被 App 使用的模型。

---

## OT-P1-002：提交意图时先内存放行、后数据库落盘，失败时会失配

### 现状

`GateActivity.handleGateSubmission()` 在数据库操作之前就调用：

```kotlin
GateGuardService.markSessionUnlocked()
```

随后才：

- switch segment
- 更新 Session intent
- 更新 `tip_control.state`
- 启动 timer

若 Room 操作失败/进程被杀：

```text
内存 isSessionUnlocked = true
数据库仍可能是 RESTRICTED
```

用户会被临时放行，但持久状态错误。

### Agent 要做

改成“持久化成功 → 再产生放行 side effect”。

理想顺序：

```text
transaction:
  validate active session
  close RESTRICTED segment
  create FULL segment
  persist intent + target
  set control FULL
commit success
↓
markSessionUnlocked()
start timer
finish Gate
```

暗号关闭同样如此：

```text
transaction commit DISARMED
↓
stop service / release restrictions
```

失败必须：

- 保持 Gate；
- 保持 RESTRICTED；
- 给出可恢复错误，不得 silent unlock。

---

## OT-P1-003：v1.0.7 新增永久白名单旁路

### 现状

v1.0.7 新增：

```kotlin
currentWhitelistedPkg
```

点击白名单应用后赋值。

`isWhitelistedAppLaunching()` 只把 1500ms 作为“启动过渡期”，但是：

```kotlin
if (pkg == currentWhitelistedPkg) return true
```

这个判断没有时间限制，而且 `currentWhitelistedPkg` 没有在过渡结束后清空。

后果：

- 某 App 曾经作为白名单被启动一次；
- 即使 1500ms 已结束；
- 即使后续白名单已删除该 App；
- 在 Service 存活期间，它仍可能一直被 `isPackageAllowedWhileLocked()` 放行。

### Agent 要做

不要把“当前启动中的包”当长期权限。

推荐：

```kotlin
data class LaunchGrace(
    val packageName: String,
    val expiresAtElapsedMs: Long
)
```

判断必须同时满足：

```text
pkg == grace.packageName
AND now <= grace.expiresAt
```

并在：

- 超时
- screen off
- session close
- whitelist revision change
- service restart

时清理。

### 验收

- 启动白名单 App 后 1500ms 内允许冷启动过渡。
- 1500ms 后，如果该包不在正式白名单，则不能继续凭 grace 放行。
- 从白名单删除后，下一个 RESTRICTED Session 立即使用新策略。
- 不允许上一会话的 `currentWhitelistedPkg` 泄漏到下一会话。

---

## OT-P1-004：白名单 revision 设计存在，但真实历史语义没有实现

### 现状

数据库已经有：

```text
whitelist_revision
whitelist_revision_entry
SessionSegmentEntity.whitelistRevision
```

但实际：

1. 初次/重新开启时，segment 经常硬编码：
   ```kotlin
   whitelistRevision = 1L
   ```
2. `saveUpdatedWhitelist()` 用当前时间创建新 revision。
3. `UsageStatsRepository.reconcileSession()` 重建历史时读取的是：
   ```kotlin
   database.whitelistDao().getWhitelistPackageNames()
   ```
   即“现在的白名单”，不是该 segment 当时记录的 revision。

结果：修改白名单后，旧会话的 `wasAllowlisted` 会被新白名单重新解释。

### Agent 要做

1. `TipControl` 或独立配置表持久化 `activeWhitelistRevision`。
2. 每次 replaceWhitelist 返回/保存新的 revision。
3. 创建 RESTRICTED segment 时写入真实当前 revision。
4. DAO 增加：
   ```kotlin
   getPackagesForRevision(revision: Long)
   ```
5. Reconciler 对每个 RESTRICTED segment 使用其自己的 revision 集合。
6. `SessionSegment.whitelistRevision` 应考虑建立 FK/完整性约束，至少保证 referenced revision 存在。
7. FULL segment 不需要白名单语义时可为 null。

### 验收

- Session A 使用 revision 10。
- 后续白名单改为 revision 11。
- 重新 reconcile Session A 后，`wasAllowlisted` 仍按 revision 10，结果不变。

---

## OT-P1-005：UsageStats 重建链路疑似未接到真实 App 生命周期

### 现状

`UsageStatsRepository` 能：

- query `UsageEvents`
- 调 `UsageTimelineReconciler`
- 写 `usage_slice`
- 写 `session_app_summary`

但静态扫描没有发现当前 App 主路径明确调用 `reconcileSession()`。

`GateActivity` 的“正在整理”逻辑实际上只：

- 读取已有 `SessionSegment`
- 读取已有 `SessionAppSummary`
- 组装 UI

没有看到它主动触发 UsageStats reconciliation。

### 风险

如果没有其他遗漏的动态调用：

- `session_app_summary` 可能长期为空/旧；
- README 宣称的“前台应用 Top3/真实使用时长”无法可靠产生；
- 测试中的 reconciler 与实际用户数据脱节。

### Agent 要做

先全局 grep 复核：

```bash
rg "UsageStatsRepository|reconcileSession|replaceDerivedTimeline" tip-android
```

若确认未接入：

推荐在 **Session close 后** 异步调度 reconciliation，并在下次 Gate 展示前做幂等补偿。

注意：

- 不要在 Activity 主线程做长时间 UsageStats 查询。
- 进程死亡后仍需能恢复。
- 可以用 WorkManager 做非即时统计回补；如果保持纯前台也可由 coordinator 在 IO scope 中完成。
- 必须基于历史 whitelist revision，而不是当前 whitelist。

### 验收

真实流程：

```text
unlock → whitelist app A → intent → app B → screen off → next unlock
```

下一 Gate 必须能看到：

- RESTRICTED / FULL 分段；
- A/B 各自时长；
- Top 3；
- 总时长；
- 重跑 reconciliation 不重复累计。

---

## OT-P1-006：Room destructive migration 与“数据主权”承诺冲突

### 现状

`TipDatabase`：

```kotlin
version = 5
exportSchema = false
...
fallbackToDestructiveMigration()
```

### 风险

schema version 升级而 Migration 缺失时，用户历史可被直接删库重建。

### Agent 要做

1. 删除 `fallbackToDestructiveMigration()`。
2. `exportSchema = true`。
3. 配置 schema 导出目录并纳入版本控制。
4. 为之后的每次 schema 变更写显式 `Migration`.
5. 若本次为 timer/session 增字段，建议数据库升到 v6，并提供 `MIGRATION_5_6`。
6. 增加 Room migration tests，至少从当前生产 v5 → 新版本。
7. 禁止用 destructive migration 作为“测试方便”的生产兜底。

### 验收

准备 v5 数据库包含：

- sessions
- todos
- whitelist revisions
- secret 文件（独立）

升级后全部数据仍在，且新字段使用合理默认值。

---

## OT-P1-007：专注倒计时只存在 Service 内存，重启/杀进程即丢失，并存在漂移

### 现状

`GateGuardService` 用：

```kotlin
currentTimerRemainingSeconds--
delay(1000)
```

倒计时状态只在 service 内存。

### 风险

- Service/进程被系统杀死后，timer 丢失。
- `START_STICKY` 恢复 service 也不知道之前倒计时。
- doze / scheduler delay 会造成累计漂移。
- 这与项目自己强调的“防杀/恢复”目标冲突。

### Agent 要做

不要持久化“每秒剩余值”，持久化 **deadline**。

建议至少保存：

```text
timerStartedWallMs
timerStartedElapsedMs
timerDeadlineWallMs
timerDeadlineElapsedMs
timerBootId
timerStatus
```

运行时每次 UI 更新：

```text
remaining = deadlineElapsed - SystemClock.elapsedRealtime()
```

而不是 `remaining--`。

进程恢复：

- 同一 boot：以 elapsedRealtime deadline 为准。
- 跨 boot：elapsedRealtime 不可复用，使用 wall deadline + quality downgrade 或按产品定义结束旧 session。
- stop/extend 必须落盘并原子更新。

### 验收

- 启动 5 分钟 timer，60 秒后杀进程，20 秒后恢复：剩余应约 220 秒，而不是重新 300 秒。
- app 在后台停调度 30 秒后回来：时间不能“暂停”。
- `+1分钟` 更新持久 deadline。
- screen off 正确结束 timer。

---

## OT-P1-008：Exact Alarm / Full Screen Intent 必须能力检测和降级

### 现状

Manifest 声明：

```text
SCHEDULE_EXACT_ALARM
USE_FULL_SCREEN_INTENT
```

Service 直接调用：

```kotlin
setExactAndAllowWhileIdle(...)
```

并直接构造 FullScreenIntent。

targetSdk = 35。

### Agent 要做

#### Exact Alarm

Android 12+：

```kotlin
if (alarmManager.canScheduleExactAlarms()) {
    ...
} else {
    // safe fallback
}
```

此项目的“1 秒自愈”并不是用户闹钟/日历核心场景，优先考虑：

- 不依赖 exact alarm；
- inexact alarm / normal process recovery / boot / accessibility reconnect；
- 若确实保留 special access，UI 必须真实检测并解释。

不得让缺权限产生 `SecurityException`。

#### Full Screen Intent

Android 14+：

```kotlin
notificationManager.canUseFullScreenIntent()
```

不可用时降级到普通 high-priority notification / Accessibility 已授权路径，不得宣称“100% 穿透”。

不要把自律提醒伪装成电话/闹钟资格来规避平台限制。

官方参考：

- https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- https://developer.android.com/about/versions/14/behavior-changes-14

---

## OT-P1-009：BootReceiver 的异步工作生命周期错误

### 现状

`BootReceiver.onReceive()` 中直接：

```kotlin
CoroutineScope(Dispatchers.IO).launch { ... }
```

然后 `onReceive()` 返回。

Android 不保证 Receiver 返回后进程继续活着。

### Agent 要做

短操作使用：

```kotlin
val pendingResult = goAsync()
scope.launch {
    try {
        ...
    } finally {
        pendingResult.finish()
    }
}
```

更长/可延迟工作交给 WorkManager/JobScheduler。

同时测试 Android 对 `BOOT_COMPLETED` 后启动 FGS 的实际限制与异常处理。

### 验收

- receiver 的 async work 总能 `finish()`；
- 异常路径也释放 PendingResult；
- boot 时若 mode disabled，不启动 service；
- enabled 时恢复不产生重复 Session。

---

## OT-P1-010：Onboarding 权限说明与当前实现事实相反

### 现状

Onboarding Intro 仍写：

```text
不索取任何无障碍...
```

但当前 Manifest 和核心防逃逸流程明确使用 AccessibilityService。

StepPermissions 只展示：

- Usage access
- Overlay

没有在 onboarding 中正确披露/引导 Accessibility。

最后一步却写：

```text
必要权限已核验通过
```

而“下一步”始终 `enabled = true`。

此外 `ManagementActivity` 当前传：

```kotlin
isManagedMode = false
```

所以 managed flavor/Device Owner 也不会在 onboarding 中真实呈现。

### Agent 要做

先决定产品策略：

### 方案 A（推荐，兼容普通侧载/Play 合规设计）

- Accessibility 作为明确、可选但强推荐的增强能力。
- 用户不授权时进入 `DEGRADED` capability，不要说 0ms/不可逃逸。
- Onboarding 内给出明确功能说明和单独同意。
- 如果将来上 Google Play，非残障辅助用途需按 AccessibilityService policy 做显著披露、affirmative consent 和 Play declaration。

### 方案 B（如果业务坚持必需）

- StepPermissions 必须真实检测 Accessibility。
- 未授权不得显示“必要权限已核验通过”。
- UI 清楚说明具体访问范围、用途、可关闭方式。
- 仍不得阻止用户在系统设置关闭服务。

同时：

- `isManagedMode` 必须由 flavor + DevicePolicyManager 实际状态确定。
- 不要硬编码 false。

Google Play 官方说明：
https://support.google.com/googleplay/android-developer/answer/10964491

---

## OT-P1-011：Managed flavor 当前并没有真正进入 Lock Task

### 现状

`ManagedRestrictionController` 只调用：

```kotlin
setLockTaskPackages(...)
setLockTaskFeatures(...)
```

静态扫描没有发现真实运行路径调用：

```kotlin
startLockTask()
stopLockTask()
```

`deployment-managed.md` 却声称 GateActivity 已执行这些操作。

同一文档还声称存在 `RecoveryActivity`，但当前源码树没有该 Activity。

### 结果

当前 managed 相关文档描述的是“计划架构”，不是已完成事实。

### Agent 要做

二选一：

#### A. 真正完成 managed

- flavor 能真实注入 `ManagedRestrictionController`。
- Device Owner 检测通过后，RESTRICTED 时：
  - setLockTaskPackages
  - setLockTaskFeatures
  - GateActivity `startLockTask()`
  - 验证 `LOCK_TASK_MODE_LOCKED`
- FULL / DISARM 时可靠 `stopLockTask()`。
- 崩溃恢复要能 inspect + reconcile actual policy。
- 实现真实 recovery flow，或者删除所有关于 `RecoveryActivity` 的描述。
- 必须在 factory-reset / Device Owner 真机上验证。

#### B. 暂停 managed 宣称

若没有资源做真机验证：

- 标记为 experimental / unverified；
- 不允许 acceptance report 写 READY；
- README 不宣称已支持强系统级隔离。

---

## OT-P1-012：`QUERY_ALL_PACKAGES` 是 v1.0.7 新增的高风险权限，且很可能不需要

### 现状

v1.0.7 Manifest 新增：

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

但 Manifest 已存在：

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

项目主要需要展示/启动 launcher apps，并不明显需要读取“所有已安装包”。

### Agent 要做

优先移除 `QUERY_ALL_PACKAGES`，验证：

- launcher app enumeration
- launch intent fallback
- launcher detection
- whitelist selector

全部仍可工作。

只有在确实存在 scoped queries 无法满足的核心功能时才保留，并记录理由。

Google Play 将 broad package visibility 视为敏感能力：
https://support.google.com/googleplay/android-developer/answer/10158779

---

## OT-P1-013：GateActivity 不应对外 exported

### 现状

Manifest：

```xml
<activity
    android:name=".ui.GateActivity"
    android:exported="true"
    ... />
```

它没有需要外部调用的 intent-filter。

### 风险

任意第三方 App 可尝试显式启动 GateActivity，扩大攻击/骚扰入口。

### Agent 要做

若无明确外部 API 需求：

```xml
android:exported="false"
```

内部 Service / AccessibilityService 同包启动不受影响。

再审查其他 exported 组件，遵循最小暴露原则。

---

## OT-P1-014：暗号 KDF 实际只有 10,000 次，ADR 写的是 600,000

### 现状

`SecretManager` 默认：

```kotlin
defaultIterations = 10_000
```

生产 `ManagementActivity` 使用默认构造。

但 `architecture-decisions.md` 明确写：

```text
PBKDF2WithHmacSHA256 = 600,000 iterations
```

### Agent 要做

1. 将新凭据默认成本提升到合理生产值；可按设备性能 benchmark，600k 是当前 ADR 目标。
2. Credential 已有 `iterations` + `version`，利用它做渐进升级：
   - 老凭据仍能验证；
   - 验证成功后可 rehash 为新成本。
3. 测试环境显式传低 iterations，不要把测试值变成生产默认。
4. 不要记录暗号原文。

---

# 3. P2：稳定性、性能、工程质量

## OT-P2-001：高频轮询成本过高

当前存在：

- PowerManager/Keyguard 约 350ms polling。
- RESTRICTED foreground app 约 300ms UsageEvents polling。

Accessibility 已提供 window state events，应当事件驱动为主，polling 只做低频 watchdog。

目标：

- Accessibility/SCREEN events = primary.
- watchdog 降到秒级甚至更低频，仅检测异常失联。
- 用 profiler / battery historian 比较修复前后。

---

## OT-P2-002：Gate 每次观察全部历史，数据量增长后会越来越重

`GateActivity` 当前：

```text
observeAllSessions()
observeAllAppSummaries()
```

然后在 Compose 内 group/map 全历史。

建议：

- “上一次会话”独立查询 `LIMIT 1`。
- “今日数据”走 SQL aggregate。
- 历史中心分页（Paging 3 或 limit/offset/cursor）。
- 不要为了 Gate 首页加载数千条历史。

---

## OT-P2-003：Gate 顶部时钟是静态的

`HeaderSection`：

```kotlin
val now = remember { Date() }
```

只在首次 composition 取时间，Gate 停留期间不会更新。

改成 lifecycle-aware ticker（例如每 1s 或每分钟更新），并避免不必要的整页 recomposition。

---

## OT-P2-004：文档严重漂移，必须以代码事实重写

至少存在这些错误：

### README

- 当前代码 v1.0.7，但 README badge/开发者区仍 v1.0.6。
- 宣称 Accessibility `PERSISTENT_PROC`、杀不掉、0ms、100% 等绝对保证。
- 宣称 Apache-2.0，但根目录没有 LICENSE 文件。

### `docs/acceptance-report.md`

A10 仍写：

```text
无 AccessibilityService
无 SYSTEM_ALERT_WINDOW
无常驻保活服务
```

与当前 Manifest 完全相反。

### `docs/capability-matrix.md`

仍描述 consumer：

```text
默认 HOME / RoleManager.ROLE_HOME
无无障碍服务
无悬浮窗
```

而当前实现已不是这套。

### `docs/deployment-managed.md`

声称：

- GateActivity 已 startLockTask()
- FULL 已 stopLockTask()
- 存在 RecoveryActivity

当前代码不支持这些断言。

### Agent 要做

文档必须绑定版本/commit，例如：

```text
Validated against: <commit sha>
```

未真机验证的能力写：

```text
NOT VERIFIED / EXPERIMENTAL
```

不要写 `PASS` / `READY` 除非有对应自动测试或真机证据。

---

## OT-P2-005：仓库存在未编入 App 的联网天气死代码

`data/weather/OpenMeteoWeatherProvider` 使用 `HttpURLConnection` 请求 Open-Meteo。

当前 app sourceSet 没有包含 `data/weather`，Manifest 也没有 INTERNET，因此当前 APK 仍可保持 offline。

但这段代码：

- 与 README“纯离线”审计目标冲突；
- 文档里又说天气离线降级；
- 容易被未来开发者误接入。

若产品已经决定纯离线：

- 删除该模块；或
- 移到明确的实验/非默认 flavor，并确保默认产物无 INTERNET。

---

## OT-P2-006：所谓“多模块”大部分只是目录，不是 Gradle module

`settings.gradle.kts` 只 include：

```text
:app
:core:domain
```

而 `app/build.gradle.kts` 通过大量：

```kotlin
kotlin.srcDirs(...)
```

把 core/model、database、platform、security、feature、enforcement 直接编进 app。

后果：

- Gradle 无法约束模块依赖；
- consumer/managed 源也同时进入 main compile graph；
- 看起来模块化，实际上是单模块共享源码。

短期不必为了“漂亮架构”大拆，但需二选一：

- 真正拆 Android/Kotlin Gradle modules；或
- 简化目录，承认单 app 模块。

优先级低于状态一致性修复。

---

## OT-P2-007：Release lint 被禁用，且没有 CI

当前：

```kotlin
lint {
    checkReleaseBuilds = false
    abortOnError = false
}
```

仓库没有 `.github/workflows`。

### Agent 要做

建立最小 CI：

```bash
./gradlew :core:domain:test
./gradlew :app:lintConsumerDebug
./gradlew :app:assembleConsumerDebug
./gradlew :app:assembleManagedDebug
```

如果 Release signing 需要 secret，不要为了 CI 再把 key 放进 repo；CI 可先验证 unsigned/debug 产物。

修掉现有 lint 问题后：

- 恢复合理的 lint gate。
- 至少禁止新增 fatal/error lint。

---

## OT-P2-008：Usage reconciler 的“幂等”测试没有验证稳定 ID

`UsageTimelineReconciler` 每次为 `UsageSlice` 生成随机 UUID。

U03 虽名为“Replay Idempotency”，但只比较：

- package
- duration
- start/end

没有比较 slice ID。

由于持久化策略是 delete + rewrite，这不一定导致统计错误，但“完全一致/确定性回放”的文档说法过强。

二选一：

1. 使用稳定、可推导 ID（session/segment/start/end/package hash）；或
2. 明确幂等定义为“业务等价”，更新测试与文档。

不要继续声称 byte-for-byte / row-identity deterministic。

---

## OT-P2-009：导出元数据版本硬编码 + 导出文件残留策略

`DataExportManager` JSON：

```text
"version": "1.0.0"
```

当前 App 已是 v1.0.7。

改为从 `BuildConfig.VERSION_NAME` / package info 获取。

同时：

- 导出文件包含完整个人意图和使用明细；
- 分享后文件会留在 app external files `exports/`。

增加：

- “删除历史导出”入口，或
- 成功分享后可选 cleanup；
- 文档明确导出内容敏感。

---

# 4. 文档/设计与代码之间的额外不一致

这些不一定单独阻塞发布，但在修复过程中必须一起消除：

1. `architecture-decisions.md` 要求所有状态变更事务保护，但真实 Activity/Service 直接多步写 DB。
2. ADR 要求 600k PBKDF2，真实生产默认 10k。
3. capability matrix 说 consumer 不依赖 Accessibility/overlay，实际依赖。
4. acceptance report 说 A10 无 Accessibility，实际存在。
5. managed 部署指南写了不存在的 RecoveryActivity。
6. ConsumerRestrictionController 说“不采用高频轮询抢前台”，真实 Service 正在轮询并 reassert Gate。
7. README 的 v1.0.6 已落后于 v1.0.7。
8. README License badge 指向不存在的 `LICENSE`。
9. “100%/0ms/杀不掉/PERSISTENT_PROC”属于无法由第三方 App 保证的表述，应改为“best effort + capability/fallback”。

---

# 5. 推荐重构目标结构

不要一次性大重写 UI；先把核心状态收拢。

推荐逻辑：

```text
System / UI Event
      │
      ▼
TipRuntimeCoordinator
      │
      ├── SessionStateMachine
      │
      ├── Room transaction repository
      │
      ├── UsageReconciliationScheduler
      │
      └── RestrictionController
      │
      ▼
Committed Runtime State
      │
      ├── Gate launch effect
      ├── Accessibility/notification fallback
      ├── Focus timer
      └── UI StateFlow
```

关键原则：

```text
DB state is authoritative.
Service fields are cache/effects, never the source of truth.
```

例如：

```text
isSessionUnlocked
currentWhitelistedPkg
timerRemaining
```

都不应成为不可恢复的业务真源。

---

# 6. 必须补的测试清单

## 单元 / Room 集成

### T01 Accessibility unlock session invariant

Accessibility 已连接时解锁：

```text
exactly 1 OPEN session
activeSessionId exists
exactly 1 open RESTRICTED segment
```

### T02 Existing open session recovery

已有 OPEN A，再触发 enable/unlock：

```text
no phantom B
activeSessionId == A
```

### T03 Intent atomicity

注入 DB failure：

```text
state remains RESTRICTED
isSessionUnlocked must not become true
Gate remains visible
```

### T04 v1.0.7 launch grace expiration

1500ms 后：

```text
temporary package permission expires
```

### T05 whitelist revision history

改变当前 whitelist 后重算旧 Session：

```text
old wasAllowlisted unchanged
```

### T06 Room migration

v5 → new version：

```text
all user data preserved
```

### T07 timer process recovery

kill/restart 后 deadline 继续，而不是重新计时。

### T08 exact alarm denied

`canScheduleExactAlarms() == false`：

```text
no crash
safe fallback
```

### T09 full-screen intent denied

```text
no crash
fallback works
```

### T10 BootReceiver lifecycle

`goAsync()` 每条路径都 finish。

### T11 managed real lock task

仅在真实 Device Owner 设备：

```text
RESTRICTED → LOCK_TASK_MODE_LOCKED
FULL/DISARM → exits safely
```

### T12 exported surface

第三方测试 App 无法直接打开 GateActivity。

### T13 PBKDF upgrade

旧 10k credential：

- 可以验证；
- 验证后升级新工作因子；
- 新 credential 不降级。

### T14 UsageStats real integration

Session close 后可生成 slices + summaries，重复执行不累计。

### T15 permission truth

撤销 Usage / Accessibility / overlay 等能力：

- UI 不显示假“已授权”；
- runtime 进入明确 degraded 状态；
- 不出现死循环强弹。

---

# 7. 真机验收矩阵

静态测试不能替代以下验证。

最低 Android：

- API 29
- API 31
- API 34
- API 35

至少一台：

- Pixel/AOSP 类设备
- Xiaomi / HyperOS 真机

核心流程每台都测：

```text
fresh install
→ onboarding
→ permissions granted/denied combinations
→ enable
→ screen off
→ unlock
→ gate
→ launch whitelist app
→ return
→ submit intent
→ timer
→ screen off
→ next unlock report
→ process kill
→ force stop
→ reboot
→ permission revoke
→ whitelist edit
→ data export
→ wipe all data
```

额外检查：

- 双击/重复广播
- 输入法切换
- 来电
- 权限弹窗
- Recents/Home gesture
- deep link
- notification click
- PiP/split screen（设备支持时）
- 省电模式
- 后台限制
- 无 Exact Alarm special access
- 无 Full Screen Intent access

---

# 8. 构建与质量门槛

最终至少要求：

```bash
cd tip-android

./gradlew clean
./gradlew :core:domain:test
./gradlew :app:testConsumerDebugUnitTest
./gradlew :app:lintConsumerDebug
./gradlew :app:lintManagedDebug
./gradlew :app:assembleConsumerDebug
./gradlew :app:assembleManagedDebug
```

若某个 task 名因 AGP/flavor 实际名称不同，先：

```bash
./gradlew :app:tasks
```

确定正确任务后记录到 README/CI。

不要声称 Release 已验证，除非：

- 使用安全的外部 signing secret；
- release build 真实成功；
- lint/test 通过；
- 没有把 key 写回仓库。

---

# 9. 建议执行顺序

严格按这个顺序，避免边修边制造新状态：

### Phase 0 — 安全止血

1. OT-P0-001 signing secret cleanup。
2. 建立基础 CI / secret scan。

### Phase 1 — 数据与状态一致性

3. OT-P0-002 Accessibility/Session 初始化。
4. OT-P0-003 canonical session transaction。
5. OT-P1-002 intent/disarm 原子提交。
6. OT-P1-001 统一 runtime coordinator/state machine。

### Phase 2 — 历史数据真实性

7. OT-P1-004 whitelist revision。
8. OT-P1-005 UsageStats reconciliation 接入。
9. OT-P1-006 Room migration。

### Phase 3 — Runtime resilience

10. OT-P1-007 timer deadline persistence。
11. OT-P1-008 ExactAlarm/FSI capability fallback。
12. OT-P1-009 BootReceiver。
13. OT-P1-003 launch grace bypass。

### Phase 4 — 权限与发行

14. OT-P1-010 onboarding/accessibility disclosure。
15. OT-P1-012 remove QUERY_ALL_PACKAGES if possible。
16. OT-P1-013 exported components。
17. OT-P1-014 PBKDF。
18. OT-P1-011 managed mode：实现或降级声明。

### Phase 5 — 工程质量

19. polling/performance。
20. history pagination + clock。
21. docs/README/license。
22. dead weather code。
23. Gradle/module cleanup。
24. export metadata/cleanup。

---

# 10. Agent 提交规则

每一阶段使用独立 commit，建议：

```text
security: remove committed release signing material
fix: make active session creation atomic
refactor: route runtime transitions through session coordinator
fix: expire whitelist launch grace
fix: preserve whitelist revisions during usage reconciliation
db: add non-destructive room migration
fix: persist focus timer deadline across service restart
fix: add Android capability checks and receiver lifecycle handling
policy: align onboarding and accessibility disclosures
managed: wire real lock task lifecycle
perf: reduce polling and paginate history
docs: align claims with verified runtime capabilities
ci: add test lint and debug build gates
```

每个 commit 必须：

- 编译；
- 对应测试通过；
- 不把私钥/密码带回；
- 不新增 INTERNET；
- 不修改不相关 UI；
- 在 commit message 或 PR 描述中写清“为什么”。

---

# 11. Definition of Done

只有同时满足以下条件，才能称为“本轮修复完成”：

- Release private key / plaintext password 不再存在于当前源码。
- `activeSessionId` 永远不会指向不存在的 Session。
- Accessibility 开启/关闭不会改变 Session 业务语义。
- 任何时刻最多一个 OPEN Session。
- intent submit 不会出现“内存已放行、数据库未提交”。
- v1.0.7 temporary whitelist grace 不会变永久旁路。
- 历史 UsageStats 按历史 whitelist revision 解释。
- Room 升级不再 destructive。
- focus timer 可恢复且基于 deadline，不按 `delay(1000)` 累计误差。
- Exact Alarm / Full Screen Intent 权限缺失不崩溃。
- BootReceiver 异步工作生命周期正确。
- Onboarding 不再声称“不使用 Accessibility”。
- `QUERY_ALL_PACKAGES` 被移除，或有明确、经过审核的不可替代理由。
- GateActivity 不再无必要 exported。
- managed 模式要么真实进入 Lock Task 并有真机证据，要么明确标记未完成。
- PBKDF 生产成本与 ADR 一致，老凭据可迁移。
- UsageStats reconciliation 在真实用户链路中确实执行。
- CI 跑测试 + lint + consumer/managed debug build。
- README / capability matrix / acceptance report 与当前代码一致。
- 添加真实 `LICENSE`，或删除虚假的 Apache-2.0 声明。
- 默认产物继续没有 `INTERNET` permission。
- Xiaomi/HyperOS 与至少一台 AOSP 设备完成手工验收并记录结果。

---

# 12. 审计中确认的重点文件

Agent 应优先阅读：

```text
README.md

tip-android/app/build.gradle.kts
tip-android/settings.gradle.kts
tip-android/app/src/main/AndroidManifest.xml
tip-android/app/src/consumer/AndroidManifest.xml
tip-android/app/src/managed/AndroidManifest.xml

tip-android/app/src/main/kotlin/com/openingtip/TipApplication.kt
tip-android/app/src/main/kotlin/com/openingtip/service/BootReceiver.kt
tip-android/app/src/main/kotlin/com/openingtip/service/GateGuardService.kt
tip-android/app/src/main/kotlin/com/openingtip/service/TipAccessibilityService.kt
tip-android/app/src/main/kotlin/com/openingtip/service/FloatingTimerManager.kt
tip-android/app/src/main/kotlin/com/openingtip/ui/GateActivity.kt
tip-android/app/src/main/kotlin/com/openingtip/ui/ManagementActivity.kt
tip-android/app/src/main/kotlin/com/openingtip/core/platform/SystemPackageHelper.kt

tip-android/core/database/src/main/kotlin/com/openingtip/core/database/TipDatabase.kt
tip-android/core/database/src/main/kotlin/com/openingtip/core/database/dao/Daos.kt
tip-android/core/database/src/main/kotlin/com/openingtip/core/database/entity/Entities.kt

tip-android/core/domain/src/main/kotlin/com/openingtip/core/domain/SessionStateMachine.kt
tip-android/core/domain/src/main/kotlin/com/openingtip/core/domain/PolicyOutboxCoordinator.kt
tip-android/core/domain/src/main/kotlin/com/openingtip/core/domain/UsageTimelineReconciler.kt
tip-android/core/domain/src/test/kotlin/com/openingtip/core/domain/DeterministicTests.kt

tip-android/data/usage/src/main/kotlin/com/openingtip/data/usage/UsageStatsRepository.kt
tip-android/data/weather/src/main/kotlin/com/openingtip/data/weather/WeatherProvider.kt

tip-android/enforcement/consumer/src/main/kotlin/com/openingtip/enforcement/consumer/ConsumerRestrictionController.kt
tip-android/enforcement/managed/src/main/kotlin/com/openingtip/enforcement/managed/ManagedRestrictionController.kt
tip-android/enforcement/managed/src/main/kotlin/com/openingtip/enforcement/managed/TipDeviceAdminReceiver.kt

tip-android/feature/onboarding/src/main/kotlin/com/openingtip/feature/onboarding/OnboardingScreen.kt
tip-android/feature/gate/src/main/kotlin/com/openingtip/feature/gate/GateScreen.kt
tip-android/feature/gate/src/main/kotlin/com/openingtip/feature/gate/HistoryCenterDialog.kt
tip-android/feature/settings/src/main/kotlin/com/openingtip/feature/settings/ManagementScreen.kt

tip-android/docs/acceptance-report.md
tip-android/docs/architecture-decisions.md
tip-android/docs/capability-matrix.md
tip-android/docs/deployment-managed.md
```

---

## 最后要求

修复结束后，不要只回复“已完成”。

输出一份 `REMEDIATION_REPORT.md`，至少包含：

```text
1. 基线 commit / 最终 commit
2. 每个 OT-* 项的状态：FIXED / DEFERRED / NOT APPLICABLE
3. 关键设计变更
4. 数据库 migration 说明
5. 自动测试命令与结果
6. consumer/managed build 结果
7. 真机测试矩阵与结果
8. 尚未解决的 OEM/Android 限制
9. 权限清单及每项用途
10. 是否仍含 QUERY_ALL_PACKAGES / FSI / exact alarm / accessibility，以及理由
11. signing key 处置状态（不得写出秘密）
12. `git diff --stat` 与最终已知风险
```

如果任何 P0/P1 项无法安全完成，明确标记 `DEFERRED` 并解释原因，不得用文档措辞把未完成能力包装成已完成。
