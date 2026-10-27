package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.update.AppUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateTest {
    private val json = """
        [
          {"tag_name": "ios-v1.0.0-38", "draft": false, "prerelease": false, "assets": []},
          {"tag_name": "android-v1.0.0-37", "draft": false, "prerelease": false,
           "assets": [{"browser_download_url": "https://github.com/x/y/releases/download/android-v1.0.0-37/PollMaster-1.0.0-37.apk"}]},
          {"tag_name": "android-v1.0.0-39", "draft": false, "prerelease": true, "assets": []},
          {"tag_name": "android-v1.0.0-36", "assets": []},
          {"tag_name": "android-vbroken", "assets": []}
        ]
    """.trimIndent()

    @Test fun picksTheNewestBuildOfThePlatform() {
        val u = AppUpdate.newest(json, "android-v", current = 36)!!
        assertEquals(37, u.build)
        assertEquals("1.0.0", u.version)
        assertEquals("PollMaster-1.0.0-37.apk", u.downloadUrl?.substringAfterLast('/'))
        assertEquals(38, AppUpdate.newest(json, "ios-v", current = 36)?.build)
    }

    @Test fun nothingWhenCurrentOrUnreadable() {
        assertNull(AppUpdate.newest(json, "android-v", current = 37))
        assertNull(AppUpdate.newest("""{"message":"rate limited"}""", "ios-v", current = 1))
    }
}
