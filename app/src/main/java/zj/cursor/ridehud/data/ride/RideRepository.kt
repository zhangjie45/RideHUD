package zj.cursor.ridehud.data.ride

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import zj.cursor.ridehud.domain.RideRecord
import zj.cursor.ridehud.domain.RideTrackPoint
import java.io.File

class RideRepository(context: Context) {
    private val root = File(context.applicationContext.filesDir, "rides").also { it.mkdirs() }
    private val indexFile = File(root, "index.json")

    fun listSummaries(): List<RideRecord> {
        val ids = readIndex()
        return ids.mapNotNull { load(it)?.copy(trackPoints = emptyList()) }
    }

    fun load(id: String): RideRecord? {
        val file = File(root, "$id.json")
        if (!file.exists()) return null
        return runCatching { parseRecord(JSONObject(file.readText())) }.getOrNull()
    }

    fun save(record: RideRecord) {
        File(root, "${record.id}.json").writeText(toJson(record).toString())
        val ids = readIndex().toMutableList()
        ids.remove(record.id)
        ids.add(0, record.id)
        writeIndex(ids)
    }

    fun delete(id: String) {
        File(root, "$id.json").delete()
        writeIndex(readIndex().filterNot { it == id })
    }

    /**
     * Update watch calorie after user provides watch export (for calibration).
     */
    fun updateWatchCalories(id: String, watchCaloriesKcal: Float?): RideRecord? {
        val current = load(id) ?: return null
        val updated = current.copy(watchCaloriesKcal = watchCaloriesKcal)
        save(updated)
        return updated
    }

    private fun readIndex(): List<String> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            buildList {
                for (i in 0 until arr.length()) add(arr.getString(i))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(ids: List<String>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        indexFile.writeText(arr.toString())
    }

    private fun toJson(record: RideRecord): JSONObject = JSONObject().apply {
        put("id", record.id)
        put("startEpochMs", record.startEpochMs)
        put("endEpochMs", record.endEpochMs)
        put("elapsedMs", record.elapsedMs)
        put("totalElapsedMs", record.totalElapsedMs)
        put("distanceM", record.distanceM.toDouble())
        put("caloriesKcal", record.caloriesKcal.toDouble())
        put("activeCaloriesKcal", record.activeCaloriesKcal.toDouble())
        put("avgSpeedKmh", record.avgSpeedKmh.toDouble())
        put("maxSpeedKmh", record.maxSpeedKmh.toDouble())
        put("avgHeartRateBpm", record.avgHeartRateBpm?.toDouble() ?: JSONObject.NULL)
        put("maxHeartRateBpm", record.maxHeartRateBpm ?: JSONObject.NULL)
        put("elevationGainM", record.elevationGainM.toDouble())
        put("elevationLossM", record.elevationLossM.toDouble())
        put("minAltitudeM", record.minAltitudeM?.toDouble() ?: JSONObject.NULL)
        put("maxAltitudeM", record.maxAltitudeM?.toDouble() ?: JSONObject.NULL)
        put("weightKgUsed", record.weightKgUsed.toDouble())
        put("calorieFormulaVersion", record.calorieFormulaVersion)
        put("watchCaloriesKcal", record.watchCaloriesKcal?.toDouble() ?: JSONObject.NULL)
        put("notes", record.notes ?: JSONObject.NULL)
        put("trackPoints", JSONArray().also { arr ->
            record.trackPoints.forEach { p ->
                arr.put(
                    JSONObject().apply {
                        put("elapsedMs", p.elapsedMs)
                        put("epochMs", p.epochMs)
                        put("speedKmh", p.speedKmh.toDouble())
                        put("distanceM", p.distanceM.toDouble())
                        put("heartRateBpm", p.heartRateBpm ?: JSONObject.NULL)
                        put("altitudeM", p.altitudeM?.toDouble() ?: JSONObject.NULL)
                        put("gradePercent", p.gradePercent?.toDouble() ?: JSONObject.NULL)
                        put("caloriesKcalCumulative", p.caloriesKcalCumulative.toDouble())
                    },
                )
            }
        })
    }

    private fun parseRecord(json: JSONObject): RideRecord {
        val pointsArr = json.optJSONArray("trackPoints") ?: JSONArray()
        val points = buildList {
            for (i in 0 until pointsArr.length()) {
                val p = pointsArr.getJSONObject(i)
                add(
                    RideTrackPoint(
                        elapsedMs = p.getLong("elapsedMs"),
                        epochMs = p.getLong("epochMs"),
                        speedKmh = p.getDouble("speedKmh").toFloat(),
                        distanceM = p.getDouble("distanceM").toFloat(),
                        heartRateBpm = p.optIntOrNull("heartRateBpm"),
                        altitudeM = p.optFloatOrNull("altitudeM"),
                        gradePercent = p.optFloatOrNull("gradePercent"),
                        caloriesKcalCumulative = p.getDouble("caloriesKcalCumulative").toFloat(),
                    ),
                )
            }
        }
        return RideRecord(
            id = json.getString("id"),
            startEpochMs = json.getLong("startEpochMs"),
            endEpochMs = json.getLong("endEpochMs"),
            elapsedMs = json.getLong("elapsedMs"),
            totalElapsedMs = json.optLong("totalElapsedMs", 0L),
            distanceM = json.getDouble("distanceM").toFloat(),
            caloriesKcal = json.getDouble("caloriesKcal").toFloat(),
            activeCaloriesKcal = json.optDouble("activeCaloriesKcal", 0.0).toFloat(),
            avgSpeedKmh = json.getDouble("avgSpeedKmh").toFloat(),
            maxSpeedKmh = json.getDouble("maxSpeedKmh").toFloat(),
            avgHeartRateBpm = json.optFloatOrNull("avgHeartRateBpm"),
            maxHeartRateBpm = json.optIntOrNull("maxHeartRateBpm"),
            elevationGainM = json.getDouble("elevationGainM").toFloat(),
            elevationLossM = json.getDouble("elevationLossM").toFloat(),
            minAltitudeM = json.optFloatOrNull("minAltitudeM"),
            maxAltitudeM = json.optFloatOrNull("maxAltitudeM"),
            weightKgUsed = json.getDouble("weightKgUsed").toFloat(),
            calorieFormulaVersion = json.getString("calorieFormulaVersion"),
            watchCaloriesKcal = json.optFloatOrNull("watchCaloriesKcal"),
            notes = if (json.isNull("notes")) null else json.optString("notes"),
            trackPoints = points,
        )
    }
}

private fun JSONObject.optFloatOrNull(key: String): Float? =
    if (isNull(key) || !has(key)) null else optDouble(key).toFloat()

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key)
