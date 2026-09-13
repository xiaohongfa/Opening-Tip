package com.openingtip.feature.settings

data class PermissionStatusItem(
    val id: String,
    val title: String,
    val description: String,
    val isGranted: Boolean,
    val isVital: Boolean = true,
    val onFix: () -> Unit
)
