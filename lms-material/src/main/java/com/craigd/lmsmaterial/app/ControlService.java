/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2023 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media.VolumeProviderCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.preference.PreferenceManager;

import com.craigd.lmsmaterial.app.cometd.CometClient;
import com.craigd.lmsmaterial.app.cometd.PlayerStatus;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class ControlService extends Service {
    public static final String NO_NOTIFICATION = "none";
    public static final String BASIC_NOTIFICATION = "basic";
    public static final String FULL_NOTIFICATION = "full";
    private static final String NEXT_TRACK = ControlService.class.getCanonicalName() + ".NEXT_TRACK";
    private static final String PREV_TRACK = ControlService.class.getCanonicalName() + ".PREV_TRACK";
    private static final String PLAY_TRACK = ControlService.class.getCanonicalName() + ".PLAY_TRACK";
    private static final String PAUSE_TRACK = ControlService.class.getCanonicalName() + ".PAUSE_TRACK";
    public static final int ACTIVE_PLAYER = 1;
    public static final int PLAYER_REFRESH = 2;

    private static final int MSG_ID = 1;
    private static final String[] PREV_COMMAND = {"button", "jump_rew"};
    private static final String[] PLAY_COMMAND = {"play"};
    private static final String[] PAUSE_COMMAND = {"pause", "1"};
    private static final String[] NEXT_COMMAND = {"playlist", "index", "+1"};
    private static final String[] TOGGLE_PLAY_PAUSE_COMMAND = {"pause"};
    private static final String[] DEC_VOLUME_COMMAND = {"mixer", "volume", "-5"};
    private static final String[] INC_VOLUME_COMMAND = {"mixer", "volume", "+5"};
    public static final String NOTIFICATION_CHANNEL_ID = "lms_control_service";

    private static boolean isRunning = false;

    public static boolean isActive() {
        return isRunning;
    }

    private JsonRpc rpc;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManagerCompat notificationManager;
    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat playbackState;
    private String notificationType = NO_NOTIFICATION;
    private CometClient cometClient = null;
    private SharedPreferences prefs = null;
    private PlayerStatus lastStatus;
    private String currentCover = null;
    private Bitmap currentBitmap = null;
    private Bitmap fallbackBitmap = null;
    private Handler handler;
    private Executor executor= null;
    private ConnectionChangeListener connectionChangeListener;
    private final Messenger messenger = new Messenger(new IncomingHandler(this));

    private static class IncomingHandler extends Handler {
        private final WeakReference<ControlService> serviceRef;
        public IncomingHandler(ControlService service) {
            super(Looper.getMainLooper());
            serviceRef = new WeakReference<>(service);
        }
        @Override
        public void handleMessage(@NonNull Message msg) {
            Utils.debug("Handle message " + msg.what);
            ControlService srv = serviceRef.get();
            if (null==srv) {
                super.handleMessage(msg);
                return;
            }
            if (msg.what == ACTIVE_PLAYER && null!=srv.notificationBuilder && null!=srv.notificationManager) {
                String[] vals = (String[])msg.obj;
                Utils.debug("Set notification player name " + vals[1] + ", id:" + vals[0]);
                srv.notificationBuilder.setContentTitle(vals[1]);
                srv.cometClient.setPlayer(vals[0]);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ActivityCompat.checkSelfPermission(srv.getApplicationContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                srv.updateNotification();
            } else if (msg.what == PLAYER_REFRESH && null!=srv.notificationBuilder && null!=srv.notificationManager) {
                srv.createNotification();
            } else {
                super.handleMessage(msg);
            }
        }
    }

    public static class ConnectionChangeListener extends BroadcastReceiver {
        private final ControlService service;

        ConnectionChangeListener(ControlService service) {
            this.service = service;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.net.conn.CONNECTIVITY_CHANGE".equals(intent.getAction()) && null!=service) {
                service.handler.post(service::networkConnectivityChanged);
            }
        }
    }
    public ControlService() {
        handler = new Handler(Looper.getMainLooper());
    }

    private void networkConnectivityChanged() {
        Utils.debug("");
        if (FULL_NOTIFICATION.equals(notificationType)) {
            if (Utils.isNetworkConnected(this)) {
                cometClient.setPlayer(MainActivity.activePlayer);
                cometClient.connect();
            } else {
                lastStatus = null;
                cometClient.disconnect();
            }
            updateNotification();
        }
    }

    public synchronized void updatePlayerStatus(PlayerStatus status) {
        lastStatus = status;
        handler.post(this::updateNotification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        Utils.debug("");
        return messenger.getBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Utils.debug("");
        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Utils.debug("");
        cometClient = new CometClient(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mediaSession = new MediaSessionCompat(getApplicationContext(), "Lyrion");
        }
        startForegroundService();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Utils.debug("");
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
        }
        stopForegroundService();
    }

    private void sendCommand(String[] command) {
        if (null == MainActivity.activePlayer) {
            return;
        }
        if (null == rpc) {
            rpc = new JsonRpc(getApplicationContext());
        }
        rpc.sendMessage(MainActivity.activePlayer, command);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (PREV_TRACK.equals(action)) {
                sendCommand(PREV_COMMAND);
            } else if (PLAY_TRACK.equals(action)) {
                sendCommand(PLAY_COMMAND);
            } else if (PAUSE_TRACK.equals(action)) {
                sendCommand(PAUSE_COMMAND);
            } else if (NEXT_TRACK.equals(action)) {
                sendCommand(NEXT_COMMAND);
            }
        }
        return super.onStartCommand(intent, flags, startId);
    }

    private void startForegroundService() {
        Utils.debug("");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
        } else {
            notificationBuilder = new NotificationCompat.Builder(this);
        }
        initialiseCometClient();
        createNotification();
        registerCallStateListener();
        isRunning = true;
    }

    private void initialiseCometClient() {
        if (null==prefs) {
            prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        }
        String setting = prefs.getString(SettingsActivity.NOTIFCATIONS_PREF_KEY, NO_NOTIFICATION);
        if (!setting.equals(FULL_NOTIFICATION)) {
            cometClient.disconnect();
            if (null!=connectionChangeListener) {
                unregisterReceiver(connectionChangeListener);
                connectionChangeListener = null;
            }
        } else {
            cometClient.connect();
            if (null==connectionChangeListener) {
                connectionChangeListener = new ConnectionChangeListener(this);
                registerReceiver(connectionChangeListener, new IntentFilter("android.net.conn.CONNECTIVITY_CHANGE"));
            }
        }
        notificationType = setting;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void createNotificationChannel() {
        Utils.debug("");
        notificationManager = NotificationManagerCompat.from(this);
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, getApplicationContext().getResources().getString(R.string.main_notification), NotificationManager.IMPORTANCE_LOW);
        chan.setLightColor(Color.BLUE);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        chan.setShowBadge(false);
        chan.enableLights(false);
        chan.enableVibration(false);
        chan.setSound(null, null);
        notificationManager.createNotificationChannel(chan);
        notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
    }

    @NonNull
    private PendingIntent getPendingIntent(@NonNull String action) {
        Intent intent = new Intent(this, ControlService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, 0, intent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private MediaStyle getMediaStyle() {
        MediaStyle mediaStyle = new MediaStyle();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            mediaStyle.setShowActionsInCompactView(1, 2, 3);
        } else {
            mediaStyle.setMediaSession(mediaSession.getSessionToken());
        }
        return mediaStyle;
    }

    private synchronized Bitmap getFallback() {
        if (null==fallbackBitmap) {
            fallbackBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.notification_image);
        }
        return fallbackBitmap;
    }

    @SuppressLint("MissingPermission")
    private synchronized Notification updateNotification() {
        Utils.debug("");
        if (!Utils.notificationAllowed(this, NOTIFICATION_CHANNEL_ID)) {
            return null;
        }
        try {
            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
            boolean statusValid = false;
            notificationBuilder
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSmallIcon(R.drawable.ic_mono_icon)
                    .setContentTitle(MainActivity.activePlayerName == null || MainActivity.activePlayerName.isEmpty() ? getResources().getString(R.string.no_player) : MainActivity.activePlayerName)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setContentIntent(pendingIntent)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setVibrate(null)
                    .setSound(null)
                    .setShowWhen(false)
                    .setStyle(getMediaStyle())
                    .setChannelId(NOTIFICATION_CHANNEL_ID);

            if (null!=lastStatus && lastStatus.id.equals(MainActivity.activePlayer) && FULL_NOTIFICATION.equals(notificationType)) {
                statusValid = true;
            } else {
                lastStatus = null;
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_prev, "Previous", getPendingIntent(PREV_TRACK)));
                if (!statusValid || !lastStatus.isPlaying) {
                    notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_play, "Play", getPendingIntent(PLAY_TRACK)));
                }
                if (!statusValid || lastStatus.isPlaying) {
                    notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_pause, "Pause", getPendingIntent(PAUSE_TRACK)));
                }
                notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_next, "Next", getPendingIntent(NEXT_TRACK)));
            } else {
                PlaybackStateCompat.Builder playbackStateBuilder = new PlaybackStateCompat.Builder();
                if (statusValid) {
                    playbackStateBuilder.setState(lastStatus.isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_STOPPED, lastStatus.time, lastStatus.isPlaying ? 1.0f : 0)
                                        .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SEEK_TO);
                } else {
                    playbackStateBuilder.setState(PlaybackStateCompat.STATE_STOPPED, 0, 0)
                                        .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT);
                }
                playbackState = playbackStateBuilder.build();
                mediaSession.setPlaybackState(playbackState);
                mediaSession.setCallback(new MediaSessionCompat.Callback() {
                    @Override
                    public void onPlay() {
                        Utils.debug("");
                        if (null!=lastStatus && lastStatus.id.equals(MainActivity.activePlayer) && FULL_NOTIFICATION.equals(notificationType)) {
                            sendCommand(PLAY_COMMAND);
                        } else {
                            mediaSession.setPlaybackState(null);
                            sendCommand(TOGGLE_PLAY_PAUSE_COMMAND);
                            mediaSession.setPlaybackState(playbackState);
                        }
                    }

                    @Override
                    public void onPause() {
                        Utils.debug("");
                        sendCommand(PAUSE_COMMAND);
                    }

                    @Override
                    public void onSkipToNext() {
                        sendCommand(NEXT_COMMAND);
                    }

                    @Override
                    public void onSkipToPrevious() {
                        sendCommand(PREV_COMMAND);
                    }

                    @Override
                    public void onSeekTo(long pos) {
                        sendCommand(new String[]{"time", Double.toString(pos/1000.0)});
                    }
                });
                mediaSession.setPlaybackToRemote(new VolumeProviderCompat(VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 50, 1) {
                    @Override
                    public void onAdjustVolume(int direction) {
                        Utils.debug(""+direction);
                        if (direction > 0) {
                            sendCommand(INC_VOLUME_COMMAND);
                        } else if (direction < 0) {
                            sendCommand(DEC_VOLUME_COMMAND);
                        }
                    }
                });
                String title = MainActivity.activePlayerName == null || MainActivity.activePlayerName.isEmpty() ? getResources().getString(R.string.no_player) : MainActivity.activePlayerName;
                MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder();
                metaBuilder.putString(MediaMetadata.METADATA_KEY_ARTIST, title);

                if (statusValid) {
                    Utils.debug("Full meta data - " + lastStatus.toString());
                    List<String> parts = new LinkedList<>();
                    if (!Utils.isEmpty(lastStatus.title)) {
                        parts.add(lastStatus.title);
                    }
                    if (!Utils.isEmpty(lastStatus.artist)) {
                        parts.add(lastStatus.artist);
                    }
                    //if (!Utils.isEmpty(lastStatus.album)) {
                    //    parts.add(lastStatus.album);
                    //}

                    metaBuilder.putString(MediaMetadata.METADATA_KEY_TITLE, String.join(" • ", parts))
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, lastStatus.duration);
                    if (!Utils.isEmpty(lastStatus.cover)) {
                        if (lastStatus.cover.equals(currentCover)) {
                            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentBitmap);
                        } else {
                            fetchCover(metaBuilder);
                        }
                    }
                } else {
                    metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, getFallback())
                            .putString(MediaMetadata.METADATA_KEY_TITLE, getResources().getString(R.string.notification_meta_text))
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, 0);
                }
                Utils.debug("Set media session title to " + title);
                mediaSession.setMetadata(metaBuilder.build());
                mediaSession.setActive(true);
            }

            Notification notification = notificationBuilder.build();
            Utils.debug("Build notification.");
            notificationManager.notify(MSG_ID, notification);
            return notification;
        } catch (Exception e) {
            Utils.error("Failed to create control notification", e);
        }
        return null;
    }

    private void fetchCover(MediaMetadataCompat.Builder metaBuilder) {
        currentCover = null;
        Utils.debug(lastStatus.cover);
        if (null==executor) {
            executor = Executors.newSingleThreadExecutor();
        }
        executor.execute(() -> {
            try {
                currentBitmap = BitmapFactory.decodeStream(new URL(lastStatus.cover).openStream());
                if (null!=currentBitmap) {
                    currentCover = lastStatus.cover;
                }
                handler.post(() -> {
                    metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, currentBitmap==null ? getFallback() : currentBitmap);
                    mediaSession.setMetadata(metaBuilder.build());
                    mediaSession.setActive(true);
                    updateNotification();
                });
            } catch (Exception e) { Utils.error("Cover error", e); }
        });
    }

    private void createNotification() {
        Utils.debug("");
        Notification notification = updateNotification();
        if (null==notification) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Utils.debug("startForegroundService");
            startForegroundService(new Intent(this, ControlService.class));
        } else {
            Utils.debug("startService");
            startService(new Intent(this, ControlService.class));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Utils.debug("ServiceCompat.startForeground");
            ServiceCompat.startForeground(this, MSG_ID, notification, Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0);
        }
    }

    private void stopForegroundService() {
        Utils.debug("");
        if (mediaSession!=null) {
            mediaSession.setActive(false);
        }
        stopForeground(true);
        unregisterCallStateListener();
        stopSelf();
        isRunning = false;
    }

    @RequiresApi(api = Build.VERSION_CODES.S)
    private static abstract class CallStateListener extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        abstract public void onCallStateChanged(int state);
    }
    private boolean callStateListenerRegistered = false;
    private PhoneStateHandler phoneStateHandler = null;
    private final CallStateListener callStateListener = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ?
            new CallStateListener() {
                @Override
                public void onCallStateChanged(int state) {
                    if (null==phoneStateHandler) {
                        phoneStateHandler = new PhoneStateHandler();
                    }
                    phoneStateHandler.handle(getApplicationContext(), state);
                }
            }
            : null;
    private final PhoneStateListener phoneStateListener = (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) ?
            new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    if (null==phoneStateHandler) {
                        phoneStateHandler = new PhoneStateHandler();
                    }
                    phoneStateHandler.handle(getApplicationContext(), state);
                }
            }
            : null;

    private void registerCallStateListener() {
        if (!callStateListenerRegistered) {
            TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    Utils.debug("calling registerTelephonyCallback");
                    telephonyManager.registerTelephonyCallback(getMainExecutor(), callStateListener);
                }
            } else {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
            callStateListenerRegistered = true;
        }
    }

    private void unregisterCallStateListener() {
        if (callStateListenerRegistered) {
            TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager.unregisterTelephonyCallback(callStateListener);
            } else {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
            callStateListenerRegistered = false;
        }
    }
}
