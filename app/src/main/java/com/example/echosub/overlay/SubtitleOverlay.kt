package com.example.echosub.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
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

/** 히스토리 영역이 늘어날 수 있는 최대 높이 — 이보다 길어지면 스크롤로 넘겨본다. */
private const val HISTORY_MAX_HEIGHT_DP = 220f

/** 스크롤이 바닥에서 이 거리(dp) 안에 있으면 "바닥에 붙어 있다"고 본다. */
private const val STICK_TO_BOTTOM_SLOP_DP = 24f

/** 번역이 아직 안 온 문장(원문만 있음)을 표시할 때 쓰는 옅은 흰색. */
private const val PENDING_COLOR = 0xB3FFFFFF.toInt()

/** 번역이 실패한 문장을 표시할 때 쓰는 옅은 빨강 — 원문은 보이되 뭔가 걸렸다는 티만 낸다. */
private const val PENDING_ERROR_COLOR = 0xB3FFCDD2.toInt()

/**
 * 다른 앱 위에 뜨는 번역 자막 창.
 *
 * Compose 대신 일반 View를 쓴다 — ComposeView를 WindowManager에 직접 붙이려면
 * ViewTreeLifecycleOwner/SavedStateRegistryOwner/ViewModelStoreOwner를 손수 심어줘야 하는데,
 * 이 오버레이는 TextView 하나와 아이콘 두 개가 전부라 그 배선을 감당할 이유가 없다.
 *
 * 호출 규칙: [show]/[setEntries]/[setTextSize]/[setBoxWidth]/[setPaused]/[hide] 모두
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

    /** 히스토리 한 줄. [translatedText]가 없으면 [sourceText]를 옅게 대신 보여준다. */
    data class Entry(
        val sourceText: String,
        val translatedText: String?,
        val translationError: String? = null,
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View? = null
    private var textView: TextView? = null
    private var scrollView: MaxHeightScrollView? = null
    private var pauseButton: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isPaused = false

    /**
     * 사용자가 위로 스크롤해서 지난 문장을 보고 있는 동안에는 새 문장이 와도 끌어내리지
     * 않는다. 바닥 근처로 돌아오면(±[STICK_TO_BOTTOM_SLOP_DP]) 자동으로 다시 붙는다 —
     * 별도 "따라가기" 버튼 없이 스크롤 위치 자체가 그 의사 표시가 되게 했다.
     */
    private var stickToBottom = true

    fun show() {
        if (rootView != null) return

        val view = LayoutInflater.from(context).inflate(R.layout.overlay_subtitle, null)
        val handle = view.findViewById<View>(R.id.overlay_handle)
        val text = view.findViewById<TextView>(R.id.overlay_text)
        val scroll = view.findViewById<MaxHeightScrollView>(R.id.overlay_scroll)

        scroll.maxHeightPx = dpToPx(HISTORY_MAX_HEIGHT_DP)
        val slopPx = dpToPx(STICK_TO_BOTTOM_SLOP_DP)
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            // 리스너 파라미터는 View 타입이라 getChildAt이 없다 — ScrollView인 scroll을 직접 쓴다.
            val content = scroll.getChildAt(0)
            val maxScroll = ((content?.height ?: 0) - scroll.height).coerceAtLeast(0)
            stickToBottom = maxScroll - scrollY <= slopPx
        }
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
            // TOUCHABLE은 유지 — 손잡이로 옮길 수 있어야 한다.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = (context.resources.displayMetrics.heightPixels * 0.7f).toInt()
        }

        // 드래그/핀치는 손잡이 줄에서만 받는다 — 본문은 이제 자체 스크롤을 가지므로
        // 박스 전체를 드래그 대상으로 두면 문장을 넘겨보려는 손짓과 창을 옮기려는
        // 손짓이 같은 제스처(세로 드래그)라 구분할 수 없다.
        attachTouchHandlers(root = view, handle = handle, params = params)

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            // 권한 미허용 상태에서 호출된 경우 등 — 여기서 죽으면 서비스 전체가 죽으므로 반드시 잡는다.
            Log.e(TAG, "오버레이 창 추가 실패", e)
            return
        }

        rootView = view
        textView = text
        scrollView = scroll
        layoutParams = params
        Log.i(TAG, "오버레이 표시 시작")
    }

    /**
     * 최근 문장들을 순서대로 그린다. 번역이 아직 안 온 문장은 원문을 옅게 대신 보여준다 —
     * 화면이 비어 있는 채로 번역이 끝나길 기다리는 것보다, 인식된 원문이라도 바로 보이는
     * 편이 "잘 돌아가고 있다"는 확신을 준다. 번역이 도착하면 같은 자리가 또렷한 번역문으로
     * 바뀐다.
     */
    fun setEntries(entries: List<Entry>) {
        val tv = textView ?: return
        if (entries.isEmpty()) {
            tv.text = context.getString(R.string.overlay_waiting)
        } else {
            val builder = SpannableStringBuilder()
            entries.forEachIndexed { index, entry ->
                if (index > 0) builder.append("\n\n")
                val start = builder.length
                builder.append(entry.translatedText ?: entry.sourceText)
                if (entry.translatedText == null) {
                    val color = if (entry.translationError != null) PENDING_ERROR_COLOR else PENDING_COLOR
                    builder.setSpan(
                        ForegroundColorSpan(color),
                        start, builder.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    builder.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        start, builder.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            tv.text = builder
        }
        autoScrollIfStuck()
    }

    /** 사용자가 위로 스크롤해 지난 내용을 보는 중이 아니면 새 내용이 보이도록 바닥까지 내린다. */
    private fun autoScrollIfStuck() {
        val scroll = scrollView ?: return
        if (stickToBottom) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
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

    private fun dpToPx(dp: Float): Int {
        val metrics = context.resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics).toInt()
    }

    /** dp를 픽셀로 바꾸되 화면 밖으로 나가지 않게 자른다 (OverlaySettings의 상한은 기기와 무관한 값이라). */
    private fun widthPxFor(dp: Float): Int {
        val metrics = context.resources.displayMetrics
        return dpToPx(dp).coerceAtMost(metrics.widthPixels)
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
        scrollView = null
        pauseButton = null
        layoutParams = null
        Log.i(TAG, "오버레이 숨김")
    }

    /**
     * 손잡이([handle])에서만 한 손가락 드래그로 이동, 두 손가락 핀치로 **박스 폭** 조절.
     * 실제로 옮겨지는 창은 [root]다 — 손잡이는 제스처를 받는 자리일 뿐이다.
     *
     * 핀치는 [OverlaySettings.setBoxWidth]만 건드린다 — 예전에는 글자 크기를 바꿔서
     * 박스를 넓히려 하면 글자까지 같이 커졌다. 글자 크기는 설정 화면 슬라이더 전담이다.
     * 실제 폭 반영은 [setBoxWidth]를 구독하는 서비스 쪽 코루틴이 처리하므로
     * 여기서는 상태만 바꾸면 된다.
     */
    private fun attachTouchHandlers(root: View, handle: View, params: WindowManager.LayoutParams) {
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

        handle.setOnTouchListener { _, event ->
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
                            windowManager.updateViewLayout(root, params)
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
