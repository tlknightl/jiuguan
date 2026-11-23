# Keep JavascriptInterface methods for Blob/DataURL download bridge
-keepclassmembers class com.sillytavern.viewer.MainActivity$BlobDownloadBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
