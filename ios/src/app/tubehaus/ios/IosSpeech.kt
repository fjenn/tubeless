package app.tubehaus.ios

import app.tubehaus.ui.Speech
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Voice search on the iPhone and iPad: Apple's Speech framework listening to the microphone (on the
 * device where the language allows it). Asks for the microphone and speech recognition the first time.
 */
object IosSpeech : Speech {
    private var engine: AVAudioEngine? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null
    private var ended: ((String?, String?) -> Unit)? = null
    private var heard: String? = null

    private fun main(f: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { f() }

    override fun start(lang: String, partial: (String) -> Unit, ended: (text: String?, error: String?) -> Unit) {
        stop()
        this.ended = ended
        heard = null
        SFSpeechRecognizer.requestAuthorization { status ->
            main {
                if (status != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    finish("Speech recognition is off for Tubeless. Turn it on in Settings → Privacy & Security → Speech Recognition."); return@main
                }
                AVAudioSession.sharedInstance().requestRecordPermission { ok ->
                    main { if (ok) listen(lang, partial) else finish("The microphone is off for Tubeless. Turn it on in Settings → Privacy & Security → Microphone.") }
                }
            }
        }
    }

    private fun listen(lang: String, partial: (String) -> Unit) {
        if (ended == null) return
        val rec = SFSpeechRecognizer(NSLocale(localeIdentifier = lang.replace('-', '_'))).takeIf { it.isAvailable() }
            ?: SFSpeechRecognizer().takeIf { it.isAvailable() }
            ?: return finish("Voice search isn't available right now.")
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryRecord, mode = AVAudioSessionModeMeasurement, options = 0u, error = null)
        session.setActive(true, error = null)
        val req = SFSpeechAudioBufferRecognitionRequest().apply { shouldReportPartialResults = true }
        val eng = AVAudioEngine()
        val input = eng.inputNode
        val format = input.outputFormatForBus(0u)
        if (format.sampleRate <= 0.0) return finish("No microphone was found.")
        input.installTapOnBus(0u, 1024u, format) { buffer, _ -> buffer?.let { req.appendAudioPCMBuffer(it) } }
        eng.prepare()
        if (!eng.startAndReturnError(null)) { input.removeTapOnBus(0u); return finish("The microphone couldn't start.") }
        engine = eng; request = req
        partial("")
        task = rec.recognitionTaskWithRequest(req) { result, error ->
            val text = result?.bestTranscription?.formattedString
            val final = result?.isFinal() == true
            main {
                if (!text.isNullOrBlank()) { heard = text; partial(text) }
                if (final || error != null) finish(null)
            }
        }
    }

    override fun stop() {
        val eng = engine ?: return
        engine = null
        eng.stop()
        eng.inputNode.removeTapOnBus(0u)
        request?.endAudio()
        // the task then ends with the last words (finish); if it doesn't within a moment, end here
        val t = task
        dispatch_async(dispatch_get_main_queue()) {
            platform.Foundation.NSTimer.scheduledTimerWithTimeInterval(1.2, repeats = false) { _ -> if (task === t) { t?.cancel(); finish(null) } }
        }
    }

    private fun finish(error: String?) {
        engine?.let { it.stop(); it.inputNode.removeTapOnBus(0u) }
        engine = null
        task = null; request = null
        // the player's sound again
        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
        val cb = ended ?: return
        ended = null
        cb(heard, if (heard == null) error else null)
    }
}
