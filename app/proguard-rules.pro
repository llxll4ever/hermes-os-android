# Keep Kotlinx Serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class art.rodriguezrodriguez.hermesos.**$$serializer { *; }
-keepclassmembers class art.rodriguezrodriguez.hermesos.** {
    *** Companion;
}
-keepclasseswithmembers class art.rodriguezrodriguez.hermesos.** {
    kotlinx.serialization.KSerializer serializer(...);
}
