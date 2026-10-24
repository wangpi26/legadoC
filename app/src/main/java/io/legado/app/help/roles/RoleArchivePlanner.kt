package io.legado.app.help.roles

/**
 * 导入计划：只做纯逻辑判断，不访问数据库。
 *
 * 默认合并策略：同一 workKey 下同类型（正式/临时）且归一化名称相同的角色跳过；
 * 归档内部同名角色同样只保留第一个，其余计入 skipped。绑定不在此处丢弃，
 * 由导入器按归档旧 id 重映射到新角色 id 或命中的已有角色 id。
 */
object RoleArchivePlanner {

    data class ExistingRole(
        val workKey: String,
        val targetType: String,
        val name: String,
        val id: Long,
    )

    data class PlannedFormal(
        val book: RoleArchiveBook,
        val role: RoleArchiveRole,
    )

    data class PlannedCast(
        val book: RoleArchiveBook,
        val role: RoleArchiveCastRole,
    )

    data class PlannedBinding(
        val book: RoleArchiveBook,
        val binding: RoleArchiveBinding,
    )

    data class SkippedRole(
        val workKey: String,
        val targetType: String,
        val archiveId: Long,
        val name: String,
        val existingId: Long = 0L,
        val duplicateOfArchiveId: Long = 0L,
    )

    data class Plan(
        val formal: List<PlannedFormal>,
        val cast: List<PlannedCast>,
        val bindings: List<PlannedBinding>,
        val skipped: List<SkippedRole>,
    ) {
        val skippedCount: Int get() = skipped.size
    }

    fun plan(
        archive: RoleArchive,
        existingRoles: List<ExistingRole>,
    ): Plan {
        val existing = existingRoles.groupBy {
            key(it.workKey, it.targetType, normalizeName(it.name))
        }
        val acceptedFormal = mutableMapOf<String, Long>()
        val acceptedCast = mutableMapOf<String, Long>()
        val formal = mutableListOf<PlannedFormal>()
        val cast = mutableListOf<PlannedCast>()
        val skipped = mutableListOf<SkippedRole>()

        archive.books.forEach { book ->
            book.formalRoles.forEach { role ->
                val key = key(book.workKey, RoleArchiveTargetType.CHARACTER, normalizeName(role.name))
                val matched = existing[key]?.firstOrNull()
                val duplicate = acceptedFormal[key]
                when {
                    matched != null -> skipped += SkippedRole(
                        book.workKey,
                        RoleArchiveTargetType.CHARACTER,
                        role.id,
                        role.name,
                        existingId = matched.id,
                    )

                    duplicate != null -> skipped += SkippedRole(
                        book.workKey,
                        RoleArchiveTargetType.CHARACTER,
                        role.id,
                        role.name,
                        duplicateOfArchiveId = duplicate,
                    )

                    else -> {
                        acceptedFormal[key] = role.id
                        formal += PlannedFormal(book, role)
                    }
                }
            }
            book.castRoles.forEach { role ->
                val key = key(book.workKey, RoleArchiveTargetType.CAST_ROLE, normalizeName(role.name))
                val matched = existing[key]?.firstOrNull()
                val duplicate = acceptedCast[key]
                when {
                    matched != null -> skipped += SkippedRole(
                        book.workKey,
                        RoleArchiveTargetType.CAST_ROLE,
                        role.id,
                        role.name,
                        existingId = matched.id,
                    )

                    duplicate != null -> skipped += SkippedRole(
                        book.workKey,
                        RoleArchiveTargetType.CAST_ROLE,
                        role.id,
                        role.name,
                        duplicateOfArchiveId = duplicate,
                    )

                    else -> {
                        acceptedCast[key] = role.id
                        cast += PlannedCast(book, role)
                    }
                }
            }
        }
        val bindings = archive.books.flatMap { book ->
            book.bindings.map { PlannedBinding(book, it) }
        }
        return Plan(formal, cast, bindings, skipped)
    }

    fun normalizeName(name: String): String {
        val boundary = setOf(
            '“', '”', '‘', '’', '「', '」', '『', '』',
            ':', '：', '，', ',', '。', '.', '！', '!', '？', '?',
        )
        return name.trim { it.isWhitespace() || it in boundary }
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    private fun key(workKey: String, targetType: String, name: String): String =
        "$workKey|$targetType|$name"
}
