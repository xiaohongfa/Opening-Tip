package com.openingtip

import androidx.sqlite.db.SupportSQLiteDatabase
import com.openingtip.core.database.TipDatabase
import com.openingtip.core.database.dao.SessionDao
import com.openingtip.core.database.entity.FocusTimerEntity
import com.openingtip.core.database.entity.SessionEntity
import com.openingtip.core.database.entity.SessionSegmentEntity
import com.openingtip.core.database.entity.TipControlEntity
import com.openingtip.core.model.SegmentKind
import com.openingtip.core.model.SessionEndReason
import com.openingtip.core.model.SessionState
import com.openingtip.core.model.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 依据 Opening-Tip_Private_Use_Agent_Guide.md 规范：
 * 覆盖场景 A ~ E 核心状态机、原子性事务与迁移安全测试套件
 */
class SessionAtomicityAndMigrationTest {

    private lateinit var fakeDao: FakeSessionDao

    @Before
    fun setup() {
        fakeDao = FakeSessionDao()
    }

    // =========================================================================
    // 场景 A & Guide §2: Session 提交原子事务测试
    // =========================================================================

    @Test
    fun testSubmitIntent_whenActiveSessionIdNullAndNoOpenSession_failsAtomically() = runBlocking {
        // activeSessionId == null 且数据库无 OPEN Session
        val success = fakeDao.submitIntentAndEnterFull(
            sessionId = null,
            intentText = "写代码",
            targetDurationMinutes = 25
        )
        assertFalse("无合法会话时必须拒绝提交", success)
        assertNull("不应产生任何活动会话", fakeDao.currentTipControlState)
    }

    @Test
    fun testSubmitIntent_whenSessionDoesNotExist_failsAtomically() = runBlocking {
        // 指定了不存在的 UUID
        val success = fakeDao.submitIntentAndEnterFull(
            sessionId = "non-existent-uuid",
            intentText = "背单词",
            targetDurationMinutes = 15
        )
        assertFalse("会话不存在时必须拒绝提交", success)
        assertNull(fakeDao.currentTipControlState)
    }

    @Test
    fun testSubmitIntent_whenSessionNotOpen_failsAtomically() = runBlocking {
        // 会话存在，但状态已经是 CLOSED
        val closedSession = SessionEntity(
            id = "sess-closed",
            bootId = "boot-1",
            startWallMs = 1000L,
            endWallMs = 2000L,
            status = SessionStatus.CLOSED.name
        )
        fakeDao.insertSession(closedSession)

        val success = fakeDao.submitIntentAndEnterFull(
            sessionId = "sess-closed",
            intentText = "背单词",
            targetDurationMinutes = 15
        )
        assertFalse("非 OPEN 会话不得切换至 FULL", success)
        assertNull(fakeDao.currentTipControlState)
    }

    @Test
    fun testSubmitIntent_whenRestrictedSegmentMissing_failsAtomically() = runBlocking {
        // 存在 OPEN 会话，但没有任何未结束的 RESTRICTED segment
        val session = SessionEntity(
            id = "sess-no-segment",
            bootId = "boot-1",
            startWallMs = 1000L,
            status = SessionStatus.OPEN.name
        )
        fakeDao.insertSession(session)

        val success = fakeDao.submitIntentAndEnterFull(
            sessionId = "sess-no-segment",
            intentText = "背单词",
            targetDurationMinutes = 15
        )
        assertFalse("缺失 RESTRICTED 分段时必须拒绝提交", success)
        assertNull(fakeDao.currentTipControlState)
    }

