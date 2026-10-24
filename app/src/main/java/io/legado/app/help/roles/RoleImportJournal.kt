package io.legado.app.help.roles

import android.content.Context
import com.google.gson.GsonBuilder
import io.legado.app.data.entities.BookRole
import io.legado.app.data.entities.BookTtsCastRole
import io.legado.app.data.entities.BookTtsVoiceBinding
import java.io.File

data class FormalRoleSnapshot(
    val roleId: Long = 0L,
    val workKey: String = "",
    val name: String = "",
    val aliasesJson: String = "[]",
    val gender: String = BookRole.Gender.UNKNOWN,
    val enabled: Boolean = true,
    val updatedAt: Long = 0L,
) {
    fun toEntity() = BookRole(roleId, workKey, name, aliasesJson, gender, enabled, updatedAt)

    companion object {
        fun from(role: BookRole) = FormalRoleSnapshot(
            role.roleId,
            role.workKey,
            role.name,
            role.aliasesJson,
            role.gender,
            role.enabled,
            role.updatedAt,
        )
    }
}

data class CastRoleSnapshot(
    val castRoleId: Long = 0L,
    val workKey: String = "",
    val name: String = "",
    val aliasesJson: String = "[]",
    val gender: String = BookRole.Gender.UNKNOWN,
    val identityState: String = BookTtsCastRole.IdentityState.STABLE,
    val occurrenceCount: Int = 0,
    val firstChapterIndex: Int = -1,
    val lastChapterIndex: Int = -1,
    val samplesJson: String = "[]",
    val evidence: String = "",
    val ignored: Boolean = false,
    val linkedRoleId: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun toEntity() = BookTtsCastRole(
        castRoleId,
        workKey,
        name,
        aliasesJson,
        gender,
        identityState,
        occurrenceCount,
        firstChapterIndex,
        lastChapterIndex,
        samplesJson,
        evidence,
        ignored,
        linkedRoleId,
        updatedAt,
    )

    companion object {
        fun from(role: BookTtsCastRole) = CastRoleSnapshot(
            role.castRoleId,
            role.workKey,
            role.name,
            role.aliasesJson,
            role.gender,
            role.identityState,
            role.occurrenceCount,
            role.firstChapterIndex,
            role.lastChapterIndex,
            role.samplesJson,
            role.evidence,
            role.ignored,
            role.linkedRoleId,
            role.updatedAt,
        )
    }
}

data class BindingSnapshot(
    val workKey: String = "",
    val targetType: String = "",
    val targetId: Long = 0L,
    val engineId: String = "",
    val speakerId: String = "",
    val bindingMode: String = BookTtsVoiceBinding.BindingMode.AUTO,
    val updatedAt: Long = 0L,
) {
    fun toEntity() = BookTtsVoiceBinding(
        workKey,
        targetType,
        targetId,
        engineId,
        speakerId,
        bindingMode,
        updatedAt,
    )

    fun key() = RoleBindingKey(workKey, targetType, targetId)

    companion object {
        fun from(binding: BookTtsVoiceBinding) = BindingSnapshot(
            binding.workKey,
            binding.targetType,
            binding.targetId,
            binding.engineId,
            binding.speakerId,
            binding.bindingMode,
            binding.updatedAt,
        )
    }
}

data class RoleBindingKey(
    val workKey: String,
    val targetType: String,
    val targetId: Long,
)

data class RoleImportJournal(
    val version: Int = CURRENT_VERSION,
    val createdAt: Long = 0L,
    val strategy: String = RoleImportStrategy.SKIP.name,
    val newFormalIds: List<Long> = emptyList(),
    val newCastIds: List<Long> = emptyList(),
    val overwrittenFormal: List<FormalRoleSnapshot> = emptyList(),
    val overwrittenCast: List<CastRoleSnapshot> = emptyList(),
    val previousBindings: List<BindingSnapshot> = emptyList(),
    val writtenBindings: List<BindingSnapshot> = emptyList(),
) {
    companion object {
        const val CURRENT_VERSION = 1
        const val FILE_NAME = "role-import-journal.json"
    }
}

enum class RoleImportStrategy {
    SKIP,
    OVERWRITE,
}

