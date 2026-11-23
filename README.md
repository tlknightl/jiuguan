# 简单的酒馆客户端

移动端访问局域网更方便

---

## 核心特性

1. **防误触退出 (`OnBackPressedDispatcher`)**
   - 拦截系统返回键与全面屏左右侧滑返回手势。
   - 必须在 **2 秒内连续触发两次** 才会退出 App，首次触发弹出 Toast 轻提示（`再按一次退出酒馆`），彻底避免在酒馆打字或滑动抽屉时误触侧滑导致直接退出。

2. **沉浸式全屏与软键盘自适应 (`adjustResize` + `WindowInsetsCompat.Type.ime()`)**
   - 隐藏顶部系统状态栏与标题栏，支持全面屏/挖孔屏全屏沉浸阅读。
   - 通过 `WindowInsetsCompat.Type.ime()` 动态监听软键盘高度并自动收缩 WebView 底部视口，完美解决 Android 全屏模式下 `adjustResize` 失效的问题，确保软键盘弹出时自动顶起酒馆底部聊天输入框，绝不遮挡发送栏。

3. **局域网明文 HTTP 放行与地址管理**
   - `AndroidManifest.xml` 已开启 `android:usesCleartextTraffic="true"` 并放行混合内容（`MIXED_CONTENT_ALWAYS_ALLOW`）。
   - **首次启动**：自动弹出地址设置框，填写局域网酒馆地址（如 `http://192.168.1.100:8000`，不写 `http://` 也会自动补全）并通过 `SharedPreferences` 本地持久化保存。
   - **双快捷入口**：
     - **右上角半透明小齿轮按钮 (`⚙`)**：点击呼出地址与显示设置弹窗；**长按小齿轮**可一键刷新当前页面；支持在设置中隐藏该按钮。
     - **摇一摇手机**：内置加速度传感器监听，随时摇晃一下手机即可呼出设置弹窗切换 IP/端口或刷新页面。

4. **完整支持酒馆文件导入与导出**
   - **文件导入 (`onShowFileChooser`)**：完整支持单选与多选本地文件，兼容 `.png` 角色卡、`.json` 世界书/预设/正则脚本、`.jsonl` 聊天记录及 `.charx` 等所有格式。
   - **文件导出 (`DownloadListener` + `Blob/DataURL Bridge`)**：不仅支持常规 HTTP 下载，还专门针对 SillyTavern 前端大量使用的 `blob:` 与 `data:` 导出机制注入了 `STAndroidDownloader` 桥接，可将导出的角色卡 PNG、世界书 JSON、聊天记录直接保存到手机系统 **`Download/`（下载）** 目录。

5. **防息屏常亮开关 (`FLAG_KEEP_SCREEN_ON`)**
   - 设置弹窗内置 **「保持屏幕常亮 (防息屏)」** 开关（默认开启，可随时自由切换并持久化保存），挂机等待长回复或生图时屏幕不会自动熄灭。

