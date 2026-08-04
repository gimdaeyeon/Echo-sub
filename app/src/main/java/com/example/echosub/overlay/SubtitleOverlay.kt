package com.example.echosub.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.example.echosub.R

private const val TAG = "SubtitleOverlay"

/**
 * 다른 앱 위에 뜨는 번역 자막 창.
 *
 * Compose 대신 일반 View를 쓴다 — ComposeView를 WindowManager에 직접 붙이려면
 * ViewTreeLifecycleOwner/SavedStateRegistryOwner/ViewModelStoreOwner를 손수 심어줘야 하는데,
 * 이 오버레이는 TextView 하나와 아이콘 두 개가 전부라 그 배선을 감당할 이유가 없다.
 *
 * 호출 규칙: [show]/[setText]/[setTextSize]/[setBoxWidth]/[setPaused]/[hide] 모두
 * 메인 스레드에서 호출해야 한다 (WindowManager 요구사항).
 * 호출자([com.example.echosub.service.AudioCaptureService])가 Dispatchers.Main에서 부른다.
 *
 * [onOpenApp]/[onTogglePause]는 다른 앱을 보는 동안 EchoSub를 따로 찾아 열지 않고
 * 오버레이에서 바로 앱으로 돌아가거나 인식을 멈출 수 있게 하기 위한 것이다.
 */
class SubtitleOverlay(
    private val context: Context,
    private val onOpenApp: () -> Unit,
    private val onTogglePause: () -> Unit,
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View? = null
    private var textView: TextView? = null
    private var pauseButton: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isPaused = false

    fun show() {
        if (rootView != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_subtitle, null)
        val text = view.findViewById<TextView>(R.id.overlay_text)
        text.text = context.getString(R.string.overlay_waiting)

        val openAppButton = view.findViewById<ImageView>(R.id.overlay_btn_open_app)
        openAppButton.setOnClickListener { onOpenApp() }

        val pauseBtn = view.findViewById<ImageView>(R.id.overlay_btn_toggle_pause)
        pauseBtn.setOnClickListener { onTogglePause() }
        pauseButton = pauseBtn
        applyPausedAppearance(pauseBtn, isPaused)

        val params = WindowManager.LayoutParams(
            widthPxFor(OverlaySettings.boxWidthDp.value),
            WindowManager.LayoutParams.WRAP_CONTENT,
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // NOT_FOCUSABLE 없으면 오버레이가 포커스를 가로채 아래 앱의 뒤로가기/키보드가 죽는다.
            // TOUCHABLE은 유지 — 드래그로 옮길 수 있어야 한다.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (context.resources.displayMetrics.heightPixels * 0.7f).toInt()
        }

        attachTouchHandlers(view, params)

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // 권한 미허용 상태에서 호출된 경우 등 — 여기서 죽으면 서비스 전체가 죽으므로 반드시 잡는다.
            Log.e(TAG, "오버레이 창 추가 실패", e)
            return
        }

        rootView = view
        textView = text
        layoutParams = params
        Log.i(TAG, "오버레이 표시 시작")
    }

    fun setText(text: String) {
        textView?.text = text
    }

    fun setTextSize(sp: Float) {
        textView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    /** 박스 폭만 바꾼다 — 글자 크기는 [setTextSize]가 따로 담당한다. */
    fun setBoxWidth(dp: Float) {
        val view = rootView ?: return
        val params = layoutParams ?: return
        val widthPx = widthPxFor(dp)
        if (params.width == widthPx) return
        params.width = widthPx
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "박스 폭 적용 실패", e)
        }
    }

    /**
     * 앱 안에 들어와 있는 동안에는 오버레이를 숨긴다 — 이미 화면에 자막이 보이는데
     * 그 위에 또 띄울 이유가 없다. 드래그로 잡아둔 위치/폭을 잃지 않도록 창을
     * 제거(removeView)하는 대신 안 보이게+터치 통과로만 바꾼다.
     */
    fun setVisible(visible: Boolean) {
        val view = rootView ?: return
        val params = layoutParams ?: return
        view.visibility = if (visible) View.VISIBLE else View.GONE
        val newFlags = if (visible) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (params.flags == newFlags) return
        params.flags = newFlags
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "표시 상태 갱신 실패", e)
        }
    }

    /** 일시정지 버튼의 아이콘/설명을 갱신한다. [show] 이전에 호출돼도 다음 [show]에 반영된다. */
    fun setPaused(paused: Boolean) {
        isPaused = paused
        pauseButton?.let { applyPausedAppearance(it, paused) }
    }

    private fun applyPausedAppearance(button: ImageView, paused: Boolean) {
        button.setImageResource(
            if (paused) R.drawable.ic_overlay_play else R.drawable.ic_overlay_pause,
        )
        button.contentDescription = context.getString(
            if (paused) R.string.overlay_resume_description else R.string.overlay_pause_description,
        )
    }

    /** dp를 픽셀로 바꾸되 화면 밖으로 나가지 않게 자른다 (OverlaySettings의 상한은 기기와 무관한 값이라). */
    private fun widthPxFor(dp: Float): Int {
        val metrics = context.resources.displayMetrics
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics).toInt()
        return px.coerceAtMost(metrics.widthPixels)
    }

    fun hide() {
        val view = rootView ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "오버레이 창 제거 실패 (이미 제거됐을 수 있음)", e)
        }
        rootView = null
        textView = null
        pauseButton = null
        layoutParams = null
        Log.i(TAG, "오버레이 숨김")
    }

    /**
     * 한 손가락 드래그로 이동, 두 손가락 핀치로 **박스 폭** 조절.
     *
     * 핀치는 [OverlaySettings.setBoxWidth]만 건드린다 — 예전에는 글자 크기를 바꿔서
     * 박스를 넓히려 하면 글자까지 같이 커졌다. 글자 크기는 설정 화면 슬라이더 전담이다.
     * 실제 폭 반영은 [setBoxWidth]를 구독하는 서비스 쪽 코루틴이 처리하므로
     * 여기서는 상태만 바꾸면 된다.
     */
    private fun attachTouchHandlers(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var startTouchX = 0f
        var startTouchY = 0f

        fun resetDragOrigin(event: MotionEvent) {
            startX = params.x
            startY = params.y
            startTouchX = event.rawX
            startTouchY = event.rawY
        }

        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    OverlaySettings.setBoxWidth(
                        OverlaySettings.boxWidthDp.value * detector.scaleFactor,
                    )
                    return true
                }
            },
        )

        view.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resetDragOrigin(event)
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    // 두 손가락 → 한 손가락으로 줄어들 때 기준점을 다시 잡아, 다음 MOVE에서
                    // 남은 손가락 위치까지의 거리가 그대로 이동량으로 튀지 않게 한다.
                    resetDragOrigin(event)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 핀치 중(포인터 2개 이상)에는 드래그 이동을 하지 않는다 — 동시에 하면 창이 튄다.
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        params.x = startX + (event.rawX - startTouchX).toInt()
                        params.y = startY + (event.rawY - startTouchY).toInt()
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "드래그 중 창 갱신 실패", e)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }
}
