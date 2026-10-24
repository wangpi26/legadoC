package io.legado.app.help.roles

/**
 * 角色归档的纯数据模型。
 *
 * 归档按 workKey（书名+作者）分书，保留角色在源设备上的 id，仅用于导入时把音色绑定
 * 重映射到新 id；导入不会复用任何旧 id。
 */
data class RoleArchive(
    val version: Int = RoleArchiveCodec.CURRENT_VERSION,
    val exportedAt: Long = 0L,
    val books: List<RoleArchiveBook> = emptyList(),
)

data class RoleArchiveBook(
    val workKey: String,
    val bookName: String = "",
    val bookAuthor: String = "",
    val formalRoles: List<RoleArchiveRole> = emptyList(),
    val castRoles: List<RoleArchiveCastRole> = emptyList(),
    val bindings: List<RoleArchiveBinding> = emptyList(),
)

data class RoleArchiveRole(
    val id: Long,
    val name: String,
    val aliases: List<String> = emptyList(),
    val gender: String = "unknown",
    val enabled: Boolean = true,
)

data class RoleArchiveCastRole(
    val id: Long,
    val name: String,
    val aliases: List<String> = emptyList(),
    val gender: String = "unknown",
    val identityState: String = "stable",
    val occurrenceCount: Int = 0,
    val ignored: Boolean = false,
    val linkedRoleId: Long = 0L,
)

data class RoleArchiveBinding(
    val targetType: String,
    val targetId: Long,
    val engineId: String = "",
    val speakerId: String = "",
    val bindingMode: String = "auto",
)

object RoleArchiveTargetType {
    const val CHARACTER = "character"
    const val CAST_ROLE = "cast_role"

    fun isRoleTarget(value: String): Boolean =
        value == CHARACTER || value == CAST_ROLE
}
