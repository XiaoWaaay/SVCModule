package moe.fuqiuluo.mamu.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import moe.fuqiuluo.mamu.data.model.Acknowledgment
import moe.fuqiuluo.mamu.data.model.LibraryCategory
import moe.fuqiuluo.mamu.data.model.OpenSourceLibrary
import moe.fuqiuluo.mamu.ui.theme.AdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.theme.Dimens
import moe.fuqiuluo.mamu.ui.theme.rememberAdaptiveLayoutInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit
) {
    val adaptiveLayout = rememberAdaptiveLayoutInfo(windowSizeClass)
    BackHandler(onBack = onNavigateBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About Mamu") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(
                        max = when (adaptiveLayout.windowSizeClass.widthSizeClass) {
                            WindowWidthSizeClass.Compact -> adaptiveLayout.contentMaxWidth
                            else -> 720.dp // 横屏时使用更宽的最大宽度
                        }
                    )
                    .fillMaxWidth()
                    .padding(paddingValues)
                    .padding(Dimens.paddingLg(adaptiveLayout))
            ) {
                // 项目信息卡片
                ProjectInfoCard(adaptiveLayout)
                Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))

                // 开源依赖分类
                getLibraryCategories().forEach { category ->
                    LibraryCategoryCard(
                        adaptiveLayout = adaptiveLayout,
                        category = category
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))
                }

                // Special Thanks
                AcknowledgmentCard(
                    adaptiveLayout = adaptiveLayout,
                    acknowledgments = getAcknowledgments()
                )
                Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))

                // Version Information
                VersionInfoCard(adaptiveLayout)

                // 底部间距
                Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))
            }
        }
    }
}

@Composable
private fun ProjectInfoCard(adaptiveLayout: AdaptiveLayoutInfo) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Dimens.paddingLg(adaptiveLayout)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 应用图标
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))

            Text(
                text = "Mamu",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Version 1.0.0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))

            Text(
                text = "Android memory debugging tool powered by root permissions",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))

            // GitHub 链接按钮
            OutlinedButton(
                onClick = {
                    openUrl(context, "https://github.com/Shirasuki/MX")
                }
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.iconSm(adaptiveLayout))
                )
                Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                Text("GitHub Repository")
            }

            Spacer(modifier = Modifier.height(Dimens.spacingSm(adaptiveLayout)))

            Text(
                text = "Built on top of the android-wuwa project",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )

            // 许可证信息
            Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Gavel,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.iconSm(adaptiveLayout)),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                Text(
                    text = "GNU GPL v3.0 License",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
private fun LibraryCategoryCard(
    adaptiveLayout: AdaptiveLayoutInfo,
    category: LibraryCategory
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(Dimens.paddingLg(adaptiveLayout))) {
            // Title行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = category.categoryName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${category.libraries.size} dependencies",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // 可Collapse内容
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = Dimens.spacingMd(adaptiveLayout))) {
                    category.libraries.forEach { library ->
                        LibraryItem(
                            adaptiveLayout = adaptiveLayout,
                            library = library
                        )
                        if (library != category.libraries.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(
                                    vertical = Dimens.spacingSm(adaptiveLayout)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryItem(
    adaptiveLayout: AdaptiveLayoutInfo,
    library: OpenSourceLibrary
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openUrl(context, library.url) }
            .padding(vertical = Dimens.spacingXs(adaptiveLayout))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = library.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )

            AssistChip(
                onClick = { openUrl(context, library.url) },
                label = {
                    Text(
                        text = library.license,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))

        Text(
            text = library.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AcknowledgmentCard(
    adaptiveLayout: AdaptiveLayoutInfo,
    acknowledgments: List<Acknowledgment>
) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Dimens.paddingLg(adaptiveLayout))) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm(adaptiveLayout))
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = "Special Thanks",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))

            acknowledgments.forEach { ack ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = ack.url != null) {
                            ack.url?.let { openUrl(context, it) }
                        }
                        .padding(vertical = Dimens.spacingSm(adaptiveLayout)),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ack.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = ack.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (ack.url != null) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.iconSm(adaptiveLayout)),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (ack != acknowledgments.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(
                            vertical = Dimens.spacingSm(adaptiveLayout)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionInfoCard(adaptiveLayout: AdaptiveLayoutInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Dimens.paddingLg(adaptiveLayout))) {
            Text(
                text = "Version Information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))

            InfoRow(
                adaptiveLayout = adaptiveLayout,
                label = "App Version",
                value = "1.0.0 (1)"
            )

            InfoRow(
                adaptiveLayout = adaptiveLayout,
                label = "Build Type",
                value = "Debug"
            )

            InfoRow(
                adaptiveLayout = adaptiveLayout,
                label = "Target Architecture",
                value = "ARM64-v8a"
            )
        }
    }
}

@Composable
private fun InfoRow(
    adaptiveLayout: AdaptiveLayoutInfo,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.spacingXs(adaptiveLayout)),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 打开 URL 的ToolsFunctions
 */
