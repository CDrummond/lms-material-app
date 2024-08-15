/**
 * LMS-Material-App
 *
 * NOTE: This file copied from https://github.com/kaaholst/android-squeezer
 *
 * Apache-2.0 license
 */

package com.craigd.lmsmaterial.app.cometd;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.craigd.lmsmaterial.app.Utils;


public class ConnectionState  {
    ConnectionState() {
    }

    public enum Error {
        START_CLIENT_ERROR,
        INVALID_URL,
        LOGIN_FALIED,
        CONNECTION_ERROR;
    }

    public enum State {
        /** User disconnected */
        MANUAL_DISCONNECT,
        /** Ordinarily disconnected from the server. */
        DISCONNECTED,
        /** A connection has been started. */
        CONNECTION_STARTED,
        /** The connection to the server did not complete. */
        CONNECTION_FAILED,
        /** The connection to the server completed, the handshake can start. */
        CONNECTION_COMPLETED,
        /** Currently trying to reestablish a previously working connection. */
        REHANDSHAKING;

        boolean isConnected() {
            return (this == CONNECTION_COMPLETED);
        }

        boolean isConnectInProgress() {
            return (this == CONNECTION_STARTED);
        }

        /**
         * @return True if the socket connection to the server has started, but not yet
         *     completed (successfully or unsuccessfully).
         */
        boolean isRehandshaking() {
            return (this == REHANDSHAKING);
        }
    }

    private volatile State state = State.DISCONNECTED;

    /** Minimum milliseconds between automatic connection */
    private static final long AUTO_CONNECT_INTERVAL = 60_000;

    /** Milliseconds since boot of latest start of rehandshake */
    private volatile long rehandshake;

    /** Duration before we give up rehandshake */
    private static final long REHANDSHAKE_TIMEOUT = 15 * 60_000;

    /**
     * Sets a new connection state, and posts a sticky
     * {@link uk.org.ngo.squeezer.service.event.ConnectionChanged} event with the new state.
     *
     * @param connectionState The new connection state.
     */
    void setConnectionState(State connectionState) {
        Utils.info(state + " => " + connectionState);
        updateConnectionState(connectionState);
        //TODO mEventBus.postSticky(new ConnectionChanged(connectionState));
    }

    void setConnectionError(Error connectionError) {
        Utils.info(state + " => " + connectionError);
        updateConnectionState(State.CONNECTION_FAILED);
        //TODO mEventBus.postSticky(new ConnectionChanged(connectionError));
    }

    private void updateConnectionState(State connectionState) {
        // Clear data if we were previously connected
        if (isConnected() && !connectionState.isConnected()) {
            // TODO mEventBus.removeAllStickyEvents();
        }

        // Start timer for rehandshake
        if (connectionState == State.REHANDSHAKING) {
            rehandshake = SystemClock.elapsedRealtime();
        }
        state = connectionState;
    }

    boolean isConnected() {
        return state.isConnected();
    }

    boolean isConnectInProgress() {
        return state.isConnectInProgress();
    }

    boolean isRehandshaking() {
        return state.isRehandshaking();
    }

    boolean canRehandshake() {
        return isRehandshaking()
                && ((SystemClock.elapsedRealtime() - rehandshake) < REHANDSHAKE_TIMEOUT);
    }

    @NonNull
    @Override
    public String toString() {
        return ""+state;
    }
}
