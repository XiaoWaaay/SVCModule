package moe.fuqiuluo.mamu.ui.screen

import android.content.Intent
import moe.fuqiuluo.mamu.DriverInstallActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.fuqiuluo.mamu.MainActivity
import moe.fuqiuluo.mamu.ui.theme.AdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.theme.Dimens
import moe.fuqiuluo.mamu.ui.theme.MXTheme
import moe.fuqiuluo.mamu.ui.theme.rememberAdaptiveLayoutInfo
import moe.fuqiuluo.mamu.utils.RootConfigManager
import moe.fuqiuluo.mamu.ui.viewmodel.PermissionSetupState
import moe.fuqiuluo.mamu.ui.viewmodel.PermissionSetupViewModel

private enum class SetupStep(
    val title: String,
    val description: String,
    val icon: ImageVector
) {
    CHECK_ROOT(
        title = "Check root access",
        description = "Verify that the device has root access",
        icon = Icons.Default.Security
    ),
    CONFIRM_ROOT(
        title = "Confirm root authorization",
        description = "Allow the app to use root access",
        icon = Icons.Default.VerifiedUser
    ),
    GRANT_PERMISSIONS(
        title = "Grant system permissions",
        description = "Automatically configure the required system permissions",
        icon = Icons.Default.Settings
    ),
    CHECK_DRIVER(
        title = "Check driver",
        description = "Verify that the memory driver is installed",
        icon = Icons.Default.Memory
    ),
    COMPLETED(
        title = "Setup complete",
        description = "Preparing to launch the app",
        icon = Icons.Default.TaskAlt
    )
}

private enum class StepStatus {
    PENDING,    // 未开始
    ACTIVE,     // 进行中
    COMPLETED,  // 已Completed
    ERROR       // Error
}

private data class StepState(
    val step: SetupStep,
    val status: StepStatus
)

@Composable
fun PermissionSetupScreen(
    windowSizeClass: WindowSizeClass,
    viewModel: PermissionSetupViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val navigateToDriverInstall by viewModel.navigateToDriverInstallEvent.collectAsState()
    val adaptiveLayout = rememberAdaptiveLayoutInfo(windowSizeClass)

    // 当状态为Completed时，跳Go to主界面
    LaunchedEffect(state) {
        if (state is PermissionSetupState.Completed) {
            val intent = Intent(context, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            context.startActivity(intent)
        }
    }

    // 导航To驱动安装界面
    LaunchedEffect(navigateToDriverInstall) {
        if (navigateToDriverInstall) {
            val intent = Intent(context, DriverInstallActivity::class.java)
            context.startActivity(intent)
            viewModel.resetNavigationEvent()
        }
    }

    // 启动时开始检查
    LaunchedEffect(Unit) {
        viewModel.startSetup()
    }

    // 根据当前状态计算步骤状态
    val stepStates = calculateStepStates(state)

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        when (adaptiveLayout.windowSizeClass.widthSizeClass) {
            WindowWidthSizeClass.Compact -> {
                // 竖屏布局：垂直滚动
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = adaptiveLayout.contentMaxWidth)
                            .fillMaxWidth()
                            .padding(paddingValues)
                            .padding(Dimens.paddingLg(adaptiveLayout))
                    ) {
                        // Title
                        Text(
                            text = "Permission setup",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = Dimens.spacingSm(adaptiveLayout))
                        )
                        Text(
                            text = "Follow the steps below to finish app initialization",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                bottom = Dimens.spacingXxl(adaptiveLayout)
                            )
                        )

                        // Stepper 时间线
                        VerticalStepper(
                            adaptiveLayout = adaptiveLayout,
                            steps = stepStates,
                            modifier = Modifier.padding(
                                bottom = Dimens.paddingLg(adaptiveLayout)
                            )
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(
                                vertical = Dimens.paddingLg(adaptiveLayout)
                            )
                        )

                        // 当前步骤的详细内容
                        StepContent(
                            adaptiveLayout = adaptiveLayout,
                            state = state,
                            viewModel = viewModel
                        )
                    }
                }
            }
            else -> {
                // 横屏布局：2窗格（stepper左侧 + content右侧）
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    // 左窗格：Stepper（30%宽度，独立滚动）
                    Column(
                        modifier = Modifier
                            .weight(0.3f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(Dimens.paddingLg(adaptiveLayout))
                    ) {
                        Text(
                            text = "Permission setup",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = Dimens.spacingSm(adaptiveLayout))
                        )
                        Text(
                            text = "Follow the steps below to finish app initialization",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                bottom = Dimens.spacingXxl(adaptiveLayout)
                            )
                        )

                        VerticalStepper(
                            adaptiveLayout = adaptiveLayout,
                            steps = stepStates,
                            modifier = Modifier.padding(
                                bottom = Dimens.paddingLg(adaptiveLayout)
                            )
                        )
                    }

                    // 分隔线
                    VerticalDivider()

                    // 右窗格：Content（70%宽度，独立滚动）
                    Column(
                        modifier = Modifier
                            .weight(0.7f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(Dimens.paddingLg(adaptiveLayout))
                    ) {
                        StepContent(
                            adaptiveLayout = adaptiveLayout,
                            state = state,
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun calculateStepStates(state: PermissionSetupState): List<StepState> {
    return when (state) {
        is PermissionSetupState.Initializing -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.ACTIVE),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.PENDING),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.CheckingRoot -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.ACTIVE),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.PENDING),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.NoRoot -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.ERROR),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.PENDING),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.WaitingUserConfirm -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.ACTIVE),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.GrantingPermissions -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.ACTIVE),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.ApplyingAntiKillProtection -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.ACTIVE),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.CheckingDriver -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.COMPLETED),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.ACTIVE),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.DriverNotInstalled -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.COMPLETED),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.ERROR),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )

        is PermissionSetupState.Completed -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.COMPLETED),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.COMPLETED),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.COMPLETED),
            StepState(SetupStep.COMPLETED, StepStatus.COMPLETED)
        )

        is PermissionSetupState.Error -> listOf(
            StepState(SetupStep.CHECK_ROOT, StepStatus.ERROR),
            StepState(SetupStep.CONFIRM_ROOT, StepStatus.PENDING),
            StepState(SetupStep.GRANT_PERMISSIONS, StepStatus.PENDING),
            StepState(SetupStep.CHECK_DRIVER, StepStatus.PENDING),
            StepState(SetupStep.COMPLETED, StepStatus.PENDING)
        )
    }
}

