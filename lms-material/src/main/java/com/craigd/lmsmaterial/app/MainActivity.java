package com.craigd.lmsmaterial.app;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private final String TAG = "LMS";
    private final String SETTINGS_URL = "mska://settings";
    private final String SB_PLAYER_PKG = "com.angrygoat.android.sbplayer";
    private final int PAGE_TIMEOUT = 5000;

    private WebView webView;
    private String url;
    private boolean pageError = false;
    private boolean settingsShown = false;

    private class Discovery extends ServerDiscovery {
        public Discovery(Context context) {
            super(context, false);
        }

        public void discoveryFinished(List<Server> servers) {
            Log.d(TAG, "Discovery finished");
            if (servers.size()<1) {
                Log.d(TAG, "No server found, show settings");
                Toast.makeText(context, getResources().getString(R.string.no_servers), Toast.LENGTH_SHORT).show();
                navigateToSettingsActivity();
            } else {
                Log.d(TAG, "Discovered server");
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(SettingsActivity.SERVER_PREF_KEY, servers.get(0).encode());
                editor.commit();
                Toast.makeText(context, getResources().getString(R.string.server_discovered)+"\n\n"+servers.get(0).describe(), Toast.LENGTH_SHORT).show();

                url = getConfiguredUrl();
                Log.i(TAG, "URL:"+url);
                pageError = false;
                loadUrl(url);
            }
        }
    }

    private void navigateToSettingsActivity() {
        Log.d(TAG, "Navigate to settings");
        if (!SettingsActivity.isVisible()) {
            settingsShown = true;
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    private String getConfiguredUrl() {
        Intent playerLaunchIntent = getApplicationContext().getPackageManager().getLaunchIntentForPackage(SB_PLAYER_PKG);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String server = new Discovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY,null)).ip;
        //return server==null || server.isEmpty() ? null : "http://"+server+":9000/material/?native&hide=notif";
        return server==null || server.isEmpty() ? null : "http://"+server+":9000/material/?hide=notif" + (null==playerLaunchIntent ? ",launchPlayer" : "") + "&appSettings="+SETTINGS_URL;
    }

    private Boolean clearCache() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        Boolean clear = sharedPreferences.getBoolean(SettingsActivity.CLEAR_CACHE_PREF_KEY,false);
        if (clear) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(SettingsActivity.CLEAR_CACHE_PREF_KEY, false);
            editor.commit();
        }
        return clear;
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

    private Runnable pageLoadTimeout = new Runnable() {
        public void run() {
            Log.d(TAG, "Page failed to load");
            navigateToSettingsActivity();
        }
    };

    Handler handler = new Handler(Looper.myLooper());

    private void loadUrl(String u) {
        Log.d(TAG, "Load URL:"+url);
        handler.removeCallbacks(pageLoadTimeout);
        webView.loadUrl(u);
        handler.postDelayed(pageLoadTimeout, PAGE_TIMEOUT);
    }

    private void discoverServer() {
        Toast.makeText(getBaseContext(), getResources().getString(R.string.discovering_server), Toast.LENGTH_SHORT).show();
        Discovery discovery = new Discovery(getApplicationContext());
        discovery.discover();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.hide();
        }
        setFullscreen();
        setContentView(R.layout.activity_main);
        // Allow to show above the lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        webView = findViewById(R.id.webview);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        //webView.addJavascriptInterface(this, "NativeReceiver");

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAppCacheEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                Log.d("MSK", "onPageStarted:" + u);
                if (u.equals(url)) {
                    Log.d(TAG, u + " is loading");
                    handler.removeCallbacks(pageLoadTimeout);
                }
                super.onPageStarted(view, u, favicon);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                Log.i(TAG, "onReceivedError:" + error.getErrorCode() + ", mf:" + request.isForMainFrame() + ", u:" + request.getUrl());
                if (request.isForMainFrame()) {
                    pageError = true;
                    discoverServer();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.i(TAG, "shouldOverrideUrlLoading:" + url);

                if (url.equals(SETTINGS_URL)) {
                    navigateToSettingsActivity();
                    return true;
                }
                // Is this an intent:// URL - used by Material to launch SB Player
                if (url.startsWith("intent://")) {
                    try {
                        String[] fragment = Uri.parse(url).getFragment().split(";");
                        for (int i = 0; i < fragment.length; ++i) {
                            if (fragment[i].startsWith("package=")) {
                                String pkg = fragment[i].substring(8);
                                Intent intent = getApplicationContext().getPackageManager().getLaunchIntentForPackage(pkg);
                                if (intent != null) {
                                    startActivity(intent);
                                }
                            }
                        }
                    } catch (Exception e) {
                    }
                    return true;
                }

                // Is URL for LMS server? If so we handle this
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                String server = new Discovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY, "")).ip;

                if (server.equals(Uri.parse(url).getHost())) {
                    return false;
                }

                // Nope, so launch an intent to handle the URL...
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            }

        });
        webView.setWebChromeClient(new WebChromeClient() {
        });

        url = getConfiguredUrl();
        if (url == null) {
            discoverServer();
        } else {
            Log.i(TAG, "URL:" + url);
            Toast.makeText(getApplicationContext(),
                    new Discovery.Server(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString(SettingsActivity.SERVER_PREF_KEY,null)).describe(),
                    Toast.LENGTH_SHORT).show();

            loadUrl(url);
        }
    }

    /*
    @JavascriptInterface
    public void updateStatus(String status) {
        Log.d(TAG, status);

        try {
            JSONObject json = new JSONObject(status);
        } catch (Exception e) {
        }
    }
     */

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
        webView.onPause();
        webView.pauseTimers();
        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "Resume");
        webView.onResume();
        webView.resumeTimers();
        super.onResume();

        if (!settingsShown) {
            return;
        }
        String u = getConfiguredUrl();
        boolean cacheCleared = false;
        if (clearCache()) {
            Log.i(TAG,"Clear cache");
            webView.clearCache(true);
            cacheCleared = true;
        }
        Log.i(TAG, "onResume, URL:"+u);
        if (u==null) {
            Log.i(TAG,"Start settings");
            navigateToSettingsActivity();
        } else if (!u.equals(url)) {
            Log.i(TAG, "Load new URL");
            pageError = false;
            url = u;
            loadUrl(u);
        } else if (pageError || cacheCleared) {
            Log.i(TAG, "Reload URL");
            pageError = false;
            webView.reload();
        }
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "Destroy");
        webView.destroy();
        webView = null;
        super.onDestroy();
    }
}