package com.openingtip.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 暗号凭据载荷
 */
data class SecretCredential(
    val salt: ByteArray,
    val verifier: ByteArray,
    val iterations: Int,
    val algorithm: String = "PBKDF2WithHmacSHA256",
    val version: Int = 1
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SecretCredential
        return salt.contentEquals(other.salt) &&
                verifier.contentEquals(other.verifier) &&
                iterations == other.iterations &&
                version == other.version
    }

    override fun hashCode(): Int {
        var result = salt.contentHashCode()
        result = 31 * result + verifier.contentHashCode()
        result = 31 * result + iterations
        result = 31 * result + version
        return result
    }
}

/**
 * 暗号管理与单向安全校验服务
 */
class SecretManager(
    private val defaultIterations: Int = 10_000 // 测试/默认迭代次数，生产可升至 600,000
) {
    private val secureRandom = SecureRandom()

    /**
     * 注册/创建新暗号
     * 规则：
     * 1. 拒绝首尾空格
     * 2. Unicode NFC 规范化
     * 3. 至少 16 字节随机 salt
     * 4. PBKDF2WithHmacSHA256 派生
     */
    fun createCredential(rawSecret: String, iterations: Int = defaultIterations): SecretCredential {
        require(rawSecret.isNotEmpty()) { "暗号不能为空" }
        require(rawSecret == rawSecret.trim()) { "暗号不能包含首尾空格以避免输入混淆" }

        val normalized = Normalizer.normalize(rawSecret, Normalizer.Form.NFC)
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)

        val verifier = hashSecret(normalized, salt, iterations)
        return SecretCredential(
            salt = salt,
            verifier = verifier,
            iterations = iterations
        )
    }

    /**
     * 校验输入文本是否匹配暗号
     * 规则：
     * 1. NFC 规范化
     * 2. 不 trim 目标字符串（保持精确比较）
     * 3. 常量时间比较 (MessageDigest.isEqual)
     */
    fun verify(input: String, credential: SecretCredential): Boolean {
        if (input.isEmpty()) return false
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFC)
        val candidate = hashSecret(normalized, credential.salt, credential.iterations)
        return MessageDigest.isEqual(candidate, credential.verifier)
    }

    private fun hashSecret(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    fun serialize(credential: SecretCredential): String {
        val b64Salt = java.util.Base64.getEncoder().encodeToString(credential.salt)
        val b64Verifier = java.util.Base64.getEncoder().encodeToString(credential.verifier)
        return "${credential.version}:${credential.algorithm}:${credential.iterations}:$b64Salt:$b64Verifier"
    }

    fun deserialize(text: String): SecretCredential? {
        return try {
            val parts = text.trim().split(":")
            if (parts.size != 5) return null
            val version = parts[0].toInt()
            val algorithm = parts[1]
            val iterations = parts[2].toInt()
            val salt = java.util.Base64.getDecoder().decode(parts[3])
            val verifier = java.util.Base64.getDecoder().decode(parts[4])
            SecretCredential(
                salt = salt,
                verifier = verifier,
                iterations = iterations,
                algorithm = algorithm,
                version = version
            )
        } catch (_: Exception) {
            null
        }
    }
}
