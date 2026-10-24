package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookRole
import io.legado.app.data.entities.BookTtsCastRole
import io.legado.app.data.entities.BookTtsVoiceBinding
import io.legado.app.databinding.ActivityRoleLibraryBinding
import io.legado.app.help.tts.BookTtsAutomationConfig
import io.legado.app.help.tts.BookTtsCastingCoordinator
import io.legado.app.help.tts.TtsEngineStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 跨书角色库: 汇总所有书的正式角色与临时角色, 支持搜索、编辑、转正、合并、删除.
 *
 * 数据与朗读侧完全复用 [BookRole] / [BookTtsCastRole] / [BookTtsVoiceBinding];
 * 不新建角色表, 避免与多角色朗读的设置分叉.
 */
class RoleLibraryActivity :
    BaseActivity<ActivityRoleLibraryBinding>(fullScreen = false) {

    override val binding by viewBinding(ActivityRoleLibraryBinding::inflate)

    private val adapter = RoleLibraryAdapter(::showRoleActions)
    private var tabPosition = 0
    private var query = ""
    private var allRows: List<RoleLibraryRow> = emptyList()
    private var refreshOnFocus = false

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setTitle(R.string.role_library)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.ai_roles_formal))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.ai_roles_temporary))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabPosition = tab.position
                applyFilter()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                applyFilter()
            }
        })
        loadRows()
    }

    override fun onResume() {
        super.onResume()
        if (allRows.isNotEmpty()) loadRows()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && refreshOnFocus) {
            refreshOnFocus = false
            loadRows()
        }
    }

    private fun loadRows() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val dao = appDb.bookRoleDao
                val books = appDb.bookDao.all.associateBy {
                    BookTtsAutomationConfig.workKeyOf(it.name, it.author)
                }
                val bindings = dao.getAllBindingsGlobal().associateBy {
                    bindingKey(it.workKey, it.targetType, it.targetId)
                }
                fun bindingLabel(workKey: String, targetType: String, targetId: Long): String {
                    val binding = bindings[bindingKey(workKey, targetType, targetId)] ?: return ""
                    if (binding.speakerId.isBlank()) return ""
                    val engine = TtsEngineStore.engineOrVoiceDirectory(binding.engineId)
                        ?: return binding.speakerId
                    val voiceName = engine.effectiveVoices()
                        .firstOrNull { it.id == binding.speakerId }?.name
                        ?: binding.speakerId
                    return "${engine.name} · $voiceName"
                }
                val formal = dao.getAllBookRolesGlobal().map { role ->
                    val title = books[role.workKey]?.name ?: getString(R.string.role_library_unknown_book)
                    RoleLibraryRow(
                        targetType = BookTtsVoiceBinding.TargetType.CHARACTER,
                        targetId = role.roleId,
                        workKey = role.workKey,
                        bookTitle = title,
                        name = role.name,
                        aliases = parseAliases(role.aliasesJson),
                        gender = role.gender,
                        detail = buildString {
                            append(genderLabel(role.gender))
                            val aliases = parseAliases(role.aliasesJson)
                            if (aliases.isNotEmpty()) {
                                append(" · ").append(aliases.joinToString("/"))
                            }
                            val voice = bindingLabel(
                                role.workKey,
                                BookTtsVoiceBinding.TargetType.CHARACTER,
                                role.roleId,
                            )
                            if (voice.isNotBlank()) append(" · ").append(voice)
                        },
                        identityState = "formal",
                        occurrenceCount = 0,
                    )
                }
                val cast = dao.getAllCastRolesGlobal()
                    .filter { !it.ignored }
                    .map { role ->
                        val title = books[role.workKey]?.name ?: getString(R.string.role_library_unknown_book)
                        RoleLibraryRow(
                            targetType = BookTtsVoiceBinding.TargetType.CAST_ROLE,
                            targetId = role.castRoleId,
                            workKey = role.workKey,
                            bookTitle = title,
                            name = role.name,
                            aliases = parseAliases(role.aliasesJson),
                            gender = role.gender,
                            detail = buildString {
                                append(genderLabel(role.gender))
                                append(" · ")
                                append(
                                    when (role.identityState) {
                                        BookTtsCastRole.IdentityState.PENDING ->
                                            getString(R.string.ai_role_state_pending)
                                        else -> getString(R.string.ai_role_state_stable)
                                    }
                                )
                                append(" · ")
                                append(getString(R.string.ai_role_occurrence, role.occurrenceCount))
                                val aliases = parseAliases(role.aliasesJson)
                                if (aliases.isNotEmpty()) {
                                    append(" · ").append(aliases.joinToString("/"))
                                }
                                val voice = bindingLabel(
                                    role.workKey,
                                    BookTtsVoiceBinding.TargetType.CAST_ROLE,
                                    role.castRoleId,
                                )
                                if (voice.isNotBlank()) append(" · ").append(voice)
                            },
                            identityState = role.identityState,
                            occurrenceCount = role.occurrenceCount,
                        )
                    }
                formal + cast
            }
            allRows = rows
            applyFilter()
        }
    }

    private fun applyFilter() {
        val keyword = query.trim().lowercase()
        val visible = allRows
            .filter { row ->
                (tabPosition == 0) ==
                    (row.targetType == BookTtsVoiceBinding.TargetType.CHARACTER)
            }
            .filter { row ->
                keyword.isBlank() ||
                    row.name.lowercase().contains(keyword) ||
                    row.bookTitle.lowercase().contains(keyword) ||
                    row.aliases.any { it.lowercase().contains(keyword) }
            }
        adapter.submitList(visible)
        binding.emptyView.isVisible = visible.isEmpty()
    }

    private fun showRoleActions(row: RoleLibraryRow) {
        val isFormal = row.targetType == BookTtsVoiceBinding.TargetType.CHARACTER
        val items = if (isFormal) {
            mutableListOf(
                getString(R.string.ai_role_edit),
                getString(R.string.role_library_merge_to),
            )
        } else {
            mutableListOf(
                getString(R.string.ai_role_promote),
                getString(R.string.role_library_merge_to),
            )
        }
        alert(title = row.name) {
            items(items) { dialog, _, which ->
                dialog.dismiss()
                when {
                    isFormal && which == 0 -> {
                        refreshOnFocus = true
                        AiRoleEditDialog.show(supportFragmentManager, row.targetId)
                    }
                    isFormal && which == 1 -> showMergeTargets(row)
                    !isFormal && which == 0 -> promoteCastRole(row)
                    !isFormal && which == 1 -> showMergeTargets(row)
                }
            }
            negativeButton(if (isFormal) R.string.delete else R.string.ai_role_delete) { dialog ->
                dialog.dismiss()
                if (isFormal) deleteFormalRole(row) else deleteCastRole(row)
            }
        }
    }

    private fun showMergeTargets(source: RoleLibraryRow) {
        lifecycleScope.launch {
            val targets = withContext(Dispatchers.IO) {
                val dao = appDb.bookRoleDao
                val books = appDb.bookDao.all.associateBy {
                    BookTtsAutomationConfig.workKeyOf(it.name, it.author)
                }
                val titleOf = {
                    workKey: String ->
                    books[workKey]?.name ?: getString(R.string.role_library_unknown_book)
                }
                val formalTargets = dao.getAllBookRolesGlobal()
                    .filter {
                        it.workKey == source.workKey &&
                            (source.targetType != BookTtsVoiceBinding.TargetType.CHARACTER ||
                                it.roleId != source.targetId)
                    }
                    .map {
                        RoleLibraryRow(
                            BookTtsVoiceBinding.TargetType.CHARACTER,
                            it.roleId,
                            it.workKey,
                            titleOf(it.workKey),
                            "正式: ${it.name}",
                            emptyList(),
                            it.gender,
                            "",
                            "formal",
                            0,
                        )
                    }
                val castTargets = if (source.targetType == BookTtsVoiceBinding.TargetType.CHARACTER) {
                    emptyList()
                } else {
                    dao.getAllCastRolesGlobal()
                        .filter { it.workKey == source.workKey && it.castRoleId != source.targetId }
                        .map {
                            RoleLibraryRow(
                                BookTtsVoiceBinding.TargetType.CAST_ROLE,
                                it.castRoleId,
                                it.workKey,
                                titleOf(it.workKey),
                                "临时: ${it.name}",
                                emptyList(),
                                it.gender,
                                "",
                                it.identityState,
                                it.occurrenceCount,
                            )
                        }
                }
                formalTargets + castTargets
            }
            if (targets.isEmpty()) {
                toastOnUi("没有可合并的角色")
                return@launch
            }
            alert(title = getString(R.string.role_library_merge_to)) {
                items(targets.map { it.name }) { dialog, _, which ->
                    dialog.dismiss()
                    if (source.targetType == BookTtsVoiceBinding.TargetType.CHARACTER) {
                        mergeFormalRole(source, targets[which])
                    } else {
                        mergeCastRole(source, targets[which])
                    }
                }
                negativeButton(R.string.cancel)
            }
        }
    }

    private fun promoteCastRole(row: RoleLibraryRow) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = appDb.bookRoleDao
                val castRole = dao.getCastRole(row.targetId) ?: return@withContext
                val existing = dao.getRoles(row.workKey).firstOrNull {
                    BookTtsCastingCoordinator.normalizeIdentityName(it.name) ==
                        BookTtsCastingCoordinator.normalizeIdentityName(castRole.name)
                }
                val roleId = existing?.roleId ?: dao.insertRole(
                    BookRole(
                        workKey = row.workKey,
                        name = castRole.name,
                        aliasesJson = castRole.aliasesJson,
                        gender = castRole.gender,
                        enabled = true,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                if (existing != null) {
                    val merged = (parseAliases(existing.aliasesJson) + castRole.name +
                        parseAliases(castRole.aliasesJson))
                        .filter {
                            it.isNotBlank() &&
                                BookTtsCastingCoordinator.normalizeIdentityName(it) !=
                                BookTtsCastingCoordinator.normalizeIdentityName(existing.name)
                        }
                        .distinctBy { BookTtsCastingCoordinator.normalizeIdentityName(it) }
                    dao.updateRole(
                        existing.copy(
                            aliasesJson = GSON.toJson(merged),
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
                dao.updateCastRole(
                    castRole.copy(
                        ignored = false,
                        linkedRoleId = roleId,
                        identityState = BookTtsCastRole.IdentityState.STABLE,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                dao.getBinding(
                    row.workKey,
                    BookTtsVoiceBinding.TargetType.CAST_ROLE,
                    castRole.castRoleId,
                )?.let { binding ->
                    dao.deleteBinding(row.workKey, binding.targetType, binding.targetId)
                    dao.insertBinding(
                        binding.copy(
                            targetType = BookTtsVoiceBinding.TargetType.CHARACTER,
                            targetId = roleId,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
            loadRows()
        }
    }

    private fun mergeFormalRole(source: RoleLibraryRow, target: RoleLibraryRow) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = appDb.bookRoleDao
                val sourceRole = dao.getRole(source.targetId) ?: return@withContext
                if (target.targetType != BookTtsVoiceBinding.TargetType.CHARACTER) {
                    return@withContext
                }
                val targetRole = dao.getRole(target.targetId) ?: return@withContext
                val aliases = (parseAliases(targetRole.aliasesJson) + sourceRole.name +
                    parseAliases(sourceRole.aliasesJson) + targetRole.name)
                    .filter { it.isNotBlank() }
                    .distinctBy { BookTtsCastingCoordinator.normalizeIdentityName(it) }
                dao.updateRole(
                    targetRole.copy(
                        aliasesJson = GSON.toJson(aliases),
                        updatedAt = System.currentTimeMillis(),
                    )
                )
                moveBinding(
                    source.workKey,
                    BookTtsVoiceBinding.TargetType.CHARACTER,
                    source.targetId,
                    BookTtsVoiceBinding.TargetType.CHARACTER,
                    target.targetId,
                )
                dao.getCastRoles(source.workKey)
                    .filter { it.linkedRoleId == source.targetId }
                    .forEach {
                        dao.updateCastRole(
                            it.copy(linkedRoleId = target.targetId, updatedAt = System.currentTimeMillis())
                        )
                    }
                dao.deleteRole(sourceRole)
                dao.deleteBinding(
                    source.workKey,
                    BookTtsVoiceBinding.TargetType.CHARACTER,
                    source.targetId,
                )
            }
            loadRows()
        }
    }

    private fun mergeCastRole(source: RoleLibraryRow, target: RoleLibraryRow) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = appDb.bookRoleDao
                val sourceRole = dao.getCastRole(source.targetId) ?: return@withContext
                val mergedAliases = (parseAliases(sourceRole.aliasesJson) + sourceRole.name)
                    .filter { it.isNotBlank() }
                when (target.targetType) {
                    BookTtsVoiceBinding.TargetType.CHARACTER -> {
                        val formal = dao.getRole(target.targetId) ?: return@withContext
                        val aliases = (parseAliases(formal.aliasesJson) + mergedAliases + formal.name)
                            .distinctBy { BookTtsCastingCoordinator.normalizeIdentityName(it) }
                        dao.updateRole(
                            formal.copy(
                                aliasesJson = GSON.toJson(aliases),
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        moveBinding(
                            source.workKey,
                            BookTtsVoiceBinding.TargetType.CAST_ROLE,
                            source.targetId,
                            BookTtsVoiceBinding.TargetType.CHARACTER,
                            target.targetId,
                        )
                        dao.deleteCastRole(sourceRole)
                    }

                    else -> {
                        val cast = dao.getCastRole(target.targetId) ?: return@withContext
                        val aliases = (parseAliases(cast.aliasesJson) + mergedAliases + cast.name)
                            .distinctBy { BookTtsCastingCoordinator.normalizeIdentityName(it) }
                        dao.updateCastRole(
                            cast.copy(
                                aliasesJson = GSON.toJson(aliases),
                                occurrenceCount = cast.occurrenceCount + sourceRole.occurrenceCount,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        moveBinding(
                            source.workKey,
                            BookTtsVoiceBinding.TargetType.CAST_ROLE,
                            source.targetId,
                            BookTtsVoiceBinding.TargetType.CAST_ROLE,
                            target.targetId,
                        )
                        dao.deleteCastRole(sourceRole)
                    }
                }
                dao.deleteBinding(
                    source.workKey,
                    BookTtsVoiceBinding.TargetType.CAST_ROLE,
                    source.targetId,
                )
            }
            loadRows()
        }
    }

    private fun moveBinding(
        workKey: String,
        sourceType: String,
        sourceId: Long,
        targetType: String,
        targetId: Long,
    ) {
        val dao = appDb.bookRoleDao
        val source = dao.getBinding(workKey, sourceType, sourceId) ?: return
        if (dao.getBinding(workKey, targetType, targetId) == null) {
            dao.insertBinding(
                source.copy(
                    targetType = targetType,
                    targetId = targetId,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun deleteCastRole(row: RoleLibraryRow) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = appDb.bookRoleDao
                dao.getCastRole(row.targetId)?.let { dao.deleteCastRole(it) }
                dao.deleteBinding(
                    row.workKey,
                    BookTtsVoiceBinding.TargetType.CAST_ROLE,
                    row.targetId,
                )
            }
            loadRows()
        }
    }

    private fun deleteFormalRole(row: RoleLibraryRow) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val dao = appDb.bookRoleDao
                val role = dao.getRole(row.targetId) ?: return@withContext
                dao.deleteRole(role)
                dao.deleteBinding(
                    row.workKey,
                    BookTtsVoiceBinding.TargetType.CHARACTER,
                    row.targetId,
                )
                dao.getCastRoles(row.workKey)
                    .filter { it.linkedRoleId == row.targetId }
                    .forEach {
                        dao.updateCastRole(
                            it.copy(linkedRoleId = 0L, updatedAt = System.currentTimeMillis())
                        )
                    }
            }
            loadRows()
        }
    }

    private fun bindingKey(workKey: String, targetType: String, targetId: Long): String =
        "$workKey|$targetType|$targetId"

    private fun genderLabel(gender: String): String = when (gender) {
        BookRole.Gender.MALE -> getString(R.string.ai_role_gender_male)
        BookRole.Gender.FEMALE -> getString(R.string.ai_role_gender_female)
        else -> getString(R.string.ai_role_gender_unknown)
    }

    private fun parseAliases(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return runCatching { GSON.fromJsonArray<String>(json).getOrNull().orEmpty() }
            .getOrDefault(emptyList())
    }
}
