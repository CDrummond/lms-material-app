/**
 * LMS-Material-App
 *
 * NOTE: This file is *very* much inspired from https://github.com/kaaholst/android-squeezer
 *
 * Apache-2.0 license
 */

package com.craigd.lmsmaterial.app.cometd;

import static com.craigd.lmsmaterial.app.MainActivity.LMS_PASSWORD_KEY;
import static com.craigd.lmsmaterial.app.MainActivity.LMS_USERNAME_KEY;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;

import com.android.volley.Response;
import com.craigd.lmsmaterial.app.ControlService;
import com.craigd.lmsmaterial.app.JsonRpc;
import com.craigd.lmsmaterial.app.MainActivity;
import com.craigd.lmsmaterial.app.ServerDiscovery;
import com.craigd.lmsmaterial.app.SettingsActivity;
import com.craigd.lmsmaterial.app.Utils;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.client.transport.ClientTransport;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.util.B64Code;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public class CometClient {
    private final SharedPreferences prefs;
    final ConnectionState connectionState;
    private SlimClient bayeuxClient;
    private String currentPlayer = null;
    private String subscribedPlayer = null;
    // Keep server details so that we can detect if changed
    private String serverAddress = "";
    private int serverPort = 9000;
    private String serverUser = "";
    private String serverPass = "";
    private final Handler backgroundHandler;
    private ControlService service;
    private JsonRpc rpc;
    private Response.Listener<JSONObject> rpcResponse;
    private int handShakeFailures = 0;

    private static final int MAX_HANDSHAKE_FAILURES = 5;
    private static final String DEFAULT_RADIO_COVER = "/material/html/images/noradio.png";
    private static final String DEFAULT_COVER = "/material/html/images/nocover.png";
    private static final String DEFAULT_WORKS_COVER = "/material/html/images/nowork.png";
    private static final String RANDOMPLAY_COVER = "/material/html/images/randomplay.png";
    private static final String IMAGE_SIZE = "_600x600_f";
    private static final String PLAYER_STATUS_TAGS = "tags:acdlKN";
    private static final int HANDSHAKE_TIMEOUT = 4*1000;
    private static final int MSG_HANDSHAKE_TIMEOUT = 1;
    private static final int MSG_DISCONNECT = 2;
    private static final int MSG_RECONNECT = 3;
    private static final int MSG_SET_PLAYER = 4;
    private static final int MSG_PUBLISH = 5;

    private class PublishListener implements ClientSessionChannel.MessageListener {
        @Override
        public void onMessage(ClientSessionChannel channel, Message message) {
            if (!message.isSuccessful()) {
                if (Message.RECONNECT_HANDSHAKE_VALUE.equals(getAdviceAction(message.getAdvice()))) {
                    Utils.info("rehandshake");
                    bayeuxClient.rehandshake();
                } else {
                    Map<String, Object> failure = getRecord(message, "failure");
                    Exception exception = (failure != null) ? (Exception) failure.get("exception") : null;
                    Utils.warn(channel + ": " + message.getJSON(), exception);
                }
            }
        }
    }

    private static class PublishMessage {
        final Object request;
        final String channel;
        final String responseChannel;
        final PublishListener publishListener;

        private PublishMessage(Object request, String channel, String responseChannel, PublishListener publishListener) {
            this.request = request;
            this.channel = channel;
            this.responseChannel = responseChannel;
            this.publishListener = publishListener;
        }
    }

    private class MessageHandler extends Handler {
        MessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(android.os.Message msg) {
            Utils.debug(""+msg.what);
            switch (msg.what) {
                case MSG_HANDSHAKE_TIMEOUT:
                    Utils.warn("Handshake timeout: " + connectionState);
                    disconnectFromServer();
                    break;
                case MSG_DISCONNECT:
                    removeCallbacksAndMessages(null);
                    disconnectFromServer();
                    break;
                case MSG_RECONNECT:
                    disconnectFromServer();
                    connect();
                    break;
                case MSG_SET_PLAYER:
                    subscribeToPlayer((String)msg.obj);
                    break;
                case MSG_PUBLISH: {
                    PublishMessage message = (PublishMessage) msg.obj;
                    doPublishMessage(message.request, message.channel, message.responseChannel, message.publishListener);
                    break;
                }
                default:
                    break;
            }
        }
    }

    public CometClient(ControlService service) {
        this.service = service;
        prefs = PreferenceManager.getDefaultSharedPreferences(service.getApplicationContext());
        connectionState = new ConnectionState();
        HandlerThread handlerThread = new HandlerThread(CometClient.class.getSimpleName());
        handlerThread.start();
        backgroundHandler = new MessageHandler(handlerThread.getLooper());
    }

    public synchronized void reconnectIfChanged() {
        Utils.debug("");
        ServerDiscovery.Server server = new ServerDiscovery.Server(prefs.getString(SettingsActivity.SERVER_PREF_KEY, null));
        boolean changed = !serverUser.equals(prefs.getString(LMS_USERNAME_KEY, "")) ||
                          !serverPass.equals(prefs.getString(LMS_PASSWORD_KEY, "")) ||
                          serverPort!=server.port || !serverAddress.equals(server.ip);
        if (changed) {
            disconnect(true);
        }
    }

    public synchronized boolean isConnected() {
        return connectionState.isConnected() && null!=bayeuxClient;
    }

    public synchronized void connect() {
        Utils.debug("");
        connectionState.setConnectionState(ConnectionState.State.CONNECTION_STARTED);
        backgroundHandler.post(() -> {
            ServerDiscovery.Server server = new ServerDiscovery.Server(prefs.getString(SettingsActivity.SERVER_PREF_KEY, null));
            if (null == server.ip) {
                connectionState.setConnectionError(ConnectionState.Error.INVALID_URL);
                return;
            }

            final HttpClient httpClient = new HttpClient();
            try {
                httpClient.start();
            } catch (Exception e) {
                connectionState.setConnectionError(ConnectionState.Error.START_CLIENT_ERROR);
                return;
            }

            serverAddress = server.ip;
            serverPort = server.port;
            String url = "http://"+serverAddress+":"+serverPort + "/cometd";
            Utils.debug("CometD URL: " + url);
            ClientTransport clientTransport = new HttpStreamingTransport(url, null, httpClient) {
                @Override
                protected void customize(org.eclipse.jetty.client.api.Request request) {
                    serverUser = prefs.getString(LMS_USERNAME_KEY, "");
                    serverPass= prefs.getString(LMS_PASSWORD_KEY, "");

                    if (!serverUser.isEmpty() && !serverPass.isEmpty()) {
                        request.header(HttpHeader.AUTHORIZATION, "Basic " + B64Code.encode(serverUser + ":" + serverPass));
                    }
                }
            };
            bayeuxClient = new SlimClient(connectionState, url, clientTransport);
            bayeuxClient.addExtension(new BayeuxExtension());
            backgroundHandler.sendEmptyMessageDelayed(MSG_HANDSHAKE_TIMEOUT, HANDSHAKE_TIMEOUT);
            bayeuxClient.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener) (channel, message) -> {
                handShakeFailures = message.isSuccessful() ? 0 : (handShakeFailures+1);
                Utils.debug("Handshake OK: " + message.isSuccessful() + ", canRehandshake: " + connectionState.canRehandshake() + ", failures:" +handShakeFailures);
                if (message.isSuccessful()) {
                    onConnected();
                } else if (handShakeFailures>=MAX_HANDSHAKE_FAILURES && Utils.isNetworkConnected(service)) {
                    Utils.error("Too many handshake errors, aborting");
                    handShakeFailures = 0;
                    try {
                        clientTransport.abort();
                        try {
                            httpClient.stop();
                        } catch (Exception e) {
                            Utils.error("Failed to stop HTTP client", e);
                        }
                        bayeuxClient.stop();
                        bayeuxClient = null;
                        if (!MainActivity.isActive() && !SettingsActivity.isVisible()) {
                            Utils.debug("UI is not visible, so terminate");
                            service.quit();
                        }
                    } catch (Exception e) {
                        Utils.error("Aborting", e);
                    }
                    connectionState.setConnectionState(ConnectionState.State.DISCONNECTED);
                } else if (!connectionState.canRehandshake()) {
                    handShakeFailures = 0;
                    Map<String, Object> failure = getRecord(message, "failure");
                    Message failedMessage = (failure != null) ? (Message) failure.get("message") : message;
                    // Advices are handled internally by the bayeux protocol, so skip these here
                    if (failedMessage != null && getAdviceAction(failedMessage.getAdvice()) == null) {
                        Utils.warn("Unsuccessful message on handshake channel: " + message.getJSON());
                        disconnect();
                    }
                }
            });
            bayeuxClient.getChannel(Channel.META_CONNECT).addListener((ClientSessionChannel.MessageListener) (channel, message) -> {
                Utils.debug("Connect OK? " + message.isSuccessful());
                // Advices are handled internally by the bayeux protocol, so skip these here
                if (!message.isSuccessful() && (getAdviceAction(message.getAdvice()) == null)) {
                    Utils.warn("Unsuccessful message on connect channel: " + message.getJSON());
                    disconnect();
                }
            });
            bayeuxClient.handshake();
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getRecord(Map<String, Object> record, String name) {
        Object rec = record.get(name);
        return rec instanceof Map ? (Map<String, Object>) rec : null;
    }

    private static String getAdviceAction(Map<String, Object> advice) {
        if (advice != null && advice.containsKey(Message.RECONNECT_FIELD)) {
            return (String) advice.get(Message.RECONNECT_FIELD);
        }
        return null;
    }

    public synchronized void setPlayer(String id) {
        currentPlayer = id;
        if (bayeuxClient != null) {
            backgroundHandler.sendMessage(android.os.Message.obtain(null, MSG_SET_PLAYER, id));
        }
    }

    private synchronized void subscribeToPlayer(String id) {
        currentPlayer = id;
        if (null==id) {
            unsubscribePlayer(subscribedPlayer);
        } else if (!id.equals(subscribedPlayer)) {
            unsubscribePlayer(subscribedPlayer);
            subscribePlayer(id);
        }
    }

    public void disconnect() {
        disconnect(false);
    }

    private void disconnect(boolean andReconnect) {
        Utils.debug("connected:"+connectionState.isConnected());
        if (bayeuxClient != null && connectionState.isConnected()) {
            backgroundHandler.sendEmptyMessage(andReconnect ? MSG_RECONNECT : MSG_DISCONNECT);
        }
        connectionState.setConnectionState(ConnectionState.State.DISCONNECTED);
    }

    private synchronized void disconnectFromServer() {
        if (bayeuxClient != null) {
            String[] channels = new String[]{Channel.META_HANDSHAKE, Channel.META_CONNECT};
            for (String channelId : channels) {
                ClientSessionChannel channel = bayeuxClient.getChannel(channelId);
                for (ClientSessionChannel.ClientSessionChannelListener listener : channel.getListeners()) {
                    channel.removeListener(listener);
                }
                channel.unsubscribe();
            }
            bayeuxClient.disconnect();
            bayeuxClient = null;
        }
        subscribedPlayer = null;
    }

    private synchronized void onConnected() {
        Utils.debug("currentPlayer:"+currentPlayer);
        subscribedPlayer = null;
        connectionState.setConnectionState(ConnectionState.State.CONNECTION_COMPLETED);
        bayeuxClient.getChannel("/"+bayeuxClient.getId() + "/slim/playerstatus/*").subscribe(this::handlePlayerStatus);
        subscribeToPlayer(currentPlayer);
        backgroundHandler.removeMessages(MSG_HANDSHAKE_TIMEOUT);
    }

    private void publishMessage(Object request, final String channel, final String responseChannel, final PublishListener publishListener) {
        // Make sure all requests are done in the handler thread
        if (backgroundHandler.getLooper() == Looper.myLooper()) {
            doPublishMessage(request, channel, responseChannel, publishListener);
        } else {
            PublishMessage publishMessage = new PublishMessage(request, channel, responseChannel, publishListener);
            android.os.Message message = backgroundHandler.obtainMessage(MSG_PUBLISH, publishMessage);
            backgroundHandler.sendMessage(message);
        }
    }

    private void doPublishMessage(Object request, String channel, String responseChannel, PublishListener publishListener) {
        Map<String, Object> data = new HashMap<>();
        if (request != null) {
            data.put("request", request);
            data.put("response", responseChannel);
        } else {
            data.put("unsubscribe", responseChannel);
        }
        bayeuxClient.getChannel(channel).publish(data, publishListener);
    }

    private void subscribePlayer(String id) {
        Utils.debug("ID:"+id+", connected:"+connectionState.isConnected());
        if (null!=id && !id.isEmpty() && connectionState.isConnected() && !id.equals(subscribedPlayer)) {
            List<Object> req = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            params.add("status");
            params.add("-");
            params.add("1");
            params.add("subscribe:0");
            params.add(PLAYER_STATUS_TAGS);
            req.add(id);
            req.add(params);
            publishMessage(req, "/slim/subscribe", "/" + bayeuxClient.getId() + "/slim/playerstatus/" + id, new PublishListener() {
                @Override
                public void onMessage(ClientSessionChannel channel, Message message) {
                    super.onMessage(channel, message);
                    if (message.isSuccessful()) {
                        subscribedPlayer = id;
                        getPlayerStatus(subscribedPlayer);
                    }
                }
            });
        }
    }

    private void unsubscribePlayer(String id) {
        Utils.debug("ID:"+id+", connected:"+connectionState.isConnected());
        if (null!=id && !id.isEmpty() && connectionState.isConnected()) {
            if (id.equals(subscribedPlayer)) {
                publishMessage(null, "/slim/subscribe", "/" + bayeuxClient.getId() + "/slim/playerstatus/" + id, new PublishListener() {
                    @Override
                    public void onMessage(ClientSessionChannel channel, Message message) {
                        super.onMessage(channel, message);
                        if (message.isSuccessful()) {
                            subscribedPlayer = null;
                        }
                    }
                });
            } else {
                subscribedPlayer = null;
            }
        }
    }

    private void sendMessage(String id, String[] command) {
        if (null==rpc) {
            rpc = new JsonRpc(service);
            rpcResponse = response -> {
                try {
                    if ("status".equals(command[0])) {
                        handlePlayerStatus(id, response.getJSONObject("result"));
                    }
                } catch (JSONException e) {
                    Utils.error("RPC failed - " + Arrays.toString(command), e);
                }
            };
        }
        rpc.sendMessage(id, command, rpcResponse);
    }

    private void getPlayerStatus(String id) {
        sendMessage(id, new String[]{"status", "-", "1", PLAYER_STATUS_TAGS});
    }

    private String resolveImageUrl(String image) {
        if ((image.contains("http://") || image.contains("https://")) && !(image.startsWith("/imageproxy") || image.startsWith("imageproxy"))) {
            try {
                URL url = new URL(image);
                if (url.getHost().startsWith("192.168.") || url.getHost().startsWith("127.") || url.getHost().endsWith(".local")) {
                    return image;
                }
                return "/imageproxy/" + Utils.encodeURIComponent(image) + "/image" + IMAGE_SIZE;
            } catch (Exception ignored) {
                return image;
            }
        }

        switch (image) {
            case "html/images/cover.png":
                return DEFAULT_COVER;
            case "html/images/radio.png":
                return DEFAULT_RADIO_COVER;
            case "html/images/works.png":
                return DEFAULT_WORKS_COVER;
            case "plugins/RandomPlay/html/images/icon.png":
                return RANDOMPLAY_COVER;
        }
        int idx = image.lastIndexOf(".png");
        if (idx < 0) {
            idx = image.lastIndexOf(".jpg");
        }
        if (idx<0 && image.matches("^[0-9a-fA-F]+$")) {
            image="music/"+image+"/cover"+IMAGE_SIZE;
        } else if (idx>0) {
            if ((image.startsWith("plugins/") || image.startsWith("/plugins/")) && image.indexOf("/html/images/")>0) {
                return image;
            }
            image = image.substring(0, idx)+IMAGE_SIZE+image.substring(idx);
        }
        return image.startsWith("/") ? image : ("/"+image);
    }

    private String coverUrl(String path) {
        return path.startsWith("http") ? path : ("http://"+serverAddress+":"+serverPort + (path.startsWith("/") ? path : ("/"+path)));
    }

    private void handlePlayerStatus(String id, String mode, String remote_title, String artist, String album, String title,
                                    String artwork_url, String coverid, long duration, long time) {
        PlayerStatus status = new PlayerStatus();
        status.id = id;
        status.timestamp = SystemClock.elapsedRealtime();
        status.artist = artist;
        status.album = album;
        if (!Utils.isEmpty(remote_title) && (!remote_title.startsWith("http") || Utils.isEmpty(title))) {
           status.title = remote_title;
        } else {
            status.title = title;
        }
        status.duration = duration;
        status.time = "stop".equals(mode) ? 0 : time;
        status.isPlaying = "play".equals(mode);
        if (!Utils.isEmpty(artwork_url)) {
            String resolved = resolveImageUrl(artwork_url);
            if (!Utils.isEmpty(resolved)) {
                status.cover = coverUrl(resolved);
            }
        }
        if (Utils.isEmpty(status.cover) && !Utils.isEmpty(coverid)) {
            status.cover = coverUrl("/music/"+coverid+"/cover"+IMAGE_SIZE);
        }
        Utils.debug(status.toString());
        service.updatePlayerStatus(status);
    }

    private float parseFloat(Object val) {
        if (null==val) {
            return 0.0f;
        }
        if (val instanceof Float) {
            return (Float)val;
        }
        if (val instanceof Double) {
            return ((Double)val).floatValue();
        }
        if (val instanceof String) {
            try {
                return Float.parseFloat((String) val);
            } catch (NumberFormatException ignored) { }
        }
        return 0.0f;
    }

    private String getString(JSONObject json, String key) {
        try {
            return json.getString(key);
        } catch (Exception ignored) {
            return "";
        }
    }

    private float getFloat(JSONObject json, String key) {
        try {
            return (float) json.getDouble(key);
        } catch (Exception ignored) {
            return parseFloat(getString(json, key));
        }
    }

    private synchronized void handlePlayerStatus(String id, JSONObject response) {
        Utils.verbose("JSON " + id);
        if (!Objects.equals(id, currentPlayer)) {
            return;
        }
        JSONArray playlist_loop = null;
        try {
            playlist_loop = response.getJSONArray("playlist_loop");
        } catch (JSONException ignored) { }
        if (playlist_loop!=null && playlist_loop.length()>0) {
            JSONObject track = null;
            try {
                track = playlist_loop.getJSONObject(0);
            } catch (JSONException ignored) { }
            if (null!=track) {
                handlePlayerStatus(id,
                        getString(response, "mode"),
                        getString(track, "remote_title"),
                        getString(track, "artist"),
                        getString(track, "album"),
                        getString(track, "title"),
                        getString(track, "artwork_url"),
                        getString(track, "coverid"),
                        (long) (getFloat(track, "duration") * 1000.0f),
                        (long) (getFloat(response, "time") * 1000.0f));
                return;
            }
        }
        handlePlayerStatus(id, getString(response, "mode"), null, null, null, null, null, null, 0, 0);
    }

    @SuppressWarnings("unchecked")
    private synchronized void handlePlayerStatus(ClientSessionChannel channel, Message message) {
        String[] parts = message.getChannel().split("/");
        String playerId = parts[parts.length - 1];
        Utils.verbose("CometD " + playerId);

        if (!Objects.equals(playerId, currentPlayer)) {
            return;
        }

        Map<String, Object> messageData = message.getDataAsMap();
        Object[] playlist_loop = (Object[]) messageData.get("playlist_loop");

        if (playlist_loop!=null && playlist_loop.length>0) {
            Map<String, Object> track = (Map<String, Object>)playlist_loop[0];
            handlePlayerStatus(playerId,
                    (String)messageData.get("mode"),
                    (String)track.get("remote_title"),
                    (String)track.get("artist"),
                    (String)track.get("album"),
                    (String)track.get("title"),
                    (String)track.get("artwork_url"),
                    (String)track.get("coverid"),
                    (long)(parseFloat(track.get("duration"))*1000.0f),
                    (long)(parseFloat(messageData.get("time"))*1000.0f));
        } else {
            handlePlayerStatus(playerId, (String)messageData.get("mode"), null, null, null, null, null, null, 0, 0);
        }
    }
}