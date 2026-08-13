package com.example.echosub.overlay

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.LinearLayout

/**
 * 두 손가락 핀치를 박스 어디에서든 받아 [scaleDetector]로 넘기는 오버레이 루트.
 *
 * 예전에는 핀치를 손잡이 줄(높이 28dp)에서만 받았는데, 그 좁은 띠에 두 손가락을
 * 올리는 것이 사실상 불가능해서 "확대/축소가 안 된다"로 느껴졌다.
 *
 * 한 손가락 제스처(히스토리 스크롤, 버튼 클릭, 손잡이 드래그)는 평소처럼 자식들이
 * 가져가고, 손가락이 둘이 되는 순간부터만 이 루트가 가로챈다 — 스크롤과 핀치는
 * 손가락 수로 갈리므로 두 제스처가 충돌할 일이 없다.
 */
class PinchToResizeLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var scaleDetector: ScaleGestureDetector? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 가로채기 전의 이벤트도 전부 디텍터에 먹인다 — 두 번째 손가락이 닿는 순간
        // (POINTER_DOWN)을 디텍터가 봐야 초기 간격이 잡혀서, 가로챈 직후의 MOVE부터
        // 바로 스케일이 나온다. (자식이 DOWN을 안 가져간 경우 onTouchEvent에서 같은
        // DOWN을 한 번 더 먹이게 되는데, DOWN은 디텍터 상태를 리셋할 뿐이라 무해하다.)
        scaleDetector?.onTouchEvent(ev)
        return ev.pointerCount >= 2 || super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(ev)
        return true
    }
}
