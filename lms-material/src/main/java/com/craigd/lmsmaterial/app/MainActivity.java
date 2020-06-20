package com.craigd.lmsmaterial.app;

import android.Manifest;
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
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    public final static String TAG = "LMS";
    private final String SETTINGS_URL = "mska://settings";
    private final String QUIT_URL = "mska://quit";
    private final String SB_PLAYER_PKG = "com.angrygoat.android.sbplayer";
    private final int PAGE_TIMEOUT = 5000;
    private final int BAR_VISIBLE = 0;
    private final int BAR_BLENDED = 1;
    private final int BAR_HIDDEN = 2;

    private SharedPreferences sharedPreferences;
    private WebView webView;
    private String url;
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

    public static String activePlayer = null;
    public static String activePlayerName = null;

    private boolean foregroundServiceBound = false;
    private Messenger foregroundServiceMessenger;
    private ServiceConnection foregroundServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            foregroundServiceMessenger = new Messenger(service);
            if (null!=activePlayerName) {
                updateService(activePlayerName);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            foregroundServiceMessenger = null;
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
        String onCall = sharedPreferences.getString(SettingsActivity.ON_CALL_PREF_KEY,PhoneStateReceiver.DO_NOTHING);
        boolean notif = sharedPreferences.getBoolean(SettingsActivity.ENABLE_NOTIF_PREF_KEY, true);
        return server.ip == null || server.ip.isEmpty()
                ? null
                : "http://" + server.ip + ":" + server.port + "/material/?hide=notif,scale" +
                  (null == playerLaunchIntent ? ",launchPlayer" : "") +
                  (statusbar==BAR_BLENDED || navbar==BAR_BLENDED ? "&nativeColors" : "") +
                  (notif || PhoneStateReceiver.MUTE_ACTIVE.equals(onCall) || PhoneStateReceiver.PAUSE_ACTIVE.equals(onCall) ? "&nativePlayer" : "") +
                  "&appSettings=" + SETTINGS_URL +
                  "&appQuit=" + QUIT_URL;
    }

    private void setDefaults() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        boolean modified = false;
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
            editor.putBoolean(SettingsActivity.ENABLE_NOTIF_PREF_KEY, true);
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
        return 0==pref ? 0 : (int)Math.round(initialWebViewScale *(100+(10*pref)));
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

    private final Handler handler = new Handler(Looper.myLooper());

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

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        setDefaults();
        enableWifi();
        controlService();
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
        webView.addJavascriptInterface(this, "NativeReceiver");

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAppCacheEnabled(true);
        webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setUseWideViewPort(false);
        initialWebViewScale = getResources().getDisplayMetrics().density;
        currentScale = getScale();
        webView.setInitialScale(currentScale);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                Log.d(TAG, "onPageStarted:" + u);
                if (u.equals(url)) {
                    Log.d(TAG, u + " is loading");
                    handler.removeCallbacks(pageLoadTimeout);
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

        checkNetworkConnection();
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
        updateService(playerName);
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
        //webView.onPause();
        webView.pauseTimers();
        super.onPause();
    }

    @Override
    protected void onResume() {
        Log.i(TAG, "Resume");
        //webView.onResume();
        webView.resumeTimers();
        super.onResume();

        if (!settingsShown) {
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
        } else if (pageError || cacheCleared || needReload) {
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
        controlService();
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
        unbindService();
        super.onDestroy();
    }

    void controlService() {
        if (sharedPreferences.getBoolean(SettingsActivity.ENABLE_WIFI_PREF_KEY, true)) {
            startService();
        } else {
            stopService();
        }
    }

    void startService() {
        if (foregroundServiceBound) {
            return;
        }

        Intent intent = new Intent(MainActivity.this, ForegroundService.class);
        intent.setAction(ForegroundService.START);
        //startService(intent);
        bindService(intent, foregroundServiceConnection, Context.BIND_AUTO_CREATE);
        foregroundServiceBound = true;
    }

    void stopService() {
        Intent intent = new Intent(MainActivity.this, ForegroundService.class);
        stopService(intent);
    }

    void unbindService() {
        if (foregroundServiceBound) {
            unbindService(foregroundServiceConnection);
            foregroundServiceBound = false;
        }
    }

    private void updateService(String playerName) {
        if (foregroundServiceBound && foregroundServiceMessenger!=null) {
            Message msg = Message.obtain(null, ForegroundService.PLAYER_NAME, playerName);
            try {
                foregroundServiceMessenger.send(msg);
            } catch (RemoteException e) {
                Log.d(TAG, "Failed to update service");
            }
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
