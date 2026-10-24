package io.legado.app.help.roles

import androidx.room.withTransaction
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookRole
import io.legado.app.data.entities.BookTtsCastRole
import io.legado.app.data.entities.BookTtsVoiceBinding
import io.legado.app.help.tts.BookTtsCastingCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 批量改性别/启停/转正/删除；全部在事务内执行。 */
object RoleBatchApplier {

    suspend fun updateGender(items: List<RoleBatchPlanner.Item>, gender: String): Int =
        withContext(Dispatchers.IO) {
            val targets = RoleBatchPlanner.genderTargets(items)
            if (targets.isEmpty()) return@withContext 0
            val now = System.currentTimeMillis()
            var affected = 0
            appDb.withTransaction {
                val dao = appDb.bookRoleDao
                targets.forEach { item ->
                    val role = dao.getRole(item.targetId) ?: return@forEach
                    dao.updateRole(role.copy(gender = gender, updatedAt = now))
                    affected++
                }
            }
            affected
        }

    suspend fun updateEnabled(items: List<RoleBatchPlanner.Item>, enabled: Boolean): Int =
        withContext(Dispatchers.IO) {
            val targets = RoleBatchPlanner.enabledTargets(items)
            if (targets.isEmpty()) return@withContext 0
            val now = System.currentTimeMillis()
            var affected = 0
            appDb.withTransaction {
                val dao = appDb.bookRoleDao
                targets.forEach { item ->
                    val role = dao.getRole(item.targetId) ?: return@forEach
                    dao.updateRole(role.copy(enabled = enabled, updatedAt = now))
                    affected++
                }
            }
            affected
        }

    suspend fun promoteCast(items: List<RoleBatchPlanner.Item>): Int =
        withContext(Dispatchers.IO) {
            val targets = RoleBatchPlanner.promoteTargets(items)
            if (targets.isEmpty()) return@withContext 0
            val now = System.currentTimeMillis()
            var affected = 0
            appDb.withTransaction {
                val dao = appDb.bookRoleDao
                targets.forEach { item ->
                    val castRole = dao.getCastRole(item.targetId) ?: return@forEach
                    val existing = dao.getRoles(item.workKey).firstOrNull {
                        BookTtsCastingCoordinator.normalizeIdentityName(it.name) ==
                            BookTtsCastingCoordinator.normalizeIdentityName(castRole.name)
                    }
                    val roleId = existing?.roleId ?: dao.insertRole(
                        BookRole(
                            workKey = item.workKey,
                            name = castRole.name,
                            aliasesJson = castRole.aliasesJson,
                            gender = castRole.gender,
                            enabled = true,
                            updatedAt = now,
                        )
                    )
                    if (existing != null) {
                        val merged = (
                            RoleArchiveCodec.aliasesFromJson(existing.aliasesJson) +
                                castRole.name +
                                RoleArchiveCodec.aliasesFromJson(castRole.aliasesJson)
                            )
                            .filter {
                                it.isNotBlank() &&
                                    BookTtsCastingCoordinator.normalizeIdentityName(it) !=
                                    BookTtsCastingCoordinator.normalizeIdentityName(existing.name)
                            }
                            .distinctBy {
                                BookTtsCastingCoordinator.normalizeIdentityName(it)
                            }
                        dao.updateRole(
                            existing.copy(
                                aliasesJson = RoleArchiveCodec.aliasesToJson(merged),
                                updatedAt = now,
                            )
                        )
                    }
                    dao.updateCastRole(
                        castRole.copy(
                            ignored = false,
                            linkedRoleId = roleId,
                            identityState = BookTtsCastRole.IdentityState.STABLE,
                            updatedAt = now,
                        )
                    )
                    dao.getBinding(
                        item.workKey,
                        BookTtsVoiceBinding.TargetType.CAST_ROLE,
                        castRole.castRoleId,
                    )?.let { binding ->
                        dao.deleteBinding(
                            item.workKey,
                            BookTtsVoiceBinding.TargetType.CAST_ROLE,
                            castRole.castRoleId,
                        )
                        dao.insertBinding(
                            binding.copy(
                                targetType = BookTtsVoiceBinding.TargetType.CHARACTER,
                                targetId = roleId,
                                updatedAt = now,
                            )
                        )
                    }
                    affected++
                }
            }
            affected
        }

    suspend fun delete(items: List<RoleBatchPlanner.Item>): Int =
        withContext(Dispatchers.IO) {
            val targets = RoleBatchPlanner.deleteTargets(items)
            if (targets.isEmpty()) return@withContext 0
            val now = System.currentTimeMillis()
            var affected = 0
            appDb.withTransaction {
                val dao = appDb.bookRoleDao
                targets.forEach { item ->
                    when (item.targetType) {
                        RoleArchiveTargetType.CHARACTER -> {
                            val role = dao.getRole(item.targetId) ?: return@forEach
                            dao.deleteRole(role)
                            dao.deleteBinding(
                                item.workKey,
                                BookTtsVoiceBinding.TargetType.CHARACTER,
                                item.targetId,
                            )
                            dao.getCastRoles(item.workKey)
                                .filter { it.linkedRoleId == item.targetId }
                                .forEach {
                                    dao.updateCastRole(
                                        it.copy(linkedRoleId = 0L, updatedAt = now)
                                    )
                                }
                        }

                        RoleArchiveTargetType.CAST_ROLE -> {
                            dao.getCastRole(item.targetId)?.let { dao.deleteCastRole(it) }
                            dao.deleteBinding(
                                item.workKey,
                                BookTtsVoiceBinding.TargetType.CAST_ROLE,
                                item.targetId,
                            )
                        }
                    }
                    affected++
                }
            }
            affected
        }
}