@Composable
private fun VerticalStepper(
    adaptiveLayout: AdaptiveLayoutInfo,
    steps: List<StepState>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        steps.forEachIndexed { index, stepState ->
            StepItem(
                adaptiveLayout = adaptiveLayout,
                stepState = stepState,
                showConnector = index < steps.size - 1
            )
        }
    }
}

@Composable
private fun StepItem(
    adaptiveLayout: AdaptiveLayoutInfo,
    stepState: StepState,
    showConnector: Boolean,
    modifier: Modifier = Modifier
) {
    val iconColor = when (stepState.status) {
        StepStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        StepStatus.ACTIVE -> MaterialTheme.colorScheme.primary
        StepStatus.ERROR -> MaterialTheme.colorScheme.error
        StepStatus.PENDING -> MaterialTheme.colorScheme.outline
    }

    val containerColor = when (stepState.status) {
        StepStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
        StepStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        StepStatus.ERROR -> MaterialTheme.colorScheme.errorContainer
        StepStatus.PENDING -> MaterialTheme.colorScheme.surfaceVariant
    }

    val textColor = when (stepState.status) {
        StepStatus.COMPLETED -> MaterialTheme.colorScheme.onSurface
        StepStatus.ACTIVE -> MaterialTheme.colorScheme.onSurface
        StepStatus.ERROR -> MaterialTheme.colorScheme.error
        StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val alpha by animateFloatAsState(
        targetValue = if (stepState.status == StepStatus.PENDING) 0.5f else 1f,
        animationSpec = tween(300),
        label = "step_alpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(alpha)
    ) {
        // Icon column with connector
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(Dimens.componentMd(adaptiveLayout))
        ) {
            // Step icon
            Surface(
                shape = CircleShape,
                color = containerColor,
                modifier = Modifier.size(Dimens.componentMd(adaptiveLayout))
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    when (stepState.status) {
                        StepStatus.COMPLETED -> {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Completed",
                                tint = iconColor,
                                modifier = Modifier.size(Dimens.iconMd(adaptiveLayout))
                            )
                        }

                        StepStatus.ACTIVE -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dimens.iconMd(adaptiveLayout)),
                                strokeWidth = Dimens.strokeMd(adaptiveLayout),
                                color = iconColor
                            )
                        }

                        StepStatus.ERROR -> {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Error",
                                tint = iconColor,
                                modifier = Modifier.size(Dimens.iconMd(adaptiveLayout))
                            )
                        }

                        StepStatus.PENDING -> {
                            Icon(
                                imageVector = stepState.step.icon,
                                contentDescription = stepState.step.title,
                                tint = iconColor,
                                modifier = Modifier.size(Dimens.iconMd(adaptiveLayout))
                            )
                        }
                    }
                }
            }

            // Connector line
            if (showConnector) {
                Box(
                    modifier = Modifier
                        .width(Dimens.spacingXxs(adaptiveLayout))
                        .height(Dimens.componentSm(adaptiveLayout))
                        .background(
                            if (stepState.status == StepStatus.COMPLETED) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            }
                        )
                )
            }
        }

        Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))

        // Step info
        Column(
            modifier = Modifier
                .weight(1f)
        ) {
            Text(
                text = stepState.step.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (stepState.status == StepStatus.ACTIVE) FontWeight.Bold else FontWeight.Medium,
                color = textColor
            )
            Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
            Text(
                text = stepState.step.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // CustomRoot检查命令（仅在CHECK_ROOT步骤失败时显示）
            if (stepState.status == StepStatus.ERROR && stepState.step == SetupStep.CHECK_ROOT) {
                var showCustomCommand by remember { mutableStateOf(false) }
                var customCommand by remember { mutableStateOf(RootConfigManager.getCustomRootCommand()) }

                Spacer(modifier = Modifier.height(Dimens.spacingSm(adaptiveLayout)))

                // 可点击的行，带图标和文字
                Surface(
                    onClick = { showCustomCommand = !showCustomCommand },
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Dimens.paddingMd(adaptiveLayout), vertical = Dimens.paddingSm(adaptiveLayout)),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.iconXs(adaptiveLayout)),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                        Text(
                            text = "Custom check command",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = if (showCustomCommand) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showCustomCommand) "Collapse" else "Expand",
                            modifier = Modifier.size(Dimens.iconSm(adaptiveLayout)),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                // Expand的输入区域
                AnimatedVisibility(
                    visible = showCustomCommand,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier.padding(top = Dimens.paddingSm(adaptiveLayout))
                    ) {
                        OutlinedTextField(
                            value = customCommand,
                            onValueChange = {
                                customCommand = it
                                RootConfigManager.setCustomRootCommand(it)
                            },
                            label = { Text("Root check command") },
                            placeholder = { Text("echo test") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                        Text(
                            text = "Default: ${RootConfigManager.DEFAULT_ROOT_COMMAND}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (showConnector) {
                Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))
            }
        }
    }
}

