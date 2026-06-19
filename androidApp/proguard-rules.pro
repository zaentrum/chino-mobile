-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn okhttp3.**
-dontwarn okio.**

# kotlinx.serialization
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
