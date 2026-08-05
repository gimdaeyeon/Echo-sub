package com.example.echosub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.echosub.service.CaptureState
import com.example.echosub.stt.TranscriptState
import kotlinx.coroutines.delay

/**
 * 자막(대본) 전용 화면.
 *
 * 위계를 뒤집었다 — 예전 화면은 원문이 본문 크기, 번역이 그 아래 보조였는데
 * 이 앱을 쓰는 이유는 번역이므로 **번역문을 본문**으로 올리고 원문을 각주처럼 내렸다.
 *
 * 최신 문장만 카드로 띄우고 지난 문장들은 왼쪽 세로줄로 묶는다 — "지금 들리는 말"과
 * "이미 지나간 말"이 스크롤 위치와 무관하게 한눈에 갈리게.
 *
 * [dismissedBanners]는 화면 밖(EchoSubApp)에 둔다 — 탭을 옮겼다 돌아올 때마다
 * 닫아둔 안내가 되살아나면 닫는 의미가 없다.
 */
@Composable
fun SubtitleScreen(
    status: CaptureState.Status,
    transcript: TranscriptState.Status,
    sttEnabled: Boolean,
    batteryOptimizationExempt: Boolean,
    dismissedBanners: MutableList<String>,
) {
    val entries = transcript.finalEntries
    val listState = rememberLazyListState()

    // 사용자가 이전 내용을 보려고 위로 올려둔 상태에서는 자동 스크롤로 끌어내리지 않는다.
    val stickToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }

    LaunchedEffect(entries.size, transcript.interimText) {
        val target = listState.layoutInfo.totalItemsCount - 1
        if (stickToBottom && target >= 0) listState.animateScrollToItem(target)
    }

    val banners = buildBanners(status, transcript, sttEnabled, batteryOptimizationExempt)
        .filter { it.key !in dismissedBanners }

    val isEmpty = entries.isEmpty() && transcript.interimText.isBlank()

    Box(modifier = Modifier.fillMaxSize()) {
        if (isEmpty) {
            EmptyState(isRunning = status.isRunning, sttEnabled = sttEnabled)
        } else {
            val latestId = entries.lastOrNull()?.id
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // 하단은 FAB에 가리지 않도록 넉넉히 비운다.
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        TranscriptEntryRow(
                            entry = entry,
                            isLatest = entry.id == latestId,
                        )
                    }
                    if (transcript.interimText.isNotBlank()) {
                        item(key = "interim") {
                            Text(
                                text = transcript.interimText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 15.dp),
                            )
                        }
                    }
                }
            }
        }

        // 배너는 자막 위에 **떠 있게** 둔다 — 레이아웃 흐름에 넣으면 안내가 뜨고 질 때마다
        // 자막 전체가 위아래로 밀려 읽던 자리를 잃는다. 대신 스스로 사라지거나(자동 소멸)
        // 사용자가 닫을 수 있게 해서, 떠 있는 동안만 잠깐 가리고 만다.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            banners.forEach { banner ->
                key(banner.key) {
                    // 안내성 배너는 읽을 시간만 주고 스스로 물러난다. 캡처가 죽은 것처럼
                    // 사용자가 손을 대야 풀리는 상태만 남겨둔다(autoDismissMs == null).
                    banner.autoDismissMs?.let { timeout ->
                        LaunchedEffect(banner.key) {
                            delay(timeout)
                            dismissedBanners.add(banner.key)
                        }
                    }
                    // 목록에 들어오는 순간 targetState를 뒤집어 등장 애니메이션을 태운다 —
                    // visible=true로 그냥 두면 첫 컴포지션에서는 애니메이션 없이 툭 나타난다.
                    val appearance = remember { MutableTransitionState(false) }
                    appearance.targetState = true
                    AnimatedVisibility(
                        visibleState = appearance,
                        enter = fadeIn() + slideInVertically { -it / 2 },
                        exit = fadeOut(),
                    ) {
                        BannerRow(banner, onDismiss = { dismissedBanners.add(banner.key) })
                    }
                }
            }
        }
    }
}

/**
 * 지금 상태에서 띄울 안내들. **키에 내용을 섞어 넣는 것이 중요하다** — 같은 종류라도
 * 메시지가 달라지면 다른 배너로 쳐서, 이전 것을 닫아뒀다고 새 오류까지 묻히지 않게 한다.
 */
