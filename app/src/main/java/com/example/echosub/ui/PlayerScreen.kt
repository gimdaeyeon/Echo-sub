package com.example.echosub.ui

import android.content.res.Configuration
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.echosub.subtitle.Cue
import com.example.echosub.subtitle.activeCueIndexAt
import com.example.echosub.subtitle.cueAt
import com.example.echosub.subtitle.parseSrt
import com.example.echosub.subtitle.subtitleFileFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 재생 위치를 자막에 반영하는 주기. 사람 눈에는 이 정도면 "바로 바뀐다"로 보인다. */
private const val POSITION_POLL_MS = 100L

/**
 * 녹음 파일을 재생하면서 그 시점의 자막을 띄우는 화면.
 *
 * 자막은 녹음할 때 WAV 옆에 남겨둔 SRT에서 읽는다 ([subtitleFileFor]). 시간이
 * 벽시계가 아니라 **오디오 기준**으로 기록돼 있어서 재생 위치와 그대로 맞물린다.
 *
 * 재생은 프레임워크 [MediaPlayer]로 충분하다 — WAV 로컬 파일 하나를 앞뒤로 움직이며
 * 트는 게 전부라 ExoPlayer를 끌어올 이유가 없다.
 */
@Composable
fun PlayerScreen(
    filePath: String,
    fileName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 가로 모드에서 좌우 대신 상하로 쌓을지. 세로 모드는 항상 상하라 이 값과 무관하다. */
    landscapeStacked: Boolean = false,
    onToggleLandscapeStacked: () -> Unit = {},
) {
    BackHandler(onBack = onBack)

    var cues by remember(filePath) { mutableStateOf<List<Cue>>(emptyList()) }
    var subtitlesLoaded by remember(filePath) { mutableStateOf(false) }

    LaunchedEffect(filePath) {
        cues = withContext(Dispatchers.IO) {
            val srt = subtitleFileFor(File(filePath))
            if (srt.exists()) runCatching { parseSrt(srt.readText()) }.getOrDefault(emptyList())
            else emptyList()
        }
        subtitlesLoaded = true
    }

    val player = remember(filePath) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
            }
        }.getOrNull()
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    var isPlaying by remember(filePath) { mutableStateOf(false) }
    var positionMs by remember(filePath) { mutableLongStateOf(0L) }
    // 사용자가 시크바를 잡고 있는 동안에는 재생 위치가 손가락을 밀어내지 않도록 폴링을 멈춘다.
    var scrubbingMs by remember(filePath) { mutableStateOf<Long?>(null) }

    // 프로세스가 죽었다 살아나도 듣던 지점으로 돌아오도록 저장해둔다.
    var savedPositionMs by rememberSaveable(filePath) { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        if (player != null && savedPositionMs > 0) {
            player.seekTo(savedPositionMs.toInt())
            positionMs = savedPositionMs
        }
    }
    LaunchedEffect(positionMs) { savedPositionMs = positionMs }

    val durationMs = player?.duration?.toLong() ?: 0L

    LaunchedEffect(player, isPlaying) {
        while (isPlaying && player != null) {
            if (scrubbingMs == null) positionMs = player.currentPosition.toLong()
            if (!player.isPlaying) isPlaying = false
            delay(POSITION_POLL_MS)
        }
    }

    val displayMs = scrubbingMs ?: positionMs
    val currentCue = cues.cueAt(displayMs)
    val activeIndex = cues.activeCueIndexAt(displayMs)

    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun seekTo(ms: Long) {
        player?.seekTo(ms.toInt())
        positionMs = ms
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
            }
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            // 세로 모드는 항상 위아래로 쌓이므로 이 버튼은 가로일 때만 의미가 있다.
            if (isLandscape) {
                IconButton(onClick = onToggleLandscapeStacked) {
                    Icon(
                        imageVector = if (landscapeStacked) Icons.Filled.ViewColumn else Icons.Filled.ViewAgenda,
                        contentDescription = if (landscapeStacked) "좌우로 나누기" else "위아래로 쌓기",
                    )
                }
            }
        }
        HorizontalDivider()

        if (player == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "이 파일을 재생할 수 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            return@Column
        }

        val nowPlaying = @Composable { panelModifier: Modifier, fillHeight: Boolean ->
            NowPlayingPanel(
                modifier = panelModifier,
                fillHeight = fillHeight,
                subtitlesLoaded = subtitlesLoaded,
                cues = cues,
                currentCue = currentCue,
                displayMs = displayMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                onScrub = { scrubbingMs = it },
                onScrubFinished = {
                    scrubbingMs?.let { seekTo(it) }
                    scrubbingMs = null
                },
                onTogglePlay = {
                    if (player.isPlaying) {
                        player.pause()
                        isPlaying = false
                    } else {
                        player.start()
                        isPlaying = true
                    }
                },
            )
        }

        val transcript = @Composable { listModifier: Modifier ->
            CueList(
                modifier = listModifier,
                cues = cues,
                activeIndex = activeIndex,
                onCueClick = { seekTo(it.startMs) },
            )
        }

        // 가로에서는 세로 공간이 절반 이하로 줄어든다. 위아래로 쌓으면 대본이
        // 몇 줄밖에 안 보이므로 기본은 좌우로 갈라 각자 높이를 다 쓰게 한다 —
        // 다만 이건 취향 차이라 상단 버튼으로 위아래 쌓기를 고를 수 있게 열어둔다.
        if (isLandscape && landscapeStacked) {
            // 세로 모드의 Column과 다르다 — 그건 높이가 넉넉해 재생 조작부가 필요한
            // 만큼만 차지해도 되지만, 가로는 높이가 짧아 그대로 재사용하면 재생 버튼이
            // 화면 밖으로 밀려난다. 좌우 분할과 똑같이 weight(1f)로 절반씩 나눠 확보한다.
            Column(modifier = Modifier.fillMaxSize()) {
                nowPlaying(Modifier.weight(1f).fillMaxWidth(), true)
                if (cues.isNotEmpty()) {
                    HorizontalDivider()
                    transcript(Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else if (isLandscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                nowPlaying(Modifier.weight(1f).fillMaxHeight(), true)
                if (cues.isNotEmpty()) {
                    VerticalDivider()
                    transcript(Modifier.weight(1f).fillMaxHeight())
                }
            }
        } else {
            nowPlaying(Modifier.fillMaxWidth(), false)
            if (cues.isNotEmpty()) {
                HorizontalDivider()
                transcript(Modifier.fillMaxSize())
            }
        }
    }
}

/** 지금 들리는 대사 + 재생 조작. 이 화면을 여는 이유라 가장 큰 자리를 준다. */
@Composable
private fun NowPlayingPanel(
    modifier: Modifier,
    /** 높이를 꽉 채우는 배치인지 (가로 모드). 자막 영역이 남는 높이를 흡수해 조작부가 잘리지 않게 한다. */
    fillHeight: Boolean,
    subtitlesLoaded: Boolean,
    cues: List<Cue>,
    currentCue: Cue?,
    displayMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onTogglePlay: () -> Unit,
) {
    Column(
        // 지금 들리는 대사 영역에만 은은한 세로 그라데이션을 깔아 "무대"처럼 띄운다 —
        // 대본 목록과 같은 흰 면이면 어디를 봐야 하는지 화면이 말해주지 않는다.
        modifier = modifier.background(
            Brush.verticalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    Color.Transparent,
                ),
            ),
        ),
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.weight(1f) else Modifier.heightIn(min = 140.dp))
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !subtitlesLoaded -> Unit

                cues.isEmpty() -> Text(
                    text = "이 녹음에는 자막이 없습니다.\n번역을 켜고 녹음하면 자막이 함께 저장됩니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                currentCue == null -> Text(
                    text = "…",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = currentCue.translatedText ?: currentCue.sourceText,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    if (currentCue.translatedText != null) {
                        Text(
                            text = currentCue.sourceText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            Slider(
                value = displayMs.toFloat().coerceIn(0f, durationMs.coerceAtLeast(1L).toFloat()),
                onValueChange = { onScrub(it.toLong()) },
                onValueChangeFinished = onScrubFinished,
                valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = formatElapsed(displayMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatElapsed(durationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                FilledIconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(64.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "일시정지" else "재생",
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }
}

/** 전체 대본. 줄을 누르면 그 대사가 나오는 지점으로 건너뛴다. */
@Composable
private fun CueList(
    modifier: Modifier,
    cues: List<Cue>,
    activeIndex: Int,
    onCueClick: (Cue) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 재생을 따라 목록도 같이 내려간다 — 지금 어디를 듣고 있는지 놓치지 않도록.
    // 화면 맨 위에 딱 붙이면 다음 줄만 보이고 흐름을 놓치므로 조금 위를 남겨둔다.
    LaunchedEffect(activeIndex) {
        if (activeIndex < 0) return@LaunchedEffect
        val viewportHeight = listState.layoutInfo.viewportSize.height
        scope.launch {
            listState.animateScrollToItem(
                index = activeIndex,
                scrollOffset = -(viewportHeight / 3),
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 시작 시각을 키로 쓰면 안 된다 — 단어 시각을 못 받아 시작점이 겹치는 자막이
        // 두 개 생기면 LazyColumn이 중복 키로 죽는다.
        items(cues.size, key = { it }) { index ->
            CueRow(
                cue = cues[index],
                isActive = index == activeIndex,
                onClick = { onCueClick(cues[index]) },
            )
        }
    }
}

@Composable
private fun CueRow(cue: Cue, isActive: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
    ) {
        Text(
            text = formatElapsed(cue.startMs),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .width(48.dp)
                .padding(top = 2.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = cue.translatedText ?: cue.sourceText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            if (cue.translatedText != null) {
                Text(
                    text = cue.sourceText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
