package io.legado.app.help.roles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleVoiceGapPlannerTest {

    @Test
    fun allBoundBookIsExcluded() {
        val gaps = RoleVoiceGapPlanner.plan(
            roles = listOf(
                role("book-a", 1, "萧炎"),
                role("book-a", 2, "药老"),
            ),
            bindings = listOf(
                binding("book-a", 1, "voice-a"),
                binding("book-a", 2, "voice-b"),
            ),
            bookTitles = mapOf("book-a" to "斗破苍穹"),
        )

        assertTrue(gaps.isEmpty())
    }

    @Test
    fun partialBookKeepsCountsAndUnboundRoles() {
        val gaps = RoleVoiceGapPlanner.plan(
            roles = listOf(
                role("book-a", 1, "萧炎"),
                role("book-a", 2, "药老"),
                role("book-a", 3, "萧薰儿"),
            ),
            bindings = listOf(binding("book-a", 1, "voice-a")),
            bookTitles = mapOf("book-a" to "斗破苍穹"),
        )

        val gap = gaps.single()
        assertEquals("斗破苍穹", gap.bookTitle)
        assertEquals(3, gap.totalFormal)
        assertEquals(1, gap.boundCount)
        assertEquals(2, gap.unboundCount)
        assertEquals(listOf("药老", "萧薰儿"), gap.unboundRoles.map { it.name })
    }

    @Test
    fun bookWithoutFormalRolesIsExcluded() {
        val gaps = RoleVoiceGapPlanner.plan(
            roles = listOf(role("book-a", 1, "云韵", targetType = RoleArchiveTargetType.CAST_ROLE)),
            bindings = emptyList(),
            bookTitles = mapOf("book-a" to "斗破苍穹"),
        )

        assertTrue(gaps.isEmpty())
    }

    @Test
    fun temporaryRoleIsNotCounted() {
        val gaps = RoleVoiceGapPlanner.plan(
            roles = listOf(
                role("book-a", 1, "萧炎"),
                role("book-a", 2, "云韵", targetType = RoleArchiveTargetType.CAST_ROLE),
            ),
            bindings = emptyList(),
            bookTitles = mapOf("book-a" to "斗破苍穹"),
        )

        val gap = gaps.single()
        assertEquals(1, gap.totalFormal)
        assertEquals(listOf("萧炎"), gap.unboundRoles.map { it.name })
    }

    @Test
    fun disabledRoleIsNotCounted() {
        val gaps = RoleVoiceGapPlanner.plan(
            roles = listOf(
                role("book-a", 1, "萧炎", enabled = false),
            ),
            bindings = emptyList(),
            bookTitles = mapOf("book-a" to "斗破苍穹"),
        )

        assertTrue(gaps.isEmpty())
    }

    @Test
    fun blankSpeakerIsUnbound() {
        val gaps = RoleVoiceGapPlanner.plan(
            roles = listOf(
                role("book-a", 1, "萧炎"),
                role("book-a", 2, "药老"),
            ),
            bindings = listOf(
                binding("book-a", 1, ""),
                binding("book-a", 2, "   "),
                binding("book-a", 1, "voice-a", targetType = RoleArchiveTargetType.CAST_ROLE),
            ),
            bookTitles = mapOf("book-a" to "斗破苍穹"),
        )

        val gap = gaps.single()
        assertEquals(0, gap.boundCount)
        assertEquals(2, gap.unboundCount)
    }

    @Test
    fun multipleBooksAreSortedByUnboundCountDescending() {
        val gaps = RoleVoiceGapPlanner.plan(
            roles = listOf(
                role("book-a", 1, "甲"),
                role("book-a", 2, "乙"),
                role("book-b", 3, "丙"),
                role("book-b", 4, "丁"),
                role("book-b", 5, "戊"),
                role("book-b", 6, "己"),
            ),
            bindings = listOf(binding("book-b", 3, "voice-a")),
            bookTitles = mapOf("book-a" to "书甲", "book-b" to "书乙"),
        )

        assertEquals(listOf("书乙", "书甲"), gaps.map { it.bookTitle })
        assertEquals(listOf(3, 2), gaps.map { it.unboundCount })
    }

    private fun role(
        workKey: String,
        roleId: Long,
        name: String,
        enabled: Boolean = true,
        targetType: String = RoleArchiveTargetType.CHARACTER,
    ) = RoleVoiceGapPlanner.FormalRole(workKey, roleId, name, enabled, targetType)

    private fun binding(
        workKey: String,
        targetId: Long,
        speakerId: String,
        targetType: String = RoleArchiveTargetType.CHARACTER,
    ) = RoleVoiceGapPlanner.Binding(workKey, targetType, targetId, speakerId)
}
