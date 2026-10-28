package zj.cursor.ridehud.data.prefs

import android.content.Context

class RiderPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var weightKg: Float
        get() = prefs.getFloat(KEY_WEIGHT_KG, DEFAULT_WEIGHT_KG)
        set(value) = prefs.edit().putFloat(KEY_WEIGHT_KG, value.coerceIn(30f, 200f)).apply()

    companion object {
        private const val PREFS = "rider_prefs"
        private const val KEY_WEIGHT_KG = "weight_kg"
        const val DEFAULT_WEIGHT_KG = 70f
    }
}
