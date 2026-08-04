package com.example.echosub.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.echosub.capture.readWavDurationMs
import com.example.echosub.subtitle.subtitleFileFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 캡처된 WAV 파일 관리 화면. [com.example.echosub.service.AudioCaptureService]가
 * `getExternalFilesDir(null)/captures`에 계속 쌓아두기만 하고 지우지 않으므로,
 * 여기서 직접 보고 지울 수 있게 한다.
 *
 * 파일 목록은 StateFlow로 공유할 이유가 없다 — 이 화면을 볼 때만 필요한 일회성 IO라
 * 다른 화면의 CaptureState/TranscriptState 패턴과 달리 로컬 상태로 충분하다.
 */
@Composable
fun HistoryScreen(
    activeOutputPaths: List<String>,
    onPlayFile: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf<List<CaptureFile>>(emptyList()) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<CaptureFile?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var confirmDeleteSelected by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<CaptureFile?>(null) }
    // 길게 눌러 들어가는 다중 선택 모드. 선택된 게 있으면 곧 선택 모드다 — 별도 플래그가 필요 없다.
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selectionMode = selectedPaths.isNotEmpty()

    LaunchedEffect(refreshTick) {
        files = withContext(Dispatchers.IO) { listCaptureFiles(context) }
    }

    fun deleteAndRefresh(paths: List<String>) {
        scope.launch {
            withContext(Dispatchers.IO) { paths.forEach { deleteCapture(it) } }
            refreshTick++
        }
    }

    fun toggleSelected(path: String) {
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }

    BackHandler(enabled = selectionMode) { selectedPaths = emptySet() }

    val deletableFiles = files.filter { it.path !in activeOutputPaths }
    val totalBytes = files.sumOf { it.sizeBytes }
    val selectedBytes = files.filter { it.path in selectedPaths }.sumOf { it.sizeBytes }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                IconButton(onClick = { selectedPaths = emptySet() }) {
                    Icon(Icons.Filled.Close, contentDescription = "선택 취소")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("${selectedPaths.size}개 선택", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = formatBytes(selectedBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { confirmDeleteSelected = true }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("삭제")
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    Text("저장된 파일 ${files.size}개", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = formatBytes(totalBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (deletableFiles.isNotEmpty()) {
                    TextButton(onClick = { confirmDeleteAll = true }) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("전체 삭제")
                    }
                }
            }
        }
        HorizontalDivider()

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "아직 저장된 파일이 없습니다.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files, key = { it.path }) { file ->
                    CaptureFileRow(
                        file = file,
                        isActive = file.path in activeOutputPaths,
                        selectionMode = selectionMode,
                        isSelected = file.path in selectedPaths,
                        onPlayClick = { onPlayFile(file.path) },
                        onRenameClick = { renameTarget = file },
                        onDeleteClick = { pendingDelete = file },
                        onToggleSelect = { toggleSelected(file.path) },
                        onLongPress = { toggleSelected(file.path) },
                    )
                }
                item { Spacer(modifier = Modifier.height(96.dp)) }
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            currentName = target.name,
            onDismiss = { renameTarget = null },
            onConfirm = { newBaseName ->
                renameTarget = null
                scope.launch {
                    withContext(Dispatchers.IO) { renameCapture(target.path, newBaseName) }
                    refreshTick++
                }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("파일 삭제") },
            text = { Text("\"${target.name}\" 파일을 삭제할까요? 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    deleteAndRefresh(listOf(target.path))
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
        )
    }

    if (confirmDeleteSelected) {
        AlertDialog(
            onDismissRequest = { confirmDeleteSelected = false },
            title = { Text("선택한 파일 삭제") },
            text = {
                Text("${selectedPaths.size}개 파일(${formatBytes(selectedBytes)})을 삭제할까요? 되돌릴 수 없습니다.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteSelected = false
                    val paths = selectedPaths.toList()
                    selectedPaths = emptySet()
                    deleteAndRefresh(paths)
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteSelected = false }) { Text("취소") }
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("전체 삭제") },
            text = {
                Text(
                    "삭제 가능한 파일 ${deletableFiles.size}개(${formatBytes(deletableFiles.sumOf { it.sizeBytes })})를 " +
                        "모두 삭제할까요? 되돌릴 수 없습니다.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    deleteAndRefresh(deletableFiles.map { it.path })
                }) { Text("전체 삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text("취소") }
            },
        )
    }
}

/**
 * 이름 바꾸기 대화상자. 확장자(.wav)는 건드리지 못하게 빼고 보여준다 —
 * 사용자가 알아보려고 붙이는 이름과, 재생·자막 짝 찾기에 쓰이는 확장자는 별개다.
 */
