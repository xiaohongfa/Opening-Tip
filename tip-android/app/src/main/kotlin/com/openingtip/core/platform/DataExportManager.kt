package com.openingtip.core.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.openingtip.core.database.TipDatabase
import com.openingtip.core.model.TodoItem
import com.openingtip.core.model.TodoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 个人数据导出管理器
 *
 * 支持将用户的全部历史记录、习惯打卡日志、意图轨迹、应用使用明细导出为：
 * 1. 标准结构化 JSON 文件（机器可读、可作完整备份）；
 * 2. 优雅排版的 Markdown 自律报告文件（人类可读、便于归档与分享）。
 * 并通过 Android 系统分享面板（FileProvider）一键外发或保存到本地。
 */
object DataExportManager {

    suspend fun exportAndShare(context: Context, database: TipDatabase) {
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val readableDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val timeStampStr = fileDateFormat.format(Date(now))

                // 1. 查询数据库全量数据
                val sessions = database.sessionDao().getAllSessions()
                val appSummaries = database.usageDao().getAllAppSummaries()
                val todoEntities = database.todoDao().getAllTodos()
                val whitelist = database.whitelistDao().getWhitelist()
                val control = database.tipControlDao().getControl()

                val summariesBySession = appSummaries.groupBy { it.sessionId }
                val domainTodos = todoEntities.map {
                    TodoItem(
                        id = it.id,
                        title = it.title,
                        type = TodoType.valueOf(it.type),
                        isCompleted = it.isCompleted,
                        createdAt = it.createdAt,
                        completedAt = it.completedAt,
                        targetTime = it.targetTime,
                        completedCount = it.completedCount,
                        completionRecordsJson = it.completionRecordsJson,
                        sortOrder = it.sortOrder
                    )
                }

                // 2. 生成 JSON 内容
                val jsonContent = buildJsonExport(
                    exportTime = readableDateFormat.format(Date(now)),
                    exportTimeMs = now,
                    sessions = sessions,
                    summariesBySession = summariesBySession,
                    todos = domainTodos,
                    whitelist = whitelist,
                    readableDateFormat = readableDateFormat
                )

                // 3. 生成 Markdown 总结报告
                val markdownContent = buildMarkdownReport(
                    exportTime = readableDateFormat.format(Date(now)),
                    sessions = sessions,
                    summariesBySession = summariesBySession,
                    todos = domainTodos,
                    whitelist = whitelist,
                    readableDateFormat = readableDateFormat
                )

                // 4. 保存至导出目录
                val exportDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply {
                    if (!exists()) mkdirs()
                }

                val jsonFile = File(exportDir, "opening_tip_data_$timeStampStr.json")
                FileOutputStream(jsonFile).use { it.write(jsonContent.toByteArray(Charsets.UTF_8)) }

                val mdFile = File(exportDir, "opening_tip_summary_$timeStampStr.md")
                FileOutputStream(mdFile).use { it.write(markdownContent.toByteArray(Charsets.UTF_8)) }

                // 5. 调用系统分享面板
                val authority = "${context.packageName}.fileprovider"
                val jsonUri: Uri = FileProvider.getUriForFile(context, authority, jsonFile)
                val mdUri: Uri = FileProvider.getUriForFile(context, authority, mdFile)

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "*/*"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(jsonUri, mdUri))
                        putExtra(Intent.EXTRA_SUBJECT, "开屏 Tip 个人自律数据与报告 ($timeStampStr)")
                        putExtra(Intent.EXTRA_TEXT, "开屏 Tip 个人自律数据已导出（包含原始 JSON 数据与 Markdown 统计报告）。")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    val chooser = Intent.createChooser(shareIntent, "导出与分享个人自律数据").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(chooser)
                    Toast.makeText(context, "数据已成功导出至: ${jsonFile.parentFile?.name}/", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildJsonExport(
        exportTime: String,
        exportTimeMs: Long,
        sessions: List<com.openingtip.core.database.entity.SessionEntity>,
        summariesBySession: Map<String, List<com.openingtip.core.database.entity.SessionAppSummaryEntity>>,
        todos: List<TodoItem>,
        whitelist: List<com.openingtip.core.database.entity.WhitelistEntryEntity>,
        readableDateFormat: SimpleDateFormat
    ): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"appName\": \"OpeningTip\",\n")
        sb.append("  \"version\": \"1.0.0\",\n")
        sb.append("  \"exportedAt\": \"$exportTime\",\n")
        sb.append("  \"exportedAtMs\": $exportTimeMs,\n")

