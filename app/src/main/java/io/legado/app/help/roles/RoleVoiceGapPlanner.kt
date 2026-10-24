package io.legado.app.help.roles

/**
 * 角色库「待配音」页签的纯逻辑。
 *
 * 只统计启用中的正式角色；临时角色、停用角色、非角色类型的绑定均不参与。
 * 音色绑定必须是非空 speakerId 才算已配音，空串/空白视为未配音。
 * 已配齐或没有正式角色的书不进入结果；按未配音角色数降序排列。
 */
object RoleVoiceGapPlanner {

    data class FormalRole(
        val workKey: String,
        val roleId: Long,
        val name: String,
        val enabled: Boolean = true,
        val targetType: String = RoleArchiveTargetType.CHARACTER,
    )

    data class Binding(
        val workKey: String,
        val targetType: String = RoleArchiveTargetType.CHARACTER,
        val targetId: Long,
        val speakerId: String = "",
    )

    data class UnboundRole(
        val roleId: Long,
        val name: String,
    )

    data class BookGap(
        val workKey: String,
        val bookTitle: String,
        val totalFormal: Int,
        val boundCount: Int,
        val unboundRoles: List<UnboundRole>,
    ) {
        val unboundCount: Int
            get() = unboundRoles.size
    }

    fun plan(
        roles: List<FormalRole>,
        bindings: List<Binding>,
        bookTitles: Map<String, String>,
    ): List<BookGap> {
        val enabledFormal = roles.filter {
            it.enabled && it.targetType == RoleArchiveTargetType.CHARACTER
        }
        val boundKeys = bindings.asSequence()
            .filter {
                it.targetType == RoleArchiveTargetType.CHARACTER &&
                    it.speakerId.isNotBlank()
            }
            .map { key(it.workKey, it.targetId) }
            .toSet()
        return enabledFormal
            .groupBy { it.workKey }
            .mapNotNull { (workKey, bookRoles) ->
                val unbound = bookRoles
                    .filterNot { key(it.workKey, it.roleId) in boundKeys }
                    .map { UnboundRole(it.roleId, it.name) }
                if (unbound.isEmpty()) return@mapNotNull null
                BookGap(
                    workKey = workKey,
                    bookTitle = bookTitles[workKey]?.takeIf { it.isNotBlank() } ?: workKey,
                    totalFormal = bookRoles.size,
                    boundCount = bookRoles.size - unbound.size,
                    unboundRoles = unbound,
                )
            }
            .sortedWith(
                compareByDescending<BookGap> { it.unboundCount }
                    .thenBy { it.bookTitle }
                    .thenBy { it.workKey }
            )
    }

    private fun key(workKey: String, targetId: Long): String = "$workKey|$targetId"
}
