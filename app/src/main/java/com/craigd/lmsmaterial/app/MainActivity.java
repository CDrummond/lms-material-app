package com.craigd.lmsmaterial.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

public class MainActivity extends AppCompatActivity {
    private final String TAG = "LMS";

    private WebView webView;
    private String url;
    private boolean pageError = false;

    private void navigateToSettingsActivity() {
        if (!SettingsActivity.isVisible()) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    private String getConfiguredUrl() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String server = sharedPreferences.getString("server_address",null);
        return server==null || server.isEmpty() ? null : "http://"+server+":9000/material/?native&hide=notif";
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (action == KeyEvent.ACTION_UP) {
                    webView.evaluateJavascript("incrementVolume()", null);
                }
                break;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (action == KeyEvent.ACTION_UP) {
                    webView.evaluateJavascript("decrementVolume()", null);
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                break;
            default:
                return super.dispatchKeyEvent(event);
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        ActionBar ab = getSupportActionBar();
        if (ab!=null) {
            ab.hide();
        }
        setFullscreen();
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);
        webView.setBackgroundColor(Color.TRANSPARENT);

        // Enable Javascript
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // if there is any error opening the web page, navigate to settings
                // screen because site could not be loaded
                Log.i(TAG, "onReceivedError:"+error.getErrorCode()+", mf:"+request.isForMainFrame()+", u:"+request.getUrl());
                if (request.isForMainFrame()) {
                    pageError = true;
                    navigateToSettingsActivity();
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
        });

        url = getConfiguredUrl();
        if (url==null) {
            navigateToSettingsActivity();
        }
        Log.i(TAG, "URL:"+url);
        webView.loadUrl(url);
        webView.addJavascriptInterface(this, "NativeReceiver");
        // TODO: Add clear cache button
        webView.clearCache(true);
    }

    @JavascriptInterface
    public void updateStatus(String status){
        Log.d(TAG, status);
    }

    private void setFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            setFullscreen();
        }
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "Pause");
        //webView.onPause();
        //webView.pauseTimers();
        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "Resume");
        //webView.onResume();
        //webView.resumeTimers();
        String u = getConfiguredUrl();
        Log.i(TAG, "onResume, URL:"+u);
        if (u==null) {
            Log.i(TAG,"Start settings");
            navigateToSettingsActivity();
        } else if (!u.equals(url)) {
            Log.i(TAG, "Load new URL");
            pageError = false;
            webView.loadUrl(u);
        } else if (pageError) {
            Log.i(TAG, "Reload URL");
            pageError = false;
            webView.reload();
        }
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "Destroy");
        webView.destroy();
        webView = null;
        super.onDestroy();
    }
}
