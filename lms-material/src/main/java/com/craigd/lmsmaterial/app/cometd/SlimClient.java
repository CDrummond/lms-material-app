/**
 * LMS-Material-App
 *
 * NOTE: This file copied from https://github.com/kaaholst/android-squeezer
 *
 * Apache-2.0 license
 */

package com.craigd.lmsmaterial.app.cometd;

import android.util.Log;

import com.craigd.lmsmaterial.app.Utils;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.client.BayeuxClient;
import org.cometd.client.transport.ClientTransport;
import org.cometd.common.HashMapMessage;

import java.io.IOException;
import java.util.List;

public class SlimClient extends BayeuxClient {
    private final ConnectionState connectionState;

    SlimClient(ConnectionState connectionState, String url, ClientTransport transport, ClientTransport... transports) {
        super(url, transport, transports);
        this.connectionState = connectionState;
    }

    @Override
    public void onSending(List<? extends Message> messages) {
        super.onSending(messages);
        for (Message message : messages) {
            Utils.verbose(message.getJSON());
        }
    }

    @Override
    public void onMessages(List<Message.Mutable> messages) {
        super.onMessages(messages);
        for (Message message : messages) {
            Utils.verbose(message.getJSON());
        }
    }

    @Override
    public void onFailure(Throwable failure, List<? extends Message> messages) {
        super.onFailure(failure, messages);
        for (Message message : messages) {
            Utils.error(message.getJSON(), failure);
        }
        if (failure instanceof IOException && connectionState.isConnected()) {
            rehandshake();
        }
    }

    public void rehandshake() {
        Utils.info("");
        connectionState.setConnectionState(ConnectionState.State.REHANDSHAKING);
        HashMapMessage message = new HashMapMessage();
        message.setId(newMessageId());
        message.setSuccessful(false);
        message.setChannel(Channel.META_HANDSHAKE);
        message.getAdvice(true).put(Message.RECONNECT_FIELD, Message.RECONNECT_HANDSHAKE_VALUE);
        message.setClientId(getId());
        processHandshake(message);
    }
}