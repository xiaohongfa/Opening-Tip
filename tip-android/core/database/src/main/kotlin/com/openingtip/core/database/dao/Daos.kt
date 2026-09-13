package com.openingtip.core.database.dao

import androidx.room.*
import com.openingtip.core.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TipControlDao {
    @Query("SELECT * FROM tip_control WHERE singletonId = 1")
    fun observeControl(): Flow<TipControlEntity?>

    @Query("SELECT * FROM tip_control WHERE singletonId = 1")
    suspend fun getControl(): TipControlEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertControl(control: TipControlEntity)

    @Query("UPDATE tip_control SET enabled = :enabled, state = :state, controlVersion = controlVersion + 1 WHERE singletonId = 1")
    suspend fun updateEnabled(enabled: Boolean, state: String)

    @Query("UPDATE tip_control SET activeSessionId = :activeSessionId WHERE singletonId = 1")
    suspend fun updateActiveSessionId(activeSessionId: String?)
}

@Dao
interface WhitelistDao {
    @Query("SELECT * FROM whitelist_entry WHERE available = 1")
    fun observeWhitelist(): Flow<List<WhitelistEntryEntity>>

    @Query("SELECT * FROM whitelist_entry WHERE available = 1")
    suspend fun getWhitelist(): List<WhitelistEntryEntity>

    @Query("SELECT packageName FROM whitelist_entry WHERE available = 1")
    suspend fun getWhitelistPackageNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWhitelistEntries(entries: List<WhitelistEntryEntity>)

    @Query("DELETE FROM whitelist_entry")
    suspend fun clearWhitelist()

    @Insert
    suspend fun insertRevision(revision: WhitelistRevisionEntity)

    @Insert
    suspend fun insertRevisionEntries(entries: List<WhitelistRevisionEntryEntity>)

    @Transaction
    suspend fun replaceWhitelist(
        newEntries: List<WhitelistEntryEntity>,
        newRevision: Long,
        createdAt: Long
    ) {
        clearWhitelist()
        insertWhitelistEntries(newEntries)
        insertRevision(WhitelistRevisionEntity(newRevision, createdAt))
        val revisionEntries = newEntries.map {
            WhitelistRevisionEntryEntity(newRevision, it.userSerial, it.packageName)
        }
        insertRevisionEntries(revisionEntries)
    }
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM session WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM session WHERE id = :sessionId LIMIT 1")
    fun observeSessionById(sessionId: String): Flow<SessionEntity?>

    @Query("SELECT * FROM session WHERE status = 'OPEN' LIMIT 1")
    suspend fun getOpenSession(): SessionEntity?

    @Query("SELECT * FROM session WHERE status = 'CLOSED' AND (:currentSessionId IS NULL OR id != :currentSessionId) AND (intentText IS NOT NULL OR durationMs >= 15000) ORDER BY endWallMs DESC LIMIT 1")
    suspend fun getPreviousMeaningfulClosedSession(currentSessionId: String?): SessionEntity?

    @Query("SELECT * FROM session WHERE status = 'CLOSED' AND (:currentSessionId IS NULL OR id != :currentSessionId) ORDER BY endWallMs DESC LIMIT 1")
    suspend fun getPreviousClosedSession(currentSessionId: String?): SessionEntity?

    @Transaction
    suspend fun getBestPreviousClosedSession(currentSessionId: String?): SessionEntity? {
        return getPreviousMeaningfulClosedSession(currentSessionId) ?: getPreviousClosedSession(currentSessionId)
    }

    @Query("SELECT * FROM session ORDER BY startWallMs DESC")
    fun observeAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session ORDER BY startWallMs DESC")
    suspend fun getAllSessions(): List<SessionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSegment(segment: SessionSegmentEntity)

    @Update
    suspend fun updateSegment(segment: SessionSegmentEntity)

    @Query("SELECT * FROM session_segment WHERE sessionId = :sessionId ORDER BY startWallMs ASC")
    suspend fun getSegmentsForSession(sessionId: String): List<SessionSegmentEntity>

