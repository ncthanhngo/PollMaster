package com.ncthanhngo.pollmaster.core.theme

/** App appearance preference (ux-spec K). Default is LIGHT, independent of the system. */
enum class ThemeMode(val key: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemDark
    }

    /** One-tap toggle: flips the displayed scheme and yields a concrete choice (never SYSTEM). */
    fun toggled(systemDark: Boolean): ThemeMode = if (isDark(systemDark)) LIGHT else DARK

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}