private fun buildBanners(
    status: CaptureState.Status,
    transcript: TranscriptState.Status,
    sttEnabled: Boolean,
    batteryOptimizationExempt: Boolean,
): List<Banner> = buildList {
    if (!sttEnabled) {
        add(
            Banner(
                key = "stt-off",
                text = "원음 녹음 모드입니다 — 번역이 꺼져 있습니다. 설정 › 고급에서 켤 수 있습니다.",
                isError = false,
                autoDismissMs = 8_000L,
            ),
        )
    }
    if (status.isRunning && transcript.connectionState == TranscriptState.ConnectionState.PAUSED) {
        // 상단 상태 알약과 아래 "번역 재개" 버튼이 이미 같은 말을 하고 있다 —
        // 여기서는 처음 멈춘 순간에만 한 번 짚어주고 물러난다.
        add(
            Banner(
                key = "stt-paused",
                text = "번역만 멈췄습니다 — 녹음은 계속됩니다. 아래 \"번역 재개\"로 다시 시작합니다.",
                isError = false,
                autoDismissMs = 6_000L,
            ),
        )
    }
    transcript.translatorMessage?.let {
        // 키/네트워크 문제라 사용자가 손대기 전에는 풀리지 않는다 — 자동으로 지우지 않는다.
        add(Banner("translator:$it", "번역 엔진: $it", isError = true, autoDismissMs = null))
    }
    transcript.errorMessage?.let {
        // STT 오류는 곧바로 재연결로 스스로 복구되므로 계속 띄워둘 이유가 없다.
        add(Banner("stt:$it", "STT 오류: $it", isError = true, autoDismissMs = 8_000L))
    }
    status.errorMessage?.let {
        // 캡처가 죽으면 스스로 살아나지 않는다 — 사용자가 읽고 닫을 때까지 남긴다.
        add(Banner("capture:$it", "캡처 오류: $it", isError = true, autoDismissMs = null))
    }
    // 입력 레벨이 낮다는 건 상단의 레벨 바 색으로만 알린다 — 여기서 배너로 띄우면
    // 말이 끊길 때마다 나타났다 사라지며 화면이 계속 흔들렸다.
    if (transcript.reconnectCount > 0) {
        // 횟수를 키에 넣어 재연결이 일어난 그때만 잠깐 뜨게 한다. 예전에는 누적 횟수를
        // 계속 띄워서, 한 번 재연결되면 세션 내내 "정상 동작입니다"가 화면을 차지했다.
        add(
            Banner(
                key = "reconnect:${transcript.reconnectCount}",
                text = "스트림을 다시 연결했습니다 — 정상 동작입니다.",
                isError = false,
                autoDismissMs = 4_000L,
            ),
        )
    }
    if (status.isRunning && !batteryOptimizationExempt) {
        add(
            Banner(
                key = "battery",
                text = "배터리 최적화 대상에서 제외돼 있지 않습니다 — 화면을 끄면 캡처가 중단될 수 있습니다. " +
                    "설정 탭에서 제외해 주세요.",
                isError = false,
                autoDismissMs = null,
            ),
        )
    }
}

@Composable
private fun TranscriptEntryRow(entry: TranscriptState.Entry, isLatest: Boolean) {
    // 최신 문장은 카드로 들어 올리고, 지난 문장은 왼쪽 세로줄만 남긴다.
    if (isLatest) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                EntryTexts(
                    entry = entry,
                    translatedColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    translatedWeight = FontWeight.SemiBold,
                    sourceColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.65f),
                )
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Column(
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EntryTexts(
                    entry = entry,
                    translatedColor = MaterialTheme.colorScheme.onSurface,
                    translatedWeight = FontWeight.Normal,
                    sourceColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EntryTexts(
    entry: TranscriptState.Entry,
    translatedColor: Color,
    translatedWeight: FontWeight,
    sourceColor: Color,
) {
    when {
        entry.translatedText != null -> Text(
            text = entry.translatedText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = translatedWeight,
            color = translatedColor,
        )

        entry.translationError != null -> Text(
            text = "번역 실패: ${entry.translationError}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )

        else -> Text(
            text = "번역 중…",
            style = MaterialTheme.typography.bodyMedium,
            color = sourceColor,
        )
    }

    Text(
        text = entry.sourceText,
        style = MaterialTheme.typography.bodySmall,
        color = sourceColor,
    )
}

/**
 * [key]는 배너의 정체성 — 닫힘 여부를 이 값으로 기억하므로 내용이 달라지면 키도 달라져야 한다.
 * [autoDismissMs]가 null이면 사용자가 닫을 때까지 남는다.
 */
private data class Banner(
    val key: String,
    val text: String,
    val isError: Boolean,
    val autoDismissMs: Long?,
)

@Composable
private fun BannerRow(banner: Banner, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (banner.isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        // 자막 위에 떠 있는 것이므로 그림자로 층을 분명히 한다 — 배경이 비치면
        // 아래 글자와 겹쳐 둘 다 읽기 어려워진다.
        shadowElevation = 6.dp,
    ) {
        val contentColor = if (banner.isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (banner.isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor,
            )
            Text(
                text = banner.text,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "안내 닫기",
                    modifier = Modifier.size(16.dp),
                    tint = contentColor,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(isRunning: Boolean, sttEnabled: Boolean) {
    val (title, hint) = when {
        !sttEnabled -> "원음 녹음 모드" to "번역 없이 WAV 파일만 저장합니다."
        isRunning -> "소리를 기다리는 중…" to "다른 앱에서 소리를 재생하면 여기에 번역이 나타납니다."
        else -> "아직 자막이 없습니다" to "아래 캡처 시작을 누르고 다른 앱에서 소리를 재생해 보세요."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Subtitles,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
