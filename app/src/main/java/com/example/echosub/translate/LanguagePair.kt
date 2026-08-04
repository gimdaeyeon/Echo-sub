package com.example.echosub.translate

import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * 인식 언어 → 번역 대상 언어 조합. 도착 언어는 항상 한국어다.
 *
 * STT 언어 코드까지 여기서 함께 정의한다 — 소스 언어를 바꾸면 Google STT의 인식 언어도
 * 같이 바뀌어야 하기 때문에, 둘을 따로 두면 어긋나기 쉽다.
 *
 * **수록 기준: ML Kit과 DeepL이 둘 다 지원하는 언어만 넣는다.** 한쪽만 되는 언어를 넣으면
 * 엔진을 바꾼 순간 번역이 조용히 실패한다 — 목록에 있으면 어느 엔진으로든 동작해야 한다.
 * (그래서 태국어·베트남어는 빠졌다. ML Kit은 되지만 DeepL이 지원하지 않는다.)
 *
 * 순서는 실제로 고를 법한 빈도순이다 — 목록이 길어졌으므로 자주 쓰는 것이 위에 있어야 한다.
 * enum 이름은 [com.example.echosub.data.AppSettings]가 그대로 저장하므로 바꾸면 안 된다.
 */
enum class LanguagePair(
    val sttLanguageCode: String,
    val mlKitSource: String,
    val deepLSource: String,
    /** 목록에 뜨는 이름. 도착 언어는 항상 한국어라 출발 언어만 적는다. */
    val label: String,
) {
    EN_TO_KO("en-US", TranslateLanguage.ENGLISH, "EN", "영어"),
    JA_TO_KO("ja-JP", TranslateLanguage.JAPANESE, "JA", "일본어"),
    ZH_TO_KO("cmn-Hans-CN", TranslateLanguage.CHINESE, "ZH", "중국어(간체)"),
    ES_TO_KO("es-ES", TranslateLanguage.SPANISH, "ES", "스페인어"),
    FR_TO_KO("fr-FR", TranslateLanguage.FRENCH, "FR", "프랑스어"),
    DE_TO_KO("de-DE", TranslateLanguage.GERMAN, "DE", "독일어"),
    RU_TO_KO("ru-RU", TranslateLanguage.RUSSIAN, "RU", "러시아어"),
    IT_TO_KO("it-IT", TranslateLanguage.ITALIAN, "IT", "이탈리아어"),
    PT_TO_KO("pt-BR", TranslateLanguage.PORTUGUESE, "PT", "포르투갈어"),
    ID_TO_KO("id-ID", TranslateLanguage.INDONESIAN, "ID", "인도네시아어"),
    NL_TO_KO("nl-NL", TranslateLanguage.DUTCH, "NL", "네덜란드어"),
    PL_TO_KO("pl-PL", TranslateLanguage.POLISH, "PL", "폴란드어"),
    TR_TO_KO("tr-TR", TranslateLanguage.TURKISH, "TR", "터키어"),
    UK_TO_KO("uk-UA", TranslateLanguage.UKRAINIAN, "UK", "우크라이나어"),
    SV_TO_KO("sv-SE", TranslateLanguage.SWEDISH, "SV", "스웨덴어"),
    DA_TO_KO("da-DK", TranslateLanguage.DANISH, "DA", "덴마크어"),
    FI_TO_KO("fi-FI", TranslateLanguage.FINNISH, "FI", "핀란드어"),
    NO_TO_KO("no-NO", TranslateLanguage.NORWEGIAN, "NB", "노르웨이어"),
    CS_TO_KO("cs-CZ", TranslateLanguage.CZECH, "CS", "체코어"),
    EL_TO_KO("el-GR", TranslateLanguage.GREEK, "EL", "그리스어"),
    HU_TO_KO("hu-HU", TranslateLanguage.HUNGARIAN, "HU", "헝가리어"),
    RO_TO_KO("ro-RO", TranslateLanguage.ROMANIAN, "RO", "루마니아어"),
    SK_TO_KO("sk-SK", TranslateLanguage.SLOVAK, "SK", "슬로바키아어"),
    SL_TO_KO("sl-SI", TranslateLanguage.SLOVENIAN, "SL", "슬로베니아어"),
    BG_TO_KO("bg-BG", TranslateLanguage.BULGARIAN, "BG", "불가리아어"),
    ET_TO_KO("et-EE", TranslateLanguage.ESTONIAN, "ET", "에스토니아어"),
    LV_TO_KO("lv-LV", TranslateLanguage.LATVIAN, "LV", "라트비아어"),
    LT_TO_KO("lt-LT", TranslateLanguage.LITHUANIAN, "LT", "리투아니아어"),
    ;

    val mlKitTarget: String get() = TranslateLanguage.KOREAN
    val deepLTarget: String get() = "KO"

    /** 어디로 번역되는지까지 밝혀야 하는 자리(선택 버튼, 로그)에 쓴다. */
    val displayName: String get() = "$label → 한국어"
}

/**
 * [label]은 칩에 들어가는 짧은 이름, [description]은 그 아래 한 줄 설명.
 * 칩 안에 설명까지 넣으면 두 줄로 접혀 목록이 지저분해진다.
 */
enum class TranslationEngine(val label: String, val description: String) {
    ML_KIT("ML Kit", "기기 안에서 번역합니다. 무료·오프라인, 첫 사용 시 모델을 내려받습니다."),
    DEEPL("DeepL", "클라우드 번역. 품질이 높지만 API 키와 인터넷이 필요합니다."),
}
