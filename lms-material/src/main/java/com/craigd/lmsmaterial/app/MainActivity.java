/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2023 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.muddz.styleabletoast.StyleableToast;

public class MainActivity extends AppCompatActivity {
    public final static String TAG = "LMS";
    private static final String SETTINGS_URL = "mska://settings";
    private static final String QUIT_URL = "mska://quit";
    private static final String STARTPLAYER_URL = "mska://startplayer";
    public static final String LMS_USERNAME_KEY = "lms-username";
    public static final String LMS_PASSWORD_KEY = "lms-password";
    private static final String CURRENT_PLAYER_ID_KEY = "current_player_id";
    private static final int PAGE_TIMEOUT = 5000;

    private SharedPreferences sharedPreferences;
    private WebView webView;
    private String url;
    private boolean reloadUrlAfterSettings = false; // Should URL be reloaded after settings closed, regardless if changed?
    private boolean pageError = false;
    private boolean settingsShown = false;
    private boolean isFullScreen = false;
    private int currentScale = 0;
    private ConnectionChangeListener connectionChangeListener;
    private double initialWebViewScale;
    private String onCall = null;
    private boolean showOverLockscreen = false;
    private UrlHandler urlHander;
    private JSONArray downloadData = null;
    private LocalPlayer localPlayer = null;
    private boolean isDark = true;
    private boolean pageLoaded = false;
    private long recreateTime = 0;

    public static String activePlayer = null;
    public static String activePlayerName = null;
    private static boolean isCurrentActivity = false;
    private static Date pausedDate = null;

    /**
     * @return true if activity is active
     */
    public static boolean isActive() {
        if (isCurrentActivity) {
            return true;
        }
        if (null!=pausedDate) {
            return (new Date().getTime() - pausedDate.getTime())<5000;
        }
        return false;
    }

