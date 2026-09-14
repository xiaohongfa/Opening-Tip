# Opening-Tip 私人使用版整改说明（给 Coding Agent）

> 项目：`xiaohongfa/Opening-Tip`  
> 使用场景：**仅个人自用 / 私人侧载，不面向公开发布或应用商店分发**  
> 当前判断：项目整体已经可用，不需要按商业 App 或公开发布标准全面整改。优先处理会直接影响个人使用稳定性、数据正确性与长期可维护性的部分。

---

## 1. 总体原则

本轮整改不要追求“大而全”。

对于私人使用场景，以下事项可以暂时降级处理：

- Release Key 泄漏后的完整商业发布迁移方案
- Google Play 合规
- 完整 CI/CD
- Release 混淆
- README 宣传文案严格性
- 多人协作规范
- 完整模块化重构
- 商业级安全审计

重点只处理：

1. 会话状态正确性
2. 数据不丢失
3. 倒计时恢复能力
4. 使用统计真实有效
5. 明显耗电问题

目标是：

> **私人长期使用时稳定、数据可信、升级不丢数据。**

---

## 2. 最高优先级：Session 状态原子化

### 当前风险

Gate 展示、Session 创建、意图提交之间仍可能存在竞态。

可能出现：

```text
Gate 已显示
↓
用户快速提交意图
↓
activeSessionId 仍为空 / Session 尚未准备完成
↓
switchToFull() 未真正完成
↓
control.state 被写成 FULL
↓
GateGuardService.markSessionUnlocked()
↓
用户被放行
```

最终表现可能是：

- 手机已经进入正常使用状态
- 但数据库里没有合法 FULL Session
- 本次意图没有正确进入历史
- 后续统计出现异常

### 必须整改

不要让 UI 自己拼多步状态。

建议新增统一事务，例如：

```kotlin
@Transaction
suspend fun submitIntentAndEnterFull(
    intentText: String,
    targetDurationMinutes: Int?
): Result
```

事务内部至少完成：

```text
1. 校验当前 activeSessionId
2. 校验对应 Session 存在且 OPEN
3. 找到当前 RESTRICTED segment
4. 关闭 RESTRICTED segment
5. 创建 FULL segment
6. 写入 intentText
7. 写入 targetDurationMinutes
8. 更新 tip_control.state = FULL
9. commit
```

只有事务成功以后，才执行：

```kotlin
GateGuardService.markSessionUnlocked()
startFocusTimer(...)
finish()
```

### 失败时要求

如果事务失败：

```text
不得 markSessionUnlocked()
不得 finish Gate
不得进入 FULL
```

应继续停留在 Gate，并允许用户重试。

### 建议测试

至少覆盖：

```text
- activeSessionId == null
- Session 不存在
- RESTRICTED segment 不存在
- DB 操作异常
- 用户快速连续点击提交
- unlock 与 submit 同时发生
```

---

## 3. 第二优先级：Session 创建必须有唯一真源

### 当前问题

Session 创建和 control.activeSessionId 的更新分散在 Service / Activity 中。

这类结构很容易出现：

```text
数据库中的真实 Session = A
activeSessionId = B
```

或者：

```text
已有 OPEN Session
但又触发一次创建流程
```

### 建议整改

新增一个统一入口：

```kotlin
@Transaction
suspend fun getOrCreateRestrictedSession(): String
```

要求：

```text
- 如果已有 OPEN Session：
  返回真实 Session ID

- 如果没有：
  创建 Session + RESTRICTED segment

- 同时修复 tip_control.activeSessionId

- 最终只返回数据库真正采用的 canonical sessionId
```

Service 不应自己猜 Session ID。

### 核心原则

```text
Room 数据库 = 权威状态
Service 内存字段 = 缓存 / 执行状态
```

不要反过来。

---

## 4. 第三优先级：禁止 destructive migration

### 当前风险

如果数据库继续使用：

```kotlin
fallbackToDestructiveMigration()
```

以后升级 schema 时漏写 Migration，可能直接：

```text
删除旧数据库
↓
重新创建
↓
历史会话、Todo、白名单等数据丢失
```

对于个人长期使用，这是比很多发布级问题更值得修的。

### 建议整改

移除：

```kotlin
fallbackToDestructiveMigration()
```

改为：

```kotlin
exportSchema = true
```

以后数据库每次升级都提供显式：

```kotlin
Migration(x, y)
```

至少增加：

```text
v5 -> 下一版本
```

的 migration test。

---

## 5. 第四优先级：倒计时状态持久化

### 当前状态

现在倒计时使用：

```kotlin
SystemClock.elapsedRealtime()
```

计算 deadline，这一点是正确的，可以避免普通 delay 漂移。

但 timer 的关键状态仍主要存在 Service 内存里。

### 风险

进程被系统杀掉：

```text
GateGuardService 消失
↓
Service 之后恢复
↓
不知道之前 timer 的 deadline
↓
倒计时丢失
```

