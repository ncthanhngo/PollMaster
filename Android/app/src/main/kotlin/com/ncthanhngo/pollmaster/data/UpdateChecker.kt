package com.ncthanhngo.pollmaster.data

import android.content.Context
import com.ncthanhngo.pollmaster.BuildConfig
import com.ncthanhngo.pollmaster.core.update.AppUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** "Có bản mới": release-apk.sh publishes a GitHub release `android-v<version>-<build>` with the APK. */
class UpdateChecker(context: Context) {
    private val prefs = context.getSharedPreferences("pollmaster_update", Context.MODE_PRIVATE)
    @Volatile private var checkedAt = 0L

    private val _available = MutableStateFlow<AppUpdate?>(null)
    /** Newer build than this one (shown in Cài đặt until installed). */
    val available: StateFlow<AppUpdate?> = _available.asStateFlow()

    private val _prompt = MutableStateFlow<AppUpdate?>(null)
    /** Asked once per build. */
    val prompt: StateFlow<AppUpdate?> = _prompt.asStateFlow()

    fun dismissPrompt() { _prompt.value = null }

    /** At launch and when the app comes back, at most once an hour (GitHub allows 60 anonymous calls an hour per address). */
    suspend fun check() {
        if (BuildConfig.DEBUG) return // debug builds carry version code 1
        val now = System.currentTimeMillis()
        if (now - checkedAt < 3_600_000) return
        checkedAt = now
        val json = withContext(Dispatchers.IO) {
            runCatching {
                (URL(AppUpdate.RELEASES_URL).openConnection() as HttpURLConnection).run {
                    connectTimeout = 15_000; readTimeout = 15_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    try { if (responseCode == 200) inputStream.bufferedReader().readText() else null } finally { disconnect() }
                }
            }.getOrNull()
        } ?: return
        val u = AppUpdate.newest(json, "android-v", BuildConfig.VERSION_CODE)
        _available.value = u
        if (u != null && prefs.getInt(KEY_PROMPTED, 0) < u.build) {
            prefs.edit().putInt(KEY_PROMPTED, u.build).apply()
            _prompt.value = u
        }
    }

    private companion object { const val KEY_PROMPTED = "prompted_build" }
}
