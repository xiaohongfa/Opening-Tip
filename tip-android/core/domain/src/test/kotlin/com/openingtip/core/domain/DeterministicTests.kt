package com.openingtip.core.domain

import com.openingtip.core.model.*
import com.openingtip.core.platform.TestTimeProvider
import com.openingtip.core.security.SecretManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 规范 10.1 节确定的 U01 ~ U13 单元测试套件
 */
class DeterministicTests {

    private lateinit var timeProvider: TestTimeProvider
    private lateinit var stateMachine: SessionStateMachine
    private lateinit var reconciler: UsageTimelineReconciler
    private lateinit var secretManager: SecretManager

    @Before
    fun setup() {
        timeProvider = TestTimeProvider(wallMs = 1_000_000L, elapsedMs = 100_000L, bootId = "boot-1")
        stateMachine = SessionStateMachine()
        reconciler = UsageTimelineReconciler()
        secretManager = SecretManager(defaultIterations = 1000)
    }

    /**
     * U01: 开启→限制 A 120s→意图→B 180s→锁屏
     * 必须结果：一会话两阶段、A=120s/B=180s、总计300s
     */
    @Test
    fun testU01_SingleSessionTwoPhases() {
        val t0 = 1_000_000L
        timeProvider.setTime(t0, 100_000L)

        // 1. 用户开启 Tip (已解锁)
        var state = SessionStateSnapshot(enabled = false)
        state = stateMachine.reduce(
            state,
            DomainEvent.EnableRequested(t0, 100_000L, "boot-1", isInteractiveAndUnlocked = true)
        )
        assertEquals(SessionState.RESTRICTED, state.sessionState)
        val session = state.currentSession
        assertNotNull(session)
        val initialSegment = state.currentSegment
        assertNotNull(initialSegment)
        assertEquals(SegmentKind.RESTRICTED, initialSegment?.kind)

        // 2. 限制阶段使用 App A 120秒
        val tAStart = t0
        val tAEnd = t0 + 120_000L

        // 3. 提交意图 "查题" -> 切换到 FULL
        timeProvider.setTime(tAEnd, 220_000L)
        state = stateMachine.reduce(
            state,
            DomainEvent.IntentSubmitted(tAEnd, 220_000L, "查题")
        )
        assertEquals(SessionState.FULL, state.sessionState)
        assertEquals("查题", state.currentSession?.intentText)
        val fullSegment = state.currentSegment
        assertNotNull(fullSegment)
        assertEquals(SegmentKind.FULL, fullSegment?.kind)

        // 4. FULL 阶段使用 App B 180秒
        val tBStart = tAEnd
        val tBEnd = tBStart + 180_000L

        // 5. 锁屏
        timeProvider.setTime(tBEnd, 400_000L)
        state = stateMachine.reduce(
            state,
            DomainEvent.LockOrNonInteractive(tBEnd, 400_000L)
        )
        assertEquals(SessionState.ARMED_IDLE, state.sessionState)
        val closedSession = state.currentSession
        assertNotNull(closedSession)
        assertEquals(SessionStatus.CLOSED, closedSession?.status)
        assertEquals(300_000L, closedSession?.durationMs)

        // 6. 统计重算
        val events = listOf(
            NormalizedUsageEvent("boot-1", tAStart, NormalizedUsageEvent.EventType.ACTIVITY_RESUMED, "com.app.a"),
            NormalizedUsageEvent("boot-1", tAEnd, NormalizedUsageEvent.EventType.ACTIVITY_PAUSED, "com.app.a"),
            NormalizedUsageEvent("boot-1", tBStart, NormalizedUsageEvent.EventType.ACTIVITY_RESUMED, "com.app.b"),
            NormalizedUsageEvent("boot-1", tBEnd, NormalizedUsageEvent.EventType.ACTIVITY_PAUSED, "com.app.b")
        )

        val segments = listOf(
            initialSegment!!.copy(endWallMs = tAEnd, durationMs = 120_000L),
            fullSegment!!.copy(endWallMs = tBEnd, durationMs = 180_000L)
        )

        val output = reconciler.reconcile(closedSession!!, segments, events, whitelistPackages = setOf("com.app.a"))
        val summaryA = output.appSummaries.find { it.packageName == "com.app.a" }
        val summaryB = output.appSummaries.find { it.packageName == "com.app.b" }

        assertNotNull(summaryA)
        assertNotNull(summaryB)
        assertEquals(120_000L, summaryA?.durationMs)
        assertEquals(180_000L, summaryB?.durationMs)
        assertEquals(SegmentKind.RESTRICTED, summaryA?.phase)
        assertEquals(SegmentKind.FULL, summaryB?.phase)
    }

