package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    public static final String SERVER_PREF_KEY = "server";
    public static final String CLEAR_CACHE_PREF_KEY = "clear_cache";
    public static final String SCALE_PREF_KEY = "scale";
    public static final String STATUSBAR_PREF_KEY = "statusbar";
    private static final String TAG = "LMS";
    private static boolean visible = false;
    public static boolean isVisible() {
        return visible;
    }

    private boolean hideStatusbar = false;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        visible = true;
        setContentView(R.layout.settings_activity);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment())
                .commit();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        String sbar = PreferenceManager.getDefaultSharedPreferences(this).getString(SettingsActivity.STATUSBAR_PREF_KEY, "visible");
        if ("blend".equals(sbar)) {
            getWindow().setStatusBarColor(getColor(R.color.colorPrimary));
        }
        hideStatusbar = "hidden".equals(sbar);
        setFullscreen();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            visible = false;
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        visible = false;
        super.onBackPressed();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
        private class Discovery extends ServerDiscovery {
            Discovery(Context context) {
                super(context, true);
            }

            public void discoveryFinished(List<Server> servers) {
                Log.d(TAG, "Discovery finished");
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

                        Preference discoverButton = getPreferenceManager().findPreference("discover");
                        if (discoverButton != null) {
                            discoverButton.setSummary(serverToUse.describe());
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

        private Discovery discovery = null;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            Log.d(TAG, "SETUP");
            Preference discoverButton = getPreferenceManager().findPreference("discover");
            if (discoverButton != null) {
                SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                discoverButton.setSummary(new Discovery.Server(sharedPreferences.getString(SERVER_PREF_KEY,"")).describe());
                discoverButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference arg0) {
                        Toast.makeText(getContext(), getResources().getString(R.string.discovering_server), Toast.LENGTH_SHORT).show();
                        if (discovery == null) {
                            discovery = new Discovery(getContext().getApplicationContext());
                        }
                        discovery.discover();
                        return true;
                    }
                });
            }

            Preference clearCacheButton = getPreferenceManager().findPreference("clearcache");
            if (clearCacheButton != null) {
                clearCacheButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference arg0) {
                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                        boolean clear = sharedPreferences.getBoolean(CLEAR_CACHE_PREF_KEY, false);
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
            updateStatusbarPreSummary();
            PreferenceManager.getDefaultSharedPreferences(getContext()).registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (STATUSBAR_PREF_KEY.equals(key)) {
                updateStatusbarPreSummary();
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            PreferenceManager.getDefaultSharedPreferences(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        }

        private void updateStatusbarPreSummary() {
            ListPreference statusBarPref = getPreferenceManager().findPreference(STATUSBAR_PREF_KEY);
            if (statusBarPref != null) {
                statusBarPref.setSummary(statusBarPref.getEntry());
            }
        }
    }

    private void setFullscreen() {
        if (hideStatusbar) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
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
}