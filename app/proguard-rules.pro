# Keep WebView JS bridge entry points
-keepclassmembers class com.elina.assistant.ui.AvatarBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclasseswithmembers class **.*$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
