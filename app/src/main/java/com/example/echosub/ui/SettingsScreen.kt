package com.example.echosub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import com.example.echosub.caption.SubtitleSource
import com.example.echosub.data.AppSettings
import com.example.echosub.overlay.OverlaySettings
import com.example.echosub.translate.LanguagePair
import com.example.echosub.translate.TranslationEngine

/**
 * 설정 전용 화면.
 *
 * 예전에는 "HiFi 44.1kHz 스테레오 / STT용 16kHz 모노" 라디오가 첫 화면 맨 위에 있었다.
 * 샘플레이트와 채널 수는 사용자가 매번 판단할 문제가 아니므로, **"번역"이 기본**이고
 * 원음 녹음은 고급 섹션의 스위치 하나로 내렸다 — 포맷은 거기서 자동으로 따라온다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    values: AppSettings.Values,
    isRunning: Boolean,
    outputPaths: List<String>,
    batteryOptimizationExempt: Boolean,
    onRequestBatteryExemption: () -> Unit,
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var showLanguagePicker by rememberSaveable { mutableStateOf(false) }
    val translateEnabled = values.sttEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "설정은 자동으로 저장되어 다음 실행에도 유지됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )

        // 이미 예외 처리돼 있으면 더 볼 것 없으니 카드 자체를 숨긴다 — 계속 신경 쓸 항목이 아니다.
        if (!batteryOptimizationExempt) {
            SettingsCard(title = "배터리 최적화", icon = Icons.Filled.BatteryAlert, enabled = true) {
                Text(
                    "화면을 끄거나 다른 앱을 오래 쓰면 OS가 캡처를 강제로 멈출 수 있습니다. " +
                        "예외로 등록하면 백그라운드에서도 끊기지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onRequestBatteryExemption) {
                    Text("배터리 최적화 대상에서 제외하기")
                }
            }
        }

        SettingsCard(title = "번역", icon = Icons.Filled.Translate, enabled = translateEnabled) {
            SwitchRow(
                title = "STT + 번역 사용",
                subtitle = "끄면 녹음만 합니다. 녹음 음질과는 무관합니다.",
                checked = values.translationEnabled,
                enabled = !isRunning,
                onCheckedChange = { AppSettings.setTranslationEnabled(it) },
            )

            Label("자막 소스")
            ChoiceRow(
                options = SubtitleSource.entries,
                selected = values.subtitleSource,
                labelOf = { it.label },
                enabled = translateEnabled && !isRunning,
                onSelect = { AppSettings.setSubtitleSource(it) },
            )
            Text(
                text = values.subtitleSource.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Label("언어")
            // 언어가 늘어나면서(28개) 칩 한 줄에 다 못 담는다 — 목록 다이얼로그로 골라서 고른다.
            LanguagePickerRow(
                selected = values.languagePair,
                enabled = translateEnabled && !isRunning,
                onClick = { showLanguagePicker = true },
            )

            Label("엔진")
            ChoiceRow(
                options = TranslationEngine.entries,
                selected = values.translationEngine,
                labelOf = { it.label },
                enabled = translateEnabled && !isRunning,
                onSelect = { AppSettings.setTranslationEngine(it) },
            )
            Text(
                text = values.translationEngine.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isRunning) {
                Text(
                    "캡처 중에는 언어·엔진을 바꿀 수 없습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsCard(title = "자막 오버레이", icon = Icons.Filled.Subtitles, enabled = translateEnabled) {
            SwitchRow(
                title = "다른 앱 위에 자막 표시",
                subtitle = "유튜브 등을 보면서 번역을 읽습니다 (오버레이 권한 필요)",
                checked = values.overlayEnabled,
                enabled = translateEnabled && !isRunning,
                onCheckedChange = { AppSettings.setOverlayEnabled(it) },
            )

            AnimatedVisibility(visible = values.overlayEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 크기는 캡처 중에도 조절 가능해야 한다 — 자막을 보면서 맞춰야 의미가 있다.
                    Label("글자 크기 · ${values.overlayTextSizeSp.toInt()}sp")
                    Slider(
                        value = values.overlayTextSizeSp,
                        onValueChange = { AppSettings.setOverlayTextSize(it) },
                        valueRange = OverlaySettings.MIN_TEXT_SIZE_SP..OverlaySettings.MAX_TEXT_SIZE_SP,
                    )

                    Label("박스 폭 · ${values.overlayBoxWidthDp.toInt()}dp")
                    Slider(
                        value = values.overlayBoxWidthDp,
                        onValueChange = { AppSettings.setOverlayBoxWidth(it) },
                        valueRange = OverlaySettings.MIN_BOX_WIDTH_DP..OverlaySettings.MAX_BOX_WIDTH_DP,
                    )
                    Text(
                        "박스 폭은 자막을 두 손가락으로 집어서 바로 조절할 수도 있습니다. " +
                            "글자 크기는 위 슬라이더로만 바뀝니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 고급 ────────────────────────────────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { advancedExpanded = !advancedExpanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "고급 · 녹음 음질",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                )
                Icon(
                    imageVector = if (advancedExpanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = if (advancedExpanded) "접기" else "펼치기",
                )
            }

            AnimatedVisibility(visible = advancedExpanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider()

                    SwitchRow(
                        title = "원음으로 저장",
                        subtitle = "44.1kHz 스테레오 WAV. 분당 약 10MB로 커집니다. " +
                            "번역은 그대로 동작합니다 (STT로는 16kHz로 변환해 보냅니다).",
                        checked = values.hifiRecordingMode,
                        enabled = !isRunning,
                        onCheckedChange = { AppSettings.setHifiRecordingMode(it) },
                    )

                    AnimatedVisibility(visible = values.hifiRecordingMode) {
                        SwitchRow(
                            title = "16kHz 모노 변환본도 저장",
                            subtitle = "리샘플러 검증용",
                            checked = values.save16kCompanion,
                            enabled = !isRunning,
                            onCheckedChange = { AppSettings.setSave16kCompanion(it) },
                        )
                    }

                    if (outputPaths.isNotEmpty()) {
                        HorizontalDivider()
                        Label("저장 경로")
                        SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                outputPaths.forEach { path ->
                                    Text(
                                        text = path,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 마지막 카드를 FAB 위로 스크롤해 올릴 수 있을 만큼 비워둔다.
        Spacer(modifier = Modifier.height(120.dp))
    }

    if (showLanguagePicker) {
        LanguagePickerDialog(
            selected = values.languagePair,
            onDismiss = { showLanguagePicker = false },
            onSelect = {
                AppSettings.setLanguagePair(it)
                showLanguagePicker = false
            },
        )
    }
}

/** 현재 고른 언어를 보여주고 누르면 [LanguagePickerDialog]를 여는 줄. */
@Composable
private fun LanguagePickerRow(
    selected: LanguagePair,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = selected.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowDown,
            contentDescription = "언어 선택",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 출발 언어 28개를 스크롤 목록에서 고른다. 도착 언어는 항상 한국어라 목록에 따로 안 보여준다. */
@Composable
private fun LanguagePickerDialog(
    selected: LanguagePair,
    onDismiss: () -> Unit,
    onSelect: (LanguagePair) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("출발 언어") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(LanguagePair.entries, key = { it.name }) { pair ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(pair) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = pair == selected, onClick = { onSelect(pair) })
                        Text(
                            text = pair.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("닫기") }
        },
    )
}

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            content()
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                label = {
                    Text(
                        text = labelOf(option),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
