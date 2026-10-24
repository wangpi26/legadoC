package io.legado.app.help.roles

import androidx.room.withTransaction
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookRole
import io.legado.app.data.entities.BookTtsCastRole
import io.legado.app.data.entities.BookTtsVoiceBinding
import io.legado.app.help.roles.RoleArchivePlanner.ExistingRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 归档预览与导入。
 *
 * preview() 只读库并生成 RoleArchivePlanner.Plan；UI 必须把同一个 Plan 交给 import()，
 * 避免预览后库变化导致二次计算漂移。导入按策略 SKIP/OVERWRITE 执行，并写入撤销 journal。
 */
object RoleArchiveImporter {

    data class BookPreview(
        val workKey: String,
        val bookName: String,
        val newFormal: Int,
        val newCast: Int,
        val conflictFormal: Int,
        val conflictCast: Int,
        val bindings: Int,
        val temporary: Int,
    )

    data class Preview(
        val plan: RoleArchivePlanner.Plan,
        val books: List<BookPreview>,
        val newRoles: Int,
        val newFormal: Int,
        val newCast: Int,
        val conflicts: Int,
        val conflictFormal: Int,
        val conflictCast: Int,
        val bindings: Int,
        val temporaryRoles: Int,
        val skipped: Int,
    )

    data class Result(
        val newRoles: Int,
        val updatedRoles: Int,
        val skipped: Int,
        val bindings: Int,
    )

    suspend fun preview(archive: RoleArchive): Preview = withContext(Dispatchers.IO) {
        buildPreview(RoleArchivePlanner.plan(archive, loadExistingRoles()))
    }

    /** 纯函数，便于 JVM 测试预览计数/分组。 */
    fun buildPreview(plan: RoleArchivePlanner.Plan): Preview {
        val books = LinkedHashMap<String, RoleArchiveBook>()
        plan.formal.forEach { books.putIfAbsent(it.book.workKey, it.book) }
        plan.cast.forEach { books.putIfAbsent(it.book.workKey, it.book) }
        plan.bindings.forEach { books.putIfAbsent(it.book.workKey, it.book) }
        plan.conflicts.forEach { books.putIfAbsent(it.book.workKey, it.book) }
        val bookPreviews = books.values.map { book ->
            BookPreview(
                workKey = book.workKey,
                bookName = book.bookName,
                newFormal = plan.formal.count { it.book.workKey == book.workKey },
                newCast = plan.cast.count { it.book.workKey == book.workKey },
                conflictFormal = plan.conflicts.count {
                    it.book.workKey == book.workKey &&
                        it.targetType == RoleArchiveTargetType.CHARACTER
                },
                conflictCast = plan.conflicts.count {
                    it.book.workKey == book.workKey &&
                        it.targetType == RoleArchiveTargetType.CAST_ROLE
                },
                bindings = plan.bindings.count {
                    it.book.workKey == book.workKey && it.binding.speakerId.isNotBlank()
                },
                temporary = plan.cast.count { it.book.workKey == book.workKey } +
                    plan.conflicts.count {
                        it.book.workKey == book.workKey &&
                            it.targetType == RoleArchiveTargetType.CAST_ROLE
                    },
            )
        }
        val conflictFormal = plan.conflicts.count {
            it.targetType == RoleArchiveTargetType.CHARACTER
        }
        val conflictCast = plan.conflicts.count {
            it.targetType == RoleArchiveTargetType.CAST_ROLE
        }
        return Preview(
            plan = plan,
            books = bookPreviews,
            newRoles = plan.formal.size + plan.cast.size,
            newFormal = plan.formal.size,
            newCast = plan.cast.size,
            conflicts = plan.conflicts.size,
            conflictFormal = conflictFormal,
            conflictCast = conflictCast,
            bindings = plan.bindings.count { it.binding.speakerId.isNotBlank() },
            temporaryRoles = plan.cast.size + conflictCast,
            skipped = plan.skippedCount,
        )
    }

