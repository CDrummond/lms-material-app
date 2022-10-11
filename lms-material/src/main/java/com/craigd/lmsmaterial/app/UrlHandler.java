/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2022 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.android.volley.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class UrlHandler {
    private static final String SHARE_TO_PLAYER_KEY = "share_to_player";
    private Activity activity;
    private JsonRpc rpc;
    private String handlingUrl;
    private SharedPreferences sharedPreferences;

    private static class Player implements Comparable {
        public Player(String name, String id) {
            this.name = name;
            this.id = id;
        }
        public String name;
        public String id;

        @Override
        public int compareTo(Object o) {
            return name.compareToIgnoreCase(((Player)o).name);
        }
    }

    private Dialog dialog;
    Spinner player_name;
    private List<Player> playerList = new LinkedList<>();
    private int chosenPlayer = 0;

    private Response.Listener<JSONObject> rpcResponse = new Response.Listener<JSONObject> () {
        @Override
        public void onResponse(JSONObject response) {
            playerList.clear();
            try {
                Log.d(MainActivity.TAG, "RESP" + response.toString(4));
                JSONObject result = response.getJSONObject("result");
                if (null!=result && result.has("players_loop")) {
                    JSONArray players = result.getJSONArray("players_loop");
                    if (null != players && players.length() > 0) {
                        for (int i = 0; i < players.length(); ++i) {
                            JSONObject obj = players.getJSONObject(i);
                            playerList.add(new Player(obj.getString("name"), obj.getString("playerid")));
                        }
                        Log.d(MainActivity.TAG, "RPC response, numPlayers:" + playerList.size());
                    }
                }
            } catch (JSONException e) {
                Log.e(MainActivity.TAG, "Failed to parse response", e);
            }
            if (playerList.isEmpty()) {
                return;
            }
            Collections.sort(playerList);

            // Create dialog
            if (null==dialog) {
                AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                LayoutInflater inflater = activity.getLayoutInflater();
                View view = inflater.inflate(R.layout.url_handler, null);

                builder.setView(view)
                        .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.dismiss();
                            }
                        });
                dialog = builder.create();
                player_name = (Spinner) view.findViewById(R.id.player_name);

                Button play_now = (Button) view.findViewById(R.id.play_now_button);
                Button play_next = (Button) view.findViewById(R.id.play_next_button);
                Button insert = (Button) view.findViewById(R.id.insert_button);

                play_now.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        dialog.dismiss();
                        addUrlToPlayer("play");
                    }
                });
                play_next.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        dialog.dismiss();
                        addUrlToPlayer("add");
                    }
                });
                insert.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        dialog.dismiss();
                        addUrlToPlayer("insert");
                    }
                });
                player_name.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        chosenPlayer = position;
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
            }

            // Initialise items
            ArrayList<String> player_names = new ArrayList<>();
            for (Player player: playerList) {
                player_names.add(player.name);
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, player_names);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            player_name.setAdapter(adapter);

            chosenPlayer = 0;
            if (null==sharedPreferences) {
                sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity);
            }
            String id = sharedPreferences.getString(SHARE_TO_PLAYER_KEY, null);
            if (null!=id && !id.isEmpty()) {
                for (int i=0; i<playerList.size(); ++i) {
                    if (playerList.get(i).id.equals(id)) {
                        chosenPlayer = i;
                        break;
                    }
                }
            }
            player_name.setSelection(chosenPlayer);

            // Show dialog
            dialog.show();
        }
    };

    public UrlHandler(Activity activity) {
        this.activity = activity;
    }

    public synchronized void handle(String url) {
        Log.d(MainActivity.TAG, "Shared URL:" + url);
        if (null==rpc) {
            rpc = new JsonRpc(activity);
        }
        handlingUrl = url;
        rpc.sendMessage("", new String[]{"serverstatus", "0", "100"}, rpcResponse);
    }

    private synchronized void addUrlToPlayer(String action) {
        if (chosenPlayer<0 || chosenPlayer>=playerList.size()) {
            return;
        }
        Player player = playerList.get(chosenPlayer);
        Log.d(MainActivity.TAG, action+": "+handlingUrl+", to: "+player.name);
        rpc.sendMessage(player.id, new String[]{"playlist", action, handlingUrl});
        handlingUrl = null;

        // Save ID for next time...
        if (!player.id.equals(sharedPreferences.getString(SHARE_TO_PLAYER_KEY, null))) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putString(SHARE_TO_PLAYER_KEY, player.id);
            editor.apply();
        }
    }
}