private fun openUrl(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Unable to open link", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 获取开源库分类列表
 */
private fun getLibraryCategories(): List<LibraryCategory> {
    return listOf(
        LibraryCategory("UI Framework", listOf(
            OpenSourceLibrary("Jetpack Compose", "Modern declarative Android UI", "Apache 2.0", "https://developer.android.com/jetpack/compose"),
            OpenSourceLibrary("Material Design 3", "Google Material Design component library", "Apache 2.0", "https://m3.material.io/"),
            OpenSourceLibrary("Material Icons Extended", "Material Design icon library", "Apache 2.0", "https://developer.android.com/")
        )),
        LibraryCategory("Android Core", listOf(
            OpenSourceLibrary("AndroidX Core KTX", "Android core extension library", "Apache 2.0", "https://developer.android.com/kotlin/ktx"),
            OpenSourceLibrary("AndroidX Lifecycle", "Lifecycle-aware components", "Apache 2.0", "https://developer.android.com/"),
            OpenSourceLibrary("AndroidX Activity Compose", "Activity Compose integration", "Apache 2.0", "https://developer.android.com/")
        )),
        LibraryCategory("Data Storage", listOf(
            OpenSourceLibrary("MMKV", "Tencent high-performance key-value storage", "BSD", "https://github.com/Tencent/MMKV")
        )),
        LibraryCategory("Async and Concurrency", listOf(
            OpenSourceLibrary("Kotlin Coroutines", "Kotlin coroutines library", "Apache 2.0", "https://kotlinlang.org/docs/coroutines-overview.html")
        )),
        LibraryCategory("Root Management", listOf(
            OpenSourceLibrary("libsu", "topjohnwu's Root Shell library", "Apache 2.0", "https://github.com/topjohnwu/libsu")
        )),
        LibraryCategory("Traditional View Components", listOf(
            OpenSourceLibrary("AppCompat", "Android compatibility library", "Apache 2.0", "https://developer.android.com/"),
            OpenSourceLibrary("RecyclerView", "High-performance list component", "Apache 2.0", "https://developer.android.com/"),
            OpenSourceLibrary("ViewPager2", "Paged swipe component", "Apache 2.0", "https://developer.android.com/"),
            OpenSourceLibrary("ConstraintLayout", "Constraint-based layout", "Apache 2.0", "https://developer.android.com/"),
            OpenSourceLibrary("CardView", "Card view", "Apache 2.0", "https://developer.android.com/")
        )),
        LibraryCategory("Tooling Libraries", listOf(
            OpenSourceLibrary("kotlin-csv", "CSV file processing", "Apache 2.0", "https://github.com/jsoizo/kotlin-csv"),
            OpenSourceLibrary("fastutil", "High-performance collections", "Apache 2.0", "https://fastutil.di.unimi.it/"),
            OpenSourceLibrary("kotlinx-io", "Kotlin IO library", "Apache 2.0", "https://github.com/Kotlin/kotlinx-io")
        )),
        LibraryCategory("Rust Core Runtime", listOf(
            OpenSourceLibrary("tokio", "Async runtime", "MIT", "https://tokio.rs/"),
            OpenSourceLibrary("nix", "Unix system calls", "MIT", "https://github.com/nix-rust/nix"),
            OpenSourceLibrary("jni", "Java Native Interface", "Apache 2.0/MIT", "https://github.com/jni-rs/jni-rs")
        )),
        LibraryCategory("Rust Data Parallelism", listOf(
            OpenSourceLibrary("rayon", "Data parallelism library", "Apache 2.0/MIT", "https://github.com/rayon-rs/rayon")
        )),
        LibraryCategory("Rust Serialization and Networking", listOf(
            OpenSourceLibrary("serde", "Serialization framework", "Apache 2.0/MIT", "https://serde.rs/"),
            OpenSourceLibrary("reqwest", "HTTP client", "Apache 2.0/MIT", "https://github.com/seanmonstar/reqwest"),
            OpenSourceLibrary("rustls", "TLS implementation", "Apache 2.0/MIT", "https://github.com/rustls/rustls")
        )),
        LibraryCategory("Rust Memory Operations", listOf(
            OpenSourceLibrary("memmap2", "Memory mapping", "Apache 2.0/MIT", "https://github.com/RazrFalcon/memmap2-rs"),
            OpenSourceLibrary("memchr", "MemorySearch", "MIT", "https://github.com/BurntSushi/memchr"),
            OpenSourceLibrary("bytemuck", "Type-safe conversions", "Zlib", "https://github.com/Lokathor/bytemuck")
        )),
        LibraryCategory("Other Rust Tools", listOf(
            OpenSourceLibrary("anyhow", "Error handling", "Apache 2.0/MIT", "https://github.com/dtolnay/anyhow"),
            OpenSourceLibrary("log", "Logging facade", "Apache 2.0/MIT", "https://github.com/rust-lang/log"),
            OpenSourceLibrary("android_logger", "Android Logs", "Apache 2.0/MIT", "https://github.com/Nercury/android_logger-rs"),
            OpenSourceLibrary("obfstr", "String obfuscation", "MIT", "https://github.com/CasualX/obfstr"),
            OpenSourceLibrary("capstone", "Disassembly engine", "BSD", "https://github.com/capstone-engine/capstone"),
            OpenSourceLibrary("lazy_static", "Lazy static variables", "Apache 2.0/MIT", "https://github.com/rust-lang-nursery/lazy-static.rs"),
            OpenSourceLibrary("rand", "Random number generation", "Apache 2.0/MIT", "https://github.com/rust-random/rand"),
            OpenSourceLibrary("zip", "ZIP compression", "MIT", "https://github.com/zip-rs/zip")
        ))
    )
}

/**
 * Get the acknowledgements list
 */
private fun getAcknowledgments(): List<Acknowledgment> {
    return listOf(
        Acknowledgment("GameGuardian", "Inspiration for Android memory modification tools", "https://gameguardian.net/"),
        Acknowledgment("Cheat Engine", "PC memory scanner and debugger", "https://www.cheatengine.org/"),
        Acknowledgment("Magisk / KernelSU", "Root access infrastructure", "https://github.com/topjohnwu/Magisk"),
        Acknowledgment("niqiuqiux's PointerScan", "C++ pointer-chain scanning implementation", "https://github.com/niqiuqiux/PointerScan"),
        Acknowledgment("android-wuwa", "Foundation of this project", "https://github.com/fuqiuluo/android-wuwa")
    )
}
