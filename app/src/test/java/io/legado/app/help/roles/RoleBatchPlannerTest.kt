package io.legado.app.help.roles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleBatchPlannerTest {

    private fun item(
        name: String,
        targetType: String = RoleArchiveTargetType.CHARACTER,
        id: Long = 1L,
        workKey: String = "w",
        bookTitle: String = "书",
    ) = RoleBatchPlanner.Item(
        workKey = workKey,
        targetType = targetType,
        targetId = id,
        name = name,
        bookTitle = bookTitle,
        gender = "unknown",
        enabled = true,
        identityState = "stable",
    )

    @Test
    fun splitSeparatesFormalAndTemporary() {
        val split = RoleBatchPlanner.split(
            listOf(
                item("正式1"),
                item("正式2", id = 2L),
                item("临时1", targetType = RoleArchiveTargetType.CAST_ROLE, id = 11L),
            )
        )

        assertEquals(listOf("正式1", "正式2"), split.formal.map { it.name })
        assertEquals(listOf("临时1"), split.temporary.map { it.name })
    }

    @Test
    fun genderAndEnabledOnlyTargetFormalRoles() {
        val items = listOf(
            item("正式1", id = 1L),
            item("临时1", targetType = RoleArchiveTargetType.CAST_ROLE, id = 11L),
        )

        assertEquals(listOf("正式1"), RoleBatchPlanner.genderTargets(items).map { it.name })
        assertEquals(listOf("正式1"), RoleBatchPlanner.enabledTargets(items).map { it.name })
    }

    @Test
    fun promoteOnlyTargetsTemporaryRoles() {
        val items = listOf(
            item("正式1", id = 1L),
            item("临时1", targetType = RoleArchiveTargetType.CAST_ROLE, id = 11L),
            item("临时2", targetType = RoleArchiveTargetType.CAST_ROLE, id = 12L),
        )

        assertEquals(
            listOf("临时1", "临时2"),
            RoleBatchPlanner.promoteTargets(items).map { it.name },
        )
    }

    @Test
    fun deleteTargetsAllSelectedAndLabelsContainBookAndName() {
        val items = listOf(
            item("正式1", bookTitle = "书A"),
            item("临时1", targetType = RoleArchiveTargetType.CAST_ROLE, id = 11L, bookTitle = "书B"),
        )

        assertEquals(2, RoleBatchPlanner.deleteTargets(items).size)
        assertEquals(
            listOf("《书A》正式1", "《书B》临时1"),
            RoleBatchPlanner.deleteLabels(items),
        )
    }

    @Test
    fun voiceSourceFallsBackToExistingBindings() {
        val fallback = listOf(
            RoleBatchPlanner.VoiceOption("engine", "voice-1", "engine · voice-1"),
        )

        val result = RoleBatchPlanner.resolveVoiceSources(emptyList(), fallback)
            as RoleBatchPlanner.VoiceSourceResult.Success

        assertTrue(result.fromFallback)
        assertEquals(fallback, result.options)
    }

    @Test
    fun voiceSourceDoesNotFallbackWhenEngineDirectoryExists() {
        val engine = listOf(RoleBatchPlanner.VoiceOption("engine", "voice-1", "voice-1"))
        val fallback = listOf(RoleBatchPlanner.VoiceOption("engine", "voice-2", "voice-2"))

        val result = RoleBatchPlanner.resolveVoiceSources(engine, fallback)
            as RoleBatchPlanner.VoiceSourceResult.Success

        assertTrue(!result.fromFallback)
        assertEquals(engine, result.options)
    }

    @Test
    fun voiceSourceBothEmptyReturnsFailure() {
        val result = RoleBatchPlanner.resolveVoiceSources(emptyList(), emptyList())

        assertTrue(result is RoleBatchPlanner.VoiceSourceResult.Failure)
        assertTrue(
            (result as RoleBatchPlanner.VoiceSourceResult.Failure).reason.contains("没有可用音色")
        )
    }
}
