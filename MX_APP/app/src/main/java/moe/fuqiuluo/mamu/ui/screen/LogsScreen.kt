package moe.fuqiuluo.mamu.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import moe.fuqiuluo.mamu.data.model.*
import moe.fuqiuluo.mamu.ui.theme.Dimens
import moe.fuqiuluo.mamu.ui.theme.rememberAdaptiveLayoutInfo
import moe.fuqiuluo.mamu.ui.viewmodel.LogViewModel

@Composable
fun LogsScreen(
    windowSizeClass: WindowSizeClass,
    viewModel: LogViewModel = viewModel()
) {
    val adaptiveLayout = rememberAdaptiveLayoutInfo(windowSizeClass)
    val uiState by viewModel.uiState.collectAsState()
    val filteredLogs = viewModel.filteredLogs  // 直接使用 SnapshotStateList
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(filteredLogs.size, uiState.autoScroll) {
        if (uiState.autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Tools栏：表达式输入 + 按钮
        Surface(tonalElevation = Dimens.elevationSm(adaptiveLayout)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.paddingXs(adaptiveLayout)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs(adaptiveLayout))
            ) {
                // 表达式输入框
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(Dimens.scaled(adaptiveLayout, 32f))
                        .clip(RoundedCornerShape(Dimens.spacingXs(adaptiveLayout)))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = Dimens.paddingSm(adaptiveLayout)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = uiState.filterExpression,
                        onValueChange = viewModel::setFilterExpression,
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            if (uiState.filterExpression.isEmpty()) {
                                Text(
                                    "package:mine",
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.6f)
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                // 播放/暂停
                IconButton(
                    onClick = { if (uiState.isCapturing) viewModel.stopCapture() else viewModel.startCapture() },
                    modifier = Modifier.size(Dimens.scaled(adaptiveLayout, 32f))
                ) {
                    Icon(
                        if (uiState.isCapturing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null,
                        Modifier.size(Dimens.iconMd(adaptiveLayout)),
                        tint = if (uiState.isCapturing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }

                // 清除
                IconButton(
                    onClick = viewModel::clearLogs,
                    modifier = Modifier.size(Dimens.scaled(adaptiveLayout, 32f))
                ) {
                    Icon(Icons.Default.Delete, null, Modifier.size(Dimens.iconMd(adaptiveLayout)))
                }

                // 自动换行
                IconButton(
                    onClick = viewModel::toggleWordWrap,
                    modifier = Modifier.size(Dimens.scaled(adaptiveLayout, 32f))
                ) {
                    Icon(
                        Icons.Default.WrapText, null,
                        Modifier.size(Dimens.iconMd(adaptiveLayout)),
                        tint = if (uiState.wordWrap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 自动滚动
                IconButton(
                    onClick = viewModel::toggleAutoScroll,
                    modifier = Modifier.size(Dimens.scaled(adaptiveLayout, 32f))
                ) {
                    Icon(
                        Icons.Default.VerticalAlignBottom, null,
                        Modifier.size(Dimens.iconMd(adaptiveLayout)),
                        tint = if (uiState.autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Logs列表
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                uiState.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                filteredLogs.isEmpty() -> {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Article, null,
                            Modifier.size(Dimens.iconXl(adaptiveLayout)),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f)
                        )
                        Spacer(Modifier.height(Dimens.spacingSm(adaptiveLayout)))
                        Text(
                            if (uiState.isCapturing) "Waiting for logs..." else "Tap ▶ to start capturing",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    val horizontalScrollState = rememberScrollState()
                    
                    SelectionContainer {
                        if (uiState.wordWrap) {
                            // 自动换行模式
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(Dimens.paddingXs(adaptiveLayout))
                            ) {
                                itemsIndexed(filteredLogs, key = { _, entry -> entry.id }) { _, entry ->
                                    LogLine(entry, adaptiveLayout, wordWrap = true)
                                }
                            }
                        } else {
                            // 横向滚动模式
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .horizontalScroll(horizontalScrollState)
                            ) {
                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.fillMaxHeight(),
                                    contentPadding = PaddingValues(Dimens.paddingXs(adaptiveLayout))
                                ) {
                                    itemsIndexed(filteredLogs, key = { _, entry -> entry.id }) { _, entry ->
                                        LogLine(entry, adaptiveLayout, wordWrap = false)
                                    }
                                }
                            }
                        }
                    }
                    if (!uiState.autoScroll) {
                        SmallFloatingActionButton(
                            onClick = { coroutineScope.launch { listState.animateScrollToItem(filteredLogs.size - 1) } },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(Dimens.paddingMd(adaptiveLayout)),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, null)
                        }
                    }
                }
            }
        }

        // 底部状态栏
        Surface(tonalElevation = Dimens.elevationSm(adaptiveLayout)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.paddingSm(adaptiveLayout), vertical = Dimens.paddingXs(adaptiveLayout)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.isCapturing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(Dimens.spacingXs(adaptiveLayout))
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF4CAF50))
                        )
                        Spacer(Modifier.width(Dimens.spacingXs(adaptiveLayout)))
                        Text("Capturing", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Spacer(Modifier)
                }
                Text(
                    "${filteredLogs.size} entries",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LogLine(
    entry: LogEntry,
    adaptiveLayout: moe.fuqiuluo.mamu.ui.theme.AdaptiveLayoutInfo,
    wordWrap: Boolean = false
) {
    // Cache levelColor - only recompute when entry.level changes
    val levelColor = remember(entry.level) {
        when (entry.level) {
            LogLevel.VERBOSE -> Color(0xFF9E9E9E)
            LogLevel.DEBUG -> Color(0xFF2196F3)
            LogLevel.INFO -> Color(0xFF4CAF50)
            LogLevel.WARNING -> Color(0xFFFF9800)
            LogLevel.ERROR -> Color(0xFFF44336)
            LogLevel.FATAL -> Color(0xFF9C27B0)
            else -> Color.Gray
        }
    }

    // Cache fontSize - only recompute when adaptiveLayout changes
    val fontSize = remember(adaptiveLayout) {
        Dimens.scaled(adaptiveLayout, 11f).value.sp
    }

    if (wordWrap) {
        // 换行模式：前缀 + Message换行
        Column(modifier = Modifier.padding(vertical = Dimens.spacingXxs(adaptiveLayout))) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs(adaptiveLayout))) {
                Text(
                    entry.formattedTime,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    entry.level.label,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, fontWeight = FontWeight.Bold),
                    color = levelColor
                )
                if (entry.pid > 0) {
                    Text(
                        entry.pid.toString(),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.tag.isNotEmpty()) {
                    Text(
                        entry.tag,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize),
                        color = levelColor
                    )
                }
            }
            Text(
                entry.message,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize),
                color = levelColor,
                modifier = Modifier.padding(start = Dimens.paddingSm(adaptiveLayout))
            )
        }
    } else {
        // 单行模式
        Row(
            modifier = Modifier.padding(vertical = Dimens.spacingXxs(adaptiveLayout)),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacingXs(adaptiveLayout))
        ) {
            Text(
                entry.formattedTime,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
            Text(
                entry.level.label,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize, fontWeight = FontWeight.Bold),
                color = levelColor,
                maxLines = 1,
                softWrap = false
            )
            if (entry.pid > 0) {
                Text(
                    entry.pid.toString(),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false
                )
            }
            if (entry.tag.isNotEmpty()) {
                Text(
                    entry.tag,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize),
                    color = levelColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Text(
                entry.message,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize),
                color = levelColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
