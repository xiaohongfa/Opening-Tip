# 开屏 Tip (Opening-Tip)

> 🧘 **开屏自律门禁工具，100% 纯血离线运行，意图审视与习惯打卡。**

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/xiaohongfa/Opening-Tip)
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
- 📈 **习惯打卡与热力图**：支持常驻打卡与单次待办，直观呈现打卡热力图统计；
- 📜 **历史意图与数据主权**：完整记录所有会话意图，支持全量个人数据一键导出与彻底物理抹除；
- 🛡️ **国产系统深度适配**：针对小米 HyperOS / MIUI 等系统的多任务清理（excludeFromRecents 防误杀）、锁屏亮屏唤起与后台弹出进行深度打磨；
- 🔒 **100% 纯血离线与隐私保护**：未声明任何网络访问权限（INTERNET 为 0），绝无任何云端上报或埋点分析，所有数据完全储存在用户本机私有 SQLite 数据库中。

---

## 👥 开发者与团队信息

* **开发者**：小红Fa
* **所属组织**：Hyperintell
* **版本号**：1.0.0
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
