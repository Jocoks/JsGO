package com.jocoks.jsgo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * JsGO 外壳：单 WebView 承载 assets/index.html。
 * 只暴露四类能力：工作区读写（App 私有目录）、文件打开/保存（SAF）、返回键回传。
 * 页面侧约定见 assets/index.html 顶部 JsGO 桥注释。
 */
public class MainActivity extends Activity {

    private static final int REQ_OPEN = 1001;
    private static final int REQ_SAVE = 1002;
    private static final String PREFS = "jsgo";
    private static final String KEY_SAVE_URI = "save_uri";
    private static final int CHUNK_NOTE = 65536;

    private WebView web;

    /** 当前绑定的 jsonl 目标文件；为空表示还没另存过 */
    private Uri saveUri;
    /** API 33+ 的返回回调（反射注册，避免低版本 dex 引用新类） */
    private Object backCallback;
    private long backAt = 0;
    /** 待写入的文件内容（SAF 选完文件后落盘） */
    private String pendingText = "";

    /** 工作区缓存（分块回传给页面） */
    private final StringBuilder storeBuf = new StringBuilder();
    private volatile String storeText = "";
    private volatile boolean storeLoaded = false;
    /** 刚打开的文件内容（分块回传给页面） */
    private volatile String openText = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = sp.getString(KEY_SAVE_URI, null);
        if (saved != null) {
            try {
                saveUri = Uri.parse(saved);
            } catch (Exception ignored) {
            }
        }

