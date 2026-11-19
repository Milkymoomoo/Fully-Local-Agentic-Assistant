package app.belle.assistant.core

/** Downloadable / importable models. Sizes are the exact download sizes in bytes. */
data class LlmModel(
    val id: String, val label: String, val url: String, val fileName: String,
    val bytes: Long, val sha256: String, val minRamGb: Int, val license: String, val note: String,
    /** "standard" (as released), "abliterated" (refusal direction removed afterwards — may still refuse) or
     *  "uncensored" (fine-tuned on uncensored data, e.g. Dolphin). Shown as a label on every model. */
    val censor: String = "standard",
    /** Roleplay / story fine-tune: always listed (own category); uses the story persona prompt. */
    val roleplay: Boolean = false,
) {
    val lessRestricted get() = censor != "standard"
    val censorLabel get() = when (censor) { "uncensored" -> "Uncensored (trained)"; "abliterated" -> "Abliterated"; else -> "Standard" }
}

/** Kokoro v0.19's speakers, in model order (from the model's own speaker_names metadata). */
val KOKORO_SPEAKERS = listOf("af", "af_bella", "af_nicole", "af_sarah", "af_sky", "am_adam", "am_michael", "bf_emma", "bf_isabella", "bm_george", "bm_lewis")
/** LibriTTS / LibriTTS-R Piper models: 904 speakers (num_speakers in the model card). */
const val LIBRITTS_SPEAKERS = 904

/** "af_bella" → "Bella · US female"; "af" → "Mixed · US female"; non-Kokoro → "Speaker i". */
fun speakerName(engine: String, i: Int): String {
    val k = (if (engine == "kokoro") KOKORO_SPEAKERS.getOrNull(i) else null) ?: return "Speaker $i"
    val who = k.substringAfter('_', "").replaceFirstChar { it.uppercase() }.ifEmpty { "Mixed" }
    val accent = if (k[0] == 'b') "British" else "US"
    val sex = if (k.getOrNull(1) == 'm') "male" else "female"
    return "$who · $accent $sex"
}

data class VoiceModel(
    val id: String, val label: String, val gender: String, val accent: String,
    val archiveUrl: String, val dirName: String, val approxBytes: Long, val license: String, val bundled: Boolean = false,
    val sha256: String = "",
    /** "piper" (VITS) or "kokoro". */
    val engine: String = "piper",
    /** Piper quality tier: low (16 kHz) / medium / high (22.05 kHz, bigger network). */
    val quality: String = "medium",
    /** "int8" (small, slightly buzzy) or "fp32" (full precision, smoothest). */
    val precision: String = "int8",
    /** Official sample clip (rhasspy/piper-samples) or a bundled asset path ("asset:…"); "" = none. */
    val sampleUrl: String = "",
) {
    /** RAM while speaking, roughly: the unpacked model plus ONNX runtime buffers. */
    // Measured with sherpa-onnx 1.13.8, 2 threads (process RSS incl. runtime): int8 ~170 MB, fp32 ~210, high fp32 ~270, Kokoro ~340.
    val ramMb: Int get() = when { engine == "kokoro" -> 350; precision == "fp32" && quality == "high" -> 280; precision == "fp32" -> 220; quality == "high" -> 200; else -> 180 }
    /** Rough speed on a recent phone (2 threads), as a label. */
    val speedLabel: String get() = when { engine == "kokoro" -> "slow (≈1 s work per 1 s speech; talks sentence by sentence)"; quality == "high" -> "medium"; else -> "fast" }
    /** How many speakers the model has (multi-speaker voices: Kokoro 11, LibriTTS 904); 1 for the rest. */
    val speakers: Int get() = when { engine == "kokoro" -> KOKORO_SPEAKERS.size; id.startsWith("en_US-libritts") -> LIBRITTS_SPEAKERS; else -> 1 }
    /** Readable name of speaker [i]: Kokoro's named voices ("Bella · US female"), otherwise "Speaker 12". */
    fun speakerLabel(i: Int): String = speakerName(engine, i)
    val qualityLabel: String get() = when { engine == "kokoro" -> "most natural"; quality == "high" && precision == "fp32" -> "best Piper"; quality == "high" -> "high"; precision == "fp32" -> "smooth"; quality == "low" -> "basic"; else -> "standard" }
}

