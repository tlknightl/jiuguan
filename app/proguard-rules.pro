# Keep JavascriptInterface methods for Blob/DataURL download bridge and AccountManager bridge
-keepclassmembers class com.sillytavern.viewer.MainActivity$BlobDownloadBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class com.sillytavern.viewer.MainActivity$AccountManagerBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
