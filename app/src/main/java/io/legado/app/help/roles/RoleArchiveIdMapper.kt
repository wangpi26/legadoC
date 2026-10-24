package io.legado.app.help.roles

/** 导入时把归档里的角色旧 id 映射成新 id；跳过角色由调用方预先写入映射。 */
object RoleArchiveIdMapper {

    fun resolve(
        targetType: String,
        oldId: Long,
        formalIds: Map<Long, Long>,
        castIds: Map<Long, Long>,
    ): Long? = when (targetType) {
        RoleArchiveTargetType.CHARACTER -> formalIds[oldId]
        RoleArchiveTargetType.CAST_ROLE -> castIds[oldId]
        else -> null
    }
}
