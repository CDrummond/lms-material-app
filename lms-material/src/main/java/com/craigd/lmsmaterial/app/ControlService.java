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
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.Bundle;
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
    private static final String QUIT_APP = ControlService.class.getCanonicalName() + ".QUIT";
    public static final int ACTIVE_PLAYER = 1;
    public static final int PLAYER_REFRESH = 2;
    public static final int CHECK_COMET_CONNECTION = 3;
    private static final String ACTION_POWER = "power";
    private static final String ACTION_QUIT = "quit";
    private static final int MSG_ID = 1;
    private static final String[] PREV_COMMAND = {"button", "jump_rew"};
    private static final String[] PLAY_COMMAND = {"play"};
    private static final String[] PAUSE_COMMAND = {"pause", "1"};
    private static final String[] NEXT_COMMAND = {"playlist", "index", "+1"};
    private static final String[] TOGGLE_PLAY_PAUSE_COMMAND = {"pause"};
    private static final String[] DEC_VOLUME_COMMAND = {"mixer", "volume", "-5"};
    private static final String[] INC_VOLUME_COMMAND = {"mixer", "volume", "+5"};
    private static final String[] POWER_COMMAND = {"power"};
    public static final String NOTIFICATION_CHANNEL_ID = "lms_control_service";

    private static boolean isRunning = false;

    public static boolean isActive() {
        return isRunning;
    }

    private JsonRpc rpc;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManagerCompat notificationManager;
    private MediaSessionCompat mediaSession;
    MediaSessionCompat.Callback mediaSessionCallback;
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
                if (FULL_NOTIFICATION.equals(srv.notificationType) && null!=srv.cometClient) {
                    srv.cometClient.setPlayer(vals[0]);
                    if (!srv.cometClient.isConnected()) {
                        srv.cometClient.connect();
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ActivityCompat.checkSelfPermission(srv.getApplicationContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                srv.updateNotification();
            } else if (msg.what == PLAYER_REFRESH && null!=srv.notificationBuilder && null!=srv.notificationManager) {
                if (FULL_NOTIFICATION.equals(srv.notificationType) && null!=srv.cometClient && !srv.cometClient.isConnected()) {
                    Utils.debug("Connect comet client");
                    srv.cometClient.connect();
                }
                srv.createNotification();
            } else if (msg.what == CHECK_COMET_CONNECTION && null!=srv.cometClient && FULL_NOTIFICATION.equals(srv.notificationType)) {
                srv.cometClient.reconnectIfChanged();
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
            if (null==service) {
                return;
            }
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
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
        // Sometimes position on first song can be wrong?
        if (null!=cometClient && cometClient.isConnected() && null!=status && (null==lastStatus || (status.id.equals(lastStatus.id) && !lastStatus.isPlaying && status.isPlaying))) {
            cometClient.getPlayerStatus(status.id);
        }
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
        if (Utils.isEmpty(MainActivity.activePlayer)) {
            return;
        }
        if (null == rpc) {
            rpc = new JsonRpc(getApplicationContext());
        }
        rpc.sendMessage(MainActivity.activePlayer, command);
        if (FULL_NOTIFICATION.equals(notificationType) && null!=cometClient && !cometClient.isConnected() && Utils.isNetworkConnected(this)) {
            cometClient.connect();
        }
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
            } else if (QUIT_APP.equals(action)) {
                quit();
            }
        }
        super.onStartCommand(intent, flags, startId);
        return START_STICKY;
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
                IntentFilter filter =  new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
                registerReceiver(connectionChangeListener, filter);
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
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }

    private MediaStyle getMediaStyle() {
        MediaStyle mediaStyle = new MediaStyle();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (FULL_NOTIFICATION.equals(notificationType)) {
                mediaStyle.setShowActionsInCompactView(0, 1, 2);
            } else {
                mediaStyle.setShowActionsInCompactView(1, 2, 3);
            }
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
            boolean isFull = FULL_NOTIFICATION.equals(notificationType);
            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
            boolean statusValid = false;
            if (null!=lastStatus && lastStatus.id.equals(MainActivity.activePlayer) && isFull) {
                statusValid = true;
            } else {
                lastStatus = null;
            }
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

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                notificationBuilder.clearActions();
                notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_prev, "Previous", getPendingIntent(PREV_TRACK)));
                if (!statusValid) {
                    notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_play, "Play", getPendingIntent(PLAY_TRACK)));
                    if (!isFull) {
                        notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_pause, "Pause", getPendingIntent(PAUSE_TRACK)));
                    }
                } else if (lastStatus.isPlaying) {
                    notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_pause, "Pause", getPendingIntent(PAUSE_TRACK)));
                } else {
                    notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_play, "Play", getPendingIntent(PLAY_TRACK)));
                }
                notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_next, "Next", getPendingIntent(NEXT_TRACK)));
                notificationBuilder.addAction(new NotificationCompat.Action(R.drawable.ic_action_quit, getString(R.string.quit), getPendingIntent(QUIT_APP)));
                notificationBuilder.setSubText(statusValid ? lastStatus.display() : getResources().getString(R.string.notification_meta_text));
            } else {
                PlaybackStateCompat.Builder playbackStateBuilder = new PlaybackStateCompat.Builder();
                if (statusValid) {
                    playbackStateBuilder.setState(lastStatus.isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_STOPPED, lastStatus.time, lastStatus.isPlaying ? 1.0f : 0)
                                        .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT | PlaybackStateCompat.ACTION_SEEK_TO);
                } else {
                    playbackStateBuilder.setState(PlaybackStateCompat.STATE_STOPPED, 0, 0)
                                        .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SKIP_TO_NEXT);
                }
                playbackStateBuilder.addCustomAction(ACTION_POWER, getString(R.string.power), android.R.drawable.ic_lock_power_off)
                        .addCustomAction(ACTION_QUIT, getString(R.string.quit), R.drawable.ic_action_quit);
                playbackState = playbackStateBuilder.build();
                mediaSession.setPlaybackState(playbackState);
                if (mediaSessionCallback==null) {
                    mediaSessionCallback=new MediaSessionCompat.Callback() {
                        @Override
                        public void onPlay() {
                            Utils.debug("");
                            if (null!=lastStatus && lastStatus.id.equals(MainActivity.activePlayer) && isFull) {
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

                        @Override
                        public void onCustomAction(String action, Bundle extras) {
                            if (ACTION_QUIT.equals(action)) {
                                quit();
                            } else if (ACTION_POWER.equals(action)) {
                                sendCommand(POWER_COMMAND);
                            }
                        }
                    };
                }
                mediaSession.setCallback(mediaSessionCallback);

                if (prefs.getBoolean(SettingsActivity.HARDWARE_VOLUME_PREF_KEY, true)) {
                    mediaSession.setPlaybackToLocal(AudioManager.STREAM_MUSIC);
                } else {
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
                }

                String title = MainActivity.activePlayerName == null || MainActivity.activePlayerName.isEmpty() ? getResources().getString(R.string.no_player) : MainActivity.activePlayerName;
                MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder();
                metaBuilder.putString(MediaMetadata.METADATA_KEY_ARTIST, title);

                if (statusValid) {
                    Utils.debug("Full meta data - " + lastStatus.toString());
                    metaBuilder.putString(MediaMetadata.METADATA_KEY_TITLE, lastStatus.display())
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, lastStatus.duration);
                    if (Utils.isEmpty(lastStatus.cover)) {
                        metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, getFallback());
                    } else {
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

    public void quit() {
        stopForegroundService();
        new LocalPlayer(prefs, this).autoStop();
        System.exit(0);
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
        Utils.debug("callStateListenerRegistered:"+callStateListenerRegistered);
        if (!callStateListenerRegistered) {
            TelephonyManager telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    Utils.debug("Calling registerTelephonyCallback");
                    telephonyManager.registerTelephonyCallback(getMainExecutor(), callStateListener);
                } else {
                    Utils.error("Permission not granted");
                    return;
                }
            } else {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            }
            callStateListenerRegistered = true;
        }
    }

    private void unregisterCallStateListener() {
        Utils.debug("callStateListenerRegistered:"+callStateListenerRegistered);
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
