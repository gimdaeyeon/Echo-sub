package com.example.echosub.caption

/**
 * 자막(원문)을 어디서 얻을지.
 *
 * [AUDIO_STT]는 기존 경로 — 재생 오디오를 Google STT로 인식한다.
 * [SCREEN_CAPTION]은 화면의 지정 영역을 주기적으로 캡처해 OCR로 되읽는다 —
 * 갤럭시 '실시간 자막'(시스템 Live Caption)이 이미 음성→텍스트를 해주고 있을 때,
 * 그 결과를 받아 번역만 얹는 경로다. STT 사용료가 들지 않는 대신
 * 시스템 실시간 자막을 사용자가 직접 켜 두어야 한다.
 *
 * enum 이름은 [com.example.echosub.data.AppSettings]가 그대로 저장하므로 바꾸면 안 된다.
 */
enum class SubtitleSource(val label: String, val description: String) {
    AUDIO_STT(
        "음성 인식",
        "재생 중인 소리를 Google STT로 인식해 번역합니다.",
    ),
    SCREEN_CAPTION(
        "화면 자막 읽기",
        "갤럭시 '실시간 자막' 등 화면에 표시되는 자막을 읽어 번역합니다. " +
            "시스템 실시간 자막을 먼저 켠 뒤 캡처를 시작하고, 자막이 표시되는 위치에 맞춰 영역을 지정하세요.",
    ),
}
