package com.solcesto.game;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private static final String TAG = "C3Main";
    private WebView webView;
    private LocalHttpServer httpServer;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        // Allow remote debugging via chrome://inspect for troubleshooting.
        WebView.setWebContentsDebuggingEnabled(true);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.i("C3Console", cm.message() + " @" + cm.lineNumber() + " [" + cm.sourceId() + "]");
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                Log.e("C3Web", "error " + error.getDescription() + " url=" + request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i("C3Web", "page finished: " + url);
            }
        });
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // Bridge for JS: "exit game" on the title screen must fully close the
        // process (window.close() is a no-op in Android WebView).
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void exitApp() {
                runOnUiThread(() -> {
                    Log.i(TAG, "exitApp: closing app process");
                    finishAffinity();
                    android.os.Process.killProcess(android.os.Process.myPid());
                });
            }
        }, "AndroidBridge");

        setContentView(webView);

        // Serve the game over loopback HTTP: file:// blocks ES modules (CORS),
        // which is what made the screen stay black.
        httpServer = new LocalHttpServer(getAssets());
        try {
            int port = httpServer.start();
            webView.loadUrl("http://127.0.0.1:" + port + "/index.html");
            Log.i(TAG, "loading http://127.0.0.1:" + port + "/index.html");
        } catch (Exception e) {
            Log.e(TAG, "http server failed, fallback to file://: " + e.getMessage());
            webView.loadUrl("file:///android_asset/www/index.html");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        if (httpServer != null) httpServer.stop();
        super.onDestroy();
    }
}
