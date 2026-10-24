package io.legado.app.help.roles

import io.legado.app.data.entities.BookTtsCastRole
import org.junit.Assert.assertEquals
import org.junit.Test

class RoleArchiveCastMapperTest {

    private val samples = """[
        {"quote":"你终于来了","chapterIndex":3,"weight":0.91},
        {"quote":"炎帝, 好久不见","chapterIndex":8}
    ]"""

    @Test
    fun v2ImportExportMappingKeepsEvidenceFieldsVerbatim() {
        val archiveCast = RoleArchiveCastRole(
            id = 21,
            name = "萧薰儿",
            aliases = listOf("薰儿"),
            gender = "female",
            identityState = "pending",
            occurrenceCount = 12,
            ignored = false,
            linkedRoleId = 0L,
            samplesJson = samples,
            evidence = "第3章 / 第8章 对白证据",
            firstChapterIndex = 3,
            lastChapterIndex = 8,
        )

        val entity = RoleArchiveCastMapper.toEntity(
            workKey = "work-a",
            role = archiveCast,
            linkedRoleId = 0L,
            now = 123L,
        )
        val exported = RoleArchiveCastMapper.toArchive(entity)

        assertEquals(samples, entity.samplesJson)
        assertEquals("第3章 / 第8章 对白证据", entity.evidence)
        assertEquals(3, entity.firstChapterIndex)
        assertEquals(8, entity.lastChapterIndex)
        assertEquals(samples, exported.samplesJson)
        assertEquals("第3章 / 第8章 对白证据", exported.evidence)
        assertEquals(3, exported.firstChapterIndex)
        assertEquals(8, exported.lastChapterIndex)
    }

    @Test
    fun overwriteUndoSnapshotKeepsEvidenceFields() {
        val old = BookTtsCastRole(
            castRoleId = 41,
            workKey = "work-a",
            name = "云韵",
            identityState = "stable",
            occurrenceCount = 5,
            firstChapterIndex = 1,
            lastChapterIndex = 6,
            samplesJson = samples,
            evidence = "旧证据",
        )
        val journal = RoleImportJournal(
            overwrittenCast = listOf(CastRoleSnapshot.from(old)),
        )

        val plan = (RoleImportUndoPlanner.plan(journal)
            as RoleImportUndoPlanner.Validation.Ready).plan
        val restored = plan.restoreCast.single()

        assertEquals(samples, restored.samplesJson)
        assertEquals("旧证据", restored.evidence)
        assertEquals(1, restored.firstChapterIndex)
        assertEquals(6, restored.lastChapterIndex)
    }
}
