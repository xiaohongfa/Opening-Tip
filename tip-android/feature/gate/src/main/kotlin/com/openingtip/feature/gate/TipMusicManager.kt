package com.openingtip.feature.gate

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.sin

data class MusicTrack(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val isBuiltIn: Boolean = false,
    val filePath: String? = null
)

/**
 * 开屏自律门禁纯血离线音乐管理器
 * 1. 100% 离线运行，内置白噪音合成（雨声、海浪、禅音、低频专注）；
 * 2. 支持用户导入任意本地音频文件并持久保存在应用私有沙盒目录中；
 * 3. 单例状态管理，向 Compose 响应式暴露播放状态。
 */
object TipMusicManager {
    private const val TAG = "TipMusicManager"
    private var mediaPlayer: MediaPlayer? = null

    var isPlaying by mutableStateOf(false)
        private set

    var currentTrack by mutableStateOf<MusicTrack?>(null)
        private set

    var playlist by mutableStateOf<List<MusicTrack>>(emptyList())
        private set

    private var isInitialized = false

    fun initIfNeeded(context: Context) {
        if (isInitialized) return
        isInitialized = true
        ensureBuiltInTracks(context)
        refreshPlaylist(context)
        if (currentTrack == null && playlist.isNotEmpty()) {
            currentTrack = playlist.first()
        }
    }

    fun refreshPlaylist(context: Context) {
        val list = mutableListOf<MusicTrack>()

        // 1. 本地导入的音乐
        val savedDir = File(context.filesDir, "saved_music")
        if (savedDir.exists() && savedDir.isDirectory) {
            savedDir.listFiles()?.filter { it.isFile && (it.extension.lowercase() in listOf("mp3", "m4a", "wav", "ogg", "flac", "aac")) }
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    list.add(
                        MusicTrack(
                            id = "local_${file.name}",
                            title = file.nameWithoutExtension,
                            subtitle = "本地存好的音乐",
                            isBuiltIn = false,
                            filePath = file.absolutePath
                        )
                    )
                }
        }

        // 2. 内置专注与白噪音音频
        val builtInDir = File(context.filesDir, "builtin_ambient")
        val builtInDefs = listOf(
            Triple("rain.wav", "🌧️ 细雨冥想", "舒缓白噪音 · 抚平浮躁"),
            Triple("waves.wav", "🌊 潮汐浪涌", "深呼吸节奏 · 意图定力"),
            Triple("zen.wav", "🧘 432Hz 颂钵", "治愈频率 · 深度专注"),
            Triple("brown.wav", "☕ 深度白噪", "低频暖声 · 屏蔽打扰")
        )
        for ((fileName, name, desc) in builtInDefs) {
            val f = File(builtInDir, fileName)
            if (f.exists()) {
                list.add(
                    MusicTrack(
                        id = "builtin_$fileName",
                        title = name,
                        subtitle = desc,
                        isBuiltIn = true,
                        filePath = f.absolutePath
                    )
                )
            }
        }

