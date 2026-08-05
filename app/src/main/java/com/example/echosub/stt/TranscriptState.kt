package com.example.echosub.stt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * SttStreamingClient/번역기(백그라운드) → UI 상태 전달용 싱글턴.
 * Phase 1의 [com.example.echosub.service.CaptureState]와 동일한 패턴.
 */
object TranscriptState {

    enum class ConnectionState { IDLE, CONNECTING, CONNECTED, ERROR, PAUSED }

    /**
     * 확정된 인식 문장 하나.
     *
     * 번역은 인식보다 늦게 도착하므로, 먼저 [sourceText]만 담아 추가해두고
     * 나중에 [setTranslation]으로 같은 [id]를 찾아 채워 넣는다.
     */
    data class Entry(
        val id: Long,
        val sourceText: String,
        val translatedText: String? = null,
        val translationError: String? = null,
        val timestampMs: Long = System.currentTimeMillis(),
        /**
         * 녹음 파일 처음을 0으로 하는 오디오 기준 구간. 나중에 녹음을 재생하면서 자막을
         * 같은 시점에 띄우기 위한 값이라 [timestampMs](벽시계)와는 별개다 —
         * STT 결과는 실제 발화보다 늦게 도착하므로 벽시계로는 재생 위치를 맞출 수 없다.
         */
        val audioStartMs: Long = 0L,
        val audioEndMs: Long = 0L,
    )

    data class Status(
        val connectionState: ConnectionState = ConnectionState.IDLE,
        val finalEntries: List<Entry> = emptyList(),
        val interimText: String = "",
        val reconnectCount: Int = 0,
        val errorMessage: String? = null,
        /** 번역 엔진 준비 상태 안내 (모델 다운로드 중, 키 미설정 등) */
        val translatorMessage: String? = null,
    )

    private const val MAX_ENTRIES = 50

    private val idGenerator = AtomicLong(0)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun update(transform: (Status) -> Status) {
        _status.value = transform(_status.value)
    }

    /** @return 나중에 [setTranslation]으로 번역을 채워 넣을 때 쓸 엔트리 id. 빈 문자열이면 -1. */
    fun appendFinal(text: String, audioStartMs: Long = 0L, audioEndMs: Long = 0L): Long {
        if (text.isBlank()) return -1
        val id = idGenerator.incrementAndGet()
        val entry = Entry(
            id = id,
            sourceText = text.trim(),
            audioStartMs = audioStartMs,
            audioEndMs = audioEndMs,
        )
        update {
            it.copy(
                finalEntries = (it.finalEntries + entry).takeLast(MAX_ENTRIES),
                interimText = "",
            )
        }
        return id
    }

    /**
     * 이미 추가한 문장에 뒷부분을 이어 붙인다.
     *
     * STT 확정 결과는 문장 중간에서 끊겨 오는 일이 잦은데, 조각마다 새 엔트리를 만들면
     * 번역기가 문장 반 토막을 문맥 없이 번역하게 된다. 조각이 이어지는 동안에는 같은
     * 엔트리를 키워 두었다가, 문장이 닫힌 뒤에 한 번만 번역한다.
     */
    fun extendFinal(id: Long, mergedText: String, audioEndMs: Long) {
        updateEntry(id) { it.copy(sourceText = mergedText.trim(), audioEndMs = audioEndMs) }
        update { it.copy(interimText = "") }
    }

    fun setTranslation(id: Long, translated: String) =
        updateEntry(id) { it.copy(translatedText = translated) }

    fun setTranslationError(id: Long, message: String) =
        updateEntry(id) { it.copy(translationError = message) }

    private fun updateEntry(id: Long, transform: (Entry) -> Entry) {
        if (id < 0) return
        update { status ->
            status.copy(
                finalEntries = status.finalEntries.map { entry ->
                    if (entry.id == id) transform(entry) else entry
                }
            )
        }
    }

    fun reset() {
        _status.value = Status()
    }
}
