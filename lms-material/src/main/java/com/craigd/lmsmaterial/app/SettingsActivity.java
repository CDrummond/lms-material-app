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
                super(context);
            }

            public void discoveryFinished(List<String> servers) {
                Log.d(TAG, "Discovery finished");
                if (servers.size()<1) {
                    Toast.makeText(getContext(),getResources().getString(R.string.no_servers), Toast.LENGTH_SHORT).show();
                } else {
                    EditTextPreference serverAddress = (EditTextPreference)getPreferenceManager().findPreference("server_address");
                    if (null != serverAddress) {
                        serverAddress.setText(servers.get(0));
                    }

                    SharedPreferences sharedPreferences = getPreferenceManager().getDefaultSharedPreferences(getContext());
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putString("server_address", servers.get(0));
                    editor.commit();
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
                        Boolean clear = sharedPreferences.getBoolean("clear_cache",false);
                        if (!clear) {
                            SharedPreferences.Editor editor = sharedPreferences.edit();
                            editor.putBoolean("clear_cache", true);
                            editor.commit();
                        }
                        return true;
                    }
                });
            }
        }
    }
}