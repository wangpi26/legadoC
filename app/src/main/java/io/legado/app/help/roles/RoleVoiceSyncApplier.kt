package io.legado.app.help.roles

import androidx.room.withTransaction
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTtsVoiceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把源角色的音色同步到跨书同名正式角色。
 *
 * 默认跳过已有绑定；overwrite=true 时覆盖。写入的绑定固定为 manual，
 * 避免被 AI 自动选音覆盖。源角色没有可用绑定或没有任何候选时给出明确结果。
 */
object RoleVoiceSyncApplier {

    data class Counts(
        val added: Int,
        val overwritten: Int,
        val skipped: Int,
    )

    data class Action(
        val candidate: RoleVoiceSyncPlanner.Candidate,
        val overwrite: Boolean,
    )

    sealed interface Decision {
        data class Ready(
            val actions: List<Action>,
            val counts: Counts,
        ) : Decision

        data class Failure(val reason: String) : Decision
    }

    sealed interface Outcome {
        data class Success(val counts: Counts) : Outcome
        data class Failure(val reason: String) : Outcome
    }

    fun decide(
        plan: RoleVoiceSyncPlanner.Plan,
        overwrite: Boolean,
    ): Decision {
        if (plan.source.targetType != RoleArchiveTargetType.CHARACTER) {
            return Decision.Failure("仅支持正式角色的跨书音色同步")
        }
        val binding = plan.sourceBinding
            ?: return Decision.Failure("源角色没有可同步的音色绑定")
        if (binding.speakerId.isBlank()) {
            return Decision.Failure("源角色的音色绑定缺少发音人")
        }
        val actions = mutableListOf<Action>()
        var added = 0
        var overwritten = 0
        var skipped = 0
        plan.candidates.forEach { candidate ->
            when {
                !candidate.hasExistingBinding -> {
                    added++
                    actions += Action(candidate, overwrite = false)
                }

                overwrite -> {
                    overwritten++
                    actions += Action(candidate, overwrite = true)
                }

                else -> skipped++
            }
        }
        return Decision.Ready(actions, Counts(added, overwritten, skipped))
    }

    suspend fun apply(
        plan: RoleVoiceSyncPlanner.Plan,
        overwrite: Boolean,
    ): Outcome = withContext(Dispatchers.IO) {
        when (val decision = decide(plan, overwrite)) {
            is Decision.Failure -> Outcome.Failure(decision.reason)
            is Decision.Ready -> runCatching {
                val binding = plan.sourceBinding
                    ?: error("源角色没有可同步的音色绑定")
                val now = System.currentTimeMillis()
                appDb.withTransaction {
                    decision.actions.forEach { action ->
                        appDb.bookRoleDao.insertBinding(
                            BookTtsVoiceBinding(
                                workKey = action.candidate.workKey,
                                targetType = RoleArchiveTargetType.CHARACTER,
                                targetId = action.candidate.roleId,
                                engineId = binding.engineId,
                                speakerId = binding.speakerId,
                                bindingMode = BookTtsVoiceBinding.BindingMode.MANUAL,
                                updatedAt = now,
                            )
                        )
                    }
                }
                Outcome.Success(decision.counts)
            }.getOrElse {
                Outcome.Failure("同步失败: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }
}
