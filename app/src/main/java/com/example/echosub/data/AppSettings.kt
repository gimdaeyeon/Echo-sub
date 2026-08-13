package com.example.echosub.data

import android.content.Context
import android.content.SharedPreferences
import com.example.echosub.caption.CaptionRegion
import com.example.echosub.caption.SubtitleSource
import com.example.echosub.capture.CaptureFormat
import com.example.echosub.overlay.OverlaySettings
import com.example.echosub.translate.LanguagePair
import com.example.echosub.translate.TranslationEngine
import com.example.echosub.util.enumByName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 사용자 설정을 디스크에 남기는 저장소.
 *
 * 이전에는 설정이 전부 Compose의 `rememberSaveable`에 있어서 앱을 내리면 사라졌다 —
 * 매번 포맷·언어·엔진·오버레이를 다시 고르는 게 이 앱의 가장 큰 불편이었다.
 * [CaptureState][com.example.echosub.service.CaptureState]/[OverlaySettings]와 같은
 * 싱글턴 StateFlow 패턴을 그대로 쓰되, 값이 바뀔 때마다 SharedPreferences에 write-through 한다.
 */
object AppSettings {

    /**
     * 사용자가 고르는 값들.
     *
     * 캡처 포맷은 사용자가 직접 고르지 않는다 — "원음으로 남길지"만 고르면
     * [captureFormat]이 거기서 따라 나온다 (16kHz 모노냐 44.1kHz 스테레오냐는
     * 사용자가 매번 판단할 문제가 아니다).
     *
     * 녹음 음질과 번역은 서로 독립이다. 원음으로 저장하면서 번역도 받을 수 있는데,
     * 이때 STT로는 [com.example.echosub.capture.PcmDownsampler]가 실시간으로 변환한
     * 16kHz 모노를 보내고 파일에는 원본이 그대로 들어간다.
     */
    data class Values(
        val languagePair: LanguagePair = LanguagePair.EN_TO_KO,
        val translationEngine: TranslationEngine = TranslationEngine.ML_KIT,
        val overlayEnabled: Boolean = true,
        val overlayTextSizeSp: Float = OverlaySettings.DEFAULT_TEXT_SIZE_SP,
        /** 자막 박스 폭. 글자 크기와 별개로 오버레이 핀치가 이 값만 바꾼다. */
        val overlayBoxWidthDp: Float = OverlaySettings.DEFAULT_BOX_WIDTH_DP,
        /** 켜면 44.1kHz 스테레오로 저장한다. 용량이 분당 약 10MB로 커진다. */
        val hifiRecordingMode: Boolean = false,
        /** 원음 녹음 시 16kHz 모노 변환본도 함께 저장 (리샘플러 검증용). */
        val save16kCompanion: Boolean = false,
        /** STT + 번역 사용 여부. 끄면 녹음만 한다. */
        val translationEnabled: Boolean = true,
        /**
         * 재생 화면을 가로로 눕혔을 때 좌우(false)로 나눌지 상하(true)로 쌓을지.
         * 세로 모드는 항상 위아래로 쌓으므로 이 값과 무관하다 — 가로에서만 의미가 있다.
         */
        val playerLandscapeStacked: Boolean = false,
        /** 원문을 어디서 얻을지 — 소리를 STT로 인식할지, 화면의 실시간 자막을 OCR로 읽을지. */
        val subtitleSource: SubtitleSource = SubtitleSource.AUDIO_STT,
        /** 화면 자막 읽기 모드에서 OCR 할 영역 (화면 비율). 영역 선택 오버레이가 갱신한다. */
        val captionRegion: CaptionRegion = CaptionRegion.DEFAULT,
    ) {
        val captureFormat: CaptureFormat
            get() = if (hifiRecordingMode) CaptureFormat.HIFI else CaptureFormat.STT_READY

        val sttEnabled: Boolean get() = translationEnabled

        /**
         * 오버레이 권한을 실제로 요청해야 하는 조합인지. 번역이 꺼져 있으면 띄울 자막이 없다.
         * 화면 자막 읽기 모드는 자막 오버레이를 꺼도 영역 선택 상자가 오버레이라 권한이 필요하다.
         */
        val needsOverlayPermission: Boolean
            get() = sttEnabled && (overlayEnabled || subtitleSource == SubtitleSource.SCREEN_CAPTION)
    }

