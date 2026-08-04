package com.example.echosub.subtitle

import android.util.Log
import java.io.File

private const val TAG = "SubtitleRecorder"

/**
 * 녹음 한 건의 자막 전체를 모아 SRT 파일로 남긴다.
 *
 * [com.example.echosub.stt.TranscriptState]를 원본으로 쓸 수 없다 — 그쪽은 화면 표시용이라
 * 최근 50문장만 유지하므로 긴 녹음이면 앞부분이 잘린다. 여기서는 개수 제한 없이 전부 들고 있는다.
 *
 * 번역은 인식보다 늦게 도착하므로 [add]로 원문 자막을 먼저 만들어두고 [setTranslation]으로
 * 나중에 채운다. 변경이 있을 때마다 파일을 통째로 다시 쓰는데, 자막 파일은 길어야 수십 KB라
 * 비용이 사실상 없고 그 대가로 앱이 죽어도 그 직전까지의 자막이 디스크에 남는다.
 *
 * 캡처 루프(IO 스레드)와 번역 코루틴이 각각 호출하므로 모든 진입점을 동기화한다.
 */
class SubtitleRecorder(private val file: File) {

    private val cuesById = LinkedHashMap<Long, Cue>()

    @Synchronized
    fun add(id: Long, startMs: Long, endMs: Long, sourceText: String) {
        if (id < 0 || sourceText.isBlank()) return
        cuesById[id] = Cue(
            startMs = startMs,
            endMs = endMs.coerceAtLeast(startMs + MIN_CUE_MS),
            sourceText = sourceText.trim(),
        )
        write()
    }

    @Synchronized
    fun setTranslation(id: Long, translated: String) {
        val cue = cuesById[id] ?: return
        cuesById[id] = cue.copy(translatedText = translated.trim())
        write()
    }

    /** 자막이 하나도 없으면 빈 파일을 남기지 않는다 — 기록 탭에서 "자막 없음"으로 보이는 게 맞다. */
    @Synchronized
    fun finish() {
        if (cuesById.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        write()
    }

    private fun write() {
        try {
            file.writeText(cuesById.values.sortedBy { it.startMs }.toSrt())
        } catch (e: Exception) {
            // 자막을 못 써도 녹음 자체는 계속돼야 한다.
            Log.w(TAG, "자막 파일 쓰기 실패: ${file.name}", e)
        }
    }

    private companion object {
        /** 끝시각이 시작시각보다 앞서는 이상값이 와도 최소한 이만큼은 화면에 남게 한다. */
        const val MIN_CUE_MS = 500L
    }
}
