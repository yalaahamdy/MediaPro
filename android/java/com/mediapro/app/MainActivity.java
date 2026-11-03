package com.mediapro.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> fileUploadCallback;
    private static final int FILE_CHOOSER_REQ = 1001;
    private static final int PERMISSION_REQ = 1002;
    private static final int INSTALL_PERMISSION_REQ = 1003;
    private File pendingApkToInstall = null;

    private static class FileComparator implements Comparator<File> {
        @Override
        public int compare(File a, File b) {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.getName().compareToIgnoreCase(b.getName());
        }
    }

    private class PermissionDialogListener implements DialogInterface.OnClickListener {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            launchAllFilesSettings();
        }
    }

    private class BackCallback implements ValueCallback<String> {
        @Override
        public void onReceiveValue(String val) {
            if ("\"exit\"".equals(val) || val == null) {
                finish();
            }
        }
    }

    private class MediaChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileUploadCallback != null) {
                fileUploadCallback.onReceiveValue(null);
            }
            fileUploadCallback = cb;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(intent, "اختر ملفاً"), FILE_CHOOSER_REQ);
            return true;
        }
    }

    private class MediaWebViewClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request != null && request.getUrl() != null) {
                Uri uri = request.getUrl();
                String path = uri.getPath();
                if (path != null && (path.endsWith("MediaPro.html") || path.contains("www/MediaPro.html") || path.contains("www\\MediaPro.html"))) {
                    try {
                        InputStream is = getAssets().open("www/MediaPro.html");
                        return new WebResourceResponse("text/html", "UTF-8", is);
                    } catch (Exception e1) {
                        try {
                            InputStream is = getAssets().open("www\\MediaPro.html");
                            return new WebResourceResponse("text/html", "UTF-8", is);
                        } catch (Exception e2) {
                            try {
                                InputStream is = getAssets().open("MediaPro.html");
                                return new WebResourceResponse("text/html", "UTF-8", is);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            if (url != null && (url.endsWith("MediaPro.html") || url.contains("www/MediaPro.html") || url.contains("www\\MediaPro.html"))) {
                try {
                    InputStream is = getAssets().open("www/MediaPro.html");
                    return new WebResourceResponse("text/html", "UTF-8", is);
                } catch (Exception e1) {
                    try {
                        InputStream is = getAssets().open("www\\MediaPro.html");
                        return new WebResourceResponse("text/html", "UTF-8", is);
                    } catch (Exception e2) {
                        try {
                            InputStream is = getAssets().open("MediaPro.html");
                            return new WebResourceResponse("text/html", "UTF-8", is);
                        } catch (Exception ignored) {}
                    }
                }
            }
            return super.shouldInterceptRequest(view, url);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request != null && request.getUrl() != null) {
                return handleExternalUrl(request.getUrl());
            }
            return false;
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url != null) {
                return handleExternalUrl(Uri.parse(url));
            }
            return false;
        }

        private boolean handleExternalUrl(Uri uri) {
            String scheme = uri.getScheme();
            if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("mailto") || scheme.equalsIgnoreCase("tel"))) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(intent);
                    return true;
                } catch (Exception ignored) {}
            }
            return false;
        }

        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            handleLoadFallback(view);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request != null && request.isForMainFrame()) {
                handleLoadFallback(view);
            }
        }
    }

    private void handleLoadFallback(WebView view) {
        try {
            InputStream is = null;
            try { is = getAssets().open("www/MediaPro.html"); }
            catch (Exception e) {
                try { is = getAssets().open("www\\MediaPro.html"); }
                catch (Exception e2) {
                    try { is = getAssets().open("MediaPro.html"); } catch (Exception ignored) {}
                }
            }
            if (is != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) baos.write(buf, 0, len);
                is.close();
                String html = baos.toString("UTF-8");
                view.loadDataWithBaseURL("file:///android_asset/www/", html, "text/html", "UTF-8", null);
            }
        } catch (Exception ignored) {}
    }

    private class ToastRunnable implements Runnable {
        private final String message;
        public ToastRunnable(String message) { this.message = message; }
        @Override
        public void run() {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private class LongToastRunnable implements Runnable {
        private final String message;
        public LongToastRunnable(String message) { this.message = message; }
        @Override
        public void run() {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        }
    }

    private class LaunchSettingsRunnable implements Runnable {
        @Override
        public void run() {
            launchAllFilesSettings();
        }
    }

    private class EvalScriptRunnable implements Runnable {
        private final String script;
        public EvalScriptRunnable(String script) { this.script = script; }
        @Override
        public void run() {
            if (webView != null) {
                webView.evaluateJavascript(script, null);
            }
        }
    }

    private class CompleteUpdateRunnable implements Runnable {
        private final File apkFile;
        public CompleteUpdateRunnable(File apkFile) { this.apkFile = apkFile; }
        @Override
        public void run() {
            if (webView != null) {
                String script = "if(window.MediaProUpdater&&typeof window.MediaProUpdater.onNativeComplete==='function'){" +
                        "window.MediaProUpdater.onNativeComplete();}";
                webView.evaluateJavascript(script, null);
            }
            installApkFile(apkFile);
        }
    }

    private class ErrorUpdateRunnable implements Runnable {
        private final String errMsg;
        public ErrorUpdateRunnable(String errMsg) { this.errMsg = errMsg; }
        @Override
        public void run() {
            Toast.makeText(MainActivity.this, "فشل تنزيل التحديث: " + errMsg, Toast.LENGTH_LONG).show();
            if (webView != null) {
                String script = "if(window.MediaProUpdater&&typeof window.MediaProUpdater.onNativeError==='function'){" +
                        "window.MediaProUpdater.onNativeError(" + JSONObject.quote(errMsg) + ");}";
                webView.evaluateJavascript(script, null);
            }
        }
    }

    private class UpdateDownloaderRunnable implements Runnable {
        private final String downloadUrl;
        public UpdateDownloaderRunnable(String downloadUrl) { this.downloadUrl = downloadUrl; }

        @Override
        public void run() {
            HttpURLConnection conn = null;
            InputStream in = null;
            FileOutputStream out = null;
            try {
                URL url = new URL(downloadUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "MediaPro-Android");
                conn.connect();

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == 307 || responseCode == 308) {
                    String newUrl = conn.getHeaderField("Location");
                    conn.disconnect();
                    url = new URL(newUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "MediaPro-Android");
                    conn.connect();
                }

                int fileLength = conn.getContentLength();
                in = new BufferedInputStream(conn.getInputStream());

                File cacheDir = getExternalCacheDir();
                if (cacheDir == null) cacheDir = getCacheDir();
                File apkFile = new File(cacheDir, "MediaPro-Update.apk");
                if (apkFile.exists()) apkFile.delete();

                out = new FileOutputStream(apkFile);
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                long lastReportTime = 0;

                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    out.write(buffer, 0, count);

                    long now = System.currentTimeMillis();
                    if (now - lastReportTime > 200) {
                        lastReportTime = now;
                        int percent = fileLength > 0 ? (int) ((total * 100) / fileLength) : -1;
                        String script = "if(window.MediaProUpdater&&typeof window.MediaProUpdater.onNativeProgress==='function'){" +
                                "window.MediaProUpdater.onNativeProgress(" + percent + "," + total + "," + fileLength + ");}";
                        runOnUiThread(new EvalScriptRunnable(script));
                    }
                }

                out.flush();
                out.close();
                out = null;
                in.close();
                in = null;

                runOnUiThread(new CompleteUpdateRunnable(apkFile));

            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                runOnUiThread(new ErrorUpdateRunnable(errMsg));
            } finally {
                try { if (out != null) out.close(); } catch (Exception ignored) {}
                try { if (in != null) in.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new MediaWebViewClient());
        webView.setWebChromeClient(new MediaChromeClient());

        webView.addJavascriptInterface(new Bridge(), "MediaProBridge");
        webView.addJavascriptInterface(new FsBridge(), "MediaProFS");
        webView.addJavascriptInterface(new UpdaterBridge(), "MediaProNativeUpdater");

        requestStorageIfNeeded();
        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/www/MediaPro.html");
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void requestStorageIfNeeded() {
        if (!hasAllFilesAccess()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                new AlertDialog.Builder(this)
                        .setTitle("إذن الوصول إلى الملفات")
                        .setMessage("يحتاج MediaPro إلى إذن الوصول الشامل للملفات لتصفح وتشغيل وإدارة الوسائط على جهازك.")
                        .setPositiveButton("منح الإذن", new PermissionDialogListener())
                        .setNegativeButton("لاحقاً", null)
                        .show();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                requestPermissions(new String[]{
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"
                }, PERMISSION_REQ);
            }
        }
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission("android.permission.READ_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED &&
                   checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void launchAllFilesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(intent);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        notifyFsState();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (webView != null && savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        }
    }

    private void notifyFsState() {
        if (webView != null) {
            webView.evaluateJavascript("if(typeof onMediaProFsReady==='function'){onMediaProFsReady(" + hasAllFilesAccess() + ");}", null);
        }
    }

    @Override
    public void onBackPressed() {
        String js = "try{" +
                "if(typeof handleGlobalBack==='function'){handleGlobalBack() ? 'consumed' : 'exit';} " +
                "else if(typeof handleBackForFiles==='function'&&handleBackForFiles()){'consumed';} " +
                "else if(document.body.classList.contains('focus-mode')){setFocusMode(false);'consumed';} " +
                "else{'exit'};" +
                "}catch(e){'exit'};";
        webView.evaluateJavascript(js, new BackCallback());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQ) {
            if (fileUploadCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    } else if (data.getClipData() != null) {
                        ClipData clip = data.getClipData();
                        results = new Uri[clip.getItemCount()];
                        for (int i = 0; i < clip.getItemCount(); i++) {
                            results[i] = clip.getItemAt(i).getUri();
                        }
                    }
                }
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        } else if (requestCode == INSTALL_PERMISSION_REQ) {
            if (pendingApkToInstall != null && canInstallPackages()) {
                installApkFile(pendingApkToInstall);
            }
            pendingApkToInstall = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private boolean canInstallPackages() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    private void installApkFile(File apkFile) {
        try {
            if (!canInstallPackages()) {
                pendingApkToInstall = apkFile;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, INSTALL_PERMISSION_REQ);
                    Toast.makeText(this, "يرجى السماح بتثبيت التطبيقات من هذا المصدر للمتابعة", Toast.LENGTH_LONG).show();
                    return;
                }
            }

            Uri apkUri = FsProvider.uriFor(apkFile.getAbsolutePath());
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "فشل بدء التثبيت: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /* =========================================================================
     *  MediaProBridge (Compatibility & Save File)
     * ========================================================================= */
    public class Bridge {
        @JavascriptInterface
        public void saveFile(String name, String base64Data) {
            try {
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File target = new File(dir, name != null ? name : "download");
                FileOutputStream fos = new FileOutputStream(target);
                fos.write(bytes);
                fos.flush();
                fos.close();
                runOnUiThread(new ToastRunnable("تم الحفظ في التنزيلات: " + target.getName()));
            } catch (Exception e) {
                runOnUiThread(new LongToastRunnable("خطأ في الحفظ: " + e.getMessage()));
            }
        }
    }

    /* =========================================================================
     *  MediaProNativeUpdater (In-App GitHub Auto-Updater)
     * ========================================================================= */
    public class UpdaterBridge {
        @JavascriptInterface
        public String getVersionName() {
            try {
                PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                return pInfo.versionName;
            } catch (Exception e) {
                return "5.5.0";
            }
        }

        @JavascriptInterface
        public int getVersionCode() {
            try {
                PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    return (int) pInfo.getLongVersionCode();
                } else {
                    return pInfo.versionCode;
                }
            } catch (Exception e) {
                return 10;
            }
        }

        @JavascriptInterface
        public boolean hasInstallPermission() {
            return canInstallPackages();
        }

        @JavascriptInterface
        public void requestInstallPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        }

        @JavascriptInterface
        public void downloadAndInstall(String downloadUrl) {
            new Thread(new UpdateDownloaderRunnable(downloadUrl)).start();
        }
    }

    /* =========================================================================
     *  MediaProFS (Filesystem Bridge)
     * ========================================================================= */
    public class FsBridge {
        @JavascriptInterface
        public String getRoots() {
            try {
                JSONArray arr = new JSONArray();
                File primary = Environment.getExternalStorageDirectory();
                if (primary != null && primary.exists()) {
                    JSONObject obj = new JSONObject();
                    obj.put("name", "الذاكرة الداخلية");
                    obj.put("path", primary.getAbsolutePath());
                    arr.put(obj);
                }
                File[] extDirs = getExternalFilesDirs(null);
                if (extDirs != null) {
                    for (File f : extDirs) {
                        if (f != null) {
                            String p = f.getAbsolutePath();
                            int idx = p.indexOf("/Android/");
                            if (idx > 0) {
                                String root = p.substring(0, idx);
                                if (!root.equals(primary != null ? primary.getAbsolutePath() : "")) {
                                    JSONObject obj = new JSONObject();
                                    obj.put("name", "بطاقة SD");
                                    obj.put("path", root);
                                    arr.put(obj);
                                }
                            }
                        }
                    }
                }
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("roots", arr);
                return res.toString();
            } catch (Exception e) {
                return err(e);
            }
        }

        @JavascriptInterface
        public boolean hasAllFilesAccess() {
            return MainActivity.this.hasAllFilesAccess();
        }

        @JavascriptInterface
        public void requestAllFilesAccess() {
            runOnUiThread(new LaunchSettingsRunnable());
        }

        @JavascriptInterface
        public String list(String path) {
            try {
                File dir = new File(path);
                if (!dir.exists() || !dir.isDirectory()) {
                    return err(new Exception("المجلد غير موجود"));
                }
                File[] files = dir.listFiles();
                JSONArray arr = new JSONArray();
                if (files != null) {
                    Arrays.sort(files, new FileComparator());
                    for (File f : files) {
                        JSONObject item = new JSONObject();
                        item.put("n", f.getName());
                        item.put("p", f.getAbsolutePath());
                        item.put("d", f.isDirectory());
                        item.put("s", f.length());
                        item.put("m", f.lastModified());
                        arr.put(item);
                    }
                }
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("items", arr);
                return res.toString();
            } catch (Exception e) {
                return err(e);
            }
        }

        @JavascriptInterface
        public String readFileBase64(String path) {
            try {
                if (path == null || path.trim().isEmpty()) {
                    return "{\"ok\":false,\"error\":\"مسار غير صالح\"}";
                }
                File f = new File(path);
                if (!f.exists() || !f.isFile()) {
                    return "{\"ok\":false,\"error\":\"الملف غير موجود\"}";
                }
                long len = f.length();
                if (len > 35 * 1024 * 1024) {
                    JSONObject res = new JSONObject();
                    res.put("ok", false);
                    res.put("tooLarge", true);
                    res.put("error", "الملف كبير جداً للقراءة كـ Base64 (أكبر من 35 ميجابايت). يتم تشغيله مباشرة عبر مسار الملف.");
                    return res.toString();
                }
                byte[] bytes = new byte[(int) len];
                FileInputStream fis = new FileInputStream(f);
                int offset = 0;
                while (offset < bytes.length) {
                    int r = fis.read(bytes, offset, bytes.length - offset);
                    if (r < 0) break;
                    offset += r;
                }
                fis.close();
                String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("b64", b64);
                res.put("data", b64);
                res.put("size", len);
                return res.toString();
            } catch (OutOfMemoryError oom) {
                System.gc();
                return "{\"ok\":false,\"error\":\"ذاكرة الجهاز غير كافية لقراءة هذا الملف كـ Base64\"}";
            } catch (Exception e) {
                return err(e);
            }
        }

        @JavascriptInterface
        public String writeFileBase64(String path, String base64Data) {
            try {
                if (path == null || base64Data == null) {
                    return "{\"ok\":false,\"error\":\"مدخلات غير صالحة\"}";
                }
                byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                File target = new File(path);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(target);
                fos.write(bytes);
                fos.flush();
                fos.close();
                JSONObject res = new JSONObject();
                res.put("ok", true);
                res.put("bytes", bytes.length);
                return res.toString();
            } catch (OutOfMemoryError oom) {
                System.gc();
                return "{\"ok\":false,\"error\":\"نفدت الذاكرة أثناء فك تشفير البيانات للكتابة\"}";
            } catch (Exception e) {
                return err(e);
            }
        }

        @JavascriptInterface
        public String mkdir(String path) {
            try {
                File f = new File(path);
                boolean ok = f.mkdirs();
                JSONObject res = new JSONObject();
                res.put("ok", ok || f.exists());
                return res.toString();
            } catch (Exception e) {
                return err(e);
            }
        }

        @JavascriptInterface
        public String renamePath(String path, String newName) {
            try {
                File src = new File(path);
                File parent = src.getParentFile();
                File dest = new File(parent, newName);
                boolean ok = src.renameTo(dest);
                JSONObject res = new JSONObject();
                res.put("ok", ok);
                if (ok) res.put("newPath", dest.getAbsolutePath());
                return res.toString();
            } catch (Exception e) {
                return err(e);
            }
        }

        @JavascriptInterface
        public String copyPath(String src, String dest) {
            try {
                boolean ok = copyRecursive(new File(src), new File(dest));
                JSONObject res = new JSONObject();
                res.put("ok", ok);
                return res.toString();
            } catch (Exception e) {
                return err(e);
            }
        }

        private boolean copyRecursive(File src, File dest) {
            if (src.isDirectory()) {
                if (!dest.exists()) dest.mkdirs();
                File[] list = src.listFiles();
                if (list != null) {
                    for (File child : list) {
                        copyRecursive(child, new File(dest, child.getName()));
                    }
                }
                return true;
            } else {
                try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
        }

        @JavascriptInterface
        public String movePath(String src, String dest) {
            try {
                File s = new File(src);
                File d = new File(dest);
                boolean ok = s.renameTo(d);
                if (!ok) {
                    ok = copyRecursive(s, d) && deleteRecursive(s);
                }
                JSONObject res = new JSONObject();
                res.put("ok", ok);
                return res.toString();
            } catch (Exception e) {
                return err(e);
            }
        }

        @JavascriptInterface
        public String deletePath(String path) {
            try {
                boolean ok = deleteRecursive(new File(path));
                JSONObject res = new JSONObject();
                res.put("ok", ok);
                return res.toString();
            } catch (Exception e) {
                return err(e);
            }
        }

        private boolean deleteRecursive(File f) {
            if (f.isDirectory()) {
                File[] list = f.listFiles();
                if (list != null) {
                    for (File c : list) deleteRecursive(c);
                }
            }
            return f.delete();
        }

        @JavascriptInterface
        public void openWithSystem(String path) {
            try {
                File f = new File(path);
                Uri uri = FsProvider.uriFor(f.getAbsolutePath());
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, FsProvider.fileFor(uri) != null ? new FsProvider().getType(uri) : "*/*");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(Intent.createChooser(intent, "فتح بواسطة"));
            } catch (Exception e) {
                toast("تعذّر فتح الملف: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void shareFile(String path) {
            try {
                File f = new File(path);
                Uri uri = FsProvider.uriFor(f.getAbsolutePath());
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(new FsProvider().getType(uri));
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, "مشاركة الملف"));
            } catch (Exception e) {
                toast("تعذّرت المشاركة: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public String stat(String path) {
            try {
                File f = new File(path);
                JSONObject res = new JSONObject();
                res.put("ok", f.exists());
                res.put("size", f.length());
                res.put("isDir", f.isDirectory());
                res.put("mtimeMs", f.lastModified());
                res.put("canWrite", f.canWrite());
                return res.toString();
            } catch (Exception e) {
                return err(e);
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(new ToastRunnable(msg));
        }
    }

    private String err(Exception e) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("ok", false);
            obj.put("error", e.getMessage() != null ? e.getMessage() : e.toString());
            return obj.toString();
        } catch (Exception ex) {
            return "{\"ok\":false,\"error\":\"error\"}";
        }
    }
}
