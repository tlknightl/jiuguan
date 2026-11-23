package com.sillytavern.viewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val PREFS_NAME = "silly_tavern_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_KEEP_BG_ALIVE = "keep_bg_alive"
        private const val KEY_SHOW_FLOATING_BTN = "show_floating_btn"

        private const val DEFAULT_URL_TEMPLATE = "http://192.168.1.100:8000"
        private const val EXIT_CONFIRM_INTERVAL_MS = 2000L

        // 摇一摇阈值与防抖间隔
        private const val SHAKE_THRESHOLD_G = 2.65f
        private const val SHAKE_DEBOUNCE_MS = 1200L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var rootContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnQuickSettings: TextView

    // 文件选择器回调（用于导入 PNG 角色卡、JSON 世界书/预设/正则/聊天记录等）
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // 防误触返回时间戳
    private var lastBackPressTime: Long = 0L

    // 摇一摇传感器
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var lastShakeTime: Long = 0L
    private var settingsDialog: AlertDialog? = null

    // 最近一次触发 <a download="xxx"> 的文件名缓存（辅助 blob: URL 还原真实文件名）
    @Volatile
    private var lastClickedDownloadFilename: String? = null

    // 云酒馆 HTTP 基础认证（basicAuthMode）凭据缓存，键为「主机|realm」
    private val authCache = mutableMapOf<String, Pair<String, String>>()

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null

        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data
        val uris = mutableListOf<Uri>()

        // 支持多选文件（例如批量导入多张 PNG 角色卡或多个 JSON 预设）
        val clipData = data?.clipData
        if (clipData != null && clipData.itemCount > 0) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i)?.uri?.let { uris.add(it) }
            }
        } else {
            data?.data?.let { uris.add(it) }
        }

        if (uris.isNotEmpty()) {
            callback.onReceiveValue(uris.toTypedArray())
        } else {
            val fallback = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
            callback.onReceiveValue(fallback)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 开启沉浸式边到边布局
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        rootContainer = findViewById(R.id.rootContainer)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        btnQuickSettings = findViewById(R.id.btnQuickSettings)

        // 1. 隐藏顶部状态栏与底部导航栏，并确保软键盘弹出时自动顶起 WebView 底部输入框（不遮挡酒馆发送栏）
        setupImmersiveFullscreenAndKeyboardResize()

        // 2. 应用“保持屏幕常亮”防息屏设置（默认开启）
        val keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        applyKeepScreenOn(keepScreenOn)

        // 2.5 应用“强制后台运行”防切屏/息屏被系统杀后台设置（默认开启）
        val keepBgAlive = prefs.getBoolean(KEY_KEEP_BG_ALIVE, true)
        applyKeepBackgroundAlive(keepBgAlive, askBatteryOptimization = false)

        // 3. 应用右上角半透明快捷按钮显示状态
        val showFloatingBtn = prefs.getBoolean(KEY_SHOW_FLOATING_BTN, true)
        applyFloatingButtonVisibility(showFloatingBtn)
        btnQuickSettings.setOnClickListener {
            showSettingsDialog(isFirstLaunch = false)
        }
        btnQuickSettings.setOnLongClickListener {
            Toast.makeText(this, "正在刷新酒馆页面…", Toast.LENGTH_SHORT).show()
            webView.reload()
            true
        }

        // 4. 初始化摇一摇传感器（随时摇晃手机也可呼出地址与常亮设置弹窗）
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // 5. 配置 WebView（放行局域网 HTTP、DOMStorage、文件导入 onShowFileChooser、文件导出 DownloadListener & Blob 桥接）
        setupWebView()

        // 6. 拦截系统返回键与全面屏左右侧滑手势：2 秒内连续触发两次才退出
        setupBackPressInterceptor()

        // 7. 检查本地持久化保存的酒馆地址：首次打开弹出输入框，已保存则直接加载
        //    若历史地址是 127.0.0.1 / localhost 这类回环地址（手机上永远连不通电脑），则重新弹出设置框引导修改
        val savedUrl = prefs.getString(KEY_SERVER_URL, null)?.trim().orEmpty()
        if (savedUrl.isEmpty() || isLoopbackHost(normalizeServerUrl(savedUrl))) {
            showSettingsDialog(isFirstLaunch = true)
        } else {
            loadTavernUrl(savedUrl)
        }
    }

    /**
     * 沉浸式全屏 + 软键盘自动顶起页面（adjustResize 兼容实现）
     * 解决 Android 全屏模式下 adjustResize 失效导致软键盘遮挡 SillyTavern 底部聊天输入框的经典问题。
     */
    private fun setupImmersiveFullscreenAndKeyboardResize() {
        hideSystemBars()

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, windowInsets ->
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val isImeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())

            // 当软键盘弹出时，给根容器底部设置与键盘高度一致的 padding，使 WebView 视口高度自动收缩（触发 CSS vh / flex 重新布局）
            val bottomPadding = if (isImeVisible) imeInsets.bottom else 0
            if (view.paddingBottom != bottomPadding) {
                view.setPadding(0, 0, 0, bottomPadding)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    /**
     * 防息屏开关（FLAG_KEEP_SCREEN_ON）
     */
    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        webView.keepScreenOn = enabled
    }

    /**
     * 强制后台运行开关（启动前台常驻服务 + CPU 唤醒锁 + 申请忽略系统电池优化白名单）
     * 防止用户在等待酒馆 AI 生成长回复时切换到微信/其他软件或锁屏后，被系统冻结或杀后台导致生成断连。
     */
    @SuppressLint("BatteryLife")
    private fun applyKeepBackgroundAlive(enabled: Boolean, askBatteryOptimization: Boolean) {
        if (enabled) {
            KeepAliveService.start(this)
            if (askBatteryOptimization && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    }
                } catch (_: Exception) {
                }
            }
        } else {
            KeepAliveService.stop(this)
        }
    }

    private fun applyFloatingButtonVisibility(visible: Boolean) {
        btnQuickSettings.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 拦截系统返回键和全面屏侧滑返回手势：
     * 无论当前处于何种页面状态，均需在 2 秒内连续触发两次返回才退出 App，防止在酒馆打字或滑动时误触侧滑直接关掉酒馆。
     */
    private fun setupBackPressInterceptor() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBackPressTime < EXIT_CONFIRM_INTERVAL_MS) {
                    finish()
                } else {
                    lastBackPressTime = now
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.back_exit_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    /**
     * 配置 WebView 核心参数、文件选择器与下载器
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false

            // 放行 HTTP/HTTPS 混合内容（例如局域网 HTTP 酒馆引用外部资源或本地图床）
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // 视口与缩放适配
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100

            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // 注入 JS 桥接：完美支持 SillyTavern 前端通过 Blob URL / Data URL 导出 JSON 角色卡、世界书、聊天记录和 PNG 角色卡
        webView.addJavascriptInterface(BlobDownloadBridge(), "STAndroidDownloader")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
                return if (scheme == "http" || scheme == "https") {
                    false // 由当前 WebView 加载
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    } catch (_: Exception) {
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                injectDownloadHelperScript()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // 当主框架连接失败（例如电脑端酒馆尚未启动或局域网 IP 变动）时，友好提示并支持一键修改地址
                if (request?.isForMainFrame == true) {
                    val failingUrl = request.url?.toString().orEmpty()
                    val desc = error?.description?.toString() ?: "无法连接到酒馆服务器"
                    renderOfflineErrorPage(failingUrl, desc)
                }
            }

            /**
             * 云酒馆 / 开了 basicAuthMode 的酒馆会返回 401 要求 HTTP 基础认证。
             * Android WebView 默认【不会】自己弹登录框，必须手动接管，否则页面只会是一片白屏。
             */
            override fun onReceivedHttpAuthRequest(
                view: WebView?,
                handler: HttpAuthHandler,
                host: String,
                realm: String
            ) {
                val key = "$host|$realm"
                val cached = authCache[key]
                if (cached != null) {
                    handler.proceed(cached.first, cached.second)
                    return
                }
                showHttpAuthDialog(handler, host, realm) { user, pass ->
                    authCache[key] = user to pass
                    webView.setHttpAuthUsernamePassword(host, realm, user, pass)
                }
            }

            /**
             * 自建云酒馆常用自签证书，证书无效时 WebView 会直接白屏。
             * 这里明确告知风险，由用户自己决定是否继续访问。
             */
            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler,
                error: SslError?
            ) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("证书不受信任")
                    .setMessage(
                        "该地址的 HTTPS 证书无效或已过期，常见于自建服务器未配置正式证书。\n\n" +
                            "继续访问会降低连接安全性（可能被中间人窃听）。确定要继续吗？"
                    )
                    .setCancelable(false)
                    .setPositiveButton("继续访问") { _, _ -> handler.proceed() }
                    .setNegativeButton("取消") { _, _ -> handler.cancel() }
                    .show()
            }
        }

        // 完整实现 onShowFileChooser：支持导入 PNG 角色卡、JSON 世界书/预设/正则/聊天记录等任意文件
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // 若上一次回调未释放，先置空防卡死
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val acceptTypes = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotBlank() }
                    .orEmpty()
                val allowMultiple =
                    fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)

                    // 将网页 <input type="file" accept=".png,.json,image/png,application/json"> 转换为 Android 标准 MIME 类型
                    val resolvedMimeTypes = resolveAcceptMimeTypes(acceptTypes)
                    if (resolvedMimeTypes.size == 1) {
                        type = resolvedMimeTypes.first()
                    } else if (resolvedMimeTypes.size > 1) {
                        putExtra(Intent.EXTRA_MIME_TYPES, resolvedMimeTypes.toTypedArray())
                    }
                }

                return try {
                    fileChooserLauncher.launch(
                        Intent.createChooser(intent, "选择要导入酒馆的角色卡 / 预设 / 世界书")
                    )
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    Toast.makeText(
                        this@MainActivity,
                        "无法打开系统文件选择器: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                    false
                }
            }
        }

        // 完整实现 DownloadListener：支持常规 HTTP 下载、Blob URL 下载以及 Data URL 下载
        webView.setDownloadListener(
            DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                handleWebViewDownload(url, userAgent, contentDisposition, mimetype)
            }
        )
    }

    /**
     * 将网页的 accept 属性（可能包含 `.png`, `.json`, `.jsonl`, `.charx` 等扩展名或 MIME 类型）解析为 Android 支持的 MIME 列表。
     * 注意：部分国产手机系统的文件管理器对 `application/json` 过滤较严，因此若包含 json/jsonl/charx/txt 等格式，保留通配符以确保所有文件可选。
     */
    private fun resolveAcceptMimeTypes(rawTypes: List<String>): List<String> {
        if (rawTypes.isEmpty()) return emptyList()
        val items = rawTypes
            .flatMap { it.split(",") }
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }

        if (items.isEmpty()) return emptyList()

        // 如果包含 json、jsonl、txt、charx、yaml 等后缀，直接返回 `*/*` 让用户能无障碍选择任何扩展名的预设/世界书/聊天记录
        val hasCustomOrJsonExt = items.any {
            it.contains("json") || it.contains("txt") || it.contains("charx") ||
                it.contains("yaml") || it.contains("yml") || it == "*/*"
        }
        if (hasCustomOrJsonExt) {
            return emptyList() // keep intent.type = "*/*"
        }

        val mimeSet = linkedSetOf<String>()
        for (item in items) {
            if (item.contains("/")) {
                mimeSet.add(item)
            } else if (item.startsWith(".")) {
                val ext = item.removePrefix(".")
                val guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                if (!guessed.isNullOrBlank()) {
                    mimeSet.add(guessed)
                }
            }
        }
        return mimeSet.toList()
    }

    /**
     * 在页面加载完成后注入辅助脚本：
     * 1. 监听带有 `download` 属性的 `<a>` 标签点击，记录原始文件名（如 `角色名.png` 或 `世界书.json`）。
     * 2. 当遇到 Android WebView 原生 DownloadManager 无法处理的 `blob:` 协议链接时，在前端将其读取为 Base64 并回传给原生保存到系统 Downloads 目录。
     */
    private fun injectDownloadHelperScript() {
        val js = """
            (function() {
                if (window.__stAndroidDownloadHooked) return;
                window.__stAndroidDownloadHooked = true;

                document.addEventListener('click', function(e) {
                    var el = e.target;
                    while (el && el.tagName !== 'A') {
                        el = el.parentElement;
                    }
                    if (!el) return;
                    var dlName = el.getAttribute('download');
                    var href = el.href || '';
                    if (dlName && window.STAndroidDownloader) {
                        window.STAndroidDownloader.recordSuggestedFileName(dlName);
                    }
                    if (href.indexOf('blob:') === 0 && window.STAndroidDownloader) {
                        e.preventDefault();
                        window.STAndroidDownloader.downloadBlobUrl(href, dlName || '');
                    }
                }, true);

                window.__stFetchBlobToAndroid = function(blobUrl, suggestedName, mimeType) {
                    fetch(blobUrl)
                        .then(function(res) { return res.blob(); })
                        .then(function(blob) {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                var base64data = reader.result;
                                var finalMime = blob.type || mimeType || 'application/octet-stream';
                                window.STAndroidDownloader.saveBase64Data(base64data, suggestedName || '', finalMime);
                            };
                            reader.readAsDataURL(blob);
                        })
                        .catch(function(err) {
                            window.STAndroidDownloader.onBlobError(String(err));
                        });
                };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    /**
     * 统一处理 WebView 下载请求（支持 `http://`、`https://`、`blob:`、`data:` 四种导出方式）
     */
    private fun handleWebViewDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        if (url.isNullOrBlank()) return

        val suggestedName = lastClickedDownloadFilename?.takeIf { it.isNotBlank() }
            ?: URLUtil.guessFileName(url, contentDisposition, mimeType)

        when {
            url.startsWith("blob:", ignoreCase = true) -> {
                fetchBlobFromWebView(url, suggestedName, mimeType.orEmpty())
            }

            url.startsWith("data:", ignoreCase = true) -> {
                saveDataUrlToDownloads(url, suggestedName, mimeType)
            }

            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) -> {
                enqueueSystemHttpDownload(url, userAgent, contentDisposition, mimeType, suggestedName)
            }

            else -> {
                Toast.makeText(this, "不支持的下载协议: $url", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchBlobFromWebView(blobUrl: String, fileName: String, mimeType: String) {
        val safeUrl = org.json.JSONObject.quote(blobUrl)
        val safeName = org.json.JSONObject.quote(fileName)
        val safeMime = org.json.JSONObject.quote(mimeType)
        webView.post {
            webView.evaluateJavascript(
                "window.__stFetchBlobToAndroid && window.__stFetchBlobToAndroid($safeUrl, $safeName, $safeMime);",
                null
            )
        }
    }

    /**
     * 使用系统 DownloadManager 下载 HTTP/HTTPS 文件（自动附带酒馆 Cookie 与 User-Agent）
     */
    private fun enqueueSystemHttpDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        suggestedName: String
    ) {
        try {
            val fileName = sanitizeFileName(
                suggestedName.ifBlank {
                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                },
                mimeType
            )

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription("正在从 SillyTavern 导出文件…")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)

                if (!mimeType.isNullOrBlank()) {
                    setMimeType(mimeType)
                }
                if (!userAgent.isNullOrBlank()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                val cookie = CookieManager.getInstance().getCookie(url)
                if (!cookie.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookie)
                }

                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            lastClickedDownloadFilename = null
            Toast.makeText(
                this,
                "已开始导出到系统「下载 (Downloads)」目录: $fileName",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            // 如果系统 DownloadManager 因特定原因失败，回退为前端 fetch 转 Base64 保存
            fetchBlobFromWebView(url, suggestedName, mimeType.orEmpty())
        }
    }

    /**
     * 将 DataURL (`data:image/png;base64,...` 或 `data:application/json;charset=utf-8,...`) 直接写入手机系统「下载 (Downloads)」目录
     */
    private fun saveDataUrlToDownloads(
        dataUrl: String,
        suggestedName: String?,
        fallbackMimeType: String?
    ) {
        Thread {
            try {
                val commaIndex = dataUrl.indexOf(',')
                if (commaIndex == -1) {
                    throw IllegalArgumentException("无效的 Data URL 格式")
                }

                val header = dataUrl.substring(5, commaIndex) // 去掉 "data:"
                val payload = dataUrl.substring(commaIndex + 1)
                val headerParts = header.split(";")
                val detectedMime = headerParts.firstOrNull()?.takeIf { it.contains("/") }
                    ?: fallbackMimeType?.takeIf { it.isNotBlank() }
                    ?: "application/octet-stream"
                val isBase64 = headerParts.any { it.equals("base64", ignoreCase = true) }

                val bytes: ByteArray = if (isBase64) {
                    Base64.decode(payload, Base64.DEFAULT)
                } else {
                    Uri.decode(payload).toByteArray(Charsets.UTF_8)
                }

                val finalFileName = sanitizeFileName(
                    suggestedName?.takeIf { it.isNotBlank() }
                        ?: lastClickedDownloadFilename?.takeIf { it.isNotBlank() }
                        ?: buildTimestampFileName(detectedMime),
                    detectedMime
                )
                lastClickedDownloadFilename = null

                writeBytesToPublicDownloads(finalFileName, detectedMime, bytes)

                runOnUiThread {
                    Toast.makeText(
                        this,
                        "已导出到手机下载目录: Download/$finalFileName",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "导出文件失败: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /**
     * 兼容 Android 7 ~ Android 14+ 将字节流写入系统公共 Downloads 目录
     */
    private fun writeBytesToPublicDownloads(fileName: String, mimeType: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IllegalStateException("无法在系统下载目录创建文件")

            resolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: throw IllegalStateException("无法打开下载输出流")

            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val outFile = File(downloadsDir, fileName)
            FileOutputStream(outFile).use { out ->
                out.write(bytes)
                out.flush()
            }
        }
    }

    private fun buildTimestampFileName(mimeType: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val ext = resolveExtensionFromMime(mimeType)
        return "SillyTavern_$timestamp.$ext"
    }

    private fun resolveExtensionFromMime(mimeType: String?): String {
        val normalized = mimeType?.lowercase(Locale.ROOT)?.substringBefore(";")?.trim().orEmpty()
        return when (normalized) {
            "application/json", "text/json" -> "json"
            "application/x-ndjson", "application/jsonl" -> "jsonl"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/jpeg" -> "jpg"
            "text/plain" -> "txt"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(normalized) ?: "dat"
        }
    }

    private fun sanitizeFileName(rawName: String, mimeType: String?): String {
        var cleaned = rawName.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (cleaned.isBlank() || cleaned.equals("download.bin", ignoreCase = true)) {
            cleaned = buildTimestampFileName(mimeType.orEmpty())
        }
        if (!cleaned.contains(".")) {
            val ext = resolveExtensionFromMime(mimeType)
            cleaned = "$cleaned.$ext"
        }
        return cleaned
    }

    /**
     * 供 WebView 内部调用的 Blob / DataURL 导出桥接类
     */
    inner class BlobDownloadBridge {
        @JavascriptInterface
        fun recordSuggestedFileName(name: String?) {
            if (!name.isNullOrBlank()) {
                lastClickedDownloadFilename = name.trim()
            }
        }

        @JavascriptInterface
        fun downloadBlobUrl(blobUrl: String?, suggestedName: String?) {
            if (blobUrl.isNullOrBlank()) return
            if (!suggestedName.isNullOrBlank()) {
                lastClickedDownloadFilename = suggestedName.trim()
            }
            fetchBlobFromWebView(blobUrl, suggestedName.orEmpty(), "")
        }

        @JavascriptInterface
        fun saveBase64Data(dataUrl: String?, suggestedName: String?, mimeType: String?) {
            if (dataUrl.isNullOrBlank()) return
            saveDataUrlToDownloads(dataUrl, suggestedName, mimeType)
        }

        @JavascriptInterface
        fun onBlobError(errorMsg: String?) {
            runOnUiThread {
                Toast.makeText(
                    this@MainActivity,
                    "导出数据时出错: ${errorMsg ?: "未知错误"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * 规范化并加载酒馆地址（若未写 http:// 或 https://，默认自动补全 http://）
     */
    private fun normalizeServerUrl(input: String): String {
        var url = input.trim()
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            url = "http://$url"
        }
        return url
    }

    /**
     * 弹出酒馆账号密码输入框（用于云酒馆 / 开启 basicAuthMode 的公网酒馆）
     */
    private fun showHttpAuthDialog(
        handler: HttpAuthHandler,
        host: String,
        realm: String,
        onCredentials: (String, String) -> Unit
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), 0)
        }
        val etUser = EditText(this).apply {
            hint = "用户名"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val etPass = EditText(this).apply {
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        container.addView(etUser)
        container.addView(etPass)

        AlertDialog.Builder(this)
            .setTitle("酒馆需要登录")
            .setMessage(
                "服务器 $host 要求输入账号密码（即酒馆配置里 basicAuthMode 设置的账号）。\n" +
                    "认证域：${realm.ifBlank { "默认" }}"
            )
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("登录") { _, _ ->
                val user = etUser.text?.toString().orEmpty()
                val pass = etPass.text?.toString().orEmpty()
                onCredentials(user, pass)
                handler.proceed(user, pass)
            }
            .setNegativeButton("取消") { _, _ -> handler.cancel() }
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 判断地址是否为「手机自己」的回环地址（127.0.0.1 / localhost / 0.0.0.0）。
     * 手机里的 127.0.0.1 指向手机本身，所以永远无法访问电脑上的酒馆，必须改成电脑的局域网 IP。
     */
    private fun isLoopbackHost(url: String): Boolean {
        if (url.isBlank()) return false
        val host = try {
            Uri.parse(url).host?.lowercase(Locale.ROOT).orEmpty()
        } catch (e: Exception) {
            ""
        }
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "0.0.0.0"
    }

    private fun loadTavernUrl(rawUrl: String) {
        val finalUrl = normalizeServerUrl(rawUrl)
        if (finalUrl.isEmpty()) {
            showSettingsDialog(isFirstLaunch = true)
            return
        }
        webView.loadUrl(finalUrl)
    }

    /**
     * 当无法连接到局域网酒馆服务器时展示的内置深色引导页
     */
    private fun renderOfflineErrorPage(failingUrl: String, errorDescription: String) {
        val html = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>连接酒馆失败</title>
                <style>
                    body {
                        margin: 0;
                        padding: 24px;
                        background: #131218;
                        color: #EAE6F2;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        min-height: 85vh;
                        text-align: center;
                    }
                    .card {
                        max-width: 420px;
                        background: #1E1C26;
                        border: 1px solid #353144;
                        border-radius: 14px;
                        padding: 24px 20px;
                        box-shadow: 0 8px 24px rgba(0,0,0,0.45);
                    }
                    h2 {
                        margin: 0 0 10px;
                        font-size: 19px;
                        color: #F3C278;
                    }
                    p {
                        margin: 8px 0;
                        font-size: 13.5px;
                        line-height: 1.6;
                        color: #BDB7CC;
                        word-break: break-all;
                    }
                    .code {
                        display: inline-block;
                        background: #121017;
                        padding: 4px 8px;
                        border-radius: 6px;
                        color: #E8A855;
                        font-family: monospace;
                        font-size: 12.5px;
                    }
                    .tips {
                        text-align: left;
                        margin-top: 14px;
                        padding: 12px;
                        background: #16141D;
                        border-radius: 8px;
                        font-size: 12.5px;
                        color: #9E97B0;
                    }
                    .btn {
                        margin-top: 16px;
                        display: inline-block;
                        background: #D4A359;
                        color: #15131B;
                        font-weight: 600;
                        padding: 10px 20px;
                        border-radius: 8px;
                        text-decoration: none;
                        font-size: 14px;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>未连接到局域网酒馆</h2>
                    <p>当前地址：<span class="code">$failingUrl</span></p>
                    <p>错误信息：$errorDescription</p>
                    <div class="tips">
                        <strong>排查建议：</strong><br/>
                        1. 不要填 127.0.0.1 或 localhost：它们在手机里指的是手机自己，请填电脑的局域网 IP（如 192.168.1.100）。<br/>
                        2. 确认手机与运行 SillyTavern 的电脑连接同一个 Wi-Fi / 路由器。<br/>
                        3. 确认酒馆 <span class="code">config.yaml</span> 中已开启 <span class="code">listen: true</span>，并把 <span class="code">whitelistMode</span> 设为 false 或加入手机 IP。<br/>
                        4. 确认电脑防火墙已放行 8000 端口（Windows 首次运行 Node.js 时弹窗要选“允许访问”）。<br/>
                        5. 先用手机浏览器打开同一地址试一下，能打开说明网络通了，再回到本 App。<br/>
                        6. 点击屏幕<strong>右上角半透明小齿轮</strong>（或<strong>摇一摇手机</strong>）修改 IP 与端口。
                    </div>
                    <a class="btn" href="$failingUrl">重新连接</a>
                </div>
            </body>
            </html>
        """.trimIndent()
        webView.loadDataWithBaseURL(failingUrl, html, "text/html", "UTF-8", failingUrl)
    }

    /**
     * 弹出酒馆地址管理与防息屏设置窗口
     * - 首次启动时必须填写或确认酒馆地址
     * - 平时可通过右上角半透明小按钮或“摇一摇手机”随时呼出
     */
    private fun showSettingsDialog(isFirstLaunch: Boolean) {
        if (settingsDialog?.isShowing == true) return

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etServerUrl = dialogView.findViewById<EditText>(R.id.etServerUrl)
        val switchKeepScreenOn = dialogView.findViewById<SwitchCompat>(R.id.switchKeepScreenOn)
        val switchKeepBgAlive = dialogView.findViewById<SwitchCompat>(R.id.switchKeepBgAlive)
        val switchShowFloatingBtn = dialogView.findViewById<SwitchCompat>(R.id.switchShowFloatingBtn)

        val currentUrl = prefs.getString(KEY_SERVER_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_URL_TEMPLATE
        etServerUrl.setText(currentUrl)
        etServerUrl.setSelection(etServerUrl.text.length)

        val currentKeepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        switchKeepScreenOn.isChecked = currentKeepScreenOn

        val currentKeepBgAlive = prefs.getBoolean(KEY_KEEP_BG_ALIVE, true)
        switchKeepBgAlive.isChecked = currentKeepBgAlive

        val currentShowFloating = prefs.getBoolean(KEY_SHOW_FLOATING_BTN, true)
        switchShowFloatingBtn.isChecked = currentShowFloating

        // 实时切换防息屏开关
        switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, isChecked).apply()
            applyKeepScreenOn(isChecked)
            val msgRes = if (isChecked) R.string.screen_on_enabled else R.string.screen_on_disabled
            Toast.makeText(this, getString(msgRes), Toast.LENGTH_SHORT).show()
        }

        // 实时切换强制后台运行（防切屏/息屏被系统杀后台断连）开关
        switchKeepBgAlive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_KEEP_BG_ALIVE, isChecked).apply()
            applyKeepBackgroundAlive(isChecked, askBatteryOptimization = isChecked)
            val msgRes = if (isChecked) R.string.bg_alive_enabled else R.string.bg_alive_disabled
            Toast.makeText(this, getString(msgRes), Toast.LENGTH_SHORT).show()
        }

        // 实时切换右上角半透明快捷按钮显示/隐藏
        switchShowFloatingBtn.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_SHOW_FLOATING_BTN, isChecked).apply()
            applyFloatingButtonVisibility(isChecked)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogView)
            .setCancelable(!isFirstLaunch)
            .setPositiveButton(R.string.btn_save_and_load, null)

        if (!isFirstLaunch) {
            builder.setNeutralButton(R.string.btn_reload) { _, _ ->
                webView.reload()
            }
            builder.setNegativeButton(R.string.btn_cancel, null)
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveBtn.setOnClickListener {
                val rawInput = etServerUrl.text?.toString()?.trim().orEmpty()
                val normalized = normalizeServerUrl(rawInput)
                if (normalized.isEmpty()) {
                    Toast.makeText(
                        this,
                        getString(R.string.url_empty_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                // 127.0.0.1 / localhost 在手机里指向手机自己，连不到电脑上的酒馆，直接拦截并给出正确写法
                if (isLoopbackHost(normalized)) {
                    Toast.makeText(
                        this,
                        "127.0.0.1 和 localhost 指的是手机自己，连不到电脑上的酒馆。\n请填写电脑的局域网 IP，例如 http://192.168.1.100:8000",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }

                prefs.edit()
                    .putString(KEY_SERVER_URL, normalized)
                    .putBoolean(KEY_KEEP_SCREEN_ON, switchKeepScreenOn.isChecked)
                    .putBoolean(KEY_KEEP_BG_ALIVE, switchKeepBgAlive.isChecked)
                    .putBoolean(KEY_SHOW_FLOATING_BTN, switchShowFloatingBtn.isChecked)
                    .apply()

                applyKeepScreenOn(switchKeepScreenOn.isChecked)
                applyKeepBackgroundAlive(
                    switchKeepBgAlive.isChecked,
                    askBatteryOptimization = switchKeepBgAlive.isChecked
                )
                applyFloatingButtonVisibility(switchShowFloatingBtn.isChecked)
                dialog.dismiss()
                loadTavernUrl(normalized)
            }
        }
        dialog.setOnDismissListener {
            hideSystemBars()
        }

        settingsDialog = dialog
        dialog.show()
    }

    /**
     * 摇一摇手机触发设置弹窗
     */
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH
        val gForce = sqrt(gX * gX + gY * gY + gZ * gZ)

        if (gForce > SHAKE_THRESHOLD_G) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastShakeTime > SHAKE_DEBOUNCE_MS) {
                lastShakeTime = now
                showSettingsDialog(isFirstLaunch = false)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 无需处理
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        accelerometer?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        sensorManager?.unregisterListener(this)
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        KeepAliveService.stop(this)
        settingsDialog?.dismiss()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