    @Query("SELECT * FROM session_segment WHERE sessionId = :sessionId ORDER BY startWallMs ASC")
    fun observeSegmentsForSession(sessionId: String): Flow<List<SessionSegmentEntity>>

    @Query("SELECT * FROM session_segment WHERE sessionId = :sessionId AND endWallMs IS NULL LIMIT 1")
    suspend fun getOpenSegmentForSession(sessionId: String): SessionSegmentEntity?

    @Query("SELECT COUNT(*) FROM session WHERE startWallMs >= :startOfDayMs")
    fun observeTodaySessionCount(startOfDayMs: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(durationMs), 0) FROM session WHERE startWallMs >= :startOfDayMs")
    fun observeTodayTotalDuration(startOfDayMs: Long): Flow<Long>

    @Query("DELETE FROM session WHERE endWallMs < :thresholdWallMs AND status = 'CLOSED'")
    suspend fun deleteClosedSessionsBefore(thresholdWallMs: Long)

    @Transaction
    suspend fun createSessionIfAbsent(
        session: SessionEntity,
        initialSegment: SessionSegmentEntity
    ): String {
        val existing = getOpenSession()
        return if (existing == null) {
            insertSession(session)
            insertSegment(initialSegment)
            session.id
        } else {
            existing.id
        }
    }

    @Transaction
    suspend fun getOrCreateCanonicalOpenSession(
        session: SessionEntity,
        initialSegment: SessionSegmentEntity
    ): String {
        val existing = getOpenSession()
        return if (existing != null) {
            existing.id
        } else {
            insertSession(session)
            insertSegment(initialSegment)
            session.id
        }
    }

    @Transaction
    suspend fun switchToFull(
        sessionId: String,
        currentSegmentId: String,
        newSegment: SessionSegmentEntity,
        intentText: String,
        targetDurationMinutes: Int? = null,
        switchWallMs: Long,
        switchElapsedMs: Long?
    ) {
        val session = getSessionById(sessionId) ?: return
        val currentSeg = getOpenSegmentForSession(sessionId) ?: return

        updateSegment(
            currentSeg.copy(
                endWallMs = switchWallMs,
                endElapsedMs = switchElapsedMs,
                durationMs = (switchWallMs - currentSeg.startWallMs).coerceAtLeast(0L)
            )
        )
        insertSegment(newSegment)
        updateSession(
            session.copy(
                intentText = intentText,
                targetDurationMinutes = targetDurationMinutes,
                intentSubmittedAt = switchWallMs
            )
        )
    }

    @Transaction
    suspend fun closeSessionIfOpen(
        sessionId: String,
        endWallMs: Long,
        endElapsedMs: Long?,
        endReason: String
    ) {
        val session = getSessionById(sessionId) ?: return
        if (session.status != "OPEN") return

        val openSeg = getOpenSegmentForSession(sessionId)
        if (openSeg != null) {
            updateSegment(
                openSeg.copy(
                    endWallMs = endWallMs,
                    endElapsedMs = endElapsedMs,
                    durationMs = (endWallMs - openSeg.startWallMs).coerceAtLeast(0L)
                )
            )
        }

        updateSession(
            session.copy(
                status = "CLOSED",
                endWallMs = endWallMs,
                endElapsedMs = endElapsedMs,
                endReason = endReason,
                durationMs = (endWallMs - session.startWallMs).coerceAtLeast(0L)
            )
        )
    }
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage_slice WHERE sessionId = :sessionId ORDER BY startWallMs ASC")
    suspend fun getSlicesForSession(sessionId: String): List<UsageSliceEntity>

    @Query("SELECT * FROM session_app_summary WHERE sessionId = :sessionId ORDER BY durationMs DESC")
    fun observeAppSummariesForSession(sessionId: String): Flow<List<SessionAppSummaryEntity>>

    @Query("SELECT * FROM session_app_summary WHERE sessionId = :sessionId ORDER BY durationMs DESC")
    suspend fun getAppSummariesForSession(sessionId: String): List<SessionAppSummaryEntity>

