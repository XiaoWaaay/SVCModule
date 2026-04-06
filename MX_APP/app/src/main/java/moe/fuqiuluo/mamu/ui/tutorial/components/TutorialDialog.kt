package moe.fuqiuluo.mamu.ui.tutorial.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.fuqiuluo.mamu.ui.theme.AdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.theme.Dimens

/**
 * Tutorial step data class
 */
data class TutorialStep(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val tips: List<String> = emptyList()
)

/**
 * Default tutorial steps for Mamu
 */
val defaultTutorialSteps = listOf(
    TutorialStep(
        icon = Icons.Default.Celebration,
        title = "Welcome to Mamu",
        description = "Mamu is a powerful Android memory debugging tool that helps you search, monitor, and modify process memory.",
        tips = listOf(
            "Root access is required for full functionality",
            "Only arm64-v8a devices are supported"
        )
    ),
    TutorialStep(
        icon = Icons.Default.Window,
        title = "Launch the floating window",
        description = "Tap the floating button in the lower-right corner of the main screen to launch the overlay. It is your main workspace for memory operations.",
        tips = listOf(
            "The overlay can be dragged anywhere on screen",
            "Tap the overlay to expand or collapse the menu"
        )
    ),
    TutorialStep(
        icon = Icons.Default.AppShortcut,
        title = "Choose a target process",
        description = "Open the process picker from the overlay menu, then choose the app process you want to inspect.",
        tips = listOf(
            "Only running processes are shown",
            "You can search by package name or process name"
        )
    ),
    TutorialStep(
        icon = Icons.Default.Search,
        title = "Search memory",
        description = "After binding a process, open Search, enter the value you want to find, choose a data type such as int or float, and start the scan.",
        tips = listOf(
            "The first search scans all selected memory",
            "Refine searches filter the current results",
            "Both exact search and fuzzy search are supported"
        )
    ),
    TutorialStep(
        icon = Icons.Default.FilterList,
        title = "Refine results",
        description = "Search results can be numerous, so refine them several times. Change the in-game value, search again, and keep narrowing the range.",
        tips = listOf(
            "Searching right after the value changes works best",
            "You can use conditions like increased or decreased",
            "Once the result count drops below 100, try editing values"
        )
    ),
    TutorialStep(
        icon = Icons.Default.Edit,
        title = "Modify values",
        description = "Once you find the target address, tap it to edit the value. Changes apply immediately so you can verify them in-game.",
        tips = listOf(
            "It is a good idea to save the address before editing",
            "Some values may be protected by validation checks",
            "You can freeze a value to stop it from changing"
        )
    ),
    TutorialStep(
        icon = Icons.Default.Warning,
        title = "Important notes",
        description = "Using a memory editing tool has risks. Use it only for offline games, learning, or research.",
        tips = listOf(
            "Using it in online games may lead to bans",
            "Practice on an emulator first if possible",
            "Keep the app hidden when needed to reduce detection risk"
        )
    )
)

/**
 * Tutorial dialog component
 */
@Composable
fun TutorialDialog(
    adaptiveLayout: AdaptiveLayoutInfo,
    onDismiss: () -> Unit,
    onComplete: () -> Unit,
    onStartPractice: (() -> Unit)? = null,
    steps: List<TutorialStep> = defaultTutorialSteps
) {
    val pagerState = rememberPagerState(pageCount = { steps.size })
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left side: Previous button
                if (pagerState.currentPage > 0) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        }
                    ) {
                        Text("Previous")
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                // Right side: action buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm(adaptiveLayout))
                ) {
                    if (pagerState.currentPage < steps.size - 1) {
                        // Skip button
                        TextButton(onClick = onComplete) {
                            Text("Skip")
                        }
                        // Next button
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        ) {
                            Text("Next")
                            Spacer(modifier = Modifier.width(Dimens.spacingXs(adaptiveLayout)))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(Dimens.iconXs(adaptiveLayout))
                            )
                        }
                    } else {
                        Button(onClick = onComplete) {
                            Text("Get started")
                        }
                    }
                }
            }
        },
        title = null,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Pager for tutorial steps
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.scaled(adaptiveLayout, 320f))
                ) { page ->
                    TutorialStepContent(adaptiveLayout = adaptiveLayout, step = steps[page])
                }

                // Practice button on last page
                if (onStartPractice != null && pagerState.currentPage == steps.size - 1) {
                    Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))
                    OutlinedButton(
                        onClick = onStartPractice,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.School,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.iconSm(adaptiveLayout))
                        )
                        Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                        Text("Start practice mode")
                    }
                }

                Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))

                // Page indicators
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    repeat(steps.size) { index ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = Dimens.spacingXs(adaptiveLayout))
                                .size(if (isSelected) Dimens.scaled(adaptiveLayout, 10f) else Dimens.spacingSm(adaptiveLayout))
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    }
                                )
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun TutorialStepContent(adaptiveLayout: AdaptiveLayoutInfo, step: TutorialStep) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Dimens.paddingSm(adaptiveLayout)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Icon
        Icon(
            imageVector = step.icon,
            contentDescription = null,
            modifier = Modifier.size(Dimens.iconXxl(adaptiveLayout)),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))

        // Title
        Text(
            text = step.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(Dimens.spacingMd(adaptiveLayout)))

        // Description
        Text(
            text = step.description,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Tips
        if (step.tips.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Dimens.spacingLg(adaptiveLayout)))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(Dimens.paddingMd(adaptiveLayout))
                ) {
                    step.tips.forEach { tip ->
                        Row(
                            modifier = Modifier.padding(vertical = Dimens.spacingXxs(adaptiveLayout)),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(Dimens.iconXs(adaptiveLayout))
                                    .padding(top = Dimens.spacingXxs(adaptiveLayout)),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.width(Dimens.spacingSm(adaptiveLayout)))
                            Text(
                                text = tip,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}
