package moe.fuqiuluo.mamu.floating.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.floating.data.model.SavedAddress
import moe.fuqiuluo.mamu.databinding.ItemSavedAddressBinding
import moe.fuqiuluo.mamu.floating.data.local.MemoryBackupManager
import moe.fuqiuluo.mamu.floating.data.model.DisplayValueType

class SavedAddressAdapter(
    private val onItemClick: (SavedAddress, Int) -> Unit = { _, _ -> },
    private val onItemLongClick: (SavedAddress, Int) -> Boolean = { _, _ -> false },
    private val onFreezeToggle: (SavedAddress, Boolean) -> Unit = { _, _ -> },
    private val onItemDelete: (SavedAddress) -> Unit = { _ -> },
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<SavedAddressAdapter.ViewHolder>() {

    private val addresses = mutableListOf<SavedAddress>()

    // 使用标志位 + 例外集合，避免Select All/Invert Selection时 O(n) 的集合操作
    // isAllSelected=true 时: 所有位置默认选中，deselectedPositions 存储Cancel选择的位置
    // isAllSelected=false 时: 所有位置默认不选中，selectedPositions 存储选中的位置
    private var isAllSelected = false
    private val selectedPositions = IntOpenHashSet()    // isAllSelected=false 时使用
    private val deselectedPositions = IntOpenHashSet()  // isAllSelected=true 时使用

    init {
        // 启用稳定ID，提升RecyclerView刷新性能
        setHasStableIds(true)
    }

    /**
     * 检查某个位置是否被选中 - O(1) 操作
     */
    private fun isPositionSelected(position: Int): Boolean {
        return if (isAllSelected) {
            position !in deselectedPositions
        } else {
            position in selectedPositions
        }
    }

    /**
     * 获取当前选中数量 - O(1) 操作
     */
    private fun getSelectedCount(): Int {
        return if (isAllSelected) {
            addresses.size - deselectedPositions.size
        } else {
            selectedPositions.size
        }
    }

    /**
     * 切换某个位置的选中状态
     */
    private fun toggleSelection(position: Int, selected: Boolean) {
        if (isAllSelected) {
            // Select All模式下，操作 deselectedPositions
            if (selected) {
                deselectedPositions.remove(position)
            } else {
                deselectedPositions.add(position)
            }
        } else {
            // 非Select All模式下，操作 selectedPositions
            if (selected) {
                selectedPositions.add(position)
            } else {
                selectedPositions.remove(position)
            }
        }
    }

    fun setAddresses(newAddresses: List<SavedAddress>) {
        val oldSize = addresses.size
        addresses.clear()
        // Reset选择状态
        isAllSelected = false
        selectedPositions.clear()
        deselectedPositions.clear()

        if (oldSize > 0) {
            notifyItemRangeRemoved(0, oldSize)
        }

        // 按Address大小排序
        addresses.addAll(newAddresses.sortedBy { it.address })
        if (addresses.isNotEmpty()) {
            notifyItemRangeInserted(0, addresses.size)
        }
        onSelectionChanged(0)
    }

    /**
     * 更新Address列表，保留已选中Address的选择状态
     */
    fun updateAddresses(newAddresses: List<SavedAddress>) {
        // 保存当前选中的Address
        val selectedAddressSet = if (isAllSelected) {
            // Select All模式：所有Address减去Cancel选择的
            addresses.indices
                .filter { it !in deselectedPositions }
                .map { addresses[it].address }
                .toSet()
        } else {
            // 手动选择模式：选中的位置对应的Address
            selectedPositions.mapNotNull { addresses.getOrNull(it)?.address }.toSet()
        }

        val oldSize = addresses.size
        addresses.clear()

        if (oldSize > 0) {
            notifyItemRangeRemoved(0, oldSize)
        }

        // 按Address大小排序
        addresses.addAll(newAddresses.sortedBy { it.address })
        
        // Restore选择状态
        isAllSelected = false
        selectedPositions.clear()
        deselectedPositions.clear()
        
        addresses.forEachIndexed { index, addr ->
            if (addr.address in selectedAddressSet) {
                selectedPositions.add(index)
            }
        }

        if (addresses.isNotEmpty()) {
            notifyItemRangeInserted(0, addresses.size)
        }
        onSelectionChanged(getSelectedCount())
    }

    fun addAddress(address: SavedAddress) {
        addresses.add(address)
        notifyItemInserted(addresses.size - 1)
    }

    fun updateAddress(address: SavedAddress) {
        val index = addresses.indexOfFirst { it.address == address.address }
        if (index >= 0) {
            addresses[index] = address
            notifyItemChanged(index)
        }
    }

    fun getSelectedItems(): List<SavedAddress> {
        return if (isAllSelected) {
            addresses.indices
                .filter { it !in deselectedPositions }
                .map { addresses[it] }
        } else {
            selectedPositions.map { addresses[it] }
        }
    }

    /**
     * Select All - O(1) 操作，只设置标志位
     */
    fun selectAll() {
        if (isAllSelected && deselectedPositions.isEmpty()) {
            // 已经Select All，无需操作
            return
        }

        // O(1) 操作：只设置标志位并清空例外集合
        isAllSelected = true
        selectedPositions.clear()
        deselectedPositions.clear()

        // 通知可见项更新（RecyclerView只会更新可见的ViewHolder）
        notifyItemRangeChanged(0, addresses.size, PAYLOAD_SELECTION_CHANGED)
        onSelectionChanged(addresses.size)
    }

    /**
     * 全不选 - O(1) 操作，只设置标志位
     */
    fun deselectAll() {
        if (!isAllSelected && selectedPositions.isEmpty()) {
            // 已经是全不选状态，直接返回
            return
        }

        // O(1) 操作：只设置标志位并清空例外集合
        isAllSelected = false
        selectedPositions.clear()
        deselectedPositions.clear()

        notifyItemRangeChanged(0, addresses.size, PAYLOAD_SELECTION_CHANGED)
        onSelectionChanged(0)
    }

    /**
     * Invert Selection - O(1) 操作，只切换标志位并交换集合
     */
    fun invertSelection() {
        // O(1) 操作：切换Select All标志位，交换两个集合的角色
        isAllSelected = !isAllSelected

        // 交换 selectedPositions 和 deselectedPositions
        val temp = IntOpenHashSet(selectedPositions)
        selectedPositions.clear()
        selectedPositions.addAll(deselectedPositions)
        deselectedPositions.clear()
        deselectedPositions.addAll(temp)

        notifyItemRangeChanged(0, addresses.size, PAYLOAD_SELECTION_CHANGED)
        onSelectionChanged(getSelectedCount())
    }

    companion object {
        private const val PAYLOAD_SELECTION_CHANGED = "selection_changed"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSavedAddressBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(addresses[position], position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            for (payload in payloads) {
                if (payload == PAYLOAD_SELECTION_CHANGED) {
                    holder.updateSelection(position)
                }
            }
        }
    }

    override fun getItemCount(): Int = addresses.size

    override fun getItemId(position: Int): Long {
        // 使用Address作为稳定ID，帮助RecyclerView优化刷新性能
        return addresses[position].address
    }

    inner class ViewHolder(
        private val binding: ItemSavedAddressBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        @SuppressLint("SetTextI18n")
        fun bind(address: SavedAddress, position: Int) {
            val context = binding.root.context

            // 设置 checkbox 状态 - 使用 isPositionSelected() 支持Select All标志位
            val isSelected = isPositionSelected(position)
            binding.checkbox.setOnCheckedChangeListener(null)
            binding.checkbox.isChecked = isSelected
            updateItemBackground(isSelected)
            binding.checkbox.setOnCheckedChangeListener { _, isChecked ->
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                    toggleSelection(pos, isChecked)
                    updateItemBackground(isChecked)
                    onSelectionChanged(getSelectedCount())
                }
            }

            // 设置变量名称
            binding.nameText.text = address.name

            // 设置Address（大写，无0x前缀）
            // 窄屏布局（sw < 390dp）时Address后加冒号，宽屏布局时不加
            val smallestWidth = context.resources.configuration.smallestScreenWidthDp
            val addressFormat = if (smallestWidth < 390) "%X:" else "%X"
            binding.addressText.text = String.format(addressFormat, address.address)

            // 设置数据类型和Range
            val valueType = address.displayValueType ?: DisplayValueType.DWORD
            binding.typeText.text = valueType.code
            binding.typeText.setTextColor(valueType.textColor)
            binding.rangeText.text = address.range.code
            binding.rangeText.setTextColor(address.range.color)

            // 设置值（颜色根据类型显示）
            binding.valueText.text = address.value.ifBlank { "Empty" }
            binding.valueText.setTextColor(valueType.textColor)

            // 备份值（旧值）
            val backup = MemoryBackupManager.getBackup(address.address)
            if (backup != null) {
                binding.backupValueText.text = "(${backup.originalValue})"
                binding.backupValueText.visibility = View.VISIBLE
            } else {
                binding.backupValueText.visibility = View.GONE
            }

            // 设置冻结按钮状态
            binding.freezeButton.apply {
                if (address.isFrozen) {
                    setIconResource(R.drawable.icon_play_arrow_24px)
                } else {
                    setIconResource(R.drawable.icon_pause_24px)
                }

                setOnClickListener {
                    val newFrozenState = !address.isFrozen
                    address.isFrozen = newFrozenState
                    // 立即更新UI
                    if (newFrozenState) {
                        setIconResource(R.drawable.icon_play_arrow_24px)
                    } else {
                        setIconResource(R.drawable.icon_pause_24px)
                    }
                    onFreezeToggle(address, newFrozenState)
                }
            }

            // 设置Delete按钮
            binding.deleteButton.setOnClickListener {
                onItemDelete(address)
            }

            // 设置点击事件
            binding.itemContainer.setOnClickListener {
                onItemClick(address, position)
            }

            // 设置长按事件
            binding.itemContainer.setOnLongClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                    onItemLongClick(addresses[pos], pos)
                } ?: false
            }
        }

        fun updateSelection(position: Int) {
            val isSelected = isPositionSelected(position)
            binding.checkbox.apply {
                setOnCheckedChangeListener(null)
                isChecked = isSelected
                updateItemBackground(isSelected)
                setOnCheckedChangeListener { _, isChecked ->
                    bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let { pos ->
                        toggleSelection(pos, isChecked)
                        updateItemBackground(isChecked)
                        onSelectionChanged(getSelectedCount())
                    }
                }
            }
        }

        private fun updateItemBackground(isSelected: Boolean) {
            // 使用前景色来显示选中状态，保留背景的涟漪效果
            binding.itemContainer.foreground = if (isSelected) {
                android.graphics.drawable.ColorDrawable(0x33448AFF)
            } else {
                null
            }
        }
    }
}
