/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2023 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.TelephonyManager;

import androidx.preference.PreferenceManager;

import com.android.volley.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedList;
import java.util.List;

public class PhoneStateHandler {
    public static final String DO_NOTHING = "nothing";
    public static final String MUTE_ALL = "muteall";
    public static final String MUTE_CURRENT = "mutecurrent";
    public static final String PAUSE_ALL = "pauseall";
    public static final String PAUSE_CURRENT = "pausecurrent";

    private SharedPreferences prefs = null;
    private JsonRpc rpc = null;
    private final List<String> activePlayers = new LinkedList<>();
    private boolean inCall = false;

    private final Response.Listener<JSONObject> rpcResponse = response -> {
        activePlayers.clear();
        if (inCall) {
            try {
                Utils.debug("RESP" + response.toString(4));
                JSONObject result = response.getJSONObject("result");
                if (result.has("players")) {
                    JSONArray players = result.getJSONArray("players");
                    if (players.length() > 0) {
                        for (int i = 0; i < players.length(); ++i) {
                            activePlayers.add(players.getJSONObject(i).getString("id"));
                        }
                        Utils.debug("RPC response, activePlayers:" + activePlayers);
                        controlPlayers();
                    }
                }
            } catch (JSONException e) {
                Utils.error("Failed to parse response", e);
            }
        }
    };

    public void handle(Context context, int state) {
        if (null==prefs) {
            prefs = PreferenceManager.getDefaultSharedPreferences(context);
        }
        String action = prefs.getString(SettingsActivity.ON_CALL_PREF_KEY, DO_NOTHING);
        Utils.debug("Call state:" + state + ", action: " + action);

        if (DO_NOTHING.equals(action)) {
            return;
        }
        if (null==rpc) {
            rpc = new JsonRpc(context);
        }
        if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
            callStarted(action);
        } else {
            callEnded();
        }
    }

    private void callStarted(String action) {
        Utils.debug("Call started, activePlayers:"+activePlayers);
        if (MainActivity.isActive() || ControlService.isActive()) {
            inCall = true;
            if (MUTE_CURRENT.equals(action) || PAUSE_CURRENT.equals(action)) {
                activePlayers.add(MainActivity.activePlayer);
                controlPlayers();
            } else {
                getActivePlayers();
            }
        } else {
            Utils.debug("App is not currently active");
        }
    }

    private void callEnded() {
        boolean resume = prefs.getBoolean(SettingsActivity.AFTER_CALL_PREF_KEY, true);
        Utils.debug("Call ended, activePlayers:"+activePlayers+", inCall:"+inCall+", resume:"+resume);
        if (inCall) {
            inCall = false;
            if (prefs.getBoolean(SettingsActivity.AFTER_CALL_PREF_KEY, true)) {
                controlPlayers();
            }
        }
        activePlayers.clear();
    }

    private void getActivePlayers() {
        rpc.sendMessage("", new String[]{"material-skin", "activeplayers"}, rpcResponse);
    }

    private void controlPlayers() {
        if (activePlayers.isEmpty()) {
            Utils.debug("Control players NO ACTIVE PLAYERS");
            return;
        }
        String action = prefs.getString(SettingsActivity.ON_CALL_PREF_KEY, DO_NOTHING);
        Utils.debug("Control players, action:" + action + ", active:" + activePlayers + ", current:"+MainActivity.activePlayer);
        if (MUTE_ALL.equals(action) || PAUSE_ALL.equals(action)) {
            for (String id: activePlayers) {
                controlPlayer(action, id);
            }
        } else if (MUTE_CURRENT.equals(action) || PAUSE_CURRENT.equals(action)) {
            for (String id: activePlayers) {
                Utils.debug(id+"=="+MainActivity.activePlayer+" ? " + id.equals(MainActivity.activePlayer));

                if (id.equals(MainActivity.activePlayer)) {
                    Utils.debug("Matched ID");
                    controlPlayer(action, id);
                    break;
                }
            }
        }
    }

    private void controlPlayer(String action, String player) {
        Utils.debug(action+" on "+player);
        if (MUTE_ALL.equals(action) || MUTE_CURRENT.equals(action)) {
            rpc.sendMessage(player, new String[]{"mixer", "muting", inCall ? "1" : "0"});
        } else if (PAUSE_ALL.equals(action) || PAUSE_CURRENT.equals(action)) {
            rpc.sendMessage(player, new String[]{"pause", inCall ? "1" : "0"});
        }
    }
}
