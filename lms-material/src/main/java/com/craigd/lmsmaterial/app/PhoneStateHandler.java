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
import android.util.Log;

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
                Log.d(MainActivity.TAG, "RESP" + response.toString(4));
                JSONObject result = response.getJSONObject("result");
                if (result.has("players")) {
                    JSONArray players = result.getJSONArray("players");
                    if (players.length() > 0) {
                        for (int i = 0; i < players.length(); ++i) {
                            activePlayers.add(players.getJSONObject(i).getString("id"));
                        }
                        Log.d(MainActivity.TAG, "RPC response, activePlayers:" + activePlayers);
                        controlPlayers();
                    }
                }
            } catch (JSONException e) {
                Log.e(MainActivity.TAG, "Failed to parse response", e);
            }
        }
    };

    public void handle(Context context, int state) {
        if (null==prefs) {
            prefs = PreferenceManager.getDefaultSharedPreferences(context);
        }
        String action = prefs.getString(SettingsActivity.ON_CALL_PREF_KEY, DO_NOTHING);
        if (DO_NOTHING.equals(action)) {
            return;
        }
        if (null==rpc) {
            rpc = new JsonRpc(context);
        }
        Log.d(MainActivity.TAG, "Call state:" + state);
        if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
            callStarted();
        } else {
            callEnded();
        }
    }

    private void callStarted() {
        Log.d(MainActivity.TAG, "Call started, activePlayers:"+activePlayers);
        if (MainActivity.isActive() || ControlService.isActive()) {
            inCall = true;
            getActivePlayers();
        } else {
            Log.d(MainActivity.TAG, "App is not currently active");
        }
    }

    private void callEnded() {
        Log.d(MainActivity.TAG, "Call ended, activePlayers:"+activePlayers);
        if (inCall) {
            inCall = false;
            controlPlayers();
        }
        activePlayers.clear();
    }

    private void getActivePlayers() {
        rpc.sendMessage("", new String[]{"material-skin", "activeplayers"}, rpcResponse);
    }

    private void controlPlayers() {
        if (activePlayers.isEmpty()) {
            Log.d(MainActivity.TAG, "Control players NO ACTIVE PLAYERS");
            return;
        }
        String action = prefs.getString(SettingsActivity.ON_CALL_PREF_KEY, DO_NOTHING);
        Log.d(MainActivity.TAG, "Control players, action:" + action + ", active:" + activePlayers + ", current:"+MainActivity.activePlayer);
        if (MUTE_ALL.equals(action) || PAUSE_ALL.equals(action)) {
            for (String id: activePlayers) {
                controlPlayer(action, id);
            }
        } else if (MUTE_CURRENT.equals(action) || PAUSE_CURRENT.equals(action)) {
            for (String id: activePlayers) {
                Log.d(MainActivity.TAG, id+"=="+MainActivity.activePlayer+" ? " + id.equals(MainActivity.activePlayer));

                if (id.equals(MainActivity.activePlayer)) {
                    Log.d(MainActivity.TAG, "Matched ID");
                    controlPlayer(action, id);
                    break;
                }
            }
        }
    }

    private void controlPlayer(String action, String player) {
        Log.d(MainActivity.TAG, action+" on "+player);
        if (MUTE_ALL.equals(action) || MUTE_CURRENT.equals(action)) {
            rpc.sendMessage(player, new String[]{"mixer", "muting", inCall ? "1" : "0"});
        } else if (PAUSE_ALL.equals(action) || PAUSE_CURRENT.equals(action)) {
            rpc.sendMessage(player, new String[]{"pause", inCall ? "1" : "0"});
        }
    }
}
