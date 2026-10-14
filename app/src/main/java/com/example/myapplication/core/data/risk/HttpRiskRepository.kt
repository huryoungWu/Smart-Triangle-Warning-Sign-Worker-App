package com.example.myapplication.core.data.risk

import com.example.myapplication.core.data.api.RiskRepository
import com.example.myapplication.core.geo.CoordTransform
import com.example.myapplication.core.geo.GeoPoint
import com.example.myapplication.core.model.risk.Accident
import com.example.myapplication.core.model.risk.AccidentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 真实数据源：`GET /api/v1/risks?lat=&lng=&radiusKm=`（见 docs/后端接口设计文档.md 第 12 节）。
 *
 * 约定：
 * - 返回坐标为 **GCJ-02**（可直接上高德地图）
 * - `distanceM` 由本类按 [center]（用户当前位置）计算，并按距离从近到远排序
 * - 服务端不可达/解析失败时按空列表降级（界面显示「周边暂无风险事件」）
 */
class HttpRiskRepository(
    private val baseUrl: String,
    /** 登录 token 提供者（未登录返回 null）；由 ServiceLocator 注入 */
    private val tokenProvider: () -> String? = { null },
    private val connectTimeoutMs: Int = 3000,
    private val readTimeoutMs: Int = 5000,
) : RiskRepository {
    override suspend fun getAccidents(center: GeoPoint, radiusKm: Double): List<Accident> =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = request(
                    "$baseUrl/api/v1/risks?lat=${center.lat}&lng=${center.lng}&radiusKm=$radiusKm",
                )
                parse(raw, center)
            }.getOrElse { emptyList() }
        }

    private fun parse(raw: String, center: GeoPoint): List<Accident> {
        val arr = JSONObject(raw).optJSONArray("risks") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val lat = o.optDouble("lat", Double.NaN)
                val lng = o.optDouble("lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue
                val point = GeoPoint(lat, lng)
                add(
                    Accident(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        type = AccidentType.entries.firstOrNull {
                            it.name.equals(o.optString("type"), ignoreCase = true)
                        } ?: AccidentType.OTHER,
                        distanceM = CoordTransform.distanceM(center, point),
                        lat = lat,
                        lng = lng,
                        occurredAt = o.optLong("occurredAt"),
                        description = o.optString("description"),
                        hasPhoto = o.optBoolean("hasPhoto", false),
                        hasVideo = o.optBoolean("hasVideo", false),
                        photoUrls = o.optJSONArray("photoUrls")?.let { arr ->
                            buildList {
                                for (i in 0 until arr.length()) {
                                    arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                                }
                            }
                        } ?: emptyList(),
                        videoUrl = o.optString("videoUrl", null)?.takeIf { it.isNotBlank() },
                    ),
                )
            }
        }.sortedBy { it.distanceM }
    }

    private fun request(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            tokenProvider()?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            require(code in 200..299) { "服务端返回 HTTP $code：$text" }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
