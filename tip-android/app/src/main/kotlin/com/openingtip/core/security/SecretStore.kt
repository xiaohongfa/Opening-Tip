package com.openingtip.core.security

import android.content.Context
import java.io.File

/**
 * 暗号安全持久化存储（应用私有内部目录，不包含在云备份中）
 */
class SecretStore(
    private val context: Context,
    private val secretManager: SecretManager = SecretManager()
) {
    private val secretFile: File by lazy {
        File(context.filesDir, "tip_secret_credential.dat")
    }

    fun save(credential: SecretCredential) {
        val serialized = secretManager.serialize(credential)
        secretFile.writeText(serialized, Charsets.UTF_8)
    }

    fun load(): SecretCredential? {
        if (!secretFile.exists()) return null
        val text = secretFile.readText(Charsets.UTF_8)
        return secretManager.deserialize(text)
    }

    fun hasSecret(): Boolean {
        return secretFile.exists() && secretFile.length() > 0
    }

    fun clear() {
        if (secretFile.exists()) {
            secretFile.delete()
        }
    }
}
