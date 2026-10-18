package com.beatraxus.app.fpsbooster

import android.content.SharedPreferences

/** SharedPreferences persistence for [FpsBoosterSettings] (the "beatraxus" prefs file). */
object FpsBoosterStore {
    private const val LEGACY_MODE = "video_motion_boost"
    private const val LEGACY_EXTREME = "video_motion_boost_extreme"

    fun load(prefs: SharedPreferences): FpsBoosterSettings {
        if (!prefs.getBoolean(FpsBoosterPrefs.MIGRATED, false)) {
            // First run of the new booster: carry over the old single-choice setting once.
            val migrated = FpsBoosterSettings.migrateLegacy(
                prefs.getString(LEGACY_MODE, null), prefs.getBoolean(LEGACY_EXTREME, false)
            )
            prefs.edit().putBoolean(FpsBoosterPrefs.MIGRATED, true).apply()
            if (migrated != null) { save(prefs, migrated); return migrated }
        }
        return FpsBoosterSettings.fromStored(
            mode = prefs.getString(FpsBoosterPrefs.MODE, null),
            targetFps = if (prefs.contains(FpsBoosterPrefs.TARGET)) prefs.getInt(FpsBoosterPrefs.TARGET, BoosterTargets.DEFAULT) else null,
            engine = prefs.getString(FpsBoosterPrefs.ENGINE, null),
            quality = prefs.getString(FpsBoosterPrefs.QUALITY, null),
            adaptive = if (prefs.contains(FpsBoosterPrefs.ADAPTIVE)) prefs.getBoolean(FpsBoosterPrefs.ADAPTIVE, true) else null,
            batterySaver = if (prefs.contains(FpsBoosterPrefs.BATTERY)) prefs.getBoolean(FpsBoosterPrefs.BATTERY, false) else null,
            showStats = if (prefs.contains(FpsBoosterPrefs.SHOW_STATS)) prefs.getBoolean(FpsBoosterPrefs.SHOW_STATS, true) else null
        )
    }

    fun save(prefs: SharedPreferences, s: FpsBoosterSettings) {
        prefs.edit()
            .putString(FpsBoosterPrefs.MODE, s.mode.name)
            .putInt(FpsBoosterPrefs.TARGET, s.targetFps)
            .putString(FpsBoosterPrefs.ENGINE, s.engine.name)
            .putString(FpsBoosterPrefs.QUALITY, s.quality.name)
            .putBoolean(FpsBoosterPrefs.ADAPTIVE, s.adaptiveResolution)
            .putBoolean(FpsBoosterPrefs.BATTERY, s.batterySaver)
            .putBoolean(FpsBoosterPrefs.SHOW_STATS, s.showStats)
            .apply()
    }

    fun loadBenchmark(prefs: SharedPreferences): BenchmarkResult? =
        BenchmarkResult.fromStored(prefs.getString(FpsBoosterPrefs.BENCH, null))

    fun saveBenchmark(prefs: SharedPreferences, r: BenchmarkResult) {
        prefs.edit().putString(FpsBoosterPrefs.BENCH, r.toStored()).remove(FpsBoosterPrefs.BENCH_SKIPPED).apply()
    }

    fun isBenchmarkSkipped(prefs: SharedPreferences) = prefs.getBoolean(FpsBoosterPrefs.BENCH_SKIPPED, false)

    fun setBenchmarkSkipped(prefs: SharedPreferences) {
        prefs.edit().putBoolean(FpsBoosterPrefs.BENCH_SKIPPED, true).apply()
    }
}
