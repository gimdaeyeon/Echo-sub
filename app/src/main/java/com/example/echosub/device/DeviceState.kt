package com.example.echosub.device

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OS가 들고 있는, 이 앱에 대한 기기 상태. [com.example.echosub.service.CaptureState],
 * [com.example.echosub.stt.TranscriptState]와 동일한 싱글턴 StateFlow 패턴 —
 * 값 자체는 시스템 설정 화면에서도 바뀔 수 있어 [refreshBatteryOptimizationExempt]로
 * 필요할 때마다 다시 읽어와야 한다 (MainActivity.onResume 등).
 */
object DeviceState {

    private val _batteryOptimizationExempt = MutableStateFlow(false)
    val batteryOptimizationExempt: StateFlow<Boolean> = _batteryOptimizationExempt.asStateFlow()

    fun refreshBatteryOptimizationExempt(context: Context) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        _batteryOptimizationExempt.value = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
}
