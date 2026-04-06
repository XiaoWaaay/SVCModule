package moe.fuqiuluo.mamu.floating.dialog

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.R
import moe.fuqiuluo.mamu.data.settings.dialogTransparencyEnabled
import moe.fuqiuluo.mamu.databinding.DialogSearchProgressBinding
import moe.fuqiuluo.mamu.data.settings.getDialogOpacity
import kotlin.math.max
import kotlin.random.Random

/**
 * Search进度数据
 * 对应native层的共享Memory结构（20字节）
 */
data class SearchProgressData(
    val currentProgress: Int,      // 0-100
    val regionsOrAddrsSearched: Int,       // 已Search的区域数/Address数
    val totalFound: Long,           // 当前找To的结果数
    val heartbeat: Int              // 心跳随机数（用于检测是否卡死）
)

/**
 * Search进度对话框
 * 显示实时Search进度（通过共享MemoryFromnative层读取）
 */
class SearchProgressDialog(
    context: Context,
    private val isRefineSearch: Boolean,
    private val onCancelClick: (() -> Unit)? = null,
    private val onHideClick: (() -> Unit)? = null
) : BaseDialog(context) {
    private lateinit var binding: DialogSearchProgressBinding

    @SuppressLint("SetTextI18n")
    override fun setupDialog() {
        binding = DialogSearchProgressBinding.inflate(LayoutInflater.from(dialog.context))
        dialog.setContentView(binding.root)
        dialog.setCancelable(false)

        // 应用透明度设置
        val mmkv = MMKV.defaultMMKV()
        val opacity = if (mmkv.dialogTransparencyEnabled) {
            max(mmkv.getDialogOpacity(), 0.95f)
        } else {
            1.0f
        }
        binding.root.background?.alpha = (opacity * 255).toInt()

        // 随机显示一个萌系Title
        binding.progressTitle.text = MOE_TITLES.random()

        if (isRefineSearch) {
            binding.tvCounter.setText(R.string.address_searched)
        }

        // 设置Cancel按钮点击事件
        binding.btnCancel.setOnClickListener {
            onCancelClick?.invoke()
        }

        // 设置隐藏按钮点击事件
        binding.btnHide.setOnClickListener {
            onHideClick?.invoke()
        }

        // 初始状态
        updateProgress(SearchProgressData(0, 0, 0, 0))
    }

    /**
     * 更新进度显示
     */
    @SuppressLint("SetTextI18n", "DefaultLocale")
    fun updateProgress(data: SearchProgressData) {
        if (!::binding.isInitialized) return

        binding.progressBar.progress = data.currentProgress
        binding.tvProgress.text = "${data.currentProgress}%"
        binding.tvRegions.text = "${data.regionsOrAddrsSearched}"
        binding.tvResults.text = String.format("%,d", data.totalFound)
        binding.progressTitle.text = MOE_TITLES.random(Random(data.heartbeat))
    }
}

private val MOE_TITLES = arrayOf(
    "Searching...",
    "Looking for the target~",
    "Digging through memory ( •̀ ω •́ )✧",
    "Sniffing data...",
    "Summoning memory sprites ✨",
    "Data hunter deployed!",
    "Tracking target (๑•̀ㅂ•́)و✧",
    "Memory adventure begins!",
    "Decoding mysterious bytes...",
    "Digging for treasure ~⛏️",
    "Data detective at work 🔍",
    "Scanning the galaxy...",
    "Tracing the bitstream...",
    "Memory minesweeper in progress 💣",
    "Cracking the code...",
    "Searching for key clues 🎯",
    "Performing data archaeology...",
    "Memory expedition launched! 🚀",
    "Following the data trail...",
    "Putting the puzzle together 🧩",
    "Interrogating XIN!!!",
    "Keep going, girl.....",
    "The other dimension is on the way.....",
)