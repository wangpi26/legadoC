package io.legado.app.help.roles

import androidx.room.withTransaction
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTtsVoiceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 批量应用音色到角色；按 (workKey, targetType, targetId) 去重并覆盖写入。
 * 写入绑定固定 manual，刷新 updatedAt。重复目标只保留一行并计入 skipped。
 */
object RoleBindingBatchApplier {

    data class Target(
        val workKey: String,
        val targetType: String,
        val targetId: Long,
    )

    data class Counts(
        val added: Int,
        val overwritten: Int,
        val skipped: Int,
    )

    data class Plan(
        val targets: List<Target>,
        val counts: Counts,
    )

    fun plan(
        targets: List<Target>,
        existingTargets: Set<Target>,
    ): Plan {
        val unique = LinkedHashMap<String, Target>()
        var skipped = 0
        targets.forEach { target ->
            if (!RoleArchiveTargetType.isRoleTarget(target.targetType) || target.targetId <= 0L) {
                skipped++
                return@forEach
            }
            val key = key(target)
            if (unique.putIfAbsent(key, target) != null) {
                skipped++
            }
        }
        var added = 0
        var overwritten = 0
        unique.values.forEach { target ->
            if (target in existingTargets) {
                overwritten++
            } else {
                added++
            }
        }
        return Plan(unique.values.toList(), Counts(added, overwritten, skipped))
    }

    suspend fun apply(
        targets: List<Target>,
        engineId: String,
        speakerId: String,
    ): Counts = withContext(Dispatchers.IO) {
        val normalized = plan(targets, emptySet())
        if (speakerId.isBlank()) {
            return@withContext normalized.counts.copy(
                added = 0,
                overwritten = 0,
                skipped = targets.size,
            )
        }
        appDb.withTransaction {
            val dao = appDb.bookRoleDao
            val existing = dao.getAllBindingsGlobal()
                .filter { RoleArchiveTargetType.isRoleTarget(it.targetType) }
                .map { Target(it.workKey, it.targetType, it.targetId) }
                .toSet()
            val plan = plan(targets, existing)
            val now = System.currentTimeMillis()
            plan.targets.forEach { target ->
                dao.insertBinding(
                    BookTtsVoiceBinding(
                        workKey = target.workKey,
                        targetType = target.targetType,
                        targetId = target.targetId,
                        engineId = engineId,
                        speakerId = speakerId,
                        bindingMode = BookTtsVoiceBinding.BindingMode.MANUAL,
                        updatedAt = now,
                    )
                )
            }
            plan.counts
        }
    }

    private fun key(target: Target): String =
        "${target.workKey}|${target.targetType}|${target.targetId}"
}
