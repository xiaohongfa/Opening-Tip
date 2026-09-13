# 开屏 Tip：受管设备 (Managed / Dedicated Device) 部署与恢复指南

版本：1.0 · 面向：系统测试工程师与交付部署人员

---

## 1. 概述与前置条件

受管设备版 (`managed` flavor) 利用 Android Enterprise 的 **Device Owner** 模式与 **Lock Task Mode** 实现系统底层严格的白名单应用隔离。在限制阶段（未输入意图前），除了 Tip Gate 和已授权的白名单应用，用户无法通过任何系统入口（通知、概览任务、分享、深链等）拉起其他应用。

### 部署前置条件
1. 目标测试设备需恢复出厂设置（Factory Reset），或为未添加任何 Google / 个人账号的全新设备。
2. 开启设备“开发者选项”并启用“USB 调试”。
3. 电脑端配置好 Android SDK Platform-Tools（`adb`）。

---

## 2. 部署步骤 (Device Owner Provisioning)

### 步骤 1：安装受管版 APK
```bash
adb install -r -t app-managed-debug.apk
```

### 步骤 2：设置 Device Owner
执行以下命令将 Tip 的 `DeviceAdminReceiver` 设置为系统设备所有者：
```bash
adb shell dpm set-device-owner com.openingtip/.enforcement.managed.TipDeviceAdminReceiver
```

> [!NOTE]
> 若设备提示 `java.lang.IllegalStateException: Not allowed to set the device owner because there are already some accounts on the device`，说明设备上存在已登录账号。必须在“设置 -> 账号”中移除全部账号，或执行出厂重置。

### 步骤 3：验证 Device Owner 状态
```bash
adb shell dumpsys device_policy | grep "admin=ComponentInfo{com.openingtip"
```
预期输出包含该组件名称，且属性包含 `isDeviceOwner=true`。

---

## 3. Lock Task 策略配置规范

在 `ManagedRestrictionController` 内部，系统策略按以下规则实施：

1. **设置允许 Lock Task 的软件包清单**：
   ```kotlin
   val packages = (whitelistPackages + context.packageName).toTypedArray()
   devicePolicyManager.setLockTaskPackages(adminComponent, packages)
   ```
2. **配置 Lock Task 特性开关**：
   - 保持真实安全锁屏与电话紧急呼叫：
   ```kotlin
   val features = DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                  DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                  DevicePolicyManager.LOCK_TASK_FEATURE_HOME
   devicePolicyManager.setLockTaskFeatures(adminComponent, features)
   ```
3. **进入 Lock Task 模式**：
   - Gate Activity 在前台执行 `activity.startLockTask()`。
   - 检查 `ActivityManager.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_LOCKED` 确认受管锁定生效。
4. **退出 Lock Task 模式**：
   - 用户成功输入意图切换到 `FULL` 阶段时，执行 `activity.stopLockTask()`。

---

## 4. 故障与紧急恢复指南 (Emergency Recovery)

为防止在开发或验收测试期间因异常崩溃导致设备无法退出锁定，提供以下两套救砖恢复手段：

### 方案 A：通过 ADB 紧急解除锁定与卸载
1. **强制停止受管任务模式**：
   ```bash
   adb shell am task lock stop
   ```
2. **清除 Device Owner 绑定并卸载应用**：
   ```bash
   # 在 DeviceAdminReceiver 中已开启 test-only 卸载支持或执行：
   adb shell dpm remove-active-admin com.openingtip/.enforcement.managed.TipDeviceAdminReceiver
   adb uninstall com.openingtip
   ```

### 方案 B：设备端安全恢复页面
若由于策略释放失败或数据库迁移异常进入 `RECOVERY_REQUIRED` 状态，应用会自动打开 `RecoveryActivity`：
- 提供“紧急释放限制”按钮（尝试直接调用底层 `stopLockTask()`）。
- 提供“重置本地数据库”安全选项，允许用户在输入设备锁屏密码后恢复正常系统使用。
