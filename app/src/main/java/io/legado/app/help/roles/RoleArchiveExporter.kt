package io.legado.app.help.roles

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTtsVoiceBinding
import io.legado.app.help.tts.BookTtsAutomationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 从 Room 汇总跨书角色归档；不包含旁白/男女对白等全局音色绑定。 */
object RoleArchiveExporter {

    suspend fun build(): RoleArchive = withContext(Dispatchers.IO) {
        val dao = appDb.bookRoleDao
        val formal = dao.getAllBookRolesIncludingDisabledGlobal()
        val cast = dao.getAllCastRolesGlobal()
        val bindings = dao.getAllBindingsGlobal().filter {
            RoleArchiveTargetType.isRoleTarget(it.targetType)
        }
        val books = appDb.bookDao.all.associateBy {
            BookTtsAutomationConfig.workKeyOf(it.name, it.author)
        }
        val workKeys = (formal.map { it.workKey } + cast.map { it.workKey } +
            bindings.map { it.workKey }).distinct().sorted()
        val archiveBooks = workKeys.map { workKey ->
            val book = books[workKey]
            RoleArchiveBook(
                workKey = workKey,
                bookName = book?.name.orEmpty(),
                bookAuthor = book?.author.orEmpty(),
                formalRoles = formal.filter { it.workKey == workKey }
                    .sortedBy { it.roleId }
                    .map {
                        RoleArchiveRole(
                            id = it.roleId,
                            name = it.name,
                            aliases = RoleArchiveCodec.aliasesFromJson(it.aliasesJson),
                            gender = it.gender,
                            enabled = it.enabled,
                        )
                    },
                castRoles = cast.filter { it.workKey == workKey }
                    .sortedBy { it.castRoleId }
                    .map {
                        RoleArchiveCastRole(
                            id = it.castRoleId,
                            name = it.name,
                            aliases = RoleArchiveCodec.aliasesFromJson(it.aliasesJson),
                            gender = it.gender,
                            identityState = it.identityState,
                            occurrenceCount = it.occurrenceCount,
                            ignored = it.ignored,
                            linkedRoleId = it.linkedRoleId,
                        )
                    },
                bindings = bindings.filter { it.workKey == workKey }
                    .sortedWith(compareBy({ it.targetType }, { it.targetId }))
                    .map {
                        RoleArchiveBinding(
                            targetType = it.targetType,
                            targetId = it.targetId,
                            engineId = it.engineId,
                            speakerId = it.speakerId,
                            bindingMode = it.bindingMode,
                        )
                    },
            )
        }
        RoleArchive(
            version = RoleArchiveCodec.CURRENT_VERSION,
            exportedAt = System.currentTimeMillis(),
            books = archiveBooks,
        )
    }
}
