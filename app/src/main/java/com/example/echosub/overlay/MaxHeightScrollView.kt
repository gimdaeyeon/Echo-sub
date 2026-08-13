package com.example.echosub.overlay

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * 높이에 상한을 두는 것만 다른 평범한 [ScrollView].
 *
 * `wrap_content`만으로는 안의 내용만큼 한없이 늘어난다 — 자막 히스토리가 쌓이면 오버레이가
 * 화면 절반을 덮게 된다. 상한을 넘는 순간부터는 스크롤로 넘겨보게 하기 위해
 * [onMeasure]에서 높이 스펙만 잘라낸다 (`android:maxHeight`는 TextView 전용 속성이라
 * ScrollView에는 없다).
 *
 * 상한은 [maxHeightPx](고정값)와 [maxScreenHeightFraction](화면 높이 비례) 중 작은 쪽이다.
 * 비례 상한을 **측정 시점마다** 다시 계산하는 것이 중요하다 — 오버레이 창은 화면을
 * 회전해도 다시 만들어지지 않아서, 세로 화면 기준으로 한 번 굳힌 값은 가로로 눕힌
 * 화면(높이가 절반 이하)에서 그대로 화면을 넘겨버린다.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    var maxHeightPx: Int = Int.MAX_VALUE

    /** 화면 높이 대비 상한 비율 (1f = 비례 상한 없음). */
    var maxScreenHeightFraction: Float = 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fractionCapPx = if (maxScreenHeightFraction < 1f) {
            (resources.displayMetrics.heightPixels * maxScreenHeightFraction).toInt()
        } else {
            Int.MAX_VALUE
        }
        val capPx = minOf(maxHeightPx, fractionCapPx)
        val cappedHeightSpec = if (capPx < Int.MAX_VALUE) {
            MeasureSpec.makeMeasureSpec(capPx, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, cappedHeightSpec)
    }
}