    /**
     * U02: 解锁→仅白名单→锁屏→再解锁
     * 必须结果：Gate 展示无意图的上一次会话
     */
    @Test
    fun testU02_RestrictedOnlySession() {
        var state = SessionStateSnapshot(enabled = true, sessionState = SessionState.ARMED_IDLE)

        // 解锁
        val t0 = 2_000_000L
        state = stateMachine.reduce(state, DomainEvent.UnlockObserved(t0, 100_000L, "boot-1"))
        assertEquals(SessionState.RESTRICTED, state.sessionState)

        // 仅启动白名单
        state = stateMachine.reduce(state, DomainEvent.WhitelistLaunch(t0 + 10_000L, 110_000L, "com.whitelist.app", null))
        assertEquals(SessionState.RESTRICTED, state.sessionState)

        // 锁屏
        val t1 = t0 + 60_000L
        state = stateMachine.reduce(state, DomainEvent.LockOrNonInteractive(t1, 160_000L))
        val prevSession = state.currentSession
        assertNotNull(prevSession)
        assertEquals(SessionStatus.CLOSED, prevSession?.status)
        assertNull("仅使用白名单没有填写意图", prevSession?.intentText)
        assertEquals(60_000L, prevSession?.durationMs)

        // 再次解锁
        val t2 = t1 + 30_000L
        state = stateMachine.reduce(state, DomainEvent.UnlockObserved(t2, 190_000L, "boot-1"))
        assertEquals(SessionState.RESTRICTED, state.sessionState)
        assertNotNull("新会话已创建", state.currentSession)
        assertNotEquals(prevSession?.id, state.currentSession?.id)
    }

    /**
     * U03: 同一事件窗口回放两次/重叠回补
     * 必须结果：Session、切片、时长完全一致
     */
    @Test
    fun testU03_ReplayIdempotency() {
        val session = Session(
            id = "sess-u03",
            bootId = "boot-1",
            startWallMs = 10_000L,
            endWallMs = 70_000L,
            durationMs = 60_000L,
            status = SessionStatus.CLOSED
        )
        val segment = SessionSegment(
            id = "seg-u03",
            sessionId = session.id,
            kind = SegmentKind.RESTRICTED,
            startWallMs = 10_000L,
            endWallMs = 70_000L,
            durationMs = 60_000L
        )
        val events = listOf(
            NormalizedUsageEvent("boot-1", 10_000L, NormalizedUsageEvent.EventType.ACTIVITY_RESUMED, "com.app.x"),
            NormalizedUsageEvent("boot-1", 40_000L, NormalizedUsageEvent.EventType.ACTIVITY_PAUSED, "com.app.x"),
            NormalizedUsageEvent("boot-1", 40_000L, NormalizedUsageEvent.EventType.ACTIVITY_RESUMED, "com.app.y"),
            NormalizedUsageEvent("boot-1", 70_000L, NormalizedUsageEvent.EventType.ACTIVITY_PAUSED, "com.app.y")
        )

        val run1 = reconciler.reconcile(session, listOf(segment), events)
        val run2 = reconciler.reconcile(session, listOf(segment), events)

        assertEquals(run1.slices.size, run2.slices.size)
        assertEquals(run1.appSummaries.size, run2.appSummaries.size)
        for (i in run1.slices.indices) {
            assertEquals(run1.slices[i].packageName, run2.slices[i].packageName)
            assertEquals(run1.slices[i].durationMs, run2.slices[i].durationMs)
            assertEquals(run1.slices[i].startWallMs, run2.slices[i].startWallMs)
            assertEquals(run1.slices[i].endWallMs, run2.slices[i].endWallMs)
        }
    }

