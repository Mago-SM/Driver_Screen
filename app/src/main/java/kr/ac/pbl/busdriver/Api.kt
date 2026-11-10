package kr.ac.pbl.busdriver

import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI

class ApiError(val status: Int, message: String) : Exception(message)
class Api(val baseUrl: String) {
    // 이 인스턴스의 쿠키만 공유합니다. 서버 변경 시 새 인스턴스를 생성합니다.
    private val cookies = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
    fun clear() = cookies.cookieStore.removeAll()
    fun request(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        val uri = URI(baseUrl + path)
        val conn = uri.toURL().openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.instanceFollowRedirects = false; conn.requestMethod = method
            conn.setRequestProperty("Accept", "application/json")
            cookies.get(uri, emptyMap()).forEach { (key, values) -> conn.setRequestProperty(key, values.joinToString("; ")) }
            if (method == "POST") {
                conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            cookies.put(uri, conn.headerFields.filterKeys { it != null })
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val text = StringBuilder(); val buffer = CharArray(8192)
                while (true) { val n = reader.read(buffer); if (n < 0) break; text.append(buffer, 0, n); require(text.length <= 2_000_000) { "서버 응답이 너무 큽니다." } }
                text.toString()
            } ?: "{}"
            val json = runCatching { JSONObject(raw) }.getOrElse { throw ApiError(code, "서버 JSON 응답을 확인해 주세요.") }
            if (code !in 200..299) throw ApiError(code, json.optJSONObject("error")?.optString("message") ?: "서버 오류 ($code)")
            return json
        } finally { conn.disconnect() }
    }
    data class Choice(val id: String, val label: String)
    private fun query(number: String, direction: String, routeId: String = "", vehicleId: String = "") =
        mapOf("routeNumber" to number, "direction" to direction, "routeId" to routeId, "vehicleId" to vehicleId)
            .entries.joinToString("&") { "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}" }
    fun routes(number: String, direction: String): List<Choice> {
        val a = request("/api/driver/routes?" + query(number, direction)).getJSONArray("routes")
        return (0 until a.length()).map { a.getJSONObject(it).let { j -> Choice(j.getString("routeId"), j.getString("label")) } }
    }
    fun vehicles(number: String, direction: String, routeId: String): List<Choice> {
        val a = request("/api/driver/vehicles?" + query(number, direction, routeId)).getJSONArray("vehicles")
        return (0 until a.length()).map { a.getJSONObject(it).let { j -> Choice(j.getString("vehicleId"), j.getString("label")) } }
    }
    fun snapshot(routeNumber: String, direction: String, routeId: String, vehicleId: String): Snapshot {
        val response = request("/api/driver/route-snapshot?" + query(routeNumber, direction, routeId, vehicleId))
        require(response.getString("routeNumber") == routeNumber && response.getString("directionKey") == direction &&
            response.getString("routeId") == routeId && response.getString("vehicleId") == vehicleId) {
            "선택한 노선·방향·차량과 서버 응답이 다릅니다."
        }
        return parse(response)
    }
    companion object {
        fun parse(j: JSONObject): Snapshot {
            require(j.getBoolean("complete")) { "일부 예약만 조회되었습니다. 마지막 정상 데이터를 유지합니다." }
            val stops = j.getJSONArray("stops").let { a -> (0 until a.length()).map { i ->
                a.getJSONObject(i).let { Stop(it.getInt("sequence"), it.getString("name")) }
            } }
            val counts = j.getJSONObject("counts")
            fun values(key: String) = counts.getJSONArray(key).let { a ->
                (0 until a.length()).map { i ->
                    val value = a.get(i)
                    require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() >= 0) { "인원 값 오류" }
                    value.toInt()
                }
            }
            val totals = Counts(values("boarding"), values("alighting"), values("plannedAlighting"))
            return Snapshot(j.getString("simulationId"), j.getString("configVersion"), j.getString("routeId"),
                j.getString("routeLabel"), j.getString("directionKey"), stops, emptyList(), j.getInt("excludedCount"), totals).also {
                require(it.simulationId.isNotBlank() && it.configVersion.isNotBlank() && it.routeId.isNotBlank() && it.direction.isNotBlank())
                require(it.excludedCount >= 0 && stops.all { s -> s.name.isNotBlank() })
                it.counts()
            }
        }
    }
}
