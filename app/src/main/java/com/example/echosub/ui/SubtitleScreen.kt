package com.example.echosub.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.echosub.service.CaptureState
import com.example.echosub.stt.TranscriptState

/**
 * 자막(대본) 전용 화면.
 *
 * 위계를 뒤집었다 — 예전 화면은 원문이 본문 크기, 번역이 그 아래 보조였는데
 * 이 앱을 쓰는 이유는 번역이므로 **번역문을 본문**으로 올리고 원문을 각주처럼 내렸다.
 */
@Composable
fun SubtitleScreen(
    status: CaptureState.Status,
    transcript: TranscriptState.Status,
    sttEnabled: Boolean,
    batteryOptimizationExempt: Boolean,
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

    val banners = buildList {
        if (!sttEnabled) {
            add(Banner("원음 녹음 모드입니다 — 번역이 꺼져 있습니다. 설정 › 고급에서 끌 수 있습니다.", isError = false))
        }
        if (status.isRunning && transcript.connectionState == TranscriptState.ConnectionState.PAUSED) {
            add(
                Banner(
                    "번역만 멈춰 있습니다 — 녹음은 계속되고 있습니다. 아래 \"번역 재개\"를 누르면 다시 번역합니다.",
                    isError = false,
                ),
            )
        }
        transcript.translatorMessage?.let { add(Banner("번역 엔진: $it", isError = true)) }
        transcript.errorMessage?.let { add(Banner("STT 오류: $it", isError = true)) }
        status.errorMessage?.let { add(Banner("캡처 오류: $it", isError = true)) }
        // 입력 레벨이 낮다는 건 상단의 레벨 바 색으로만 알린다 — 여기서 배너로 띄우면
        // 말이 끊길 때마다 나타났다 사라지며 화면이 계속 흔들렸다.
        if (transcript.reconnectCount > 0) {
            add(Banner("스트림 재연결 ${transcript.reconnectCount}회 — 정상 동작입니다.", isError = false))
        }
        if (status.isRunning && !batteryOptimizationExempt) {
            add(
                Banner(
                    "배터리 최적화 대상에서 제외돼 있지 않습니다 — 화면을 끄면 캡처가 중단될 수 있습니다. " +
                        "설정 탭에서 제외해 주세요.",
                    isError = false,
                ),
            )
        }
    }

    val isEmpty = entries.isEmpty() && transcript.interimText.isBlank()

    Column(modifier = Modifier.fillMaxSize()) {
        banners.forEach { BannerRow(it) }

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
                    verticalArrangement = Arrangement.spacedBy(18.dp),
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
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptEntryRow(entry: TranscriptState.Entry, isLatest: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            entry.translatedText != null -> Text(
                text = entry.translatedText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isLatest) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isLatest) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            entry.translationError != null -> Text(
                text = "번역 실패: ${entry.translationError}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )

            else -> Text(
                text = "번역 중…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = entry.sourceText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class Banner(val text: String, val isError: Boolean)

@Composable
private fun BannerRow(banner: Banner) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (banner.isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Text(
            text = banner.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (banner.isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
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