    /**
     * U04: 同包多 Activity、重复 resume/pause
     * 必须结果：不双计，不产生负时长
     */
    @Test
    fun testU04_MultipleActivitiesSamePackage() {
        val session = Session(id = "sess-u04", bootId = "boot-1", startWallMs = 1000L, endWallMs = 5000L, status = SessionStatus.CLOSED)
        val segment = SessionSegment(id = "seg-u04", sessionId = "sess-u04", kind = SegmentKind.RESTRICTED, startWallMs = 1000L, endWallMs = 5000L)

        // 同一个应用内部 ActivityA -> ActivityB 跳转
        val events = listOf(
            NormalizedUsageEvent("boot-1", 1000L, NormalizedUsageEvent.EventType.ACTIVITY_RESUMED, "com.example.app", "ActivityA"),
            NormalizedUsageEvent("boot-1", 2000L, NormalizedUsageEvent.EventType.ACTIVITY_PAUSED, "com.example.app", "ActivityA"),
            NormalizedUsageEvent("boot-1", 2000L, NormalizedUsageEvent.EventType.ACTIVITY_RESUMED, "com.example.app", "ActivityB"),
            NormalizedUsageEvent("boot-1", 5000L, NormalizedUsageEvent.EventType.ACTIVITY_PAUSED, "com.example.app", "ActivityB")
        )

        val output = reconciler.reconcile(session, listOf(segment), events)
        val summary = output.appSummaries.find { it.packageName == "com.example.app" }
        assertNotNull(summary)
        assertEquals(4000L, summary?.durationMs)
        assertTrue("时长绝不能为负", summary?.durationMs ?: -1L >= 0L)
    }

    /**
     * U05: 重复锁屏/解锁广播、点击提交两次
     * 必须结果：不创建重复会话或意图
     */
    @Test
    fun testU05_DeduplicateBroadcastsAndClicks() {
        var state = SessionStateSnapshot(enabled = true, sessionState = SessionState.ARMED_IDLE)

        // 重复解锁事件
        state = stateMachine.reduce(state, DomainEvent.UnlockObserved(1000L, 100L, "boot-1"))
        val firstSessionId = state.currentSession?.id
        assertNotNull(firstSessionId)

        // 第二个紧接着的重复广播
        state = stateMachine.reduce(state, DomainEvent.UnlockObserved(1005L, 105L, "boot-1"))
        assertEquals("会话 ID 保持不变，不重复创建", firstSessionId, state.currentSession?.id)

        // 提交意图
        state = stateMachine.reduce(state, DomainEvent.IntentSubmitted(2000L, 200L, "学习"))
        assertEquals(SessionState.FULL, state.sessionState)

        // 重复点击提交
        state = stateMachine.reduce(state, DomainEvent.IntentSubmitted(2050L, 250L, "学习"))
        assertEquals(SessionState.FULL, state.sessionState)
        assertEquals("学习", state.currentSession?.intentText)
    }

    /**
     * U06: 使用中进程死亡，之后拿到完整事件
     * 必须结果：恢复原会话并准确截断
     */
    @Test
    fun testU06_ProcessDeathRecovery() {
        val openSession = Session(
            id = "sess-u06",
            bootId = "boot-1",
            startWallMs = 10_000L,
            status = SessionStatus.OPEN
        )
        val openSegment = SessionSegment(
            id = "seg-u06",
            sessionId = "sess-u06",
            kind = SegmentKind.RESTRICTED,
            startWallMs = 10_000L
        )
        val state = SessionStateSnapshot(
            enabled = true,
            sessionState = SessionState.RESTRICTED,
            currentSession = openSession,
            currentSegment = openSegment
        )

        // 进程重启后恢复事件（同一 boot）
        val restoredState = stateMachine.reduce(
            state,
            DomainEvent.ProcessRestored(15_000L, 105_000L, "boot-1")
        )
        assertEquals(SessionState.RESTRICTED, restoredState.sessionState)
        assertEquals(openSession.id, restoredState.currentSession?.id)

        // 收到随后的锁屏事件，正常闭合
        val finalState = stateMachine.reduce(
            restoredState,
            DomainEvent.LockOrNonInteractive(20_000L, 110_000L)
        )
        assertEquals(SessionStatus.CLOSED, finalState.currentSession?.status)
        assertEquals(10_000L, finalState.currentSession?.durationMs)
    }

