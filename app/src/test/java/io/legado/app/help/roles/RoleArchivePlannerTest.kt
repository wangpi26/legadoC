package io.legado.app.help.roles

import org.junit.Assert.assertEquals
import org.junit.Test

class RoleArchivePlannerTest {

    private fun book(
        workKey: String,
        formal: List<RoleArchiveRole> = emptyList(),
        cast: List<RoleArchiveCastRole> = emptyList(),
        bindings: List<RoleArchiveBinding> = emptyList(),
    ) = RoleArchiveBook(
        workKey = workKey,
        formalRoles = formal,
        castRoles = cast,
        bindings = bindings,
    )

    @Test
    fun sameNameInExistingBook_isSkippedAndBindingKeepsExistingId() {
        val archive = RoleArchive(
            books = listOf(
                book(
                    workKey = "w",
                    formal = listOf(
                        RoleArchiveRole(1, "萧炎"),
                        RoleArchiveRole(2, "药老"),
                    ),
                    bindings = listOf(
                        RoleArchiveBinding("character", 2, "e", "v"),
                    ),
                )
            )
        )
        val plan = RoleArchivePlanner.plan(
            archive,
            listOf(
                RoleArchivePlanner.ExistingRole(
                    "w",
                    RoleArchiveTargetType.CHARACTER,
                    " 萧炎 ",
                    99L,
                )
            ),
        )

        assertEquals(1, plan.formal.size)
        assertEquals("药老", plan.formal.single().role.name)
        assertEquals(1, plan.skippedCount)
        assertEquals(99L, plan.skipped.single().existingId)
        val remapped = RoleArchiveIdMapper.resolve(
            "character",
            1L,
            mapOf(1L to 99L, 2L to 100L),
            emptyMap(),
        )
        assertEquals(99L, remapped)
    }

    @Test
    fun duplicateNamesInsideArchive_onlyFirstIsInserted() {
        val archive = RoleArchive(
            books = listOf(
                book(
                    workKey = "w",
                    cast = listOf(
                        RoleArchiveCastRole(11, "萧炎"),
                        RoleArchiveCastRole(12, "萧炎 "),
                    ),
                )
            )
        )
        val plan = RoleArchivePlanner.plan(archive, emptyList())

        assertEquals(1, plan.cast.size)
        assertEquals(11L, plan.cast.single().role.id)
        assertEquals(1, plan.skippedCount)
        assertEquals(11L, plan.skipped.single().duplicateOfArchiveId)
    }

    @Test
    fun differentWorkKey_doesNotCollide() {
        val archive = RoleArchive(
            books = listOf(
                book("w1", formal = listOf(RoleArchiveRole(1, "萧炎"))),
                book("w2", formal = listOf(RoleArchiveRole(2, "萧炎"))),
            )
        )
        val plan = RoleArchivePlanner.plan(
            archive,
            listOf(
                RoleArchivePlanner.ExistingRole(
                    "w1",
                    RoleArchiveTargetType.CHARACTER,
                    "萧炎",
                    5L,
                )
            ),
        )

        assertEquals(1, plan.formal.size)
        assertEquals("w2", plan.formal.single().book.workKey)
        assertEquals(1, plan.skippedCount)
    }

    @Test
    fun bindingIdRemap_handlesFormalAndCastRoles() {
        val plan = RoleArchivePlanner.plan(
            RoleArchive(
                books = listOf(
                    book(
                        "w",
                        formal = listOf(RoleArchiveRole(1, "萧炎")),
                        cast = listOf(RoleArchiveCastRole(11, "萧薰儿")),
                        bindings = listOf(
                            RoleArchiveBinding("character", 1, "e", "v1"),
                            RoleArchiveBinding("cast_role", 11, "e", "v2"),
                        ),
                    )
                )
            ),
            emptyList(),
        )

        val formalIds = mapOf(1L to 1001L)
        val castIds = mapOf(11L to 2001L)

        assertEquals(
            1001L,
            RoleArchiveIdMapper.resolve(
                plan.bindings[0].binding.targetType,
                plan.bindings[0].binding.targetId,
                formalIds,
                castIds,
            ),
        )
        assertEquals(
            2001L,
            RoleArchiveIdMapper.resolve(
                plan.bindings[1].binding.targetType,
                plan.bindings[1].binding.targetId,
                formalIds,
                castIds,
            ),
        )
        assertEquals(
            null,
            RoleArchiveIdMapper.resolve("narrator", 1L, formalIds, castIds),
        )
    }
}
