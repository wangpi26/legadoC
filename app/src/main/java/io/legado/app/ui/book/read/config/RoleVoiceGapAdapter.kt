package io.legado.app.ui.book.read.config

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.databinding.ItemRoleVoiceGapBinding
import io.legado.app.help.roles.RoleVoiceGapPlanner

/** 「待配音」页签的一行: 一本书 + 已配音进度 + 前几个未配音角色. */
data class RoleVoiceGapRow(
    val workKey: String,
    val bookTitle: String,
    val progress: String,
    val roles: String,
    val unboundRoles: List<RoleVoiceGapPlanner.UnboundRole>,
)

class RoleVoiceGapAdapter(
    private val onClick: (RoleVoiceGapRow) -> Unit,
) : ListAdapter<RoleVoiceGapRow, RoleVoiceGapAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ItemRoleVoiceGapBinding.inflate(
                android.view.LayoutInflater.from(parent.context),
                parent,
                false,
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRoleVoiceGapBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: RoleVoiceGapRow) {
            binding.tvBook.text = binding.root.context.getString(
                io.legado.app.R.string.role_voice_gap_book,
                row.bookTitle,
            )
            binding.tvProgress.text = row.progress
            binding.tvRoles.text = row.roles
            binding.root.setOnClickListener { onClick(row) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<RoleVoiceGapRow>() {
        override fun areItemsTheSame(oldItem: RoleVoiceGapRow, newItem: RoleVoiceGapRow): Boolean =
            oldItem.workKey == newItem.workKey

        override fun areContentsTheSame(oldItem: RoleVoiceGapRow, newItem: RoleVoiceGapRow): Boolean =
            oldItem == newItem
    }
}
