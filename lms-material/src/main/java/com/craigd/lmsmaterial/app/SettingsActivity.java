/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2023 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreferenceCompat;

import java.util.List;

import io.github.muddz.styleabletoast.StyleableToast;

public class SettingsActivity extends AppCompatActivity {
    public static final String SERVER_PREF_KEY = "server";
    public static final String AUTODISCOVER_PREF_KEY = "autodiscover";
    public static final String CLEAR_CACHE_PREF_KEY = "clear_cache";
    public static final String SCALE_PREF_KEY = "zoomscale";
    public static final String FULLSCREEN_PREF_KEY = "fullscreen";
    public static final String KEEP_SCREEN_ON_PREF_KEY = "keep_screen_on";
    public static final String ORIENTATION_PREF_KEY = "orientation";
    public static final String ON_CALL_PREF_KEY = "on_call";
    public static final String NOTIFCATIONS_PREF_KEY = "notifs";
    public static final String SHOW_OVER_LOCK_SCREEN_PREF_KEY ="show_over_lock_screen";
    public static final String DEFAULT_PLAYER_PREF_KEY ="default_player";
    public static final String SINGLE_PLAYER_PREF_KEY ="single_player";
    public static final String PLAYER_APP_PREF_KEY = "player_app";
    public static final String STOP_APP_PREF_KEY = "stop_app";
    public static final String AUTO_START_PLAYER_APP_PREF_KEY = "auto_start_player";
    public static final String PLAYER_START_MENU_ITEM_PREF_KEY = "menu_start_player";
    public static final String STOP_APP_ON_QUIT_PREF_KEY = "stop_app_on_quit";
    public static final String SQUEEZELITE_OPTIONS_KEY = "squeezelite_options";

    public static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    public static final int PERMISSION_READ_PHONE_STATE = 1;
    public static final int PERMISSION_RUN_TERMUX_COMMAND = 2;
    public static final int PERMISSION_POST_NOTIFICATIONS = 3;
    public static final int PERMISSION_NOTIFS_AND_READ_PHONE_STATE = 4;

    private static boolean visible = false;
    public static boolean isVisible() {
        return visible;
    }

