package io.legado.app.help.roles

/**
 * 角色库批量操作的纯计划/校验逻辑。
 *
 * 选中集合按正式/临时分流；性别、启停只作用于正式角色，转正只作用于临时角色，
 * 删除与音色可作用于两类角色。音色来源优先当前引擎目录，目录为空时回退已有绑定。
 */
object RoleBatchPlanner {

    data class Item(
        val workKey: String,
        val targetType: String,
        val targetId: Long,
        val name: String,
        val bookTitle: String,
        val gender: String,
        val enabled: Boolean,
        val identityState: String,
    )

    data class Split(
        val formal: List<Item>,
        val temporary: List<Item>,
    )

    data class VoiceOption(
        val engineId: String,
        val speakerId: String,
        val label: String,
    )

    sealed interface VoiceSourceResult {
        data class Success(
            val options: List<VoiceOption>,
            val fromFallback: Boolean,
        ) : VoiceSourceResult

        data class Failure(val reason: String) : VoiceSourceResult
    }

    fun split(items: List<Item>): Split = Split(
        formal = items.filter { it.targetType == RoleArchiveTargetType.CHARACTER },
        temporary = items.filter { it.targetType == RoleArchiveTargetType.CAST_ROLE },
    )

    fun genderTargets(items: List<Item>): List<Item> = split(items).formal

    fun enabledTargets(items: List<Item>): List<Item> = split(items).formal

    fun promoteTargets(items: List<Item>): List<Item> = split(items).temporary

    fun deleteTargets(items: List<Item>): List<Item> = items

    fun deleteLabels(items: List<Item>): List<String> =
        items.map { "《${it.bookTitle}》${it.name}" }

    /**
     * engineVoices 非空时只用引擎目录；为空时回退已有绑定；两者都空返回明确失败。
     */
    fun resolveVoiceSources(
        engineVoices: List<VoiceOption>,
        bindingVoices: List<VoiceOption>,
    ): VoiceSourceResult {
        val engine = engineVoices
            .filter { it.engineId.isNotBlank() && it.speakerId.isNotBlank() }
            .distinctBy { key(it.engineId, it.speakerId) }
        if (engine.isNotEmpty()) {
            return VoiceSourceResult.Success(engine, fromFallback = false)
        }
        val fallback = bindingVoices
            .filter { it.speakerId.isNotBlank() }
            .distinctBy { key(it.engineId, it.speakerId) }
        if (fallback.isNotEmpty()) {
            return VoiceSourceResult.Success(fallback, fromFallback = true)
        }
        return VoiceSourceResult.Failure(
            "没有可用音色：当前引擎没有发音人目录，库中也没有已有绑定"
        )
    }

    private fun key(engineId: String, speakerId: String): String = "$engineId|$speakerId"
}
