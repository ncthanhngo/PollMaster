package com.ncthanhngo.pollmaster.core

import com.ncthanhngo.pollmaster.core.editor.RecordFactory
import com.ncthanhngo.pollmaster.core.export.Cell
import com.ncthanhngo.pollmaster.core.export.ExportBuilder
import com.ncthanhngo.pollmaster.core.export.ExportJson
import com.ncthanhngo.pollmaster.core.export.ProjectSummary
import com.ncthanhngo.pollmaster.core.export.XlsxWriter
import com.ncthanhngo.pollmaster.core.export.ZipExporter
import com.ncthanhngo.pollmaster.core.export.ZipRecord
import com.ncthanhngo.pollmaster.core.model.Record
import com.ncthanhngo.pollmaster.core.model.tables
import com.ncthanhngo.pollmaster.core.project.BundleException
import com.ncthanhngo.pollmaster.core.project.Project
import com.ncthanhngo.pollmaster.core.project.ProjectBundle
import com.ncthanhngo.pollmaster.core.project.ProjectException
import com.ncthanhngo.pollmaster.core.project.ProjectImporter
import com.ncthanhngo.pollmaster.core.project.ProjectStore
import com.ncthanhngo.pollmaster.core.project.TemplateRef
import com.ncthanhngo.pollmaster.core.project.ref
import com.ncthanhngo.pollmaster.core.sites.Site
import com.ncthanhngo.pollmaster.core.storage.RecordStore
import com.ncthanhngo.pollmaster.core.storage.TemplateStore
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

private const val NOW = "2026-10-23T10:00:00+07:00"

class ProjectBundleTest {
    private val t = TestData.template
    private val ref = TemplateRef(t.id, t.version)
    private val project = Project(id = "p-1", code = "VG-HN", name = "Tủ đổi pin", client = "V-Green", templates = listOf(ref), createdAt = NOW, updatedAt = NOW)
    private val sites = listOf(Site("S1", "1 Phố, \"Cổ\"", 21.0, 105.8), Site("S2", "Không tọa độ"))

    private fun bundle(p: Project = project, withSites: Boolean = true): ByteArray =
        ByteArrayOutputStream().also { ProjectBundle.write(p, mapOf(ref to TestData.templateText), if (withSites) sites else null, it) }.toByteArray()

