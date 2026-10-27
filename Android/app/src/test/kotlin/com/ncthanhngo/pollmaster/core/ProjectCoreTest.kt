package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.editor.RecordFactory
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectCodes
import com.ncthanhngo.pollmaster.core.project.ProjectDraft
import com.ncthanhngo.pollmaster.core.project.ProjectException
import com.ncthanhngo.pollmaster.core.project.ErpProject
import com.ncthanhngo.pollmaster.core.project.ErpProjectSync
import com.ncthanhngo.pollmaster.core.project.ErpEnvelope
import com.ncthanhngo.pollmaster.core.project.ErpPage
import com.ncthanhngo.pollmaster.core.project.LogoChange
import com.ncthanhngo.pollmaster.core.project.ProjectNames
import com.ncthanhngo.pollmaster.core.project.ProjectRules
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.storage.Backup
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val NOW = "2026-10-23T10:00:00+07:00"

class ProjectCoreTest {
    private val t = TestData.template

    @Test fun projectEncodesAndDecodesAndToleratesMissingKeys() {
        val p = Project(id = "p1", code = "VGREEN-HN", name = "Khảo sát", client = "V-Green", color = "#1B6B5A",
            templates = listOf(TemplateRef("BB-TDP-01", "1.2")), createdAt = NOW, updatedAt = NOW)
        val json = PollJson.encodeToString(Project.serializer(), p)
        assertEquals(p, PollJson.decodeFromString(Project.serializer(), json))
        assertTrue(json.contains("\"schemaVersion\""))
        assertTrue(json.contains("\"archived\": false"))
        val bare = PollJson.decodeFromString(Project.serializer(), "{\"id\":\"x\"}")
        assertEquals("x", bare.id)
        assertFalse(bare.archived)
        assertTrue(bare.templates.isEmpty())
    }

    @Test fun codeFromVietnameseName() {
        assertEquals("KHAO-SAT-TU-DOI", ProjectCodes.fromName("Khảo sát tủ đổi pin Hà Nội"))
        assertEquals("DUONG-DE-DA", ProjectCodes.fromName("Đường  Đê - Đá"))
        assertEquals("V-GREEN-2026", ProjectCodes.fromName("  v-green 2026!! "))
        assertEquals("ABCDEFGHIJKLMNOP", ProjectCodes.fromName("Abcdefghijklmnopqrstuvwxyz"))
        assertEquals("AAAA-BBBB-CCCC-D", ProjectCodes.fromName("aaaa bbbb cccc d eeee"))
        assertTrue(ProjectCodes.fromName("Một hai ba bốn năm sáu bảy").length <= 16)
        assertEquals("", ProjectCodes.fromName("!!!"))
    }

    @Test fun codeIsTrimmedAtDashBoundaryAndUnique() {
        assertEquals("ABCDE-FGHIJ-KLMN", ProjectCodes.fromName("abcde fghij klmn opqrs"))
        assertEquals("ABCDE-FGHIJ", ProjectCodes.fromName("abcde fghij klmnopqrstuv"))
        assertEquals("X", ProjectCodes.generate("x", emptySet()))
        assertEquals("X-2", ProjectCodes.generate("x", setOf("X")))
        assertEquals("X-3", ProjectCodes.generate("x", setOf("X", "x-2")))
        assertEquals("DUAN", ProjectCodes.generate("???", emptySet()))
        val long = ProjectCodes.generate("abcdefghijklmnop", setOf("ABCDEFGHIJKLMNOP"))
        assertEquals("ABCDEFGHIJKLMN-2", long)
        assertTrue(long.length <= 16 && long.endsWith("-2"))
        assertTrue(ProjectCodes.isValid("VGREEN-HN"))
        assertFalse(ProjectCodes.isValid("vgreen"))
        assertFalse(ProjectCodes.isValid("A--B"))
        assertFalse(ProjectCodes.isValid("ABCDEFGHIJKLMNOPQ"))
    }

