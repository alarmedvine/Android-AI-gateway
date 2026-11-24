package dev.aigw.core.provider.newapi

import com.google.gson.JsonParser
import dev.aigw.core.util.HttpSupport

/**
 * 用站点自己的日志接口核验「这一次签到到底有没有落库」。
 *
 * ## 为什么不能只信 `checked_in`
 *
 * 站点前端在登录/回调响应里看到 `data.checked_in === true` 就提示「签到成功，新增额度已到账」，
 * 但当天**已经签过**的账号再登录一次，这个位同样是 true。把它当成本次成功的证据，
 * 结果就是「每天点一次都报成功，额度其实一分没涨」——上一版正是栽在这类弱判据上。
 *
 * 青龙脚本（`2263075977/qinglong`）实测给出的硬判据是：
 * `GET /api/log/self` 里存在 **type=4（LogTypeSystem）且 `created_at` 晚于本次开始时刻** 的记录。
 * 这里照此实现，并保留 5 分钟容差吸收时钟偏差。
 *
 * 调用方拿到的三态是明确的：`FRESH`（本次新签）/ `ALREADY`（今天签过但不是在本次窗口）/
 * `NONE`（查不到，视为失败）。**绝不把 NONE 说成成功。**
 */
class CheckinLogClient(
    private val profile: NewApiSiteProfile,
) {
    enum class Outcome {
        /** 日志里出现了本次运行之后的签到记录：额度确实到账了。 */
        FRESH,

        /** 日志里有今天的签到记录，但早于本次窗口：今天已经签过，这次没有新增。 */
        ALREADY,

        /** 日志里找不到任何今天的签到记录：登录可能成功，但签到没有落库。 */
        NONE,

        /** 查询本身失败（网络 / 会话失效 / 接口不可用），与「没签到」要分开报。 */
        ERROR,
    }

    data class Result(
        val outcome: Outcome,
        val detail: String = "",
        /** 命中记录的 epoch millis（[Outcome.FRESH] / [Outcome.ALREADY] 时有值）。 */
        val atMillis: Long? = null,
    )

    /**
     * 查询并判定。
     *
     * @param cookies 已登录会话的 cookie（至少要含 `session`）
     * @param expectedUid 期望的站点用户 id；日志接口按会话认人，但仍带上 `New-API-User` 兜底
     * @param runStartMs 本次签到开始时刻（毫秒）
     */
    fun verify(
        cookies: Map<String, String>,
        expectedUid: String?,
        runStartMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Result {
        if (cookies.isEmpty()) return Result(Outcome.ERROR, "没有可用的会话 cookie")
        val startSec = ((nowMs - LOOKBACK_MS) / 1000).coerceAtLeast(0)
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        headers["Cookie"] = CheckinCookie.format(cookies)
        expectedUid?.takeIf { it.isNotEmpty() }?.let { headers[profile.apiUserHeader] = it }

        val text: String
        val status: Int
        try {
            val conn = HttpSupport.open(
                url = profile.domain.trimEnd('/') + CheckinLogEvidence.logQueryPath(startSec),
                method = "GET",
                headers = headers,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
                allowCompression = true,
            )
            try {
                status = conn.responseCode
                text = HttpSupport.readBody(conn)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return Result(Outcome.ERROR, e.message ?: "日志查询失败")
        }

        if (status == 401 || status == 403 || CheckinCookie.isUnauthorized(text)) {
            return Result(Outcome.ERROR, "会话已失效（HTTP $status）")
        }
        if (status !in 200..299) return Result(Outcome.ERROR, "日志查询失败（HTTP $status）")

        val items = parseItems(text) ?: return Result(Outcome.ERROR, "日志响应无法解析")
        val fresh = CheckinLogEvidence.freshCheckinAt(items, runStartMs)
        if (fresh != null) return Result(Outcome.FRESH, "签到记录已落库", fresh)

        // 本次窗口内没有，但今天有：说明今天早些时候已经签过（重复点击 / 自动签到跑过）。
        val today = CheckinLogEvidence.freshCheckinAt(items, startOfTodayMs(nowMs))
        if (today != null) return Result(Outcome.ALREADY, "今天已经签过，本次没有新增", today)

        return Result(Outcome.NONE, "今天没有签到记录")
    }

    /**
     * 解析 `{"success":true,"data":{"items":[...]}}`。
     *
     * New-API 各版本在 `data` 直接给数组、或包一层分页对象两种情况都出现过，
     * 两种都接受；结构不认就返回 null（调用方报 ERROR，不猜成功）。
     */
    private fun parseItems(text: String): List<Map<String, Any?>>? = runCatching {
        val root = JsonParser.parseString(text)
        if (!root.isJsonObject) return null
        val data = root.asJsonObject.get("data") ?: return null
        val array = when {
            data.isJsonArray -> data.asJsonArray
            data.isJsonObject -> data.asJsonObject.get("items")?.takeIf { it.isJsonArray }?.asJsonArray
            else -> null
        } ?: return null
        array.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            val map = LinkedHashMap<String, Any?>()
            obj.get("created_at")?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }
                ?.asString?.toLongOrNull()?.let { map["created_at"] = it }
            obj.get("type")?.takeIf { it.isJsonPrimitive && !it.asJsonPrimitive.isBoolean }
                ?.asString?.toIntOrNull()?.let { map["type"] = it }
            obj.get("content")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString?.let { map["content"] = it }
            map
        }
    }.getOrNull()

    private fun startOfTodayMs(nowMs: Long): Long {
        val zone = java.util.TimeZone.getDefault()
        val cal = java.util.Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private companion object {
        /** 回看 30 小时：北京时间今天 00:00 起最多 24h，多留 6h 吸收时区/时钟误差。 */
        const val LOOKBACK_MS = 30L * 60 * 60 * 1000

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
