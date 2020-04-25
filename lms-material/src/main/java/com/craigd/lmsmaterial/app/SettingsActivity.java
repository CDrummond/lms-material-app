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
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    public static final String SERVER_PREF_KEY = "server_address";
    public static final String CLEAR_CACHE_PREF_KEY = "clear_cache";
    private static final String TAG = "LMS";
    private static boolean visible = false;
    public static boolean isVisible() {
        return visible;
    }

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

        getWindow().getDecorView().setSystemUiVisibility(
                  View.SYSTEM_UI_FLAG_IMMERSIVE
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()){
            case android.R.id.home:
                onBackPressed();
                visible = false;
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private class Discovery extends ServerDiscovery {
            public Discovery(Context context) {
                super(context, true);
            }

            public void discoveryFinished(List<String> servers) {
                Log.d(TAG, "Discovery finished");
                if (servers.size()<1) {
                    Toast.makeText(getContext(),getResources().getString(R.string.no_servers), Toast.LENGTH_SHORT).show();
                } else {
                    EditTextPreference serverAddress = (EditTextPreference)getPreferenceManager().findPreference(SERVER_PREF_KEY);
                    SharedPreferences sharedPreferences = getPreferenceManager().getDefaultSharedPreferences(getContext());
                    String serverToUse = servers.get(0);
                    String current = null != serverAddress ? serverAddress.getText() : null;
                    if (null == current || current.isEmpty()) {
                        current = sharedPreferences.getString(SERVER_PREF_KEY, null);
                    }

                    if (servers.size()>1) {
                        // If more than 1 server found, then select one that is different to the currently selected one.

                        if (current!=null && !current.isEmpty()) {
                            for (String server: servers) {
                                if (!server.equals(current)) {
                                    serverToUse = server;
                                    break;
                                }
                            }
                        }
                    }

                    if (null==current || current.isEmpty() || !current.equals(serverToUse)) {
                        if (null==current || current.isEmpty()) {
                            Toast.makeText(getContext(), getResources().getString(R.string.server_discovered), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getContext(), getResources().getString(R.string.server_changed), Toast.LENGTH_SHORT).show();
                        }

                        if (null != serverAddress) {
                            serverAddress.setText(serverToUse);
                        }

                        SharedPreferences.Editor editor = sharedPreferences.edit();
                        editor.putString(SERVER_PREF_KEY, serverToUse);
                        editor.commit();
                    }
                }
            }
        }

        private Discovery discovery = null;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            Log.d(TAG, "SETUP");
            Preference discoverButton = (Preference)getPreferenceManager().findPreference("discover");
            if (discoverButton != null) {
                discoverButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference arg0) {
                        if (discovery == null) {
                            discovery = new Discovery(getContext().getApplicationContext());
                        }
                        discovery.discover();
                        return true;
                    }
                });
            }

            Preference clearCacheButton = (Preference)getPreferenceManager().findPreference("clearcache");
            if (clearCacheButton != null) {
                clearCacheButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference arg0) {
                        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
                        Boolean clear = sharedPreferences.getBoolean(CLEAR_CACHE_PREF_KEY,false);
                        if (!clear) {
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putBoolean(CLEAR_CACHE_PREF_KEY, true);
                            editor.commit();
                        }
                        return true;
                    }
                });
            }
        }
    }
}