@Composable
private fun StepContent(
    adaptiveLayout: AdaptiveLayoutInfo,
    state: PermissionSetupState,
    viewModel: PermissionSetupViewModel
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        when (state) {
            is PermissionSetupState.Initializing -> {
                InitializingContent(adaptiveLayout)
            }

            is PermissionSetupState.CheckingRoot -> {
                CheckingRootContent(adaptiveLayout)
            }

            is PermissionSetupState.NoRoot -> {
                NoRootContent(adaptiveLayout, onRetry = { viewModel.retryRootCheck() })
            }

            is PermissionSetupState.WaitingUserConfirm -> {
                WaitingConfirmContent(
                    adaptiveLayout = adaptiveLayout,
                    onConfirm = { viewModel.confirmUseRoot() }
                )
            }

            is PermissionSetupState.GrantingPermissions -> {
                GrantingPermissionsContent(
                    adaptiveLayout = adaptiveLayout,
                    current = state.current,
                    total = state.total,
                    currentPermission = state.currentPermission
                )
            }

            is PermissionSetupState.ApplyingAntiKillProtection -> {
                ApplyingAntiKillProtectionContent(
                    adaptiveLayout = adaptiveLayout,
                    current = state.current,
                    total = state.total,
                    currentMeasure = state.currentMeasure
                )
            }

            is PermissionSetupState.CheckingDriver -> {
                CheckingDriverContent(adaptiveLayout)
            }

            is PermissionSetupState.DriverNotInstalled -> {
                DriverNotInstalledContent(
                    adaptiveLayout = adaptiveLayout,
                    onInstall = { viewModel.navigateToDriverInstall() }
                )
            }

            is PermissionSetupState.Completed -> {
                CompletedContent(
                    adaptiveLayout = adaptiveLayout,
                    allGranted = state.allGranted,
                    grantedCount = state.grantedCount,
                    totalCount = state.totalCount
                )
            }

            is PermissionSetupState.Error -> {
                ErrorContent(
                    adaptiveLayout = adaptiveLayout,
                    message = state.message,
                    onRetry = { viewModel.retryRootCheck() }
                )
            }
        }
    }
}

