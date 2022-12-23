/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2022 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import io.github.muddz.styleabletoast.StyleableToast;

public class LocalPlayer {
    public static final String NO_PLAYER = "none";
    public static final String SB_PLAYER = "sbplayer";
    public static final String SQUEEZE_PLAYER = "squeezeplayer";
    public static final String TERMUX_PLAYER = "termux";

    private static boolean started = false;
    private SharedPreferences sharedPreferences;
    private Context context;

    public LocalPlayer(SharedPreferences sharedPreferences, Context context) {
        this.sharedPreferences = sharedPreferences;
        this.context = context;
    }

    public void start(boolean force) {
        if (started && !force) {
            return;
        }
        String playerApp = sharedPreferences.getString(SettingsActivity.PLAYER_APP_PREF_KEY, null);
        if (SB_PLAYER.equals(playerApp)) {
            if (sendSbPlayerIntent(true)) {
                started = true;
            }
        } else if (SQUEEZE_PLAYER.equals(playerApp)) {
            if (controlSqueezePlayer(true)) {
                started = true;
            }
        } else if (TERMUX_PLAYER.equals(playerApp)) {
            ServerDiscovery.Server current = new ServerDiscovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY, null));
            if (current!=null) {
                if (runTermuxCommand("/data/data/com.termux/files/usr/bin/bash",
                        new String[]{"/data/data/com.termux/files/home/tmux-sqzlite.sh", "-s", current.ip,
                                "-n", Settings.Global.getString(context.getContentResolver(), "device_name")})) {
                    started = true;
                }
            }
        }
    }

    public boolean stop() {
        String playerApp = sharedPreferences.getString(SettingsActivity.PLAYER_APP_PREF_KEY, null);
        if (SB_PLAYER.equals(playerApp)) {
            if (sendSbPlayerIntent(false)) {
                started = false;
                return true;
            }
        } else if (SQUEEZE_PLAYER.equals(playerApp)) {
            if (controlSqueezePlayer(false)) {
                started = false;
                return true;
            }
        } else if (TERMUX_PLAYER.equals(playerApp)) {
            if (runTermuxCommand("/data/data/com.termux/files/usr/bin/killall", new String[]{"-9", "squeezelite"})) {
                started = false;
                return true;
            }
        }
        return false;
    }

    private boolean sendSbPlayerIntent(boolean start) {
        Intent intent = new Intent();
        intent.setClassName("com.angrygoat.android.sbplayer", "com.angrygoat.android.sbplayer.SBPlayerReceiver");
        intent.setAction("com.angrygoat.android.sbplayer." + (start ? "LAUNCH" : "EXIT"));
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        try {
            context.sendBroadcast(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean controlSqueezePlayer(boolean start) {
        Intent intent = new Intent();
        intent.setClassName("de.bluegaspode.squeezeplayer", "de.bluegaspode.squeezeplayer.playback.service.PlaybackService");

        ServerDiscovery.Server current = new ServerDiscovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY, null));
        if (current != null) {
            intent.putExtra("forceSettingsFromIntent", true);
            intent.putExtra("intentHasServerSettings", true);
            intent.putExtra("serverURL", current.ip + ":" + current.port);
            intent.putExtra("serverName", current.name);
            String user = sharedPreferences.getString(MainActivity.LMS_USERNAME_KEY, null);
            String pass = sharedPreferences.getString(MainActivity.LMS_PASSWORD_KEY, null);
            if (user != null && pass!=null) {
                intent.putExtra("username", user);
                intent.putExtra("password", pass);
            }
        }
        try {
            if (start) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
                started = true;
            } else {
                context.stopService(intent);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean runTermuxCommand(String app, String[] args) {
        if (ContextCompat.checkSelfPermission(context, SettingsActivity.TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            StyleableToast.makeText(context, context.getResources().getString(R.string.no_termux_run_perms), Toast.LENGTH_SHORT, R.style.toast).show();
            return false;
        }
        Intent intent = new Intent();
        intent.setClassName("com.termux", "com.termux.app.RunCommandService");
        intent.setAction("com.termux.RUN_COMMAND");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", app);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", args);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
