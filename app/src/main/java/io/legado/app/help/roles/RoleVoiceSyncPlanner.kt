package io.legado.app.help.roles

/**
 * 跨书同名角色音色同步的纯匹配逻辑。
 *
 * 只在正式角色（character）之间匹配；同书角色不参与，临时角色不参与。
 * 名称/别名先做规范化：trim、去空白、去「·」/「・」、全角转半角、拉丁忽略大小写。
 */
object RoleVoiceSyncPlanner {

    data class Source(
        val workKey: String,
        val targetType: String,
        val roleId: Long,
        val name: String,
        val aliases: List<String>,
    )

    data class FormalRole(
        val workKey: String,
        val targetType: String,
        val roleId: Long,
        val name: String,
        val aliases: List<String>,
    )

    data class Binding(
        val workKey: String,
        val targetType: String,
        val targetId: Long,
        val engineId: String,
        val speakerId: String,
        val bindingMode: String,
    )

    data class Candidate(
        val workKey: String,
        val roleId: Long,
        val name: String,
        val matchedAliases: List<String>,
        val hasExistingBinding: Boolean,
    )

    data class Plan(
        val source: Source,
        val sourceBinding: Binding?,
        val candidates: List<Candidate>,
    )

    fun plan(
        source: Source,
        allFormalRoles: List<FormalRole>,
        allBindings: List<Binding>,
    ): Plan {
        if (source.targetType != RoleArchiveTargetType.CHARACTER) {
            return Plan(source, null, emptyList())
        }
        val sourceTerms = (source.aliases + source.name)
            .map(::normalize)
            .filter { it.isNotEmpty() }
            .toSet()
        if (sourceTerms.isEmpty()) return Plan(source, null, emptyList())
        val sourceBinding = allBindings.firstOrNull {
            it.targetType == RoleArchiveTargetType.CHARACTER &&
                it.workKey == source.workKey &&
                it.targetId == source.roleId
        }
        val bindingKeys = allBindings
            .asSequence()
            .filter { it.targetType == RoleArchiveTargetType.CHARACTER }
            .map { bindingKey(it.workKey, it.targetId) }
            .toSet()
        val candidates = allFormalRoles.asSequence()
            .filter {
                it.targetType == RoleArchiveTargetType.CHARACTER &&
                    it.workKey != source.workKey
            }
            .mapNotNull { role ->
                val roleTerms = (role.aliases + role.name)
                    .map(::normalize)
                    .filter { it.isNotEmpty() }
                val matched = sourceTerms.intersect(roleTerms.toSet())
                if (matched.isEmpty()) return@mapNotNull null
                Candidate(
                    workKey = role.workKey,
                    roleId = role.roleId,
                    name = role.name,
                    matchedAliases = matched.sorted(),
                    hasExistingBinding = bindingKey(role.workKey, role.roleId) in bindingKeys,
                )
            }
            .distinctBy { bindingKey(it.workKey, it.roleId) }
            .sortedWith(compareBy({ it.workKey }, { it.name }, { it.roleId }))
            .toList()
        return Plan(source, sourceBinding, candidates)
    }

    fun normalize(value: String): String {
        val builder = StringBuilder(value.length)
        value.trim().forEach { char ->
            when {
                char == '·' || char == '・' -> Unit
                char == '\u3000' -> Unit
                char.isWhitespace() -> Unit
                char.code in 0xFF01..0xFF5E ->
                    builder.append((char.code - 0xFEE0).toChar().lowercaseChar())
                else -> builder.append(char.lowercaseChar())
            }
        }
        return builder.toString()
    }

    private fun bindingKey(workKey: String, roleId: Long): String =
        "$workKey|$roleId"
}
