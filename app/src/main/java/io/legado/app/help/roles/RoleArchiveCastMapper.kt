package io.legado.app.help.roles

import io.legado.app.data.entities.BookTtsCastRole

/**
 * 临时角色归档字段的纯映射。
 *
 * samplesJson/evidence/首末章节是 AI 分镜分析证据, 归档 v2 起原样透传:
 * 不解析 JSON、不裁剪字符串、不改变下标。
 */
object RoleArchiveCastMapper {

    fun toEntity(
        workKey: String,
        role: RoleArchiveCastRole,
        linkedRoleId: Long,
        now: Long,
    ) = BookTtsCastRole(
        castRoleId = 0L,
        workKey = workKey,
        name = role.name,
        aliasesJson = RoleArchiveCodec.aliasesToJson(role.aliases),
        gender = role.gender,
        identityState = role.identityState,
        occurrenceCount = role.occurrenceCount,
        firstChapterIndex = role.firstChapterIndex,
        lastChapterIndex = role.lastChapterIndex,
        samplesJson = role.samplesJson,
        evidence = role.evidence,
        ignored = role.ignored,
        linkedRoleId = linkedRoleId,
        updatedAt = now,
    )

    fun toArchive(role: BookTtsCastRole) = RoleArchiveCastRole(
        id = role.castRoleId,
        name = role.name,
        aliases = RoleArchiveCodec.aliasesFromJson(role.aliasesJson),
        gender = role.gender,
        identityState = role.identityState,
        occurrenceCount = role.occurrenceCount,
        firstChapterIndex = role.firstChapterIndex,
        lastChapterIndex = role.lastChapterIndex,
        samplesJson = role.samplesJson,
        evidence = role.evidence,
        ignored = role.ignored,
        linkedRoleId = role.linkedRoleId,
    )
}