### 建议保存

至少持久化：

```text
sessionId
timerStartedWallMs
timerStartedElapsedMs
timerDeadlineWallMs
timerDeadlineElapsedMs
timerTotalSeconds
timerIntentText
timerStatus
```

恢复逻辑：

```text
同一次 boot：
    优先使用 elapsedRealtime deadline

跨 boot：
    使用 wall clock deadline
    或直接结束旧 timer
```

不要每秒写数据库。

只需要在：

```text
start
extend
stop
screen off
session close
```

这些事件写入。

---

## 6. 第五优先级：确认 UsageStats 真正接入运行链路

项目已经存在：

```text
UsageStatsRepository
UsageTimelineReconciler
session_app_summary
usage_slice
```

但需要确认真实 App 生命周期是否真的调用了：

```kotlin
reconcileSession()
```

### Agent 先执行

```bash
rg "UsageStatsRepository|reconcileSession|replaceDerivedTimeline" tip-android
```

如果确认没有真正接入：

建议在：

```text
screen off
↓
Session close
↓
异步 reconcile
```

同时下次 Gate 打开时可以做一次幂等补偿。

### 验收流程

真实操作：

```text
解锁
↓
进入 App A
↓
提交意图
↓
进入 App B
↓
熄屏
↓
再次解锁
```

下一次 Gate 应能显示可信的：

```text
- 上次意图
- 总使用时间
- App A 时间
- App B 时间
- Top Apps
- 是否超出目标时间
```

---

## 7. 中等优先级：降低高频 polling

当前设计中存在约：

```text
300ms foreground app polling
350ms interactive polling
```

私人使用也可能带来额外耗电。

### 建议

优先采用：

```text
Accessibility event
Screen broadcast
Activity lifecycle
```

作为主触发。

Polling 只做 watchdog。

可以先放宽到：

```text
1~2 秒
```

观察实际门禁体验。

如果 Accessibility 已工作稳定，可以进一步降低 polling 频率。

---

## 8. 暂时不用优先处理的事项

下面这些在“私人自用”阶段不用阻塞功能开发：

### Release signing 完整治理

虽然公开仓库中的旧 JKS 应视为泄漏，但如果当前 APK 仅自己使用：

```text
可以后续再处理正式发布签名体系
```

但不要继续把新的：

```text
*.jks
keystore.properties
password
```

提交进仓库。

### CI

当前可以先不搭完整 CI。

如果后续有时间，最小 CI 只需要：

```bash
./gradlew :core:domain:test
./gradlew :app:lintConsumerDebug
./gradlew :app:assembleConsumerDebug
```

### 混淆

私人使用阶段：

```kotlin
isMinifyEnabled = false
```

不是核心问题。

### README 与文档

版本号、宣传语、Play 合规表述都可以后处理。

---

## 9. 推荐执行顺序

Coding Agent 按这个顺序改：

```text
1. Session submit 原子事务
2. Session create/recover 统一入口
3. 删除 destructive migration
4. Timer deadline 持久化
5. UsageStats reconciliation 接入
6. 降低 polling
7. 补关键测试
8. 最后再清理工程与文档
```

不要先做 UI 重构。

不要先做 Gradle 多模块大拆。

不要先重写整个状态机。

---

## 10. 最低验收标准

整改完成后，至少保证以下真实场景：

### 场景 A：快速提交

```text
解锁
Gate 出现
立即输入意图并提交
```

结果：

```text
存在合法 OPEN Session
存在 FULL segment
intent 正确保存
control.state == FULL
activeSessionId 有效
```

### 场景 B：连续解锁事件

同一次解锁过程中收到多次：

```text
SCREEN_ON
USER_PRESENT
Accessibility event
```

结果：

```text
只能存在一个 OPEN Session
```

### 场景 C：数据库写入失败

提交意图时模拟数据库失败：

```text
Gate 不退出
Session 不进入 FULL
isSessionUnlocked 不得变 true
```

### 场景 D：升级数据库

从旧版本数据库升级：

```text
历史 Session 保留
Todo 保留
Whitelist 保留
```

### 场景 E：倒计时恢复

```text
启动 5 分钟 timer
↓
运行 1 分钟
↓
杀进程
↓
20 秒后恢复
```

剩余时间应约：

```text
3 分 40 秒
```

而不是重新开始 5 分钟。

### 场景 F：长期私人使用

持续使用数天后：

```text
历史数据没有明显缺失
不会频繁出现 phantom session
倒计时不会因 Service 恢复失效
电量消耗可接受
```

---

## 11. 最终目标

本项目当前私人使用阶段不需要追求：

```text
“商业发布级完美”
```

真正目标应该是：

```text
个人每天使用时：
门禁稳定
状态一致
历史可信
数据不丢
倒计时可恢复
耗电不过分
```

如果整改资源有限，优先保证：

> **Session 原子性 + Migration 安全**

这两个是当前私人长期使用最值得投入的改进。