        playlist = list
        if (currentTrack != null) {
            val exists = playlist.find { it.id == currentTrack?.id }
            if (exists != null) {
                currentTrack = exists
            } else if (playlist.isNotEmpty()) {
                currentTrack = playlist.first()
            }
        }
    }

    fun togglePlay(context: Context) {
        initIfNeeded(context)
        if (isPlaying) {
            pause()
        } else {
            val track = currentTrack ?: playlist.firstOrNull() ?: return
            playTrack(context, track)
        }
    }

    fun playTrack(context: Context, track: MusicTrack) {
        initIfNeeded(context)
        try {
            stop()
            currentTrack = track
            val path = track.filePath ?: return
            val file = File(path)
            if (!file.exists()) {
                Log.w(TAG, "Audio file does not exist: $path")
                refreshPlaylist(context)
                return
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(path)
                isLooping = true
                prepare()
                start()
            }
            isPlaying = true
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio track: ${e.message}", e)
            stop()
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                }
            }
            isPlaying = false
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            }
        } catch (_: Exception) {}
        mediaPlayer = null
        isPlaying = false
    }

    fun next(context: Context) {
        if (playlist.isEmpty()) return
        val currentIdx = playlist.indexOfFirst { it.id == currentTrack?.id }
        val nextIdx = if (currentIdx < 0 || currentIdx >= playlist.size - 1) 0 else currentIdx + 1
        playTrack(context, playlist[nextIdx])
    }

    fun playNext(context: Context) {
        next(context)
    }

    fun importLocalAudio(context: Context, uri: Uri, displayName: String?): MusicTrack? {
        return try {
            val dir = File(context.filesDir, "saved_music").apply { if (!exists()) mkdirs() }
            val rawName = displayName ?: "audio_${System.currentTimeMillis()}.mp3"
            val sanitized = rawName.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
            val targetFile = File(dir, sanitized)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            refreshPlaylist(context)
            val newTrack = playlist.find { it.filePath == targetFile.absolutePath }
            if (newTrack != null) {
                currentTrack = newTrack
            }
            newTrack
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import local audio: ${e.message}", e)
            null
        }
    }

    fun deleteTrack(context: Context, track: MusicTrack): Boolean {
        if (track.isBuiltIn) return false
        val path = track.filePath ?: return false
        val file = File(path)
        val wasCurrent = currentTrack?.id == track.id
        if (wasCurrent) {
            stop()
        }
        val deleted = try { file.delete() } catch (_: Exception) { false }
        refreshPlaylist(context)
        if (wasCurrent && playlist.isNotEmpty()) {
            currentTrack = playlist.first()
        }
        return deleted
    }

    private fun ensureBuiltInTracks(context: Context) {
        try {
            val dir = File(context.filesDir, "builtin_ambient").apply { if (!exists()) mkdirs() }
            val sampleRate = 22050

            // 1. 雨声 (Pink noise)
            val rainFile = File(dir, "rain.wav")
            if (!rainFile.exists()) {
                val samples = generatePinkNoiseSamples(sampleRate, durationSeconds = 6)
                writeWav(rainFile, sampleRate, samples)
            }

            // 2. 海浪 (Wave-modulated noise)
            val wavesFile = File(dir, "waves.wav")
            if (!wavesFile.exists()) {
                val samples = generateWavesSamples(sampleRate, durationSeconds = 8)
                writeWav(wavesFile, sampleRate, samples)
            }

            // 3. 432Hz 颂钵 (Zen Chime)
            val zenFile = File(dir, "zen.wav")
            if (!zenFile.exists()) {
                val samples = generateZenSamples(sampleRate, durationSeconds = 6)
                writeWav(zenFile, sampleRate, samples)
            }

            // 4. 低频专注 (Brown noise)
            val brownFile = File(dir, "brown.wav")
            if (!brownFile.exists()) {
                val samples = generateBrownNoiseSamples(sampleRate, durationSeconds = 6)
                writeWav(brownFile, sampleRate, samples)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed generating ambient audio: ${e.message}", e)
        }
    }

    private fun generatePinkNoiseSamples(sampleRate: Int, durationSeconds: Int): ShortArray {
        val total = sampleRate * durationSeconds
        val out = ShortArray(total)
        val random = Random(42)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        for (i in 0 until total) {
            val white = random.nextDouble() * 2.0 - 1.0
            b0 = 0.99886 * b0 + white * 0.0555179
            b1 = 0.99332 * b1 + white * 0.0750759
            b2 = 0.96900 * b2 + white * 0.1538520
            b3 = 0.86650 * b3 + white * 0.3104856
            b4 = 0.55000 * b4 + white * 0.5329522
            b5 = -0.7616 * b5 - white * 0.0168980
            val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
            b6 = white * 0.115926
            val clamped = (pink * 3500.0).coerceIn(-32000.0, 32000.0)
            out[i] = clamped.toInt().toShort()
        }
        return out
    }

    private fun generateWavesSamples(sampleRate: Int, durationSeconds: Int): ShortArray {
        val total = sampleRate * durationSeconds
        val out = ShortArray(total)
        val random = Random(123)
        var last = 0.0
        for (i in 0 until total) {
            val white = random.nextDouble() * 2.0 - 1.0
            last = (last + (0.02 * white)) / 1.02
            // 周期波浪调制
            val env = 0.3 + 0.7 * (0.5 * (1.0 + sin(2.0 * Math.PI * (i.toDouble() / (sampleRate * 4.0)))))
            val sample = last * env * 24000.0
            out[i] = sample.coerceIn(-32000.0, 32000.0).toInt().toShort()
        }
        return out
    }

    private fun generateZenSamples(sampleRate: Int, durationSeconds: Int): ShortArray {
        val total = sampleRate * durationSeconds
        val out = ShortArray(total)
        val freq1 = 432.0
        val freq2 = 528.0
        for (i in 0 until total) {
            val t = i.toDouble() / sampleRate
            val decay = (1.0 - (t % 3.0) / 3.0)
            val sig = (sin(2.0 * Math.PI * freq1 * t) * 0.6 + sin(2.0 * Math.PI * freq2 * t) * 0.4) * decay * 0.5
            out[i] = (sig * 20000.0).coerceIn(-32000.0, 32000.0).toInt().toShort()
        }
        return out
    }

    private fun generateBrownNoiseSamples(sampleRate: Int, durationSeconds: Int): ShortArray {
        val total = sampleRate * durationSeconds
        val out = ShortArray(total)
        val random = Random(999)
        var last = 0.0
        for (i in 0 until total) {
            val white = random.nextDouble() * 2.0 - 1.0
            last = (last + (0.02 * white)) / 1.02
            val sample = last * 30000.0
            out[i] = sample.coerceIn(-32000.0, 32000.0).toInt().toShort()
        }
        return out
    }

    private fun writeWav(file: File, sampleRate: Int, samples: ShortArray) {
        val numBytes = samples.size * 2
        val totalDataLen = numBytes + 36
        val byteRate = sampleRate * 1 * 2
        FileOutputStream(file).use { out ->
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
            header[20] = 1; header[21] = 0
            header[22] = 1; header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = 2; header[33] = 0
            header[34] = 16; header[35] = 0
            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
            header[40] = (numBytes and 0xff).toByte()
            header[41] = ((numBytes shr 8) and 0xff).toByte()
            header[42] = ((numBytes shr 16) and 0xff).toByte()
            header[43] = ((numBytes shr 24) and 0xff).toByte()
            out.write(header)

            val bb = ByteBuffer.allocate(numBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) {
                bb.putShort(s)
            }
            out.write(bb.array())
        }
    }
}