    @Test fun storeCrudArchiveAndValidation() {
        val store = ProjectStore(TestData.tempDir())
        val a = store.create(ProjectDraft("Beta", "beta", client = " V "), NOW)
        assertEquals("BETA", a.code)
        assertEquals("V", a.client)
        val b = store.create(ProjectDraft("Alpha", "ALPHA"), NOW)
        assertEquals(listOf("ALPHA", "BETA"), store.list().map { it.code })
        try {
            store.create(ProjectDraft("Other", "BETA"), NOW)
            fail("duplicate code accepted")
        } catch (_: ProjectException) {
        }
        try {
            store.create(ProjectDraft(" ", "OK"), NOW)
            fail("blank name accepted")
        } catch (_: ProjectException) {
        }
        assertEquals("BETA", store.update(a.id, ProjectDraft("Beta 2", "BETA", description = "d"), NOW).code) // own code is fine
        assertEquals("Beta 2", store.get(a.id)?.name)
        store.setArchived(b.id, true, NOW)
        assertEquals(listOf("BETA", "ALPHA"), store.list().map { it.code }) // archived last
        store.setTemplates(a.id, listOf(TemplateRef("T", "1"), TemplateRef("T", "1")), NOW)
        assertEquals(1, store.get(a.id)?.templates?.size)
    }

    @Test fun recordProjectIdRoundTripsAndDefaultsOnRead() {
        val r = RecordFactory.create(t, null, NOW, projectId = "p1")
        val json = PollJson.encodeToString(Record.serializer(), r)
        assertTrue(json.contains("\"projectId\": \"p1\""))
        assertEquals("p1", PollJson.decodeFromString(Record.serializer(), json).projectId)
        val old = PollJson.decodeFromString(Record.serializer(), "{\"id\":\"r\",\"templateId\":\"X\"}")
        assertNull(old.projectId)
        assertFalse(PollJson.encodeToString(Record.serializer(), old).contains("projectId"))
    }

    @Test fun erpRowDecodes() {
        val row = """{"id":"p1","code":"EVS-01","name":"Trạm sạc Đà Nẵng","short_name":" ","project_type":"internal","customer_name":null,""" +
            """"customer_logo_url":"/uploads/a.png","created_at":"2026-05-01T08:00:00+07:00","member_count":3}"""
        val e = PollJson.decodeFromString(ErpProject.serializer(), row)
        assertEquals("internal", e.type)
        assertEquals("/uploads/a.png", e.logo)
        assertEquals("Trạm sạc Đà Nẵng", e.displayName)
        val page = PollJson.decodeFromString(ErpEnvelope.serializer(ErpPage.serializer(ErpProject.serializer())), """{"data":{"items":[$row],"total":1}}""")
        assertEquals(1, page.data?.total)
        assertEquals("Ngắn", ErpProject("x", "C", "Long", shortName = "Ngắn").displayName)
    }

    @Test fun erpSyncCreatesUpdatesAndHides() {
        val store = ProjectStore(TestData.tempDir())
        val t1 = TemplateRef("BB-TDP-01", "1.2")
        val a = ErpProject("a", "evs a/1", "Dự án A", type = "company", customerName = "V-Green", logo = "/uploads/a.png")
        val b = ErpProject("b", "B", "Nội bộ B", type = "internal")
        val me = ErpProject("c", "C", "Cá nhân", type = "personal")
        assertEquals(listOf(LogoChange("a", "/uploads/a.png")), ErpProjectSync.apply(listOf(a, b, me), store, NOW))
        val pa = store.get("a")!!
        assertEquals(listOf("EVS-A-1", "Dự án A", "V-Green", "company"), listOf(pa.code, pa.name, pa.client, pa.kind))
        assertEquals("internal", store.get("b")?.kind)
        assertTrue(pa.color != store.get("b")?.color)
        assertNull(store.get("c"))

        store.setTemplates("a", listOf(t1), NOW)
        store.sites("a").save(listOf(Site("S1")))
        assertEquals(listOf(LogoChange("a", null)), ErpProjectSync.apply(listOf(a.copy(name = "Dự án A mới", logo = null)), store, NOW))
        val pa2 = store.get("a")!!
        assertEquals("Dự án A mới", pa2.name)
        assertEquals(listOf(t1), pa2.templates) // device fields kept
        assertEquals(pa.color, pa2.color)
        assertEquals(1, store.sites("a").load().size)
        assertTrue(store.get("b")!!.archived) // dropped from the ERP list: hidden

        ErpProjectSync.apply(listOf(b), store, NOW)
        assertFalse(store.get("b")!!.archived)
        assertTrue(store.get("a")!!.archived)
    }

