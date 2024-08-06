/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2023 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.app.Dialog;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import androidx.appcompat.app.AlertDialog;

import com.android.volley.Response;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class UrlHandler {
    private final MainActivity mainActivity;
    private JsonRpc rpc;
    private String handlingUrl;
    private Dialog dialog;
    private Spinner playerName;
    private final List<Player> playerList = new LinkedList<>();
    private int chosenPlayer = 0;

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

    private final Response.Listener<JSONObject> serverStatusResponse = new Response.Listener<JSONObject> () {
        @Override
        public void onResponse(JSONObject response) {
            playerList.clear();
            try {
                Utils.debug("RESP" + response.toString(4));
                JSONObject result = response.getJSONObject("result");
                if (result.has("players_loop")) {
                    JSONArray players = result.getJSONArray("players_loop");
                    if (players.length() > 0) {
                        for (int i = 0; i < players.length(); ++i) {
                            JSONObject obj = players.getJSONObject(i);
                            playerList.add(new Player(obj.getString("name"), obj.getString("playerid")));
                        }
                        Utils.debug("RPC response, numPlayers:" + playerList.size());
                    }
                }
            } catch (JSONException e) {
                Utils.error( "Failed to parse response", e);
            }
            if (playerList.isEmpty()) {
                return;
            }
            Collections.sort(playerList);

            // Create dialog
            if (null==dialog) {
                AlertDialog.Builder builder = new AlertDialog.Builder(mainActivity);
                LayoutInflater inflater = mainActivity.getLayoutInflater();
                View view = inflater.inflate(R.layout.url_handler, null);

                builder.setView(view)
                        .setNegativeButton(R.string.cancel, (dialog, id) -> dialog.dismiss());
                dialog = builder.create();
                playerName = view.findViewById(R.id.player_name);

                Button play_now = view.findViewById(R.id.play_now_button);
                Button play_next = view.findViewById(R.id.play_next_button);
                Button insert = view.findViewById(R.id.insert_button);

                play_now.setOnClickListener(view1 -> {
                    dialog.dismiss();
                    addUrlToPlayer("play");
                });
                play_next.setOnClickListener(view12 -> {
                    dialog.dismiss();
                    addUrlToPlayer("add");
                });
                insert.setOnClickListener(view13 -> {
                    dialog.dismiss();
                    addUrlToPlayer("insert");
                });
                playerName.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
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
            ArrayAdapter<String> adapter = new ArrayAdapter<>(mainActivity, android.R.layout.simple_spinner_item, player_names);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            playerName.setAdapter(adapter);

            chosenPlayer = 0;
            if (null!=MainActivity.activePlayer) {
                for (int i=0; i<playerList.size(); ++i) {
                    if (playerList.get(i).id.equals(MainActivity.activePlayer)) {
                        chosenPlayer = i;
                        break;
                    }
                }
            }
            playerName.setSelection(chosenPlayer);

            // Show dialog
            dialog.show();
        }
    };

    private final Response.Listener<JSONObject> addActionResponse = new Response.Listener<JSONObject> () {
        @Override
        public void onResponse(JSONObject response) {
            if (chosenPlayer>=0 && chosenPlayer<playerList.size()) {
                mainActivity.setPlayer(playerList.get(chosenPlayer).id);
            }
        }
    };

    public UrlHandler(MainActivity mainActivity) {
        this.mainActivity = mainActivity;
    }

    public synchronized void handle(String url) {
        Utils.debug("Shared URL:" + url);
        if (null==rpc) {
            rpc = new JsonRpc(mainActivity);
        }
        handlingUrl = url;
        rpc.sendMessage("", new String[]{"serverstatus", "0", "100"}, serverStatusResponse);
    }

    private synchronized void addUrlToPlayer(String action) {
        if (chosenPlayer<0 || chosenPlayer>=playerList.size()) {
            return;
        }
        Player player = playerList.get(chosenPlayer);
        Utils.debug(action+": "+handlingUrl+", to: "+player.name);
        rpc.sendMessage(player.id, new String[]{"playlist", action, handlingUrl}, addActionResponse);
        handlingUrl = null;
    }
}
