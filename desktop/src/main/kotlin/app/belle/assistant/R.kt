package app.belle.assistant

/** Desktop stand-in for the Android resource ids the shared code uses; the files are copied from app/src/main/res. */
object R {
    object drawable {
        const val avatar_default = 1
        const val ic_mic = 2
        private val PATHS = mapOf(avatar_default to "drawable/avatar_default.webp", ic_mic to "drawable/ic_mic.xml")
        fun path(id: Int): String = PATHS[id] ?: error("no drawable $id")
        /** Every drawable the desktop carries (id to classpath path), for the self-test. */
        val all: Map<Int, String> get() = PATHS
    }
}