    @Test fun erpPhoenixStartsWithTheLibrary() {
        val store = ProjectStore(TestData.tempDir())
        val lib = listOf(TemplateRef("BB-TDP-01", "1.2"))
        val phx = ErpProject("phx", "PHN-01", "Dự án Phoenix", type = "company")
        val other = ErpProject("o", "O", "Khác", type = "company")
        ErpProjectSync.apply(listOf(phx, other), store, NOW, lib)
        assertEquals(lib, store.get("phx")?.templates)
        assertTrue(store.get("o")!!.templates.isEmpty())
        store.setTemplates("phx", emptyList(), NOW)
        ErpProjectSync.apply(listOf(phx, other), store, NOW, lib)
        assertTrue(store.get("phx")!!.templates.isEmpty()) // only when it first arrives
    }

    @Test fun newBundledTemplateGoesToPhoenix() {
        val store = ProjectStore(TestData.tempDir())
        val old = TemplateRef("BB-TDP-01", "1.2")
        val new = TemplateRef("KS-VF-SWAP-01", "1.0")
        val phx = ErpProject("phx", "PHN", "PHOENIX", type = "company")
        val other = ErpProject("o", "O", "Khác", type = "company")
        ErpProjectSync.apply(listOf(phx, other), store, NOW, listOf(old))
        store.setTemplates("phx", emptyList(), NOW)
        ErpProjectSync.addNewTemplates(listOf(new), store, NOW)
        assertEquals(listOf(new), store.get("phx")?.templates) // the removed one stays removed
        assertTrue(store.get("o")!!.templates.isEmpty())
        ErpProjectSync.addNewTemplates(listOf(TemplateRef("KS-VF-SWAP-01", "1.1")), store, NOW)
        assertEquals(listOf(new), store.get("phx")?.templates) // a newer version is a suggestion, not added
    }

    @Test fun erpMissingAvatarIsFetchedAgain() {
        val store = ProjectStore(TestData.tempDir())
        val a = ErpProject("a", "A", "A", type = "company", logo = "https://x/a.png")
        ErpProjectSync.apply(listOf(a), store, NOW)
        assertEquals(1, ErpProjectSync.apply(listOf(a), store, NOW).size)
        store.setAvatar("a", byteArrayOf(1))
        assertTrue(ErpProjectSync.apply(listOf(a), store, NOW).isEmpty())
    }

    @Test fun erpFileUrlAndReset() {
        assertEquals("https://api.evselab.com/uploads/a.png", ErpProjectSync.fileUrl("/uploads/a.png", "https://api.evselab.com"))
        assertEquals("https://cdn/x.png", ErpProjectSync.fileUrl("https://cdn/x.png", "https://api.evselab.com"))
        val root = TestData.tempDir()
        TemplateStore(root).install(TestData.templateText, false)
        ProjectStore(root).create(ProjectDraft("Old", "OLD"), NOW)
        RecordStore(root).save(RecordFactory.create(t, null, NOW, projectId = "x"))
        ErpProjectSync.resetLocalData(root)
        assertTrue(ProjectStore(root).list().isEmpty())
        assertTrue(RecordStore(root).list().isEmpty())
        assertEquals(1, TemplateStore(root).list().size)
    }

