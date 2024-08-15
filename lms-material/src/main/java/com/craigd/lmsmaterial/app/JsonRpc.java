/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2023 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import static com.craigd.lmsmaterial.app.MainActivity.LMS_PASSWORD_KEY;
import static com.craigd.lmsmaterial.app.MainActivity.LMS_USERNAME_KEY;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.B64Code;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class JsonRpc {
    private final RequestQueue requestQueue;
    private final SharedPreferences prefs ;

    private class Request extends JsonObjectRequest {
        public Request(String url, @Nullable JSONObject request, Response.Listener<JSONObject> responseListener) {
            super(Request.Method.POST, url, request, responseListener, null);
        }

        @Override
        public Map<String, String> getHeaders() throws AuthFailureError {
            String user = prefs.getString(LMS_USERNAME_KEY, "");
            String pass = prefs.getString(LMS_PASSWORD_KEY, "");

            if (user.isEmpty() || pass.isEmpty()) {
                return  super.getHeaders();
            }

            Map<String, String> headers = super.getHeaders();
            if (null==headers) {
                headers = new HashMap<>();
                headers.put("Authorization", "Basic " + B64Code.encode(user + ":" + pass));
            }
            return headers;
        }
    };

    public JsonRpc(Context context) {
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
                requestQueue.add(new Request("http://" + server.ip + ":" + server.port + "/jsonrpc.js", request, responseListener));
            } catch (Exception e) {
                Utils.error("Failed to send control message", e);
            }
        }
    }
}