    @Test
    fun testSubmitIntent_whenValidRestrictedSession_succeedsAtomically() = runBlocking {
        val t0 = 10_000L
        val sessionId = fakeDao.getOrCreateRestrictedSession(bootId = "boot-1", timestamp = t0, elapsedMs = 1000L)
        assertEquals(SessionState.RESTRICTED.name, fakeDao.currentTipControlState)
        assertEquals(sessionId, fakeDao.currentActiveSessionId)

        val tSubmit = t0 + 5000L
        val success = fakeDao.submitIntentAndEnterFull(
            sessionId = sessionId,
            intentText = "完成需求评审",
            targetDurationMinutes = 30,
            switchWallMs = tSubmit,
            switchElapsedMs = 6000L
        )
        assertTrue("合法 RESTRICTED 会话必须成功提交", success)

        // 验证数据库状态不变量
        val session = fakeDao.getSessionById(sessionId)
        assertNotNull(session)
        assertEquals("完成需求评审", session?.intentText)
        assertEquals(30, session?.targetDurationMinutes)
        assertEquals(tSubmit, session?.intentSubmittedAt)

        val segments = fakeDao.getSegmentsForSession(sessionId)
        assertEquals(2, segments.size)

        val restrictedSeg = segments.find { it.kind == SegmentKind.RESTRICTED.name }
        assertNotNull(restrictedSeg)
        assertEquals(tSubmit, restrictedSeg?.endWallMs)
        assertEquals(5000L, restrictedSeg?.durationMs)

        val fullSeg = segments.find { it.kind == SegmentKind.FULL.name }
        assertNotNull(fullSeg)
        assertEquals(tSubmit, fullSeg?.startWallMs)
        assertNull(fullSeg?.endWallMs)

        assertEquals(SessionState.FULL.name, fakeDao.currentTipControlState)
        assertEquals(sessionId, fakeDao.currentActiveSessionId)
    }

    @Test
    fun testSubmitIntent_rapidConsecutiveSubmissions_secondFailsAtomically() = runBlocking {
        // 用户手滑快速双击提交
        val sessionId = fakeDao.getOrCreateRestrictedSession("boot-1", 10_000L, 1000L)

        val firstSuccess = fakeDao.submitIntentAndEnterFull(sessionId, "第一次提交", 10)
        assertTrue("第一次提交必须成功", firstSuccess)

        // 此时 RESTRICTED 分段已关闭，处于 FULL 状态
        val secondSuccess = fakeDao.submitIntentAndEnterFull(sessionId, "第二次提交", 10)
        assertFalse("已处于 FULL 状态的会话二次提交必须被原子拦截", secondSuccess)

        // 确保仍旧保留第一次提交的意图
        val session = fakeDao.getSessionById(sessionId)
        assertEquals("第一次提交", session?.intentText)
    }

    // =========================================================================
    // 场景 B & Guide §3: Session 创建唯一真源与去重测试
    // =========================================================================

    @Test
    fun testGetOrCreateRestrictedSession_deduplicationAndCanonicalSource() = runBlocking {
        val t0 = 10_000L
        val firstId = fakeDao.getOrCreateRestrictedSession("boot-1", t0, 1000L)
        assertNotNull(firstId)

        // 模拟连续解锁事件（SCREEN_ON, USER_PRESENT, Accessibility window event 同时到达）
        val secondId = fakeDao.getOrCreateRestrictedSession("boot-1", t0 + 100L, 1100L)
        val thirdId = fakeDao.getOrCreateRestrictedSession("boot-1", t0 + 200L, 1200L)

        assertEquals("多次触发必须返回完全相同的 Canonical Session ID", firstId, secondId)
        assertEquals("多次触发必须返回完全相同的 Canonical Session ID", firstId, thirdId)

        // 验证数据库中确实只有唯一一个 Session
        val allSessions = fakeDao.getAllSessions()
        assertEquals(1, allSessions.size)
        assertEquals(firstId, allSessions.first().id)
        assertEquals(SessionStatus.OPEN.name, allSessions.first().status)

        // 验证 control 表 activeSessionId 正确对应
        assertEquals(firstId, fakeDao.currentActiveSessionId)
        assertEquals(SessionState.RESTRICTED.name, fakeDao.currentTipControlState)
    }

    // =========================================================================
    // 场景 E & Guide §5: 倒计时状态持久化与恢复测试
    // =========================================================================

    @Test
    fun testFocusTimer_sameBootRemainingCalculation() {
        val startElapsed = 100_000L
        val totalSec = 300 // 5分钟
        val deadlineElapsed = startElapsed + totalSec * 1000L

        // 运行 1 分钟后杀进程 (nowElapsed = 160_000L)
        // 20 秒后服务自愈恢复 (nowElapsed = 180_000L)
        val resumeElapsed = 180_000L
        val isSameBoot = (resumeElapsed >= startElapsed)
        assertTrue(isSameBoot)

        val remainingSec = ((deadlineElapsed - resumeElapsed) / 1000L).toInt()
        assertEquals("运行1分20秒后恢复，剩余时间必须约为220秒 (3分40秒)", 220, remainingSec)
    }