    suspend fun import(
        plan: RoleArchivePlanner.Plan,
        strategy: RoleImportStrategy,
        journalStore: RoleImportJournalStore = RoleImportJournalStore(appCtx),
    ): Result = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val journal = RoleImportJournal(
            createdAt = now,
            strategy = strategy.name,
        )
        val newFormalIds = mutableListOf<Long>()
        val newCastIds = mutableListOf<Long>()
        val overwrittenFormal = mutableListOf<FormalRoleSnapshot>()
        val overwrittenCast = mutableListOf<CastRoleSnapshot>()
        val previousBindings = mutableListOf<BindingSnapshot>()
        val writtenBindings = mutableListOf<BindingSnapshot>()
        var updatedRoles = 0
        var bindings = 0

        appDb.withTransaction {
            val dao = appDb.bookRoleDao
            val formalIds = mutableMapOf<Long, Long>()
            val castIds = mutableMapOf<Long, Long>()
            plan.conflicts.forEach { conflict ->
                when (conflict.targetType) {
                    RoleArchiveTargetType.CHARACTER -> formalIds[conflict.archiveId] = conflict.existingId
                    RoleArchiveTargetType.CAST_ROLE -> castIds[conflict.archiveId] = conflict.existingId
                }
            }
            plan.formal.forEach { planned ->
                val role = planned.role
                val newId = dao.insertRole(
                    BookRole(
                        workKey = planned.book.workKey,
                        name = role.name,
                        aliasesJson = RoleArchiveCodec.aliasesToJson(role.aliases),
                        gender = role.gender.takeIf { it in VALID_GENDERS } ?: BookRole.Gender.UNKNOWN,
                        enabled = role.enabled,
                        updatedAt = now,
                    )
                )
                formalIds[role.id] = newId
                newFormalIds += newId
            }
            plan.cast.forEach { planned ->
                val role = planned.role
                val newId = dao.insertCastRole(
                    BookTtsCastRole(
                        workKey = planned.book.workKey,
                        name = role.name,
                        aliasesJson = RoleArchiveCodec.aliasesToJson(role.aliases),
                        gender = role.gender.takeIf { it in VALID_GENDERS } ?: BookRole.Gender.UNKNOWN,
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
                newCastIds += newId
            }
            // 归档内部重名: 绑定/linkedRoleId 指向同一本书内第一个被接收的角色.
            resolveDuplicateSkipped(RoleArchiveTargetType.CHARACTER, plan, formalIds)
            resolveDuplicateSkipped(RoleArchiveTargetType.CAST_ROLE, plan, castIds)

            if (strategy == RoleImportStrategy.OVERWRITE) {
                plan.conflicts.forEach { conflict ->
                    when (conflict.targetType) {
                        RoleArchiveTargetType.CHARACTER -> {
                            val current = dao.getRole(conflict.existingId) ?: return@forEach
                            overwrittenFormal += FormalRoleSnapshot.from(current)
                            val incoming = conflict.incomingFormal ?: return@forEach
                            dao.updateRole(
                                current.copy(
                                    name = incoming.name,
                                    aliasesJson = RoleArchiveCodec.aliasesToJson(incoming.aliases),
                                    gender = incoming.gender.takeIf { it in VALID_GENDERS }
                                        ?: BookRole.Gender.UNKNOWN,
                                    enabled = incoming.enabled,
                                    updatedAt = now,
                                )
                            )
                            updatedRoles++
                        }

                        RoleArchiveTargetType.CAST_ROLE -> {
                            val current = dao.getCastRole(conflict.existingId) ?: return@forEach
                            overwrittenCast += CastRoleSnapshot.from(current)
                            val incoming = conflict.incomingCast ?: return@forEach
                            dao.updateCastRole(
                                current.copy(
                                    name = incoming.name,
                                    aliasesJson = RoleArchiveCodec.aliasesToJson(incoming.aliases),
                                    gender = incoming.gender.takeIf { it in VALID_GENDERS }
                                        ?: BookRole.Gender.UNKNOWN,
                                    identityState = incoming.identityState.takeIf { it in VALID_STATES }
                                        ?: BookTtsCastRole.IdentityState.STABLE,
                                    occurrenceCount = incoming.occurrenceCount,
                                    ignored = incoming.ignored,
                                    linkedRoleId = formalIds[incoming.linkedRoleId]
                                        ?: current.linkedRoleId,
                                    updatedAt = now,
                                )
                            )
                            updatedRoles++
                        }
                    }
                }
            }

            val conflictTargetKeys = plan.conflicts.map {
                BindingSnapshot(
                    workKey = it.book.workKey,
                    targetType = it.targetType,
                    targetId = it.existingId,
                ).key()
            }.toSet()
            if (strategy == RoleImportStrategy.OVERWRITE) {
                // 覆盖策略下冲突角色音色整体替换; 没归档绑定时也清空旧绑定.
                conflictTargetKeys.forEach { key ->
                    dao.getBinding(key.workKey, key.targetType, key.targetId)?.let {
                        previousBindings += BindingSnapshot.from(it)
                        dao.deleteBinding(key.workKey, key.targetType, key.targetId)
                    }
                }
            }
            plan.bindings.forEach { planned ->
                val binding = planned.binding
                if (binding.speakerId.isBlank()) return@forEach
                val targetId = RoleArchiveIdMapper.resolve(
                    binding.targetType,
                    binding.targetId,
                    formalIds,
                    castIds,
                ) ?: return@forEach
                val targetKey = RoleBindingKey(
                    planned.book.workKey,
                    binding.targetType,
                    targetId,
                )
                if (strategy == RoleImportStrategy.SKIP && targetKey in conflictTargetKeys) {
                    return@forEach
                }
                val snapshot = BindingSnapshot(
                    workKey = planned.book.workKey,
                    targetType = binding.targetType,
                    targetId = targetId,
                    engineId = binding.engineId,
                    speakerId = binding.speakerId,
                    bindingMode = binding.bindingMode.takeIf { it in VALID_BINDING_MODES }
                        ?: BookTtsVoiceBinding.BindingMode.AUTO,
                    updatedAt = now,
                )
                dao.insertBinding(snapshot.toEntity())
                writtenBindings += snapshot
                bindings++
            }
        }

        val hasChanges = newFormalIds.isNotEmpty() ||
            newCastIds.isNotEmpty() ||
            overwrittenFormal.isNotEmpty() ||
            overwrittenCast.isNotEmpty() ||
            writtenBindings.isNotEmpty() ||
            previousBindings.isNotEmpty()
        if (hasChanges) {
            journalStore.save(
                journal.copy(
                    newFormalIds = newFormalIds,
                    newCastIds = newCastIds,
                    overwrittenFormal = overwrittenFormal,
                    overwrittenCast = overwrittenCast,
                    previousBindings = previousBindings,
                    writtenBindings = writtenBindings,
                )
            )
        } else {
            journalStore.clear()
        }
        Result(
            newRoles = newFormalIds.size + newCastIds.size,
            updatedRoles = updatedRoles,
            skipped = plan.skippedCount,
            bindings = bindings,
        )
    }

    private fun loadExistingRoles(): List<ExistingRole> {
        val dao = appDb.bookRoleDao
        return buildList {
            dao.getAllBookRolesIncludingDisabledGlobal().forEach {
                add(
                    ExistingRole(
                        workKey = it.workKey,
                        targetType = RoleArchiveTargetType.CHARACTER,
                        name = it.name,
                        id = it.roleId,
                        aliasesJson = it.aliasesJson,
                        gender = it.gender,
                        enabled = it.enabled,
                    )
                )
            }
            dao.getAllCastRolesGlobal().forEach {
                add(
                    ExistingRole(
                        workKey = it.workKey,
                        targetType = RoleArchiveTargetType.CAST_ROLE,
                        name = it.name,
                        id = it.castRoleId,
                        aliasesJson = it.aliasesJson,
                        gender = it.gender,
                        enabled = !it.ignored,
                        identityState = it.identityState,
                    )
                )
            }
        }
    }

    private fun resolveDuplicateSkipped(
        targetType: String,
        plan: RoleArchivePlanner.Plan,
        ids: MutableMap<Long, Long>,
    ) {
        plan.skipped.filter {
            it.targetType == targetType && it.duplicateOfArchiveId > 0L
        }.forEach {
            ids[it.duplicateOfArchiveId]?.let { mapped -> ids[it.archiveId] = mapped }
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
