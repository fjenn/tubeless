package app.tubehaus.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.tubehaus.ui.Speech

/** Voice search on Android: the system's SpeechRecognizer, with the app's own listening screen (VoiceSearch) */
class AndroidSpeech(private val ctx: Context, private val activity: () -> MainActivity?) : Speech {
    private var rec: SpeechRecognizer? = null
    private var ended: ((String?, String?) -> Unit)? = null
    private var heard: String? = null

    override fun start(lang: String, partial: (String) -> Unit, ended: (text: String?, error: String?) -> Unit) {
        stop()
        this.ended = ended
        heard = null
        val act = activity() ?: return finish("Voice search isn't available right now.")
        act.requestMic { ok -> if (ok) listen(lang, partial) else finish("The microphone is off for Tubeless. Turn it on in the app's settings.") }
    }

    private fun listen(lang: String, partial: (String) -> Unit) {
        if (ended == null) return
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { partial("") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { heard = it; partial(it) }
            }
            override fun onResults(b: Bundle?) {
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { heard = it }
                finish(null)
            }
            override fun onError(error: Int) {
                finish(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The microphone is off for Tubeless. Turn it on in the app's settings."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice search needs a connection."
                    else -> "Voice search failed. Try again."
                })
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.startListening(i)
    }

    override fun stop() { rec?.stopListening() }

    private fun finish(error: String?) {
        rec?.destroy(); rec = null
        val cb = ended ?: return
        ended = null
        cb(heard, if (heard == null) error else null)
    }
}
