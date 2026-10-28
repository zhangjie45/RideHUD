package zj.cursor.ridehud.data.ride

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import zj.cursor.ridehud.domain.RideRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class RideExporter(private val context: Context) {
    private val exportDir = File(context.cacheDir, "exports").also { it.mkdirs() }

    fun exportCsv(record: RideRecord): File {
        val stamp = fileStamp(record.startEpochMs)
        val file = File(exportDir, "ridehud_${stamp}_${record.id.take(8)}.csv")
        file.writeText(buildCsv(record))
        return file
    }

    fun exportJson(record: RideRecord, source: RideRepository): File {
        // Reload full record with track points if needed
        val full = source.load(record.id) ?: record
        val stamp = fileStamp(full.startEpochMs)
        val file = File(exportDir, "ridehud_${stamp}_${full.id.take(8)}.json")
        // Reuse repository serialization via save temp — write manually from loaded file
        val stored = File(context.filesDir, "rides/${full.id}.json")
        if (stored.exists()) {
            file.writeText(stored.readText())
        } else {
            file.writeText(buildMinimalJson(full))
        }
        return file
    }

    fun shareFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "导出运动记录"))
    }

    private fun buildCsv(record: RideRecord): String {
        val sb = StringBuilder()
        sb.appendLine("# RideHUD export")
        sb.appendLine("# id,${record.id}")
        sb.appendLine("# startEpochMs,${record.startEpochMs}")
        sb.appendLine("# endEpochMs,${record.endEpochMs}")
        sb.appendLine("# elapsedMs,${record.elapsedMs}")
        sb.appendLine("# distanceM,${record.distanceM}")
        sb.appendLine("# caloriesKcal,${record.caloriesKcal}")
        sb.appendLine("# watchCaloriesKcal,${record.watchCaloriesKcal ?: ""}")
        sb.appendLine("# avgSpeedKmh,${record.avgSpeedKmh}")
        sb.appendLine("# maxSpeedKmh,${record.maxSpeedKmh}")
        sb.appendLine("# avgHeartRateBpm,${record.avgHeartRateBpm ?: ""}")
        sb.appendLine("# maxHeartRateBpm,${record.maxHeartRateBpm ?: ""}")
        sb.appendLine("# elevationGainM,${record.elevationGainM}")
        sb.appendLine("# elevationLossM,${record.elevationLossM}")
        sb.appendLine("# weightKgUsed,${record.weightKgUsed}")
        sb.appendLine("# calorieFormulaVersion,${record.calorieFormulaVersion}")
        sb.appendLine(
            "elapsedMs,epochMs,speedKmh,distanceM,heartRateBpm,altitudeM,gradePercent,caloriesKcalCumulative",
        )
        val full = record.trackPoints.ifEmpty {
            // caller should pass full record; keep header-only if empty
            emptyList()
        }
        full.forEach { p ->
            sb.append(p.elapsedMs).append(',')
            sb.append(p.epochMs).append(',')
            sb.append(p.speedKmh).append(',')
            sb.append(p.distanceM).append(',')
            sb.append(p.heartRateBpm ?: "").append(',')
            sb.append(p.altitudeM ?: "").append(',')
            sb.append(p.gradePercent ?: "").append(',')
            sb.appendLine(p.caloriesKcalCumulative)
        }
        return sb.toString()
    }

    private fun buildMinimalJson(record: RideRecord): String =
        """{"id":"${record.id}","caloriesKcal":${record.caloriesKcal},"watchCaloriesKcal":${record.watchCaloriesKcal},"calorieFormulaVersion":"${record.calorieFormulaVersion}"}"""

    private fun fileStamp(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        fmt.timeZone = TimeZone.getDefault()
        return fmt.format(Date(epochMs))
    }
}