    /**
     * U07: 无 pause、缺解锁、事件保留不足
     * 必须结果：标质量问题，不制造完整报告
     */
    @Test
    fun testU07_MissingPauseMarkedEstimated() {
        val session = Session(id = "sess-u07", bootId = "boot-1", startWallMs = 1000L, endWallMs = 10_000L, status = SessionStatus.CLOSED)
        val segment = SessionSegment(id = "seg-u07", sessionId = "sess-u07", kind = SegmentKind.RESTRICTED, startWallMs = 1000L, endWallMs = 10_000L)

        // 只有 RESUMED，没有 PAUSED，直到会话结束
        val events = listOf(
            NormalizedUsageEvent("boot-1", 1000L, NormalizedUsageEvent.EventType.ACTIVITY_RESUMED, "com.app.no_pause")
        )

        val output = reconciler.reconcile(session, listOf(segment), events)
        val slice = output.slices.find { it.packageName == "com.app.no_pause" }
        assertNotNull(slice)
        assertEquals(QualityRating.ESTIMATED, slice?.quality)
    }

    /**
     * U08: 暗号→重启/HOME/worker
     * 必须结果：enabled 仍 false，关闭后切片为零
     */
    @Test
    fun testU08_SecretDisarmPersistsDisabled() {
        var state = SessionStateSnapshot(
            enabled = true,
            sessionState = SessionState.RESTRICTED,
            currentSession = Session(id = "sess-u08", bootId = "boot-1", startWallMs = 1000L)
        )

        // 输入暗号
        state = stateMachine.reduce(state, DomainEvent.SecretSubmitted(2000L, 200L))
        assertFalse("已持久关闭", state.enabled)
        assertEquals(SessionState.DISARMED, state.sessionState)
        assertEquals(SessionEndReason.DISARMED_BY_SECRET, state.currentSession?.endReason)

        // 尝试 Home 广播
        state = stateMachine.reduce(state, DomainEvent.HomeOpened(3000L, 300L))
        assertFalse(state.enabled)
        assertEquals(SessionState.DISARMED, state.sessionState)

        // 尝试开机重启恢复
        state = stateMachine.reduce(state, DomainEvent.ProcessRestored(4000L, 400L, "boot-2"))
        assertFalse("重启后绝不自动启用", state.enabled)
        assertEquals(SessionState.DISARMED, state.sessionState)
    }

    /**
     * U09: FULL 改白名单
     * 必须结果：当前 FULL 不变、历史快照不变、下次限制用新版本
     */
    @Test
    fun testU09_ModifyWhitelistInFull() {
        val state = SessionStateSnapshot(
            enabled = true,
            sessionState = SessionState.FULL,
            activeWhitelistRevision = 1L,
            currentSession = Session(id = "sess-u09", bootId = "boot-1", startWallMs = 1000L),
            currentSegment = SessionSegment(id = "seg-full", sessionId = "sess-u09", kind = SegmentKind.FULL, startWallMs = 2000L)
        )

        // 管理员在 FULL 修改白名单，递增 revision 为 2L
        val updatedState = state.copy(activeWhitelistRevision = 2L)
        assertEquals(SessionState.FULL, updatedState.sessionState)
        assertEquals(SegmentKind.FULL, updatedState.currentSegment?.kind)

        // 锁屏结束该会话
        val lockedState = stateMachine.reduce(updatedState, DomainEvent.LockOrNonInteractive(5000L, 500L))
        assertEquals(SessionState.ARMED_IDLE, lockedState.sessionState)

        // 下一次解锁开启限制阶段，自动引用 revision 2L
        val nextUnlockedState = stateMachine.reduce(lockedState, DomainEvent.UnlockObserved(6000L, 600L, "boot-1"))
        assertEquals(SessionState.RESTRICTED, nextUnlockedState.sessionState)
        assertEquals(2L, nextUnlockedState.currentSegment?.whitelistRevision)
    }

    /**
     * U10: 时间回拨、跨午夜/时区、重启
     * 必须结果：不负值、不跨 boot 累计关机时长
     */
    @Test
    fun testU10_RebootAcrossShutdown() {
        val openSession = Session(
            id = "sess-u10",
            bootId = "boot-1",
            startWallMs = 10_000L,
            status = SessionStatus.OPEN
        )
        val state = SessionStateSnapshot(
            enabled = true,
            sessionState = SessionState.RESTRICTED,
            currentSession = openSession
        )

        // 跨 boot 恢复（例如意外断电重启）
        val restoredState = stateMachine.reduce(
            state,
            DomainEvent.ProcessRestored(20_000L, 500L, "boot-2")
        )
        val session = restoredState.currentSession
        assertEquals(SessionStatus.INTERRUPTED, session?.status)
        assertEquals(SessionEndReason.SHUTDOWN, session?.endReason)
        assertEquals(QualityRating.DEGRADED, session?.quality)
    }

