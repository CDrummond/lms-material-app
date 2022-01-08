/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2022 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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

public class PhoneStateReceiver extends BroadcastReceiver {
    public static final String DO_NOTHING = "nothing";
    public static final String MUTE_ALL = "muteall";
    public static final String MUTE_CURRENT = "mutecurrent";
    public static final String PAUSE_ALL = "pauseall";
    public static final String PAUSE_CURRENT = "pausecurrent";

    private SharedPreferences prefs = null;
    private JsonRpc rpc = null;
    private List<String> activePlayers = new LinkedList<String>();
    private boolean inCall = false;

    private Response.Listener<JSONObject> rpcResponse = new Response.Listener<JSONObject> () {
        @Override
        public void onResponse(JSONObject response) {
            activePlayers.clear();
            if (inCall) {
                try {
                    Log.d(MainActivity.TAG, "RESP" + response.toString(4));
                    JSONObject result = response.getJSONObject("result");
                    if (null!=result && result.has("players")) {
                        JSONArray players = result.getJSONArray("players");
                        if (null != players && players.length() > 0) {
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
        }
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"android.intent.action.PHONE_STATE".equals(intent.getAction())) {
            return;
        }
        if (null==prefs) {
            prefs = PreferenceManager.getDefaultSharedPreferences(context);
        }
        if (null==rpc) {
            rpc = new JsonRpc(context);
        }
        String state = intent.getExtras().getString(TelephonyManager.EXTRA_STATE);
        Log.d(MainActivity.TAG, "Call state:" + state);
        if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            callEnded();
        } else if(TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            callStarted();
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
        inCall = false;
        controlPlayers();
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
