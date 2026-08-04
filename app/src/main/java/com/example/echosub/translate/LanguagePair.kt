package com.example.echosub.translate

import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * 인식 언어 → 번역 대상 언어 조합.
 *
 * STT 언어 코드까지 여기서 함께 정의한다 — 소스 언어를 바꾸면 Google STT의 인식 언어도
 * 같이 바뀌어야 하기 때문에, 둘을 따로 두면 어긋나기 쉽다.
 */
enum class LanguagePair(
    val sttLanguageCode: String,
    val mlKitSource: String,
    val mlKitTarget: String,
    val deepLSource: String,
    val deepLTarget: String,
    val label: String,
) {
    JA_TO_KO(
        sttLanguageCode = "ja-JP",
        mlKitSource = TranslateLanguage.JAPANESE,
        mlKitTarget = TranslateLanguage.KOREAN,
        deepLSource = "JA",
        deepLTarget = "KO",
        label = "일본어 → 한국어",
    ),
    EN_TO_KO(
        sttLanguageCode = "en-US",
        mlKitSource = TranslateLanguage.ENGLISH,
        mlKitTarget = TranslateLanguage.KOREAN,
        deepLSource = "EN",
        deepLTarget = "KO",
        label = "영어 → 한국어",
    ),
}

/**
 * [label]은 칩에 들어가는 짧은 이름, [description]은 그 아래 한 줄 설명.
 * 칩 안에 설명까지 넣으면 두 줄로 접혀 목록이 지저분해진다.
 */
enum class TranslationEngine(val label: String, val description: String) {
    ML_KIT("ML Kit", "기기 안에서 번역합니다. 무료·오프라인, 첫 사용 시 모델을 내려받습니다."),
    DEEPL("DeepL", "클라우드 번역. 품질이 높지만 API 키와 인터넷이 필요합니다."),
}
