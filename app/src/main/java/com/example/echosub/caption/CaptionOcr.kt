package com.example.echosub.caption

import com.example.echosub.translate.LanguagePair
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * 언어 조합에 맞는 ML Kit 문자 인식기를 고른다.
 *
 * ML Kit 텍스트 인식은 문자계 단위 모델이다 — 라틴/중국어/일본어/한국어/데바나가리만
 * 지원하고 키릴·그리스 문자는 모델이 없다. 지원하지 않는 언어에서 라틴 모델로
 * 강행하면 오독한 텍스트가 번역까지 타고 내려가므로, null을 돌려 화면 자막 모드를
 * 시작 단계에서 막는다 ([unsupportedMessage]가 사용자에게 보일 이유 설명).
 */
internal object CaptionOcr {

    private val CYRILLIC_OR_GREEK = setOf(
        LanguagePair.RU_TO_KO,
        LanguagePair.UK_TO_KO,
        LanguagePair.BG_TO_KO,
        LanguagePair.EL_TO_KO,
    )

    fun recognizerFor(pair: LanguagePair): TextRecognizer? = when {
        pair == LanguagePair.JA_TO_KO ->
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        pair == LanguagePair.ZH_TO_KO ->
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        pair in CYRILLIC_OR_GREEK -> null
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun unsupportedMessage(pair: LanguagePair): String =
        "${pair.label}의 문자는 화면 자막 읽기(OCR)가 지원하지 않습니다. " +
            "자막 소스를 '음성 인식'으로 바꿔 주세요."
}
