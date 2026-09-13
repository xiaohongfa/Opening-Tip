package com.openingtip.feature.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openingtip.core.model.TodoItem
import java.text.SimpleDateFormat
import java.util.*

/**
 * 单天热力格子数据模型
 */
data class HeatmapDay(
    val dateKey: String,          // yyyy-MM-dd
    val displayDate: String,       // M月d日 EEE
    val monthName: String,         // M月
    val count: Int,
    val isFuture: Boolean,
    val isToday: Boolean
)

/**
 * 习惯打卡 GitHub 风格热力图组件
 *
 * 特性：
 * 1. 7 行（周一至周日）x N 列（近 10-12 周）热力方格矩阵；
 * 2. 真实根据时间戳聚合每日打卡频次与四级渐变配色；
 * 3. 实时计算当前连续打卡天数（Streak）、历史最佳连续天数与总活跃天数；
 * 4. 支持点击格子查看具体日期的打卡记录详情。
 */
@Composable
fun HabitHeatmap(
    todo: TodoItem,
    modifier: Modifier = Modifier,
    numWeeks: Int = 10
) {
    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val displayFormat = remember { SimpleDateFormat("M月d日 EEEE", Locale.CHINESE) }
    val monthFormat = remember { SimpleDateFormat("M月", Locale.CHINESE) }

    // 1. 聚合每日打卡次数
    val dayCounts = remember(todo.completionRecordsJson) {
        val map = mutableMapOf<String, Int>()
        todo.getCompletionTimestamps().forEach { ts ->
            val key = dayFormat.format(Date(ts))
            map[key] = (map[key] ?: 0) + 1
        }
        map
    }

    // 2. 计算当前连续天数、历史最高连续、总天数
    val (currentStreak, maxStreak, activeDays) = remember(dayCounts) {
        calculateStreaks(dayCounts, dayFormat)
    }

    // 3. 构建近 numWeeks 周的日历网格数据
    val weeks = remember(dayCounts, numWeeks) {
        generateHeatmapGrid(numWeeks, dayCounts, dayFormat, displayFormat, monthFormat)
    }

    var selectedDay by remember { mutableStateOf<HeatmapDay?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // 头部：打卡连续天数统计
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "打卡热力图 (${todo.title})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "共活跃 $activeDays 天",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 统计指标徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatBadge(
                    label = "当前连续",
                    value = "$currentStreak 天",
                    icon = "🔥",
                    highlight = currentStreak > 0,
                    modifier = Modifier.weight(1f)
                )
                StatBadge(
                    label = "历史最佳",
                    value = "$maxStreak 天",
                    icon = "🏆",
                    highlight = false,
                    modifier = Modifier.weight(1f)
                )
                StatBadge(
                    label = "累计打卡",
                    value = "${todo.completedCount} 次",
                    icon = "📅",
                    highlight = false,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 热力图矩阵主体（左侧周一~周日标签 + 水平可滚动的周列）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // 左侧星期标签（周一、周三、周五、周日）
                Column(
                    modifier = Modifier.padding(top = 16.dp, end = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val labels = listOf("一", "", "三", "", "五", "", "日")
                    labels.forEach { label ->
                        Box(
                            modifier = Modifier.size(13.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (label.isNotEmpty()) {
                                Text(
                                    text = label,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }

                // 右侧可横向滚动的热力矩阵
                val scrollState = rememberScrollState()
                LaunchedEffect(weeks) {
                    // 自动滚动到最右侧显示最新日期
                    scrollState.scrollTo(scrollState.maxValue)
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState)
                ) {
                    // 月份标题行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        var lastMonth = ""
                        weeks.forEach { week ->
                            val firstDayMonth = week.firstOrNull { !it.isFuture }?.monthName ?: ""
                            val showMonth = if (firstDayMonth.isNotEmpty() && firstDayMonth != lastMonth) {
                                lastMonth = firstDayMonth
                                firstDayMonth
                            } else {
                                ""
                            }
                            Box(
                                modifier = Modifier.width(13.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (showMonth.isNotEmpty()) {
                                    Text(
                                        text = showMonth,
                                        fontSize = 9.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // 周列 x 7 天
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        weeks.forEach { week ->
                            Column(
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                week.forEach { day ->
                                    HeatmapCell(
                                        day = day,
                                        isSelected = selectedDay?.dateKey == day.dateKey,
                                        onClick = {
                                            if (!day.isFuture) {
                                                selectedDay = if (selectedDay?.dateKey == day.dateKey) null else day
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 底部：图例与点击交互详情
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 点击详情展示
                if (selectedDay != null) {
                    Text(
                        text = "${selectedDay!!.displayDate}：打卡 ${selectedDay!!.count} 次",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "点击格子可查看具体打卡频次",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }

                // 颜色图例
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text("少", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                    Spacer(modifier = Modifier.width(2.dp))
                    HeatmapLegendBox(HeatmapColors.level0)
                    HeatmapLegendBox(HeatmapColors.level1)
                    HeatmapLegendBox(HeatmapColors.level2)
                    HeatmapLegendBox(HeatmapColors.level3)
                    HeatmapLegendBox(HeatmapColors.level4)
                    Spacer(modifier = Modifier.width(2.dp))
                    Text("多", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(
    day: HeatmapDay,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    if (day.isFuture) {
        Box(modifier = Modifier.size(13.dp))
        return
    }

    val cellColor = when {
        day.count == 0 -> HeatmapColors.level0
        day.count == 1 -> HeatmapColors.level1
        day.count in 2..3 -> HeatmapColors.level2
        day.count in 4..5 -> HeatmapColors.level3
        else -> HeatmapColors.level4
    }

    val borderModifier = when {
        isSelected -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(2.5.dp))
        day.isToday -> Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(2.5.dp))
        else -> Modifier
    }

    Box(
        modifier = Modifier
            .size(13.dp)
            .then(borderModifier)
            .clip(RoundedCornerShape(2.5.dp))
            .background(cellColor)
            .clickable { onClick() }
    )
}

@Composable
private fun HeatmapLegendBox(color: Color) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(color)
    )
}

@Composable
private fun StatBadge(
    label: String,
    value: String,
    icon: String,
    highlight: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$icon $value",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

object HeatmapColors {
    val level0 = Color(0xFFE8ECEF) // 无打卡 (柔和浅灰)
    val level1 = Color(0xFFA7E9AF) // 1 次 (浅嫩绿)
    val level2 = Color(0xFF57C96B) // 2-3 次 (鲜明绿)
    val level3 = Color(0xFF239A3B) // 4-5 次 (经典深绿)
    val level4 = Color(0xFF145D22) // 6+ 次 (高密度墨绿)
}

/**
 * 连续打卡计算
 */
private fun calculateStreaks(
    dayCounts: Map<String, Int>,
    dayFormat: SimpleDateFormat
): Triple<Int, Int, Int> {
    if (dayCounts.isEmpty()) return Triple(0, 0, 0)

    val activeDays = dayCounts.count { it.value > 0 }
    val todayCal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    // 1. 计算当前连续打卡天数 (从今天或昨天逆推)
    var currentStreak = 0
    val checkCal = todayCal.clone() as Calendar
    val todayKey = dayFormat.format(checkCal.time)
    val hasToday = (dayCounts[todayKey] ?: 0) > 0

    if (hasToday) {
        currentStreak = 1
        checkCal.add(Calendar.DAY_OF_YEAR, -1)
    } else {
        // 检查昨天是否打卡，若昨天打卡则连续天数不断
        checkCal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayKey = dayFormat.format(checkCal.time)
        if ((dayCounts[yesterdayKey] ?: 0) > 0) {
            currentStreak = 1
            checkCal.add(Calendar.DAY_OF_YEAR, -1)
        }
    }

    if (currentStreak > 0) {
        while (true) {
            val key = dayFormat.format(checkCal.time)
            if ((dayCounts[key] ?: 0) > 0) {
                currentStreak++
                checkCal.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }
    }

    // 2. 计算历史最佳连续天数
    val sortedDates = dayCounts.keys
        .filter { (dayCounts[it] ?: 0) > 0 }
        .mapNotNull {
            try { dayFormat.parse(it) } catch (_: Exception) { null }
        }
        .sorted()

    var maxStreak = 0
    var tempStreak = 0
    var lastDate: Date? = null

    for (d in sortedDates) {
        if (lastDate == null) {
            tempStreak = 1
        } else {
            val diffMs = d.time - lastDate.time
            val diffDays = (diffMs / (24 * 3600 * 1000L)).toInt()
            if (diffDays == 1) {
                tempStreak++
            } else if (diffDays > 1) {
                tempStreak = 1
            }
        }
        lastDate = d
        if (tempStreak > maxStreak) {
            maxStreak = tempStreak
        }
    }
    maxStreak = maxStreak.coerceAtLeast(currentStreak)

    return Triple(currentStreak, maxStreak, activeDays)
}

/**
 * 生成近 numWeeks 周的矩阵数据（周一至周日）
 */
private fun generateHeatmapGrid(
    numWeeks: Int,
    dayCounts: Map<String, Int>,
    dayFormat: SimpleDateFormat,
    displayFormat: SimpleDateFormat,
    monthFormat: SimpleDateFormat
): List<List<HeatmapDay>> {
    val todayCal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayKey = dayFormat.format(todayCal.time)

    // 找到本周的周日（结束日）
    val endCal = todayCal.clone() as Calendar
    val currentDayOfWeek = endCal.get(Calendar.DAY_OF_WEEK) // 1: 周日, 2: 周一 ... 7: 周六
    val daysUntilSunday = if (currentDayOfWeek == Calendar.SUNDAY) 0 else 8 - currentDayOfWeek
    endCal.add(Calendar.DAY_OF_YEAR, daysUntilSunday)

    // 计算起始周的周一
    val totalDays = numWeeks * 7
    val startCal = endCal.clone() as Calendar
    startCal.add(Calendar.DAY_OF_YEAR, -totalDays + 1)

    val weeks = mutableListOf<List<HeatmapDay>>()
    val iterCal = startCal.clone() as Calendar

    for (w in 0 until numWeeks) {
        val weekDays = mutableListOf<HeatmapDay>()
        for (d in 0 until 7) {
            val dateKey = dayFormat.format(iterCal.time)
            val isFuture = iterCal.after(todayCal)
            val isToday = dateKey == todayKey
            val count = if (isFuture) 0 else (dayCounts[dateKey] ?: 0)

            weekDays.add(
                HeatmapDay(
                    dateKey = dateKey,
                    displayDate = displayFormat.format(iterCal.time),
                    monthName = monthFormat.format(iterCal.time),
                    count = count,
                    isFuture = isFuture,
                    isToday = isToday
                )
            )
            iterCal.add(Calendar.DAY_OF_YEAR, 1)
        }
        weeks.add(weekDays)
    }

    return weeks
}
