package com.example.echosub.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.example.echosub.data.AppSettings
import com.example.echosub.device.DeviceState
import com.example.echosub.service.CaptureState
import com.example.echosub.stt.TranscriptState
import java.io.File

/**
 * 앱 셸 — 상단 상태 표시줄, 하단 탭, 그리고 **항상 떠 있는** 시작/중지 버튼.
 *
 * 이전 화면은 설정과 자막이 하나의 스크롤 Column에 쌓여 있어서, 자막이 길어지면
 * 중지 버튼이 위로 밀려 한참 스크롤해야 눌 수 있었다. 시작/중지를 FAB로 띄워
 * 스크롤 위치·탭과 무관하게 항상 손에 닿게 하고, 설정과 자막을 탭으로 갈랐다.
 */
private enum class Tab(val label: String, val icon: ImageVector) {
    SUBTITLE("자막", Icons.AutoMirrored.Filled.List),
    HISTORY("기록", Icons.Filled.Folder),
    SETTINGS("설정", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EchoSubApp(
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onToggleSttPause: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
) {
    val status by CaptureState.status.collectAsState()
    val transcript by TranscriptState.status.collectAsState()
    val settings by AppSettings.values.collectAsState()
    val batteryOptimizationExempt by DeviceState.batteryOptimizationExempt.collectAsState()

    // 화면 회전은 configChanges로 막아뒀지만, 프로세스가 죽었다 살아나는 경우까지
    // 보려면 저장 가능한 상태여야 한다.
    var tab by rememberSaveable { mutableStateOf(Tab.SUBTITLE) }
    var playingPath by rememberSaveable { mutableStateOf<String?>(null) }

    var confirmStop by remember { mutableStateOf(false) }

    // 자막 화면의 안내 배너 중 사용자가 닫은 것들. 자막 화면 안에 두면 탭을 옮겼다
    // 돌아올 때마다 되살아나므로(그 컴포지션이 통째로 버려진다) 셸이 들고 있는다.
    val dismissedBanners = remember { mutableStateListOf<String>() }

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 종료는 되돌릴 수 없다(이어 녹음이 불가능하고 새 파일이 된다). 무엇이 일어나는지와,
    // "잠깐 멈추고 싶었던 것"이라면 대신 뭘 써야 하는지를 여기서 분명히 말해준다.
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("녹음을 끝낼까요?") },
            text = {
                Text(
                    "지금까지 녹음된 내용이 파일로 저장되고 기록 탭에 남습니다.\n\n" +
                        "이어서 녹음할 수는 없습니다 — 다시 시작하면 새 파일이 됩니다. " +
                        "녹음은 계속하면서 번역만 잠시 멈추려면 옆의 \"번역 정지\"를 쓰세요.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onStopClick()
                }) { Text("저장하고 끝내기") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("계속 녹음") }
            },
        )
    }

    // 재생 화면은 셸(상단바·하단 탭·FAB) 안이 아니라 화면 전체를 쓴다.
    // 가로로 눕히면 남는 높이가 절반 이하라, 셸에 자리를 내주면 재생 버튼이 잘리고
    // "캡처 시작" FAB이 대본 위에 겹친다.
    playingPath?.let { path ->
        Surface(modifier = Modifier.fillMaxSize()) {
            PlayerScreen(
                filePath = path,
                fileName = File(path).name,
                onBack = { playingPath = null },
                modifier = Modifier.systemBarsPadding(),
                landscapeStacked = settings.playerLandscapeStacked,
                onToggleLandscapeStacked = {
                    AppSettings.setPlayerLandscapeStacked(!settings.playerLandscapeStacked)
                },
            )
        }
        return
    }

    // 캡처를 시작하면 볼 것은 자막뿐이다 — 설정 탭에 남아 있을 이유가 없다.
    LaunchedEffect(status.isRunning) {
        if (status.isRunning) tab = Tab.SUBTITLE
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { BrandWordmark() },
                    // 위쪽은 "지금 어떤 상태인가"만 보여주고, 손대는 것은 전부 아래 버튼으로 모았다.
                    actions = {
                        StatusPill(
                            isRunning = status.isRunning,
                            elapsedMs = status.elapsedMs,
                            connection = transcript.connectionState,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                // 입력 레벨을 얇은 띠로 항상 같은 자리에 보여준다 — "소리가 들어오고
                // 있나"를 텍스트 배너로 나타냈다 지웠다 하면 말이 끊길 때마다 화면이
                // 흔들려서, 대신 이 바의 색으로만 조용히 알린다 (자리는 고정, 색만 변화).
                AnimatedVisibility(
                    visible = status.isRunning,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    LinearProgressIndicator(
                        progress = { status.rmsLevel },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = if (status.rmsLevel < 0.01f) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, contentDescription = entry.label) },
                        label = { Text(entry.label) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            // 종료와 번역 정지를 한 손 닿는 자리에 나란히 둔다. 색으로 위계를 준다 —
            // 오른쪽(종료)이 주 동작이고 왼쪽(번역 정지)은 보조라, 크기 대신 톤으로 구분한다.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (status.isRunning && settings.sttEnabled) {
                    SttPauseFab(
                        isPaused = transcript.connectionState == TranscriptState.ConnectionState.PAUSED,
                        onClick = onToggleSttPause,
                    )
                }
                CaptureFab(
                    isRunning = status.isRunning,
                    onStartClick = onStartClick,
                    onStopClick = { confirmStop = true },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 세로에서는 FAB이 콘텐츠 아래쪽 여백 위에 뜨지만, 가로에서는 높이가 줄어든 만큼
                // 화면 한복판을 가린다. 가로일 때만 오른쪽에 FAB 자리를 비워둔다
                // (가로는 폭이 넉넉해서 이 정도 내주는 게 가려지는 것보다 낫다).
                .padding(end = if (isLandscape) 96.dp else 0.dp),
        ) {
            when (tab) {
                Tab.SUBTITLE -> SubtitleScreen(
                    status = status,
                    transcript = transcript,
                    sttEnabled = settings.sttEnabled,
                    batteryOptimizationExempt = batteryOptimizationExempt,
                    dismissedBanners = dismissedBanners,
                )

                Tab.HISTORY -> HistoryScreen(
                    activeOutputPaths = if (status.isRunning) status.outputPaths else emptyList(),
                    onPlayFile = { playingPath = it },
                )

                Tab.SETTINGS -> SettingsScreen(
                    values = settings,
                    isRunning = status.isRunning,
                    outputPaths = status.outputPaths,
                    batteryOptimizationExempt = batteryOptimizationExempt,
                    onRequestBatteryExemption = onRequestBatteryExemption,
                )
            }
        }
    }
}

@Composable
private fun CaptureFab(
    isRunning: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
) {
    ExtendedFloatingActionButton(
        onClick = if (isRunning) onStopClick else onStartClick,
        containerColor = if (isRunning) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primary
        },
        contentColor = if (isRunning) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onPrimary
        },
        icon = {
            Icon(
                imageVector = if (isRunning) Icons.Filled.Close else Icons.Filled.PlayArrow,
                contentDescription = null,
            )
        },
        // "중지"는 일시정지로도 읽힌다 — 실제로는 파일을 확정 저장하고 끝내는,
        // 되돌릴 수 없는 동작이라 버튼에서부터 그렇게 말한다.
        text = { Text(if (isRunning) "저장하고 종료" else "캡처 시작") },
    )
}