    private const val PREFS_NAME = "echosub_settings"
    private const val KEY_LANGUAGE_PAIR = "language_pair"
    private const val KEY_ENGINE = "translation_engine"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    private const val KEY_OVERLAY_TEXT_SIZE = "overlay_text_size"
    private const val KEY_OVERLAY_BOX_WIDTH = "overlay_box_width"
    private const val KEY_HIFI_MODE = "hifi_recording_mode"
    private const val KEY_SAVE_16K = "save_16k_companion"
    private const val KEY_TRANSLATION_ENABLED = "translation_enabled"
    private const val KEY_PLAYER_LANDSCAPE_STACKED = "player_landscape_stacked"
    private const val KEY_SUBTITLE_SOURCE = "subtitle_source"
    private const val KEY_CAPTION_REGION_LEFT = "caption_region_left"
    private const val KEY_CAPTION_REGION_TOP = "caption_region_top"
    private const val KEY_CAPTION_REGION_RIGHT = "caption_region_right"
    private const val KEY_CAPTION_REGION_BOTTOM = "caption_region_bottom"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var prefs: SharedPreferences? = null

    private val _values = MutableStateFlow(Values())
    val values: StateFlow<Values> = _values.asStateFlow()

    /**
     * 액티비티/서비스 어느 쪽이 먼저 뜨든 한 번만 실행된다.
     * 저장된 값을 읽어 상태에 싣고, 오버레이 글자 크기는 [OverlaySettings]로 흘려보낸다.
     */
    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p

        _values.value = Values(
            languagePair = enumByName(p.getString(KEY_LANGUAGE_PAIR, null), LanguagePair.EN_TO_KO),
            translationEngine = enumByName(p.getString(KEY_ENGINE, null), TranslationEngine.ML_KIT),
            overlayEnabled = p.getBoolean(KEY_OVERLAY_ENABLED, true),
            overlayTextSizeSp = p.getFloat(KEY_OVERLAY_TEXT_SIZE, OverlaySettings.DEFAULT_TEXT_SIZE_SP),
            overlayBoxWidthDp = p.getFloat(KEY_OVERLAY_BOX_WIDTH, OverlaySettings.DEFAULT_BOX_WIDTH_DP),
            hifiRecordingMode = p.getBoolean(KEY_HIFI_MODE, false),
            save16kCompanion = p.getBoolean(KEY_SAVE_16K, false),
            translationEnabled = p.getBoolean(KEY_TRANSLATION_ENABLED, true),
            playerLandscapeStacked = p.getBoolean(KEY_PLAYER_LANDSCAPE_STACKED, false),
            subtitleSource = enumByName(p.getString(KEY_SUBTITLE_SOURCE, null), SubtitleSource.AUDIO_STT),
            captionRegion = CaptionRegion(
                left = p.getFloat(KEY_CAPTION_REGION_LEFT, CaptionRegion.DEFAULT.left),
                top = p.getFloat(KEY_CAPTION_REGION_TOP, CaptionRegion.DEFAULT.top),
                right = p.getFloat(KEY_CAPTION_REGION_RIGHT, CaptionRegion.DEFAULT.right),
                bottom = p.getFloat(KEY_CAPTION_REGION_BOTTOM, CaptionRegion.DEFAULT.bottom),
            ).sanitized(),
        )
        OverlaySettings.setTextSize(_values.value.overlayTextSizeSp)
        OverlaySettings.setBoxWidth(_values.value.overlayBoxWidthDp)

