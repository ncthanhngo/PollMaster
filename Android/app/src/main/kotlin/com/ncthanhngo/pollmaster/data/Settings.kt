package com.ncthanhngo.pollmaster.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PhotoQuality(val key: String, val maxEdge: Int, val jpegQuality: Int) {
    HIGH("high", 2560, 85),
    MEDIUM("medium", 1600, 70),
}

/** Local preferences (surveyor name, photo quality, last export selection). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("pollmaster_settings", Context.MODE_PRIVATE)

    private val _surveyorName = MutableStateFlow(prefs.getString(KEY_SURVEYOR, "").orEmpty())
    val surveyorName: StateFlow<String> = _surveyorName.asStateFlow()

    private val _photoQuality = MutableStateFlow(
        PhotoQuality.entries.firstOrNull { it.key == prefs.getString(KEY_QUALITY, null) } ?: PhotoQuality.HIGH,
    )
    val photoQuality: StateFlow<PhotoQuality> = _photoQuality.asStateFlow()

    fun setSurveyorName(value: String) {
        _surveyorName.value = value
        prefs.edit().putString(KEY_SURVEYOR, value).apply()
    }

    fun setPhotoQuality(value: PhotoQuality) {
        _photoQuality.value = value
        prefs.edit().putString(KEY_QUALITY, value.key).apply()
    }

    var exportFormats: Set<String>
        get() = prefs.getStringSet(KEY_EXPORT, null) ?: setOf("zip", "xlsx", "pdf")
        set(value) {
            prefs.edit().putStringSet(KEY_EXPORT, value).apply()
        }

    /** Names of bundled templates already installed (so a deleted one is not resurrected). */
    var installedBundled: Set<String>
        get() = prefs.getStringSet(KEY_BUNDLED, null) ?: emptySet()
        set(value) {
            prefs.edit().putStringSet(KEY_BUNDLED, value).apply()
        }

    private companion object {
        const val KEY_SURVEYOR = "surveyor_name"
        const val KEY_QUALITY = "photo_quality"
        const val KEY_EXPORT = "export_formats"
        const val KEY_BUNDLED = "bundled_installed"
    }
}