        // 统计
        val totalHabitPunchIns = todos.filter { it.type == TodoType.PERMANENT }.sumOf { it.completedCount }
        val totalSessionDurationSec = sessions.sumOf { it.durationMs } / 1000
        sb.append("  \"overview\": {\n")
        sb.append("    \"totalSessions\": ${sessions.size},\n")
        sb.append("    \"totalSessionDurationSeconds\": $totalSessionDurationSec,\n")
        sb.append("    \"totalTodos\": ${todos.size},\n")
        sb.append("    \"totalHabitPunchIns\": $totalHabitPunchIns\n")
        sb.append("  },\n")

        // 习惯与待办列表
        sb.append("  \"todos\": [\n")
        todos.forEachIndexed { i, todo ->
            val timestamps = todo.getCompletionTimestamps()
            sb.append("    {\n")
            sb.append("      \"id\": \"${escapeJson(todo.id)}\",\n")
            sb.append("      \"title\": \"${escapeJson(todo.title)}\",\n")
            sb.append("      \"type\": \"${todo.type.name}\",\n")
            sb.append("      \"isCompleted\": ${todo.isCompleted},\n")
            sb.append("      \"createdAt\": \"${readableDateFormat.format(Date(todo.createdAt))}\",\n")
            sb.append("      \"completedAt\": ${todo.completedAt?.let { "\"${readableDateFormat.format(Date(it))}\"" } ?: "null"},\n")
            sb.append("      \"targetTime\": ${todo.targetTime?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
            sb.append("      \"completedCount\": ${todo.completedCount},\n")
            sb.append("      \"punchInTimestamps\": [${timestamps.joinToString(",")}]\n")
            sb.append(if (i == todos.size - 1) "    }\n" else "    },\n")
        }
        sb.append("  ],\n")

        // 白名单
        sb.append("  \"whitelist\": [\n")
        whitelist.forEachIndexed { i, w ->
            sb.append("    {\n")
            sb.append("      \"packageName\": \"${escapeJson(w.packageName)}\",\n")
            sb.append("      \"label\": \"${escapeJson(w.labelCache ?: w.packageName)}\"\n")
            sb.append(if (i == whitelist.size - 1) "    }\n" else "    },\n")
        }
        sb.append("  ],\n")

