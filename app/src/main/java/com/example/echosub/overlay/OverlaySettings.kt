package com.example.echosub.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 메인 화면(슬라이더) → 서비스 안의 [SubtitleOverlay]로 값을 전달하는 통로.
 * [com.example.echosub.service.CaptureState], [com.example.echosub.stt.TranscriptState]와
 * 동일한 싱글턴 StateFlow 패턴.
 *
 * 오버레이 핀치는 [boxWidthDp]와 [textSizeSp]를 **같은 배율로 함께** 바꾼다 —
 * PPT에서 모서리를 잡아 늘리듯 비율을 유지한 확대/축소로 느껴지게 하기 위해서다.
 * 설정 화면 슬라이더로는 두 값을 따로따로 미세 조정할 수 있다.
 */
object OverlaySettings {

    const val MIN_TEXT_SIZE_SP = 14f
    const val MAX_TEXT_SIZE_SP = 44f
    const val DEFAULT_TEXT_SIZE_SP = 22f

    const val MIN_BOX_WIDTH_DP = 160f
    const val MAX_BOX_WIDTH_DP = 900f
    const val DEFAULT_BOX_WIDTH_DP = 320f

    private val _textSizeSp = MutableStateFlow(DEFAULT_TEXT_SIZE_SP)
    val textSizeSp: StateFlow<Float> = _textSizeSp.asStateFlow()

    private val _boxWidthDp = MutableStateFlow(DEFAULT_BOX_WIDTH_DP)
    val boxWidthDp: StateFlow<Float> = _boxWidthDp.asStateFlow()

    fun setTextSize(sp: Float) {
        _textSizeSp.value = sp.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
    }

    /** 상한은 여기서 넉넉히 잡고, 실제 화면 폭을 넘지 않게 자르는 건 [SubtitleOverlay]가 한다. */
    fun setBoxWidth(dp: Float) {
        _boxWidthDp.value = dp.coerceIn(MIN_BOX_WIDTH_DP, MAX_BOX_WIDTH_DP)
    }
}