    @Query("SELECT * FROM session_app_summary ORDER BY sessionId ASC, durationMs DESC")
    suspend fun getAllAppSummaries(): List<SessionAppSummaryEntity>

    @Query("SELECT * FROM session_app_summary ORDER BY sessionId ASC, durationMs DESC")
    fun observeAllAppSummaries(): Flow<List<SessionAppSummaryEntity>>

    @Insert
    suspend fun insertSlices(slices: List<UsageSliceEntity>)

    @Query("DELETE FROM usage_slice WHERE sessionId = :sessionId")
    suspend fun deleteSlicesForSession(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppSummaries(summaries: List<SessionAppSummaryEntity>)

    @Query("DELETE FROM session_app_summary WHERE sessionId = :sessionId")
    suspend fun deleteAppSummariesForSession(sessionId: String)

    @Transaction
    suspend fun replaceDerivedTimeline(
        sessionId: String,
        newSlices: List<UsageSliceEntity>,
        newSummaries: List<SessionAppSummaryEntity>
    ) {
        deleteSlicesForSession(sessionId)
        deleteAppSummariesForSession(sessionId)
        insertSlices(newSlices)
        insertAppSummaries(newSummaries)
    }

    @Query("DELETE FROM usage_slice WHERE startWallMs < :thresholdWallMs")
    suspend fun deleteSlicesBefore(thresholdWallMs: Long)
}

@Dao
interface PolicyDao {
    @Query("SELECT * FROM policy_command WHERE status = 'PENDING' ORDER BY requestedAt ASC LIMIT 1")
    suspend fun claimPendingPolicyCommand(): PolicyCommandEntity?

    @Insert
    suspend fun insertCommand(command: PolicyCommandEntity)

    @Update
    suspend fun updateCommand(command: PolicyCommandEntity)

    @Query("SELECT * FROM applied_policy WHERE singletonId = 1")
    suspend fun getAppliedPolicy(): AppliedPolicyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateAppliedPolicy(appliedPolicy: AppliedPolicyEntity)
}

@Dao
interface TodoDao {
    @Query("SELECT * FROM todo_item ORDER BY type ASC, createdAt DESC")
    fun observeAllTodos(): Flow<List<TodoItemEntity>>

    @Query("SELECT * FROM todo_item ORDER BY type ASC, createdAt DESC")
    suspend fun getAllTodos(): List<TodoItemEntity>

    @Query("SELECT * FROM todo_item WHERE type = 'PERMANENT' ORDER BY createdAt ASC")
    fun observePermanentTodos(): Flow<List<TodoItemEntity>>

    @Query("SELECT * FROM todo_item WHERE type = 'SHORT_TERM' ORDER BY isCompleted ASC, createdAt DESC")
    fun observeShortTermTodos(): Flow<List<TodoItemEntity>>

    @Query("SELECT * FROM todo_item WHERE id = :id LIMIT 1")
    suspend fun getTodoById(id: String): TodoItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTodo(todo: TodoItemEntity)

    @Update
    suspend fun updateTodo(todo: TodoItemEntity)

    @Query("UPDATE todo_item SET isCompleted = :isCompleted, completedAt = :completedAt WHERE id = :id")
    suspend fun toggleTodo(id: String, isCompleted: Boolean, completedAt: Long?)

    @Query("UPDATE todo_item SET completedCount = :completedCount, completedAt = :completedAt, completionRecordsJson = :completionRecordsJson, isCompleted = :isCompleted WHERE id = :id")
    suspend fun updatePermanentProgress(id: String, completedCount: Int, completedAt: Long?, completionRecordsJson: String, isCompleted: Boolean)

    @Query("DELETE FROM todo_item WHERE id = :id")
    suspend fun deleteTodo(id: String)

    @Query("DELETE FROM todo_item WHERE type = 'SHORT_TERM' AND isCompleted = 1")
    suspend fun clearCompletedShortTermTodos()
}

