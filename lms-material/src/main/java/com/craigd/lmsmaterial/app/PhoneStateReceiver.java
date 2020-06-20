package com.craigd.lmsmaterial.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.preference.PreferenceManager;

public class PhoneStateReceiver extends BroadcastReceiver {
    public static final String DO_NOTHING = "nothing";
    public static final String MUTE_ALL = "muteall";
    public static final String MUTE_ACTIVE = "muteactive";
    public static final String PAUSE_ALL = "pauseall";
    public static final String PAUSE_ACTIVE = "pauseactive";

    private TelephonyManager telephony;
    private static boolean pausedOrMutedPlayers = false;
    private SharedPreferences prefs ;
    private JsonRpc rpc;

    private final PhoneStateListener phoneListener = new PhoneStateListener() {
        public void onCallStateChanged(int state, String incomingNumber) {
            switch (state) {
                case TelephonyManager.CALL_STATE_IDLE:
                    Log.d(MainActivity.TAG, "OnCall: Idle, pausedOrMutedPlayers:"+pausedOrMutedPlayers);
                    if (pausedOrMutedPlayers && sendMessage(false)) {
                        pausedOrMutedPlayers = false;
                        sendMessage(false);
                    }
                    break;
                case TelephonyManager.CALL_STATE_OFFHOOK:
                    Log.d(MainActivity.TAG, "OnCall: OffHook, pausedOrMutedPlayers:"+pausedOrMutedPlayers);
                    if (!pausedOrMutedPlayers) {
                        pausedOrMutedPlayers = sendMessage(true);
                    }
                    break;
                case TelephonyManager.CALL_STATE_RINGING:
                    Log.d(MainActivity.TAG, "OnCall: Ringing, pausedOrMutedPlayers:"+pausedOrMutedPlayers);
                    break;
            }
        }
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        telephony.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE);
        rpc = new JsonRpc(context);
    }

    private boolean sendMessage(boolean inCall) {
        ServerDiscovery.Server server = new ServerDiscovery.Server(prefs.getString(SettingsActivity.SERVER_PREF_KEY,null));
        if (null!=server.ip) {
            String action = prefs.getString(SettingsActivity.ON_CALL_PREF_KEY, DO_NOTHING);
            String player = "";
            String[] command;

            if (MUTE_ALL.equals(action)) {
                command=new String[]{"material-skin", "playercontrol", "action:muting", "val:"+(inCall ? "1" : "0")};
            } else if (MUTE_ACTIVE.equals(action)) {
                if (MainActivity.activePlayer==null) {
                    Log.e(MainActivity.TAG, "OnCall: No current player stored");
                    return false;
                }
                command=new String[]{"mixer", "muting", inCall ? "1" : "0"};
                player=MainActivity.activePlayer;
            } else if (PAUSE_ALL.equals(action)) {
                command=new String[]{"material-skin", "playercontrol", "action:pause", "val:"+(inCall ? "1" : "0")};
            } else if (PAUSE_ACTIVE.equals(action)) {
                if (MainActivity.activePlayer==null) {
                    Log.e(MainActivity.TAG, "OnCall: No current player stored");
                    return false;
                }
                command=new String[]{"pause", inCall ? "1" : "0"};
                player=MainActivity.activePlayer;
            } else {
                return false;
            }
            return rpc.sendMessage(player, command);
        }
        return false;
    }
}
