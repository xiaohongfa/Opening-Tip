package com.openingtip.core.platform

/**
 * 时间提供器接口（用于确定性测试与生产解耦）
 */
interface TimeProvider {
    /** 挂钟时间（Unix 毫秒） */
    fun nowWallMs(): Long

    /** 单调时钟（开机至今毫秒，不受手动修改时间影响） */
    fun nowElapsedMs(): Long

    /** 当前开机实例唯一标识 */
    fun currentBootId(): String
}

/**
 * 默认系统时间提供器
 */
class DefaultTimeProvider(
    private val bootId: String = "boot-${System.currentTimeMillis()}"
) : TimeProvider {
    override fun nowWallMs(): Long = System.currentTimeMillis()
    override fun nowElapsedMs(): Long = System.nanoTime() / 1_000_000L
    override fun currentBootId(): String = bootId
}

/**
 * 确定性测试时间提供器
 */
class TestTimeProvider(
    private var wallMs: Long = 1_000_000L,
    private var elapsedMs: Long = 100_000L,
    private var bootId: String = "test-boot-1"
) : TimeProvider {
    override fun nowWallMs(): Long = wallMs
    override fun nowElapsedMs(): Long = elapsedMs
    override fun currentBootId(): String = bootId

    fun setTime(wall: Long, elapsed: Long) {
        wallMs = wall
        elapsedMs = elapsed
    }

    fun advanceTime(durationMs: Long) {
        wallMs += durationMs
        elapsedMs += durationMs
    }

    fun reboot(newBootId: String = "test-boot-2") {
        bootId = newBootId
        elapsedMs = 1000L
    }
}
