package io.legado.app.help.roles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleVoiceSyncPlannerTest {

    private fun source(
        workKey: String = "book-a",
        targetType: String = RoleArchiveTargetType.CHARACTER,
        name: String = "萧炎",
        aliases: List<String> = listOf("炎帝"),
    ) = RoleVoiceSyncPlanner.Source(workKey, targetType, 1L, name, aliases)

    private fun role(
        workKey: String,
        name: String,
        aliases: List<String> = emptyList(),
        targetType: String = RoleArchiveTargetType.CHARACTER,
        id: Long = 10L,
    ) = RoleVoiceSyncPlanner.FormalRole(workKey, targetType, id, name, aliases)

    private fun binding(
        workKey: String,
        targetId: Long,
        speakerId: String = "voice-x",
    ) = RoleVoiceSyncPlanner.Binding(
        workKey,
        RoleArchiveTargetType.CHARACTER,
        targetId,
        "engine",
        speakerId,
        "manual",
    )

    @Test
    fun normalize_removesSpacesDotsAndFullWidth() {
        assertEquals("萧炎", RoleVoiceSyncPlanner.normalize("  萧 · 炎  "))
        assertEquals("abc", RoleVoiceSyncPlanner.normalize("ＡＢＣ·"))
        assertEquals("消炎", RoleVoiceSyncPlanner.normalize("消 炎"))
    }

    @Test
    fun matchesNameAndAliasesInOtherBooksOnly() {
        val plan = RoleVoiceSyncPlanner.plan(
            source(),
            listOf(
                role("book-b", "萧炎", id = 21L),
                role("book-b", "炎帝", id = 22L),
                role("book-a", "萧炎", id = 23L),
                role("book-c", "萧薰儿", aliases = listOf("薰儿"), id = 30L),
                role("book-d", "萧炎", targetType = RoleArchiveTargetType.CAST_ROLE, id = 31L),
            ),
            listOf(binding("book-b", 21L)),
        )

        assertEquals(2, plan.candidates.size)
        val byName = plan.candidates.associateBy { it.name }
        assertTrue(byName.getValue("萧炎").hasExistingBinding)
        assertTrue(!byName.getValue("炎帝").hasExistingBinding)
        assertTrue(plan.candidates.none { it.workKey == "book-a" })
        assertTrue(plan.candidates.none { it.name == "萧薰儿" })
    }

    @Test
    fun aliasMatchAndNormalizationAreApplied() {
        val plan = RoleVoiceSyncPlanner.plan(
            source(name = "萧炎", aliases = listOf("炎帝")),
            listOf(
                role("book-b", "萧 炎", id = 21L),
                role("book-c", "炎·帝", id = 22L),
            ),
            emptyList(),
        )

        assertEquals(2, plan.candidates.size)
        assertEquals(setOf("炎帝"), plan.candidates.first { it.name == "炎·帝" }.matchedAliases.toSet())
    }

    @Test
    fun noCandidatesWhenOnlySameBookOrCastRoles() {
        val plan = RoleVoiceSyncPlanner.plan(
            source(),
            listOf(
                role("book-a", "萧炎"),
                role("book-b", "萧炎", targetType = RoleArchiveTargetType.CAST_ROLE),
                role("book-b", "路人"),
            ),
            emptyList(),
        )

        assertTrue(plan.candidates.isEmpty())
    }

    @Test
    fun sourceCastRoleNeverProducesCandidates() {
        val plan = RoleVoiceSyncPlanner.plan(
            source(targetType = RoleArchiveTargetType.CAST_ROLE),
            listOf(role("book-b", "萧炎")),
            emptyList(),
        )

        assertTrue(plan.candidates.isEmpty())
        assertNull(plan.sourceBinding)
    }

    @Test
    fun sourceBindingIsLookedUpByWorkKeyAndRoleId() {
        val plan = RoleVoiceSyncPlanner.plan(
            source(),
            emptyList(),
            listOf(
                binding("book-a", 1L, "voice-source"),
                binding("book-a", 2L, "voice-other"),
            ),
        )

        assertEquals("voice-source", plan.sourceBinding?.speakerId)
    }
}
