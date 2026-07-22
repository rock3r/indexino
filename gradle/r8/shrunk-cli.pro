# Preserve metadata consumed by Kotlin reflection, PSI, and diagnostics.
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep class dev.sebastiano.indexino.cli.MainCommandKt {
    public static void main(java.lang.String[]);
}

# JNA's native bootstrap and proxies resolve these classes and entry points by reflection.
-keep class com.sun.jna.** { *; }
-keep interface * extends com.sun.jna.Library { *; }
-keep interface * extends com.sun.jna.Callback { *; }
-keep class * implements com.sun.jna.Callback { *; }

# Xodus registers these standard MBeans reflectively by naming convention.
-keep class jetbrains.exodus.env.management.** { *; }
-keep class jetbrains.exodus.management.** { *; }

# Kotlin's embedded IntelliJ bootstrap invokes this singleton for registration side effects.
-keep class org.jetbrains.kotlin.com.intellij.psi.compiled.ClassFileDecompilers { *; }

# The embedded application container instantiates this application service reflectively.
-keep class org.jetbrains.kotlin.com.intellij.psi.LanguageSubstitutors { *; }

# KtStubElementType resolves PSI constructors reflectively from the declared AST/stub type.
# Keep rules on one line because Shadow's R8 merger deduplicates repeated lines, including braces.
-keepclassmembers class org.jetbrains.kotlin.psi.** { public <init>(org.jetbrains.kotlin.com.intellij.lang.ASTNode); }
-keepclassmembers class org.jetbrains.kotlin.psi.** { public <init>(org.jetbrains.kotlin.psi.stubs.**); }

# Shadow 9.6 discovers these dependency rules automatically, but its rule merger currently makes
# their multiline blocks invalid. The shrunk archive excludes the embedded copies and keeps their
# runtime-sensitive rules here in the one-line form accepted by the merger.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> { static <1>$Companion Companion; }
-if @kotlinx.serialization.Serializable class ** { static **$* *; }
-keepclassmembers class <2>$<3> { kotlinx.serialization.KSerializer serializer(...); }
-if @kotlinx.serialization.Serializable class ** { public static ** INSTANCE; }
-keepclassmembers class <1> { public static <1> INSTANCE; kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers public class **$$serializer { private ** descriptor; }
-keepclassmembers @kotlinx.serialization.Serializable class ** { public static ** INSTANCE; kotlinx.serialization.KSerializer serializer(...); }
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembers class kotlin.coroutines.SafeContinuation { volatile <fields>; }

# Compile-time annotations and optional IntelliJ integration hooks are absent from the runtime.
-dontwarn gnu.trove.TObjectHashingStrategy
-dontwarn kotlin.annotations.jvm.**
-dontwarn kotlinx.coroutines.internal.intellij.IntellijCoroutines
-dontwarn kotlinx.serialization.internal.ClassValueReferences
-dontwarn java.lang.ClassValue
-dontwarn java.lang.instrument.ClassFileTransformer
-dontwarn java.lang.instrument.Instrumentation
-dontwarn org.checkerframework.checker.nullness.qual.Nullable
-dontwarn org.jetbrains.annotations.**
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.**
-dontwarn org.jetbrains.kotlin.com.google.j2objc.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn sun.misc.Signal
-dontwarn sun.misc.SignalHandler
