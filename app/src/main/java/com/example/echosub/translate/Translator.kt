package com.example.echosub.translate

/**
 * 번역 엔진 추상화. ML Kit(온디바이스)와 DeepL(클라우드)을 같은 자리에 끼워 넣어
 * 실제 콘텐츠로 품질을 비교할 수 있게 한다.
 */
interface Translator : AutoCloseable {

    /**
     * 엔진을 사용 가능한 상태로 만든다 (ML Kit은 언어 모델 다운로드, DeepL은 키 검증).
     * 네트워크 I/O가 일어날 수 있으므로 IO 컨텍스트에서 호출해야 한다.
     *
     * @throws TranslatorUnavailableException 준비에 실패해 번역을 시작할 수 없는 경우
     */
    suspend fun prepare()

    /** @return 번역된 문자열. 실패 시 [TranslationFailedException]. */
    suspend fun translate(text: String): String

    override fun close() {}
}

/** 엔진 자체를 쓸 수 없는 상태 (키 미설정, 모델 다운로드 실패 등) — 사용자에게 안내해야 함 */
class TranslatorUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** 개별 문장 번역 실패 — 해당 문장만 건너뛰면 됨 */
class TranslationFailedException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