    private SettingsFragment fragment;
    private SettingsActivity activity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(MainActivity.isDark ? R.style.AppTheme : R.style.AppTheme_Light);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, MainActivity.isDark ? R.color.colorBackground : R.color.colorBackgroundLight));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, MainActivity.isDark ? R.color.colorBackground : R.color.colorBackgroundLight));
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        getWindow().getDecorView().setSystemUiVisibility(MainActivity.isDark ? (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR));

        visible = true;
        setContentView(R.layout.settings_activity);
        activity = this;
        fragment = new SettingsFragment();
        fragment.setActivity(this);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, fragment)
                .commit();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setBackgroundDrawable(new ColorDrawable(ContextCompat.getColor(this, MainActivity.isDark ? R.color.colorBackground : R.color.colorBackgroundLight)));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.right_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            visible = false;
            return true;
        } else if (item.getItemId() == R.id.action_quit) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
            if (sharedPreferences.getBoolean(STOP_APP_ON_QUIT_PREF_KEY, false)) {
                LocalPlayer localPlayer = new LocalPlayer(sharedPreferences, getApplicationContext());
                localPlayer.stop();
            }
            stopService(new Intent(this, ControlService.class));
            finishAffinity();
            System.exit(0);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        visible = false;
        super.onBackPressed();
    }

    @Override
    public void onDestroy() {
        visible = false;
        super.onDestroy();
    }

    @Override
    public void onResume() {
        visible = true;
        super.onResume();
    }

    @Override
    public void onPause() {
        visible = false;
        super.onPause();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
        private class Discovery extends ServerDiscovery {
            Discovery(Context context) {
                super(context, true);
            }

            public void discoveryFinished(List<Server> servers) {
                Utils.debug("Discovery finished");
                if (getContext()==null) {
                    return;
                }
                if (servers.isEmpty()) {
                    StyleableToast.makeText(getContext(), getResources().getString(R.string.no_servers), Toast.LENGTH_SHORT, R.style.toast).show();
                } else {
                    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                    Server serverToUse = servers.get(0);
                    Server current = new Server(sharedPreferences.getString(SERVER_PREF_KEY, null));

                    if (servers.size()>1) {
                        // If more than 1 server found, then select one that is different to the currently selected one.
                        if (!current.isEmpty()) {
                            for (Server server: servers) {
                                if (!server.equals(current)) {
                                    serverToUse = server;
                                    break;
                                }
                            }
                        }
                    }

                    if (current.isEmpty() || !current.equals(serverToUse)) {
                        if (current.isEmpty()) {
                            StyleableToast.makeText(getContext(), getResources().getString(R.string.server_discovered)+"\n\n"+serverToUse.describe(), Toast.LENGTH_SHORT, R.style.toast).show();
                        } else {
                            StyleableToast.makeText(getContext(), getResources().getString(R.string.server_changed)+"\n\n"+serverToUse.describe(), Toast.LENGTH_SHORT, R.style.toast).show();
                        }

                        Preference addressButton = getPreferenceManager().findPreference("server_address");
                        if (addressButton != null) {
                            addressButton.setSummary(serverToUse.describe());
                        }
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString(SERVER_PREF_KEY, serverToUse.encode());
                        editor.apply();
                    } else {
                        StyleableToast.makeText(getContext(), getResources().getString(R.string.no_new_server), Toast.LENGTH_SHORT, R.style.toast).show();
                    }
                }
            }
        }

        private SettingsActivity activity = null;
        private Discovery discovery = null;

        public void setActivity(SettingsActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            Utils.debug("SETUP");

            if (getContext()==null) {
                return;
            }
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            final Preference addressButton = getPreferenceManager().findPreference("server_address");
            if (addressButton != null) {
                addressButton.setSummary(new Discovery.Server(sharedPreferences.getString(SERVER_PREF_KEY,"")).describe());
                addressButton.setOnPreferenceClickListener(arg0 -> {
                    if (getContext()!=null) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                        builder.setTitle(R.string.server_address);
                        SharedPreferences sharedPreferences1 = PreferenceManager.getDefaultSharedPreferences(getContext());
                        Discovery.Server server = new Discovery.Server(sharedPreferences1.getString(SERVER_PREF_KEY, null));

                        int padding = getResources().getDimensionPixelOffset(R.dimen.dlg_padding);
                        final EditText input = new EditText(getContext());
                        input.setInputType(InputType.TYPE_CLASS_TEXT);
                        input.setText(server.address());
                        LinearLayout layout = new LinearLayout(getContext());
                        layout.setOrientation(LinearLayout.VERTICAL);
                        layout.setPadding(padding, padding, padding, padding / 2);
                        layout.addView(input);
                        builder.setView(layout);

                        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
                            String str = input.getText().toString().replaceAll("\\s+", "");
                            String[] parts = str.split(":");
                            Discovery.Server server1 = new Discovery.Server(parts[0], parts.length > 1 ? Integer.parseInt(parts[1]) : Discovery.Server.DEFAULT_PORT, null);
                            SharedPreferences sharedPreferences11 = PreferenceManager.getDefaultSharedPreferences(getContext());
                            SharedPreferences.Editor editor = sharedPreferences11.edit();
                            editor.putString(SERVER_PREF_KEY, server1.encode());
                            editor.apply();
                            addressButton.setSummary(server1.describe());
                        });
                        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

                        builder.show();
                    }
                    return true;
                });
            }

            Preference discoverButton = getPreferenceManager().findPreference("discover");
            if (discoverButton != null) {
                discoverButton.setOnPreferenceClickListener(arg0 -> {
                    Utils.debug("Discover clicked");
                    if (getContext()!=null) {
                        StyleableToast.makeText(getContext(), getResources().getString(R.string.discovering_server), Toast.LENGTH_SHORT, R.style.toast).show();
                        if (discovery == null) {
                            discovery = new Discovery(getContext().getApplicationContext());
                        }
                        discovery.discover();
                    }
                    return true;
                });
            }

            final Preference defaultPlayerButton = getPreferenceManager().findPreference(DEFAULT_PLAYER_PREF_KEY);
            if (defaultPlayerButton != null) {
                String defaultPlayer = sharedPreferences.getString(DEFAULT_PLAYER_PREF_KEY, null);
                if (defaultPlayer!=null && !defaultPlayer.isEmpty()) {
                    defaultPlayerButton.setSummary(defaultPlayer);
                }

                defaultPlayerButton.setOnPreferenceClickListener(arg0 -> {
                    if (getContext()!=null) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                        builder.setTitle(R.string.default_player);
                        SharedPreferences sharedPreferences12 = PreferenceManager.getDefaultSharedPreferences(getContext());
                        String value = sharedPreferences12.getString(DEFAULT_PLAYER_PREF_KEY, null);

                        int padding = getResources().getDimensionPixelOffset(R.dimen.dlg_padding);
                        final EditText input = new EditText(getContext());
                        input.setInputType(InputType.TYPE_CLASS_TEXT);

                        if (null != value) {
                            input.setText(value);
                        }

                        LinearLayout layout = new LinearLayout(getContext());
                        layout.setOrientation(LinearLayout.VERTICAL);
                        layout.setPadding(padding, padding, padding, padding / 2);
                        layout.addView(input);
                        builder.setView(layout);

                        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
                            String str = input.getText().toString().trim();
                            SharedPreferences sharedPreferences121 = PreferenceManager.getDefaultSharedPreferences(getContext());
                            SharedPreferences.Editor editor = sharedPreferences121.edit();
                            editor.putString(DEFAULT_PLAYER_PREF_KEY, str);
                            editor.apply();
                            defaultPlayerButton.setSummary(str.isEmpty() ? getResources().getString(R.string.default_player_summary) : str);
                        });
                        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

                        builder.show();
                    }
                    return true;
                });
            }

            Preference clearCacheButton = getPreferenceManager().findPreference(CLEAR_CACHE_PREF_KEY);
            if (clearCacheButton != null) {
                clearCacheButton.setOnPreferenceClickListener(arg0 -> {
                    if (getContext()!=null) {
                        SharedPreferences sharedPreferences13 = PreferenceManager.getDefaultSharedPreferences(getContext());
                        boolean clear = sharedPreferences13.getBoolean(CLEAR_CACHE_PREF_KEY, false);
                        Utils.debug("Clear clicked, config:" + clear);
                        if (!clear) {
                            SharedPreferences.Editor editor = sharedPreferences13.edit();
                            editor.putBoolean(CLEAR_CACHE_PREF_KEY, true);
                            editor.apply();
                            StyleableToast.makeText(getContext(), getResources().getString(R.string.cache_to_be_cleared), Toast.LENGTH_SHORT, R.style.toast).show();
                        }
                    }
                    return true;
                });
            }

            Preference stopAppButton = getPreferenceManager().findPreference(STOP_APP_PREF_KEY);
            if (stopAppButton != null) {
                stopAppButton.setOnPreferenceClickListener(arg0 -> {
                    if (getContext()!=null) {
                        LocalPlayer localPlayer = new LocalPlayer(PreferenceManager.getDefaultSharedPreferences(getContext()), getContext());
                        StyleableToast.makeText(getContext(), getResources().getString(R.string.stopping_player), Toast.LENGTH_SHORT, R.style.toast).show();
                        localPlayer.stop();
                    }
                    return true;
                });
            }

            final Preference squeezeliteOptionsButton = getPreferenceManager().findPreference(SQUEEZELITE_OPTIONS_KEY);
            if (squeezeliteOptionsButton != null) {
                squeezeliteOptionsButton.setSummary(sharedPreferences.getString(SQUEEZELITE_OPTIONS_KEY,""));
                squeezeliteOptionsButton.setOnPreferenceClickListener(arg0 -> {
                    if (getContext()!=null) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                        builder.setTitle(R.string.squeezelite_options);
                        SharedPreferences sharedPreferences14 = PreferenceManager.getDefaultSharedPreferences(getContext());

                        int padding = getResources().getDimensionPixelOffset(R.dimen.dlg_padding);
                        TextView text = new TextView(getContext());
                        final EditText input = new EditText(getContext());
                        input.setInputType(InputType.TYPE_CLASS_TEXT);
                        input.setText(sharedPreferences14.getString(SQUEEZELITE_OPTIONS_KEY, null));
                        text.setText(R.string.squeezelite_options_summary);
                        LinearLayout layout = new LinearLayout(getContext());
                        layout.setOrientation(LinearLayout.VERTICAL);
                        layout.setPadding(padding, padding, padding, padding / 2);
                        layout.addView(text);
                        layout.addView(input);
                        builder.setView(layout);

                        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
                            String str = input.getText().toString();
                            SharedPreferences sharedPreferences141 = PreferenceManager.getDefaultSharedPreferences(getContext());
                            SharedPreferences.Editor editor = sharedPreferences141.edit();
                            editor.putString(SQUEEZELITE_OPTIONS_KEY, str);
                            editor.apply();
                            squeezeliteOptionsButton.setSummary(str);
                        });
                        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.cancel());

                        builder.show();
                    }
                    return true;
                });
            }

            if (Utils.cutoutTopLeft(activity)) {
                SwitchPreferenceCompat fullscreenPref = getPreferenceManager().findPreference(FULLSCREEN_PREF_KEY);
                if (null != fullscreenPref) {
                    fullscreenPref.setVisible(false);
                }
            }

            updateListSummary(NOTIFCATIONS_PREF_KEY);
            updateListSummary(ORIENTATION_PREF_KEY);
            updateListSummary(ON_CALL_PREF_KEY);
            updateListSummary(PLAYER_APP_PREF_KEY);
            PreferenceManager.getDefaultSharedPreferences(getContext()).registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (ORIENTATION_PREF_KEY.equals(key)) {
                updateListSummary(key);
            }

            if (ON_CALL_PREF_KEY.equals(key)) {
                updateListSummary(key);
                if (! PhoneStateHandler.DO_NOTHING.equals(sharedPreferences.getString(key, PhoneStateHandler.DO_NOTHING))) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        activity.checkOnCallAndNotifPermission();
                    } else {
                        activity.checkOnCallPermission();
                    }
                }
            }
            if (PLAYER_APP_PREF_KEY.equals(key)) {
                updateListSummary(key);
                if (LocalPlayer.TERMUX_PLAYER.equals(sharedPreferences.getString(key, null))) {
                    activity.checkTermuxPermission();
                }
            }
            if (NOTIFCATIONS_PREF_KEY.equals(key)) {
                updateListSummary(key);
                if (! ControlService.NO_NOTIFICATION.equals(sharedPreferences.getString(key, ControlService.NO_NOTIFICATION))) {
                    activity.checkNotificationPermission();
                } else {
                    resetOnCall();
                }
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (getContext()!=null) {
                PreferenceManager.getDefaultSharedPreferences(getContext()).unregisterOnSharedPreferenceChangeListener(this);
            }
        }

        private void updateListSummary(String key) {
            ListPreference pref = getPreferenceManager().findPreference(key);
            if (pref != null) {
                pref.setSummary(pref.getEntry());
            }
        }

        public void resetOnCall() {
            if (getContext()==null) {
                return;
            }
            ListPreference pref = getPreferenceManager().findPreference(ON_CALL_PREF_KEY);
            if (pref==null || !PhoneStateHandler.DO_NOTHING.equals(pref.getValue())) {
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putString(ON_CALL_PREF_KEY, PhoneStateHandler.DO_NOTHING);
                editor.apply();
                if (pref != null) {
                    pref.setValue(PhoneStateHandler.DO_NOTHING);
                }
                updateListSummary(ON_CALL_PREF_KEY);
            }
        }

        public void resetStartPlayer() {
            if (getContext()==null) {
                return;
            }
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(PLAYER_APP_PREF_KEY, LocalPlayer.NO_PLAYER);
            editor.apply();
            ListPreference pref = getPreferenceManager().findPreference("start_player");
            if (pref != null) {
                pref.setValue(LocalPlayer.NO_PLAYER);
            }
            updateListSummary(PLAYER_APP_PREF_KEY);
        }

        public void setNotifications(String val) {
            if (getContext()==null) {
                return;
            }
            ListPreference pref = getPreferenceManager().findPreference(NOTIFCATIONS_PREF_KEY);
            String current = null==pref ? ControlService.NO_NOTIFICATION : pref.getValue();

            if (!ControlService.NO_NOTIFICATION.equals(val) && !ControlService.NO_NOTIFICATION.equals(current)) {
                // Already enabled
                return;
            }

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(NOTIFCATIONS_PREF_KEY, val);
            editor.apply();
            updateListSummary(NOTIFCATIONS_PREF_KEY);

            if (ControlService.NO_NOTIFICATION.equals(val)) {
                resetOnCall();
            }
        }
    }

    public void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !Utils.notificationAllowed(this, ControlService.NOTIFICATION_CHANNEL_ID)) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_POST_NOTIFICATIONS);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    public void checkOnCallAndNotifPermission() {
        boolean reqPhoneState = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED,
                reqNotify = !Utils.notificationAllowed(this, ControlService.NOTIFICATION_CHANNEL_ID);

        if (reqPhoneState && reqNotify) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_PHONE_STATE}, PERMISSION_NOTIFS_AND_READ_PHONE_STATE);
        } else if (reqNotify) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_POST_NOTIFICATIONS);
        } else if (reqPhoneState) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_PHONE_STATE}, PERMISSION_READ_PHONE_STATE);
        } else  {
            fragment.setNotifications(ControlService.FULL_NOTIFICATION);
        }
    }

    public void checkOnCallPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_PHONE_STATE}, PERMISSION_READ_PHONE_STATE);
        } else  {
            fragment.setNotifications(ControlService.FULL_NOTIFICATION);
        }
    }

    public void checkTermuxPermission() {
        if (ContextCompat.checkSelfPermission(this, TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{TERMUX_PERMISSION}, PERMISSION_RUN_TERMUX_COMMAND);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_READ_PHONE_STATE: {
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    fragment.resetOnCall();
                } else {
                    fragment.setNotifications(ControlService.FULL_NOTIFICATION);
                }
                return;
            }
            case PERMISSION_RUN_TERMUX_COMMAND: {
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    fragment.resetStartPlayer();
                }
                return;
            }
            case PERMISSION_POST_NOTIFICATIONS: {
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    fragment.setNotifications(ControlService.FULL_NOTIFICATION);
                }
                return;
            }
            case PERMISSION_NOTIFS_AND_READ_PHONE_STATE:
                if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    fragment.setNotifications(ControlService.NO_NOTIFICATION);
                    fragment.resetOnCall();
                } else if (grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                    fragment.resetOnCall();
                } else {
                    fragment.setNotifications(ControlService.FULL_NOTIFICATION);
                }
                return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
