/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2021 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

abstract class ServerDiscovery {
    private static final int SERVER_DISCOVERY_TIMEOUT = 1500;

    public static class Server implements Comparable<Server> {
        public static final int DEFAULT_PORT = 9000;
        public String ip = "";
        public String name = "";
        public int port = DEFAULT_PORT;

        private static String getString(JSONObject json, String key, String def) {
            try {
                return json.getString(key);
            } catch (JSONException e) {
                return def;
            }
        }

        private static int getInt(JSONObject json, String key, int def) {
            try {
                return json.getInt(key);
            } catch (JSONException e) {
                return def;
            }
        }

        public Server(String str) {
            Log.d(MainActivity.TAG, "DECODE:"+str);
            if (str != null) {
                try {
                    JSONObject json = new JSONObject(str);
                    ip = getString(json, "ip", "");
                    name = getString(json, "name", "");
                    port = getInt(json, "port", DEFAULT_PORT);
                } catch (JSONException e) {
                }
            }
        }

        public Server(String ip, int port, String name) {
            this.ip=ip;
            this.port=port;
            this.name=name;
        }

        public Server(DatagramPacket pkt) {
            ip = pkt.getAddress().getHostAddress();

            // Try to get name of server for packet
            int pktLen = pkt.getLength();
            byte[] bytes = pkt.getData();

            // Look for NAME:<Name> in list of key:value pairs
            for(int i=1; i < pktLen; ) {
                if (i + 5 > pktLen) {
                    break;
                }

                // Extract 4 bytes
                String key = new String(bytes, i, 4);
                i += 4;

                int valueLen = bytes[i++] & 0xFF;
                if (i + valueLen > pktLen) {
                    break;
                }

                if (key.equals("NAME")) {
                    name = new String(bytes, i, valueLen);
                    Log.d(MainActivity.TAG, "Name:"+name);
                } else if (key.equals("JSON")) {
                    try {
                        port = Integer.parseInt(new String(bytes, i, valueLen));
                        Log.d(MainActivity.TAG, "Port:"+port);
                    } catch (NumberFormatException e) {
                    }
                }
                i += valueLen;
            }
        }

        public boolean isEmpty() {
            return null==ip || ip.isEmpty();
        }

        @Override
        public int compareTo(@NonNull Server o) {
            return null==ip ? (o.ip==null ? 0 : -1) : ip.compareTo(o.ip);
        }

        public boolean equals(Server o) {
            return Objects.equals(ip, o.ip);
        }

        public String describe() {
            if (null==name || name.isEmpty()) {
                return address();
            }
            return name+" ("+address()+")";
        }

        public String address() {
            return ip + (DEFAULT_PORT==port ? "" : (":"+port));
        }

        public String encode() {
            try {
                JSONObject json = new JSONObject();
                json.put("ip", ip);
                json.put("name", name);
                json.put("port", port);
                return json.toString(0);
            } catch (JSONException e) {
                return ip;
            }
        }
    }

    class DiscoveryRunnable implements Runnable {
        private volatile boolean active = false;
        private final WifiManager wifiManager;
        private final List<Server> servers = new LinkedList<>();

        DiscoveryRunnable(WifiManager wifiManager) {
            this.wifiManager = wifiManager;
        }

        @Override
        public void run() {
            Log.d(MainActivity.TAG,"Discover LMS servers");

            active = true;
            WifiManager.WifiLock wifiLock;
            DatagramSocket socket = null;
            wifiLock = wifiManager.createWifiLock(MainActivity.TAG);
            wifiLock.acquire();

            try {
                InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
                socket = new DatagramSocket();
                byte[] req = { 'e', 'I', 'P', 'A', 'D', 0, 'N', 'A', 'M', 'E', 0, 'J', 'S', 'O', 'N', 0 };
                DatagramPacket reqPkt = new DatagramPacket(req, req.length, broadcastAddress, 3483);
                byte[] resp = new byte[256];
                DatagramPacket respPkt = new DatagramPacket(resp, resp.length);

                socket.setSoTimeout(SERVER_DISCOVERY_TIMEOUT);
                socket.send(reqPkt);
                for (;;) {
                    try {
                        socket.receive(respPkt);
                        if (resp[0]==(byte)'E') {
                            Server server = new Server(respPkt);
                            if (servers.indexOf(server) < 0) {
                                servers.add(server);
                                if (!discoverAll) {
                                    break; // Stop at first for now...
                                }
                            }
                        }
                    } catch (IOException e) {
                        break;
                    }
                }

            } catch (Exception e) {
            } finally {
                if (socket != null) {
                    socket.close();
                }

                Log.v(MainActivity.TAG, "Scanning complete, unlocking WiFi");
                wifiLock.release();
            }

            handler.sendMessage(new Message());
            active = false;
        }

        public boolean isActive() {
            return active;
        }
    }

    final Context context;
    private final boolean discoverAll;
    private final Handler handler;
    private DiscoveryRunnable runnable;

    ServerDiscovery(Context context, boolean discoverAll) {
        this.context = context;
        this.discoverAll = discoverAll;
        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message unused) {
                discoveryFinished(runnable.servers);
            }
        };
    }

    public void discover() {
        if (runnable!=null && runnable.isActive()) {
            return;
        }
        runnable = new DiscoveryRunnable((WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE));
        Thread thread = new Thread(runnable);
        thread.start();
    }

    protected abstract void discoveryFinished(List<Server> servers);
}
