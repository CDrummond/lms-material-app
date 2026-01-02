/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class DownloadService extends Service {
    private static final String COVER_ART_SRC = "cover.jpg";
    private static final String COVER_ART_DEST = "albumart.jpg";
    public static final String STATUS = DownloadService.class.getCanonicalName() + ".STATUS";
    public static final String STATUS_BODY = "body";
    public static final String STATUS_LEN = "len";
    public static final int DOWNLOAD_LIST = 1;
    public static final int CANCEL_LIST = 2;
    public static final int STATUS_REQ = 3;
    private static final int MSG_ID = 2;
    private static final int MAX_QUEUED_ITEMS = 4;
    public static final String NOTIFICATION_CHANNEL_ID = "lms_download_service";

    private DownloadManager downloadManager;
    private SharedPreferences sharedPreferences;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManagerCompat notificationManager;
    private final Messenger messenger = new Messenger(new IncomingHandler(this));

    static String getString(JSONObject obj, String key) {
        try {
            return obj.getString(key);
        } catch (JSONException e) {
            return null;
        }
    }

    static int getInt(JSONObject obj, String key) {
        try {
            return obj.getInt(key);
        } catch (JSONException e) {
            return 0;
        }
    }

    static String fatSafe(String str) {
        return str.replaceAll("[?<>\\\\:*|\"/]", "_");
    }

    static String fixEmpty(String str) {
        return Utils.isEmpty(str) ? "Unknown" : str;
    }

    static class DownloadItem {
        public DownloadItem(JSONObject obj, boolean transcode) {
            id = getInt(obj, "id");
            filename = getString(obj, "filename");
            title = getString(obj, "title");
            ext = getString(obj, "ext");
            artist = getString(obj, "artist");
            album = getString(obj, "album");
            tracknum = getInt(obj, "tracknum");
            disc = getInt(obj, "disc");
            albumId = getInt(obj, "album_id");
            isTrack = true;

            if (Utils.isEmpty(filename)) {
                filename = "";
                if (disc > 0) {
                    filename += disc;
                }
                if (tracknum > 0) {
                    filename += (!filename.isEmpty() ? "." : "") + (tracknum < 10 ? "0" : "") + tracknum + " ";
                } else if (!filename.isEmpty()) {
                    filename += " ";
                }
                filename += fixEmpty(title) + "." + (transcode ? "mp3" : ext);
            } else if (transcode) {
                int pos = filename.lastIndexOf('.');
                filename = (pos > 0 ? filename.substring(0, pos) : filename) + ".mp3";
            }
        }

        public DownloadItem(int id, int albumId, String artist, String album) {
            isTrack = false;
            this.id = id * -1; // Ensure we do not overlap with track id
            this.albumId = albumId;
            this.artist = artist;
            this.album = album;
            this.filename = COVER_ART_DEST;
        }

        JSONObject toObject(boolean downloading) throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("downloading", downloading);
            obj.put("title", filename);
            obj.put("subtitle", artist + " - " + album);
            return obj;
        }

        public String getFolder() {
            return fatSafe(!artist.isEmpty() && !album.isEmpty()
                    ? artist + " - " + album
                    : !artist.isEmpty()
                    ? artist
                    : !album.isEmpty()
                    ? album
                    : "Unknown");
        }

        public String getDownloadFileName() {
            return fatSafe(fixEmpty(artist) + " - " + fixEmpty(album) + " - " + filename);
        }

        public int id;
        public String filename;
        public String title;
        public String ext;
        public String artist;
        public String album;
        public int tracknum;
        public int disc;
        public int albumId;
        public boolean isTrack;
        public long downloadId = 0;
    }

    final List<DownloadItem> items = new LinkedList<>();
    List<DownloadItem> queuedItems = new LinkedList<>();
    Set<Integer> trackIds = new HashSet<>();
    Set<Integer> albumIds = new HashSet<>();

    private static class IncomingHandler extends Handler {
        private final WeakReference<DownloadService> serviceRef;
        public IncomingHandler(DownloadService service) {
            super(Looper.getMainLooper());
            serviceRef = new WeakReference<>(service);
        }
        @Override
        public void handleMessage(@NonNull Message msg) {
            DownloadService srv = serviceRef.get();
            if (null==srv) {
                super.handleMessage(msg);
                return;
            }
            switch (msg.what) {
                case DOWNLOAD_LIST:
                    srv.addTracks((JSONArray) msg.obj);
                    break;
                case CANCEL_LIST:
                    srv.cancel((JSONArray) msg.obj);
                    break;
                case STATUS_REQ:
                    srv.sendStatusUpdate();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    public DownloadService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return messenger.getBinder();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Utils.debug("");
        DownloadStatusReceiver.init(this);
        startForegroundService();
        downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
    }

    private void startForegroundService() {
        Utils.debug("Start download service.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
        } else {
            notificationBuilder = new NotificationCompat.Builder(this);
        }
        createNotification();
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void createNotificationChannel() {
        notificationManager = NotificationManagerCompat.from(this);
        NotificationChannel chan = new NotificationChannel(NOTIFICATION_CHANNEL_ID, getApplicationContext().getResources().getString(R.string.download_notification), NotificationManager.IMPORTANCE_LOW);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        chan.setShowBadge(false);
        chan.enableLights(false);
        chan.enableVibration(false);
        chan.setSound(null, null);
        notificationManager.createNotificationChannel(chan);
        notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
    }

    @SuppressLint("MissingPermission")
    private void createNotification() {
        if (!Utils.notificationAllowed(this, NOTIFICATION_CHANNEL_ID)) {
            Utils.error("Permission not granted");
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = notificationBuilder.setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(getResources().getString(R.string.downloading))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(null)
                .setSound(null)
                .setShowWhen(false)
                .setChannelId(NOTIFICATION_CHANNEL_ID)
                .build();

        notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(MSG_ID, notificationBuilder.build());
        // startForeground(MSG_ID, notification);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(new Intent(this, DownloadService.class));
        } else {
            startService(new Intent(this, DownloadService.class));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, MSG_ID, notification, Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE : 0);
        }
    }

    void addTracks(JSONArray tracks) {
        Utils.debug("");
        boolean transcode = sharedPreferences.getBoolean("transcode", false);

        synchronized (items) {
            try {
                int before = items.size();
                List<DownloadItem> newAlbumCovers = new LinkedList<>();
                for (int i = 0; i < tracks.length(); ++i) {
                    DownloadItem track = new DownloadItem((JSONObject) tracks.get(i), transcode);
                    if (!trackIds.contains(track.id)) {
                        trackIds.add(track.id);
                        items.add(track);
                        if (!albumIds.contains(track.albumId)) {
                            albumIds.add(track.albumId);
                            newAlbumCovers.add(new DownloadItem(track.id, track.albumId, track.artist, track.album));
                        }
                    }
                }
                items.addAll(newAlbumCovers);
                Utils.debug("Before: " + before + " now:"+ items.size());
                if (before!=items.size()) {
                    if (0==before) {
                        downloadItems();
                    } else {
                        sendStatusUpdate();
                    }
                }
            } catch (JSONException e) {
                Utils.error("Failed to add tracks", e);
            }
        }
    }

    void cancel(JSONArray ids) {
        Utils.debug("");
        List<DownloadItem> toRemove = new LinkedList<>();
        List<DownloadItem> toRemoveQueued = new LinkedList<>();
        Set<Integer> idSet = new HashSet<>();
        for (int i = 0; i < ids.length(); ++i) {
            try {
                idSet.add(ids.getInt(i));
            } catch (JSONException e) {
                Utils.error("Failed to decode cancel array", e);
            }
        }
        if (!idSet.isEmpty()) {
            synchronized (items) {
                for (int i = 0; i < items.size(); ++i) {
                    if (idSet.contains(items.get(i).id)) {
                        toRemove.add(items.get(i));
                    }
                }
                for (int i = 0; i < queuedItems.size(); ++i) {
                    if (idSet.contains(queuedItems.get(i).id)) {
                        toRemoveQueued.add(queuedItems.get(i));
                    }
                }
                Utils.debug("Remove " + toRemove.size() + " item(s)");
                if (!toRemove.isEmpty()) {
                    for (DownloadItem item : toRemove) {
                        items.remove(item);
                    }
                }
                if (!toRemoveQueued.isEmpty()) {
                    for (DownloadItem item : toRemoveQueued) {
                        downloadManager.remove(item.downloadId);
                        queuedItems.remove(item);
                    }
                }

                if (!toRemove.isEmpty() || !toRemoveQueued.isEmpty()) {
                    sendStatusUpdate();
                    if (items.isEmpty()) {
                        Utils.debug("Empty, so stop");
                        stop();
                    } else if (!toRemoveQueued.isEmpty()) {
                        downloadItems();
                    }
                }
            }
        }
    }

    void sendStatusUpdate() {
        JSONArray update = new JSONArray();
        int count = 0;
        Utils.debug("Items:" + items.size()+" Queued:"+queuedItems.size());
        synchronized (items) {
            for (DownloadItem item: queuedItems) {
                try {
                    update.put(item.toObject(true));
                    count++;
                } catch (JSONException e) {
                    Utils.error("Failed to create item string", e);
                }
            }
            for (DownloadItem item: items) {
                try {
                    update.put(item.toObject(false));
                    count++;
                } catch (JSONException e) {
                    Utils.error("Failed to create item string", e);
                }
            }
        }
        try {
            Utils.debug("Send status update to webview, count:" + count);
            Intent intent = new Intent();
            intent.setAction(STATUS);
            intent.putExtra(STATUS_BODY, update.toString(0));
            intent.putExtra(STATUS_LEN, count);
            sendBroadcast(intent);
        } catch (JSONException e) {
            Utils.error("Failed to create update string", e);
        }
    }

    void downloadItems() {
        Utils.debug("");
        synchronized (items) {
            while(!items.isEmpty() && queuedItems.size()<MAX_QUEUED_ITEMS) {
                DownloadItem item = items.remove(0);
                if (!destExists(item)) {
                    enqueueDownload(item);
                }
            }
        }
        sendStatusUpdate();
    }

    void enqueueDownload(DownloadItem item) {
        ServerDiscovery.Server server = new ServerDiscovery.Server(sharedPreferences.getString(SettingsActivity.SERVER_PREF_KEY,null));
        boolean transcode = sharedPreferences.getBoolean("transcode", false);
        Uri url = item.isTrack ? Uri.parse("http://" + server.ip + ":" + server.port + "/music/" + item.id + "/download" + (transcode ? ".mp3" : ""))
                               : Uri.parse("http://" + server.ip + ":" + server.port + "/music/" + (item.id*-1) + "/" + COVER_ART_SRC);
        DownloadManager.Request request = new DownloadManager.Request(url)
                .setTitle(item.title)
                .setVisibleInDownloadsUi(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, item.getDownloadFileName());

        item.downloadId = downloadManager.enqueue(request);
        Utils.debug("Download url: " + url + " id: " + item.downloadId + " filename: " + item.getDownloadFileName());
        queuedItems.add(item);
    }

    void downloadComplete(long id) {
        Utils.debug(""+id);

        DownloadItem item = null;
        synchronized (items) {
            for (DownloadItem itm : queuedItems) {
                if (itm.downloadId == id) {
                    item = itm;
                    break;
                }
            }
        }

        if (item==null) {
            Utils.error("Failed to find queue item id " + id);
            return;
        }

        queuedItems.remove(item);
        addToMediaStorage(item);

        Utils.debug("Num items: " + items.size() + " Queue size:" + queuedItems.size());
        if (items.isEmpty() && queuedItems.isEmpty()) {
            sendStatusUpdate();
            stop();
        } else {
            downloadItems();
        }
    }

    boolean destExists(DownloadItem item) {
        File destFile = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), item.getFolder()), item.filename);
        Utils.debug("Check dest: " + destFile.getAbsolutePath() + " exists:" + destFile.exists());
        return destFile.exists();
    }

    void addToMediaStorage(DownloadItem item) {
        File destDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), item.getFolder());
        File destFile = new File(destDir, fatSafe(item.filename));
        File sourceFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), item.getDownloadFileName());
        try {
            if (!destDir.exists() && !destDir.mkdir()) {
                Utils.error("Failed to create " + destDir.getAbsolutePath());
                return;
            }
        } catch (Exception e) {
            Utils.error("Failed to create " + destDir.getAbsolutePath(), e);
        }

        Utils.debug("Copy from: " + sourceFile.getPath() + " to " + destFile.getAbsolutePath());
        try (InputStream inputStream = new FileInputStream(sourceFile); OutputStream outputStream = new FileOutputStream(destFile)) {

            byte[] b = new byte[16 * 1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(b)) > 0) {
                outputStream.write(b, 0, bytesRead);
            }
        } catch (Exception e) {
            Utils.error("Failed to copy " + sourceFile.getAbsolutePath() + " to " + destFile.getAbsolutePath(), e);
        }
        try {
            if (!sourceFile.delete()) {
                Utils.error("Failed to delete " + sourceFile.getAbsolutePath());
            }
        } catch (Exception e) {
            Utils.error("Failed to delete " + sourceFile.getAbsolutePath(), e);
        }
    }

    void stop() {
        Utils.debug("");
        stopForeground(true);
        stopSelf();
    }
}