        web = new WebView(this);
        web.setBackgroundColor(0xFFFAF7F0);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(false);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "JsGO");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new Chrome());

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFAF7F0);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applySystemBars(root);
        registerBackCallback();
        root.post(new Runnable() {
            @Override
            public void run() {
                View r = findViewById(android.R.id.content);
                if (r != null) r.requestApplyInsets();
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    /** 状态栏/导航栏跟随页面底色；Android 11 起自己接管 insets，给内容留出系统栏空间 */
    private void applySystemBars(final View root) {
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0xFFFAF7F0);
        w.setNavigationBarColor(0xFFFAF7F0);

        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(mask, mask);
            }
            w.setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    int bottom = Math.max(bars.bottom, ime);
                    v.setPadding(bars.left, bars.top, bars.right, bottom);
                    return insets;
                }
            });
        } else if (Build.VERSION.SDK_INT >= 23) {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        // 首帧兜底：监听回调前先按状态栏高度留白，避免内容顶到状态栏下面
        if (Build.VERSION.SDK_INT >= 30) {
            root.setPadding(0, statusBarHeight(), 0, 0);
        }
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) return getResources().getDimensionPixelSize(id);
        float d = getResources().getDisplayMetrics().density;
        return Math.round(24 * d);
    }

    /** 系统对话框（页面里的 confirm / alert 走这里） */
    private class Chrome extends WebChromeClient {
        @Override
        public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            result.confirm();
                        }
                    })
                    .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            result.cancel();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface d) {
                            result.cancel();
                        }
                    })
                    .show();
            return true;
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message)
                    .setPositiveButton("知道了", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int which) {
                            result.confirm();
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface d) {
                            result.cancel();
                        }
                    })
                    .show();
            return true;
        }
    }

    /* ==================== 页面桥 ==================== */

    private class Bridge {

        /* ---- 工作区（App 私有目录 workspace.json）---- */

        @JavascriptInterface
        public void storeBegin() {
            synchronized (storeBuf) {
                storeBuf.setLength(0);
            }
        }

        @JavascriptInterface
        public void storeAppend(String s) {
            if (s == null) return;
            synchronized (storeBuf) {
                storeBuf.append(s);
            }
        }

        @JavascriptInterface
        public void storeCommit() {
            final String text;
            synchronized (storeBuf) {
                text = storeBuf.toString();
            }
            final File f = storeFile();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        FileOutputStream fos = new FileOutputStream(f);
                        fos.write(text.getBytes(StandardCharsets.UTF_8));
                        fos.close();
                        storeText = text;
                        storeLoaded = true;
                    } catch (Exception ignored) {
                    }
                }
            }).start();
        }

        @JavascriptInterface
        public int storeLength() {
            ensureStore();
            return storeText.length();
        }

        @JavascriptInterface
        public String storeChunk(int start, int end) {
            ensureStore();
            return slice(storeText, start, end);
        }

        /* ---- 文件打开 ---- */

        @JavascriptInterface
        public void openFile() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    try {
                        startActivityForResult(i, REQ_OPEN);
                    } catch (Exception e) {
                        recv("没有可用的文件选择器", "error");
                    }
                }
            });
        }

        @JavascriptInterface
        public int openLength() {
            return openText.length();
        }

        @JavascriptInterface
        public String openChunk(int start, int end) {
            return slice(openText, start, end);
        }

        /* ---- 文件保存 ---- */

        @JavascriptInterface
        public void saveBegin() {
            synchronized (storeBuf) {
                storeBuf.setLength(0);
            }
        }

        @JavascriptInterface
        public void appendText(String s) {
            if (s == null) return;
            synchronized (storeBuf) {
                storeBuf.append(s);
            }
        }

        @JavascriptInterface
        public void saveCommit() {
            final String text;
            synchronized (storeBuf) {
                text = storeBuf.toString();
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (saveUri != null) {
                        try {
                            writeAll(saveUri, text);
                            recv("", "save");
                            return;
                        } catch (Exception e) {
                            saveUri = null;
                        }
                    }
                    pendingText = text;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("application/octet-stream");
                    i.putExtra(Intent.EXTRA_TITLE, "train.jsonl");
                    try {
                        startActivityForResult(i, REQ_SAVE);
                    } catch (Exception e) {
                        recv("没有可用的文件保存器", "error");
                    }
                }
            });
        }
    }

    /* ==================== 文件 IO ==================== */

    private File storeFile() {
        return new File(getFilesDir(), "workspace.json");
    }

    private void ensureStore() {
        if (storeLoaded) return;
        synchronized (Bridge.class) {
            if (storeLoaded) return;
            String text = "";
            try {
                InputStream in = new FileInputStream(storeFile());
                text = readAll(in);
            } catch (Exception ignored) {
            }
            storeText = text == null ? "" : text;
            storeLoaded = true;
        }
    }

    private static String slice(String s, int start, int end) {
        if (s == null) return "";
        int n = s.length();
        if (start < 0) start = 0;
        if (end > n) end = n;
        if (start >= end) return "";
        return s.substring(start, end);
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) throw new Exception("无法打开文件");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void writeAll(Uri u, String text) throws Exception {
        OutputStream out = getContentResolver().openOutputStream(u, "wt");
        if (out == null) throw new Exception("无法写入文件");
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();
    }

    /* ==================== 回调页面 ==================== */

    private void recv(final String text, final String action) {
        js("window.JsGO_receive(" + JSONObject.quote(text == null ? "" : text) + ","
                + JSONObject.quote(action) + ")");
    }

    private void js(final String expr) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override
            public void run() {
                try {
                    web.evaluateJavascript(expr, null);
                } catch (Exception ignored) {
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri u = data.getData();
        if (u == null) return;

        if (requestCode == REQ_OPEN) {
            try {
                getContentResolver().takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            try {
                String text = readAll(getContentResolver().openInputStream(u));
                openText = text == null ? "" : text;
                recv("", "open");
            } catch (Exception e) {
                recv("读取失败：" + e.getMessage(), "error");
            }
        } else if (requestCode == REQ_SAVE) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            saveUri = u;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_SAVE_URI, u.toString()).apply();
            try {
                writeAll(u, pendingText);
                pendingText = "";
                recv("", "save");
            } catch (Exception e) {
                recv("写入失败：" + e.getMessage(), "error");
            }
        }
    }

    /**
     * Android 15 起 targetSdk>=35 默认启用预测性返回，导航键/手势返回不再走 onBackPressed，
     * 必须在 OnBackInvokedDispatcher 上注册回调。这里用反射注册，避免编译期依赖 API 33 的类。
     */
    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return;
        try {
            final Class<?> cbCls = Class.forName("android.window.OnBackInvokedCallback");
            Object cb = java.lang.reflect.Proxy.newProxyInstance(
                    cbCls.getClassLoader(),
                    new Class<?>[]{cbCls},
                    new java.lang.reflect.InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] args) {
                            String name = m.getName();
                            if ("onBackInvoked".equals(name)) {
                                handleBack();
                                return null;
                            }
                            if (m.getDeclaringClass() == Object.class || "toString".equals(name)) {
                                if ("toString".equals(name)) return "JsGOBack";
                                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                                if ("equals".equals(name)) return proxy == (args != null && args.length > 0 ? args[0] : null);
                            }
                            return null;
                        }
                    });
            Object dispatcher = Activity.class.getMethod("getOnBackInvokedDispatcher").invoke(this);
            if (dispatcher == null) return;
            java.lang.reflect.Method reg = Class.forName("android.window.OnBackInvokedDispatcher")
                    .getMethod("registerOnBackInvokedCallback", int.class, cbCls);
            reg.invoke(dispatcher, 0, cb);   // PRIORITY_DEFAULT = 0
            backCallback = cb;
        } catch (Throwable ignored) {
        }
    }

    private void unregisterBackCallback() {
        if (backCallback == null || Build.VERSION.SDK_INT < 33) return;
        try {
            Object dispatcher = Activity.class.getMethod("getOnBackInvokedDispatcher").invoke(this);
            if (dispatcher != null) {
                java.lang.reflect.Method unreg = Class.forName("android.window.OnBackInvokedDispatcher")
                        .getMethod("unregisterOnBackInvokedCallback",
                                Class.forName("android.window.OnBackInvokedCallback"));
                unreg.invoke(dispatcher, backCallback);
            }
        } catch (Throwable ignored) {
        }
        backCallback = null;
    }

    /** 先问页面要不要消费这次返回（关面板 / 回分类），页面不要才退出 */
    private void handleBack() {
        long now = System.currentTimeMillis();
        if (now - backAt < 350) return;
        backAt = now;
        if (web == null) {
            finish();
            return;
        }
        web.evaluateJavascript("(window.__back&&window.__back())?1:0", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"1".equals(value)) {
                    finish();
                }
            }
        });
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onDestroy() {
        unregisterBackCallback();
        if (web != null) {
            web.removeJavascriptInterface("JsGO");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** 分块大小提示，仅用于阅读代码时对齐页面逻辑 */
    @SuppressWarnings("unused")
    private static final int CHUNK = CHUNK_NOTE;
}
