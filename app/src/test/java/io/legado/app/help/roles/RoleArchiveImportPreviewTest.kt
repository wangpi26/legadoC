package io.legado.app.help.roles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleArchiveImportPreviewTest {

    private fun formal(id: Long, name: String, book: String = "a") = RoleArchiveRole(
        id = id,
        name = name,
        aliases = listOf("$name-alias"),
        gender = "male",
        enabled = true,
    )

    private fun cast(id: Long, name: String) = RoleArchiveCastRole(
        id = id,
        name = name,
    )

    private fun binding(type: String, id: Long, speaker: String = "voice") =
        RoleArchiveBinding(type, id, engineId = "engine", speakerId = speaker)

    private fun archive(vararg books: RoleArchiveBook) = RoleArchive(books = books.toList())

    @Test
    fun previewCountsGroupsAndConflictsByBook() {
        val archive = archive(
            RoleArchiveBook(
                workKey = "a",
                bookName = "书A",
                formalRoles = listOf(formal(1L, "萧炎")),
                castRoles = listOf(cast(11L, "云韵")),
                bindings = listOf(binding(RoleArchiveTargetType.CHARACTER, 1L)),
            ),
            RoleArchiveBook(
                workKey = "b",
                bookName = "书B",
                formalRoles = listOf(formal(2L, "药老")),
                bindings = listOf(binding(RoleArchiveTargetType.CHARACTER, 2L)),
            ),
        )
        val plan = RoleArchivePlanner.plan(
            archive,
            listOf(
                RoleArchivePlanner.ExistingRole(
                    workKey = "b",
                    targetType = RoleArchiveTargetType.CHARACTER,
                    name = "药老",
                    id = 99L,
                )
            ),
        )

        val preview = RoleArchiveImporter.buildPreview(plan)

        assertEquals(2, preview.newRoles)
        assertEquals(1, preview.newFormal)
        assertEquals(1, preview.newCast)
        assertEquals(1, preview.conflicts)
        assertEquals(1, preview.conflictFormal)
        assertEquals(0, preview.conflictCast)
        assertEquals(2, preview.bindings)
        assertEquals(1, preview.temporaryRoles)
        assertEquals(2, preview.books.size)
        val bookA = preview.books.first { it.workKey == "a" }
        assertEquals(1, bookA.newFormal)
        assertEquals(1, bookA.newCast)
        assertEquals(0, bookA.conflictFormal)
        assertEquals(1, bookA.bindings)
        val bookB = preview.books.first { it.workKey == "b" }
        assertEquals(0, bookB.newFormal)
        assertEquals(1, bookB.conflictFormal)
        assertEquals(1, bookB.bindings)
        assertEquals(plan, preview.plan)
    }

    @Test
    fun emptyArchiveHasZeroCountsAndNoBooks() {
        val preview = RoleArchiveImporter.buildPreview(
            RoleArchivePlanner.plan(archive(), emptyList())
        )

        assertEquals(0, preview.newRoles)
        assertEquals(0, preview.conflicts)
        assertEquals(0, preview.bindings)
        assertTrue(preview.books.isEmpty())
    }

    @Test
    fun duplicateRoleInsideArchiveIsOnlyAcceptedOnce() {
        val archive = archive(
            RoleArchiveBook(
                workKey = "a",
                bookName = "书A",
                formalRoles = listOf(formal(1L, "萧炎"), formal(2L, "萧炎")),
            )
        )
        val plan = RoleArchivePlanner.plan(archive, emptyList())
        val preview = RoleArchiveImporter.buildPreview(plan)

        assertEquals(1, preview.newFormal)
        assertEquals(0, preview.conflicts)
        assertEquals(1, preview.skipped)
        assertEquals(1, preview.plan.formal.size)
        assertEquals(1, preview.plan.skipped.size)
    }

    @Test
    fun overwriteConflictCapturesArchiveAndExistingFields() {
        val archive = archive(
            RoleArchiveBook(
                workKey = "a",
                bookName = "书A",
                formalRoles = listOf(formal(1L, "萧炎")),
            )
        )
        val plan = RoleArchivePlanner.plan(
            archive,
            listOf(
                RoleArchivePlanner.ExistingRole(
                    workKey = "a",
                    targetType = RoleArchiveTargetType.CHARACTER,
                    name = "萧炎",
                    id = 55L,
                    aliasesJson = "[\"old\"]",
                    gender = "female",
                    enabled = false,
                )
            ),
        )
        val conflict = plan.conflicts.single()

        assertEquals(55L, conflict.existingId)
        assertEquals("萧炎", conflict.existingName)
        assertEquals("female", conflict.existingGender)
        assertEquals(false, conflict.existingEnabled)
        assertEquals("萧炎", conflict.incomingFormal?.name)
        assertEquals(listOf("萧炎-alias"), conflict.incomingFormal?.aliases)
    }
}
