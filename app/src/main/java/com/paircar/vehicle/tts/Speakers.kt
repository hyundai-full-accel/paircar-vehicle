package com.paircar.vehicle.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTextToSpeech
import android.speech.tts.TextToSpeech as AndroidTextToSpeech

/** 운전자에게 소리로 알린다. 주행 중에는 화면 조작 대신 TTS 로만 알린다 (기획서 4장 예외 상황). */
interface Speaker {
    fun speak(text: String)
    fun release()
}

/**
 * Pleos TextToSpeech SDK (https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/TextToSpeech/intro).
 * ON_DEVICE 모드: 네트워크·앱 등록 없이 차량 안에서 합성한다.
 * 말하는 중에 새 문장이 오면 끝난 뒤 이어서 말한다 (끊지 않는다).
 * 초기화에 실패하면(차량이 아닌 기기 등) [onUnavailable] 로 알린다.
 */
class PleosSpeaker(
    context: Context,
    private val onUnavailable: (ErrorType) -> Unit,
) : Speaker {

    private val main = Handler(Looper.getMainLooper())
    private val tts = PleosTextToSpeech(context.applicationContext, Mode.ON_DEVICE)
    private val queue = ArrayDeque<String>()
    private var ready = false
    private var speaking = false

    private val listener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() {
            Log.i(TAG, "TTS 말하기 시작")
        }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() = next()
        override fun onStop() = next()
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "TTS 오류: $errorType")
            next()
        }
    }

    init {
        // SDK 3.3.1: initialize 가 끝나기 전에 addEventListener 를 부르면 내부 객체가 없어 실패한다 (lateinit)
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                main.post {
                    runCatching { tts.addEventListener(listener) }.onFailure { Log.w(TAG, "TTS 리스너 등록 실패", it) }
                    runCatching { tts.setLocale(Locale.KOREA) }.onFailure { Log.w(TAG, "TTS 언어 설정 실패: $it") }
                    ready = true
                    Log.i(TAG, "Pleos TTS 준비됨")
                    speakNext()
                }
            }

            override fun onFailure(errorType: ErrorType) {
                main.post {
                    Log.w(TAG, "Pleos TTS 초기화 실패: $errorType")
                    onUnavailable(errorType)
                }
            }
        })
    }

    override fun speak(text: String) {
        main.post {
            queue.addLast(text)
            speakNext()
        }
    }

    private fun next() {
        main.post {
            speaking = false
            speakNext()
        }
    }

    private fun speakNext() {
        if (!ready || speaking) return
        val text = queue.removeFirstOrNull() ?: return
        speaking = true
        runCatching { tts.speak(text, true) }.onFailure {
            Log.w(TAG, "TTS 요청 실패: $it")
            speaking = false
        }
    }

    /** 초기화에 실패했을 때 대신 말할 수 있도록 아직 못 말한 문장을 돌려준다 */
    fun drainPending(): List<String> = queue.toList().also { queue.clear() }

    override fun release() {
        runCatching {
            tts.removeEventListener(listener)
            tts.release()
        }
    }

    private companion object {
        const val TAG = "PairCarTts"
    }
}

/** 안드로이드 기본 TTS (차량이 아닌 기기·Pleos TTS 를 못 쓸 때) */
class AndroidSpeaker(context: Context) : Speaker {

    private val main = Handler(Looper.getMainLooper())
    private val pending = mutableListOf<String>()
    private var ready = false
    private var id = 0
    private var tts: AndroidTextToSpeech? = null

    init {
        tts = AndroidTextToSpeech(context.applicationContext) { status ->
            // 생성자 안에서 바로 불릴 수 있어 tts 가 정해진 뒤에 처리한다
            main.post {
                ready = status == AndroidTextToSpeech.SUCCESS
                if (ready) {
                    tts?.language = Locale.KOREAN
                    pending.forEach(::say)
                    pending.clear()
                } else {
                    Log.w("PairCarTts", "안드로이드 TTS 초기화 실패: $status (TTS 엔진 없음)")
                }
            }
        }
    }

    override fun speak(text: String) {
        main.post { if (ready) say(text) else pending += text }
    }

    private fun say(text: String) {
        tts?.speak(text, AndroidTextToSpeech.QUEUE_ADD, null, "paircar-${id++}")
    }

    override fun release() {
        tts?.shutdown()
    }
}

/** Pleos TTS 를 먼저 쓰고, 못 쓰면 안드로이드 기본 TTS 로 바꾼다 */
class VehicleSpeaker(private val context: Context) : Speaker {

    private var delegate: Speaker = runCatching<Speaker> { PleosSpeaker(context, ::fallBack) }
        .getOrElse {
            Log.w("PairCarTts", "Pleos TTS 를 만들 수 없어 기본 TTS 를 씁니다", it)
            AndroidSpeaker(context)
        }

    private fun fallBack(error: ErrorType) {
        val pleos = delegate as? PleosSpeaker ?: return
        val pending = pleos.drainPending()
        pleos.release()
        delegate = AndroidSpeaker(context).also { android -> pending.forEach(android::speak) }
        Log.i("PairCarTts", "Pleos TTS 를 못 써서($error) 기본 TTS 로 바꿨습니다")
    }

    override fun speak(text: String) = delegate.speak(text)

    override fun release() = delegate.release()
}
