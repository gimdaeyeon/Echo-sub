package com.example.echosub.subtitle

import java.io.File
import java.util.Locale

/**
 * 자막 한 줄. 시간은 **녹음 파일 처음을 0으로 하는 오디오 기준 시각**이다
 * (벽시계 시각이 아니다 — STT 응답은 실제 발화보다 1~2초 늦게 도착하므로
 * 벽시계로 찍으면 재생할 때 자막이 밀린다).
 */
data class Cue(
    val startMs: Long,
    val endMs: Long,
    val sourceText: String,
    val translatedText: String? = null,
)

/**
 * WAV 옆에 나란히 두는 자막 파일 경로. 확장자만 갈아끼워 같은 이름을 쓰므로
 * 파일을 PC로 옮겨도 VLC 등이 자동으로 짝을 찾는다.
 */
fun subtitleFileFor(audioFile: File): File =
    File(audioFile.parentFile, audioFile.nameWithoutExtension + ".srt")

/**
 * SRT 직렬화. 번역문을 첫 줄, 원문을 둘째 줄에 둔다 — 외부 플레이어에서도
 * 번역이 위에 크게 보이고, 앱 화면의 위계(번역이 본문, 원문이 각주)와도 같다.
 */
fun List<Cue>.toSrt(): String = buildString {
    this@toSrt.forEachIndexed { index, cue ->
        append(index + 1).append('\n')
        append(formatSrtTime(cue.startMs))
        append(" --> ")
        append(formatSrtTime(cue.endMs)).append('\n')
        cue.translatedText?.takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
        append(cue.sourceText).append('\n')
        append('\n')
    }
}

/**
 * [toSrt]가 쓴 파일을 되읽는다. 블록의 본문이 두 줄이면 번역+원문, 한 줄이면
 * 번역이 아직 붙지 않은 원문으로 본다 ([toSrt]가 그렇게만 쓴다).
 *
 * 형식이 깨진 블록은 통째로 건너뛴다 — 자막이 몇 줄 빠지는 것이
 * 재생 자체가 실패하는 것보다 낫다.
 */
fun parseSrt(text: String): List<Cue> {
    val cues = mutableListOf<Cue>()
    for (block in text.split(Regex("\\r?\\n\\r?\\n"))) {
        val lines = block.trim().lines().filter { it.isNotBlank() }
        if (lines.size < 3) continue

        val (startMs, endMs) = parseSrtTimeLine(lines[1]) ?: continue
        val body = lines.drop(2)
        val cue = if (body.size >= 2) {
            Cue(startMs, endMs, sourceText = body.last(), translatedText = body.first())
        } else {
            Cue(startMs, endMs, sourceText = body.first(), translatedText = null)
        }
        cues += cue
    }
    return cues
}

/** 재생 위치에 해당하는 자막. 없으면 null (무음 구간). */
fun List<Cue>.cueAt(positionMs: Long): Cue? =
    lastOrNull { positionMs >= it.startMs && positionMs < it.endMs }

/**
 * 대본에서 "지금 여기까지 왔다"를 가리킬 줄의 인덱스. 아직 시작 전이면 -1.
 *
 * [cueAt]과 달리 무음 구간에서도 직전 줄을 계속 가리킨다 — 목록 하이라이트와
 * 자동 스크롤에 [cueAt]을 쓰면 대사 사이 침묵마다 하이라이트가 꺼졌다 켜지고
 * 스크롤이 튄다.
 */
fun List<Cue>.activeCueIndexAt(positionMs: Long): Int =
    indexOfLast { positionMs >= it.startMs }

private fun formatSrtTime(ms: Long): String {
    val safe = ms.coerceAtLeast(0)
    val hours = safe / 3_600_000
    val minutes = (safe / 60_000) % 60
    val seconds = (safe / 1_000) % 60
    val millis = safe % 1_000
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
}

private fun parseSrtTimeLine(line: String): Pair<Long, Long>? {
    val parts = line.split("-->")
    if (parts.size != 2) return null
    val start = parseSrtTime(parts[0].trim()) ?: return null
    val end = parseSrtTime(parts[1].trim()) ?: return null
    return start to end
}

private fun parseSrtTime(token: String): Long? {
    val match = Regex("(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})").matchEntire(token) ?: return null
    val (h, m, s, millis) = match.destructured
    return h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + millis.padEnd(3, '0').toLong()
}
