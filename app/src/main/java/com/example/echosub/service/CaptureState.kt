package com.example.echosub.service

import com.example.echosub.capture.CaptureFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AudioCaptureService(백그라운드) → UI(MainActivity/Compose) 상태 전달용 싱글턴.
 *
 * 서비스와 액티비티가 완전히 분리된 컴포넌트이므로 바인딩 대신 프로세스 전역
 * StateFlow로 상태를 공유한다. Phase 1 개인용 프로젝트 범위에서는 이 방식이
 * 가장 단순하고 UI 재구성(화면 회전 등)에도 안전하다.
 */
object CaptureState {

    data class Status(
        val isRunning: Boolean = false,
        val format: CaptureFormat = CaptureFormat.HIFI,
        val elapsedMs: Long = 0L,
        val bytesWritten: Long = 0L,
        /** 0f(무음)~1f(풀스케일) 정규화된 RMS 레벨. 파일을 열어보기 전에 "소리가 들어오는지" 즉시 확인용. */
        val rmsLevel: Float = 0f,
        val outputPaths: List<String> = emptyList(),
        val errorMessage: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun update(transform: (Status) -> Status) {
        _status.value = transform(_status.value)
    }

    fun reset() {
        _status.value = Status()
    }
}
