-keep public class com.urik.keyboard.UrikInputMethodService {
    public protected <methods>;
}

-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }

-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }

-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel

-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class ** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,Exception

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}

# JGit (Library git archive): keep the library (reflection + META-INF/services providers) and silence its
# optional transport/crypto deps we don't ship (ssh/jsch, apache-http, bouncycastle, servlet, JMX).
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**
-keep class com.googlecode.javaewah.** { *; }
-dontwarn com.googlecode.javaewah.**
-dontwarn javax.servlet.**
-dontwarn javax.management.**
-dontwarn org.apache.**
-dontwarn org.bouncycastle.**
-dontwarn org.ietf.jgss.**
-dontwarn com.jcraft.**
-dontwarn java.lang.management.**

# Whisper voice engine: ONNX Runtime (+extensions) and the WebRTC VAD are JNI-bound — native code
# resolves Java classes/methods/fields by NAME, so R8 renaming or stripping breaks the binding at
# runtime (the IME process died at the first transcription). Neither AAR ships consumer rules;
# upstream whisperIMEplus builds with minify OFF and never hits this. Keep the engine subset too.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
-keep class com.konovalov.vad.** { *; }
-keep class com.whisperonnx.** { *; }

-repackageclasses
-allowaccessmodification
