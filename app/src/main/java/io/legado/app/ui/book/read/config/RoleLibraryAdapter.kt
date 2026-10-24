package io.legado.app.ui.book.read.config

import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.databinding.ItemRoleLibraryBinding

/** 跨书角色库行: 正式角色与临时角色共用. */
data class RoleLibraryRow(
    val targetType: String,
    val targetId: Long,
    val workKey: String,
    val bookTitle: String,
    val name: String,
    val aliases: List<String>,
    val gender: String,
    val detail: String,
    val identityState: String,
    val occurrenceCount: Int,
)

class RoleLibraryAdapter(
    private val onClick: (RoleLibraryRow) -> Unit,
) : ListAdapter<RoleLibraryRow, RoleLibraryAdapter.ViewHolder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemRoleLibraryBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        ))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemRoleLibraryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: RoleLibraryRow) {
            binding.tvName.text = row.name
            binding.tvDetail.text = row.detail
            binding.tvBook.text = "《${row.bookTitle}》"
            binding.root.setOnClickListener { onClick(row) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<RoleLibraryRow>() {
        override fun areItemsTheSame(oldItem: RoleLibraryRow, newItem: RoleLibraryRow): Boolean =
            oldItem.targetType == newItem.targetType && oldItem.targetId == newItem.targetId

        override fun areContentsTheSame(oldItem: RoleLibraryRow, newItem: RoleLibraryRow): Boolean =
            oldItem == newItem
    }
}
