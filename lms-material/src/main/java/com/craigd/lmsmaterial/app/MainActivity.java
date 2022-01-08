/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2022 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.HttpAuthHandler;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    public final static String TAG = "LMS";
    private static final String SETTINGS_URL = "mska://settings";
    private static final String QUIT_URL = "mska://quit";
    private static final String SB_PLAYER_PKG = "com.angrygoat.android.sbplayer";
    private static final String LMS_USERNAME_KEY = "lms-username";
    private static final String LMS_PASSWORD_KEY = "lms-password";
    private static final int PAGE_TIMEOUT = 5000;
    private static final int BAR_VISIBLE = 0;
    private static final int BAR_BLENDED = 1;
    private static final int BAR_HIDDEN = 2;

    private SharedPreferences sharedPreferences;
    private WebView webView;
    private String url;
    private boolean reloadUrlAfterSettings = false; // Should URL be reloaded after settings closed, regardless if changed?
    private boolean pageError = false;
    private boolean settingsShown = false;
    private int currentScale = 0;
    private ConnectionChangeListener connectionChangeListener;
    private double initialWebViewScale;
    private boolean haveDefaultColors = false;
    private int defaultStatusbar;
    private int defaultNavbar;
    private int statusbar = BAR_VISIBLE;
    private int navbar = BAR_HIDDEN;
    private boolean showOverLockscreen = false;
    private String addUrl; // URL to add to play queue...
    private JsonRpc rpc;
    private JSONArray downloadData = null;

    public static String activePlayer = null;
    public static String activePlayerName = null;
    private static boolean isCurrentActivity = false;
    private static Date pausedDate = null;

    /**
     * Check whether activty is active, or was in the last 5 seconds...
     * @return
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
    private ServiceConnection controlServiceConnection = new ServiceConnection() {
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
    private ServiceConnection downloadServiceConnection = new ServiceConnection() {
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
            if (servers.size()<1) {
                Log.d(TAG, "No server found, show settings");
                Toast.makeText(context, getResources().getString(R.string.no_servers), Toast.LENGTH_SHORT).show();
                navigateToSettingsActivity();
            } else {
                Log.d(TAG, "Discovered server");
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(SettingsActivity.SERVER_PREF_KEY, servers.get(0).encode());
                editor.apply();
                Toast.makeText(context, getResources().getString(R.string.server_discovered)+"\n\n"+servers.get(0).describe(), Toast.LENGTH_SHORT).show();

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

        public ConnectionChangeListener() {
            // Empty constructor to keep lint happy...
        }

        ConnectionChangeListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("android.net.conn.CONNECTIVITY_CHANGE") && null!=activity) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        activity.checkNetworkConnection();
                    }
                });
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
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String user = username.getText().toString();
                String pass = password.getText().toString();
                if (null!=user) {
                    user=user.trim();
                }
                if (null!=pass) {
                    pass=pass.trim();
                }
                if (null!=user && user.length()>0 && null!=pass && pass.length()>0) {
                    dialog.dismiss();
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putString(LMS_USERNAME_KEY, user);
                    editor.putString(LMS_PASSWORD_KEY, pass);
                    editor.commit();

                    httpAuthHandler.proceed(user, pass);
                }
            }
        });
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
                dialog.dismiss();
                reloadUrlAfterSettings=true;
                navigateToSettingsActivity();
            }
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
        Intent playerLaunchIntent = getApplicationContext().getPackageManager().getLaunchIntentForPackage(SB_PLAYER_PKG);
        Discovery.Server server = new Discovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY,null));
        String defaultPlayer = sharedPreferences.getString(SettingsActivity.DEFAULT_PLAYER_PREF_KEY, null);
        if (server.ip == null || server.ip.isEmpty()) {
            return null;
        }

        try {
            Uri.Builder builder = Uri.parse("http://" + server.ip + ":" + server.port + "/material/").buildUpon();
            if (statusbar==BAR_BLENDED || navbar==BAR_BLENDED) {
                builder.appendQueryParameter("nativeColors", "1");
            }
            if (defaultPlayer!=null && !defaultPlayer.isEmpty()) {
                builder.appendQueryParameter("player", defaultPlayer);
                if (sharedPreferences.getBoolean(SettingsActivity.SINGLE_PLAYER_PREF_KEY, false)) {
                    builder.appendQueryParameter("single", "1");
                }
            }
            builder.appendQueryParameter("nativePlayer", "1");
            return builder.build().toString()+
                    // Can't use Uri.Builder for the following as MaterialSkin expects that values to *not* be URL encoded!
                    "&hide=notif,scale" + (null == playerLaunchIntent ? ",launchPlayer" : "")+
                    "&appSettings="+SETTINGS_URL+
                    "&appQuit="+QUIT_URL +
                    "&download=native";
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
        if (!sharedPreferences.contains(SettingsActivity.STATUSBAR_PREF_KEY)) {
            editor.putString(SettingsActivity.STATUSBAR_PREF_KEY, "blend");
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.NAVBAR_PREF_KEY)) {
            editor.putString(SettingsActivity.NAVBAR_PREF_KEY, gestureNavigationEnabled() ? "blend" : "hidden");
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.KEEP_SCREEN_ON_PREF_KEY)) {
            editor.putBoolean(SettingsActivity.KEEP_SCREEN_ON_PREF_KEY, false);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.ORIENTATION_PREF_KEY)) {
            editor.putString(SettingsActivity.ORIENTATION_PREF_KEY, "auto");
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.ENABLE_WIFI_PREF_KEY)) {
            editor.putBoolean(SettingsActivity.ENABLE_WIFI_PREF_KEY, true);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.ON_CALL_PREF_KEY)) {
            editor.putString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateReceiver.DO_NOTHING);
            modified=true;
        }
        if (! PhoneStateReceiver.DO_NOTHING.equals(sharedPreferences.getString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateReceiver.DO_NOTHING)) &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            editor.putString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateReceiver.DO_NOTHING);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.ENABLE_NOTIF_PREF_KEY)) {
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
        if (modified) {
            editor.apply();
        }
    }

    private int getBarSetting(String key, int def) {
        try {
            String val = sharedPreferences.getString(key, null);
            if ("hidden".equals(val)) {
                return BAR_HIDDEN;
            }
            if ("blend".equals(val)) {
                return BAR_BLENDED;
            }
            if ("visible".equals(val)) {
                return BAR_VISIBLE;
            }
        } catch (Exception e) {
        }
        return def;
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
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (action == KeyEvent.ACTION_UP) {
                    if (webView.getVisibility()==View.VISIBLE) {
                        webView.evaluateJavascript("navigateBack()", null);
                    } else {
                        finishAffinity();
                        System.exit(0);
                    }
                }
                break;
            default:
                return super.dispatchKeyEvent(event);
        }
        return true;
    }

    private final Runnable pageLoadTimeout = new Runnable() {
        public void run() {
            Log.d(TAG, "Page failed to load");
            discoverServer();
        }
    };

    private final Handler pageLoadHandler = new Handler(Looper.myLooper());

    private void loadUrl(String u) {
        Log.d(TAG, "Load URL:"+url);
        pageLoadHandler.removeCallbacks(pageLoadTimeout);
        webView.loadUrl(u);
        pageLoadHandler.postDelayed(pageLoadTimeout, PAGE_TIMEOUT);
    }

    private void discoverServer() {
        Toast.makeText(getBaseContext(), getResources().getString(R.string.discovering_server), Toast.LENGTH_SHORT).show();
        Discovery discovery = new Discovery(getApplicationContext());
        discovery.discover();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        setDefaults();
        enableWifi();
        manageControlService();
        statusbar = getBarSetting(SettingsActivity.STATUSBAR_PREF_KEY, statusbar);
        navbar = getBarSetting(SettingsActivity.NAVBAR_PREF_KEY, navbar);
        setOrientation();
        Log.d(TAG, "sb:"+statusbar+", nb:"+navbar);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.hide();
        }
        if (sharedPreferences.getBoolean(SettingsActivity.KEEP_SCREEN_ON_PREF_KEY, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setFullscreen();
        setContentView(R.layout.activity_main);
        init5497Workaround();
        manageShowOverLockscreen();
        webView = findViewById(R.id.webview);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.addJavascriptInterface(this, "NativeReceiver");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else {
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }
        webView.setVerticalScrollBarEnabled(false);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(false);
        initialWebViewScale = getResources().getDisplayMetrics().density;
        currentScale = getScale();
        webView.setInitialScale(currentScale);

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
                super.onPageStarted(view, u, favicon);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Log.i(TAG, "onReceivedError:" + error.getErrorCode() + ", mf:" + request.isForMainFrame() + ", u:" + request.getUrl());
                } else {
                    Log.i(TAG, "onReceivedError, mf:" + request.isForMainFrame() + ", u:" + request.getUrl());
                }
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
                if (url.equals(QUIT_URL)) {
                    finishAffinity();
                    System.exit(0);
                    return true;
                }
                // Is this an intent:// URL - used by Material to launch SB Player
                if (url.startsWith("intent://")) {
                    try {
                        String[] fragment = Uri.parse(url).getFragment().split(";");
                        for (String s : fragment) {
                            if (s.startsWith("package=")) {
                                String pkg = s.substring(8);
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
                startActivity(intent);
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

    private void enableWifi() {
        if (sharedPreferences.getBoolean(SettingsActivity.ENABLE_WIFI_PREF_KEY, true)) {
            Log.d(TAG, "Enable WiFi");
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            wifi.setWifiEnabled(true);
        }
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
                discoverServer();
            } else {
                // Try to connect to previous server
                Log.i(TAG, "URL:" + url);
                Toast.makeText(getApplicationContext(),
                        new Discovery.Server(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString(SettingsActivity.SERVER_PREF_KEY, null)).describe(),
                        Toast.LENGTH_SHORT).show();

                loadUrl(url);
            }
        } else if (connectionChangeListener==null) {
            Log.d(TAG, "Not connected");
            // No network connection, show progress spinner until we are connected
            webView.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
            connectionChangeListener = new ConnectionChangeListener(this);
            registerReceiver(connectionChangeListener, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
            enableWifi();
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
        addUrlToPlayer();
    }

    @JavascriptInterface
    public void updateToolbarColors(final String topColor, String botColor) {
        if (statusbar != BAR_BLENDED && navbar != BAR_BLENDED) {
            Log.d(TAG, "Ignore color update, as not blending");
            return;
        }
        if (!haveDefaultColors) {
            defaultStatusbar = getWindow().getStatusBarColor();
            defaultNavbar = getWindow().getNavigationBarColor();
            haveDefaultColors = true;
        }
        Log.d(TAG, topColor+" "+botColor);
        if (null==topColor || topColor.length()<4 || null==botColor || botColor.length()<4) {
            return;
        }
        try {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        int flags = getWindow().getDecorView().getSystemUiVisibility();
                        // TODO: Need better way of detecting light toolbar!
                        boolean dark = !topColor.toLowerCase().equals("#f5f5f5");
                        getWindow().getDecorView().setSystemUiVisibility(dark ? (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
                    } catch (Exception e) {
                    }
                }
            });

            if (statusbar == BAR_BLENDED) {
                Log.d(TAG, "Blend statusbar");
                if (topColor.length() < 7) {
                    getWindow().setStatusBarColor(Color.parseColor("#" + topColor.charAt(1) + topColor.charAt(1) + topColor.charAt(2) + topColor.charAt(2) + topColor.charAt(3) + topColor.charAt(3)));
                } else {
                    getWindow().setStatusBarColor(Color.parseColor(topColor));
                }
            }
            if (navbar == BAR_BLENDED) {
                Log.d(TAG, "Blend navbar");
                if (botColor.length() < 7) {
                    getWindow().setNavigationBarColor(Color.parseColor("#" + botColor.charAt(1) + botColor.charAt(1) + botColor.charAt(2) + botColor.charAt(2) + botColor.charAt(3) + botColor.charAt(3)));
                } else {
                    getWindow().setNavigationBarColor(Color.parseColor(botColor));
                }
            }
        } catch (Exception e) {
        }
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
        switch (requestCode) {
            case 1:
                for (int result: grantResults) {
                    if (PackageManager.PERMISSION_GRANTED != result) {
                        return;
                    }
                }
                startDownload(downloadData);
                break;
        }
    }

    private void setFullscreen() {
        if (statusbar==BAR_HIDDEN) {
            if (navbar==BAR_HIDDEN) {
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
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
        } else if (navbar==BAR_HIDDEN) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
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
        isCurrentActivity = false;
        pausedDate = new Date();
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
            return;
        }
        settingsShown = false;
        int prevSbar = statusbar;
        int prevNavbar = navbar;
        statusbar = getBarSetting(SettingsActivity.STATUSBAR_PREF_KEY, statusbar);
        navbar = getBarSetting(SettingsActivity.NAVBAR_PREF_KEY, navbar);

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
            cacheCleared = true;
        }
        if (prevSbar!=statusbar || prevNavbar!=navbar) {
            setFullscreen();
            if (BAR_HIDDEN==prevSbar || BAR_HIDDEN==prevNavbar) {
                recreate();
                return;
            }
            if (BAR_BLENDED==statusbar) {
                needReload=true;
            } else if (BAR_BLENDED==prevSbar) {
                getWindow().setStatusBarColor(defaultStatusbar);
            }
            if (BAR_BLENDED==navbar) {
                needReload=true;
            } else if (BAR_BLENDED==prevNavbar) {
                getWindow().setNavigationBarColor(defaultNavbar);
            }
            if (BAR_BLENDED==prevSbar || BAR_BLENDED==prevNavbar) {
                getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
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
        manageControlService();
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
                new URL(intent.getStringExtra(Intent.EXTRA_TEXT));
                addUrl = intent.getStringExtra(Intent.EXTRA_TEXT);
                Log.d(TAG, "Received: "+addUrl);
                addUrlToPlayer();
            } catch (MalformedURLException e) {
                Log.d(TAG, "Malformed URL", e);
            }
        }
    }

    private void addUrlToPlayer() {
        if (null!=activePlayer && null!=addUrl) {
            if (null==rpc) {
                rpc = new JsonRpc(this);
            }
            Log.d(TAG, "Add:"+addUrl+" to:"+activePlayer);
            rpc.sendMessage(activePlayer, new String[]{"playlist", "add", addUrl});
            addUrl = null;
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

    @Override
    protected void onDestroy() {
        Log.i(TAG, "Destroy");
        webView.destroy();
        webView = null;
        stopControlService();
        super.onDestroy();
    }

    void manageControlService() {
        if (sharedPreferences.getBoolean(SettingsActivity.ENABLE_NOTIF_PREF_KEY, false)) {
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
        Boolean showOver = sharedPreferences.getBoolean(SettingsActivity.SHOW_OVER_LOCK_SCREEN_PREF_KEY, true);
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
        childOfContent.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
                possiblyResizeChildOfContent();
            }
        });
        frameLayoutParams = (FrameLayout.LayoutParams) childOfContent.getLayoutParams();
    }

    private void possiblyResizeChildOfContent() {
        if (BAR_HIDDEN !=statusbar) {
            return;
        }
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
}
