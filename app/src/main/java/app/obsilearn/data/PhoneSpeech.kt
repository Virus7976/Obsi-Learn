package app.obsilearn.data

import android.content.Context
import android.net.Uri
import app.obsi.core.speech.Pcm
import app.obsi.core.speech.Speech
import app.obsi.core.speech.Word
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Speech to text on the phone, with sherpa-onnx (Apache 2.0). A voice finder (Silero VAD) cuts the sound
 * into spoken stretches of up to 20 seconds; the speech model writes each one down. Free, offline, private.
 */
object PhoneSpeech {
    /** The speech models the phone can use. Each is a set of files from Hugging Face, fetched once. */
    enum class Model(
        val key: String,
        val title: String,
        val about: String,
        val repo: String,
        val files: List<String>,
        val mb: Int,
        /** Phones with less memory than this get the smaller model. */
        val minRamGb: Int,
    ) {
        Parakeet(
            "parakeet-v3", "Parakeet", "Most exact. English and 24 other European languages.",
            "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), 670, 6,
        ),
        Moonshine(
            "moonshine-base", "Moonshine", "Smaller and quicker. English only.",
            "csukuangfj/sherpa-onnx-moonshine-base-en-int8",
            listOf("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt"), 290, 0,
        );

        fun url(file: String) = "https://huggingface.co/$repo/resolve/main/$file"

        companion object {
            fun of(key: String): Model? = entries.firstOrNull { it.key == key }
        }
    }

    /** The voice finder, shared by every model. */
    const val VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

    /** The model that suits this phone: the most exact one its memory can hold. */
    fun suggest(context: Context): Model {
        val ram = app.obsi.core.ai.OnPhone.ramGb(context)
        return Model.entries.first { ram + 0.5 >= it.minRamGb }
    }

    /** [key] when it names a model, else the one that suits this phone. */
    fun pick(context: Context, key: String): Model = Model.of(key) ?: suggest(context)

    /**
     * Words from one spoken stretch. [text] is the model's text, with its stops and capitals. [tokens] and
     * [stamps] are the pieces of words and when each starts, in seconds from the stretch start, when the
     * model gives them. A piece starting with a space (or ▁) starts a new word. [t0] is when the stretch
     * starts and [dur] how long it is.
     */
    fun stretch(text: String, tokens: Array<String>, stamps: FloatArray, t0: Double, dur: Double): List<Word> {
        val parts = text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val timed = ArrayList<Word>()
        if (tokens.isNotEmpty() && stamps.size == tokens.size) {
            var w = StringBuilder()
            var start = 0.0
            fun close(end: Double) {
                if (w.isNotEmpty()) timed += Word(w.toString(), t0 + start, t0 + maxOf(end, start))
                w = StringBuilder()
            }
            tokens.forEachIndexed { i, tok ->
                val newWord = tok.startsWith(" ") || tok.startsWith("▁")
                val clean = tok.trimStart(' ', '▁')
                if (newWord || w.isEmpty()) { close(stamps[i].toDouble()); start = stamps[i].toDouble() }
                w.append(clean)
            }
            close(dur)
        }
        val out = ArrayList<Word>()
        Speech.Aligner(timed).take(parts, t0, t0 + dur, out)
        return out
    }

    /**
     * Writes down the speech in [uri]. [dir] holds the model's files and [vadFile] the voice finder.
     * [onProgress] gets 0..1.
     */
    suspend fun transcribe(
        context: Context, model: Model, dir: File, vadFile: File, uri: Uri, headers: Map<String, String>,
        onProgress: (Float) -> Unit,
    ): List<Word> {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        fun f(name: String) = File(dir, name).absolutePath
        val mc = when (model) {
            Model.Parakeet -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = f("encoder.int8.onnx"), decoder = f("decoder.int8.onnx"), joiner = f("joiner.int8.onnx")),
                tokens = f("tokens.txt"), numThreads = threads, modelType = "nemo_transducer",
            )
            Model.Moonshine -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = f("preprocess.onnx"), encoder = f("encode.int8.onnx"),
                    uncachedDecoder = f("uncached_decode.int8.onnx"), cachedDecoder = f("cached_decode.int8.onnx"),
                ),
                tokens = f("tokens.txt"), numThreads = threads,
            )
        }
        val rec = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = mc))
        val vad = Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadFile.absolutePath, threshold = 0.5f, minSilenceDuration = 0.3f,
                    minSpeechDuration = 0.25f, windowSize = WINDOW, maxSpeechDuration = 20f,
                ),
                sampleRate = Pcm.RATE, numThreads = 1,
            ),
        )
        val words = ArrayList<Word>()
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front()
                val st = rec.createStream()
                try {
                    st.acceptWaveform(seg.samples, Pcm.RATE)
                    rec.decode(st)
                    val r = rec.getResult(st)
                    words += stretch(r.text, r.tokens, r.timestamps, seg.start.toDouble() / Pcm.RATE, seg.samples.size.toDouble() / Pcm.RATE)
                } finally {
                    st.release()
                }
                vad.pop()
            }
        }
        try {
            val buf = FloatArray(WINDOW)
            var n = 0
            Pcm.decode(context, uri, headers, onProgress) { block ->
                for (s in block) {
                    buf[n++] = s / 32768f
                    if (n == WINDOW) { vad.acceptWaveform(buf.copyOf()); n = 0; drain() }
                }
            }
            if (n > 0) vad.acceptWaveform(buf.copyOf(n))
            vad.flush()
            drain()
        } finally {
            vad.release()
            rec.release()
        }
        return words
    }

    /** Samples the voice finder looks at, at a time. */
    private const val WINDOW = 512
}
