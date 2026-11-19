package android.media

/** Desktop stand-in: audio focus doesn't exist on Windows, so every request is granted and nothing ever takes it away. */
class AudioManager {
    fun requestAudioFocus(r: AudioFocusRequest): Int = AUDIOFOCUS_REQUEST_GRANTED
    fun abandonAudioFocusRequest(r: AudioFocusRequest): Int = AUDIOFOCUS_REQUEST_GRANTED
    companion object {
        const val AUDIOFOCUS_GAIN = 1; const val AUDIOFOCUS_GAIN_TRANSIENT = 2; const val AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK = 3
        const val AUDIOFOCUS_LOSS = -1; const val AUDIOFOCUS_LOSS_TRANSIENT = -2; const val AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3
        const val AUDIOFOCUS_REQUEST_GRANTED = 1
    }
}

class AudioFocusRequest private constructor() {
    fun interface OnAudioFocusChangeListener { fun onAudioFocusChange(c: Int) }
    class Builder(gain: Int) {
        fun setAudioAttributes(a: AudioAttributes) = this
        fun setOnAudioFocusChangeListener(l: OnAudioFocusChangeListener) = this
        fun setOnAudioFocusChangeListener(l: (Int) -> Unit) = this
        fun setAcceptsDelayedFocusGain(b: Boolean) = this
        fun setWillPauseWhenDucked(b: Boolean) = this
        fun build() = AudioFocusRequest()
    }
}
