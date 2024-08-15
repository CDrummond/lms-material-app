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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import com.craigd.lmsmaterial.app.ControlService;
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

import java.util.List;
import java.util.Map;
import java.util.Objects;


public class CometClient {
    private final SharedPreferences prefs;
    final ConnectionState connectionState;
    private SlimClient bayeuxClient;
    private String currentPlayer = null;
    private final Handler backgroundHandler;

    private static final int HANDSHAKE_TIMEOUT = 4*1000;
    private static final int MSG_HANDSHAKE_TIMEOUT = 1;
    private static final int MSG_DISCONNECT = 2;
    private static final int MSG_SET_PLAYER = 3;


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
                    disconnectFromServer();
                case MSG_SET_PLAYER:
                    subscribeToPlayer((String)msg.obj);
                default:
                    break;
            }
        }
    }

    public CometClient(Context context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
        connectionState = new ConnectionState();
        HandlerThread handlerThread = new HandlerThread(CometClient.class.getSimpleName());
        handlerThread.start();
        backgroundHandler = new MessageHandler(handlerThread.getLooper());
    }

    public synchronized void connect() {
        Utils.debug("");
        disconnect();
        currentPlayer = null;
        connectionState.setAutoConnect();
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

            String url = "http://" + server.ip + ":" + server.port + "/cometd";
            Utils.debug("CometD URL: " + url);
            ClientTransport clientTransport = new HttpStreamingTransport(url, null, httpClient) {
                @Override
                protected void customize(org.eclipse.jetty.client.api.Request request) {
                    String user = prefs.getString(LMS_USERNAME_KEY, "");
                    String pass = prefs.getString(LMS_PASSWORD_KEY, "");

                    if (!user.isEmpty() && !pass.isEmpty()) {
                        request.header(HttpHeader.AUTHORIZATION, "Basic " + B64Code.encode(user + ":" + pass));
                    }
                }
            };
            bayeuxClient = new SlimClient(connectionState, url, clientTransport);
            backgroundHandler.sendEmptyMessageDelayed(MSG_HANDSHAKE_TIMEOUT, HANDSHAKE_TIMEOUT);
            bayeuxClient.getChannel(Channel.META_HANDSHAKE).addListener((ClientSessionChannel.MessageListener) (channel, message) -> {
                if (message.isSuccessful()) {
                    onConnected();
                } else if (!connectionState.canRehandshake()) {
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
                // Advices are handled internally by the bayeux protocol, so skip these here
                if (!message.isSuccessful() && (getAdviceAction(message.getAdvice()) == null)) {
                    Utils.warn("Unsuccessful message on connect channel: " + message.getJSON());
                    disconnect();
                }
            });
            bayeuxClient.handshake();
        });
    }

    public static Map<String, Object> getRecord(Map<String, Object> record, String recordName) {
        return (Map<String, Object>) record.get(recordName);
    }

    private static String getAdviceAction(Map<String, Object> advice) {
        if (advice != null && advice.containsKey(Message.RECONNECT_FIELD)) {
            return (String) advice.get(Message.RECONNECT_FIELD);
        }
        return null;
    }

    public synchronized void setPlayer(String id) {
        if (bayeuxClient != null) {
            backgroundHandler.sendMessage(android.os.Message.obtain(null, MSG_SET_PLAYER, id));
        } else {
            currentPlayer = id;
        }
    }

    private synchronized void subscribeToPlayer(String id) {
        if (null==id) {
            unsubscribe(currentPlayer);
        } else if (!id.equals(currentPlayer)) {
            unsubscribe(currentPlayer);
            subscribe(id);
        }
        currentPlayer = id;
    }

    public void disconnect() {
        Utils.debug("");
        if (bayeuxClient != null) {
            backgroundHandler.sendEmptyMessage(MSG_DISCONNECT);
        }
        connectionState.setConnectionState(ConnectionState.State.DISCONNECTED);
    }

    private synchronized void disconnectFromServer() {
        if (bayeuxClient != null) {
            for (String channelId: List.of(Channel.META_HANDSHAKE, Channel.META_CONNECT)) {
                ClientSessionChannel channel = bayeuxClient.getChannel(channelId);
                for (ClientSessionChannel.ClientSessionChannelListener listener : channel.getListeners()) {
                    channel.removeListener(listener);
                }
                channel.unsubscribe();
            }
            unsubscribe(currentPlayer);
            bayeuxClient.disconnect();
            bayeuxClient = null;
        }
        currentPlayer = null;
    }

    private void subscribe(String id) {
        Utils.debug(id);
        if (null!=id && !id.isEmpty() && connectionState.isConnected()) {
            bayeuxClient.getChannel("/"+bayeuxClient.getId() + "/slim/playerstatus/" + id).subscribe(this::handlePlayerStatus);
        }
    }
    private void unsubscribe(String id) {
        Utils.debug(id);
        if (null!=id && !id.isEmpty() && connectionState.isConnected()) {
            bayeuxClient.getChannel("/"+bayeuxClient.getId() + "/slim/playerstatus/" + id).unsubscribe();
        }
    }
    private synchronized void onConnected() {
        connectionState.setConnectionState(ConnectionState.State.CONNECTION_COMPLETED);
        subscribe(currentPlayer);
        backgroundHandler.removeMessages(MSG_HANDSHAKE_TIMEOUT);
    }

    private synchronized void handlePlayerStatus(ClientSessionChannel channel, Message message) {
        Utils.debug("");
        String[] parts = message.getChannel().split("/");
        String playerId = parts[parts.length - 1];
        Utils.verbose(playerId);

        if (!Objects.equals(playerId, currentPlayer)) {
            return;
        }

        Map<String, Object> messageData = message.getDataAsMap();
        // TODO: Update notification...
    }
}