/** 撤销记录编解码; 坏 JSON、错误版本都返回 failure, 不会生成可执行计划。 */
object RoleImportJournalCodec {

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .create()

    fun encode(journal: RoleImportJournal): String = gson.toJson(journal)

    fun decode(raw: String): Result<RoleImportJournal> = runCatching {
        if (raw.isBlank()) error("撤销记录为空")
        val journal = gson.fromJson(raw, RoleImportJournal::class.java)
            ?: error("撤销记录格式无效")
        if (journal.version != RoleImportJournal.CURRENT_VERSION) {
            error("撤销记录版本不匹配: ${journal.version}")
        }
        journal.copy(
            newFormalIds = journal.newFormalIds.orEmpty(),
            newCastIds = journal.newCastIds.orEmpty(),
            overwrittenFormal = journal.overwrittenFormal.orEmpty(),
            overwrittenCast = journal.overwrittenCast.orEmpty(),
            previousBindings = journal.previousBindings.orEmpty(),
            writtenBindings = journal.writtenBindings.orEmpty(),
        )
    }
}

enum class JournalStatus {
    NONE,
    READY,
    INVALID,
}

data class JournalState(
    val status: JournalStatus,
    val message: String = "",
)

class RoleImportJournalStore(context: Context) {

    private val file = File(context.filesDir, RoleImportJournal.FILE_NAME)

    fun load(): RoleImportJournal? {
        if (!file.exists()) return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        return RoleImportJournalCodec.decode(raw).getOrNull()
    }

    fun state(): JournalState {
        if (!file.exists()) return JournalState(JournalStatus.NONE)
        val journal = RoleImportJournalCodec.decode(runCatching { file.readText() }.getOrElse {
            return JournalState(JournalStatus.INVALID, "上次导入记录读取失败")
        }).getOrElse {
            return JournalState(JournalStatus.INVALID, it.message ?: "上次导入记录损坏")
        }
        return when (val validation = RoleImportUndoPlanner.plan(journal)) {
            is RoleImportUndoPlanner.Validation.Failure ->
                JournalState(JournalStatus.INVALID, validation.reason)

            is RoleImportUndoPlanner.Validation.Ready -> JournalState(JournalStatus.READY)
        }
    }

    fun canUndo(): Boolean = state().status == JournalStatus.READY

    fun save(journal: RoleImportJournal) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(RoleImportJournalCodec.encode(journal))
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }
}

/** 纯撤销计划: 新建角色删除、覆盖角色恢复、绑定删除与恢复一一对应。 */
object RoleImportUndoPlanner {

    data class UndoPlan(
        val deleteFormalIds: List<Long>,
        val deleteCastIds: List<Long>,
        val restoreFormal: List<FormalRoleSnapshot>,
        val restoreCast: List<CastRoleSnapshot>,
        val deleteBindings: List<RoleBindingKey>,
        val restoreBindings: List<BindingSnapshot>,
    )

    sealed interface Validation {
        data class Ready(val plan: UndoPlan) : Validation
        data class Failure(val reason: String) : Validation
    }

    fun plan(journal: RoleImportJournal): Validation {
        if (journal.version != RoleImportJournal.CURRENT_VERSION) {
            return Validation.Failure("撤销记录版本不匹配")
        }
        if (journal.newFormalIds.isEmpty() &&
            journal.newCastIds.isEmpty() &&
            journal.overwrittenFormal.isEmpty() &&
            journal.overwrittenCast.isEmpty() &&
            journal.writtenBindings.isEmpty() &&
            journal.previousBindings.isEmpty()
        ) {
            return Validation.Failure("撤销记录为空")
        }
        val writtenKeys = journal.writtenBindings.map { it.key() }
        val previousKeys = journal.previousBindings.map { it.key() }
        return Validation.Ready(
            UndoPlan(
                deleteFormalIds = journal.newFormalIds.distinct(),
                deleteCastIds = journal.newCastIds.distinct(),
                restoreFormal = journal.overwrittenFormal.distinctBy { it.roleId },
                restoreCast = journal.overwrittenCast.distinctBy { it.castRoleId },
                deleteBindings = (writtenKeys + previousKeys).distinct(),
                restoreBindings = journal.previousBindings.distinctBy { it.key() },
            )
        )
    }
}
