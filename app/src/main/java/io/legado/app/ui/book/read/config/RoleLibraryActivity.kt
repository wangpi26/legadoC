package io.legado.app.ui.book.read.config

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
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
import io.legado.app.databinding.DialogRoleVoiceSyncBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.roles.RoleArchiveCodec
import io.legado.app.help.roles.RoleArchiveExporter
import io.legado.app.help.roles.RoleArchiveImporter
import io.legado.app.help.roles.RoleBatchApplier
import io.legado.app.help.roles.RoleBatchPlanner
import io.legado.app.help.roles.RoleBindingBatchApplier
import io.legado.app.help.roles.RoleVoiceSyncApplier
import io.legado.app.help.roles.RoleVoiceSyncPlanner
import io.legado.app.help.tts.BookTtsAutomationConfig
import io.legado.app.help.tts.BookTtsCastingCoordinator
import io.legado.app.help.tts.TtsEngineStore
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private val selectedRows = linkedMapOf<String, RoleLibraryRow>()
    private var selectionMode = false
    private var visibleRows: List<RoleLibraryRow> = emptyList()
    private val adapter = RoleLibraryAdapter(
        onClick = ::onRowClick,
        onLongClick = ::onRowLongClick,
        isSelected = ::isRowSelected,
    )
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(RoleArchiveCodec.MIME_TYPE)
    ) { uri -> uri?.let(::exportArchive) }
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::importArchive) }
    private var tabPosition = 0
    private var query = ""
    private var allRows: List<RoleLibraryRow> = emptyList()
    private var refreshOnFocus = false

    private data class VoiceSyncData(
        val plan: RoleVoiceSyncPlanner.Plan,
        val bookTitles: Map<String, String>,
    )

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
        setupSelectionUi()
        onBackPressedDispatcher.addCallback(this) {
            if (selectionMode) {
                exitSelectionMode()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
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

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.role_library, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_role_export -> {
                val fileName = "readernext-roles-${
                    SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                }.json"
                exportLauncher.launch(fileName)
                return true
            }

            R.id.menu_role_import -> {
                importLauncher.launch(
                    arrayOf(RoleArchiveCodec.MIME_TYPE, "text/plain", "application/octet-stream")
                )
                return true
            }
        }
        return super.onCompatOptionsItemSelected(item)
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
        visibleRows = visible
        adapter.submitList(visible)
        adapter.refreshSelection()
        binding.emptyView.isVisible = visible.isEmpty()
        if (selectionMode) updateSelectionUi()
    }

    private fun showRoleActions(row: RoleLibraryRow) {
        val isFormal = row.targetType == BookTtsVoiceBinding.TargetType.CHARACTER
        val items = if (isFormal) {
            mutableListOf(
                getString(R.string.ai_role_edit),
                getString(R.string.role_library_merge_to),
                getString(R.string.role_voice_sync),
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
                    isFormal && which == 2 -> showVoiceSync(row)
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

    private fun setupSelectionUi() {
        binding.tvSelectAll.setOnClickListener { toggleSelectAll() }
        binding.tvExitSelection.setOnClickListener { exitSelectionMode() }
        binding.btnBatchGender.setOnClickListener { showBatchGender() }
        binding.btnBatchEnabled.setOnClickListener { showBatchEnabled() }
        binding.btnBatchPromote.setOnClickListener { showBatchPromote() }
        binding.btnBatchDelete.setOnClickListener { showBatchDelete() }
        binding.btnBatchVoice.setOnClickListener { showBatchVoice() }
    }

    private fun onRowClick(row: RoleLibraryRow) {
        if (selectionMode) toggleSelection(row) else showRoleActions(row)
    }

    private fun onRowLongClick(row: RoleLibraryRow) {
        if (!selectionMode) {
            selectionMode = true
            binding.selectionBar.isVisible = true
            binding.bottomActions.isVisible = true
        }
        toggleSelection(row)
    }

    private fun isRowSelected(row: RoleLibraryRow): Boolean =
        selectionMode && selectionKey(row) in selectedRows

    private fun selectionKey(row: RoleLibraryRow): String =
        "${row.targetType}|${row.targetId}"

    private fun toggleSelection(row: RoleLibraryRow) {
        val key = selectionKey(row)
        if (selectedRows.remove(key) == null) {
            selectedRows[key] = row
        }
        adapter.refreshSelection()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        if (!selectionMode) return
        binding.tvSelectionCount.text = getString(R.string.role_batch_selected, selectedRows.size)
        val allSelected = visibleRows.isNotEmpty() && visibleRows.all { isRowSelected(it) }
        binding.tvSelectAll.text = getString(
            if (allSelected) R.string.role_batch_clear_selection else R.string.role_batch_select_all
        )
        val formalCount = RoleBatchPlanner.genderTargets(selectedItems()).size
        val castCount = RoleBatchPlanner.promoteTargets(selectedItems()).size
        val hasAny = selectedRows.isNotEmpty()
        setActionEnabled(binding.btnBatchGender, formalCount > 0)
        setActionEnabled(binding.btnBatchEnabled, formalCount > 0)
        setActionEnabled(binding.btnBatchPromote, castCount > 0)
        setActionEnabled(binding.btnBatchDelete, hasAny)
        setActionEnabled(binding.btnBatchVoice, hasAny)
    }

    private fun setActionEnabled(view: android.view.View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.4f
    }

    private fun toggleSelectAll() {
        if (visibleRows.isEmpty()) return
        if (visibleRows.all { isRowSelected(it) }) {
            visibleRows.forEach { selectedRows.remove(selectionKey(it)) }
        } else {
            visibleRows.forEach { selectedRows[selectionKey(it)] = it }
        }
        adapter.refreshSelection()
        updateSelectionUi()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedRows.clear()
        binding.selectionBar.isVisible = false
        binding.bottomActions.isVisible = false
        binding.titleBar.setTitle(R.string.role_library)
        adapter.refreshSelection()
    }

    private fun selectedItems(): List<RoleBatchPlanner.Item> =
        selectedRows.values.map {
            RoleBatchPlanner.Item(
                workKey = it.workKey,
                targetType = it.targetType,
                targetId = it.targetId,
                name = it.name,
                bookTitle = it.bookTitle,
                gender = it.gender,
                enabled = true,
                identityState = it.identityState,
            )
        }

    private fun showBatchGender() {
        val targets = RoleBatchPlanner.genderTargets(selectedItems())
        if (targets.isEmpty()) {
            toastOnUi(R.string.role_batch_no_targets)
            return
        }
        val genders = listOf(
            BookRole.Gender.MALE,
            BookRole.Gender.FEMALE,
            BookRole.Gender.UNKNOWN,
        )
        val labels = genders.map {
            getString(
                when (it) {
                    BookRole.Gender.MALE -> R.string.ai_role_gender_male
                    BookRole.Gender.FEMALE -> R.string.ai_role_gender_female
                    else -> R.string.ai_role_gender_unknown
                }
            )
        }
        alert(title = getString(R.string.role_batch_gender_title)) {
            items(labels) { dialog, _, which ->
                dialog.dismiss()
                runBatch { RoleBatchApplier.updateGender(targets, genders[which]) }
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun showBatchEnabled() {
        val targets = RoleBatchPlanner.enabledTargets(selectedItems())
        if (targets.isEmpty()) {
            toastOnUi(R.string.role_batch_no_targets)
            return
        }
        val labels = listOf(
            getString(R.string.role_batch_enabled),
            getString(R.string.role_batch_disabled),
        )
        alert(title = getString(R.string.role_batch_enable_title)) {
            items(labels) { dialog, _, which ->
                dialog.dismiss()
                runBatch { RoleBatchApplier.updateEnabled(targets, enabled = which == 0) }
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun showBatchPromote() {
        val targets = RoleBatchPlanner.promoteTargets(selectedItems())
        if (targets.isEmpty()) {
            toastOnUi(R.string.role_batch_no_targets)
            return
        }
        alert(title = getString(R.string.role_batch_promote_title)) {
            setMessage(getString(R.string.role_batch_affected, targets.size))
            positiveButton(R.string.role_batch_promote) {
                runBatch { RoleBatchApplier.promoteCast(targets) }
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun showBatchDelete() {
        val targets = RoleBatchPlanner.deleteTargets(selectedItems())
        if (targets.isEmpty()) return
        val labels = RoleBatchPlanner.deleteLabels(targets)
        alert(title = getString(R.string.role_batch_delete_title)) {
            setMessage(
                getString(R.string.role_batch_delete_message, targets.size) +
                    "\n" + labels.joinToString("\n")
            )
            positiveButton(R.string.role_batch_delete) {
                runBatch { RoleBatchApplier.delete(targets) }
            }
            negativeButton(R.string.cancel)
        }
    }

    private fun showBatchVoice() {
        val targets = selectedItems()
        if (targets.isEmpty()) return
        lifecycleScope.launch {
            when (val result = withContext(Dispatchers.IO) { buildVoiceSources() }) {
                is RoleBatchPlanner.VoiceSourceResult.Failure -> toastOnUi(result.reason)
                is RoleBatchPlanner.VoiceSourceResult.Success -> {
                    alert(title = getString(R.string.role_batch_voice_title)) {
                        items(result.options.map { it.label }) { dialog, _, which ->
                            dialog.dismiss()
                            val option = result.options[which]
                            alert(title = getString(R.string.role_batch_voice_title)) {
                                setMessage(
                                    getString(
                                        R.string.role_batch_voice_confirm,
                                        targets.size,
                                        option.label,
                                    )
                                )
                                positiveButton(R.string.role_batch_voice_apply) {
                                    applyBatchVoice(option, targets)
                                }
                                negativeButton(R.string.cancel)
                            }
                        }
                        negativeButton(R.string.cancel)
                    }
                }
            }
        }
    }

    private fun applyBatchVoice(
        option: RoleBatchPlanner.VoiceOption,
        targets: List<RoleBatchPlanner.Item>,
    ) {
        val bindingTargets = targets.map {
            RoleBindingBatchApplier.Target(it.workKey, it.targetType, it.targetId)
        }
        lifecycleScope.launch {
            runCatching {
                RoleBindingBatchApplier.apply(
                    bindingTargets,
                    option.engineId,
                    option.speakerId,
                )
            }.onSuccess { counts ->
                exitSelectionMode()
                loadRows()
                alert(title = getString(R.string.role_batch_done)) {
                    setMessage(
                        getString(
                            R.string.role_batch_voice_applied,
                            counts.added,
                            counts.overwritten,
                            counts.skipped,
                        )
                    )
                    positiveButton(R.string.ok)
                }
            }.onFailure {
                toastOnUi(getString(R.string.role_batch_failed, it.message.orEmpty()))
            }
        }
    }

    private fun runBatch(block: suspend () -> Int) {
        lifecycleScope.launch {
            runCatching { block() }.onSuccess { count ->
                exitSelectionMode()
                loadRows()
                alert(title = getString(R.string.role_batch_done)) {
                    setMessage(getString(R.string.role_batch_affected, count))
                    positiveButton(R.string.ok)
                }
            }.onFailure {
                toastOnUi(getString(R.string.role_batch_failed, it.message.orEmpty()))
            }
        }
    }

    private fun buildVoiceSources(): RoleBatchPlanner.VoiceSourceResult {
        val engine = runCatching {
            TtsEngineStore.engineOrVoiceDirectory(AppConfig.ttsEngine)
        }.getOrNull()
        val engineVoices = engine?.effectiveVoices()
            ?.mapNotNull { voice ->
                val name = voice.name.takeIf { it.isNotBlank() } ?: voice.id
                if (voice.id.isBlank()) null
                else RoleBatchPlanner.VoiceOption(engine.id, voice.id, "${engine.name} · $name")
            }
            .orEmpty()
        val fallback = appDb.bookRoleDao.getAllBindingsGlobal()
            .asSequence()
            .filter { it.speakerId.isNotBlank() }
            .distinctBy { "${it.engineId}|${it.speakerId}" }
            .map {
                RoleBatchPlanner.VoiceOption(
                    engineId = it.engineId,
                    speakerId = it.speakerId,
                    label = voiceLabel(it.engineId, it.speakerId),
                )
            }
            .toList()
        return RoleBatchPlanner.resolveVoiceSources(engineVoices, fallback)
    }

    private fun voiceLabel(engineId: String, speakerId: String): String {
        val engine = runCatching {
            TtsEngineStore.engineOrVoiceDirectory(engineId)
        }.getOrNull() ?: return speakerId
        val name = engine.effectiveVoices()
            .firstOrNull { it.id == speakerId }?.name
            ?.takeIf { it.isNotBlank() }
            ?: speakerId
        return "${engine.name} · $name"
    }

    private fun showVoiceSync(row: RoleLibraryRow) {
        if (row.targetType != BookTtsVoiceBinding.TargetType.CHARACTER) return
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { loadVoiceSyncData(row) }
            val binding = data.plan.sourceBinding
            if (binding == null || binding.speakerId.isBlank()) {
                toastOnUi(R.string.role_voice_sync_no_source)
                return@launch
            }
            if (data.plan.candidates.isEmpty()) {
                toastOnUi(R.string.role_voice_sync_no_targets)
                return@launch
            }
            val dialogBinding = DialogRoleVoiceSyncBinding.inflate(layoutInflater)
            dialogBinding.tvDesc.text = getString(R.string.role_voice_sync_desc, row.name)
            dialogBinding.tvTargets.text = buildString {
                data.plan.candidates.forEach { candidate ->
                    val title = data.bookTitles[candidate.workKey]
                        ?: getString(R.string.role_library_unknown_book)
                    append("《").append(title).append("》")
                    append(candidate.name)
                    append("  ")
                    append(
                        getString(
                            if (candidate.hasExistingBinding) {
                                R.string.role_voice_sync_has_binding
                            } else {
                                R.string.role_voice_sync_new_binding
                            }
                        )
                    )
                    append('\n')
                }
            }
            alert(title = getString(R.string.role_voice_sync_title)) {
                customView { dialogBinding.root }
                positiveButton(R.string.role_voice_sync_apply) {
                    val overwrite = dialogBinding.rbOverwrite.isChecked
                    lifecycleScope.launch {
                        when (val outcome = RoleVoiceSyncApplier.apply(data.plan, overwrite)) {
                            is RoleVoiceSyncApplier.Outcome.Success -> {
                                loadRows()
                                alert(title = getString(R.string.role_voice_sync_done)) {
                                    setMessage(
                                        getString(
                                            R.string.role_voice_sync_summary,
                                            outcome.counts.added,
                                            outcome.counts.overwritten,
                                            outcome.counts.skipped,
                                        )
                                    )
                                    positiveButton(R.string.ok)
                                }
                            }

                            is RoleVoiceSyncApplier.Outcome.Failure ->
                                toastOnUi(outcome.reason)
                        }
                    }
                }
                negativeButton(R.string.cancel)
            }
        }
    }

    private fun loadVoiceSyncData(row: RoleLibraryRow): VoiceSyncData {
        val dao = appDb.bookRoleDao
        val books = appDb.bookDao.all.associateBy {
            BookTtsAutomationConfig.workKeyOf(it.name, it.author)
        }
        val source = RoleVoiceSyncPlanner.Source(
            workKey = row.workKey,
            targetType = row.targetType,
            roleId = row.targetId,
            name = row.name,
            aliases = row.aliases,
        )
        val formal = dao.getAllBookRolesGlobal().map {
            RoleVoiceSyncPlanner.FormalRole(
                workKey = it.workKey,
                targetType = BookTtsVoiceBinding.TargetType.CHARACTER,
                roleId = it.roleId,
                name = it.name,
                aliases = parseAliases(it.aliasesJson),
            )
        }
        val bindings = dao.getAllBindingsGlobal().map {
            RoleVoiceSyncPlanner.Binding(
                workKey = it.workKey,
                targetType = it.targetType,
                targetId = it.targetId,
                engineId = it.engineId,
                speakerId = it.speakerId,
                bindingMode = it.bindingMode,
            )
        }
        return VoiceSyncData(
            plan = RoleVoiceSyncPlanner.plan(source, formal, bindings),
            bookTitles = books.mapValues { it.value.name },
        )
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
                            "${getString(R.string.ai_roles_formal)}: ${it.name}",
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
                                "${getString(R.string.ai_roles_temporary)}: ${it.name}",
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
                toastOnUi(R.string.role_library_no_merge_targets)
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

    private fun exportArchive(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val archive = RoleArchiveExporter.build()
                val json = RoleArchiveCodec.encode(archive)
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法写入所选文件")
                }
                archive
            }.onSuccess { archive ->
                val roleCount = archive.books.sumOf {
                    it.formalRoles.size + it.castRoles.size
                }
                toastOnUi(
                    getString(
                        R.string.role_library_export_success,
                        archive.books.size,
                        roleCount,
                    )
                )
            }.onFailure {
                toastOnUi(
                    getString(
                        R.string.role_library_export_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                )
            }
        }
    }

    private fun importArchive(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val raw = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("无法读取所选文件")
                }
                val archive = RoleArchiveCodec.decode(raw).getOrThrow()
                val roleCount = archive.books.sumOf {
                    it.formalRoles.size + it.castRoles.size
                }
                if (roleCount == 0) error(getString(R.string.role_library_import_empty))
                RoleArchiveImporter.import(archive)
            }.onSuccess { result ->
                loadRows()
                alert(title = getString(R.string.role_library_import_done)) {
                    setMessage(
                        getString(
                            R.string.role_library_import_summary,
                            result.newRoles,
                            result.skipped,
                            result.bindings,
                        )
                    )
                    positiveButton(R.string.ok)
                }
            }.onFailure {
                toastOnUi(
                    getString(
                        R.string.role_library_import_failed,
                        it.message ?: it.javaClass.simpleName,
                    )
                )
            }
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
