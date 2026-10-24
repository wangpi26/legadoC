package io.legado.app.help.roles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoleArchiveCodecTest {

    private val archive = RoleArchive(
        version = 1,
        exportedAt = 123456789L,
        books = listOf(
            RoleArchiveBook(
                workKey = "work-a",
                bookName = "斗破苍穹",
                bookAuthor = "天蚕土豆",
                formalRoles = listOf(
                    RoleArchiveRole(1, "萧炎", listOf("炎帝"), "male", true),
                    RoleArchiveRole(2, "药老", listOf("药尘"), "male", false),
                ),
                castRoles = listOf(
                    RoleArchiveCastRole(
                        id = 11,
                        name = "萧薰儿",
                        aliases = listOf("薰儿"),
                        gender = "female",
                        identityState = "stable",
                        occurrenceCount = 12,
                        linkedRoleId = 1,
                    )
                ),
                bindings = listOf(
                    RoleArchiveBinding("character", 1, "engine", "voice-a", "manual"),
                    RoleArchiveBinding("cast_role", 11, "engine", "voice-b", "auto"),
                ),
            ),
            RoleArchiveBook(
                workKey = "work-b",
                bookName = "第二本",
                formalRoles = listOf(RoleArchiveRole(3, "主角")),
            ),
        ),
    )

    @Test
    fun roundTrip_preservesArchive() {
        val decoded = RoleArchiveCodec.decode(RoleArchiveCodec.encode(archive)).getOrThrow()

        assertEquals(archive, decoded)
    }

    @Test
    fun exportImportRoundTrip_keepsRolesAndBindings() {
        val decoded = RoleArchiveCodec.decode(RoleArchiveCodec.encode(archive)).getOrThrow()
        val plan = RoleArchivePlanner.plan(decoded, emptyList())

        assertEquals(4, plan.formal.size + plan.cast.size)
        assertEquals(2, plan.bindings.size)
        assertEquals(0, plan.skippedCount)
        val mapped = RoleArchiveIdMapper.resolve(
            plan.bindings[0].binding.targetType,
            plan.bindings[0].binding.targetId,
            mapOf(1L to 101L),
            mapOf(11L to 201L),
        )
        assertEquals(101L, mapped)
    }

    @Test
    fun badJson_returnsFailure() {
        val result = RoleArchiveCodec.decode("{not-json")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().isNotBlank())
    }

    @Test
    fun missingVersion_returnsFailure() {
        val result = RoleArchiveCodec.decode("""{"books":[]}""")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("version"))
    }

    @Test
    fun missingRoleName_returnsFailure() {
        val json = """
            {
              "version": 1,
              "books": [{
                "workKey": "work-a",
                "formalRoles": [{"id": 1}]
              }]
            }
        """.trimIndent()

        val result = RoleArchiveCodec.decode(json)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("name"))
    }

    @Test
    fun missingOptionalFields_useDefaults() {
        val json = """
            {
              "version": 1,
              "books": [{
                "workKey": "work-a",
                "formalRoles": [{"id": 1, "name": "萧炎"}],
                "castRoles": [{"id": 11, "name": "萧薰儿"}]
              }]
            }
        """.trimIndent()

        val book = RoleArchiveCodec.decode(json).getOrThrow().books.single()

        assertEquals(emptyList<String>(), book.formalRoles.single().aliases)
        assertEquals(true, book.formalRoles.single().enabled)
        assertEquals("stable", book.castRoles.single().identityState)
        assertEquals(0, book.castRoles.single().occurrenceCount)
        assertEquals(emptyList<RoleArchiveBinding>(), book.bindings)
    }

    @Test
    fun aliasesJson_isTolerant() {
        assertEquals(emptyList<String>(), RoleArchiveCodec.aliasesFromJson("bad-json"))
        assertEquals(listOf("炎帝", "萧炎"), RoleArchiveCodec.aliasesFromJson("""["炎帝","萧炎"]"""))
        assertEquals(
            listOf("炎帝"),
            RoleArchiveCodec.aliasesFromJson(RoleArchiveCodec.aliasesToJson(listOf("炎帝", ""))),
        )
    }
}
