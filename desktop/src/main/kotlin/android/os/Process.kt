package android.os

object Process {
    @JvmStatic fun myPid(): Int = ProcessHandle.current().pid().toInt()
    /** Only the program's own process: ends it at once (used by the build's crash-resume self-test). */
    @JvmStatic fun killProcess(pid: Int) { if (pid == myPid()) Runtime.getRuntime().halt(9) }
}
