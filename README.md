# 开屏 Tip (Opening-Tip)

> 🧘 **开屏自律门禁工具，100% 纯血离线运行，意图审视与习惯打卡。**

[![Version](https://img.shields.io/badge/version-1.0.5-blue.svg)](https://github.com/xiaohongfa/Opening-Tip)
[![Developer](https://img.shields.io/badge/Developer-%E5%B0%8F%E7%BA%A2Fa-brightgreen.svg)](https://github.com/xiaohongfa)
[![Organization](https://img.shields.io/badge/Organization-Hyperintell-orange.svg)](https://github.com/xiaohongfa)
[![Offline](https://img.shields.io/badge/Network-0%20Permissions%20(Offline)-success.svg)](https://github.com/xiaohongfa/Opening-Tip)
[![License](https://img.shields.io/badge/License-Apache%202.0-lightgrey.svg)](LICENSE)

---

## 📖 项目简介

**开屏 Tip** 是一款旨在帮助现代人摆脱“无意识刷手机”与“注意力碎片化”的开源 Android 门禁工具。每次解锁点亮屏幕时，应用会以全屏门禁引导用户审视当前开屏意图，或完成既定的常驻打卡与待办事项，从而重建对数字生活的掌控力。

---

## ✨ 核心特性

- 🚪 **开屏意图审视**：解锁屏幕即唤起全屏门禁，审视本次开屏的真实目标；
- ⏱️ **预计使用时间预算 (Time Budget)**：支持快捷选定本次开屏计划使用时长（包含 **1分钟**、3分钟、5分钟、10分钟、15分钟、30分钟及不限）；
- 🎯 **意图达成主动研判展示**：在下次开屏时，系统基于上次的预计时长与实际使用时间自动研判并醒目展示 `🟢 按时达成` 或 `🟠 超时使用`，用户无需手动答题，自律反馈一目了然；
- 🏷️ **自定义快捷意图标签**：内置高频动作标签，并支持用户自由增删改与持久化自定义（如回工作、背单词、查资料等）；
- 📊 **今日自律看板**：开屏顶部直观呈现当前高清时钟、**今日第 N 次点亮屏幕次数**以及**今日累计使用时长**；
- 🌿 **日常健康微习惯提示**：门禁底部呈现深呼吸、喝温水润喉、眺望远方、舒展肩颈等温馨身心关怀提示；
- 🥋 **无障碍金钟罩与0ms防逃逸 (杀不掉)**：集成 Android 系统内核最高保活级别的无障碍服务（`BIND_ACCESSIBILITY_SERVICE`），具备 `PERSISTENT_PROC` 优先级，豁免系统多任务一键清理；未输入意图前，0ms瞬间截获桌面滑动手势与按键，强制重回门禁；
- 🩺 **权限体检与系统防杀中心**：全自动化体检无障碍金钟罩、使用情况统计、悬浮窗、后台弹出界面、电池无限制与开机自启动，提供顶部醒目警示与逐项直达修复通道；
- 📈 **习惯打卡与热力图**：支持常驻打卡与单次待办，直观呈现打卡热力图统计；
- 📜 **历史意图与数据主权**：完整记录所有会话意图，支持全量个人数据一键导出与彻底物理抹除；
- 🛡️ **国产系统深度适配**：针对小米 HyperOS / MIUI 等系统的后台弹出权限（AppOps 10020）、多任务防误杀（`excludeFromRecents`）与锁屏穿透进行深度打磨；
- 🔒 **100% 纯血离线与隐私保护**：未声明任何网络访问权限（INTERNET 为 0），绝无任何云端上报或埋点分析，所有数据完全储存在用户本机私有 SQLite 数据库中。

---

## 👥 开发者与团队信息

* **开发者**：小红Fa
* **所属组织**：Hyperintell
* **版本号**：v1.0.5 (正式版)
* **开源协议**：Apache-2.0

---

## 🛠️ 构建与编译

本项目基于现代 Android 架构构建（Jetpack Compose + Room + Kotlin Coroutines + Gradle 8.11+ + JDK 21）。

### 编译 Release 版本
`ash
cd tip-android
./gradlew :app:assembleConsumerRelease
`
编译产物位于：
	ip-android/app/build/outputs/apk/consumer/release/app-consumer-release.apk

---

## 📄 详细设计规范

更多关于架构设计、会话状态机、特权模式（Managed Device Admin）及白名单系统的详细技术规范，请参阅：
- [开屏Tip-Android工程实现规范.md](./开屏Tip-Android工程实现规范.md)
