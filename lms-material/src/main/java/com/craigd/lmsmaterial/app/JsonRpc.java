package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

public class JsonRpc {
    private RequestQueue requestQueue;
    private SharedPreferences prefs ;

    JsonRpc(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        requestQueue = Volley.newRequestQueue(context);
    }

    public boolean sendMessage(String id, String[] command) {
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

                Log.i(MainActivity.TAG, "OnCall: MSG:" + request.toString());
                requestQueue.add(new JsonObjectRequest(Request.Method.POST, "http://" + server.ip + ":" + server.port + "/jsonrpc.js", request, null, null));
            } catch (Exception e) {
                Log.e(MainActivity.TAG, "OnCall: Failed to send control message", e);
            }
            return true;
        }
        return false;
    }
}
