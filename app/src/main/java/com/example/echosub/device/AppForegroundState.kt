package com.example.echosub.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [com.example.echosub.MainActivity]가 화면에 보이는 중인지. 앱 안에서 이미 자막을
 * 보고 있는 동안에는 [com.example.echosub.overlay.SubtitleOverlay]가 그 위에 겹쳐
 * 뜰 이유가 없어, 이 값으로 오버레이 표시 여부를 결정한다.
 */
object AppForegroundState {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    fun setForeground(foreground: Boolean) {
        _isForeground.value = foreground
    }
}
