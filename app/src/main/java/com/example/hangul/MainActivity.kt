package com.example.hangul

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    private val bgColor = 0xFFFFF8E1.toInt()

    private lateinit var emojiView: TextView
    private lateinit var wordView: TextView
    private lateinit var starView: TextView
    private lateinit var boxRow: LinearLayout
    private lateinit var normalRow: LinearLayout
    private lateinit var nextBtn: Button
    private lateinit var confetti: ConfettiView
    private lateinit var sound: SoundFx

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val handler = Handler(Looper.getMainLooper())

    private val boxes = ArrayList<WritingBox>()
    private val deck = ArrayList<Word>()
    private var lastWord: Word? = null
    private var currentWord: Word? = null
    private var completed = false
    private var stars = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = bgColor
        sound = SoundFx()
        setContentView(buildUi())
        initTts()
        showWord(nextWord())
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Throwable) {
        }
        sound.release()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun makeButton(label: String, color: Int): Button {
        val b = Button(this)
        b.text = label
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        b.setAllCaps(false)
        b.setTextColor(Color.WHITE)
        b.background = rounded(color, dp(30))
        b.setStateListAnimator(null)
        return b
    }

    private fun buildUi(): View {
        val metrics = resources.displayMetrics
        val root = FrameLayout(this)
        root.setBackgroundColor(bgColor)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(16), dp(16), dp(16), dp(20))

        // 맨 위: 안내 문구 + 별 개수
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        val hint = TextView(this)
        hint.text = "천천히 써 보세요"
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        hint.setTextColor(0xFF888888.toInt())
        top.addView(hint, LinearLayout.LayoutParams(0, WRAP, 1f))
        starView = TextView(this)
        starView.text = "⭐ 0"
        starView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        starView.setTextColor(0xFFFF8F00.toInt())
        top.addView(starView, LinearLayout.LayoutParams(WRAP, WRAP))
        col.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // 그림 (누르면 단어를 읽어줘요)
        emojiView = TextView(this)
        emojiView.gravity = Gravity.CENTER
        emojiView.setIncludeFontPadding(false)
        emojiView.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.heightPixels * 0.14f)
        emojiView.setOnClickListener { speakWord() }
        col.addView(emojiView, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // 단어
        wordView = TextView(this)
        wordView.gravity = Gravity.CENTER
        wordView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52f)
        wordView.setTextColor(0xFF222222.toInt())
        wordView.setOnClickListener { speakWord() }
        val wlp = LinearLayout.LayoutParams(MATCH, WRAP)
        wlp.topMargin = dp(4)
        wlp.bottomMargin = dp(16)
        col.addView(wordView, wlp)

        // 따라쓰기 칸
        boxRow = LinearLayout(this)
        boxRow.orientation = LinearLayout.HORIZONTAL
        boxRow.gravity = Gravity.CENTER
        col.addView(boxRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // 버튼 영역
        val buttonArea = FrameLayout(this)
        normalRow = LinearLayout(this)
        normalRow.orientation = LinearLayout.HORIZONTAL

        val clearBtn = makeButton("지우기", 0xFF90A4AE.toInt())
        clearBtn.setOnClickListener { boxes.filter { !it.done }.forEach { it.clear() } }
        val clp = LinearLayout.LayoutParams(0, dp(60), 1f)
        clp.marginEnd = dp(8)
        normalRow.addView(clearBtn, clp)

        val skipBtn = makeButton("다른 단어", 0xFF42A5F5.toInt())
        skipBtn.setOnClickListener { showWord(nextWord()) }
        val slp = LinearLayout.LayoutParams(0, dp(60), 1f)
        slp.marginStart = dp(8)
        normalRow.addView(skipBtn, slp)
        buttonArea.addView(normalRow, FrameLayout.LayoutParams(MATCH, WRAP))

        nextBtn = makeButton("다음 단어 →", 0xFF43A047.toInt())
        nextBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        nextBtn.visibility = View.GONE
        nextBtn.setOnClickListener { showWord(nextWord()) }
        buttonArea.addView(nextBtn, FrameLayout.LayoutParams(MATCH, dp(68)))

        val blp = LinearLayout.LayoutParams(MATCH, WRAP)
        blp.topMargin = dp(20)
        col.addView(buttonArea, blp)

        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))

        // 폭죽 오버레이 (맨 위, 터치 통과)
        confetti = ConfettiView(this)
        root.addView(confetti, FrameLayout.LayoutParams(MATCH, MATCH))

        return root
    }

    // ---------------------------------------------------------------- 단어 진행

    /** 섞은 순서대로 뽑고, 다 쓰면 다시 섞어요. 같은 단어가 연달아 나오지 않게 합니다. */
    private fun nextWord(): Word {
        if (deck.isEmpty()) {
            deck.addAll(WORDS.shuffled())
            if (deck.size > 1 && deck[0] == lastWord) {
                deck.add(deck.removeAt(0))
            }
        }
        val w = deck.removeAt(0)
        lastWord = w
        return w
    }

    private fun showWord(w: Word) {
        currentWord = w
        completed = false
        confetti.stop()

        val syllables = w.text.filter { it in '가'..'힣' }
        emojiView.text = w.emoji
        wordView.text = syllables.toList().joinToString("  ")

        boxRow.removeAllViews()
        boxes.clear()
        val n = syllables.length.coerceAtLeast(1)
        val avail = resources.displayMetrics.widthPixels - dp(32)
        val size = minOf(avail / n, dp(170))
        for (ch in syllables) {
            val box = WritingBox(this, ch.toString())
            box.onDone = { onBoxDone() }
            boxRow.addView(box, LinearLayout.LayoutParams(size, size))
            boxes.add(box)
        }

        nextBtn.visibility = View.GONE
        normalRow.visibility = View.VISIBLE

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ speakWord() }, 350)
    }

    private fun onBoxDone() {
        if (completed) return
        if (boxes.all { it.done }) {
            completed = true
            onWordComplete()
        } else {
            sound.playChime()
        }
    }

    private fun onWordComplete() {
        stars++
        starView.text = "⭐ $stars"
        sound.playFanfare()
        confetti.start()

        normalRow.visibility = View.GONE
        nextBtn.visibility = View.VISIBLE
        nextBtn.scaleX = 0.6f
        nextBtn.scaleY = 0.6f
        nextBtn.animate().scaleX(1f).scaleY(1f).setDuration(350)
            .setInterpolator(OvershootInterpolator()).start()

        handler.postDelayed({ speak("참 잘했어요!") }, 1200)
    }

    // ---------------------------------------------------------------- 읽어주기 (TTS)

    private fun initTts() {
        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = tts?.setLanguage(Locale.KOREAN)
                    ttsReady = r != null &&
                        r != TextToSpeech.LANG_MISSING_DATA &&
                        r != TextToSpeech.LANG_NOT_SUPPORTED
                    if (ttsReady) handler.post { if (!completed) speakWord() }
                }
            }
        } catch (e: Throwable) {
        }
    }

    private fun speakWord() {
        currentWord?.let { speak(it.text) }
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hangul")
        } catch (e: Throwable) {
        }
    }
}
