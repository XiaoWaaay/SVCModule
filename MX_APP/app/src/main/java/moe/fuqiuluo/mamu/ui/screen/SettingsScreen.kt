package moe.fuqiuluo.mamu.ui.screen

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tencent.mmkv.MMKV
import moe.fuqiuluo.mamu.data.settings.autoStartFloatingWindow
import moe.fuqiuluo.mamu.ui.theme.AdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.theme.AppTheme
import moe.fuqiuluo.mamu.ui.theme.DarkMode
import moe.fuqiuluo.mamu.ui.theme.Dimens
import moe.fuqiuluo.mamu.ui.theme.ThemeManager
import moe.fuqiuluo.mamu.ui.theme.rememberAdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.tutorial.TutorialManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateBack: () -> Unit,
    onShowAbout: () -> Unit
) {
    val adaptiveLayout = rememberAdaptiveLayoutInfo(windowSizeClass)
    BackHandler(onBack = onNavigateBack)

    val useDynamicColor by ThemeManager.useDynamicColor.collectAsState()
    val currentTheme by ThemeManager.currentTheme.collectAsState()
    val darkMode by ThemeManager.darkMode.collectAsState()
    var showThemeDialog by remember { mutableStateOf(false) }
    var showDarkModeDialog by remember { mutableStateOf(false) }
    var showTutorialResetDialog by remember { mutableStateOf(false) }

    val mmkv = remember { MMKV.defaultMMKV() }
    var autoStartFloating by remember { mutableStateOf(mmkv.autoStartFloatingWindow) }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            adaptiveLayout = adaptiveLayout,
            currentTheme = currentTheme,
            onThemeSelected = { theme ->
                ThemeManager.setTheme(theme)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showDarkModeDialog) {
        DarkModeSelectionDialog(
            adaptiveLayout = adaptiveLayout,
            currentMode = darkMode,
            onModeSelected = { mode ->
                ThemeManager.setDarkMode(mode)
                showDarkModeDialog = false
            },
            onDismiss = { showDarkModeDialog = false }
        )
    }

    if (showTutorialResetDialog) {
        AlertDialog(
            onDismissRequest = { showTutorialResetDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = null
                )
            },
            title = { Text("Restart Tutorial") },
            text = { Text("The tutorial has been reset. It will appear again when you return to the home screen.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        TutorialManager.resetTutorial()
                        showTutorialResetDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text("Return Home")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTutorialResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            ) {
                // GeneralSettings分组
                SettingsGroup(
                    adaptiveLayout = adaptiveLayout,
                    title = "General"
                ) {
                    SettingsSwitchItem(
                        adaptiveLayout = adaptiveLayout,
                        icon = Icons.Default.Window,
                        title = "Auto-show Overlay",
                        description = "Automatically show the overlay when the app starts",
                        checked = autoStartFloating,
                        onCheckedChange = { enabled ->
                            autoStartFloating = enabled
                            mmkv.autoStartFloatingWindow = enabled
                        }
                    )

                    SettingsClickableItem(
                        adaptiveLayout = adaptiveLayout,
                        icon = Icons.Default.School,
                        title = "Replay Tutorial",
                        description = "Show the beginner tutorial again",
                        onClick = { showTutorialResetDialog = true }
                    )

                    SettingsClickableItem(
                        adaptiveLayout = adaptiveLayout,
                        icon = Icons.Default.Info,
                        title = "About Mamu",
                        description = "View version info, open-source dependencies, and acknowledgements",
                        onClick = {
                            onNavigateBack()
                            onShowAbout()
                        }
                    )
                }

                // AppearanceSettings分组
                SettingsGroup(
                    adaptiveLayout = adaptiveLayout,
                    title = "Appearance"
                ) {
                    SettingsClickableItem(
                        adaptiveLayout = adaptiveLayout,
                        icon = Icons.Default.Brightness6,
                        title = "Dark Mode",
                        description = when (darkMode) {
                            DarkMode.FOLLOW_SYSTEM -> "Follow System"
                            DarkMode.LIGHT -> "Light"
                            DarkMode.DARK -> "Dark"
                        },
                        onClick = { showDarkModeDialog = true }
                    )

                    SettingsClickableItem(
                        adaptiveLayout = adaptiveLayout,
                        icon = Icons.Default.ColorLens,
                        title = "Theme",
                        description = stringResource(currentTheme.displayNameRes),
                        onClick = { showThemeDialog = true }
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        SettingsSwitchItem(
                            adaptiveLayout = adaptiveLayout,
                            icon = Icons.Default.Palette,
                            title = "Dynamic Colors",
                            description = "Generate theme colors automatically from the wallpaper",
                            checked = useDynamicColor,
                            onCheckedChange = { ThemeManager.setUseDynamicColor(it) }
                        )
                    } else {
                        SettingsInfoItem(
                            adaptiveLayout = adaptiveLayout,
                            icon = Icons.Default.Palette,
                            title = "Dynamic Colors",
                            description = "Requires Android 12 or later"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsGroup(
    adaptiveLayout: AdaptiveLayoutInfo,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                horizontal = Dimens.paddingLg(adaptiveLayout),
                vertical = Dimens.paddingSm(adaptiveLayout)
            ),
            fontWeight = FontWeight.Bold
        )
        content()
    }
}

@Composable
fun SettingsSwitchItem(
    adaptiveLayout: AdaptiveLayoutInfo,
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.paddingLg(adaptiveLayout),
                vertical = Dimens.paddingMd(adaptiveLayout)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.iconMd(adaptiveLayout))
        )
        Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingsInfoItem(
    adaptiveLayout: AdaptiveLayoutInfo,
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimens.paddingLg(adaptiveLayout),
                vertical = Dimens.paddingMd(adaptiveLayout)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.iconMd(adaptiveLayout))
        )
        Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsClickableItem(
    adaptiveLayout: AdaptiveLayoutInfo,
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = Dimens.paddingLg(adaptiveLayout),
                vertical = Dimens.paddingMd(adaptiveLayout)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.iconMd(adaptiveLayout))
        )
        Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Dialog 使用响应式尺寸
@Composable
fun ThemeSelectionDialog(
    adaptiveLayout: AdaptiveLayoutInfo,
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Theme") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                AppTheme.entries.forEach { theme ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeSelected(theme) }
                            .padding(vertical = Dimens.spacingMd(adaptiveLayout)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = theme == currentTheme,
                            onClick = { onThemeSelected(theme) }
                        )
                        Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                        Column {
                            Text(
                                text = stringResource(theme.displayNameRes),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (theme == currentTheme) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                text = stringResource(theme.descriptionRes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DarkModeSelectionDialog(
    adaptiveLayout: AdaptiveLayoutInfo,
    currentMode: DarkMode,
    onModeSelected: (DarkMode) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        DarkMode.FOLLOW_SYSTEM to "Follow System",
        DarkMode.LIGHT to "Light",
        DarkMode.DARK to "Dark"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Dark Mode") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModeSelected(mode) }
                            .padding(vertical = Dimens.spacingMd(adaptiveLayout)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == currentMode,
                            onClick = { onModeSelected(mode) }
                        )
                        Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (mode == currentMode) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
