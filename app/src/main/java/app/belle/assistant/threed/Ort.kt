package app.belle.assistant.threed

/** Thin JNI over the ONNX Runtime C API (see cpp/ort_jni.c). One [Session] per model file. */
object Ort {
    init { System.loadLibrary("onnxruntime"); System.loadLibrary("belle_ort") }
    @JvmStatic external fun open(path: String, threads: Int, arena: Boolean): Long
    @JvmStatic external fun close(h: Long)
    @JvmStatic external fun names(h: Long, io: Int): Array<String>
    /** inData: FloatArray / LongArray (Java heap) or a direct ByteBuffer ([inTypes] 0 = float32, 1 = int64). Outputs: float[] or, with
     *  [outNative], direct ByteBuffers over malloc'd memory (freed with [NativeMem.free]). Null on failure ([lastError]). */
    @JvmStatic external fun run2(h: Long, inNames: Array<String>, inData: Array<Any>, inTypes: IntArray, inShapes: Array<LongArray>, outNames: Array<String>,
                                 outShapes: Array<LongArray>, outNative: Boolean): Array<Any?>?
    @JvmStatic external fun lastError(): String
    /** Returns freed native heap to the system (also done on every [Session.close]). */
    @JvmStatic external fun trim()

    class Tensor(val data: FloatArray, val shape: LongArray)
    /** An output in native memory ([Big]): close it when done. */
    class BigTensor(val data: Big, val shape: LongArray) : AutoCloseable { override fun close() = data.close() }

    class Session(path: String, threads: Int = 4, arena: Boolean = false) : AutoCloseable {
        private val h = open(path, threads, arena).also { if (it == 0L) error("ONNX Runtime couldn't load $path: ${lastError()}") }
        val inputs: Array<String> = names(h, 0)
        val outputs: Array<String> = names(h, 1)
        /** [feeds]: name → (FloatArray, LongArray or [Big], shape). Returns every output on the Java heap: small outputs only. */
        fun run(feeds: Map<String, Pair<Any, LongArray>>): Map<String, Tensor> {
            val shapes = Array(outputs.size) { LongArray(8) }
            val r = call(feeds, shapes, false)
            return outputs.indices.associate { i -> outputs[i] to Tensor(r[i] as FloatArray, shapes[i].filter { it >= 0 }.toLongArray()) }
        }
        /** Like [run], but every output stays in native memory ([Big]; the caller closes them). For big tensors. */
        fun runBig(feeds: Map<String, Pair<Any, LongArray>>): Map<String, BigTensor> {
            val shapes = Array(outputs.size) { LongArray(8) }
            val r = call(feeds, shapes, true)
            return outputs.indices.associate { i -> val b = r[i] as java.nio.ByteBuffer
                outputs[i] to BigTensor(Big.adopt(b, (b.capacity() / 4)), shapes[i].filter { it >= 0 }.toLongArray()) }
        }
        private fun call(feeds: Map<String, Pair<Any, LongArray>>, shapes: Array<LongArray>, native: Boolean): Array<Any?> {
            val data = feeds.values.map { (v, _) -> if (v is Big) v.bytes else v }.toTypedArray()
            val types = feeds.values.map { (v, _) -> if (v is Big && v.type == Big.L || v is LongArray) 1 else 0 }.toIntArray()
            return run2(h, feeds.keys.toTypedArray(), data, types, feeds.values.map { it.second }.toTypedArray(), outputs, shapes, native)
                ?: run { val err = lastError(); throw if (err.startsWith("no Java heap") || err.startsWith("no native memory")) OutOfMemoryError(err) else IllegalStateException("ONNX Runtime run failed: $err") }
        }
        override fun close() = close(h)
    }
}
