package app.belle.desktop.platform

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** A short alarm tone for alarms and timers (the phone uses the system alarm sound). */
object Chimes {
    fun alarm() = Thread({
        runCatching {
            val rate = 22050f; val fmt = AudioFormat(rate, 16, 1, true, false)
            val line = AudioSystem.getSourceDataLine(fmt); line.open(fmt); line.start()
            val beep = ByteArray((rate * 0.25f).toInt() * 2); val gap = ByteArray((rate * 0.15f).toInt() * 2)
            for (i in 0 until beep.size / 2) {
                val env = minOf(1.0, minOf(i, beep.size / 2 - i) / 400.0)
                val v = (Math.sin(2 * Math.PI * 880 * i / rate) * 9000 * env).toInt()
                beep[2 * i] = v.toByte(); beep[2 * i + 1] = (v shr 8).toByte()
            }
            repeat(6) { line.write(beep, 0, beep.size); line.write(gap, 0, gap.size) }
            line.drain(); line.close()
        }
    }, "alarm").apply { isDaemon = true; start() }
}
