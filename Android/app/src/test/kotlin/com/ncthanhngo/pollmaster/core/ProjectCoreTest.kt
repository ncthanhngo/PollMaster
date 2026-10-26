package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.editor.RecordFactory
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectCodes
import com.ncthanhngo.pollmaster.core.project.ProjectDraft
import com.ncthanhngo.pollmaster.core.project.ProjectException
import com.ncthanhngo.pollmaster.core.project.ProjectMigration
import com.ncthanhngo.pollmaster.core.project.ProjectNames
import com.ncthanhngo.pollmaster.core.project.ProjectRules
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.sites.SitesStore
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
import java.io.File

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
        store.sites(a.id).save(listOf(Site("S1")))
        assertTrue(store.delete(a.id))
        assertNull(store.get(a.id))
        assertEquals("ALPHA", store.byCode("alpha")?.code)
    }

    @Test fun recordProjectIdRoundTripsAndDefaultsOnRead() {
        val r = RecordFactory.create(t, null, NOW, projectId = "p1")
        val json = PollJson.encodeToString(Record.serializer(), r)
        assertTrue(json.contains("\"projectId\": \"p1\""))
        assertEquals("p1", PollJson.decodeFromString(Record.serializer(), json).projectId)
        val old = PollJson.decodeFromString(Record.serializer(), "{\"id\":\"r\",\"templateId\":\"X\"}")
        assertNull(old.projectId)
        assertEquals("chung", ProjectRules.effectiveProjectId(old, "chung"))
        assertFalse(PollJson.encodeToString(Record.serializer(), old).contains("projectId"))
    }

    @Test fun migrationAssignsRecordsMovesSitesAndIsIdempotent() {
        val root = TestData.tempDir()
        val templates = TemplateStore(root)
        templates.install(TestData.templateText, false)
        templates.install(TestData.templateText.replace("\"version\": \"1.2\"", "\"version\": \"1.10\""), false)
        val records = RecordStore(root)
        val old = RecordFactory.create(t, null, NOW)
        records.save(old)
        SitesStore(root).save(listOf(Site("S1", "Hà Nội", 21.0, 105.8)))
        val projects = ProjectStore(root)

        val first = ProjectMigration.run(root, projects, templates, records, NOW)
        val chung = first.chung!!
        assertEquals("chung", chung.id) // fixed id
        assertEquals("CHUNG", chung.code)
        assertEquals(1, first.adopted)
        assertEquals(chung.id, records.load(old.id)?.projectId)
        assertEquals(listOf(TemplateRef(t.id, "1.10")), chung.templates) // newest version only
        assertEquals(listOf("S1"), projects.sites(chung.id).load().map { it.code })
        assertFalse(File(root, "sites.json").exists())

        val second = ProjectMigration.run(root, projects, templates, records, NOW)
        assertEquals(0, second.adopted)
        assertEquals(1, projects.list().size)
        assertEquals(1, projects.sites(chung.id).load().size)
        val other = records.save(RecordFactory.create(t, null, NOW, projectId = "gone")).let { records.list().first { it.projectId == "gone" } }
        assertEquals(1, ProjectMigration.run(root, projects, templates, records, NOW).adopted) // dangling project id
        assertEquals(chung.id, records.load(other.id)?.projectId)
    }

    @Test fun chungWithRandomIdIsRekeyedWithItsRecords() {
        val root = TestData.tempDir()
        val templates = TemplateStore(root)
        val records = RecordStore(root)
        val projects = ProjectStore(root)
        val old = projects.create(ProjectDraft("Chung", "CHUNG"), NOW, id = "random-uuid")
        projects.sites(old.id).save(listOf(Site("S1")))
        val rec = RecordFactory.create(t, null, NOW, projectId = old.id).also(records::save)
        val result = ProjectMigration.run(root, projects, templates, records, NOW)
        assertEquals("random-uuid", result.renamedFrom)
        assertNull(projects.get("random-uuid"))
        assertEquals("chung", projects.get("chung")?.id)
        assertEquals("chung", records.load(rec.id)?.projectId)
        assertEquals(listOf("S1"), projects.sites("chung").load().map { it.code })
        assertEquals(1, projects.list().size)
        assertNull(ProjectMigration.run(root, projects, templates, records, NOW).renamedFrom)
    }

    @Test fun moveTargetsAndDeleteRules() {
        val rec = RecordFactory.create(t, null, NOW, projectId = "a")
        val ref = TemplateRef(t.id, "1.0")
        val a = Project(id = "a", code = "A", templates = listOf(ref))
        val projects = listOf(
            a, Project(id = "b", code = "B", templates = listOf(ref)),
            Project(id = "c", code = "C", templates = listOf(TemplateRef("OTHER", "1"))),
            Project(id = "d", code = "D", templates = listOf(ref), archived = true),
        )
        assertEquals(listOf("b"), ProjectRules.moveTargets(rec, projects).map { it.id })
        assertFalse(ProjectRules.canDelete("a", listOf(rec), null))
        assertTrue(ProjectRules.canDelete("b", listOf(rec), null))
        assertFalse(ProjectRules.canDelete("chung", listOf(rec.copy(projectId = null)), "chung"))
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