/**
 * STT/번역을 잠깐 멈추는 토글. 캡처(오디오 녹음)는 계속되고 STT 스트림만 끊는다 —
 * 무음·대사 없는 구간에서 사용자가 직접 눌러 STT 사용료를 아끼기 위한 것.
 *
 * 종료 버튼 바로 위에 붙여두되 톤 컬러로 낮춰, 주 동작(종료)과 헷갈리지 않게 한다.
 */
@Composable
private fun SttPauseFab(
    isPaused: Boolean,
    onClick: () -> Unit,
) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        containerColor = if (isPaused) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isPaused) {
            MaterialTheme.colorScheme.onTertiaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        icon = {
            Icon(
                imageVector = if (isPaused) Icons.Filled.Mic else Icons.Filled.Pause,
                contentDescription = null,
            )
        },
        // 그냥 "일시정지"라고만 하면 녹음이 멈추는 걸로 읽힌다 — 멈추는 건 번역뿐이다.
        text = { Text(if (isPaused) "번역 재개" else "번역 정지") },
    )
}

/**
 * 상단 좌측의 브랜드 마크 — 그라데이션 사각 로고 + 워드마크.
 * "Echo"만 굵게 쳐서 텍스트 하나여도 로고처럼 읽히게 한다.
 */
@Composable
private fun BrandWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        Row(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                text = "Echo",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Sub",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 상단 우측의 상태 알약 — 실행 중이면 경과 시간, 아니면 대기 표시. */
@Composable
private fun StatusPill(
    isRunning: Boolean,
    elapsedMs: Long,
    connection: TranscriptState.ConnectionState,
    modifier: Modifier = Modifier,
) {
    val dotColor = when {
        !isRunning -> MaterialTheme.colorScheme.outline
        connection == TranscriptState.ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        connection == TranscriptState.ConnectionState.PAUSED -> MaterialTheme.colorScheme.tertiary
        connection == TranscriptState.ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
        else -> Color(0xFF2E7D32)
    }
    val label = when {
        !isRunning -> "대기 중"
        // 녹음은 계속되고 있다 — 경과 시간이 계속 도는 이유가 여기서 읽혀야 한다.
        connection == TranscriptState.ConnectionState.PAUSED -> "녹음 중 · 번역 정지 ${formatElapsed(elapsedMs)}"
        else -> formatElapsed(elapsedMs)
    }

    // 녹음 중에는 점을 천천히 깜빡여 "지금 돌고 있다"가 숫자를 읽지 않아도 보이게 한다.
    val pulse = rememberInfiniteTransition(label = "statusDot")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "statusDotAlpha",
    )

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (isRunning) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(if (isRunning) dotAlpha else 1f)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isRunning) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

internal fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
