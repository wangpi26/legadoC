package io.legado.app.help.roles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleVoiceSyncApplierTest {

    private fun plan(
        sourceBinding: RoleVoiceSyncPlanner.Binding? = RoleVoiceSyncPlanner.Binding(
            "book-a",
            RoleArchiveTargetType.CHARACTER,
            1L,
            "engine",
            "voice-source",
            "manual",
        ),
        candidates: List<RoleVoiceSyncPlanner.Candidate> = listOf(
            RoleVoiceSyncPlanner.Candidate("book-b", 21L, "萧炎", emptyList(), false),
            RoleVoiceSyncPlanner.Candidate("book-c", 31L, "萧炎", emptyList(), true),
        ),
    ) = RoleVoiceSyncPlanner.Plan(
        source = RoleVoiceSyncPlanner.Source(
            "book-a",
            RoleArchiveTargetType.CHARACTER,
            1L,
            "萧炎",
            emptyList(),
        ),
        sourceBinding = sourceBinding,
        candidates = candidates,
    )

    @Test
    fun defaultDecision_skipsExistingBinding() {
        val decision = RoleVoiceSyncApplier.decide(plan(), overwrite = false)
            as RoleVoiceSyncApplier.Decision.Ready

        assertEquals(1, decision.counts.added)
        assertEquals(0, decision.counts.overwritten)
        assertEquals(1, decision.counts.skipped)
        assertEquals(1, decision.actions.size)
        assertEquals("book-b", decision.actions.single().candidate.workKey)
    }

    @Test
    fun overwriteDecision_overwritesExistingBinding() {
        val decision = RoleVoiceSyncApplier.decide(plan(), overwrite = true)
            as RoleVoiceSyncApplier.Decision.Ready

        assertEquals(1, decision.counts.added)
        assertEquals(1, decision.counts.overwritten)
        assertEquals(0, decision.counts.skipped)
        assertEquals(2, decision.actions.size)
        assertTrue(decision.actions.all { it.overwrite || !it.candidate.hasExistingBinding })
    }

    @Test
    fun missingSourceBinding_returnsFailure() {
        val decision = RoleVoiceSyncApplier.decide(plan(sourceBinding = null), false)

        assertTrue(decision is RoleVoiceSyncApplier.Decision.Failure)
        assertEquals(
            "源角色没有可同步的音色绑定",
            (decision as RoleVoiceSyncApplier.Decision.Failure).reason,
        )
    }

    @Test
    fun blankSpeaker_returnsFailure() {
        val binding = RoleVoiceSyncPlanner.Binding(
            "book-a",
            RoleArchiveTargetType.CHARACTER,
            1L,
            "engine",
            "",
            "manual",
        )
        val decision = RoleVoiceSyncApplier.decide(plan(sourceBinding = binding), false)

        assertTrue(decision is RoleVoiceSyncApplier.Decision.Failure)
    }

    @Test
    fun emptyCandidates_returnsZeroCounts() {
        val decision = RoleVoiceSyncApplier.decide(plan(candidates = emptyList()), false)
            as RoleVoiceSyncApplier.Decision.Ready

        assertEquals(0, decision.counts.added)
        assertEquals(0, decision.counts.overwritten)
        assertEquals(0, decision.counts.skipped)
        assertTrue(decision.actions.isEmpty())
    }
}