        // 会话历史与应用使用
        sb.append("  \"sessions\": [\n")
        sessions.forEachIndexed { i, s ->
            val appList = summariesBySession[s.id] ?: emptyList()
            sb.append("    {\n")
            sb.append("      \"id\": \"${escapeJson(s.id)}\",\n")
            sb.append("      \"intentText\": ${s.intentText?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
            sb.append("      \"startWallMs\": ${s.startWallMs},\n")
            sb.append("      \"startTime\": \"${readableDateFormat.format(Date(s.startWallMs))}\",\n")
            sb.append("      \"endWallMs\": ${s.endWallMs ?: "null"},\n")
            sb.append("      \"endTime\": ${s.endWallMs?.let { "\"${readableDateFormat.format(Date(it))}\"" } ?: "null"},\n")
            sb.append("      \"durationSeconds\": ${s.durationMs / 1000},\n")
            sb.append("      \"status\": \"${s.status}\",\n")
            sb.append("      \"endReason\": ${s.endReason?.let { "\"${escapeJson(it)}\"" } ?: "null"},\n")
            sb.append("      \"appUsages\": [\n")
            appList.forEachIndexed { j, app ->
                sb.append("        {\n")
                sb.append("          \"packageName\": \"${escapeJson(app.packageName)}\",\n")
                sb.append("          \"appName\": \"${escapeJson(app.labelSnapshot ?: app.packageName)}\",\n")
                sb.append("          \"durationSeconds\": ${app.durationMs / 1000},\n")
                sb.append("          \"phase\": \"${app.phase}\"\n")
                sb.append(if (j == appList.size - 1) "        }\n" else "        },\n")
            }
            sb.append("      ]\n")
            sb.append(if (i == sessions.size - 1) "    }\n" else "    },\n")
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    private fun buildMarkdownReport(
        exportTime: String,
        sessions: List<com.openingtip.core.database.entity.SessionEntity>,
        summariesBySession: Map<String, List<com.openingtip.core.database.entity.SessionAppSummaryEntity>>,
        todos: List<TodoItem>,
        whitelist: List<com.openingtip.core.database.entity.WhitelistEntryEntity>,
        readableDateFormat: SimpleDateFormat
    ): String {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val sb = StringBuilder()

        sb.append("# 📱 开屏 Tip 个人自律数据报告\n\n")
        sb.append("> **生成时间**：$exportTime  \n")
        sb.append("> **导出范围**：全部历史记录、习惯打卡日志与应用使用明细  \n\n")

        // 1. 数据总览
        val totalHabitPunchIns = todos.filter { it.type == TodoType.PERMANENT }.sumOf { it.completedCount }
        val totalDurationMinutes = sessions.sumOf { it.durationMs } / (60 * 1000)
        val hours = totalDurationMinutes / 60
        val mins = totalDurationMinutes % 60

        sb.append("## 📊 1. 自律总览\n\n")
        sb.append("- **累计解锁进入手机**：${sessions.size} 次\n")
        sb.append("- **累计手机使用时长**：${hours} 小时 ${mins} 分钟\n")
        sb.append("- **配置待办与习惯数**：${todos.size} 个\n")
        sb.append("- **累计习惯打卡次数**：${totalHabitPunchIns} 次\n")
        sb.append("- **可用白名单软件数**：${whitelist.size} 个\n\n")

        // 2. 习惯打卡与待办明细
        sb.append("## 🎯 2. 习惯与待办打卡看板\n\n")
        val habits = todos.filter { it.type == TodoType.PERMANENT }
        if (habits.isNotEmpty()) {
            sb.append("### 常驻习惯打卡记录\n\n")
            sb.append("| 习惯名称 | 累计打卡 | 最近打卡时间 | 打卡时间记录 (近期) |\n")
            sb.append("| :--- | :---: | :--- | :--- |\n")
            habits.forEach { habit ->
                val timestamps = habit.getCompletionTimestamps()
                val recentStr = timestamps.takeLast(5).joinToString(", ") { readableDateFormat.format(Date(it)) }
                val lastStr = timestamps.lastOrNull()?.let { readableDateFormat.format(Date(it)) } ?: "无"
                sb.append("| **${habit.title}** | **${habit.completedCount} 次** | $lastStr | ${recentStr.ifBlank { "暂无" }} |\n")
            }
            sb.append("\n")
        }

        val shortTodos = todos.filter { it.type == TodoType.SHORT_TERM }
        if (shortTodos.isNotEmpty()) {
            sb.append("### 短期待办任务\n\n")
            sb.append("| 任务内容 | 状态 | 目标时间 | 创建时间 |\n")
            sb.append("| :--- | :---: | :---: | :--- |\n")
            shortTodos.forEach { t ->
                val statusStr = if (t.isCompleted) "✅ 已完成" else "⏳ 待处理"
                val targetStr = t.targetTime ?: "未设限"
                sb.append("| ${t.title} | $statusStr | $targetStr | ${readableDateFormat.format(Date(t.createdAt))} |\n")
            }
            sb.append("\n")
        }

        // 3. 意图与历史足迹
        sb.append("## ⏳ 3. 开屏意图与会话足迹\n\n")
        if (sessions.isEmpty()) {
            sb.append("暂无历史会话记录。\n\n")
        } else {
            val sessionsByDay = sessions.groupBy { dayFormat.format(Date(it.startWallMs)) }
            sessionsByDay.forEach { (day, daySessions) ->
                sb.append("### 📅 日期：$day (${daySessions.size} 次会话)\n\n")
                daySessions.forEach { s ->
                    val startStr = timeFormat.format(Date(s.startWallMs))
                    val endStr = s.endWallMs?.let { timeFormat.format(Date(it)) } ?: "--:--"
                    val durSec = s.durationMs / 1000
                    val durStr = if (durSec < 60) "${durSec}秒" else "${durSec / 60}分${durSec % 60}秒"
                    val intent = s.intentText ?: "未填写意图，仅使用白名单软件"

                    sb.append("- **$startStr ~ $endStr** ($durStr) — **意图**：`$intent`\n")
                    val apps = summariesBySession[s.id] ?: emptyList()
                    if (apps.isNotEmpty()) {
                        val appDetail = apps.joinToString("、") { app ->
                            val sec = app.durationMs / 1000
                            "${app.labelSnapshot ?: app.packageName} (${if (sec < 60) "${sec}秒" else "${sec / 60}分"})"
                        }
                        sb.append("  - *前台使用*：$appDetail\n")
                    }
                }
                sb.append("\n")
            }
        }

        sb.append("---\n*本报告由 开屏 Tip 离线安全生成并导出*\n")
        return sb.toString()
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