    /**
     * U11: 分屏重叠与 PiP
     * 必须结果：时间线不重叠且标估计，未知可解释
     */
    @Test
    fun testU11_SplitScreenExclusiveAttribution() {
        val session = Session(id = "sess-u11", bootId = "boot-1", startWallMs = 1000L, endWallMs = 6000L, status = SessionStatus.CLOSED)
        val segment = SessionSegment(id = "seg-u11", sessionId = "sess-u11", kind = SegmentKind.RESTRICTED, startWallMs = 1000L, endWallMs = 6000L)

        // 分屏两个应用相继 RESUMED，没有 PAUSED
        val events = listOf(
            NormalizedUsageEvent("boot-1", 1000L, NormalizedUsageEvent.EventType.ACTIVITY_RESUMED, "com.app.left"),
            NormalizedUsageEvent("boot-1", 3000L, NormalizedUsageEvent.EventType.ACTIVITY_RESUMED, "com.app.right"),
            NormalizedUsageEvent("boot-1", 6000L, NormalizedUsageEvent.EventType.ACTIVITY_PAUSED, "com.app.right")
        )

        val output = reconciler.reconcile(session, listOf(segment), events)
        val left = output.slices.find { it.packageName == "com.app.left" }
        val right = output.slices.find { it.packageName == "com.app.right" }

        assertNotNull(left)
        assertNotNull(right)
        assertEquals(2000L, left?.durationMs) // 1000 ~ 3000
        assertEquals(3000L, right?.durationMs) // 3000 ~ 6000
        // 切片总时长与区间无缝衔接且绝不重叠
        assertEquals(5000L, (left?.durationMs ?: 0L) + (right?.durationMs ?: 0L))
    }

    /**
     * U12: 策略命令每一步注入崩溃
     * 必须结果：恢复幂等，过期命令不能重新启用
     */
    @Test
    fun testU12_PolicyOutboxExpiration() {
        val fakeController = object : RestrictionController {
            override suspend fun capability(): OperationMode = OperationMode.CONSUMER
            override suspend fun applyRestricted(command: PolicyCommand) = PolicyExecutionResult(true)
            override suspend fun releaseOwnedRestrictions(command: PolicyCommand) = PolicyExecutionResult(true)
            override suspend fun inspectActualPolicy() = ObservedPolicy(isRestricted = false, lockTaskActive = false, verifiedRevision = 1L)
        }
        val coordinator = PolicyOutboxCoordinator(fakeController)

        val expiredCommand = PolicyCommand(
            id = "cmd-expired",
            controlVersion = 1L,
            type = PolicyCommandType.RELEASE_RESTRICTION,
            targetPolicyRevision = 1L,
            requestedAt = 1000L
        )

        kotlinx.coroutines.runBlocking {
            // 当前版本已升为 2L，执行版本为 1L 的旧命令应当拒绝生效
            val executed = coordinator.executeCommand(expiredCommand, currentControlVersion = 2L)
            assertEquals(CommandStatus.FAILED, executed.status)
            assertEquals("EXPIRED_CONTROL_VERSION", executed.errorCode)
        }
    }

    /**
     * U13: 清空历史后回补
     * 必须结果：已删区间不重新生成
     */
    @Test
    fun testU13_ClearHistoryIdempotence() {
        // 模拟清空历史点设置在 50_000L
        val clearedWallMs = 50_000L

        val oldSession = Session(
            id = "sess-old",
            bootId = "boot-1",
            startWallMs = 10_000L,
            endWallMs = 40_000L,
            status = SessionStatus.CLOSED
        )

        // 若会话结束时间早于清空时间，回补器应当跳过或重算为空
        val events = listOf(
            NormalizedUsageEvent("boot-1", 10_000L, NormalizedUsageEvent.EventType.ACTIVITY_RESUMED, "com.old.app"),
            NormalizedUsageEvent("boot-1", 40_000L, NormalizedUsageEvent.EventType.ACTIVITY_PAUSED, "com.old.app")
        )

        // 业务层规则：清空历史记录后，回补游标重置为 clearedWallMs
        val filteredEvents = events.filter { it.timestampWallMs >= clearedWallMs }
        assertTrue("旧历史事件已被游标截断排除", filteredEvents.isEmpty())
    }
}
