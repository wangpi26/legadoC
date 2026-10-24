package io.legado.app.help.roles

import androidx.room.withTransaction
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookRole
import io.legado.app.data.entities.BookTtsCastRole
import io.legado.app.data.entities.BookTtsVoiceBinding
import io.legado.app.help.roles.RoleArchivePlanner.ExistingRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把归档写入 Room。
 *
 * - 所有角色都重建 roleId/castRoleId，绝不沿用归档旧 id；
 * - 音色绑定按归档旧 id 映射到新 id；
 * - 同 workKey/同类型/同名角色跳过，其绑定尽量落到命中的已有角色；
 * - 整个导入在一个事务内完成，失败回滚。
 */
object RoleArchiveImporter {

    data class Result(
        val newRoles: Int,
        val skipped: Int,
        val bindings: Int,
    )

    suspend fun import(archive: RoleArchive): Result = withContext(Dispatchers.IO) {
        val dao = appDb.bookRoleDao
        val existing = buildList {
            dao.getAllBookRolesIncludingDisabledGlobal().forEach {
                add(
                    ExistingRole(
                        it.workKey,
                        RoleArchiveTargetType.CHARACTER,
                        it.name,
                        it.roleId,
                    )
                )
            }
            dao.getAllCastRolesGlobal().forEach {
                add(
                    ExistingRole(
                        it.workKey,
                        RoleArchiveTargetType.CAST_ROLE,
                        it.name,
                        it.castRoleId,
                    )
                )
            }
        }
        val plan = RoleArchivePlanner.plan(archive, existing)
        val now = System.currentTimeMillis()
        appDb.withTransaction {
            val formalIds = mutableMapOf<Long, Long>()
            val castIds = mutableMapOf<Long, Long>()
            plan.skipped.forEach {
                if (it.existingId > 0L) {
                    when (it.targetType) {
                        RoleArchiveTargetType.CHARACTER -> formalIds[it.archiveId] = it.existingId
                        RoleArchiveTargetType.CAST_ROLE -> castIds[it.archiveId] = it.existingId
                    }
                }
            }
            var newRoles = 0
            fun resolveDuplicateSkipped(targetType: String, ids: MutableMap<Long, Long>) {
                plan.skipped.filter {
                    it.targetType == targetType && it.duplicateOfArchiveId > 0L
                }.forEach {
                    ids[it.duplicateOfArchiveId]?.let { mapped -> ids[it.archiveId] = mapped }
                }
            }
            plan.formal.forEach { planned ->
                val role = planned.role
                val newId = dao.insertRole(
                    BookRole(
                        workKey = planned.book.workKey,
                        name = role.name,
                        aliasesJson = RoleArchiveCodec.aliasesToJson(role.aliases),
                        gender = role.gender.takeIf { it in VALID_GENDERS } ?: "unknown",
                        enabled = role.enabled,
                        updatedAt = now,
                    )
                )
                formalIds[role.id] = newId
                newRoles++
            }
            resolveDuplicateSkipped(RoleArchiveTargetType.CHARACTER, formalIds)
            plan.cast.forEach { planned ->
                val role = planned.role
                val newId = dao.insertCastRole(
                    BookTtsCastRole(
                        workKey = planned.book.workKey,
                        name = role.name,
                        aliasesJson = RoleArchiveCodec.aliasesToJson(role.aliases),
                        gender = role.gender.takeIf { it in VALID_GENDERS } ?: "unknown",
                        identityState = role.identityState.takeIf { it in VALID_STATES }
                            ?: BookTtsCastRole.IdentityState.STABLE,
                        occurrenceCount = role.occurrenceCount,
                        firstChapterIndex = -1,
                        lastChapterIndex = -1,
                        samplesJson = "[]",
                        evidence = "",
                        ignored = role.ignored,
                        linkedRoleId = formalIds[role.linkedRoleId] ?: 0L,
                        updatedAt = now,
                    )
                )
                castIds[role.id] = newId
                newRoles++
            }
            // 归档内部同名被跳过的角色：绑定/linkedRoleId 指向同一本书内第一个被接收的角色。
            resolveDuplicateSkipped(RoleArchiveTargetType.CAST_ROLE, castIds)
            var bindings = 0
            plan.bindings.forEach { planned ->
                val binding = planned.binding
                if (binding.speakerId.isBlank()) return@forEach
                val targetId = RoleArchiveIdMapper.resolve(
                    binding.targetType,
                    binding.targetId,
                    formalIds,
                    castIds,
                ) ?: return@forEach
                dao.insertBinding(
                    BookTtsVoiceBinding(
                        workKey = planned.book.workKey,
                        targetType = binding.targetType,
                        targetId = targetId,
                        engineId = binding.engineId,
                        speakerId = binding.speakerId,
                        bindingMode = binding.bindingMode.takeIf { it in VALID_BINDING_MODES }
                            ?: BookTtsVoiceBinding.BindingMode.AUTO,
                        updatedAt = now,
                    )
                )
                bindings++
            }
            Result(newRoles, plan.skippedCount, bindings)
        }
    }

    private val VALID_GENDERS = setOf(
        BookRole.Gender.MALE,
        BookRole.Gender.FEMALE,
        BookRole.Gender.UNKNOWN,
    )
    private val VALID_STATES = setOf(
        BookTtsCastRole.IdentityState.STABLE,
        BookTtsCastRole.IdentityState.PENDING,
        BookTtsCastRole.IdentityState.GUEST,
    )
    private val VALID_BINDING_MODES = setOf(
        BookTtsVoiceBinding.BindingMode.AUTO,
        BookTtsVoiceBinding.BindingMode.MANUAL,
    )
}
