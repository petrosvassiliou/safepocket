# SafePocket v0.1 ProGuard / R8 rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep SQLCipher JNI native bridge classes
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# Keep kotlinx.serialization generated serializers
-keepclassmembers class kotlinx.serialization.json.** { *; }
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
