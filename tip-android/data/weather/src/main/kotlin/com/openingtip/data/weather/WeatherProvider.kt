package com.openingtip.data.weather

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WeatherInfo(
    val cityId: String,
    val cityName: String,
    val temperatureC: Float?,
    val conditionText: String,
    val conditionCode: String,
    val observedAt: Long,
    val fetchedAt: Long,
    val provider: String,
    val isAvailable: Boolean,
    val statusDescription: String
)

interface WeatherProvider {
    suspend fun fetchWeather(cityId: String, cityName: String): WeatherInfo
}

/**
 * 基于 Open-Meteo 开源合规天气接口的实现（无需私有密钥，尊重用户隐私，无定位权限）
 */
class OpenMeteoWeatherProvider : WeatherProvider {

    override suspend fun fetchWeather(cityId: String, cityName: String): WeatherInfo = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val coordinates = cityCoordinates[cityId] ?: cityCoordinates["beijing"]!!

        val urlString = "https://api.open-meteo.com/v1/forecast?latitude=${coordinates.first}&longitude=${coordinates.second}&current_weather=true"

        try {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                // 解析 current_weather.temperature 与 weathercode
                val tempRegex = """"temperature":\s*([0-9.-]+)""".toRegex()
                val codeRegex = """"weathercode":\s*([0-9]+)""".toRegex()

                val tempMatch = tempRegex.find(response)
                val codeMatch = codeRegex.find(response)

                val temp = tempMatch?.groupValues?.get(1)?.toFloatOrNull()
                val code = codeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val (condText, condCode) = mapWmoCode(code)

                WeatherInfo(
                    cityId = cityId,
                    cityName = cityName,
                    temperatureC = temp,
                    conditionText = condText,
                    conditionCode = condCode,
                    observedAt = now,
                    fetchedAt = now,
                    provider = "Open-Meteo",
                    isAvailable = temp != null,
                    statusDescription = if (temp != null) "正常" else "数据暂不可用"
                )
            } else {
                WeatherInfo(
                    cityId = cityId,
                    cityName = cityName,
                    temperatureC = null,
                    conditionText = "天气暂不可用",
                    conditionCode = "UNAVAILABLE",
                    observedAt = 0L,
                    fetchedAt = now,
                    provider = "Open-Meteo",
                    isAvailable = false,
                    statusDescription = "网络连接异常 (HTTP ${conn.responseCode})"
                )
            }
        } catch (e: Exception) {
            // 离线降级：明确 unavailable 状态，绝不造假 mock
            WeatherInfo(
                cityId = cityId,
                cityName = cityName,
                temperatureC = null,
                conditionText = "天气暂不可用",
                conditionCode = "UNAVAILABLE",
                observedAt = 0L,
                fetchedAt = now,
                provider = "Open-Meteo",
                isAvailable = false,
                statusDescription = "离线或连接超时: ${e.message}"
            )
        }
    }

    private fun mapWmoCode(code: Int): Pair<String, String> {
        return when (code) {
            0 -> Pair("晴", "CLEAR")
            1, 2, 3 -> Pair("多云", "CLOUDY")
            45, 48 -> Pair("雾", "FOG")
            51, 53, 55 -> Pair("毛毛雨", "DRIZZLE")
            61, 63, 65 -> Pair("小雨", "RAIN")
            71, 73, 75 -> Pair("雪", "SNOW")
            95, 96, 99 -> Pair("雷阵雨", "THUNDERSTORM")
            else -> Pair("阴", "OVERCAST")
        }
    }

    companion object {
        val cityCoordinates = mapOf(
            "beijing" to Pair(39.9042, 116.4074),
            "shanghai" to Pair(31.2304, 121.4737),
            "guangzhou" to Pair(23.1291, 113.2644),
            "shenzhen" to Pair(22.5431, 114.0579),
            "chengdu" to Pair(30.5728, 104.0668),
            "hangzhou" to Pair(30.2741, 120.1551),
            "wuhan" to Pair(30.5928, 114.3055),
            "nanjing" to Pair(32.0603, 118.7969)
        )
    }
}