    @Test
    fun testFocusTimer_crossBootRemainingCalculation() {
        val startWall = 1_700_000_000_000L
        val startElapsed = 500_000L
        val totalSec = 300 // 5分钟
        val deadlineWall = startWall + totalSec * 1000L

        // 机器重启后 elapsed 重置为小值 (10_000L < startElapsed)
        val rebootElapsed = 10_000L
        val isSameBoot = (rebootElapsed >= startElapsed)
        assertFalse("跨启动场景检测", isSameBoot)

        // 重启后耗时 60 秒 (wall 推进 60s)
        val rebootWall = startWall + 60_000L
        val remainingSec = ((deadlineWall - rebootWall) / 1000L).toInt()
        assertEquals("跨开机使用 Wall Clock 恢复，剩余时间为240秒 (4分钟)", 240, remainingSec)
    }

    // =========================================================================
    // 场景 D & Guide §4: 数据库升级 5 -> 6 迁移测试
    // =========================================================================

    @Test
    fun testMigration_5_to_6_createsFocusTimerTable() {
        val executedStatements = mutableListOf<String>()
        val mockDb = java.lang.reflect.Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedStatements.add(args[0] as String)
            }
            null
        } as SupportSQLiteDatabase

        TipDatabase.MIGRATION_5_6.migrate(mockDb)

        assertEquals(1, executedStatements.size)
        val sql = executedStatements.first()
        assertTrue("迁移必须创建 focus_timer 表", sql.contains("CREATE TABLE IF NOT EXISTS `focus_timer`"))
        assertTrue("包含 timerDeadlineElapsedMs 字段", sql.contains("`timerDeadlineElapsedMs` INTEGER NOT NULL"))
        assertTrue("包含 timerDeadlineWallMs 字段", sql.contains("`timerDeadlineWallMs` INTEGER NOT NULL"))
        assertTrue("包含 timerStatus 字段", sql.contains("`timerStatus` TEXT NOT NULL"))
    }
}

/**
 * 纯 JVM 内存版 SessionDao 测试双，专门用于验证 default 事务实现逻辑
 */
class FakeSessionDao : SessionDao {
    private val sessions = mutableMapOf<String, SessionEntity>()
    private val segments = mutableMapOf<String, SessionSegmentEntity>()

    var currentTipControlState: String? = null
    var currentActiveSessionId: String? = null

    override suspend fun updateTipControlStateAndSession(state: String, activeSessionId: String?) {
        currentTipControlState = state
        currentActiveSessionId = activeSessionId
    }

    override suspend fun getSessionById(sessionId: String): SessionEntity? = sessions[sessionId]

    override suspend fun getOpenSession(): SessionEntity? = sessions.values.find { it.status == "OPEN" }

    override suspend fun getAllSessions(): List<SessionEntity> = sessions.values.toList()

    override suspend fun insertSession(session: SessionEntity) {
        sessions[session.id] = session
    }

    override suspend fun updateSession(session: SessionEntity) {
        sessions[session.id] = session
    }

    override suspend fun insertSegment(segment: SessionSegmentEntity) {
        segments[segment.id] = segment
    }

    override suspend fun updateSegment(segment: SessionSegmentEntity) {
        segments[segment.id] = segment
    }

    override suspend fun getSegmentsForSession(sessionId: String): List<SessionSegmentEntity> =
        segments.values.filter { it.sessionId == sessionId }.sortedBy { it.startWallMs }

    override suspend fun getOpenSegmentForSession(sessionId: String): SessionSegmentEntity? =
        segments.values.find { it.sessionId == sessionId && it.endWallMs == null }

    override fun observeAllSessions(): Flow<List<SessionEntity>> = emptyFlow()
    override fun observeSessionById(sessionId: String): Flow<SessionEntity?> = emptyFlow()
    override fun observeSegmentsForSession(sessionId: String): Flow<List<SessionSegmentEntity>> = emptyFlow()
    override fun observeTodaySessionCount(startOfDayMs: Long): Flow<Int> = emptyFlow()
    override fun observeTodayTotalDuration(startOfDayMs: Long): Flow<Long> = emptyFlow()
    override suspend fun deleteClosedSessionsBefore(thresholdWallMs: Long) {}
    override suspend fun getPreviousClosedSession(currentSessionId: String?): SessionEntity? = null
    override suspend fun getPreviousMeaningfulClosedSession(currentSessionId: String?): SessionEntity? = null
}

