package com.example.echosub.overlay

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 높이에 상한을 두는 것만 다른 평범한 [ScrollView].
 *
 * `wrap_content`만으로는 안의 내용만큼 한없이 늘어난다 — 자막 히스토리가 쌓이면 오버레이가
 * 화면 절반을 덮게 된다. [maxHeightPx]를 넘는 순간부터는 스크롤로 넘겨보게 하기 위해
 * [onMeasure]에서 높이 스펙만 잘라낸다 (`android:maxHeight`는 TextView 전용 속성이라
 * ScrollView에는 없다).
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    var maxHeightPx: Int = Int.MAX_VALUE

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cappedHeightSpec = if (maxHeightPx < Int.MAX_VALUE) {
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, cappedHeightSpec)
    }
}