    private fun zip(entries: Map<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.forEach { m[it.name] = z.readBytes() } }
        return m
    }

    @Test fun bundleRoundTrips() {
        val bytes = bundle()
        assertEquals(setOf("project.json", "templates/${t.id}@${t.version}.json", "sites.csv"), entries(bytes).keys)
        assertTrue(entries(bytes).getValue("sites.csv").decodeToString().startsWith("\uFEFFcode,address,lat,lng"))
        val back = ProjectBundle.read(bytes)
        assertEquals(project, back.project)
        assertEquals(listOf(t), back.templates.map { it.template })
        assertEquals(sites, back.sites)
        assertNull(ProjectBundle.read(bundle(withSites = false)).sites)
    }

    @Test fun readValidatesContentNotExtension() {
        fun errors(bytes: ByteArray) = try { ProjectBundle.read(bytes); fail("accepted"); emptyList() } catch (e: BundleException) { e.errors }
        assertTrue(errors("not a zip".toByteArray()).single().startsWith("Không phải gói dự án"))
        assertTrue(errors(zip(mapOf("hello.txt" to "x"))).single().contains("project.json"))
        assertTrue(errors(zip(mapOf("project.json" to "{ nope"))).single().startsWith("project.json"))
        val bad = errors(zip(mapOf("project.json" to "{\"id\":\"a\",\"code\":\"x y\"}", "templates/a@1.json" to "{ nope", "sites.csv" to "foo\nbar")))
        assertTrue(bad.any { it.contains("code không hợp lệ") })
        assertTrue(bad.any { it.startsWith("templates/a@1.json") })
        assertTrue(bad.any { it.startsWith("sites.csv") })
    }

    /** The ERP project a bundle is imported into on the receiving device. */
    private fun target(projects: ProjectStore, id: String = "erp-1", templates: List<TemplateRef> = emptyList()): Project =
        Project(id = id, code = "ERP-1", name = "Dự án ERP", kind = "company", templates = templates, createdAt = NOW, updatedAt = NOW)
            .also(projects::save)

    @Test fun importAddsTemplatesAndSitesToTheChosenProject() {
        val root = TestData.tempDir()
        val projects = ProjectStore(root)
        val templates = TemplateStore(root)
        val other = TemplateRef("OTHER", "1")
        target(projects, templates = listOf(other))
        val contents = ProjectBundle.read(bundle())
        val plan = ProjectImporter.plan(contents, projects, templates)
        assertNull(plan.proposed)
        val out = ProjectImporter.apply(contents, "erp-1", projects, templates, false, NOW)
        assertEquals(listOf(ref), out.installed)
        assertNotNull(templates.get(t.id, t.version))
        val p = projects.get("erp-1")!!
        assertEquals("Dự án ERP" to "ERP-1", p.name to p.code) // ERP identity stays
        assertEquals(listOf(other, ref), p.templates)
        assertEquals(1, projects.list().size)
        assertEquals(listOf("S1", "S2"), projects.sites("erp-1").load().map { it.code })
        assertEquals("1 Phố, \"Cổ\"", projects.sites("erp-1").load().first().address)
    }

    @Test fun ownProjectIsProposedAndVersionsReplacedWithoutTouchingRecords() {
        val root = TestData.tempDir()
        val projects = ProjectStore(root)
        val templates = TemplateStore(root)
        val records = RecordStore(root)
        target(projects, id = "p-1", templates = listOf(TemplateRef(t.id, "0.9")))
        val rec = RecordFactory.create(t, null, NOW, projectId = "p-1").also(records::save)
        projects.sites("p-1").save(listOf(Site("OLD")))
        val contents = ProjectBundle.read(bundle(withSites = false))
        assertEquals("p-1", ProjectImporter.plan(contents, projects, templates).proposed?.id)
        ProjectImporter.apply(contents, "p-1", projects, templates, false, NOW)
        assertEquals(listOf(ref), projects.get("p-1")?.templates) // 0.9 replaced by the bundled version
        assertEquals(listOf("OLD"), projects.sites("p-1").load().map { it.code }) // no sites.csv -> sites kept
        assertEquals("p-1", records.load(rec.id)?.projectId)
        projects.setArchived("p-1", true, NOW)
        assertNull(ProjectImporter.plan(contents, projects, templates).proposed) // hidden projects are not proposed
    }

    @Test fun refsMissingFromBundleAndLibraryAreDroppedAndReported() {
        val root = TestData.tempDir()
        val projects = ProjectStore(root)
        val templates = TemplateStore(root)
        target(projects)
        val ghost = TemplateRef("GHOST", "1")
        val contents = ProjectBundle.read(bundle(project.copy(templates = listOf(ref, ghost))))
        assertEquals(listOf(ghost), ProjectImporter.plan(contents, projects, templates).dropped)
        val out = ProjectImporter.apply(contents, "erp-1", projects, templates, false, NOW)
        assertEquals(listOf(ghost), out.dropped)
        assertEquals(listOf(ref), projects.get("erp-1")?.templates)
    }

    @Test fun importNeedsAnExistingTarget() {
        val root = TestData.tempDir()
        try {
            ProjectImporter.apply(ProjectBundle.read(bundle()), "missing", ProjectStore(root), TemplateStore(root), false, NOW)
            fail("imported into nothing")
        } catch (_: ProjectException) {}
    }

    @Test fun differingInstalledTemplateIsAConflictResolvedByTheUser() {
        val root = TestData.tempDir()
        val projects = ProjectStore(root)
        val templates = TemplateStore(root)
        target(projects)
        val changed = TestData.templateText.replace("\"title\": \"", "\"title\": \"X ")
        templates.install(changed, false)
        val contents = ProjectBundle.read(bundle())
        assertEquals(listOf(ref), ProjectImporter.plan(contents, projects, templates).conflicts)
        val kept = ProjectImporter.apply(contents, "erp-1", projects, templates, false, NOW)
        assertEquals(listOf(ref), kept.kept)
        assertTrue(templates.get(t.id, t.version)!!.title.startsWith("X "))
        ProjectImporter.apply(contents, "erp-1", projects, templates, true, NOW)
        assertEquals(t.title, templates.get(t.id, t.version)!!.title)
    }

    @Test fun exportJsonAndZipCarryTheProject() {
        val r = RecordFactory.create(t, null, NOW, projectId = "p-1")
        val json = ExportJson.build(t, r, project.ref())
        val p = json.getValue("project").jsonObject
        assertEquals("VG-HN", p.getValue("code").jsonPrimitive.content)
        assertEquals("Tủ đổi pin", p.getValue("name").jsonPrimitive.content)
        assertFalse(ExportJson.build(t, r).containsKey("project"))
        val info = ExportBuilder(t, r, project.ref()).infoSheet().rows.first()
        assertEquals(Cell.Text("Dự án"), info[2])
        assertEquals(Cell.Text("Tủ đổi pin (VG-HN)"), info[3])
        assertTrue(ExportBuilder(t, r, project.ref()).document().blocks.any { it.toString().contains("Dự án: Tủ đổi pin (VG-HN)") })
    }

    @Test fun recordsZipHasAFolderPerRecord() {
        val dir = TestData.tempDir()
        val store = RecordStore(dir)
        val a = RecordFactory.create(t, null, NOW).also(store::save)
        val b = RecordFactory.create(t, null, NOW).also(store::save)
        val out = ByteArrayOutputStream()
        ZipExporter.writeMany(listOf(ZipRecord(t, a, store.dir(a.id)), ZipRecord(t, b, store.dir(b.id))), out, project.ref())
        val names = entries(out.toByteArray()).keys
        assertEquals(setOf("${a.id}/record.json", "${a.id}/export.json", "${b.id}/record.json", "${b.id}/export.json"), names)
    }

    @Test fun summaryHasSheetPerTemplateAndTableSheetsWithRecordCode() {
        val done = Record(
            id = "r-done", templateId = t.id, templateVersion = t.version, status = "completed",
            createdAt = NOW, updatedAt = NOW, completedAt = NOW,
            values = mapOf("HD-code" to TestData.text("007")),
        )
        val draft = done.copy(id = "r-draft", status = "draft", completedAt = null, values = mapOf("HD-code" to TestData.text("008")))
        val all = ProjectSummary.sheets(listOf(t), listOf(t to done, t to draft), completedOnly = false)
        val main = all.first()
        assertEquals(t.id, main.name)
        assertEquals(2, main.headerRows)
        assertEquals(listOf("recordCode", "title", "status", "createdAt", "updatedAt", "completedAt"), main.headers.take(6))
        assertEquals(listOf("Mã phiếu", "Tiêu đề", "Trạng thái", "Tạo lúc", "Cập nhật lúc", "Hoàn thành lúc").map { Cell.Text(it) }, main.rows[0].take(6)) // labels row
        assertEquals(2, main.rows.size - 1) // one row per record
        assertEquals(listOf("Hoàn thành", "Nháp"), main.rows.drop(1).map { (it[2] as Cell.Text).value })
        assertEquals(main.headers.size, main.rows.first().size)
        assertTrue(main.headers.contains("A-01")) // field codes
        val computed = t.tables() // table sheets
        assertEquals(1 + computed.size, all.size)
        val table = all[1]
        assertEquals("${t.id}-${computed.first().id}", table.name)
        assertEquals("Mã phiếu", table.headers.first())
        assertEquals(computed.first().columns.size + 1, table.headers.size)

        val onlyDone = ProjectSummary.sheets(listOf(t), listOf(t to done, t to draft), completedOnly = true)
        assertEquals(1, onlyDone.first().rows.size - 1)
        assertEquals(Cell.Text("007"), onlyDone.first().rows[1][0])
    }

    @Test fun summaryColumnsAreTheUnionOfVersionsNewestFirst() {
        val newer = t.copy(version = "9.0", header = t.header.drop(1)) // drops the first header field
        val older = t.copy(version = "1.0")
        val dropped = t.header.first().id
        val r1 = Record(id = "a", templateId = t.id, templateVersion = "1.0", createdAt = NOW, updatedAt = NOW)
        val r2 = r1.copy(id = "b", templateVersion = "9.0", createdAt = "2026-10-24T10:00:00+07:00")
        val sheet = ProjectSummary.sheets(listOf(newer), listOf(newer to r2, older to r1), false).first()
        assertTrue(sheet.headers.contains(dropped)) // older version's field is kept
        assertTrue(sheet.headers.indexOf(dropped) > sheet.headers.indexOf(newer.header.first().id)) // after the newest order
        assertEquals(listOf("a", "b"), sheet.rows.drop(1).map { (it[0] as Cell.Text).value }) // createdAt ascending
    }

    @Test fun summaryXlsxIsWellFormed() {
        val r = RecordFactory.create(t, "An", NOW)
        val sheets = ProjectSummary.sheets(listOf(t), listOf(t to r), completedOnly = false)
        val out = ByteArrayOutputStream()
        XlsxWriter.write(sheets, out)
        val parts = entries(out.toByteArray())
        assertTrue("xl/worksheets/sheet${sheets.size}.xml" in parts)
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        parts.forEach { (n, b) -> if (n.endsWith(".xml") || n.endsWith(".rels")) f.newDocumentBuilder().parse(ByteArrayInputStream(b)) }
        assertTrue(parts.getValue("xl/worksheets/sheet1.xml").decodeToString().contains("ySplit=\"2\""))
        val empty = ProjectSummary.sheets(emptyList(), emptyList(), false)
        assertEquals(1, empty.size)
    }
}
