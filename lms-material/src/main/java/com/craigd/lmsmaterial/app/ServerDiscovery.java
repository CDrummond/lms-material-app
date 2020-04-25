package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.LinkedList;
import java.util.List;

public abstract class ServerDiscovery {
    private static final String TAG = "LMS";
    private static final int SERVER_DISCOVERY_TIMEOUT = 1500;

    public class DiscoveryRunnable implements Runnable {
        private volatile boolean active = false;
        private volatile boolean cancelled;
        private WifiManager wifiManager;
        private List<String> servers = new LinkedList<>();

        public DiscoveryRunnable(WifiManager wifiManager) {
            this.wifiManager = wifiManager;
        }

        @Override
        public void run() {
            Log.d("MSK","Discover LMS servers");

            active = true;
            WifiManager.WifiLock wifiLock;
            DatagramSocket socket = null;
            wifiLock = wifiManager.createWifiLock("MSK");
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
                    if (cancelled) {
                        break;
                    }
                    try {
                        socket.receive(respPkt);
                        if (resp[0]==(byte)'E') {
                            String server = respPkt.getAddress().getHostAddress();
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

                Log.v(TAG, "Scanning complete, unlocking WiFi");
                wifiLock.release();
            }

            handler.sendMessage(new Message());
            active = false;
        }

        public void cancel() {
            cancelled = true;
        }

        public boolean isActive() {
            return active;
        }
    }

    protected Context context;
    private boolean discoverAll;
    private Handler handler;
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
        runnable = new DiscoveryRunnable((WifiManager) context.getSystemService(Context.WIFI_SERVICE));
        Thread thread = new Thread(runnable);
        thread.start();
    }

    public abstract void discoveryFinished(List<String> servers);
}
