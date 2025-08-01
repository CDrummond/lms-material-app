/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2023 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import io.github.muddz.styleabletoast.StyleableToast;

public class LocalPlayer {
    public static final String NO_PLAYER = "none";
    public static final String SB_PLAYER = "sbplayer";
    public static final String SQUEEZE_PLAYER = "squeezeplayer";
    public static final String SQUEEZELITE = "squeezelite";
    public static final String TERMUX_PLAYER = "termux";
    public static final String TERMUX_MAC_PREF = "termux_mac";

    private final SharedPreferences sharedPreferences;
    private final Context context;
    private JsonRpc rpc = null;

    private enum State {
        INITIAL,
        STARTED,
        STOPPED
    }
    private static State state = State.INITIAL;

    public LocalPlayer(SharedPreferences sharedPreferences, Context context) {
        this.sharedPreferences = sharedPreferences;
        this.context = context;
    }

    public void autoStart(boolean fromResume) {
        // If resuming and user had stopped this player, then don't auto-restart
        if (fromResume && State.STOPPED.equals(state)) {
            return;
        }
        if (sharedPreferences.getBoolean(SettingsActivity.AUTO_START_PLAYER_APP_PREF_KEY, false)) {
            // Only SqueezePlayer needs re-starting from resume???
            if (!fromResume || SQUEEZE_PLAYER.equals(sharedPreferences.getString(SettingsActivity.PLAYER_APP_PREF_KEY, null))) {
                start();
            }
        }
    }

    public void autoStop() {
        if (sharedPreferences.getBoolean(SettingsActivity.STOP_APP_ON_QUIT_PREF_KEY, false)) {
            stop();
        }
    }

    @SuppressLint("SdCardPath")
    public void start() {
        String playerApp = sharedPreferences.getString(SettingsActivity.PLAYER_APP_PREF_KEY, null);
        Utils.debug("Start player: " + playerApp);
        if (SB_PLAYER.equals(playerApp)) {
            if (sendSbPlayerIntent(true)) {
                state = State.STARTED;
            }
        } else if (SQUEEZE_PLAYER.equals(playerApp)) {
            if (controlSqueezePlayer(true)) {
                state = State.STARTED;
            }
        } else if (SQUEEZELITE.equals(playerApp)) {
            if (controlSqueezelite(true)) {
                state = State.STARTED;
            }
        } else if (TERMUX_PLAYER.equals(playerApp)) {
            // First check Squeezelite is not already running...
            runTermuxCommand("/data/data/com.termux/files/usr/bin/ps", new String[]{"-eaf"}, true);
        }
    }

    @SuppressLint("SdCardPath")
    public void startTermuxSqueezeLite() {
        ServerDiscovery.Server current = new ServerDiscovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY, null));
        state = State.INITIAL;
        String opts = sharedPreferences.getString(SettingsActivity.SQUEEZELITE_OPTIONS_KEY, "");
        Map<String, String> params = new HashMap<>();
        params.put("-M", "SqueezeLiteAndroid");
        params.put("-C", "5");
        params.put("-s", current.ip);
        params.put("-m", getTermuxMac());
        params.put("-n", Settings.Global.getString(context.getContentResolver(), "device_name"));
        String[] parts = opts.split(" ");
        if (parts.length>1 && parts.length%2 == 0) {
            for (int i=0; i<parts.length; i+=2) {
                if (parts[i].startsWith("-")) {
                    if (parts[i].equals("-n")) {
                        params.put(parts[i], parts[i + 1].replace("_", " "));
                    } else {
                        params.put(parts[i], parts[i + 1]);
                    }
                }
            }
        }
        String[] args = new String[params.size()*2];
        int i=0;
        for (Map.Entry<String, String> entry: params.entrySet()) {
            args[i]=entry.getKey();
            i++;
            args[i]=entry.getValue();
            i++;
        }
        if (runTermuxCommand("/data/data/com.termux/files/usr/bin/squeezelite", args, false)) {
            state = State.STARTED;
        }
    }

    public void stopPlayer(String playerId) {
        // If stopping player via skin's 'power' button, then we need to ask LMS to forget
        // the client first, and then do the actual stop.
        if (null==rpc) {
            rpc = new JsonRpc(context);
        }
        rpc.sendMessage(playerId, new String[]{"client", "forget"}, response -> stop());
    }

    @SuppressLint("SdCardPath")
    public void stop() {
        String playerApp = sharedPreferences.getString(SettingsActivity.PLAYER_APP_PREF_KEY, null);
        Utils.debug("Stop player: " + playerApp);
        if (SB_PLAYER.equals(playerApp)) {
            if (sendSbPlayerIntent(false)) {
                state = State.STOPPED;
            }
        } else if (SQUEEZE_PLAYER.equals(playerApp)) {
            if (controlSqueezePlayer(false)) {
                state = State.STOPPED;
            }
        } else if (SQUEEZELITE.equals(playerApp)) {
            if (controlSqueezelite(false)) {
                state = State.STOPPED;
            }
        } else if (TERMUX_PLAYER.equals(playerApp)) {
            if (runTermuxCommand("/data/data/com.termux/files/usr/bin/killall", new String[]{"-9", "squeezelite"}, false)) {
                state = State.STOPPED;
            }
        }
    }

    private String getTermuxMac() {
        String mac = sharedPreferences.getString(TERMUX_MAC_PREF, null);
        if (null!=mac) {
            return mac;
        }
        List<String> parts = new LinkedList<>();
        Random rand = new Random();
        parts.add("ab");
        parts.add("cd");
        while (parts.size()<6) {
            parts.add(String.format("%02x", rand.nextInt(255)));
        }
        String newMac = String.join(":", parts);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(TERMUX_MAC_PREF, newMac);
        editor.apply();
        return newMac;
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
            Utils.error("Failed to control SB Player - " + e.getMessage());
            return false;
        }
    }

    private boolean controlSqueezePlayer(boolean start) {
        Intent intent = new Intent();
        intent.setClassName("de.bluegaspode.squeezeplayer", "de.bluegaspode.squeezeplayer.playback.service.PlaybackService");

        ServerDiscovery.Server current = new ServerDiscovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY, null));
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
        try {
            if (start) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } else {
                context.stopService(intent);
            }
            return true;
        } catch (Exception e) {
            Utils.error("Failed to control SqueezePlayer - " + e.getMessage());
            return false;
        }
    }

    private boolean controlSqueezelite(boolean start) {
        try {
            Intent intent = new Intent();
            intent.setClassName("org.lyrion.squeezelite", "org.lyrion.squeezelite.PlayerService");
            if (start) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent);
                } else {
                    context.startService(intent);
                }
            } else {
                context.stopService(intent);
            }
            return true;
        } catch (Exception e) {
            Utils.error("Failed to control Squeezelite - " + e.getMessage());
            return false;
        }
    }

    private boolean runTermuxCommand(String app, String[] args, boolean handleResp) {
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
        if (handleResp) {
            Utils.debug("HANDLE RESP");
            int executionId = TermuxResultsService.getNextExecutionId();
            Intent serviceIntent = new Intent(context, TermuxResultsService.class);
            serviceIntent.putExtra(TermuxResultsService.EXTRA_EXECUTION_ID, executionId);
            PendingIntent pendingIntent = PendingIntent.getService(context, executionId, serviceIntent,
                        PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0));
            intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent);
        }
        Utils.debug("Send Termux command:"+app+" args:"+String.join(", ", args));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (Exception e) {
            Utils.error("Failed to send Termux command - " + e.getMessage());
            return false;
        }
    }
}
