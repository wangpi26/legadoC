package io.legado.app.help.roles

import androidx.room.withTransaction
import io.legado.app.data.appDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 执行一次撤销; 所有删除/恢复在一个 Room 事务内, 失败整体回滚. */
object RoleImportUndo {

    data class Result(
        val deletedRoles: Int,
        val restoredRoles: Int,
        val restoredBindings: Int,
    )

    suspend fun undo(
        journal: RoleImportJournal,
        store: RoleImportJournalStore,
    ): Result = withContext(Dispatchers.IO) {
        val validation = RoleImportUndoPlanner.plan(journal)
        val plan = when (validation) {
            is RoleImportUndoPlanner.Validation.Failure ->
                error(validation.reason)

            is RoleImportUndoPlanner.Validation.Ready -> validation.plan
        }
        val result = appDb.withTransaction {
            val dao = appDb.bookRoleDao
            var deleted = 0
            val bindingKeys = plan.deleteBindings.toMutableSet()
            plan.deleteFormalIds.forEach { id ->
                dao.getRole(id)?.let {
                    bindingKeys += RoleBindingKey(
                        it.workKey,
                        io.legado.app.data.entities.BookTtsVoiceBinding.TargetType.CHARACTER,
                        id,
                    )
                    dao.deleteRole(it)
                    deleted++
                }
            }
            plan.deleteCastIds.forEach { id ->
                dao.getCastRole(id)?.let {
                    bindingKeys += RoleBindingKey(
                        it.workKey,
                        io.legado.app.data.entities.BookTtsVoiceBinding.TargetType.CAST_ROLE,
                        id,
                    )
                    dao.deleteCastRole(it)
                    deleted++
                }
            }
            plan.restoreFormal.forEach { dao.insertRole(it.toEntity()) }
            plan.restoreCast.forEach { dao.insertCastRole(it.toEntity()) }
            bindingKeys.forEach {
                dao.deleteBinding(it.workKey, it.targetType, it.targetId)
            }
            plan.restoreBindings.forEach { dao.insertBinding(it.toEntity()) }
            Result(
                deletedRoles = deleted,
                restoredRoles = plan.restoreFormal.size + plan.restoreCast.size,
                restoredBindings = plan.restoreBindings.size,
            )
        }
        store.clear()
        result
    }
}