@Composable
private fun InitializingContent(adaptiveLayout: AdaptiveLayoutInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(Dimens.paddingLg(adaptiveLayout)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(Dimens.iconMd(adaptiveLayout)),
                strokeWidth = Dimens.strokeMd(adaptiveLayout)
            )
            Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
            Text(
                text = "Initializing...",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun CheckingRootContent(adaptiveLayout: AdaptiveLayoutInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingXl(adaptiveLayout))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.iconMd(adaptiveLayout)),
                    strokeWidth = Dimens.strokeMd(adaptiveLayout)
                )
                Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
                Column {
                    Text(
                        text = "Checking root access",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                    Text(
                        text = "Allow root access in the authorization prompt",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NoRootContent(adaptiveLayout: AdaptiveLayoutInfo, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingXl(adaptiveLayout))
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    modifier = Modifier.size(Dimens.iconMd(adaptiveLayout)),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Root access not granted",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingSm(adaptiveLayout)))
                    Text(
                        text = "This app requires root access to work properly. Make sure your device is rooted and that access is allowed in your root manager.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                    Text(
                        text = "You can edit the custom root check command above and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))
            Button(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun WaitingConfirmContent(
    adaptiveLayout: AdaptiveLayoutInfo,
    onConfirm: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingXl(adaptiveLayout))
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.VerifiedUser,
                    contentDescription = "Success",
                    modifier = Modifier.size(Dimens.iconMd(adaptiveLayout)),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Root access granted",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingSm(adaptiveLayout)))
                    Text(
                        text = "The app can now use root access to grant the required system permissions automatically",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                    Text(
                        text = "This includes overlay permission, storage access, and related system permissions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))
            Button(
                onClick = onConfirm,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Grant permissions")
            }
        }
    }
}

@Composable
private fun GrantingPermissionsContent(
    adaptiveLayout: AdaptiveLayoutInfo,
    current: Int,
    total: Int,
    currentPermission: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingXl(adaptiveLayout))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    CircularProgressIndicator(
                        progress = { current.toFloat() / total.toFloat() },
                        modifier = Modifier.size(Dimens.componentMd(adaptiveLayout)),
                        strokeWidth = Dimens.strokeLg(adaptiveLayout)
                    )
                    Text(
                        text = "$current",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Granting permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                    Text(
                        text = "$current / $total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))

            LinearProgressIndicator(
                progress = { current.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))

            Text(
                text = "Current permission",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
            Text(
                text = currentPermission,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ApplyingAntiKillProtectionContent(
    adaptiveLayout: AdaptiveLayoutInfo,
    current: Int,
    total: Int,
    currentMeasure: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingXl(adaptiveLayout))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    CircularProgressIndicator(
                        progress = { current.toFloat() / total.toFloat() },
                        modifier = Modifier.size(Dimens.componentMd(adaptiveLayout)),
                        strokeWidth = Dimens.strokeLg(adaptiveLayout),
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = "$current",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Apply anti-kill protection",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                    Text(
                        text = "$current / $total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))

            LinearProgressIndicator(
                progress = { current.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiary
            )

            Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))

            Text(
                text = "Current protection measures",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
            Text(
                text = currentMeasure,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CheckingDriverContent(adaptiveLayout: AdaptiveLayoutInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingXl(adaptiveLayout))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.iconMd(adaptiveLayout)),
                    strokeWidth = Dimens.strokeMd(adaptiveLayout)
                )
                Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
                Column {
                    Text(
                        text = "Checking driver",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                    Text(
                        text = "Verifying that the memory driver is installed...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DriverNotInstalledContent(adaptiveLayout: AdaptiveLayoutInfo, onInstall: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingXl(adaptiveLayout))
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    modifier = Modifier.size(Dimens.iconMd(adaptiveLayout)),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Driver not installed",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingSm(adaptiveLayout)))
                    Text(
                        text = "The memory driver is not installed yet. Install it to use all features.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))
            Button(
                onClick = onInstall,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Install driver")
            }
        }
    }
}

@Composable
private fun CompletedContent(
    adaptiveLayout: AdaptiveLayoutInfo,
    allGranted: Boolean,
    grantedCount: Int,
    totalCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (allGranted) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingXl(adaptiveLayout))
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = if (allGranted) Icons.Default.TaskAlt else Icons.Default.Warning,
                    contentDescription = if (allGranted) "Success" else "Warning",
                    modifier = Modifier.size(Dimens.iconMd(adaptiveLayout)),
                    tint = if (allGranted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                )
                Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (allGranted) "Permissions granted" else "Some permissions were granted",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingSm(adaptiveLayout)))
                    Text(
                        text = "Granted $grantedCount / $totalCount permissions",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingXs(adaptiveLayout)))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.iconXs(adaptiveLayout)),
                            strokeWidth = Dimens.spacingXxs(adaptiveLayout)
                        )
                        Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                        Text(
                            text = "Launching the main screen...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorContent(
    adaptiveLayout: AdaptiveLayoutInfo,
    message: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(Dimens.paddingXl(adaptiveLayout))
        ) {
            Row(
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Error",
                    modifier = Modifier.size(Dimens.iconMd(adaptiveLayout)),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(Dimens.spacingLg(adaptiveLayout)))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "An error occurred",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(Dimens.spacingSm(adaptiveLayout)))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))
            Button(
                onClick = onRetry,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Retry")
            }
        }
    }
}