/** A required speech file (not bundled, to keep the APK small). */
data class SpeechFile(val id: String, val label: String, val url: String, val fileName: String, val bytes: Long, val sha256: String, val license: String)

object ModelCatalog {
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    /** Speech recognition: Moonshine Tiny (English, int8). Extracted on import. */
    val STT = SpeechFile("moonshine-tiny", "Speech recognition — Moonshine Tiny (English)",
        "$GH/asr-models/sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2",
        "sherpa-onnx-moonshine-tiny-en-quantized-2026-02-27.tar.bz2", 29_858_559, "9ec31b342d8fa3240c3b81b8f82e1cf7e3ac467c93ca5a999b741d5887164f8d", "MIT")
    /** Voice-activity detector used with speech recognition. */
    val VAD = SpeechFile("silero-vad", "Voice activity detector — Silero VAD", "$GH/asr-models/silero_vad.onnx",
        "silero_vad.onnx", 643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", "MIT")

    private const val HF = "https://huggingface.co"
    val LLMS = listOf(
        LlmModel("qwen2.5-0.5b", "Qwen2.5 0.5B Instruct (Q4_K_M)", "$HF/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "qwen2.5-0.5b-instruct-q4_k_m.gguf", 491_400_032, "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", 3, "Apache-2.0",
            "Fastest. OK for simple commands; weak at chat."),
        LlmModel("qwen2.5-1.5b", "Qwen2.5 1.5B Instruct (Q4_K_M)", "$HF/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "qwen2.5-1.5b-instruct-q4_k_m.gguf", 1_117_320_736, "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e", 6, "Apache-2.0",
            "Recommended for 6-8 GB phones (Galaxy S24)."),
        LlmModel("llama3.2-1b", "Llama 3.2 1B Instruct (Q4_K_M)", "$HF/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            "Llama-3.2-1B-Instruct-Q4_K_M.gguf", 807_694_464, "6f85a640a97cf2bf5b8e764087b1e83da0fdb51d7c9fab7d0fece9385611df83", 4, "Llama 3.2 Community License",
            "Alternative small model."),
        LlmModel("qwen2.5-3b", "Qwen2.5 3B Instruct (Q4_K_M)", "$HF/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            "qwen2.5-3b-instruct-q4_k_m.gguf", 2_104_932_768, "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d", 8, "Qwen Research License (non-commercial)",
            "Smarter. Recommended for 12 GB phones (Oukitel WP100 Titan); usable on S24."),
        LlmModel("llama3.2-3b", "Llama 3.2 3B Instruct (Q4_K_M)", "$HF/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            "Llama-3.2-3B-Instruct-Q4_K_M.gguf", 2_019_377_696, "6c1a2b41161032677be168d354123594c0e6e67d2b9227c84f296ad037c728ff", 8, "Llama 3.2 Community License",
            "Alternative 3B model."),
        LlmModel("qwen3-4b-2507", "Qwen3 4B Instruct 2507 (Q4_K_M)", "$HF/unsloth/Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", 2_497_281_120, "3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597", 12, "Apache-2.0",
            "Best answers, slowest. 12 GB phones only."),
        // ---- Uncensored (trained): Dolphin 3.0 by Cognitive Computations (Eric Hartford), fine-tuned on uncensored
        // data; GGUF quantisations by bartowski. These are the defaults (see recommendedLlm).
        LlmModel("dolphin3-qwen2.5-0.5b", "Dolphin 3.0 Qwen2.5 0.5B (Q4_K_M)", "$HF/bartowski/Dolphin3.0-Qwen2.5-0.5B-GGUF/resolve/main/Dolphin3.0-Qwen2.5-0.5B-Q4_K_M.gguf",
            "Dolphin3.0-Qwen2.5-0.5B-Q4_K_M.gguf", 397_811_008, "6a53689e2cb91027fdc9e366142eba8e35f56c14ee353e0a4d64de981efbfffa", 3, "Apache-2.0",
            "Fastest; weak at chat. For small phones.", censor = "uncensored"),
        LlmModel("dolphin3-qwen2.5-1.5b", "Dolphin 3.0 Qwen2.5 1.5B (Q4_K_M)", "$HF/bartowski/Dolphin3.0-Qwen2.5-1.5B-GGUF/resolve/main/Dolphin3.0-Qwen2.5-1.5B-Q4_K_M.gguf",
            "Dolphin3.0-Qwen2.5-1.5B-Q4_K_M.gguf", 986_051_648, "7caa630a60c8831a509e2663e1761355fa24bcf6ccc03e3cc767e5b5747a3be5", 6, "Apache-2.0",
            "Default for 6–8 GB phones (Galaxy S24). Fast.", censor = "uncensored"),
        LlmModel("dolphin3-llama3.2-3b", "Dolphin 3.0 Llama 3.2 3B (Q4_K_M)", "$HF/bartowski/Dolphin3.0-Llama3.2-3B-GGUF/resolve/main/Dolphin3.0-Llama3.2-3B-Q4_K_M.gguf",
            "Dolphin3.0-Llama3.2-3B-Q4_K_M.gguf", 2_019_382_400, "5d6d02eeefa1ab5dbf23f97afdf5c2c95ad3d946dc3b6e9ab72e6c1637d54177", 8, "Llama 3.2 Community License",
            "Default for 12 GB phones (Oukitel WP100); usable on 8 GB.", censor = "uncensored"),
        LlmModel("dolphin3-qwen2.5-3b", "Dolphin 3.0 Qwen2.5 3B (Q4_K_M)", "$HF/bartowski/Dolphin3.0-Qwen2.5-3b-GGUF/resolve/main/Dolphin3.0-Qwen2.5-3b-Q4_K_M.gguf",
            "Dolphin3.0-Qwen2.5-3b-Q4_K_M.gguf", 1_929_906_144, "0cb1908c5f444e1dc2c5b5619d62ac4957a22ad39cd42f2d0b48e2d8b1c358ab", 8, "Qwen Research License (non-commercial)",
            "Alternative 3B.", censor = "uncensored"),
        LlmModel("dolphin2.9.4-gemma2-2b", "Dolphin 2.9.4 Gemma 2 2B (Q4_K_M)", "$HF/bartowski/dolphin-2.9.4-gemma2-2b-GGUF/resolve/main/dolphin-2.9.4-gemma2-2b-Q4_K_M.gguf",
            "dolphin-2.9.4-gemma2-2b-Q4_K_M.gguf", 1_708_586_240, "eab9dd37a1fbeaa318cdee9243851f7dcc7504f80380ce3ad0e45d295d898a59", 8, "Gemma Terms of Use",
            "Gemma-based alternative.", censor = "uncensored"),
        LlmModel("dolphin3-llama3.1-8b", "Dolphin 3.0 Llama 3.1 8B (Q4_K_M)", "$HF/bartowski/Dolphin3.0-Llama3.1-8B-GGUF/resolve/main/Dolphin3.0-Llama3.1-8B-Q4_K_M.gguf",
            "Dolphin3.0-Llama3.1-8B-Q4_K_M.gguf", 4_920_749_472, "268390e07edd407ad93ea21a868b7ae995b5950e01cad0db9e1802ae5049d405", 12, "Llama 3.1 Community License",
            "Smartest uncensored option; slow (a few words a second). 12 GB phones only.", censor = "uncensored"),
        // ---- Abliterated fallbacks (refusal direction removed after training; can still refuse sometimes)
        LlmModel("qwen3-4b-2507-abl", "Qwen3 4B Instruct 2507 abliterated (Q4_K_M)",
            "$HF/mradermacher/Huihui-Qwen3-4B-Instruct-2507-abliterated-GGUF/resolve/main/Huihui-Qwen3-4B-Instruct-2507-abliterated.Q4_K_M.gguf",
            "Huihui-Qwen3-4B-Instruct-2507-abliterated.Q4_K_M.gguf", 2_497_281_312, "b1324e2f1bd2e0610d591f54f93e38d90b187f69d1f215f4cace349eade678d7", 12, "Apache-2.0 (base: Qwen)",
            "Best answers among the 4B models; abliteration by huihui-ai, GGUF by mradermacher. 12 GB phones.", censor = "abliterated"),
        LlmModel("gemma3-4b-abl", "Gemma 3 4B it abliterated (Q4_K_M)",
            "$HF/mlabonne/gemma-3-4b-it-abliterated-GGUF/resolve/main/gemma-3-4b-it-abliterated.q4_k_m.gguf",
            "gemma-3-4b-it-abliterated.q4_k_m.gguf", 2_489_894_016, "99dc4abe5d344e1033f09e6a2c1f4e3d39093dd1f007b9d3254d8c2ef94950e1", 12, "Gemma Terms of Use",
            "Natural chat; from the abliteration author's own repo (mlabonne). 12 GB phones.", censor = "abliterated"),
        LlmModel("qwen2.5-1.5b-abl", "Qwen2.5 1.5B Instruct abliterated (Q4_K_M)",
            "$HF/mradermacher/Qwen2.5-1.5B-Instruct-abliterated-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-abliterated.Q4_K_M.gguf",
            "Qwen2.5-1.5B-Instruct-abliterated.Q4_K_M.gguf", 986_049_088, "59aa9f44bde5349dbe292d7024d197db605f422b8baf65f3246a59abbde4e8e9", 6, "Apache-2.0 (base: Qwen)",
            "Small abliterated fallback; abliteration by huihui-ai, GGUF by mradermacher.", censor = "abliterated"),
        // ---- Roleplay / story: creative-writing fine-tunes, trained on uncensored fiction
        LlmModel("gemmasutra-mini-2b", "Gemmasutra Mini 2B v1 — roleplay (Q4_K_M)", "$HF/TheDrummer/Gemmasutra-Mini-2B-v1-GGUF/resolve/main/Gemmasutra-Mini-2B-v1-Q4_K_M.gguf",
            "Gemmasutra-Mini-2B-v1-Q4_K_M.gguf", 1_708_582_784, "29ba3db911fbadef4452ba757ddd9ce58fb892b7a872f19eefd0743c961797fb", 8, "Gemma Terms of Use",
            "Small, fast roleplay model by TheDrummer (his own repo). 8 GB phones.", censor = "uncensored", roleplay = true),
        LlmModel("impish-llama-3b", "Impish LLaMA 3B — roleplay / story (Q4_K_M)", "$HF/SicariusSicariiStuff/Impish_LLAMA_3B_GGUF/resolve/main/Impish_LLAMA_3B-Q4_K_M.gguf",
            "Impish_LLAMA_3B-Q4_K_M.gguf", 2_019_373_792, "77f78ad8624d66a0f695a6b3308beb2b46a579deef105c063cc5ec40a79a26e3", 8, "Llama 3.2 Community License",
            "Playful roleplay and stories by SicariusSicariiStuff (author's repo). 8 GB phones.", censor = "uncensored", roleplay = true),
        LlmModel("stheno-8b", "L3 8B Stheno v3.2 — roleplay / story (Q4_K_M)", "$HF/bartowski/L3-8B-Stheno-v3.2-GGUF/resolve/main/L3-8B-Stheno-v3.2-Q4_K_M.gguf",
            "L3-8B-Stheno-v3.2-Q4_K_M.gguf", 4_920_734_240, "8e98c1953f9c04e060fd9640bbe866685c844363a2360f09099b79c6c9195fc4", 12, "Llama 3 Community License",
            "Well-known roleplay model by Sao10K, GGUF by bartowski. Slow; 12 GB phones only.", censor = "uncensored", roleplay = true),
    )

    /** Default model by total RAM (GB as reported by the phone, which is a bit under the sticker size). */
    fun recommendedLlm(totalRamGb: Double): LlmModel = when {
        totalRamGb >= 10.5 -> LLMS.first { it.id == "dolphin3-llama3.2-3b" }
        totalRamGb >= 5.0 -> LLMS.first { it.id == "dolphin3-qwen2.5-1.5b" }
        else -> LLMS.first { it.id == "dolphin3-qwen2.5-0.5b" }
    }

    /** Default / recommended voice by RAM: the "high" quality Lessac voice (fp32 on roomy phones, int8 otherwise). */
    fun recommendedVoice(totalRamGb: Double): VoiceModel? = when {
        totalRamGb >= 7.0 -> VOICES.firstOrNull { it.id == "en_US-lessac-high-fp32" }
        totalRamGb >= 3.5 -> VOICES.firstOrNull { it.id == "en_US-lessac-high-int8" }
        else -> null
    }

    /** Threads: big cores only. Using every core (incl. little ones) is slower on phones. */
    fun recommendedThreads(cpuCount: Int): Int = when {
        cpuCount >= 8 -> 4
        cpuCount >= 4 -> 2
        else -> 1
    }

    private const val TTS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    /** Exact sizes + SHA-256 from the GitHub release's asset digests. */
    private val VOICE_DIGESTS = mapOf(
        "en_US-amy-medium" to (21028122L to "bd23c0aa629eb3719448582f45ede49e8fa6a679061fed5eab16a6a6fd8e7e82"),
        "en_US-kristin-medium" to (20882061L to "16289d7ee8e6b2311a0a0af6531a55f498f82499644a1bb6fddb991fe6fa950c"),
        "en_US-hfc_female-medium" to (21021379L to "7c467d19054ddf84056a4887a8be75f5f3b2a5d426637b526953a5e488fec5ce"),
        "en_US-lessac-medium" to (20969179L to "f1c6d0295cf16087b05f80fdca5b44daca5cd78e2c425d419a42ba34929805f9"),
        "en_US-ljspeech-medium" to (21090429L to "24dc3bd77dd48c291e52c297878d3437c9492f245d823d7f6a06c4bbb67f4b6b"),
        "en_US-kathleen-low" to (21289266L to "08a1372ea3eed70b9477401c3aa378a73db1c609eece25fa10203c854cfe044a"),
        "en_US-ryan-medium" to (21083446L to "376eb489d42e98cb49f0e13a633e8d88580bd247ed8aacc298a733210404f771"),
        "en_US-john-medium" to (20800096L to "2ea2f6cf88bba033be71c941d00cd4d2af35d23655b5d68b7fe8239d519f17b4"),
        "en_US-joe-medium" to (21230019L to "644527f29eca0ada5595d7b6e4daf6388f5ac0c4870b4d42996a6687fcefeb37"),
        "en_US-hfc_male-medium" to (20984819L to "a12daed3ba978ffb403dec4afa245d6f114668b370157a022fcc47fa6ca073d8"),
        "en_US-bryce-medium" to (20910568L to "89cd4f464c91579440565927bfca9d26555f577c4e37431167568c934fdb82f6"),
        "en_US-libritts_r-medium" to (23398348L to "7e4552e239988f4896872822b56e99e0e9e00958164e3f6bdf5ee14391fbe829"),
        "en_GB-southern_english_female-medium" to (23714066L to "aad8ee08e22d4867ce5098c7321bf0400231ffce6bad7635e0811cb18d790e1f"),
    )
    private fun sample(id: String): String { val (lang, name, q) = id.split("-").let { Triple(it[0], it[1], it[2]) }
        return "https://rhasspy.github.io/piper-samples/samples/${lang.substringBefore('_')}/$lang/$name/$q/speaker_0.mp3" }
    private fun piper(id: String, label: String, gender: String, accent: String, license: String, bundled: Boolean = false): VoiceModel {
        val (bytes, sha) = VOICE_DIGESTS[id] ?: (21_000_000L to "")
        return VoiceModel(id, label, gender, accent, "$TTS/vits-piper-$id-int8.tar.bz2", "vits-piper-$id-int8", bytes, license, bundled, sha,
            quality = id.substringAfterLast('-'), sampleUrl = sample(id))
    }
    private fun full(v: VoiceModel): VoiceModel? { val (bytes, sha) = FULL_DIGESTS[v.id] ?: return null
        return v.copy(id = v.id + "-fp32", label = v.label.substringBefore(" (") + " · full precision", archiveUrl = "$TTS/vits-piper-${v.id}.tar.bz2",
            dirName = "vits-piper-${v.id}", approxBytes = bytes, sha256 = sha, bundled = false, precision = "fp32") }
    private fun high(id: String, label: String, gender: String, accent: String, license: String): List<VoiceModel> {
        val (b8, s8) = HIGH_DIGESTS["$id-int8"]!!; val (b, sh) = HIGH_DIGESTS[id]!!
        return listOf(VoiceModel("$id-int8", label, gender, accent, "$TTS/vits-piper-$id-int8.tar.bz2", "vits-piper-$id-int8", b8, license, false, s8, quality = "high", sampleUrl = sample(id)),
            VoiceModel("$id-fp32", "$label · full precision", gender, accent, "$TTS/vits-piper-$id.tar.bz2", "vits-piper-$id", b, license, false, sh, quality = "high", precision = "fp32", sampleUrl = sample(id)))
    }

    private val BASE_VOICES = listOf(
        piper("en_US-amy-medium", "Amy (warm; try with the Feminine slider for a Southern-belle feel)", "female", "General American", "Mimic3 voice license (see github.com/MycroftAI/mimic3-voices)"),
        piper("en_US-kristin-medium", "Kristin", "female", "General American (LibriVox)", "Public domain"),
        piper("en_US-hfc_female-medium", "HFC Female", "female", "General American", "CC BY-NC-SA 4.0"),
        piper("en_US-lessac-medium", "Lessac", "female", "General American (expressive)", "Blizzard 2013 Lessac license (non-commercial)"),
        piper("en_US-ljspeech-medium", "LJ", "female", "General American (audiobook)", "Public domain"),
        piper("en_US-kathleen-low", "Kathleen", "female", "General American", "CC0"),
        piper("en_US-ryan-medium", "Ryan (Bob's default voice)", "male", "General American", "CC BY-NC-SA 4.0"),
        piper("en_US-john-medium", "John", "male", "General American (LibriVox)", "Public domain"),
        piper("en_US-joe-medium", "Joe", "male", "General American", "CC0"),
        piper("en_US-hfc_male-medium", "HFC Male", "male", "General American", "CC BY-NC-SA 4.0"),
        piper("en_US-bryce-medium", "Bryce", "male", "General American", "Public domain"),
        piper("en_US-libritts_r-medium", "LibriTTS-R (904 speakers)", "mixed", "Many US speakers; pick one in Settings › Voice › Speaker", "CC BY 4.0"),
        piper("en_GB-southern_english_female-medium", "Southern English Female", "female", "Southern ENGLAND (British), not US South", "See Piper model card"),
    )

    /** Full-precision (fp32) builds of the int8 voices: smoother, less buzzy; about 3× the download and RAM. */
    private val FULL_DIGESTS = mapOf(
        "en_US-amy-medium" to (67223746L to "9a5d1fc497f85e8022b785bff5f8105203b1e33099ee6265203efc70b0cb0264"),
        "en_US-kristin-medium" to (67259230L to "c2206f572df2956c50b1ae3367eebce3853c663e890cba8048cd62b1e4dbe6c7"),
        "en_US-hfc_female-medium" to (67228166L to "3fffdceb0c65bd9415a085d09c3cb88cc82f9d74a6ca453f8ce7fc5eaee81ff8"),
        "en_US-lessac-medium" to (67230653L to "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e"),
        "en_US-ljspeech-medium" to (67169893L to "3dfb4b759d8be032a4903a9538d128b0fda2a06ab1de6cbc2d93a97e2dd83dba"),
        "en_US-kathleen-low" to (67118360L to "3dd0adaf077e19de32876608c3ac3d4a0a46a6f06310cc7a5633b3b47d762cde"),
        "en_US-ryan-medium" to (67213100L to "c546af78b6395b4e7c4ce1ed899438b64426a362f5d4ec5fecd090ded9ad7505"),
        "en_US-john-medium" to (67249181L to "d90362cfa48e429d3096f644785184446145b6e38216f2eaf4c0601b8aad5a36"),
        "en_US-joe-medium" to (67169394L to "261b903a1fb2ce621e2b5b14233cd41223c3b9f9590f896c11daee4df22d9bd3"),
        "en_US-hfc_male-medium" to (67214049L to "76388f84acfca8ba5c0ed1636a26ada14c598abd52e76f110d4756fe326fc5f2"),
        "en_US-bryce-medium" to (67278133L to "f35bb77ddecdf229798e850d1c77ded839d4179879be8b90da5f82e7beb538f2"),
        "en_US-libritts_r-medium" to (82038311L to "10dc268f3e371696d721486123e2705a9fc1faa113491979fde4d88dba1f1b1c"),
        "en_GB-southern_english_female-medium" to (80275090L to "cea2dcbe8a8e2a44a4bab6824e10dc38fcd5cf84458e8b3f3e5be1c5fb804512"),
    )
    /** "high" quality Piper voices (22.05 kHz, larger network): int8 and fp32. */
    private val HIGH_DIGESTS = mapOf(
        "en_US-ryan-high-int8" to (34473341L to "df13fc140db80fd586e98834cad703a8988c1004748daa068dd07a59e502013e"),
        "en_US-ryan-high" to (115630708L to "6a71edf4d308b9cb2eaeadc8d1f3c6bf96120ecb7fe52c29a2b6e139c59760ed"),
        "en_US-lessac-high-int8" to (35022847L to "f06e85ae07ca11adae6d3b8cedff4b600fa1181a354f1f86779866b86b2fdb33"),
        "en_US-lessac-high" to (115545841L to "8619d204c7005866fe4f420181dfa79622af6a6222389f0b0818d2af31e0db0e"),
        "en_US-ljspeech-high-int8" to (33886472L to "916b2526d4ea191f9710bd2753698ac97926ec38eade867408d3f5fd422ca285"),
        "en_US-ljspeech-high" to (115817679L to "00c6408d2409050312193b0d40ae07fde28af7d3d45a56efcc55440db516b935"),
        "en_US-libritts-high-int8" to (36406088L to "c35f249498fe9406c5ca3b6db1fc16749d7ee415ff2a09d5da9334206c2e0026"),
        "en_US-libritts-high" to (131033598L to "d9d35056703fd38ed38e95c202a50f603fefdc8a92a7b6332c4f1a41616eac72"),
        "en_GB-cori-high-int8" to (35038210L to "115adb9000159c3a90076fff8ba75ae4d26ccbbc749a444a24dc277b0dad872b"),
        "en_GB-cori-high" to (115574061L to "42922f07738fcde2e49eed4e959635692f73b933de35a6b7c1010162ff566292"),
    )
    const val KOKORO_BYTES = 103248205L; const val KOKORO_SHA = "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd"

    /** Kokoro-82M (int8, English, 11 voices; Apache-2.0): the most natural on-device voice here, slower than Piper. */
    val KOKORO = VoiceModel("kokoro-int8-en-v0_19", "Kokoro (11 voices, most natural)", "mixed", "US & British; 11 named speakers (Settings › Voice › Speaker)",
        "$TTS/kokoro-int8-en-v0_19.tar.bz2", "kokoro-int8-en-v0_19", KOKORO_BYTES, "Apache-2.0", false, KOKORO_SHA,
        engine = "kokoro", quality = "high", precision = "int8", sampleUrl = "asset:voice_samples/kokoro-int8-en-v0_19.ogg")

    /** Every voice: the small int8 builds, their full-precision builds, the "high" voices and Kokoro. */
    val VOICES: List<VoiceModel> = BASE_VOICES + high("en_US-ryan-high", "Ryan HQ", "male", "General American", "CC BY-NC-SA 4.0") +
        high("en_US-lessac-high", "Lessac HQ", "female", "General American (expressive)", "Blizzard 2013 Lessac license (non-commercial)") +
        high("en_US-ljspeech-high", "LJ HQ", "female", "General American (audiobook)", "Public domain") +
        high("en_US-libritts-high", "LibriTTS HQ (904 speakers)", "mixed", "Many US speakers; pick one in Settings › Voice › Speaker", "CC BY 4.0") +
        high("en_GB-cori-high", "Cori HQ", "female", "British (RP)", "Public domain") +
        BASE_VOICES.mapNotNull { full(it) } + KOKORO

    // The on-phone 3D generator (Pixal3D, ONNX) is installed from app/src/main/assets/pixal3d_models.json — see threed/Pixal3DInstall.
}
