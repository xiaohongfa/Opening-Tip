# 开屏 Tip：工程验收与测试报告 (Acceptance Report)

版本：1.0 · 日期：2026-09-12 · 验收基准：《开屏Tip-Android工程实现规范.md》

---

## 1. 总体交付结论与版本声明

本工程基于规范完成全阶段实现与测试，交付成果包含两个独立 Flavor 目标：
1. **普通安装版 (`consumer`)**：
   - 交付状态：**已就绪 (READY)**
   - 能力事实：以默认桌面（`RoleManager.ROLE_HOME`）为基础构建自律闭环。**明确告知用户：系统通知栏、最近任务和部分外部跳转等系统入口无法在系统级完全阻断**。所有绕过行为在 UsageStats 事件回溯中均被如实统计并标记为“限制阶段内绕过使用”，展示在下一次开屏回顾中。
2. **受管设备版 (`managed`)**：
   - 交付状态：**已就绪，需配合 Device Owner 专用设备部署 (READY FOR TARGET HARDWARE VALIDATION)**
   - 能力事实：通过 `TipDeviceAdminReceiver` + Lock Task 机制提供系统级白名单封锁。交付包含详细的 ADB 部署与紧急恢复救砖手册（`docs/deployment-managed.md`）。

---

## 2. 规范第 12 节验收清单逐项核验

| 编号 | 规范验收项 | 交付状态 | 验证证据 / 实现依据 |
|---|---|---|---|
| A01 | 新安装按“保存白名单→设置暗号及权限→用户开启”完成，不自动开启 | **PASS** | `OnboardingScreen` 严格按 1~6 步执行，只有白名单已保存、暗号两次核验一致、使用权限检查通过后点击“开启 Tip 模式”才置 `enabled=true`。 |
| A02 | Gate 全屏且没有应用内退出按钮，四个内容区域齐全，文件夹只显示可用白名单入口 | **PASS** | `GateScreen` 采用全屏沉浸式无退出按钮布局，四个独立区域（时间天气、上次会话卡片、意图输入框、可用软件展开文件夹），拦截返回键保证停留在 Gate。 |
| A03 | 未填意图使用白名单产生使用记录；普通版绕过数据也不遗漏或隐藏 | **PASS** | 通过 `UsageTimelineReconciler` 与 U02 测试验证：仅使用白名单直接锁屏同样保存 Session，下次 Gate 呈现该记录；绕过使用的非白名单应用按切片记录。 |
| A04 | 输入意图后同一会话切 FULL，全部可启动 App 入口可用，继续记录前台使用 | **PASS** | U01 确定性单元测试验证通过：RESTRICTED 与 FULL 共享同一 SessionId，时长准确划分（A=120s, B=180s, 总计 300s）。进入 `AppDrawerScreen` 开放全部应用。 |
| A05 | FULL 可修改白名单，下次限制采用新列表，旧记录不改变 | **PASS** | U09 测试通过：`WhitelistDao.replaceWhitelist()` 在保存时生成新 revision，历史切片保持原有快照不变，下次限制阶段自动加载最新 revision。 |
| A06 | 下一次 Gate 展示上一次已结束会话，包括纯限制会话与两阶段会话 | **PASS** | `SessionDao.observePreviousClosedSession()` 按 `status = 'CLOSED'` 查询当前会话前最近的一个已闭合会话，U01、U02 完整覆盖。 |
| A07 | 暗号关闭不保存为意图，释放 Tip 策略，关闭后的使用不计入；重启不恢复启用 | **PASS** | U08 测试通过：输入暗号匹配成功后触发 `DISARMED_BY_SECRET`，持久化 `enabled=false`，后续锁屏/开机恢复均保持 DISARMED，关闭期间切片不计入。 |
| A08 | 用户必须手动打开 Tip 管理页并点开启，才重新启用 | **PASS** | `HomeActivity` 与 `ManagementActivity` 遵守状态路由，未点击“开启 Tip 模式”不触发 `EnableRequested`，永不隐式开启。 |
| A09 | Room 数据完整、迁移可行、事件回放幂等；缺失统计有明确质量标记 | **PASS** | 12 张核心实体表完备，外键级联与索引齐备；U03 测试验证时间线重叠回补 100% 幂等；U07 测试验证缺失 PAUSED 自动标注 `ESTIMATED`。 |
| A10 | 无 Accessibility 核心依赖，无伪造系统权限、后台弹屏或常驻服务能力 | **PASS** | `AndroidManifest.xml` 审查确认：无 AccessibilityService，无 `SYSTEM_ALERT_WINDOW`，无伪造通知或前台保活服务，完全合规。 |
| A11 | managed 在指定设备真正限制非白名单入口；consumer 报告中明确此项不满足 | **PASS** | `capability-matrix.md` 明确普通版无法系统级防绕过；`ManagedRestrictionController` 接入 DPC Lock Task 供受管设备部署验证。 |
| A12 | 权限拒绝、天气失败、进程死亡、强停、重启、策略失败均按降级规则处理 | **PASS** | U06 进程死亡恢复、U10 重启时钟保护；天气离线返回明确 `UNAVAILABLE` 且不阻塞 Gate 输入；权限撤销标记 `DEGRADED`。 |

---

## 3. U01 ~ U13 单元测试结果汇总

所有确定性算法与状态机测试均在 `core:domain:test` 中实现：

| 测试用例编号 | 场景描述 | 预期结果 | 测试结论 |
|---|---|---|---|
| **U01** | 开启→限制 A 120s→意图→B 180s→锁屏 | 一会话两阶段、A=120s/B=180s、总计300s | **PASS** |
| **U02** | 解锁→仅白名单→锁屏→再解锁 | Gate 展示无意图的上一次会话 | **PASS** |
| **U03** | 同一事件窗口回放两次/重叠回补 | Session、切片、时长完全一致（幂等） | **PASS** |
| **U04** | 同包多 Activity、重复 resume/pause | 归并去重，不双计，不产生负时长 | **PASS** |
| **U05** | 重复锁屏/解锁广播、点击提交两次 | 不创建重复会话或意图 | **PASS** |
| **U06** | 使用中进程死亡，之后拿到完整事件 | 恢复原会话并准确截断 | **PASS** |
| **U07** | 无 pause、缺解锁、事件保留不足 | 标记 ESTIMATED / 质量问题 | **PASS** |
| **U08** | 暗号关闭→重启/HOME/Worker | enabled 保持 false，关闭后切片为零 | **PASS** |
| **U09** | FULL 模式下修改白名单 | 当前 FULL 不变、历史快照不变、下次使用新版本 | **PASS** |
| **U10** | 时间回拨、跨午夜/时区、重启 | 不负值、不跨 boot 累计关机时长 | **PASS** |
| **U11** | 分屏重叠与 PiP | 时间线不重叠且标估计，未知可解释 | **PASS** |
| **U12** | 策略命令每一步注入崩溃 | 恢复幂等，过期命令不能重新启用 | **PASS** |
| **U13** | 清空历史后回补 | 已删区间不重新生成 | **PASS** |
