package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.model.FieldValue
import com.ncthanhngo.pollmaster.core.project.ErpMessages
import com.ncthanhngo.pollmaster.core.project.ErpProject
import com.ncthanhngo.pollmaster.core.project.ErpProjectSync
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.storage.Backup
import com.ncthanhngo.pollmaster.core.storage.DebouncedSaver
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.validation.WarningChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReviewFixesTest {
    private val base = TestData.emptyRecord().copy(projectId = "p1", updatedAt = "2026-10-23T10:00:00+07:00")

    // ---- backup stamp rule ----

    @Test fun backupStampsOnlyAnUnchangedRecord() {
        assertTrue(Backup.shouldStamp(base, base.copy(backupAt = "2026-10-23T09:00:00+07:00")))
        assertFalse("edited meanwhile", Backup.shouldStamp(base, base.copy(updatedAt = "2026-10-23T10:00:05+07:00")))
        assertFalse("moved meanwhile", Backup.shouldStamp(base, base.copy(projectId = "p2")))
        assertFalse("deleted meanwhile", Backup.shouldStamp(base, null))
    }

    // ---- ERP ids, messages, local data ----

    @Test fun onlySafeProjectIdsBecomeFolders() {
        listOf("a", "A-1", "0b9f6c2e-1111-4222-8333-444455556666", "x".repeat(64)).forEach { assertTrue(it, ErpProjectSync.isSafeId(it)) }
        listOf("", "../x", "a/b", "a b", "a.b", "a\\b", "ư", "x".repeat(65), "a\n").forEach { assertFalse(it, ErpProjectSync.isSafeId(it)) }
    }

    @Test fun syncSkipsRowsWithUnsafeIds() {
        val root = TestData.tempDir()
        val store = ProjectStore(root)
        val rows = listOf("ok-1", "../evil", "a/b", "").map { ErpProject(it, "C${it.length}", "Dự án", type = "company") }
        ErpProjectSync.apply(rows, store, "2026-10-23T10:00:00+07:00")
        assertEquals(listOf("ok-1"), store.list().map { it.id })
        assertEquals(listOf("projects"), root.list()!!.toList())
        assertEquals(listOf("ok-1"), File(root, "projects").list()!!.toList())
    }

    @Test fun localDataCountsRecordAndProjectFolders() {
        val root = TestData.tempDir()
        assertEquals(0 to 0, ErpProjectSync.localDataCounts(root))
        File(root, "projects/p1").mkdirs()
        File(root, "projects/p2").mkdirs()
        File(root, "records/r1").mkdirs()
        assertEquals(1 to 2, ErpProjectSync.localDataCounts(root))
        ErpProjectSync.resetLocalData(root)
        assertEquals(0 to 0, ErpProjectSync.localDataCounts(root))
    }

    @Test fun onlyVietnameseSentencesAreShownFromTheServer() {
        assertTrue(ErpMessages.isUserSentence("Tài khoản đã bị khóa, liên hệ quản trị viên."))
        assertTrue(ErpMessages.isUserSentence("  Email không hợp lệ  "))
        listOf(null, "", "INVALID_CREDENTIALS", "ACCOUNT_LOCKED_2", "Invalid credentials", "Key: 'LoginReq.Email' Error:Field validation", "unauthorized")
            .forEach { assertFalse(it.toString(), ErpMessages.isUserSentence(it)) }
    }

    // ---- change detectors ----

    @Test fun fingerprintsChangeWhenFilesChange() {
        val root = TestData.tempDir()
        val records = RecordStore(root)
        val projects = ProjectStore(root)
        val empty = records.fingerprint()
        records.save(base)
        val one = records.fingerprint()
        assertNotEquals(empty, one)
        assertEquals(one, records.fingerprint())
        records.save(base.copy(values = mapOf("A-01" to TestData.text("x"))))
        assertNotEquals(one, records.fingerprint())
        records.delete(base.id)
        assertEquals(empty, records.fingerprint())

        val p0 = projects.fingerprint()
        File(root, "projects/p1").mkdirs()
        File(root, "projects/p1/project.json").writeText("{}")
        assertNotEquals(p0, projects.fingerprint())
    }

    @Test fun cancelledSaverNeverWrites() = runBlocking {
        val written = mutableListOf<String>()
        val saver = DebouncedSaver(CoroutineScope(Dispatchers.Default), 30) { written += it.updatedAt }
        saver.submit(base)
        saver.cancel()
        delay(120)
        saver.flush()
        assertTrue(written.isEmpty())
        saver.submit(base)
        delay(120)
        assertEquals(1, written.size)
    }

    // ---- planned-site distances ----

    @Test fun siteDistancesAreComputedOncePerRecord() {
        val t = TestData.template
        val checker = WarningChecker(t, listOf(Site("TD-01", "1 Lê Lợi", 10.0, 106.0)))
        val gps = FieldValue(v = JsonObject(mapOf("lat" to JsonPrimitive(10.01), "lng" to JsonPrimitive(106.0))))
        val r = TestData.emptyRecord().copy(values = mapOf("A-01" to TestData.text("td-01"), "A-04" to gps))
        val d = checker.siteDistances(r)
        assertEquals(setOf("A-04"), d.keys)
        assertEquals(1112.0, d.getValue("A-04"), 5.0)
        assertEquals(checker.check(r), checker.check(r, d))
        assertTrue(checker.siteDistances(TestData.emptyRecord()).isEmpty())
        assertTrue(WarningChecker(t).siteDistances(r).isEmpty())
    }
}
