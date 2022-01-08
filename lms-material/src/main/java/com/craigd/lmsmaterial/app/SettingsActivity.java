/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2022 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    public static final String SERVER_PREF_KEY = "server";
    public static final String CLEAR_CACHE_PREF_KEY = "clear_cache";
    public static final String SCALE_PREF_KEY = "zoomscale";
    public static final String STATUSBAR_PREF_KEY = "statusbar";
    public static final String NAVBAR_PREF_KEY = "navbar";
    public static final String KEEP_SCREEN_ON_PREF_KEY = "keep_screen_on";
    public static final String ENABLE_WIFI_PREF_KEY = "enable_wifi";
    public static final String ORIENTATION_PREF_KEY = "orientation";
    public static final String ON_CALL_PREF_KEY = "on_call";
    public static final String ENABLE_NOTIF_PREF_KEY = "enable_notif";
    public static final String SHOW_OVER_LOCK_SCREEN_PREF_KEY ="show_over_lock_screen";
    public static final String DEFAULT_PLAYER_PREF_KEY ="default_player";
    public static final String SINGLE_PLAYER_PREF_KEY ="single_player";

    private static final int PERMISSION_READ_PHONE_STATE = 1;

    private static boolean visible = false;
    public static boolean isVisible() {
        return visible;
    }

    private SettingsFragment fragment;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        visible = true;
        setContentView(R.layout.settings_activity);
        fragment = new SettingsFragment();
        fragment.setActivity(this);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, fragment)
                .commit();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
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
                Log.d(MainActivity.TAG, "Discovery finished");
                if (servers.size()<1) {
                    Toast.makeText(getContext(),getResources().getString(R.string.no_servers), Toast.LENGTH_SHORT).show();
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
                            Toast.makeText(getContext(), getResources().getString(R.string.server_discovered)+"\n\n"+serverToUse.describe(), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getContext(), getResources().getString(R.string.server_changed)+"\n\n"+serverToUse.describe(), Toast.LENGTH_SHORT).show();
                        }

                        Preference addressButton = getPreferenceManager().findPreference("server_address");
                        if (addressButton != null) {
                            addressButton.setSummary(serverToUse.describe());
                        }
                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString(SERVER_PREF_KEY, serverToUse.encode());
                        editor.apply();
                    } else {
                        Toast.makeText(getContext(), getResources().getString(R.string.no_new_server), Toast.LENGTH_SHORT).show();
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
            Log.d(MainActivity.TAG, "SETUP");

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            final Preference addressButton = getPreferenceManager().findPreference("server_address");
            if (addressButton != null) {
                addressButton.setSummary(new Discovery.Server(sharedPreferences.getString(SERVER_PREF_KEY,"")).describe());
                addressButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference arg0) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                        builder.setTitle(R.string.server_address);
                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                        Discovery.Server server = new Discovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY,null));

                        int padding = getResources().getDimensionPixelOffset(R.dimen.dlg_padding);
                        final EditText input = new EditText(getContext());
                        input.setInputType(InputType.TYPE_CLASS_TEXT);

                        if (null!=server) {
                            input.setText(server.address());
                        }

                        input.setPadding(padding, input.getPaddingTop(), padding, input.getPaddingBottom());
                        builder.setView(input);

                        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String str = input.getText().toString();
                                if (null!=str) {
                                    str=str.replaceAll("\\s+","");
                                    String parts[]=str.split(":");
                                    Discovery.Server server=new Discovery.Server(parts[0], parts.length>1 ? Integer.parseInt(parts[1]) : Discovery.Server.DEFAULT_PORT, null);
                                    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                                    SharedPreferences.Editor editor = sharedPreferences.edit();
                                    editor.putString(SERVER_PREF_KEY, server.encode());
                                    editor.apply();
                                    addressButton.setSummary(server.describe());
                                }
                            }
                        });
                        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.cancel();
                            }
                        });

                        builder.show();
                        return true;
                    }
                });
            }

            Preference discoverButton = getPreferenceManager().findPreference("discover");
            if (discoverButton != null) {
                discoverButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference arg0) {
                        Log.d(MainActivity.TAG, "Discover clicked");
                        Toast.makeText(getContext(), getResources().getString(R.string.discovering_server), Toast.LENGTH_SHORT).show();
                        if (discovery == null) {
                            discovery = new Discovery(getContext().getApplicationContext());
                        }
                        discovery.discover();
                        return true;
                    }
                });
            }

            final Preference defaultPlayerButton = getPreferenceManager().findPreference("default_player");
            if (defaultPlayerButton != null) {
                String defaultPlayer = sharedPreferences.getString(SettingsActivity.DEFAULT_PLAYER_PREF_KEY, null);
                if (defaultPlayer!=null && !defaultPlayer.isEmpty()) {
                    defaultPlayerButton.setSummary(defaultPlayer);
                }

                defaultPlayerButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference arg0) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
                        builder.setTitle(R.string.default_player);
                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                        String value = sharedPreferences.getString(SettingsActivity.DEFAULT_PLAYER_PREF_KEY,null);

                        int padding = getResources().getDimensionPixelOffset(R.dimen.dlg_padding);
                        final EditText input = new EditText(getContext());
                        input.setInputType(InputType.TYPE_CLASS_TEXT);

                        if (null!=value) {
                            input.setText(value);
                        }

                        input.setPadding(padding, input.getPaddingTop(), padding, input.getPaddingBottom());
                        builder.setView(input);

                        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                String str = input.getText().toString();
                                if (null!=str) {
                                    str = str.trim();
                                    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                                    SharedPreferences.Editor editor = sharedPreferences.edit();
                                    editor.putString(DEFAULT_PLAYER_PREF_KEY, str);
                                    editor.apply();
                                    defaultPlayerButton.setSummary(str.isEmpty() ? getResources().getString(R.string.default_player_summary) : str);
                                }
                            }
                        });
                        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.cancel();
                            }
                        });

                        builder.show();
                        return true;
                    }
                });
            }

            Preference clearCacheButton = getPreferenceManager().findPreference(CLEAR_CACHE_PREF_KEY);
            if (clearCacheButton != null) {
                clearCacheButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference arg0) {
                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                        boolean clear = sharedPreferences.getBoolean(CLEAR_CACHE_PREF_KEY, false);
                        Log.d(MainActivity.TAG, "Clear clicked, config:"+clear);
                        if (!clear) {
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putBoolean(CLEAR_CACHE_PREF_KEY, true);
                            editor.apply();
                            Toast.makeText(getContext(), getResources().getString(R.string.cache_to_be_cleared), Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    }
                });
            }

            updateListSummary(STATUSBAR_PREF_KEY);
            updateListSummary(NAVBAR_PREF_KEY);
            updateListSummary(ORIENTATION_PREF_KEY);
            updateListSummary(ON_CALL_PREF_KEY);
            PreferenceManager.getDefaultSharedPreferences(getContext()).registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (STATUSBAR_PREF_KEY.equals(key) || NAVBAR_PREF_KEY.equals(key) || ORIENTATION_PREF_KEY.equals(key)) {
                updateListSummary(key);
            }

            if (ON_CALL_PREF_KEY.equals(key)) {
                updateListSummary(key);
                if (! PhoneStateReceiver.DO_NOTHING.equals(sharedPreferences.getString(key, PhoneStateReceiver.DO_NOTHING))) {
                    activity.checkOnCallPermission();
                }
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            PreferenceManager.getDefaultSharedPreferences(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        }

        private void updateListSummary(String key) {
            ListPreference pref = getPreferenceManager().findPreference(key);
            if (pref != null) {
                pref.setSummary(pref.getEntry());
            }
        }

        public void resetOnCall() {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(SettingsActivity.ON_CALL_PREF_KEY, PhoneStateReceiver.DO_NOTHING);
            editor.apply();
            ListPreference pref = getPreferenceManager().findPreference("on_call");
            if (pref != null) {
                pref.setValue(PhoneStateReceiver.DO_NOTHING);
            }
            updateListSummary(ON_CALL_PREF_KEY);
        }
    }

    public void checkOnCallPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_PHONE_STATE}, PERMISSION_READ_PHONE_STATE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_READ_PHONE_STATE: {
                if (grantResults.length < 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                    fragment.resetOnCall();
                }
                return;
            }
        }
    }
}
