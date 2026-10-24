package io.legado.app.help.roles

import org.junit.Assert.assertEquals
import org.junit.Test

class RoleBindingBatchApplierTest {

    private fun target(
        id: Long,
        workKey: String = "w",
        targetType: String = RoleArchiveTargetType.CHARACTER,
    ) = RoleBindingBatchApplier.Target(workKey, targetType, id)

    @Test
    fun duplicateTargetIsAppliedOnceAndCountedAsSkipped() {
        val plan = RoleBindingBatchApplier.plan(
            targets = listOf(target(1L), target(1L), target(2L)),
            existingTargets = emptySet(),
        )

        assertEquals(2, plan.targets.size)
        assertEquals(2, plan.counts.added)
        assertEquals(0, plan.counts.overwritten)
        assertEquals(1, plan.counts.skipped)
    }

    @Test
    fun existingTargetIsCountedAsOverwritten() {
        val plan = RoleBindingBatchApplier.plan(
            targets = listOf(target(1L), target(2L)),
            existingTargets = setOf(target(1L)),
        )

        assertEquals(1, plan.counts.added)
        assertEquals(1, plan.counts.overwritten)
        assertEquals(0, plan.counts.skipped)
    }

    @Test
    fun invalidTargetIsSkipped() {
        val plan = RoleBindingBatchApplier.plan(
            targets = listOf(target(0L), target(1L, targetType = "narrator")),
            existingTargets = emptySet(),
        )

        assertEquals(0, plan.targets.size)
        assertEquals(2, plan.counts.skipped)
    }
}
