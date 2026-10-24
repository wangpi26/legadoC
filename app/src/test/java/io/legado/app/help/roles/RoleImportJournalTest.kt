package io.legado.app.help.roles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleImportJournalTest {

    private fun formalSnapshot(id: Long, name: String = "旧名") = FormalRoleSnapshot(
        roleId = id,
        workKey = "w",
        name = name,
        aliasesJson = "[\"旧别名\"]",
        gender = "male",
        enabled = false,
    )

    private fun castSnapshot(id: Long) = CastRoleSnapshot(
        castRoleId = id,
        workKey = "w",
        name = "旧临时",
        identityState = "pending",
    )

    private fun binding(id: Long, speaker: String) = BindingSnapshot(
        workKey = "w",
        targetType = RoleArchiveTargetType.CHARACTER,
        targetId = id,
        engineId = "engine",
        speakerId = speaker,
        bindingMode = "auto",
    )

    @Test
    fun undoPlanIsSymmetricForCreatedOverwrittenAndBindings() {
        val oldBinding = binding(20L, "old-voice")
        val newBinding = binding(20L, "new-voice")
        val journal = RoleImportJournal(
            newFormalIds = listOf(10L),
            newCastIds = listOf(11L),
            overwrittenFormal = listOf(formalSnapshot(20L)),
            overwrittenCast = listOf(castSnapshot(21L)),
            previousBindings = listOf(oldBinding),
            writtenBindings = listOf(newBinding),
        )

        val plan = (RoleImportUndoPlanner.plan(journal)
            as RoleImportUndoPlanner.Validation.Ready).plan

        assertEquals(listOf(10L), plan.deleteFormalIds)
        assertEquals(listOf(11L), plan.deleteCastIds)
        assertEquals(listOf(formalSnapshot(20L)), plan.restoreFormal)
        assertEquals(listOf(castSnapshot(21L)), plan.restoreCast)
        assertEquals(
            setOf(RoleBindingKey("w", RoleArchiveTargetType.CHARACTER, 20L)),
            plan.deleteBindings.toSet(),
        )
        assertEquals(listOf(oldBinding), plan.restoreBindings)
    }

    @Test
    fun emptyOrVersionMismatchedJournalCannotBeUndone() {
        val empty = RoleImportUndoPlanner.plan(RoleImportJournal())
        assertTrue(empty is RoleImportUndoPlanner.Validation.Failure)

        val oldVersion = RoleImportJournal(
            version = RoleImportJournal.CURRENT_VERSION + 1,
            newFormalIds = listOf(1L),
        )
        assertTrue(RoleImportUndoPlanner.plan(oldVersion) is RoleImportUndoPlanner.Validation.Failure)
    }

    @Test
    fun codecRoundTripsJournalAndRejectsCorruptJson() {
        val journal = RoleImportJournal(
            strategy = RoleImportStrategy.OVERWRITE.name,
            newFormalIds = listOf(10L),
            newCastIds = listOf(11L),
            overwrittenFormal = listOf(formalSnapshot(20L)),
            overwrittenCast = listOf(castSnapshot(21L)),
            previousBindings = listOf(binding(20L, "old-voice")),
            writtenBindings = listOf(binding(20L, "new-voice")),
        )
        val decoded = RoleImportJournalCodec.decode(
            RoleImportJournalCodec.encode(journal)
        ).getOrThrow()

        assertEquals(journal, decoded)
        assertTrue(RoleImportJournalCodec.decode("not-json").isFailure)
        val badVersion = RoleImportJournalCodec.encode(
            journal.copy(version = RoleImportJournal.CURRENT_VERSION + 1)
        )
        assertTrue(RoleImportJournalCodec.decode(badVersion).isFailure)
    }

    @Test
    fun duplicateWrittenBindingsAreDeletedOnceButRestoredOldValue() {
        val oldBinding = binding(20L, "old-voice")
        val first = binding(20L, "new-voice-1")
        val second = binding(20L, "new-voice-2")
        val journal = RoleImportJournal(
            overwrittenFormal = listOf(formalSnapshot(20L)),
            previousBindings = listOf(oldBinding),
            writtenBindings = listOf(first, second),
        )

        val plan = (RoleImportUndoPlanner.plan(journal)
            as RoleImportUndoPlanner.Validation.Ready).plan

        assertEquals(1, plan.deleteBindings.size)
        assertEquals(listOf(oldBinding), plan.restoreBindings)
    }
}
