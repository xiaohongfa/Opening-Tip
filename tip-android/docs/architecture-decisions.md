# 开屏 Tip：核心架构决策记录 (Architecture Decision Records)

版本：1.0 · 日期：2026-09-12 · 状态：已批准

---

## 1. ADR-01: 单一活动会话与强约束模型

### 决策背景
开屏 Tip 要求统计“上一次已结束会话”与“当前进行中会话”，涉及锁屏、解锁、意图提交、白名单启动等复杂时序。若并发事件导致两个会话重叠或产生幽灵会话，会导致时长计算严重失真。

### 架构决定
1. **单一活动会话与切片约束**：
   - 任何时刻，主用户最多存在一个 `status = OPEN` 的 `Session`，以及最多一个关联该会话且 `endWallMs = null` 的 `SessionSegment`。
   - `TipControl` 采用单例表（`singletonId = 1`），包含 `activeSessionId`，所有状态变更由事务保护。
2. **状态机不可逆转移**：
   - 一个会话生命周期内，只允许从 `RESTRICTED` 单向转变为 `FULL` 一次。
   - 意图一旦提交成功，不得因打开白名单应用或返回桌面而倒退回 `RESTRICTED`。
   - 只有在明确的锁定或非交互事件（`LockOrNonInteractive`）触发后，当前会话关闭，下一次交互才能开启新会话。

---

## 2. ADR-02: 策略 Outbox 模式与崩溃一致性

### 决策背景
Room 本地数据库事务与 Android 系统策略（如 `RoleManager`、`DevicePolicyManager.setLockTaskPackages()`）分属两个独立世界，无法形成 XA 原子分布式事务。如果策略修改成功但数据库提交前应用崩溃，或数据库记录已开放但系统策略未释放，用户将陷入严重故障。

### 架构决定
1. **采用 Outbox 模式 (`PolicyCommand` + `AppliedPolicy`)**：
   - 当需要变更系统限制（如由 `RESTRICTED` 转为 `FULL`、或暗号关闭）时，首先在 Room 事务中持久化 `PolicyCommand`（标记为 `PENDING`，附带 UUID、目标控制版本与时间戳）。
   - 限制适配器（`RestrictionController`）幂等执行底层策略修改。
   - 执行后调用 `inspectActualPolicy()` 核验系统真实生效状态。
   - 校验通过后，提交业务状态变更，并将 `PolicyCommand` 标记为 `APPLIED`（清除敏感意图载荷）。
2. **崩溃恢复机制**：
   - 进程恢复或初始化时，优先检查未决命令。若发现未完成的释放命令，核验系统策略后补交阶段边界，防止用户被误锁在 Gate 中。

---

## 3. ADR-03: UsageStats 事件流重算与幂等回放算法

### 决策背景
Android `UsageStatsManager.queryUsageStats()` 仅提供基于天/周维度的前台累计时长，无法提供会话精细到秒的前台应用明细。必须使用 `UsageStatsManager.queryEvents(begin, end)` 重建真实时间线。

### 架构决定
1. **窗口分块与游标回溯**：
   - 回补查询以 6 小时为一个分块（建议值），向前设置 2 分钟回看游标，以吸收系统迟到的事件。
2. **事件规范化标识**：
   - 系统 `UsageEvents.Event` 没有全局唯一 ID。采用元组 `(bootId, timestamp, type, packageName, className, instanceId, orderInBatch)` 进行唯一规范化。
3. **事务性全量替换（幂等性保证）**：
   - 绝不采用在旧切片上累加增量的方式。每次根据 Checkpoint 重算时，在单个 Room 事务中删除受影响会话的旧 `UsageSlice` 和 `SessionAppSummary`，重新写入新切片。
4. **多窗口与不确定性降级处理**：
   - 分屏/PiP 下，采用“最近一次 `ACTIVITY_RESUMED` 的候选应用独占归因”口径，切片质量标记为 `ESTIMATED`，报告中向用户展示“分屏时长为估计值”。
   - 无法确认的空白区间归为 `UNKNOWN`，系统界面占用归为 `SYSTEM`，不强行分摊给普通应用。

---

## 4. ADR-04: 关闭暗号的单向加密与安全基线

### 决策背景
暗号是关闭 Tip 限制的重要凭证，绝不能明文落盘，也不能因备份、换机被非预期带回。

### 架构决定
1. **算法选型**：
   - 随机 Salt（使用 `SecureRandom`，至少 16 字节）。
   - `PBKDF2WithHmacSHA256` 密钥派生算法，工作因子设定为 600,000 次迭代。
   - 验证暗号时采用常量时间比较（`MessageDigest.isEqual`），杜绝时序侧信道攻击。
2. **输入预处理与敏感数据隔离**：
   - 比较前执行 Unicode NFC 标准化（`Normalizer.normalize(secret, Form.NFC)`）。
   - 严禁对暗号输入打日志、上报埋点或保存在 Room 的通用会话表中。
   - 暗号通过应用私有隔离文件存储，配置 Android Auto Backup 规则彻底排除该凭据文件与使用历史。

---

## 5. ADR-05: 纯 Kotlin 领域层解耦 (Domain Purity)

### 架构决定
- `core:domain` 模块设计为**零 Android SDK 依赖（Pure Kotlin）**。
- 时间提供器、事件源、系统策略控制器均抽象为接口（`TimeProvider`、`UsageEventSource`、`RestrictionController`）。
- 使得规范中规定的 **U01 到 U13 单元测试套件** 能够在 JVM 上秒级确定性执行，无需 Mock 笨重的 Android 运行时，保证算法的高可靠性。
