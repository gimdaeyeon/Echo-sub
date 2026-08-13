package com.example.echosub.caption

/**
 * 화면에서 자막을 읽어올 영역. 화면 크기에 대한 비율(0..1)로 저장한다 —
 * 픽셀로 저장하면 회전하거나 해상도가 다른 화면에서 엉뚱한 곳을 읽는다.
 */
data class CaptionRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** 저장값이 손상됐거나 뒤집혀 있어도 읽을 수 있는 영역으로 되돌린다. */
    fun sanitized(): CaptionRegion {
        val l = left.coerceIn(0f, 1f)
        val t = top.coerceIn(0f, 1f)
        val r = right.coerceIn(0f, 1f)
        val b = bottom.coerceIn(0f, 1f)
        if (r - l < MIN_SIZE || b - t < MIN_SIZE) return DEFAULT
        return CaptionRegion(l, t, r, b)
    }

    companion object {
        /** 이보다 작은 영역은 자막이 들어갈 수 없다 — 손상된 저장값 판정 기준. */
        private const val MIN_SIZE = 0.05f

        /** 시스템 실시간 자막의 기본 위치(화면 중하단)를 넉넉히 덮는 시작값. */
        val DEFAULT = CaptionRegion(left = 0.05f, top = 0.55f, right = 0.95f, bottom = 0.82f)
    }
}
