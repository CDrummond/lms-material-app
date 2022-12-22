/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2022 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

public class LocalPlayer {
    public static final String NO_PLAYER = "none";
    public static final String SB_PLAYER = "sbplayer";
    public static final String SQUEEZE_PLAYER = "squeezeplayer";
    public static final String TERMUX_PLAYER = "termux";

    private static final String SB_PLAYER_PKG = "com.angrygoat.android.sbplayer";
    private static final String SQUEEZE_PLAYER_PKG = "de.bluegaspode.squeezeplayer";
    private static final String SQUEEZE_PLAYER_SRV = "de.bluegaspode.squeezeplayer.playback.service.PlaybackService";

    private static boolean started = false;
    public static void start(SharedPreferences sharedPreferences, Context context) {
        if (started) {
            return;
        }
        String playerApp = sharedPreferences.getString(SettingsActivity.START_PLAYER_PREF_KEY, null);
        if (SB_PLAYER.equals(playerApp)) {
            Intent launchIntent = context.getApplicationContext().getPackageManager().getLaunchIntentForPackage(SB_PLAYER_PKG);
            context.startActivity(launchIntent);
            started = true;
        } else if (SQUEEZE_PLAYER.equals(playerApp)) {
            ComponentName component = new ComponentName(SQUEEZE_PLAYER_PKG, SQUEEZE_PLAYER_SRV);
            Intent intent = new Intent().setComponent(component);
            ServerDiscovery.Server current = new ServerDiscovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY, null));

            if (current != null) {
                intent.putExtra("forceSettingsFromIntent", true);
                intent.putExtra("intentHasServerSettings", true);
                intent.putExtra("serverURL", current.ip);
                intent.putExtra("serverName", current.name);
                String user = sharedPreferences.getString(MainActivity.LMS_USERNAME_KEY, null);
                String pass = sharedPreferences.getString(MainActivity.LMS_PASSWORD_KEY, null);
                if (user != null) {
                    intent.putExtra("username", user);
                }
                if (pass != null) {
                    intent.putExtra("password", pass);
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            started = true;
        } else if (TERMUX_PLAYER.equals(playerApp)) {
            ServerDiscovery.Server current = new ServerDiscovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY, null));
            if (current!=null) {
                Intent intent = new Intent();
                intent.setClassName("com.termux", "com.termux.app.RunCommandService");
                intent.setAction("com.termux.RUN_COMMAND");
                intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/squeezelite");
                intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-M", "SqueezeLiteAndroid", "-s", current.ip, "-C", "5", "-n", Settings.Global.getString(context.getContentResolver(), "device_name")});
                intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
                intent.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
                intent.putExtra("com.termux.execute.command_label", "LMS Player");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
                started = true;
            }
        }
    }
}
