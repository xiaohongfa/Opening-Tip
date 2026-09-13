package com.openingtip.feature.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

data class AppItem(
    val packageName: String,
    val appName: String,
    val isSelected: Boolean = false
)

/**
 * 首次安装与配置向导界面（严格对应规范 2.1 节 6 步流程）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    currentStep: Int,
    availableApps: List<AppItem>,
    isUsageAccessGranted: Boolean,
    isOverlayPermissionGranted: Boolean,
    isManagedMode: Boolean,
    onStepChange: (Int) -> Unit,
    onAppToggle: (String) -> Unit,
    onConfirmEmptyWhitelist: () -> Unit,
    onSaveSecret: (String) -> Boolean,
    onRequestUsagePermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onEnableTipMode: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开屏 Tip 配置向导 (${currentStep + 1}/6)") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when (currentStep) {
                    0 -> StepIntro()
                    1 -> StepWhitelist(
                        apps = availableApps,
                        onAppToggle = onAppToggle
                    )
                    2 -> StepSecret(onSaveSecret = onSaveSecret, onNext = { onStepChange(3) })
                    3 -> StepPermissions(
                        isUsageAccessGranted = isUsageAccessGranted,
                        isOverlayPermissionGranted = isOverlayPermissionGranted,
                        onRequestUsagePermission = onRequestUsagePermission,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onOpenAppDetails = onOpenAppDetails,
                        onRefreshPermissions = onRefreshPermissions
                    )
                    4 -> StepModeCapability(
                        isManagedMode = isManagedMode
                    )
                    5 -> StepEnable(
                        isManagedMode = isManagedMode,
                        onEnableTipMode = onEnableTipMode
                    )
                }
            }

            // 底部导航动作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (currentStep > 0 && currentStep != 2) {
                    OutlinedButton(onClick = { onStepChange(currentStep - 1) }) {
                        Text("上一步")
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                if (currentStep == 1) {
                    val hasSelected = availableApps.any { it.isSelected }
                    Button(
                        onClick = {
                            if (!hasSelected) onConfirmEmptyWhitelist()
                            onStepChange(2)
                        }
                    ) {
                        Text(if (hasSelected) "保存白名单并继续" else "明确确认空白名单并继续")
                    }
                } else if (currentStep in listOf(0, 3, 4)) {
                    Button(
                        onClick = { onStepChange(currentStep + 1) },
                        enabled = true // 绝不锁死用户
                    ) {
                        Text("下一步")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIntro() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "欢迎使用 开屏 Tip",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "开屏 Tip 是一款面向深度自律的时间审视工具。\n\n" +
                    "• 未填写意图时，仅允许使用您指定的白名单软件；\n" +
                    "• 输入本次打开手机的目标后，方可进入无限制模式；\n" +
                    "• 每次打开手机，首先回顾上一次使用的实际耗时与应用明细；\n" +
                    "• 所有数据 100% 保留在本机本地，绝不上云，不索取任何无障碍或通讯录隐私权限。",
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun StepWhitelist(
    apps: List<AppItem>,
    onAppToggle: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = apps.filter { it.appName.contains(searchQuery, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "第一步：选择限制阶段可用的白名单软件",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "未输入意图时，仅这些应用对您可见。若不选择任何软件，将默认为空白名单（无任何可用软件，专注输入意图）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("搜索已安装应用") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filtered) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAppToggle(app.packageName) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = app.isSelected,
                        onCheckedChange = { onAppToggle(app.packageName) }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(app.appName, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun StepSecret(
    onSaveSecret: (String) -> Boolean,
    onNext: () -> Unit
) {
    var secret1 by remember { mutableStateOf("") }
    var secret2 by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "第二步：设置关闭暗号",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "在开屏 Gate 界面输入此暗号可彻底退出限制。暗号仅用于关闭 Tip，不会清空历史记录。请勿包含首尾空格。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = secret1,
            onValueChange = { secret1 = it; errorMsg = null },
            label = { Text("输入关闭暗号") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = secret2,
            onValueChange = { secret2 = it; errorMsg = null },
            label = { Text("再次输入暗号确认") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        if (errorMsg != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(errorMsg!!, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                if (secret1.isEmpty()) {
                    errorMsg = "暗号不能为空"
                } else if (secret1 != secret1.trim()) {
                    errorMsg = "暗号不能包含首尾空格"
                } else if (secret1 != secret2) {
                    errorMsg = "两次输入的暗号不一致"
                } else {
                    val success = onSaveSecret(secret1)
                    if (success) onNext() else errorMsg = "暗号保存失败"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存暗号并继续")
        }
    }
}

@Composable
private fun StepPermissions(
    isUsageAccessGranted: Boolean,
    isOverlayPermissionGranted: Boolean,
    onRequestUsagePermission: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onOpenAppDetails: () -> Unit,
    onRefreshPermissions: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "第三步：必要系统权限配置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            FilledTonalButton(onClick = onRefreshPermissions) {
                Text("🔄 刷新检测")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("1. 使用情况访问权限 (必需)", fontWeight = FontWeight.Bold)
                Text(
                    "用于在本地离线统计上一次会话的前台应用使用时长与明细。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (isUsageAccessGranted) "✓ 已授权" else "✗ 未授权")
                    Button(onClick = onRequestUsagePermission) {
                        Text(if (isUsageAccessGranted) "重新设置" else "前往授权")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("2. 悬浮窗 / 显示在其他应用上层 (必需)", fontWeight = FontWeight.Bold)
                Text(
                    "用于在系统指纹/密码解锁后，将自律门禁全屏呈现在最上层。无需替换手机系统桌面！",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (isOverlayPermissionGranted) "✓ 已授权" else "✗ 未授权")
                    Button(onClick = onRequestOverlayPermission) {
                        Text(if (isOverlayPermissionGranted) "重新设置" else "前往授权")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("3. 后台弹出界面与自启动 (国产手机强烈推荐)", fontWeight = FontWeight.Bold)
                Text(
                    "针对小米 HyperOS/MIUI、华为鸿蒙等机型：请前往应用设置，开启「后台弹出界面」与「自启动」，防止系统误拦截门禁。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onOpenAppDetails) {
                        Text("前往应用设置")
                    }
                }
            }
        }
    }
}

@Composable
private fun StepModeCapability(
    isManagedMode: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "第四步：自律模式与边界确认",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("当前自律运行模式：", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                if (isManagedMode) {
                    Text("✓ 已验证的受管强限制模式 (Managed Device Owner)\n通过系统底层 Lock Task 严格封锁非白名单应用与后台任务。", color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("ℹ 全屏无感自律门禁模式 (Full-Screen Gate Guard)\n✓ 原装系统桌面 100% 完整保留，壁纸与小部件完好无损；\n✓ 全面屏侧滑手势 100% 满血保留；\n✓ 指纹/面容/密码解锁完成后自动弹出开屏门禁；\n✓ 声明本次意图后立即退场，无感直达原装桌面。", color = MaterialTheme.colorScheme.secondary)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("💡 纯净自律原则说明", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "开屏 Tip 不内置任何天气预报或娱乐组件。若日常需要查看天气或日程，只需在第二步中将您常用的天气/日历软件勾选加入白名单即可随时使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StepEnable(
    isManagedMode: Boolean,
    onEnableTipMode: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "一切就绪！",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "白名单已保存、暗号已就绪、必要权限已核验通过。\n点击下方按钮立即开启 Tip 模式，开启后将立即呈现开屏审视 Gate 界面。",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onEnableTipMode,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) {
            Text("开启 Tip 模式", style = MaterialTheme.typography.titleMedium)
        }
    }
}
