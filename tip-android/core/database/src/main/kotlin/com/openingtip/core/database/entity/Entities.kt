package com.openingtip.core.database.entity

import androidx.room.*

@Entity(tableName = "tip_control")
data class TipControlEntity(
    @PrimaryKey val singletonId: Int = 1,
    val onboardingStep: Int = 0,
    val whitelistConfirmed: Boolean = false,
    val enabled: Boolean = false,
    val mode: String = "CONSUMER", // CONSUMER | MANAGED
    val state: String = "DISARMED", // DISARMED | ARMED_IDLE | RESTRICTED | FULL
    val health: String = "OK", // OK | DEGRADED | RECOVERY_REQUIRED
    val controlVersion: Long = 1L,
    val activeSessionId: String? = null,
    val disabledAt: Long? = null,
    val disableReason: String? = null
)

@Entity(
    tableName = "whitelist_entry",
    primaryKeys = ["userSerial", "packageName"]
)
data class WhitelistEntryEntity(
    val userSerial: Int = 0,
    val packageName: String,
    val preferredComponent: String? = null,
    val labelCache: String? = null,
    val selectedAt: Long,
    val available: Boolean = true
)

@Entity(tableName = "whitelist_revision")
data class WhitelistRevisionEntity(
    @PrimaryKey val revision: Long,
    val createdAt: Long
)

@Entity(
    tableName = "whitelist_revision_entry",
    primaryKeys = ["revision", "userSerial", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = WhitelistRevisionEntity::class,
            parentColumns = ["revision"],
            childColumns = ["revision"],
            onDelete = ForeignKey.RESTRICT // 版本引用期间不能删除
        )
    ],
    indices = [Index(value = ["revision"])]
)
data class WhitelistRevisionEntryEntity(
    val revision: Long,
    val userSerial: Int = 0,
    val packageName: String
)

@Entity(
    tableName = "session",
    indices = [
        Index(value = ["startWallMs"]),
        Index(value = ["endWallMs"]),
        Index(value = ["status"])
    ]
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val userSerial: Int = 0,
    val bootId: String,
    val startWallMs: Long,
    val startElapsedMs: Long? = null,
    val endWallMs: Long? = null,
    val endElapsedMs: Long? = null,
    val status: String = "OPEN", // OPEN | CLOSED | INTERRUPTED
    val intentText: String? = null,
    val intentSubmittedAt: Long? = null,
    val endReason: String? = null,
    val quality: String = "PRECISE",
    val qualityReasons: String? = null,
    val durationMs: Long = 0L,
    val statsRevision: Long = 1L,
    val createdAt: Long = startWallMs
)

@Entity(
    tableName = "session_segment",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["kind"])
    ]
)
data class SessionSegmentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val kind: String, // RESTRICTED | FULL
    val startWallMs: Long,
    val endWallMs: Long? = null,
    val startElapsedMs: Long? = null,
    val endElapsedMs: Long? = null,
    val whitelistRevision: Long? = null,
    val durationMs: Long = 0L
)

@Entity(
    tableName = "usage_slice",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SessionSegmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["segmentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId", "startWallMs"]),
        Index(value = ["segmentId"])
    ]
)
data class UsageSliceEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val segmentId: String,
    val startWallMs: Long,
    val endWallMs: Long,
    val durationMs: Long,
    val packageName: String? = null,
    val userSerial: Int = 0,
    val labelSnapshot: String? = null,
    val category: String = "APP", // APP | TIP | SYSTEM | UNKNOWN
    val quality: String = "PRECISE",
    val source: String = "USAGE_EVENTS",
    val wasAllowlisted: Boolean? = null
)

@Entity(
    tableName = "session_app_summary",
    primaryKeys = ["sessionId", "packageName", "phase"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["sessionId"])]
)
data class SessionAppSummaryEntity(
    val sessionId: String,
    val packageName: String,
    val phase: String, // RESTRICTED | FULL
    val durationMs: Long,
    val labelSnapshot: String? = null,
    val quality: String = "PRECISE"
)

@Entity(
    tableName = "local_event",
    indices = [Index(value = ["wallMs"])]
)
data class LocalEventEntity(
    @PrimaryKey val id: String,
    val controlVersion: Long,
    val bootId: String,
    val wallMs: Long,
    val elapsedMs: Long? = null,
    val type: String,
    val sessionId: String? = null,
    val payload: String? = null
)

@Entity(
    tableName = "usage_checkpoint",
    primaryKeys = ["userSerial", "bootId"]
)
data class UsageCheckpointEntity(
    val userSerial: Int = 0,
    val bootId: String,
    val queriedThroughWallMs: Long,
    val anchorWallMs: Long? = null,
    val anchorElapsedMs: Long? = null,
    val replayState: String? = null,
    val schemaVersion: Int = 1
)

@Entity(
    tableName = "policy_command",
    indices = [
        Index(value = ["status", "requestedAt"])
    ]
)
data class PolicyCommandEntity(
    @PrimaryKey val id: String,
    val controlVersion: Long,
    val type: String, // APPLY_RESTRICTION | RELEASE_RESTRICTION | DISARM_SECRET
    val targetPolicyRevision: Long,
    val status: String = "PENDING", // PENDING | APPLIED | FAILED
    val requestedAt: Long,
    val appliedAt: Long? = null,
    val pendingIntentText: String? = null,
    val errorCode: String? = null
)

@Entity(tableName = "applied_policy")
data class AppliedPolicyEntity(
    @PrimaryKey val singletonId: Int = 1,
    val desiredRevision: Long = 1L,
    val verifiedRevision: Long = 0L,
    val lockTaskObserved: Boolean = false,
    val ownedChangesJson: String? = null,
    val lastVerifiedAt: Long = 0L
)

@Entity(
    tableName = "todo_item",
    indices = [
        Index(value = ["type"]),
        Index(value = ["isCompleted"])
    ]
)
data class TodoItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: String, // PERMANENT | SHORT_TERM
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val targetTime: String? = null,
    val completedCount: Int = 0,
    val completionRecordsJson: String = "[]",
    val sortOrder: Int = 0
)

