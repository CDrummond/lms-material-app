/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
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
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
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
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.github.muddz.styleabletoast.StyleableToast;

public class MainActivity extends AppCompatActivity {
    private static final String SETTINGS_URL = "mska://settings";
    private static final String QUIT_URL = "mska://quit";
    private static final String STARTPLAYER_URL = "mska://startplayer";
    public static final String LMS_USERNAME_KEY = "lms-username";
    public static final String LMS_PASSWORD_KEY = "lms-password";
    private static final String CURRENT_PLAYER_ID_KEY = "current_player_id";
    private static final int PAGE_TIMEOUT = 5000; // ms
    private static final int DISCONNECT_TIMEOUT = 6; // seconds
    private static final int DISCONNECT_TIMEOUT_WITHOUT_NETWORK = 10; // seconds
    private static final int STORAGE_ACCESS_REQUEST_CODE = 123;

    private SharedPreferences sharedPreferences;
    private WebView webView;
    private WebViewCache webViewCache = null;
    private String url;
    private boolean reloadUrlAfterSettings = false; // Should URL be reloaded after settings closed, regardless if changed?
    private boolean pageError = false;
    private boolean settingsShown = false;
    private boolean isFullScreen = false;
    private int currentScale = 0;
    private ConnectionChangeListener connectionChangeListener;
    private double initialWebViewScale;
    private String onCall = null;
    private String notifications = null;
    private boolean showOverLockscreen = false;
    private UrlHandler urlHander;
    private JSONArray downloadData = null;
    public static boolean isDark = true;
    private boolean pageLoaded = false;
    private long recreateTime = 0;
    private LocalPlayer localPlayer;
    private boolean interceptVolume = true;

    public static String activePlayer = null;
    public static String activePlayerName = null;
    private static boolean isCurrentActivity = false;

    /**
     * @return true if activity is active
     */
    public static boolean isActive() {
        return isCurrentActivity;
    }

