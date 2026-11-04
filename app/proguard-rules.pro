# ---------------------------------------------------------------------------
# Release builds run R8 with obfuscation enabled, so everything that is reached
# *by name* has to be spelled out here: the Java -> native direction is covered
# by the AGP default rule for `native <methods>`, but nothing protects the
# native -> Java direction (GetMethodID/GetFieldID), nor `Class.forName`, nor the
# class names written inside res/xml, nor the JCA provider registry.
# ---------------------------------------------------------------------------

# Keep Moonlight native / JNI
-keep class com.limelight.nvstream.jni.** { *; }
-keep class com.limelight.stream.** { *; }

# RFB / VNC. Two separate name dependencies on the same class:
#  - NativeLibraryProbe.isVncAvailable() does Class.forName("com.limelight.vnc.RfbClientNative"),
#    which would simply report "VNC unavailable" if the class were renamed;
#  - libnative-vnc.so looks up every callback below by name and signature, and
#    reads the returned UserCredential through its field names. A miss there is a
#    pending NoSuchMethodError, i.e. an ART abort inside the connection loop.
-keep class com.limelight.vnc.RfbClientNative { *; }
-keep class com.limelight.vnc.UserCredential { *; }

# Evdev raw input access, reached through Class.forName() from
# EvdevCaptureProviderShim (kept even though the root-only path is compiled out,
# so that a root build cannot silently lose it).
-keep class com.limelight.binding.input.evdev.** { *; }

# Settings screens. androidx.preference inflates a preference whose tag in
# res/xml/pref_*.xml is a class name, and PrefsActivity.onPreferenceStartFragment
# instantiates the value of `app:fragment` the same way. Neither is a View nor a
# manifest component, so no generated rule covers them; without these the
# PreferenceScreen fails to inflate the moment Settings is opened.
-keep class * extends androidx.preference.Preference { *; }
-keep class * extends androidx.fragment.app.Fragment { *; }

# BouncyCastle is used as an explicit JCA provider (KeyFactory/CertificateFactory
# getInstance(..., bcProvider)). Its service registry maps algorithm names to
# implementation class names stored as strings and loaded reflectively, so
# shrinking or renaming the providers turns pairing and certificate creation
# into NoSuchAlgorithmException.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class sun.misc.Unsafe { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.security.**
-dontwarn java.beans.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# These dependencies reference platform/optional APIs that are not present on
# Android; the warnings are expected and would otherwise fail the R8 task.
-dontwarn okio.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn java.nio.file.**

# ADB wireless pairing (libadb-android). AdbPairingPort.isAvailable probes for
# io.github.muntashirakon.adb.AbsAdbConnectionManager by name, so shrinking or renaming
# that class would report "pairing unavailable" on a build that does contain it.
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn io.github.muntashirakon.adb.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Serializers for @Serializable classes are generated into an inner `$serializer`
# plus a `Companion`; the lookup goes through the companion object, which R8
# otherwise shrinks away. Rules from the kotlinx.serialization README.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.limelight.**$$serializer { *; }
-keepclassmembers class com.limelight.** {
    *** Companion;
}
-keepclasseswithmembers class com.limelight.** {
    kotlinx.serialization.KSerializer serializer(...);
}
