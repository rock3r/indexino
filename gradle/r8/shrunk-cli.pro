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
# Keep these rules on one line because Shadow 9.5.1's R8 adapter drops a second standalone `}`.
-keepclassmembers class org.jetbrains.kotlin.psi.** { public <init>(org.jetbrains.kotlin.com.intellij.lang.ASTNode); }
-keepclassmembers class org.jetbrains.kotlin.psi.** { public <init>(org.jetbrains.kotlin.psi.stubs.**); }

# Compile-time annotations and optional IntelliJ integration hooks are absent from the runtime.
-dontwarn gnu.trove.TObjectHashingStrategy
-dontwarn kotlin.annotations.jvm.**
-dontwarn kotlinx.coroutines.internal.intellij.IntellijCoroutines
-dontwarn org.checkerframework.checker.nullness.qual.Nullable
-dontwarn org.jetbrains.annotations.**
-dontwarn org.jetbrains.kotlin.com.google.errorprone.annotations.**
-dontwarn org.jetbrains.kotlin.com.google.j2objc.annotations.**
