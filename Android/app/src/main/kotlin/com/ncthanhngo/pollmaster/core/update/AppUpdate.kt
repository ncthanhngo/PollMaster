package com.ncthanhngo.pollmaster.core.update

import com.ncthanhngo.pollmaster.core.model.PollJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A newer build published by the release scripts: GitHub releases of the public repo, tagged `<platform>-v<version>-<build>`
 * (`android-v1.0.0-37`, `ios-v1.0.0-37`); the build is the commit count, so a larger one is newer.
 */
data class AppUpdate(val build: Int, val version: String, /** First asset: the APK. */ val downloadUrl: String?) {
    @Serializable private data class Asset(val browser_download_url: String)

    @Serializable
    private data class Release(val tag_name: String, val draft: Boolean = false, val prerelease: Boolean = false, val assets: List<Asset> = emptyList())

    companion object {
        /** GitHub `GET /repos/{owner}/{repo}/releases` of the repo the app is released from. */
        const val RELEASES_URL = "https://api.github.com/repos/ncthanhngo/PollMaster/releases?per_page=30"

        /** Newest release tagged `prefix…` with a build above [current]; null when there is none or the list is unreadable. */
        fun newest(json: String, prefix: String, current: Int): AppUpdate? {
            val list = runCatching { PollJson.decodeFromString(ListSerializer(Release.serializer()), json) }.getOrNull() ?: return null
            return list.mapNotNull { r ->
                if (r.draft || r.prerelease || !r.tag_name.startsWith(prefix)) return@mapNotNull null
                val rest = r.tag_name.removePrefix(prefix)
                val build = rest.substringAfterLast('-', "").toIntOrNull() ?: return@mapNotNull null
                AppUpdate(build, rest.substringBeforeLast('-'), r.assets.firstOrNull()?.browser_download_url)
            }.filter { it.build > current }.maxByOrNull { it.build }
        }
    }
}