    private Messenger controlServiceMessenger;
    private final ServiceConnection controlServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "onServiceConnected: "+className.getClassName());
            Log.d(TAG, "Setup control messenger");
            controlServiceMessenger = new Messenger(service);
            if (null != activePlayerName) {
                updateControlService(activePlayerName);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "onServiceDisconnected:" + className.getClassName());
            controlServiceMessenger = null;
        }
    };

    private Messenger downloadServiceMessenger = null;
    private BroadcastReceiver downloadStatusReceiver = null;
    private final ServiceConnection downloadServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "Setup download messenger");
            downloadServiceMessenger = new Messenger(service);
            downloadStatusReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.d(MainActivity.TAG, "Download status received: " + intent.getStringExtra(DownloadService.STATUS_BODY));
                    String msg  = intent.getStringExtra(DownloadService.STATUS_BODY).replace("\n", "")
                            .replace("\\\"", "\\\\\"")
                            .replace("\"", "\\\"");
                    webView.evaluateJavascript("downloadStatus(\"" + msg +"\")", null);

                    if (0==intent.getIntExtra(DownloadService.STATUS_LEN, -1)) {
                        try {
                            unbindService(downloadServiceConnection);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to unbind download service");
                        }
                        downloadServiceMessenger = null;
                    }
                }
            };

            registerReceiver(downloadStatusReceiver, new IntentFilter(DownloadService.STATUS));
            if (null != downloadData) {
                startDownload(downloadData);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "onServiceDisconnected:" + className.getClassName());
            downloadServiceMessenger = null;
            unregisterReceiver(downloadStatusReceiver);
            downloadStatusReceiver = null;
        }
    };

    private class Discovery extends ServerDiscovery {
        Discovery(Context context) {
            super(context, false);
        }

        public void discoveryFinished(List<Server> servers) {
            Log.d(TAG, "Discovery finished");
            if (servers.isEmpty()) {
                Log.d(TAG, "No server found, show settings");
                StyleableToast.makeText(context, getResources().getString(R.string.no_servers), Toast.LENGTH_SHORT, R.style.toast).show();
                navigateToSettingsActivity();
            } else {
                Log.d(TAG, "Discovered server");
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(SettingsActivity.SERVER_PREF_KEY, servers.get(0).encode());
                editor.apply();
                StyleableToast.makeText(context, getResources().getString(R.string.server_discovered)+"\n\n"+servers.get(0).describe(), Toast.LENGTH_SHORT, R.style.toast).show();

                url = getConfiguredUrl();
                Log.i(TAG, "URL:"+url);
                pageError = false;
                loadUrl(url);
            }
        }
    }

    // static class to keep lint happy...
    public static class ConnectionChangeListener extends BroadcastReceiver {
        private MainActivity activity;

        ConnectionChangeListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.net.conn.CONNECTIVITY_CHANGE".equals(intent.getAction()) && null!=activity) {
                activity.runOnUiThread(() -> activity.checkNetworkConnection());
            }
        }
    }

    public void promptForUserNameAndPassword(final HttpAuthHandler httpAuthHandler)  {
        View layout = getLayoutInflater().inflate(R.layout.auth_prompt, null);
        final EditText username = layout.findViewById(R.id.username);
        final EditText password = layout.findViewById(R.id.password);
        username.setText(sharedPreferences.getString(LMS_USERNAME_KEY, ""));
        password.setText(sharedPreferences.getString(LMS_PASSWORD_KEY, ""));
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            String user = username.getText().toString().trim();
            String pass = password.getText().toString().trim();
            if (!user.isEmpty() && !pass.isEmpty()) {
                dialog.dismiss();
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(LMS_USERNAME_KEY, user);
                editor.putString(LMS_PASSWORD_KEY, pass);
                editor.apply();

                httpAuthHandler.proceed(user, pass);
            }
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> {
            dialog.cancel();
            dialog.dismiss();
            reloadUrlAfterSettings=true;
            navigateToSettingsActivity();
        });
        AlertDialog dialog = builder.create();
        dialog.setView(layout);
        dialog.show();
    }

    private void navigateToSettingsActivity() {
        Log.d(TAG, "Navigate to settings");
        if (!SettingsActivity.isVisible()) {
            settingsShown = true;
            startActivity(new Intent(this, SettingsActivity.class));
        }
    }

    private String getConfiguredUrl() {
        Discovery.Server server = new Discovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY,null));
        String defaultPlayer = sharedPreferences.getString(SettingsActivity.DEFAULT_PLAYER_PREF_KEY, null);
        if (server.ip == null || server.ip.isEmpty()) {
            return null;
        }

        try {
            Uri.Builder builder = Uri.parse("http://" + server.ip + ":" + server.port + "/material/").buildUpon();
            if (defaultPlayer!=null && !defaultPlayer.isEmpty()) {
                builder.appendQueryParameter("player", defaultPlayer);
                if (sharedPreferences.getBoolean(SettingsActivity.SINGLE_PLAYER_PREF_KEY, false)) {
                    builder.appendQueryParameter("single", "1");
                }
            }
            builder.appendQueryParameter("nativePlayer", "1");
            builder.appendQueryParameter("nativeTheme", "1");
            if (sharedPreferences.getBoolean(SettingsActivity.PLAYER_START_MENU_ITEM_PREF_KEY, false)) {
                builder.appendQueryParameter("nativePlayerPower", "1");
            }
            if (Utils.notificationAllowed(this, DownloadService.NOTIFICATION_CHANNEL_ID)) {
                builder.appendQueryParameter("download", "native");
            }
            if (!sharedPreferences.getBoolean(SettingsActivity.FULLSCREEN_PREF_KEY, false) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                boolean gestureNav = usingGestureNavigation();
                builder.appendQueryParameter("topPad", "24");
                builder.appendQueryParameter("botPad", gestureNav ? "12" : "40");
                if (!gestureNav) {
                    builder.appendQueryParameter("dlgPad", "48");
                }
            }

            return builder.build().toString()+
                    // Can't use Uri.Builder for the following as MaterialSkin expects that values to *not* be URL encoded!
                    "&hide=notif,scale" +
                    "&appSettings="+SETTINGS_URL+
                    "&appQuit="+QUIT_URL+
                    (sharedPreferences.getBoolean(SettingsActivity.PLAYER_START_MENU_ITEM_PREF_KEY, false)
                        ? ("&appLaunchPlayer="+STARTPLAYER_URL) : "") +
                    "&dontEmbed=pdf";
        } catch (Exception e) {
            Log.e(TAG, "Failed to build URL", e);
        }
        return null;
    }

    private void setDefaults() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        boolean modified = false;
        if (!sharedPreferences.contains(SettingsActivity.SCALE_PREF_KEY)) {
            // Convert from previous...
            if (sharedPreferences.contains("scale")) {
                editor.putInt(SettingsActivity.SCALE_PREF_KEY, sharedPreferences.getInt("scale", 0) + 5);
                editor.remove("scale");
            } else {
                editor.putInt(SettingsActivity.SCALE_PREF_KEY, 5);
            }
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.AUTODISCOVER_PREF_KEY)) {
            editor.putBoolean(SettingsActivity.AUTODISCOVER_PREF_KEY, true);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.KEEP_SCREEN_ON_PREF_KEY)) {
            editor.putBoolean(SettingsActivity.KEEP_SCREEN_ON_PREF_KEY, false);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.FULLSCREEN_PREF_KEY)) {
            editor.putBoolean(SettingsActivity.FULLSCREEN_PREF_KEY, false);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.ORIENTATION_PREF_KEY)) {
            editor.putString(SettingsActivity.ORIENTATION_PREF_KEY, "auto");
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.ON_CALL_PREF_KEY)) {
            editor.putString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING);
            modified=true;
        }
        boolean haveNotifPerm = Utils.notificationAllowed(this, ControlService.NOTIFICATION_CHANNEL_ID);
        if (!sharedPreferences.contains(SettingsActivity.ON_CALL_PREF_KEY) ||
              (! PhoneStateHandler.DO_NOTHING.equals(sharedPreferences.getString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING)) &&
                (!haveNotifPerm ||
                  ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)) ) {
            editor.putString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.ENABLE_NOTIF_PREF_KEY) ||
              (sharedPreferences.getBoolean(SettingsActivity.ENABLE_NOTIF_PREF_KEY, false) && !haveNotifPerm)) {
            editor.putBoolean(SettingsActivity.ENABLE_NOTIF_PREF_KEY, false);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.SHOW_OVER_LOCK_SCREEN_PREF_KEY)) {
            editor.putBoolean(SettingsActivity.SHOW_OVER_LOCK_SCREEN_PREF_KEY, true);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.SINGLE_PLAYER_PREF_KEY)) {
            editor.putBoolean(SettingsActivity.SINGLE_PLAYER_PREF_KEY, false);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.PLAYER_APP_PREF_KEY) ||
             ( LocalPlayer.TERMUX_PLAYER.equals(sharedPreferences.getString(SettingsActivity.PLAYER_APP_PREF_KEY, LocalPlayer.NO_PLAYER)) &&
               ContextCompat.checkSelfPermission(this, SettingsActivity.TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) ) {
            editor.putString(SettingsActivity.PLAYER_APP_PREF_KEY, LocalPlayer.NO_PLAYER);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.AUTO_START_PLAYER_APP_PREF_KEY)) {
            editor.putBoolean(SettingsActivity.AUTO_START_PLAYER_APP_PREF_KEY, false);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.PLAYER_START_MENU_ITEM_PREF_KEY)) {
            editor.putBoolean(SettingsActivity.PLAYER_START_MENU_ITEM_PREF_KEY, false);
            modified=true;
        }
        if (modified) {
            editor.apply();
        }
        activePlayer = sharedPreferences.getString(CURRENT_PLAYER_ID_KEY, activePlayer);
        Log.d(TAG, "Startup player set to:"+activePlayer);
    }

    private Boolean clearCache() {
        Boolean clear = sharedPreferences.getBoolean(SettingsActivity.CLEAR_CACHE_PREF_KEY,false);
        if (clear) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(SettingsActivity.CLEAR_CACHE_PREF_KEY, false);
            editor.apply();
        }
        return clear;
    }

    private int getScale() {
        int pref = sharedPreferences.getInt(SettingsActivity.SCALE_PREF_KEY,0);
        if (5==pref) {
            return 0;
        }
        if (pref<5) {
            return (int)Math.round(initialWebViewScale *(100+(5*(pref-5))));
        }
        return (int)Math.round(initialWebViewScale *(100+(10*(pref-5))));
    }

    private boolean gestureNavigationEnabled() {
        try {
            Resources resources = getResources();
            int resourceId = resources.getIdentifier("config_navBarInteractionMode", "integer", "android");
            return resources.getInteger(resourceId) == 2; // 2 = Androdid Q gesture nav?
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (0==(event.getRepeatCount()%2)) {
                    webView.evaluateJavascript("incrementVolume()", null);
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (0==(event.getRepeatCount()%2)) {
                    webView.evaluateJavascript("decrementVolume()", null);
                }
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return true;
        }

        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_UP) {
                if (webView.getVisibility() == View.VISIBLE) {
                    webView.evaluateJavascript("navigateBack()", null);
                } else {
                    finishAffinity();
                    System.exit(0);
                }
            }
        } else {
            return super.dispatchKeyEvent(event);
        }
        return true;
    }

    private final Runnable pageLoadTimeout = () -> {
        Log.d(TAG, "Page failed to load");
        discoverServer(false);
    };

    private final Handler pageLoadHandler = new Handler(Looper.myLooper());

    private void loadUrl(String u) {
        Log.d(TAG, "Load URL:"+url);
        pageLoadHandler.removeCallbacks(pageLoadTimeout);
        webView.loadUrl(u);
        if (SystemClock.elapsedRealtime()-recreateTime>1000) {
            pageLoadHandler.postDelayed(pageLoadTimeout, PAGE_TIMEOUT);
        }
    }

    private void discoverServer(boolean force) {
        if (force || sharedPreferences.getBoolean(SettingsActivity.AUTODISCOVER_PREF_KEY, true)) {
            StyleableToast.makeText(getBaseContext(), getResources().getString(R.string.discovering_server), Toast.LENGTH_SHORT, R.style.toast).show();
            Discovery discovery = new Discovery(getApplicationContext());
            discovery.discover();
        } else {
            navigateToSettingsActivity();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "MainActivity.onConfigurationChanged");
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        webView.setLayoutParams(new RelativeLayout.LayoutParams(size.x, size.y));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "MainActivity.onCreate");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        } else {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.colorBackground));
            getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.colorBackground));
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        //isDark = sharedPreferences.getBoolean(SettingsActivity.IS_DARK_PREF_KEY, true);
        localPlayer = new LocalPlayer(sharedPreferences, this);
        setTheme();
        setDefaults();
        if (sharedPreferences.getBoolean(SettingsActivity.FULLSCREEN_PREF_KEY, false)) {
            setFullScreen(true, true);
        }
        manageControlService(false);
        onCall = sharedPreferences.getString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING);
        setOrientation();
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.hide();
        }
        if (sharedPreferences.getBoolean(SettingsActivity.KEEP_SCREEN_ON_PREF_KEY, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setContentView(R.layout.activity_main);
        init5497Workaround();
        manageShowOverLockscreen();
        webView = findViewById(R.id.webview);
        webView.setVisibility(View.GONE);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.addJavascriptInterface(this, "NativeReceiver");
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setVerticalScrollBarEnabled(false);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(false);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webSettings.setTextZoom(100);
        initialWebViewScale = getResources().getDisplayMetrics().density;
        currentScale = getScale();
        webView.setInitialScale(currentScale);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setVerticalScrollBarEnabled(false);

        webView.setWebViewClient(new WebViewClient() {
            private boolean firstAuthReq = true;
            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                Log.d(TAG, "onPageStarted:" + u);
                if (u.equals(url)) {
                    Log.d(TAG, u + " is loading");
                    pageLoadHandler.removeCallbacks(pageLoadTimeout);
                    firstAuthReq=true;
                }
                localPlayer.autoStart(false);
                pageLoaded = true;
                webView.setVisibility(View.VISIBLE);
                super.onPageStarted(view, u, favicon);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                Log.d(TAG, "onReceivedHttpError url:" + request.getUrl() + ", mf:" + request.isForMainFrame() + ", sc:" + errorResponse.getStatusCode());
                if (request.isForMainFrame() && 404== errorResponse.getStatusCode() && request.getUrl().toString().equals(getConfiguredUrl())) {
                    pageError = true;
                    //discoverServer(false);
                    pageLoadHandler.removeCallbacks(pageLoadTimeout);
                    navigateToSettingsActivity();
                }
                super.onReceivedHttpError(view, request, errorResponse);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.i(TAG, "onReceivedError:" + error.getErrorCode() + ", mf:" + request.isForMainFrame() + ", u:" + request.getUrl());
                } else {
                    Log.i(TAG, "onReceivedError, mf:" + request.isForMainFrame() + ", u:" + request.getUrl());
                }
                if (request.isForMainFrame()) {
                    webView.setVisibility(View.GONE);
                    pageError = true;
                    discoverServer(false);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Log.i(TAG, "shouldOverrideUrlLoading:" + url);

                if (url.equals(SETTINGS_URL)) {
                    navigateToSettingsActivity();
                    return true;
                }
                if (url.equals(QUIT_URL)) {
                    stopControlService();
                    finishAffinity();
                    localPlayer.autoStop();
                    System.exit(0);
                    return true;
                }
                if (url.equals(STARTPLAYER_URL)) {
                    localPlayer.start();
                    return true;
                }

                Uri uri=Uri.parse(url);

                // Is URL for LMS server? If so we handle this
                /*
                NO - don't handle URLs, not required?
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
                String server = new Discovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY, "")).ip;
                if (server.equals(uri.getHost()) && uri.getPath().startsWith("/material") && !uri.getPath().contains("/docs/")) {
                    return false;
                }
                */

                // Nope, so launch an intent to handle the URL...
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                String path = uri.getPath();
                if (null!=path && path.toLowerCase().endsWith(".pdf")) {
                    intent.setDataAndType(uri, "application/pdf");
                }
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    StyleableToast.makeText(getApplicationContext(),
                            getApplicationContext().getResources().getString(R.string.no_termux_run_perms),
                            Toast.LENGTH_SHORT, R.style.toast).show();
                }
                return true;
            }

            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler httpAuthHandler, String host, String realm) {
                Log.i(TAG, "onReceivedHttpAuthRequest");
                pageLoadHandler.removeCallbacks(pageLoadTimeout);
                // If this is the first time we have been asked for auth on this page, then use and settings stored in preferences.
                if (firstAuthReq) {
                    firstAuthReq = false;
                    String user = sharedPreferences.getString(LMS_USERNAME_KEY, null);
                    String pass = sharedPreferences.getString(LMS_PASSWORD_KEY, null);
                    if (user!=null && pass!=null) {
                        Log.i(TAG, "Try prev auth detail");
                        httpAuthHandler.proceed(user, pass);
                        return;
                    }
                }
                Log.i(TAG, "Prompt for auth details");
                promptForUserNameAndPassword(httpAuthHandler);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
        });

        checkNetworkConnection();
        handleIntent(getIntent());
    }

    private void checkNetworkConnection() {
        Log.d(TAG, "Check network connection");
        View progress = findViewById(R.id.progress);
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = connectivityManager.getActiveNetworkInfo();
        if (null!=info && info.isConnected()) {
            Log.d(TAG, "Connected");
            webView.setVisibility(View.VISIBLE);

            if (connectionChangeListener != null) {
                unregisterReceiver(connectionChangeListener);
            }
            progress.setVisibility(View.GONE);

            url = getConfiguredUrl();
            if (url == null) {
                // No previous server, so try to find
                discoverServer(true);
            } else {
                // Try to connect to previous server
                Log.i(TAG, "URL:" + url);
                StyleableToast.makeText(getApplicationContext(),
                        new Discovery.Server(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString(SettingsActivity.SERVER_PREF_KEY, null)).describe(),
                        Toast.LENGTH_SHORT, R.style.toast).show();

                loadUrl(url);
            }
        } else if (connectionChangeListener==null) {
            Log.d(TAG, "Not connected");
            // No network connection, show progress spinner until we are connected
            webView.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
            connectionChangeListener = new ConnectionChangeListener(this);
            registerReceiver(connectionChangeListener, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
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

    @JavascriptInterface
    public void updatePlayer(String playerId, String playerName) {
        Log.d(TAG, "Active player: "+playerId+", name: "+playerName);
        activePlayer = playerId;
        activePlayerName = playerName;
        updateControlService(playerName);
    }

    public static String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException ex) {
        }
        return null;
    }

    @JavascriptInterface
    public int controlLocalPlayerPower(String playerId, String ipAddress, int state) {
        String[] parts = ipAddress.split(":");
        Log.d(TAG, "Player Power, ID: "+playerId+", IP:"+parts[0]+", State: "+state);
        if (0==state && parts[0].compareTo(getLocalIpAddress())==0) {
            localPlayer.stopPlayer(playerId);
            return 1;
        }
        return 0;
    }

    @JavascriptInterface
    public void updateTheme(final String theme) {
        Log.d(TAG, theme);
        if (null==theme || theme.length()<4) {
            return;
        }
        try {
            runOnUiThread(() -> {
                try {
                    int flags = getWindow().getDecorView().getSystemUiVisibility();
                    boolean dark = !theme.contains("light") || theme.contains("-colored");
                    if (Build.VERSION.SDK_INT >= 23) {
                        getWindow().getDecorView().setSystemUiVisibility(dark ? (flags & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)) : (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
                    }
                } catch (Exception e) {
                }
            });
        } catch (Exception e) {
        }
    }

    /*
    @JavascriptInterface
    public void updateTheme(String theme) {
        Log.d(TAG, "updateTheme: " + theme);
        String ltheme = theme.toLowerCase(Locale.ROOT);
        boolean dark = ltheme.contains("dark") || ltheme.contains("black");
        if (dark!=isDark) {
            isDark = dark;
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(SettingsActivity.IS_DARK_PREF_KEY, isDark);
            editor.apply();
            setTheme();
        }
    }
    */

    private void setTheme() {
        setTheme(isDark ? R.style.AppTheme : R.style.AppTheme_Light);
    }

    @JavascriptInterface
    public void cancelDownload(String str) {
        Log.d(TAG, "cancelDownload: " + str);

        try {
            if (downloadServiceMessenger!=null) {
                Message msg = Message.obtain(null, DownloadService.CANCEL_LIST, new JSONArray(str));
                try {
                    downloadServiceMessenger.send(msg);
                } catch (RemoteException e) {
                    Log.d(TAG, "Failed to request download cancel");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "failed to decode cancelDownload", e);
        }
    }

    @JavascriptInterface
    public void download(String str) {
        Log.d(TAG, "download: " + str);

        try {
            doDownload(new JSONArray(str));
        } catch (Exception e) {
            Log.e(TAG, "failed to decode download", e);
        }
    }

    private void doDownload(JSONArray data) {
        if (Build.VERSION.SDK_INT >=Build.VERSION_CODES.M &&
                ( checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ) ) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            downloadData = data;
        } else {
            startDownload(data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == 1) {
            for (int result : grantResults) {
                if (PackageManager.PERMISSION_GRANTED != result) {
                    return;
                }
            }
            startDownload(downloadData);
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onPause() {
        Log.i(TAG, "Pause");
        webView.onPause();
        webView.pauseTimers();
        super.onPause();
        isCurrentActivity = false;
        pausedDate = new Date();
    }

    private static boolean deleteDir(File path, Set<String> ignore) {
        if (null!=path) {
            if (path.isDirectory()) {
                String[] plist = path.list();
                if (plist!=null) {
                    for (String entry : plist) {
                        if ((null == ignore || !ignore.contains(entry)) && !deleteDir(new File(path, entry), ignore)) {
                            return false;
                        }
                    }
                }
                Log.i(TAG, "Delete dir:" + path.getAbsolutePath());
                return path.delete();
            } else if (path.isFile()) {
                Log.i(TAG, "Delete file:" + path.getAbsolutePath());
                return path.delete();
            }
        }
        return false;
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "Resume");
        webView.onResume();
        webView.resumeTimers();
        super.onResume();
        isCurrentActivity = true;

        if (!settingsShown) {
            updateDownloadStatus();
            if (pageLoaded) {
                localPlayer.autoStart(true);
            }
            if (sharedPreferences.getBoolean(SettingsActivity.ENABLE_NOTIF_PREF_KEY, false) && Utils.notificationAllowed(this, ControlService.NOTIFICATION_CHANNEL_ID)) {
                if (!ControlService.isActive()) {
                    startControlService();
                } else {
                    refreshControlService();
                }
            }
            return;
        }
        settingsShown = false;
        String u = getConfiguredUrl();
        boolean cacheCleared = false;
        boolean needReload = false;
        int scale = getScale();
        if (scale!=currentScale) {
            currentScale = scale;
            webView.setInitialScale(scale);
            needReload = true;
        }
        if (clearCache()) {
            Log.i(TAG,"Clear cache");
            webView.clearCache(true);
            try {
                Set<String> ignore = new HashSet<>();
                ignore.add("lib");
                ignore.add("shared_prefs");
                deleteDir(this.getCacheDir(), ignore);
            } catch (Exception e) { }
            cacheCleared = true;
        }
        if (isFullScreen != sharedPreferences.getBoolean(SettingsActivity.FULLSCREEN_PREF_KEY, isFullScreen)) {
            setFullScreen(!isFullScreen, false);
            needReload = true;
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
        } else if (pageError || cacheCleared || needReload || reloadUrlAfterSettings) {
            Log.i(TAG, "Reload URL");
            pageError = false;
            webView.reload();
        }

        if (sharedPreferences.getBoolean(SettingsActivity.KEEP_SCREEN_ON_PREF_KEY, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setOrientation();
        manageControlService(sharedPreferences.getString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING).equals(this.onCall));
        manageShowOverLockscreen();
        reloadUrlAfterSettings=false;
        updateDownloadStatus();
    }

    private void updateDownloadStatus() {
        if (downloadServiceMessenger!=null) {
            Message msg = Message.obtain(null, DownloadService.STATUS_REQ);
            try {
                downloadServiceMessenger.send(msg);
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to request download update");
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Log.d(TAG, "onNewIntent: "+intent.getAction());
        handleIntent(intent);
        super.onNewIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent!=null && "android.intent.action.SEND".equals(intent.getAction())) {
            try {
                String url = intent.getStringExtra(Intent.EXTRA_TEXT);
                Log.d(TAG, "Received: "+url);
                if (url!=null && !url.startsWith("http") && (url.contains("http://") || url.contains("https://"))) {
                    String[] parts = url.split("\\s");
                    for (String part: parts) {
                        part = part.trim();
                        if (part.startsWith("http://") || part.startsWith("https://")) {
                            url=part;
                            Log.d(TAG, "Converted: "+url);
                            break;
                        }
                    }
                }
                new URL(url);
                if (null!=url && !url.isEmpty()) {
                    if (null==urlHander) {
                        urlHander = new UrlHandler(this);
                    }
                    urlHander.handle(url);
                }
            } catch (MalformedURLException e) {
                Log.d(TAG, "Malformed URL", e);
            }
        }
    }

    private void setOrientation() {
        String o = sharedPreferences.getString(SettingsActivity.ORIENTATION_PREF_KEY, null);
        if ("landscape".equals(o)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else if ("portrait".equals(o)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
        }
    }

    public void setPlayer(String id) {
        webView.evaluateJavascript("setCurrentPlayer(\"" + id +"\")", null);
    }

    @Override
    protected void onDestroy() {
        Log.i(TAG, "Destroy");
        webView.destroy();
        webView = null;
        stopControlService();
        if (null!=activePlayer && !activePlayer.equals(sharedPreferences.getString(CURRENT_PLAYER_ID_KEY, null))) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(CURRENT_PLAYER_ID_KEY, activePlayer);
            editor.apply();
        }
        super.onDestroy();
    }

    void manageControlService(boolean onCallChanged) {
        Log.d(TAG, "MainActivity.manageControlService onCallChanged:"+onCallChanged);
        boolean showNotif = sharedPreferences.getBoolean(SettingsActivity.ENABLE_NOTIF_PREF_KEY, false);
        if (showNotif && !Utils.notificationAllowed(this, ControlService.NOTIFICATION_CHANNEL_ID)) {
            showNotif = false;
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putBoolean(SettingsActivity.ENABLE_NOTIF_PREF_KEY, false);
            editor.putString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING);
            editor.apply();
        }
        if (showNotif) {
            if (onCallChanged) {
                stopControlService();
            }
            startControlService();
        } else {
            stopControlService();
        }
    }

    void startControlService() {
        if (controlServiceMessenger!=null) {
            return;
        }
        Log.d(TAG, "Start control service");
        Intent intent = new Intent(MainActivity.this, ControlService.class);
        bindService(intent, controlServiceConnection, Context.BIND_AUTO_CREATE);
    }

    void stopControlService() {
        if (controlServiceMessenger!=null) {
            Log.d(TAG, "Stop control service");
            try {
                unbindService(controlServiceConnection);
            } catch (Exception e) {
                Log.e(TAG, "Failed to unbind control service");
            }
            stopService(new Intent(MainActivity.this, ControlService.class));
            controlServiceMessenger = null;
        }
    }

    private void updateControlService(String playerName) {
        if (controlServiceMessenger!=null) {
            Message msg = Message.obtain(null, ControlService.PLAYER_NAME, playerName);
            try {
                controlServiceMessenger.send(msg);
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to update service");
            }
        }
    }

    private void refreshControlService() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (controlServiceMessenger!=null) {
            Message msg = Message.obtain(null, ControlService.PLAYER_REFRESH);
            try {
                controlServiceMessenger.send(msg);
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to refresh service");
            }
        }
    }

    void startDownload(JSONArray data) {
        if (downloadServiceMessenger!= null) {
            Log.d(TAG, "Send track list to download service");
            Message msg = Message.obtain(null, DownloadService.DOWNLOAD_LIST, data);
            try {
                downloadServiceMessenger.send(msg);
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to send data to download service");
            }
            downloadData = null;
        } else {
            downloadData = data;
            Log.d(TAG, "Start download service");
            Intent intent = new Intent(MainActivity.this, DownloadService.class);
            bindService(intent, downloadServiceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    private Boolean lockScreenInit = false;
    private void manageShowOverLockscreen() {
        boolean showOver = sharedPreferences.getBoolean(SettingsActivity.SHOW_OVER_LOCK_SCREEN_PREF_KEY, true);
        if (showOver==showOverLockscreen) {
            return;
        }
        showOverLockscreen = showOver;
        // Allow to show above the lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(showOverLockscreen);
            setTurnScreenOn(showOverLockscreen);

            if (showOverLockscreen && !lockScreenInit) {
                KeyguardManager keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                if (keyguardManager != null) {
                    keyguardManager.requestDismissKeyguard(this, null);
                }
            }
        } else if (showOverLockscreen) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
    }

    /*
    Work-around for android bug 5497 - https://issuetracker.google.com/issues/36911528
     */
    private View childOfContent;
    private int usableHeightPrevious;
    private FrameLayout.LayoutParams frameLayoutParams;

    private void init5497Workaround() {
        FrameLayout content = findViewById(android.R.id.content);
        childOfContent = content.getChildAt(0);
        childOfContent.getViewTreeObserver().addOnGlobalLayoutListener(this::possiblyResizeChildOfContent);
        frameLayoutParams = (FrameLayout.LayoutParams) childOfContent.getLayoutParams();
    }

    private void possiblyResizeChildOfContent() {
        int usableHeightNow = computeUsableHeight();
        if (usableHeightNow != usableHeightPrevious) {
            int usableHeightSansKeyboard = childOfContent.getRootView().getHeight();
            int heightDifference = usableHeightSansKeyboard - usableHeightNow;
            if (heightDifference > (usableHeightSansKeyboard/4)) {
                // keyboard probably just became visible
                frameLayoutParams.height = usableHeightSansKeyboard - heightDifference;
            } else {
                // keyboard probably just became hidden
                frameLayoutParams.height = usableHeightSansKeyboard;
            }
            childOfContent.requestLayout();
            usableHeightPrevious = usableHeightNow;
        }
    }

    private int computeUsableHeight() {
        Rect r = new Rect();
        childOfContent.getWindowVisibleDisplayFrame(r);
        return (r.bottom - r.top);
    }

    public boolean usingGestureNavigation() {
        Resources resources = getResources();
        int resourceId = resources.getIdentifier("config_navBarInteractionMode", "integer", "android");
        if (resourceId > 0) {
            return 2==resources.getInteger(resourceId);
        }
        return false;
    }

    private void setFullScreen(boolean on, boolean isStartup) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            }
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            }
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            }
            if (!isStartup) {
                recreate();
                recreateTime = SystemClock.elapsedRealtime();
            }
        }
        isFullScreen = on;
    }
}
