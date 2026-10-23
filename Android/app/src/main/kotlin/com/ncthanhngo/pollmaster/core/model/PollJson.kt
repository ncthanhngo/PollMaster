package com.ncthanhngo.pollmaster.core.model

import kotlinx.serialization.json.Json

/** Shared JSON configuration for templates, records and exports. */
val PollJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    prettyPrint = true
}
