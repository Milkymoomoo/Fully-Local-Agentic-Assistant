package android.media

/** Desktop stand-in (constants + builder); the desktop always plays and records 16-bit PCM through Java Sound. */
class AudioFormat private constructor(val sampleRate: Int, val encoding: Int, val channelMask: Int) {
    class Builder {
        private var rate = 16000; private var enc = ENCODING_PCM_FLOAT; private var mask = CHANNEL_OUT_MONO
        fun setEncoding(e: Int) = apply { enc = e }
        fun setSampleRate(r: Int) = apply { rate = r }
        fun setChannelMask(m: Int) = apply { mask = m }
        fun build() = AudioFormat(rate, enc, mask)
    }
    companion object {
        const val ENCODING_PCM_16BIT = 2; const val ENCODING_PCM_FLOAT = 4
        const val CHANNEL_OUT_MONO = 4; const val CHANNEL_IN_MONO = 16
    }
}
