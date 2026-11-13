# NexusClone ProGuard rules.
#
# The container is reflection-heavy: BlackReflection generates classes that are looked up
# by name at runtime, and the mirror package shadows android.* internals. Anything in those
# trees must survive R8 untouched or the container fails at runtime with
# ClassNotFoundException, which is invisible until a clone is launched.

# --- Container engine -------------------------------------------------------------
-keep class top.niunaijun.blackbox.** {*; }
-keep class top.niunaijun.jnihook.** {*; }
-keep class top.niunaijun.blackreflection.** {*; }
-keep class mirror.** {*; }
-keep class android.** {*; }
-keep class com.android.** {*; }
-keep class black.** {*; }

# --- BlackReflection annotations --------------------------------------------------
-keep @top.niunaijun.blackreflection.annotation.BClass class * {*;}
-keep @top.niunaijun.blackreflection.annotation.BClassName class * {*;}
-keep @top.niunaijun.blackreflection.annotation.BClassNameNotProcess class * {*;}
-keepclasseswithmembernames class * {
    @top.niunaijun.blackreflection.annotation.BField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldSetNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BFieldCheckNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BMethod.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticField.* <methods>;
    @top.niunaijun.blackreflection.annotation.BStaticMethod.* <methods>;
    @top.niunaijun.blackreflection.annotation.BMethodCheckNotProcess.* <methods>;
    @top.niunaijun.blackreflection.annotation.BConstructor.* <methods>;
    @top.niunaijun.blackreflection.annotation.BConstructorNotProcess.* <methods>;
}

# --- NexusClone core --------------------------------------------------------------
# Kept by name so diagnostics, logcat and crash reports stay readable, and so the
# SQLite/JSON serialisation paths never depend on a renamed class.
-keep class com.kiyoscripts.nexusclone.core.** {*;}
-keep class com.kiyoscripts.nexusclone.ui.** {*;}

# --- Android components -----------------------------------------------------------
# Manifest-declared components are kept by AGP, but keep the lifecycle callbacks too
# because the container instantiates them reflectively.
-keep class * extends android.app.Service {*;}
-keep class * extends android.content.BroadcastReceiver {*;}
-keep class * extends android.app.job.JobService {*;}
-keep class * extends android.app.Application {*;}

# --- Data classes used by JSON / Parcel -------------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# --- Keep line numbers for readable crash reports ---------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Silence warnings from optional dependencies ----------------------------------
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.osmdroid.**
