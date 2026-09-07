/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

public abstract class ServerDiscovery {
    private static final int SERVER_DISCOVERY_TIMEOUT = 1500;

    public static class Server implements Comparable<Server> {
        public static final int DEFAULT_PORT = 9000;
        public String ip = "";
        public String name = "";
        public int port = DEFAULT_PORT;

        private static String getString(JSONObject json, String key) {
            try {
                return json.getString(key);
            } catch (JSONException e) {
                return "";
            }
        }

        private static int getPort(JSONObject json) {
            try {
                return json.getInt("port");
            } catch (JSONException e) {
                return Server.DEFAULT_PORT;
            }
        }

        public Server(String str) {
            Utils.debug("DECODE:"+str);
            if (str != null) {
                try {
                    JSONObject json = new JSONObject(str);
                    ip = getString(json, "ip");
                    name = getString(json, "name");
                    port = getPort(json);
                } catch (JSONException ignored) {
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
                    Utils.debug("Name:"+name);
                } else if (key.equals("JSON")) {
                    try {
                        port = Integer.parseInt(new String(bytes, i, valueLen));
                        Utils.debug("Port:"+port);
                    } catch (NumberFormatException ignored) {
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

        @Override
        public boolean equals(Object o) {
            if (this==o) {
                return true;
            }
            if (!(o instanceof Server)) {
                return false;
            }
            Server other = (Server)o;
            return port==other.port && Objects.equals(ip, other.ip);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ip, port);
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

        // Returns true if a server was found and discoverAll is false (caller should stop).
        private boolean discoverOnInterface(InetAddress localAddr, InetAddress broadcastAddr, byte[] req) {
            DatagramSocket socket = null;
            try {
                socket = localAddr != null ? new DatagramSocket(0, localAddr) : new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(SERVER_DISCOVERY_TIMEOUT);
                DatagramPacket reqPkt = new DatagramPacket(req, req.length, broadcastAddr, 3483);
                socket.send(reqPkt);
                byte[] resp = new byte[256];
                DatagramPacket respPkt = new DatagramPacket(resp, resp.length);
                for (;;) {
                    try {
                        socket.receive(respPkt);
                        if (resp[0]==(byte)'E') {
                            Server server = new Server(respPkt);
                            if (!servers.contains(server)) {
                                servers.add(server);
                                if (!discoverAll) {
                                    return true;
                                }
                            }
                        }
                    } catch (IOException e) {
                        break;
                    }
                }

            } catch (Exception ignored) {
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
            return false;
        }

        private int inet4ToInt(InetAddress address) {
            byte[] bytes = address.getAddress();
            return ((bytes[0] & 0xff) << 24) |
                   ((bytes[1] & 0xff) << 16) |
                   ((bytes[2] & 0xff) << 8) |
                   (bytes[3] & 0xff);
        }

        private InetAddress intToInet4(int value) {
            byte[] bytes = {
                    (byte)((value >> 24) & 0xff),
                    (byte)((value >> 16) & 0xff),
                    (byte)((value >> 8) & 0xff),
                    (byte)(value & 0xff)
            };
            try {
                return InetAddress.getByAddress(bytes);
            } catch (Exception ignored) {
                return null;
            }
        }

        private boolean isHotspotInterface(NetworkInterface iface) {
            String name = iface.getName();
            if (name == null) {
                return false;
            }
            name = name.toLowerCase();
            return name.startsWith("ap") || name.startsWith("swlan") ||
                    name.startsWith("softap") || name.matches("wlan[1-9].*");
        }

        // Some Android hotspot implementations do not reliably deliver broadcast packets
        // to connected clients. Probe small IPv4 subnets directly as a fallback.
        private boolean discoverOnSubnet(InterfaceAddress addr, byte[] req) {
            InetAddress localAddr = addr.getAddress();
            InetAddress broadcastAddr = addr.getBroadcast();
            int prefixLength = addr.getNetworkPrefixLength();
            if (localAddr == null || broadcastAddr == null || localAddr.getAddress().length != 4 ||
                    prefixLength < 24 || prefixLength > 30) {
                return false;
            }

            DatagramSocket socket = null;
            try {
                int local = inet4ToInt(localAddr);
                int mask = -1 << (32 - prefixLength);
                int network = local & mask;
                int broadcast = inet4ToInt(broadcastAddr);
                socket = new DatagramSocket(0, localAddr);
                socket.setSoTimeout(SERVER_DISCOVERY_TIMEOUT);
                for (int host = network + 1; host < broadcast; ++host) {
                    if (host == local) {
                        continue;
                    }
                    InetAddress target = intToInet4(host);
                    if (target != null) {
                        socket.send(new DatagramPacket(req, req.length, target, 3483));
                    }
                }

                byte[] resp = new byte[256];
                DatagramPacket respPkt = new DatagramPacket(resp, resp.length);
                for (;;) {
                    try {
                        socket.receive(respPkt);
                        if (resp[0]==(byte)'E') {
                            Server server = new Server(respPkt);
                            if (!servers.contains(server)) {
                                servers.add(server);
                                if (!discoverAll) {
                                    return true;
                                }
                            }
                        }
                    } catch (IOException e) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
            return false;
        }

        @Override
        public void run() {
            Utils.debug("Discover LMS servers");

            active = true;
            WifiManager.WifiLock wifiLock = wifiManager.createWifiLock(Utils.LOG_TAG);
            wifiLock.acquire();

            try {
                byte[] req = { 'e', 'I', 'P', 'A', 'D', 0, 'N', 'A', 'M', 'E', 0, 'J', 'S', 'O', 'N', 0 };

                discoverOnInterface(null, InetAddress.getByName("255.255.255.255"), req);

                // If the normal discovery path does not find a server, try the hotspot
                // interface where Android exposes connected clients (swlan0/ap0/softap0).
                List<InterfaceAddress> hotspotCandidates = new ArrayList<>();
                if (servers.isEmpty() || discoverAll) {
                    try {
                        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
                        if (ifaces != null) {
                            while (ifaces.hasMoreElements()) {
                                NetworkInterface iface = ifaces.nextElement();
                                if (!iface.isLoopback() && iface.isUp() && isHotspotInterface(iface)) {
                                    for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                                        if (addr.getBroadcast() != null) {
                                            hotspotCandidates.add(addr);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    for (InterfaceAddress addr : hotspotCandidates) {
                        if (discoverOnInterface(addr.getAddress(), addr.getBroadcast(), req)) {
                            break;
                        }
                    }
                    if (servers.isEmpty() || discoverAll) {
                        for (InterfaceAddress addr : hotspotCandidates) {
                            if (discoverOnSubnet(addr, req)) {
                                break;
                            }
                        }
                    }
                }

            } catch (Exception ignored) {
            } finally {
                Utils.verbose("Scanning complete, unlocking WiFi");
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
            public void handleMessage(@NonNull Message unused) {
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
