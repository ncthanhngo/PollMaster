package com.ncthanhngo.pollmaster.core.format

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val dateTimeFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
private val monthFmt = DateTimeFormatter.ofPattern("MM/yyyy")
private val fileStampFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
private val isoFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

/** Storage form of a timestamp: always with seconds, no fraction (`2026-10-23T10:00:00+07:00`). */
fun toIso(time: OffsetDateTime): String = time.format(isoFmt)

fun nowIso(zone: ZoneId = ZoneId.systemDefault()): String = toIso(OffsetDateTime.now(zone))

fun todayIso(zone: ZoneId = ZoneId.systemDefault()): String = LocalDate.now(zone).toString()

fun displayDate(iso: String): String =
    runCatching { LocalDate.parse(iso).format(dateFmt) }.getOrDefault(iso)

fun displayMonth(iso: String): String =
    runCatching { YearMonth.parse(iso).format(monthFmt) }.getOrDefault(iso)

fun displayDateTime(iso: String, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(dateTimeFmt) }.getOrDefault(iso)

fun fileStamp(instant: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String =
    instant.atZone(zone).format(fileStampFmt)

fun parseInstant(iso: String?): Instant? =
    iso?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
