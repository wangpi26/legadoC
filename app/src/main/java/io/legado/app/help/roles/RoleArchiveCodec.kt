package io.legado.app.help.roles

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 角色归档 JSON 编解码。
 *
 * 只依赖 Gson 与 JDK，不触碰 Android/Room，便于 JVM 单测。
 * 根字段/版本/必填角色字段缺失或类型错误时返回 failure，由 UI 给出明确提示；
 * 可选字段缺失时使用默认值，保证旧归档可继续导入。
 */
object RoleArchiveCodec {

    const val CURRENT_VERSION = 1
    const val MIME_TYPE = "application/json"

    private val gson = GsonBuilder()
        .disableHtmlEscaping()
        .setPrettyPrinting()
        .create()

    fun encode(archive: RoleArchive): String = gson.toJson(archive)

    fun decode(raw: String): Result<RoleArchive> = runCatching {
        if (raw.isBlank()) fail("角色归档为空")
        val root = parseObject(raw, "角色归档根节点")
        val version = root.intOrNull("version")
            ?: fail("角色归档缺少 version")
        if (version != CURRENT_VERSION) {
            fail("不支持的角色归档版本: $version")
        }
        val exportedAt = root.longOrNull("exportedAt") ?: 0L
        val books = root.arrayOrEmpty("books")
        val archiveBooks = mutableListOf<RoleArchiveBook>()
        val seenWorkKeys = mutableSetOf<String>()
        books.forEachIndexed { index, element ->
            val book = parseBook(element, "books[$index]")
            if (!seenWorkKeys.add(book.workKey)) {
                fail("角色归档包含重复的 workKey: ${book.workKey}")
            }
            archiveBooks += book
        }
        RoleArchive(version, exportedAt, archiveBooks)
    }

    fun aliasesToJson(aliases: List<String>): String =
        gson.toJson(aliases.distinct().filter { it.isNotBlank() })

    fun aliasesFromJson(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val element = JsonParser.parseString(json)
            if (!element.isJsonArray) return@runCatching emptyList()
            element.asJsonArray.mapNotNull { item ->
                item.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    ?.asString
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }

    private fun parseBook(element: JsonElement, context: String): RoleArchiveBook {
        val obj = element.asObjectOrFail(context)
        val workKey = obj.stringOrNull("workKey")?.trim().orEmpty()
        if (workKey.isBlank()) fail("$context 缺少 workKey")
        val bookName = obj.stringOrNull("bookName").orEmpty()
        val bookAuthor = obj.stringOrNull("bookAuthor").orEmpty()
        val formal = obj.arrayOrEmpty("formalRoles").mapIndexed { index, role ->
            parseFormalRole(role, "$context.formalRoles[$index]")
        }
        val cast = obj.arrayOrEmpty("castRoles").mapIndexed { index, role ->
            parseCastRole(role, "$context.castRoles[$index]")
        }
        checkUniqueIds(formal.map { it.id }, "$context.formalRoles")
        checkUniqueIds(cast.map { it.id }, "$context.castRoles")
        val bindings = obj.arrayOrEmpty("bindings").mapIndexed { index, binding ->
            parseBinding(binding, "$context.bindings[$index]")
        }
        return RoleArchiveBook(workKey, bookName, bookAuthor, formal, cast, bindings)
    }

    private fun parseFormalRole(element: JsonElement, context: String): RoleArchiveRole {
        val obj = element.asObjectOrFail(context)
        val id = obj.longOrNull("id") ?: fail("$context 缺少 id")
        if (id <= 0L) fail("$context 的 id 必须大于 0")
        val name = obj.stringOrNull("name")?.trim().orEmpty()
        if (name.isBlank()) fail("$context 缺少 name")
        val aliases = obj.stringArrayOrEmpty("aliases", context)
        val gender = obj.stringOrNull("gender").orEmpty().ifBlank { "unknown" }
        val enabled = obj.booleanOrNull("enabled") ?: true
        return RoleArchiveRole(id, name, aliases, gender, enabled)
    }

    private fun parseCastRole(element: JsonElement, context: String): RoleArchiveCastRole {
        val obj = element.asObjectOrFail(context)
        val id = obj.longOrNull("id") ?: fail("$context 缺少 id")
        if (id <= 0L) fail("$context 的 id 必须大于 0")
        val name = obj.stringOrNull("name")?.trim().orEmpty()
        if (name.isBlank()) fail("$context 缺少 name")
        val aliases = obj.stringArrayOrEmpty("aliases", context)
        val gender = obj.stringOrNull("gender").orEmpty().ifBlank { "unknown" }
        val identityState = obj.stringOrNull("identityState").orEmpty().ifBlank { "stable" }
        val occurrenceCount = obj.intOrNull("occurrenceCount") ?: 0
        val ignored = obj.booleanOrNull("ignored") ?: false
        val linkedRoleId = obj.longOrNull("linkedRoleId") ?: 0L
        return RoleArchiveCastRole(
            id = id,
            name = name,
            aliases = aliases,
            gender = gender,
            identityState = identityState,
            occurrenceCount = occurrenceCount,
            ignored = ignored,
            linkedRoleId = linkedRoleId,
        )
    }

    private fun parseBinding(element: JsonElement, context: String): RoleArchiveBinding {
        val obj = element.asObjectOrFail(context)
        val targetType = obj.stringOrNull("targetType")?.trim().orEmpty()
        if (!RoleArchiveTargetType.isRoleTarget(targetType)) {
            fail("$context 的 targetType 无效: $targetType")
        }
        val targetId = obj.longOrNull("targetId") ?: fail("$context 缺少 targetId")
        if (targetId <= 0L) fail("$context 的 targetId 必须大于 0")
        return RoleArchiveBinding(
            targetType = targetType,
            targetId = targetId,
            engineId = obj.stringOrNull("engineId").orEmpty(),
            speakerId = obj.stringOrNull("speakerId").orEmpty(),
            bindingMode = obj.stringOrNull("bindingMode").orEmpty().ifBlank { "auto" },
        )
    }

    private fun checkUniqueIds(ids: List<Long>, context: String) {
        val seen = mutableSetOf<Long>()
        ids.forEach { id ->
            if (!seen.add(id)) fail("$context 包含重复 id: $id")
        }
    }

    private fun parseObject(raw: String, context: String): JsonObject {
        val element = runCatching { JsonParser.parseString(raw) }.getOrElse {
            fail("$context 不是有效 JSON")
        }
        return element.asObjectOrFail(context)
    }

    private fun JsonElement.asObjectOrFail(context: String): JsonObject {
        if (!isJsonObject) fail("$context 必须是对象")
        return asJsonObject
    }

    private fun JsonObject.arrayOrEmpty(key: String): List<JsonElement> {
        val element = get(key) ?: return emptyList()
        if (element.isJsonNull) return emptyList()
        if (!element.isJsonArray) fail("$key 必须是数组")
        return element.asJsonArray.toList()
    }

    private fun JsonObject.stringArrayOrEmpty(key: String, context: String): List<String> {
        val array = arrayOrEmpty(key)
        return array.mapIndexed { index, item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) {
                fail("$context.$key[$index] 必须是字符串")
            }
            item.asString.trim()
        }.filter { it.isNotEmpty() }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            fail("$key 必须是字符串")
        }
        return element.asString
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            fail("$key 必须是数字")
        }
        return element.asInt
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) {
            fail("$key 必须是数字")
        }
        return element.asLong
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val element = get(key) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isBoolean) {
            fail("$key 必须是布尔值")
        }
        return element.asBoolean
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}
