/**
 * LMS-Material-App
 *
 * NOTE: This file copied from https://github.com/kaaholst/android-squeezer
 *
 * Apache-2.0 license
 */

package com.craigd.lmsmaterial.app.cometd;

import com.craigd.lmsmaterial.app.Utils;

import org.cometd.bayeux.Channel;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSession;

public class BayeuxExtension extends ClientSession.Extension.Adapter {
    @Override
    public boolean sendMeta(ClientSession session, Message.Mutable message) {
        if (Channel.META_HANDSHAKE.equals(message.getChannel())) {
            if (message.getClientId() != null) {
                Utils.verbose("Reset client id");
                message.setClientId(null);
            }
        }
        return true;
    }
}
