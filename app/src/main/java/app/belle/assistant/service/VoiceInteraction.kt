package app.belle.assistant.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.belle.assistant.Prefs

/**
 * Lets the app be chosen as the phone's default digital assistant
 * (Settings > Apps > Default apps > Digital assistant app). Then long-press
 * Home / the side key (Samsung) / corner swipe opens us instead of Google.
 */
class BelleVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        AssistantRuntime.event("vis: ready (we are the default assistant)")
        // Android exempts the default assistant's own starts from the background microphone limit.
        if (Prefs(this).serviceWanted) AssistantService.start(this, from = "assistant")
    }
}

class BelleSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = BelleSession(this)
}

/** Small card shown at the bottom while listening; closes itself when done. */
class BelleSession(ctx: Context) : VoiceInteractionSession(ctx) {
    private var label: TextView? = null
    private val listener: () -> Unit = { update() }

    override fun onCreateContentView(): View {
        val tv = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 18f; setPadding(48, 40, 48, 40)
            text = "Listening…"
        }
        label = tv
        return LinearLayout(context).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 64)
            addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                tv.background = GradientDrawable().apply { setColor(Color.parseColor("#E6202833")); cornerRadius = 48f }
            })
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        AssistantRuntime.addListener(listener)
        AssistantRuntime.event("vis: assist invoked")
        AssistantRuntime.listenNow(context)
    }

    private fun update() {
        val last = synchronized(AssistantRuntime.log) { AssistantRuntime.log.lastOrNull().orEmpty() }
        label?.text = when {
            last.startsWith("heard: ") -> "“" + last.removePrefix("heard: ") + "”"
            last.startsWith("reply[") -> last.substringAfter("]: ").substringBefore(" | action")
            else -> AssistantRuntime.status
        }
        if (AssistantRuntime.mode == AssistantRuntime.Mode.WAKE && (last.startsWith("mode=WAKE"))) {
            label?.postDelayed({ hide() }, 2500)
        }
    }

    override fun onHide() { AssistantRuntime.removeListener(listener); super.onHide() }
}

/** Required companion for a selectable VoiceInteractionService; real recognition happens in AssistantRuntime. */
class BelleRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

/** Handles ACTION_ASSIST / VOICE_COMMAND (headset button, older launchers). */
class AssistActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AssistantRuntime.event("assist activity: ${intent?.action}")
        AssistantService.start(this, AssistantService.ACTION_LISTEN, from = "assistant")
        finish()
    }
}
