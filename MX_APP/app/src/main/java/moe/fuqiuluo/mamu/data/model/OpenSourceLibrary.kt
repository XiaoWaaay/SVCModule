package moe.fuqiuluo.mamu.data.model

/**
 * 开源库信息数据类
 *
 * @property name 库名称
 * @property description 简短描述
 * @property license 许可证
 * @property url 项目链接
 */
data class OpenSourceLibrary(
    val name: String,
    val description: String,
    val license: String,
    val url: String
)

/**
 * 开源库分类
 *
 * @property categoryName 分类名称
 * @property libraries 该分类下的库列表
 */
data class LibraryCategory(
    val categoryName: String,
    val libraries: List<OpenSourceLibrary>
)

/**
 * 特别感谢的人员/项目
 *
 * @property name 项目/人员名称
 * @property description 贡献描述
 * @property url 链接（可选）
 */
data class Acknowledgment(
    val name: String,
    val description: String,
    val url: String?
)