        // 글자 크기는 설정 화면 슬라이더가, 박스 폭은 오버레이 핀치가 바꾼다.
        // 둘 다 OverlaySettings를 단일 소스로 두고 이쪽이 그 결과를 따라가며 저장한다.
        bindLiveTunable(
            flow = OverlaySettings.textSizeSp,
            key = KEY_OVERLAY_TEXT_SIZE,
            get = { it.overlayTextSizeSp },
            copyWith = { values, sp -> values.copy(overlayTextSizeSp = sp) },
        )
        bindLiveTunable(
            flow = OverlaySettings.boxWidthDp,
            key = KEY_OVERLAY_BOX_WIDTH,
            get = { it.overlayBoxWidthDp },
            copyWith = { values, dp -> values.copy(overlayBoxWidthDp = dp) },
        )
    }

    /**
     * 값 자체는 [OverlaySettings]가 들고 있고(설정 슬라이더든 오버레이 핀치든 거기로 쓴다),
     * 이 함수는 그 결과를 구독해 [_values]에 반영하고 SharedPreferences에 써넣기만 한다.
     * 오버레이 글자 크기/박스 폭처럼 "실시간으로 바뀌면서 동시에 저장도 돼야 하는" 값에
     * 공통으로 쓰는 배선이라 값이 하나 늘어날 때마다 이 함수 호출 한 줄이면 된다.
     */
    private fun bindLiveTunable(
        flow: StateFlow<Float>,
        key: String,
        get: (Values) -> Float,
        copyWith: (Values, Float) -> Values,
    ) {
        scope.launch {
            flow.collect { newValue ->
                if (get(_values.value) != newValue) {
                    _values.value = copyWith(_values.value, newValue)
                    prefs?.edit()?.putFloat(key, newValue)?.apply()
                }
            }
        }
    }

    fun setLanguagePair(pair: LanguagePair) = update(
        transform = { it.copy(languagePair = pair) },
        persist = { putString(KEY_LANGUAGE_PAIR, pair.name) },
    )

    fun setTranslationEngine(engine: TranslationEngine) = update(
        transform = { it.copy(translationEngine = engine) },
        persist = { putString(KEY_ENGINE, engine.name) },
    )

    fun setOverlayEnabled(enabled: Boolean) = update(
        transform = { it.copy(overlayEnabled = enabled) },
        persist = { putBoolean(KEY_OVERLAY_ENABLED, enabled) },
    )

    /** 저장은 [OverlaySettings] 구독 쪽(bindLiveTunable)에서 처리된다 — 여기서는 단일 소스에만 쓴다. */
    fun setOverlayTextSize(sp: Float) = OverlaySettings.setTextSize(sp)
    fun setOverlayBoxWidth(dp: Float) = OverlaySettings.setBoxWidth(dp)

    fun setHifiRecordingMode(enabled: Boolean) = update(
        transform = { it.copy(hifiRecordingMode = enabled) },
        persist = { putBoolean(KEY_HIFI_MODE, enabled) },
    )

    fun setSave16kCompanion(enabled: Boolean) = update(
        transform = { it.copy(save16kCompanion = enabled) },
        persist = { putBoolean(KEY_SAVE_16K, enabled) },
    )

    fun setTranslationEnabled(enabled: Boolean) = update(
        transform = { it.copy(translationEnabled = enabled) },
        persist = { putBoolean(KEY_TRANSLATION_ENABLED, enabled) },
    )

    fun setPlayerLandscapeStacked(stacked: Boolean) = update(
        transform = { it.copy(playerLandscapeStacked = stacked) },
        persist = { putBoolean(KEY_PLAYER_LANDSCAPE_STACKED, stacked) },
    )

    fun setSubtitleSource(source: SubtitleSource) = update(
        transform = { it.copy(subtitleSource = source) },
        persist = { putString(KEY_SUBTITLE_SOURCE, source.name) },
    )

    fun setCaptionRegion(region: CaptionRegion) {
        val sanitized = region.sanitized()
        update(
            transform = { it.copy(captionRegion = sanitized) },
            persist = {
                putFloat(KEY_CAPTION_REGION_LEFT, sanitized.left)
                putFloat(KEY_CAPTION_REGION_TOP, sanitized.top)
                putFloat(KEY_CAPTION_REGION_RIGHT, sanitized.right)
                putFloat(KEY_CAPTION_REGION_BOTTOM, sanitized.bottom)
            },
        )
    }

    private inline fun update(
        transform: (Values) -> Values,
        persist: SharedPreferences.Editor.() -> Unit,
    ) {
        _values.value = transform(_values.value)
        prefs?.edit()?.apply(persist)?.apply()
    }
}
