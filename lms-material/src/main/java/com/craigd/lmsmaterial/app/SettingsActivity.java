package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "LMS";
    private static final int SERVER_DISCOVERY_TIMEOUT = 1500;
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

        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        public class ServerDiscovery implements Runnable {
            private volatile boolean active = false;
            private volatile boolean cancelled;
            private WifiManager wifiManager;
            public List<String> servers = new LinkedList<String>();

            public ServerDiscovery(WifiManager wifiManager) {
                this.wifiManager = wifiManager;
            }

            @Override
            public void run() {
                Log.d("MSK","Discover LMS servers");

                active = true;
                WifiManager.WifiLock wifiLock;
                DatagramSocket socket = null;
                wifiLock = wifiManager.createWifiLock("MSK");
                wifiLock.acquire();

                try {
                    InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
                    socket = new DatagramSocket();
                    byte[] req = { 'e', 'I', 'P', 'A', 'D', 0, 'N', 'A', 'M', 'E', 0, 'J', 'S', 'O', 'N', 0 };
                    DatagramPacket reqPkt = new DatagramPacket(req, req.length, broadcastAddress, 3483);
                    byte[] resp = new byte[256];
                    DatagramPacket respPkt = new DatagramPacket(resp, resp.length);

                    socket.setSoTimeout(SERVER_DISCOVERY_TIMEOUT);
                    socket.send(reqPkt);
                    for (;;) {
                        if (cancelled) {
                            break;
                        }
                        try {
                            socket.receive(respPkt);
                            if (resp[0]==(byte)'E') {
                                String server = respPkt.getAddress().getHostAddress();
                                if (servers.indexOf(server) < 0) {
                                    servers.add(server);
                                    break; // Stop at first for now...
                                }
                            }
                        } catch (IOException e) {
                            break;
                        }
                    }

                } catch (Exception e) {
                } finally {
                    if (socket != null) {
                        socket.close();
                    }

                    Log.v(TAG, "Scanning complete, unlocking WiFi");
                    wifiLock.release();
                }

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        discoveryFinished();
                    }
                });
                active = false;
            }

            public void cancel() {
                cancelled = true;
            }

            public boolean isActive() {
                return active;
            }
        }

        private ServerDiscovery discovery = null;

        public void discoverServers() {
            if (discovery!=null && discovery.isActive()) {
                return;
            }
            discovery = new ServerDiscovery((WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE));
            Thread thread = new Thread(discovery);
            thread.start();
        }

        public void discoveryFinished() {
            Log.d(TAG, "Discovery finished");
            if (null!=discovery) {
                if (discovery.servers.size()<1) {
                    Toast.makeText(getContext(),getResources().getString(R.string.no_servers), Toast.LENGTH_SHORT).show();
                } else {
                    EditTextPreference serverAddress = (EditTextPreference)getPreferenceManager().findPreference("server_address");
                    if (null != serverAddress) {
                        serverAddress.setText(discovery.servers.get(0));
                    }

                    SharedPreferences sharedPreferences = getPreferenceManager().getDefaultSharedPreferences(getContext());
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putString("server_address", discovery.servers.get(0));
                    editor.commit();
                }
            }
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            Log.d(TAG, "SETUP");
            Preference discoverButton = (Preference)getPreferenceManager().findPreference("discover");
            if (discoverButton != null) {
                discoverButton.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference arg0) {
                        discoverServers();
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