    @Test fun moveTargetsAndNewerVersion() {
        val rec = RecordFactory.create(t, null, NOW, projectId = "a")
        val ref = TemplateRef(t.id, "1.0")
        val a = Project(id = "a", code = "A", templates = listOf(ref))
        val projects = listOf(
            a, Project(id = "b", code = "B", templates = listOf(ref)),
            Project(id = "c", code = "C", templates = listOf(TemplateRef("OTHER", "1"))),
            Project(id = "d", code = "D", templates = listOf(ref), archived = true),
        )
        assertEquals(listOf("b"), ProjectRules.moveTargets(rec, projects).map { it.id })
        assertEquals("1.3", ProjectRules.newerVersion(ref, listOf(TemplateRef(t.id, "1.3"), TemplateRef(t.id, "1.2"), TemplateRef(t.id, "0.9")), ::cmp))
        assertNull(ProjectRules.newerVersion(ref, listOf(TemplateRef(t.id, "1.0")), ::cmp))
    }

    private fun cmp(a: String, b: String) = com.ncthanhngo.pollmaster.core.storage.compareVersions(a, b)

    @Test fun fileNamesCarryTheProjectCode() {
        assertEquals("VG_HD-01_20261023-1000", ProjectNames.recordBase("VG", "HD 01", "id", "20261023-1000"))
        assertEquals("VG_id_20261023-1000", ProjectNames.recordBase("VG", null, "id", "20261023-1000"))
        assertEquals("HD-01_20261023-1000", ProjectNames.recordBase(null, "HD 01", "id", "20261023-1000"))
        assertEquals("VG_tong-hop_20261023-1000.xlsx", ProjectNames.summary("VG", "20261023-1000"))
        assertEquals("VG_phieu_20261023-1000.zip", ProjectNames.recordsZip("VG", "20261023-1000"))
        assertEquals("VG_HD-01_abcdef12_backup.zip", Backup.fileName("HD 01", "abcdef12-0000", "VG"))
        assertEquals("VG_abcdef12-0000_backup.zip", Backup.fileName(null, "abcdef12-0000", "VG"))
        assertEquals("Download/PollMaster/VG", Backup.folder("VG"))
        assertEquals("Download/PollMaster", Backup.folder(null))
        assertNotNull(Backup.FOLDER)
    }

    @Test fun nextColorSkipsColoursInUse() {
        assertEquals(Project.COLORS[0], Project.nextColor(emptyList()))
        assertEquals(Project.COLORS[2], Project.nextColor(listOf(Project.COLORS[0], Project.COLORS[1])))
        // case-insensitive, unknown colours ignored
        assertEquals(Project.COLORS[1], Project.nextColor(listOf(Project.COLORS[0].lowercase(), "#000000")))
    }

    @Test fun nextColorWhenAllUsedPicksLeastUsed() {
        assertEquals(Project.COLORS[0], Project.nextColor(Project.COLORS + Project.COLORS.drop(1)))
    }

    @Test fun paletteColoursAreDistinct() {
        assertEquals(Project.COLORS.size, Project.COLORS.map { it.uppercase() }.toSet().size)
        assertTrue(Project.COLORS.size >= 10)
    }

    @Test fun initialsFromName() {
        assertEquals("KS", Project.initials("Khảo sát tủ đổi pin Hà Nội"))
        assertEquals("C", Project.initials("Chung"))
        assertEquals("TS", Project.initials("  trạm sạc  "))
        assertEquals("?", Project.initials(""))
    }

    @Test fun avatarFileLifecycle() {
        val store = ProjectStore(TestData.tempDir())
        val p = store.create(ProjectDraft("Avatar", "AV"), NOW)
        assertNull(store.avatarFile(p.id))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        store.setAvatar(p.id, jpeg)
        assertEquals(jpeg.toList(), store.avatarFile(p.id)!!.readBytes().toList())
        assertEquals(listOf(p.id), store.list().map { it.id })
        store.removeAvatar(p.id)
        assertNull(store.avatarFile(p.id))
        store.removeAvatar(p.id) // removing twice is fine
    }
}
