package com.ncthanhngo.pollmaster.core.editor

import com.ncthanhngo.pollmaster.core.model.StopwatchDef
import com.ncthanhngo.pollmaster.core.model.Row
import com.ncthanhngo.pollmaster.core.model.isEmptyJson
import java.time.Duration
import java.time.Instant

/** Stopwatch rules for a row: T0 in [Row.stopwatchStart], marks fill [StopwatchDef.columns] in order. */
object Stopwatch {

    /** First column without a value, or null when all marks are recorded. */
    fun nextColumn(def: StopwatchDef, row: Row): String? =
        def.columns.firstOrNull { row.values[it]?.v.isEmptyJson() }

    /** Last column that holds a mark. */
    fun lastMarked(def: StopwatchDef, row: Row): String? =
        def.columns.lastOrNull { !row.values[it]?.v.isEmptyJson() }

    /** Seconds from T0 to [now]; whole seconds. */
    fun secondsSince(start: Instant, now: Instant): Long =
        Math.round(Duration.between(start, now).toMillis() / 1000.0).coerceAtLeast(0)
}
