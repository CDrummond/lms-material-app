/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2023 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;


public class LocalPlayer {
    public static final String NO_PLAYER = "none";
    public static final String SB_PLAYER = "sbplayer";
    public static final String SQUEEZE_PLAYER = "squeezeplayer";
    public static final String SQUEEZELITE = "squeezelite";

    public static final String SB_PLAYER_PKG = "com.angrygoat.android.sbplayer";
    public static final String SQUEEZE_PLAYER_PKG = "de.bluegaspode.squeezeplayer";
    public static final String SQUEEZELITE_PKG = "org.lyrion.squeezelite";
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
        }
    }

    private boolean sendSbPlayerIntent(boolean start) {
        if (!Utils.isInstalled(context, SB_PLAYER_PKG, "SB Player")) {
            return false;
        }
        Intent intent = new Intent();
        intent.setClassName(SB_PLAYER_PKG, SB_PLAYER_PKG+".SBPlayerReceiver");
        intent.setAction(SB_PLAYER_PKG+"." + (start ? "LAUNCH" : "EXIT"));
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
        if (!Utils.isInstalled(context, SQUEEZE_PLAYER_PKG, "SqueezePlayer")) {
            return false;
        }
        Intent intent = new Intent();
        intent.setClassName(SQUEEZE_PLAYER_PKG, SQUEEZE_PLAYER_PKG+".playback.service.PlaybackService");

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
        if (!Utils.isInstalled(context, SQUEEZELITE_PKG, "Squeezelite")) {
            return false;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName(SQUEEZELITE_PKG, SQUEEZELITE_PKG+".PlayerService");
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
}