    private Messenger controlServiceMessenger;
    private final ServiceConnection controlServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Utils.debug("Setup control messenger");
            controlServiceMessenger = new Messenger(service);
            if (null != activePlayer && null != activePlayerName) {
                sendMessageToService(ControlService.ACTIVE_PLAYER, new String[]{activePlayer, activePlayerName});
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Utils.debug("onServiceDisconnected:" + className.getClassName());
            controlServiceMessenger = null;
        }
    };

    private Messenger downloadServiceMessenger = null;
    private BroadcastReceiver downloadStatusReceiver = null;
    private final ServiceConnection downloadServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Utils.debug("Setup download messenger");
            downloadServiceMessenger = new Messenger(service);
            downloadStatusReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Utils.debug("Download status received: " + intent.getStringExtra(DownloadService.STATUS_BODY));
                    String msg  = intent.getStringExtra(DownloadService.STATUS_BODY);
                    if (null==msg) {
                        return;
                    }
                    msg = msg.replace("\n", "")
                            .replace("\\\"", "\\\\\"")
                            .replace("\"", "\\\"");
                    webView.evaluateJavascript("downloadStatus(\"" + msg +"\")", null);

                    if (0==intent.getIntExtra(DownloadService.STATUS_LEN, -1)) {
                        try {
                            unbindService(downloadServiceConnection);
                        } catch (Exception e) {
                            Utils.error("Failed to unbind download service");
                        }
                        downloadServiceMessenger = null;
                    }
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(downloadStatusReceiver, new IntentFilter(DownloadService.STATUS), RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(downloadStatusReceiver, new IntentFilter(DownloadService.STATUS));
            }
            if (null != downloadData) {
                startDownload(downloadData);
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Utils.debug("onServiceDisconnected:" + className.getClassName());
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
            Utils.debug("Discovery finished");
            if (servers.isEmpty()) {
                Utils.debug("No server found, show settings");
                StyleableToast.makeText(context, getResources().getString(R.string.no_servers), Toast.LENGTH_SHORT, R.style.toast).show();
                navigateToSettingsActivity();
            } else {
                Utils.debug("Discovered server");
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(SettingsActivity.SERVER_PREF_KEY, servers.get(0).encode());
                editor.apply();
                String prevUrl = url;
                url = getConfiguredUrl();
                if (!url.equals(prevUrl)) {
                    StyleableToast.makeText(context, getResources().getString(R.string.server_discovered) + "\n\n" + servers.get(0).describe(), Toast.LENGTH_SHORT, R.style.toast).show();
                    Utils.info("URL:" + url);
                    pageError = false;
                    loadUrl(url);
                }
            }
        }
    }

    // static class to keep lint happy...
    public static class ConnectionChangeListener extends BroadcastReceiver {
        private final MainActivity activity;

        ConnectionChangeListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction()) && null!=activity) {
                activity.runOnUiThread(activity::checkNetworkConnection);
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
        Utils.debug("Navigate to settings");
        if (!SettingsActivity.isVisible()) {
            stopDisconnectTimer();
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
            builder.appendQueryParameter("nativeTextColor", "1");
            builder.appendQueryParameter("nativeConnectionStatus", "1");
            builder.appendQueryParameter("dontTrapBack", "1");
            if (sharedPreferences.getBoolean(SettingsActivity.PLAYER_START_MENU_ITEM_PREF_KEY, false)) {
                builder.appendQueryParameter("nativePlayerPower", "1");
            }
            if (Utils.notificationAllowed(this, DownloadService.NOTIFICATION_CHANNEL_ID)) {
                builder.appendQueryParameter("download", "native");
            }
            if (!sharedPreferences.getBoolean(SettingsActivity.FULLSCREEN_PREF_KEY, false) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int scale = sharedPreferences.getInt(SettingsActivity.SCALE_PREF_KEY,0);
                double adjust = 1.0;
                if (scale<5) {
                    adjust += (5-scale)/10.0;
                }
                Utils.cutoutTopLeft(this);
                builder.appendQueryParameter("topPad", ""+(int) Math.ceil(Utils.getTopPadding(this)*adjust));
                builder.appendQueryParameter("botPad", ""+(int)Math.ceil(Utils.getBottomPadding(this)*adjust));
                if (!Utils.usingGestureNavigation(this)) {
                    builder.appendQueryParameter("dlgPad", ""+(int)Math.ceil(48*adjust));
                }
            }
            boolean addStartPlayer = sharedPreferences.getBoolean(SettingsActivity.PLAYER_START_MENU_ITEM_PREF_KEY, false);
            if (addStartPlayer) {
                builder.appendQueryParameter("ipAddresses", String.join(",", getLocalIpAddresses()));
            }
            return builder.build().toString()+
                    // Can't use Uri.Builder for the following as MaterialSkin expects that values to *not* be URL encoded!
                    "&hide=notif,scale" +
                    "&appSettings="+SETTINGS_URL+
                    (sharedPreferences.getBoolean(SettingsActivity.QUITMENU_PREF_KEY, false)
                        ? ("&appQuit="+QUIT_URL) : "") +
                    (addStartPlayer
                        ? ("&appLaunchPlayer="+STARTPLAYER_URL) : "") +
                    "&dontEmbed=pdf";
        } catch (Exception e) {
            Utils.error("Failed to build URL", e);
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
        if (!sharedPreferences.contains(SettingsActivity.FULLSCREEN_PREF_KEY) ||
            (sharedPreferences.getBoolean(SettingsActivity.FULLSCREEN_PREF_KEY, false) && Utils.cutoutTopLeft(this))) {
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
        if (! PhoneStateHandler.DO_NOTHING.equals(sharedPreferences.getString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING)) &&
                (!haveNotifPerm ||
                  ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED)) {
            editor.putString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.AFTER_CALL_PREF_KEY)) {
            editor.putBoolean(SettingsActivity.AFTER_CALL_PREF_KEY, true);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.NOTIFCATIONS_PREF_KEY) ||
              (!haveNotifPerm && !ControlService.NO_NOTIFICATION.equals(sharedPreferences.getString(SettingsActivity.NOTIFCATIONS_PREF_KEY, ControlService.NO_NOTIFICATION)))) {
            editor.putString(SettingsActivity.NOTIFCATIONS_PREF_KEY, ControlService.NO_NOTIFICATION);
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
                "termux".equals(sharedPreferences.getString(SettingsActivity.PLAYER_APP_PREF_KEY, LocalPlayer.NO_PLAYER))) {
            editor.putString(SettingsActivity.PLAYER_APP_PREF_KEY, LocalPlayer.NO_PLAYER);
            modified=true;
        }
        if (!sharedPreferences.contains(SettingsActivity.HARDWARE_VOLUME_PREF_KEY)) {
            editor.putBoolean(SettingsActivity.HARDWARE_VOLUME_PREF_KEY, true);
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
        Utils.debug("Startup player set to:"+activePlayer);
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (interceptVolume) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                    if (0 == (event.getRepeatCount() % 2)) {
                        webView.evaluateJavascript("incrementVolume()", null);
                    }
                    return true;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    if (0 == (event.getRepeatCount() % 2)) {
                        webView.evaluateJavascript("decrementVolume()", null);
                    }
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        if (interceptVolume) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    return true;
            }
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
        Utils.debug("Page failed to load");
        discoverServer(false);
    };

    private final Handler pageLoadHandler = new Handler(Looper.getMainLooper());

    private void loadUrl(String u) {
        Utils.debug("Load URL:"+url);
        pageLoadHandler.removeCallbacks(pageLoadTimeout);
        webView.loadUrl(u);
        if (SystemClock.elapsedRealtime()-recreateTime>1000) {
            pageLoadHandler.postDelayed(pageLoadTimeout, PAGE_TIMEOUT);
        }
    }

    private void discoverServer(boolean force) {
        discoverServer(force, true, true);
    }

    private void discoverServer(boolean force, boolean canOpenSettings, boolean showToast) {
        Utils.debug("force:"+force);
        if (force || sharedPreferences.getBoolean(SettingsActivity.AUTODISCOVER_PREF_KEY, true)) {
            Utils.debug("Start discovery");
            if (showToast) {
                runOnUiThread(() -> {
                    StyleableToast.makeText(getBaseContext(), getResources().getString(R.string.discovering_server), Toast.LENGTH_SHORT, R.style.toast).show();
                });
            }
            Discovery discovery = new Discovery(getApplicationContext());
            discovery.discover();
        } else if (canOpenSettings) {
            navigateToSettingsActivity();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Utils.debug("MainActivity.onConfigurationChanged");
        Point size = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(size);
        webView.setLayoutParams(new RelativeLayout.LayoutParams(size.x, size.y));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Utils.debug("MainActivity.onCreate");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        } else {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.colorBackground));
            getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.colorBackground));
        }

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        localPlayer = new LocalPlayer(sharedPreferences, this);
        setTheme();
        setDefaults();
        if (sharedPreferences.getBoolean(SettingsActivity.FULLSCREEN_PREF_KEY, false)) {
            setFullScreen(true, true);
        }
        manageControlService(false);
        onCall = sharedPreferences.getString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING);
        notifications = sharedPreferences.getString(SettingsActivity.NOTIFCATIONS_PREF_KEY, ControlService.NO_NOTIFICATION);
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
        webViewCache = new WebViewCache(this);
        webView = findViewById(R.id.webview);
        webView.setVisibility(View.GONE);
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.addJavascriptInterface(this, "NativeReceiver");
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setVerticalScrollBarEnabled(false);
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            webSettings.setAlgorithmicDarkeningAllowed(false);
        }
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
                Utils.debug(u);
                if (u.equals(url)) {
                    pageLoadHandler.removeCallbacks(pageLoadTimeout);
                    firstAuthReq=true;
                }
                localPlayer.autoStart(false);
                pageLoaded = true;
                webView.setVisibility(View.VISIBLE);
                sendMessageToService(ControlService.CHECK_COMET_CONNECTION, null);
                super.onPageStarted(view, u, favicon);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                Utils.debug(request.getUrl() + ", mf:" + request.isForMainFrame() + ", sc:" + errorResponse.getStatusCode());
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
                Utils.info(error.getErrorCode() + ", mf:" + request.isForMainFrame() + ", u:" + request.getUrl());
                if (request.isForMainFrame()) {
                    webView.setVisibility(View.GONE);
                    pageError = true;
                    discoverServer(false);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Utils.info(url);

                switch (url) {
                    case SETTINGS_URL:
                        navigateToSettingsActivity();
                        return true;
                    case QUIT_URL:
                        stopControlService();
                        finishAffinity();
                        localPlayer.autoStop();
                        System.exit(0);
                        return true;
                    case STARTPLAYER_URL:
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
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    PackageManager pm = getPackageManager();
                    List<ResolveInfo> activities = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
                    if (activities.isEmpty()) {
                        StyleableToast.makeText(getApplicationContext(),
                                getApplicationContext().getResources().getString(R.string.no_pdf_handler),
                                Toast.LENGTH_SHORT, R.style.toast).show();
                        return true;
                    }
                }
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Utils.error("Can't open PDF", e);
                    StyleableToast.makeText(getApplicationContext(),
                            getApplicationContext().getResources().getString(R.string.failed_start_activity),
                            Toast.LENGTH_SHORT, R.style.toast).show();
                }
                return true;
            }

            public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler httpAuthHandler, String host, String realm) {
                Utils.info("");
                pageLoadHandler.removeCallbacks(pageLoadTimeout);
                // If this is the first time we have been asked for auth on this page, then use and settings stored in preferences.
                if (firstAuthReq) {
                    firstAuthReq = false;
                    String user = sharedPreferences.getString(LMS_USERNAME_KEY, null);
                    String pass = sharedPreferences.getString(LMS_PASSWORD_KEY, null);
                    if (user!=null && pass!=null) {
                        Utils.info("Try prev auth detail");
                        httpAuthHandler.proceed(user, pass);
                        return;
                    }
                }
                Utils.info("Prompt for auth details");
                promptForUserNameAndPassword(httpAuthHandler);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // Check if we should cache this path
                if (webViewCache.shouldIntercept(request)) {
                    WebResourceResponse resourceResponse = webViewCache.get(request);
                    if (resourceResponse != null) {
                        return resourceResponse;
                    }
                }

                return super.shouldInterceptRequest(view, request);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
        });

        checkNetworkConnection();
        handleIntent(getIntent());
    }

    private void checkNetworkConnection() {
        Utils.debug("Check network connection");
        View progress = findViewById(R.id.progress);
        if (Utils.isNetworkConnected(this)) {
            Utils.debug("Connected");
            webView.setVisibility(View.VISIBLE);

            if (connectionChangeListener != null) {
                unregisterReceiver(connectionChangeListener);
                connectionChangeListener = null;
            }
            progress.setVisibility(View.GONE);

            url = getConfiguredUrl();
            if (url == null) {
                // No previous server, so try to find
                discoverServer(true);
            } else {
                // Try to connect to previous server
                Utils.info("URL:" + url);
                StyleableToast.makeText(getApplicationContext(),
                        new Discovery.Server(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString(SettingsActivity.SERVER_PREF_KEY, null)).describe(),
                        Toast.LENGTH_SHORT, R.style.toast).show();

                loadUrl(url);
            }
        } else if (connectionChangeListener==null) {
            Utils.debug("Not connected");
            // No network connection, show progress spinner until we are connected
            webView.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
            connectionChangeListener = new ConnectionChangeListener(this);
            registerReceiver(connectionChangeListener, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        }
    }

    /*
    @JavascriptInterface
    public void updateStatus(String status) {
        Utils.debug(status);

        try {
            JSONObject json = new JSONObject(status);
        } catch (Exception e) {
        }
    }
    */

    @JavascriptInterface
    public void updatePlayer(String playerId, String playerName, String ip) {
        Utils.debug("Active player: "+playerId+", name: "+playerName+", ip:" + ip);
        activePlayer = playerId;
        activePlayerName = playerName;

        interceptVolume = !sharedPreferences.getBoolean(SettingsActivity.HARDWARE_VOLUME_PREF_KEY, true) || !isLocalPlayer(ip);
        Utils.debug("interceptVolume:"+interceptVolume);
        sendMessageToService(ControlService.ACTIVE_PLAYER, new String[]{activePlayer, activePlayerName});
    }

    public static Set<String> getLocalIpAddresses() {
        Set<String> addresses = new TreeSet<>();
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    String addr = enumIpAddr.nextElement().getHostAddress();
                    if (null!=addr && !"127.0.0.1".equals(addr) && addr.split("\\.").length==4) {
                        addresses.add(addr);
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return addresses;
    }

    private boolean isLocalPlayer(String ipAddress) {
        return getLocalIpAddresses().contains(ipAddress.split(":")[0]);
    }

    @JavascriptInterface
    public int controlLocalPlayerPower(String playerId, String ipAddress, int state) {
        Utils.debug("Player Power, ID: "+playerId+", IP:"+ipAddress+", State: "+state);
        if (0==state && isLocalPlayer(ipAddress)) {
            localPlayer.stopPlayer(playerId);
            return 1;
        }
        return 0;
    }

    private String lastTextColor = null;
    @JavascriptInterface
    public void updateTextColor(final String color) {
        Utils.debug(color);
        if (null == color || color.length() < 4 || color.equals(lastTextColor)) {
            return;
        }
        lastTextColor = color;
        int r, g, b;
        try {
            if (color.length() < 5) {
                r = Integer.parseInt(""+color.charAt(1), 16);
                g = Integer.parseInt(""+color.charAt(2), 16);
                b = Integer.parseInt(""+color.charAt(3), 16);
            } else {
                r = Integer.parseInt(color.substring(1, 3), 16) / 8;
                g = Integer.parseInt(color.substring(3, 5), 16) / 8;
                b = Integer.parseInt(color.substring(5, 7), 16) / 8;
            }
            setAndroidColors(((r+g+b)/3)>4);
        } catch (Exception ignored) {  }
    }

    @JavascriptInterface
    public void updateTheme(final String theme) {
        if (null==theme || theme.length()<4) {
            return;
        }
        Utils.debug(theme);
        boolean darkTheme = !theme.contains("light");
        if (darkTheme!=isDark) {
            isDark = darkTheme;
            //setTheme();
        }
        setAndroidColors(isDark || theme.contains("-colored"));
    }

    private void setAndroidColors(boolean dark) {
        try {
            runOnUiThread(() -> {
                try {
                    int flags = getWindow().getDecorView().getSystemUiVisibility();
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getWindow().getDecorView().setSystemUiVisibility(dark ? (flags & ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)) : (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
                    } else {
                        getWindow().getDecorView().setSystemUiVisibility(dark ? (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /*
    @JavascriptInterface
    public void updateTheme(String theme) {
        Utils.debug("updateTheme: " + theme);
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
        setTheme(/*isDark ?*/ R.style.AppTheme /*: R.style.AppTheme_Light*/);
    }

    @JavascriptInterface
    public void updateConnectionStatus(boolean connected) {
        Utils.debug(""+connected);
        if (connected) {
            stopDisconnectTimer();
            if (ControlService.isActive()) {
                refreshControlService();
            }
        } else {
            startDisconnectTimer();
        }
    }

    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    ScheduledFuture<?> disconnectHandler;
    private void startDisconnectTimer() {
        Utils.debug("");
        stopDisconnectTimer();
        disconnectHandler = executorService.schedule(() -> {
            Utils.debug("Disconnected delay timer invoked");
            discoverServer(false, false, false);
        }, Utils.isNetworkConnected(this) ? DISCONNECT_TIMEOUT : DISCONNECT_TIMEOUT_WITHOUT_NETWORK, TimeUnit.SECONDS);
    }

    private void stopDisconnectTimer() {
        Utils.debug("");
        if (null!=disconnectHandler) {
            disconnectHandler = null;
        }
    }
    @JavascriptInterface
    public void cancelDownload(String str) {
        Utils.debug(str);

        try {
            if (downloadServiceMessenger!=null) {
                Message msg = Message.obtain(null, DownloadService.CANCEL_LIST, new JSONArray(str));
                try {
                    downloadServiceMessenger.send(msg);
                } catch (RemoteException e) {
                    Utils.error("Failed to request download cancel");
                }
            }
        } catch (Exception e) {
            Utils.error("failed to decode cancelDownload", e);
        }
    }

    @JavascriptInterface
    public void download(String str) {
        Utils.debug(str);

        try {
            doDownload(new JSONArray(str));
        } catch (Exception e) {
            Utils.error("failed to decode download", e);
        }
    }

    private void doDownload(JSONArray data) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Environment.isExternalStorageManager()) {
            Utils.debug("Request manage permission");
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + BuildConfig.APPLICATION_ID));
            startActivityForResult(intent, STORAGE_ACCESS_REQUEST_CODE);
            downloadData = data;
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ( checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                  checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ) ) {
            Utils.debug("Request r/w permissions");
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            downloadData = data;
        } else {
            startDownload(data);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // If MANAGE_EXTERNAL_STORAGE permission is rejected then we can still download, just not cover-art
        startDownload(downloadData);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == 1) {
            for (int result : grantResults) {
                if (PackageManager.PERMISSION_GRANTED != result) {
                    Utils.debug("Read/write external storage permission not granted");
                    return;
                }
            }
            startDownload(downloadData);
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onPause() {
        Utils.info("");
        webView.onPause();
        webView.pauseTimers();
        super.onPause();
        isCurrentActivity = false;
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
                Utils.info("Delete dir:" + path.getAbsolutePath());
                return path.delete();
            } else if (path.isFile()) {
                Utils.info("Delete file:" + path.getAbsolutePath());
                return path.delete();
            }
        }
        return false;
    }

    @Override
    protected void onResume() {
        Utils.info("");
        webView.onResume();
        webView.resumeTimers();
        super.onResume();
        isCurrentActivity = true;

        if (!settingsShown) {
            updateDownloadStatus();
            if (pageLoaded) {
                localPlayer.autoStart(true);
            }
            if (!ControlService.NO_NOTIFICATION.equals(sharedPreferences.getString(SettingsActivity.NOTIFCATIONS_PREF_KEY, ControlService.NO_NOTIFICATION)) && Utils.notificationAllowed(this, ControlService.NOTIFICATION_CHANNEL_ID)) {
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
            Utils.info("Clear cache");
            webView.clearCache(true);
            try {
                Set<String> ignore = new HashSet<>();
                ignore.add("lib");
                ignore.add("shared_prefs");
                deleteDir(this.getCacheDir(), ignore);
            } catch (Exception ignored) { }
            cacheCleared = true;
        }
        if (isFullScreen != sharedPreferences.getBoolean(SettingsActivity.FULLSCREEN_PREF_KEY, isFullScreen)) {
            setFullScreen(!isFullScreen, false);
            needReload = true;
        }
        Utils.info("URL:"+u);
        if (u==null) {
            Utils.info("Start settings");
            navigateToSettingsActivity();
        } else if (!u.equals(url)) {
            Utils.info("Load new URL");
            pageError = false;
            url = u;
            loadUrl(u);
        } else if (pageError || cacheCleared || needReload || reloadUrlAfterSettings) {
            Utils.info("Reload URL");
            pageError = false;
            webView.reload();
        }

        if (sharedPreferences.getBoolean(SettingsActivity.KEEP_SCREEN_ON_PREF_KEY, false)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setOrientation();
        String onCallNow = sharedPreferences.getString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING);
        String notificationsNow = sharedPreferences.getString(SettingsActivity.NOTIFCATIONS_PREF_KEY, ControlService.NO_NOTIFICATION);
        manageControlService(!onCallNow.equals(onCall) || !notificationsNow.equals(notifications));
        onCall = onCallNow;
        notifications = notificationsNow;
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
                Utils.error("Failed to request download update");
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Utils.debug(intent.getAction());
        handleIntent(intent);
        super.onNewIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent!=null && "android.intent.action.SEND".equals(intent.getAction())) {
            try {
                String url = intent.getStringExtra(Intent.EXTRA_TEXT);
                Utils.debug("Received: "+url);
                if (url!=null && !url.startsWith("http") && (url.contains("http://") || url.contains("https://"))) {
                    String[] parts = url.split("\\s");
                    for (String part: parts) {
                        part = part.trim();
                        if (part.startsWith("http://") || part.startsWith("https://")) {
                            url=part;
                            Utils.debug("Converted: "+url);
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
                Utils.error("Malformed URL", e);
            }
        }
    }

    @SuppressLint("SourceLockedOrientationActivity")
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
        Utils.info("");
        webView.destroy();
        webView = null;
        webViewCache.stop();
        stopControlService();
        if (null!=activePlayer && !activePlayer.equals(sharedPreferences.getString(CURRENT_PLAYER_ID_KEY, null))) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(CURRENT_PLAYER_ID_KEY, activePlayer);
            editor.apply();
        }
        super.onDestroy();
    }

    void manageControlService(boolean needRestart) {
        Utils.debug("needRestart:"+needRestart);
        boolean showNotif = !ControlService.NO_NOTIFICATION.equals(sharedPreferences.getString(SettingsActivity.NOTIFCATIONS_PREF_KEY, ControlService.NO_NOTIFICATION));
        if (showNotif && !Utils.notificationAllowed(this, ControlService.NOTIFICATION_CHANNEL_ID)) {
            showNotif = false;
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(SettingsActivity.NOTIFCATIONS_PREF_KEY, ControlService.NO_NOTIFICATION);
            editor.putString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING);
            editor.apply();
        }
        if (showNotif) {
            if (needRestart) {
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
        Utils.debug("Start control service");
        Intent intent = new Intent(MainActivity.this, ControlService.class);
        bindService(intent, controlServiceConnection, Context.BIND_AUTO_CREATE);
    }

    void stopControlService() {
        if (controlServiceMessenger!=null) {
            Utils.debug("Stop control service");
            try {
                unbindService(controlServiceConnection);
            } catch (Exception e) {
                Utils.error("Failed to unbind control service");
            }
            stopService(new Intent(MainActivity.this, ControlService.class));
            controlServiceMessenger = null;
        }
    }

    private void sendMessageToService(int msgId, Object params) {
        if (controlServiceMessenger!=null) {
            Message msg = Message.obtain(null, msgId, params);
            try {
                controlServiceMessenger.send(msg);
            } catch (RemoteException e) {
                Utils.error("Failed to send id:" + msgId + " to service");
            }
        }
    }

    private void refreshControlService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (controlServiceMessenger!=null) {
            Message msg = Message.obtain(null, ControlService.PLAYER_REFRESH);
            try {
                controlServiceMessenger.send(msg);
            } catch (RemoteException e) {
                Utils.error("Failed to refresh service");
            }
        }
    }

    void startDownload(JSONArray data) {
        if (downloadServiceMessenger!= null) {
            Utils.debug("Send track list to download service");
            Message msg = Message.obtain(null, DownloadService.DOWNLOAD_LIST, data);
            try {
                downloadServiceMessenger.send(msg);
            } catch (RemoteException e) {
                Utils.error("Failed to send data to download service");
            }
            downloadData = null;
        } else {
            downloadData = data;
            Utils.debug("Start download service");
            Intent intent = new Intent(MainActivity.this, DownloadService.class);
            bindService(intent, downloadServiceConnection, Context.BIND_AUTO_CREATE);
        }
    }

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

            if (showOverLockscreen) {
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
        if (!isFullScreen) {
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

    private void setFullScreen(boolean on, boolean isStartup) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getWindow().getAttributes().layoutInDisplayCutoutMode =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
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
