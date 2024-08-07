/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2023 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

public class JsonRpc {
    private final RequestQueue requestQueue;
    private final SharedPreferences prefs ;

    JsonRpc(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        requestQueue = Volley.newRequestQueue(context);
    }

    public void sendMessage(String id, String[] command) {
        sendMessage(id, command, null);
    }

    public void sendMessage(String id, String[] command, Response.Listener<JSONObject> responseListener) {
        ServerDiscovery.Server server = new ServerDiscovery.Server(prefs.getString(SettingsActivity.SERVER_PREF_KEY,null));
        if (null!=server.ip) {
            try {
                JSONObject request = new JSONObject();
                JSONArray params = new JSONArray();
                JSONArray cmd = new JSONArray();
                params.put(0, id);
                for (String c : command) {
                    cmd.put(cmd.length(), c);
                }
                params.put(1, cmd);
                request.put("id", 1);
                request.put("method", "slim.request");
                request.put("params", params);

                Utils.info("MSG:" + request);
                requestQueue.add(new JsonObjectRequest(Request.Method.POST, "http://" + server.ip + ":" + server.port + "/jsonrpc.js", request, responseListener, null));
            } catch (Exception e) {
                Utils.error("Failed to send control message", e);
            }
        }
    }
}
