package app.belle.assistant.core

/**
 * How the app's own native libraries are loaded. On the phone it is plain [System.loadLibrary]; the Windows program
 * replaces [load] at startup so it can pick the variant for the chosen acceleration backend (e.g. the CUDA build of
 * belle_llm or the GPU build of ONNX Runtime) from its runtimes folder.
 */
object NativeLibs {
    @Volatile var load: (String) -> Unit = { System.loadLibrary(it) }
}
