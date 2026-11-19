# sherpa-onnx: JNI looks up Kotlin classes/fields by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# Our llama.cpp JNI bridge.
-keep class app.belle.assistant.llm.LlamaEngine { *; }
-keepclasseswithmembernames class * { native <methods>; }
# Android components referenced from the manifest are kept automatically.
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-keep class app.belle.assistant.llm.LlamaEngine$Sink { *; }
# pdfbox-android ("Read to me" PDF import): optional JPEG2000 codec isn't bundled; keep the library intact (it loads
# fonts/resources and some classes by name).
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