@Composable
private fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val currentBase = currentName.substringBeforeLast('.')
    var input by remember(currentName) { mutableStateOf(currentBase) }

    val trimmed = input.trim()
    val error = when {
        trimmed.isEmpty() -> "이름을 입력하세요."
        trimmed.any { it in ILLEGAL_NAME_CHARS } -> "다음 문자는 쓸 수 없습니다: \\ / : * ? \" < > |"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이름 바꾸기") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    isError = error != null,
                    suffix = { Text(".wav") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = error ?: "자막 파일도 같은 이름으로 함께 바뀝니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = error == null && trimmed != currentBase,
            ) { Text("변경") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CaptureFileRow(
    file: CaptureFile,
    isActive: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    onPlayClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 녹음 중인 파일은 아직 헤더가 확정되지 않아 재생·선택할 수 없다.
                // 길게 눌러 선택 모드로 들어가고, 그 안에서는 탭이 재생 대신 선택을 토글한다.
                .combinedClickable(
                    enabled = !isActive,
                    onClick = { if (selectionMode) onToggleSelect() else onPlayClick() },
                    onLongClick = onLongPress,
                )
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode && !isActive) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = file.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = buildString {
                        append(formatTimestamp(file.lastModified))
                        // 녹음 중인 파일은 헤더가 아직 패치되지 않아 길이를 알 수 없다.
                        if (!isActive) {
                            file.durationMs?.let {
                                append(" · ")
                                append(formatElapsed(it))
                            }
                        }
                        append(" · ")
                        append(formatBytes(file.sizeBytes))
                        if (file.hasSubtitles) append(" · 자막")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isActive) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = "녹음 중인 파일 — 삭제할 수 없습니다",
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!selectionMode) {
                IconButton(onClick = onRenameClick) {
                    Icon(
                        imageVector = Icons.Filled.DriveFileRenameOutline,
                        contentDescription = "이름 바꾸기",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "삭제",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private data class CaptureFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val hasSubtitles: Boolean,
    val durationMs: Long?,
)

/**
 * 목록에는 녹음(WAV)만 올린다. 자막(.srt)은 녹음에 딸린 것이라 따로 한 줄을 차지하면
 * 목록만 두 배로 길어진다 — 대신 각 줄에 "자막" 표시로 붙이고, 삭제도 함께 처리한다.
 */
private fun listCaptureFiles(context: Context): List<CaptureFile> {
    val dir = File(context.getExternalFilesDir(null), "captures")
    return dir.listFiles()
        ?.filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
        ?.map {
            CaptureFile(
                name = it.name,
                path = it.absolutePath,
                sizeBytes = it.length(),
                lastModified = it.lastModified(),
                hasSubtitles = subtitleFileFor(it).exists(),
                durationMs = readWavDurationMs(it),
            )
        }
        ?.sortedByDescending { it.lastModified }
        ?: emptyList()
}

/** 녹음을 지우면 짝인 자막도 같이 지운다 — 남겨두면 영영 쓸 데가 없다. */
private fun deleteCapture(path: String) {
    val audio = File(path)
    audio.delete()
    subtitleFileFor(audio).takeIf { it.exists() }?.delete()
}

private val ILLEGAL_NAME_CHARS = charArrayOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

/**
 * 녹음과 짝인 자막을 같은 이름으로 함께 바꾼다. 자막은 확장자만 다른 같은 이름으로
 * 찾으므로([subtitleFileFor]) 한쪽만 바꾸면 짝이 끊어져 재생 화면에서 자막이 사라진다.
 *
 * 이미 같은 이름이 있으면 뒤에 번호를 붙인다 — 이름을 새로 지어 달라고 되돌려 보내는 것보다,
 * 겹치지 않게 알아서 붙여주는 편이 개인용 앱에서는 덜 성가시다.
 */
private fun renameCapture(path: String, newBaseName: String) {
    val audio = File(path)
    val dir = audio.parentFile ?: return
    val extension = audio.extension

    var candidate = File(dir, "$newBaseName.$extension")
    var suffix = 1
    while (candidate.exists() && candidate.absolutePath != audio.absolutePath) {
        candidate = File(dir, "$newBaseName ($suffix).$extension")
        suffix++
    }
    if (candidate.absolutePath == audio.absolutePath) return

    val oldSubtitle = subtitleFileFor(audio)
    if (audio.renameTo(candidate) && oldSubtitle.exists()) {
        oldSubtitle.renameTo(subtitleFileFor(candidate))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTimestamp(epochMs: Long): String = timestampFormat.format(epochMs)
