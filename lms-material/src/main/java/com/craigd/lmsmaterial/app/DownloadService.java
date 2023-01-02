/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2023 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class DownloadService extends Service {
    public static final String STATUS = DownloadService.class.getCanonicalName()+".STATUS";
    public static final String STATUS_BODY = "body";
    public static final String STATUS_LEN = "len";
    public static final int DOWNLOAD_LIST = 1;
    public static final int CANCEL_LIST = 2;
    public static final int STATUS_REQ = 3;
    private static final int MSG_ID = 2;
    private static final int MAX_QUEUED_ITEMS = 4;

    private DownloadManager downloadManager;
    private SharedPreferences sharedPreferences;
    private NotificationCompat.Builder notificationBuilder;
    private NotificationManagerCompat notificationManager;
    private final Messenger messenger = new Messenger(
            new IncomingHandler()
    );

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
        return str.replaceAll("[?<>\\\\:*|\"]", "_");
    }

    static String fixEmpty(String str) {
        return 0==str.length() ? "Unknown" : str;
    }

    class DownloadItem {
        public DownloadItem(JSONObject obj, boolean transcode) {
            id=getInt(obj, "id");
            filename=getString(obj, "filename");
            title=getString(obj, "title");
            ext=getString(obj, "ext");
            artist=getString(obj, "artist");
            album=getString(obj, "album");
            tracknum=getInt(obj, "tracknum");
            disc=getInt(obj, "disc");
            albumId=getInt(obj, "album_id");
            isTrack=true;

            if (filename.length()<1) {
                if (disc>0) {
                    filename+=disc;
                }
                if (tracknum>0) {
                    filename+=(filename.length()>0 ? "." : "") + (tracknum<10 ? "0" : "") + tracknum + " ";
                } else if (filename.length()>0) {
                    filename+=" ";
                }
                filename+=title+"."+(transcode ? "mp3" : ext);
            } else if (transcode) {
                int pos = filename.lastIndexOf('.');
                if (pos>0) {
                    filename=filename.substring(0, pos)+".mp3";
                }
            }
        }

        public DownloadItem(int id, int albumId, String artist, String album) {
            isTrack=false;
            this.id=id*-1; // Ensure we do not overlap with track id
            this.albumId=albumId;
            this.artist=artist;
            this.album=album;
            this.filename="albumart.jpg";
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
            return fatSafe(artist.length()>0 && album.length()>0
                    ? artist + " - " + album
                    : artist.length()>0
                        ? artist
                        : album.length()>0
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

    List<DownloadItem> items = new LinkedList<DownloadItem>();
    List<DownloadItem> queuedItems = new LinkedList<DownloadItem>();
    Set<Integer> trackIds = new HashSet<Integer>();
    Set<Integer> albumIds = new HashSet<Integer>();

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DOWNLOAD_LIST:
                    addTracks((JSONArray)msg.obj);
                    break;
                case CANCEL_LIST:
                    cancel((JSONArray)msg.obj);
                    break;
                case STATUS_REQ:
                    sendStatusUpdate();
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
        Log.d("LMS", "DownloadService.onCreate()");
        DownloadStatusReceiver.init(this);
        startForegroundService();
        downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
    }

    private void startForegroundService() {
        Log.d(MainActivity.TAG, "Start download service.");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel("lms_download_service", "LMS Download Service");
        } else {
            notificationBuilder = new NotificationCompat.Builder(this);
        }
        createNotification();
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void createNotificationChannel(String channelId, String channelName) {
        NotificationChannel chan = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT);
        chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        chan.setShowBadge(false);
        chan.enableLights(false);
        chan.enableVibration(false);
        chan.setSound(null, null);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        assert manager != null;
        manager.createNotificationChannel(chan);
        notificationBuilder = new NotificationCompat.Builder(this, channelId);
    }

    @NonNull
    private PendingIntent getPendingIntent(@NonNull String action){
        Intent intent = new Intent(this, DownloadService.class);
        intent.setAction(action);
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = notificationBuilder.setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(getResources().getString(R.string.downloading))
                .setPriority(NotificationManager.IMPORTANCE_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(null)
                .setSound(null)
                .setShowWhen(false)
                .build();
        notificationManager = NotificationManagerCompat.from(this);
        notificationManager.notify(MSG_ID, notificationBuilder.build());
        startForeground(MSG_ID, notification);
    }

    void addTracks(JSONArray tracks) {
        Log.d(MainActivity.TAG, "addTracks");
        boolean transcode = sharedPreferences.getBoolean("transcode", false);

        synchronized (items) {
            try {
                int before = items.size();
                List<DownloadItem> newAlbumCovers = new LinkedList<DownloadItem>();
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
                for (DownloadItem albumCover : newAlbumCovers) {
                    items.add(albumCover);
                }
                Log.d(MainActivity.TAG, "Before: " + before + " now:"+ items.size());
                if (before!=items.size()) {
                    if (0==before) {
                        downloadItems();
                    } else {
                        sendStatusUpdate();
                    }
                }
            } catch (JSONException e) {
                Log.e(MainActivity.TAG, "Failed to add tracks", e);
            }
        }
    }

    void cancel(JSONArray ids) {
        Log.d(MainActivity.TAG, "Cancel downloads");
        List<DownloadItem> toRemove = new LinkedList<DownloadItem>();
        List<DownloadItem> toRemoveQueued = new LinkedList<DownloadItem>();
        Set<Integer> idSet = new HashSet<Integer>();
        for (int i = 0; i < ids.length(); ++i) {
            try {
                idSet.add(ids.getInt(i));
            } catch (JSONException e) {
                Log.e(MainActivity.TAG, "Failed to decode cancel array", e);
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
                Log.d(MainActivity.TAG, "Remove " + toRemove.size() + " item(s)");
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
                        Log.d(MainActivity.TAG, "Empty, so stop");
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
        Log.d(MainActivity.TAG, "Items:" + items.size()+" Queued:"+queuedItems.size());
        synchronized (items) {
            for (DownloadItem item: queuedItems) {
                try {
                    update.put(item.toObject(true));
                    count++;
                } catch (JSONException e) {
                    Log.e(MainActivity.TAG, "Failed to create item string", e);
                }
            }
            for (DownloadItem item: items) {
                try {
                    update.put(item.toObject(false));
                    count++;
                } catch (JSONException e) {
                    Log.e(MainActivity.TAG, "Failed to create item string", e);
                }
            }
        }
        try {
            Log.d(MainActivity.TAG, "Send status update to webview, count:" + count);
            Intent intent = new Intent();
            intent.setAction(STATUS);
            intent.putExtra(STATUS_BODY, update.toString(0));
            intent.putExtra(STATUS_LEN, count);
            sendBroadcast(intent);
        } catch (JSONException e) {
            Log.e(MainActivity.TAG, "Failed to create update string", e);
        }
    }

    void downloadItems() {
        Log.d(MainActivity.TAG, "Download some items");
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
                               : Uri.parse("http://" + server.ip + ":" + server.port + "/music/" + (item.id*-1) + "/cover.jpg");
        DownloadManager.Request request = new DownloadManager.Request(url)
                .setTitle(item.title)
                .setVisibleInDownloadsUi(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, item.getDownloadFileName());

        item.downloadId = downloadManager.enqueue(request);
        Log.d(MainActivity.TAG, "Download url: " + url + " id: " + item.downloadId);
        queuedItems.add(item);
    }

    void downloadComplete(long id) {
        Log.d(MainActivity.TAG, "Download completed: "  +id);

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
            Log.e(MainActivity.TAG, "Failed to find queue item id " + id);
            return;
        }

        queuedItems.remove(item);
        addToMediaStorage(item);

        Log.d(MainActivity.TAG, "Num items: " + items.size() + " Queue size:" + queuedItems.size());
        if (items.isEmpty() && queuedItems.isEmpty()) {
            sendStatusUpdate();
            stop();
        } else {
            downloadItems();
        }
    }

    boolean destExists(DownloadItem item) {
        File destFile = new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), item.getFolder()), item.filename);
        Log.d(MainActivity.TAG, "Check dest: " + destFile.getAbsolutePath() + " exists:" + destFile.exists());
        return destFile.exists();
    }

    void addToMediaStorage(DownloadItem item) {
        File destDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), item.getFolder());
        File destFile = new File(destDir, item.filename);
        File sourceFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), item.getDownloadFileName());
        if (!destDir.exists()) {
            destDir.mkdir();
        }

        InputStream inputStream = null;
        OutputStream outputStream = null;
        Log.d(MainActivity.TAG, "Copy from: " + sourceFile.getPath() + " to " + destFile.getAbsolutePath());
        try {
            inputStream = new FileInputStream(sourceFile);
            outputStream = new FileOutputStream(destFile);

            byte[] b = new byte[16*1024];
            int bytesRead;
            while ((bytesRead = inputStream.read(b)) > 0) {
                outputStream.write(b, 0, bytesRead);
            }
        } catch (Exception e) {
        } finally {
            if (null!=inputStream) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                }
            }
            if (null!=outputStream) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                }
            }
        }
        sourceFile.delete();
    }

    void stop() {
        Log.d(MainActivity.TAG, "Stop download service.");
        stopForeground(true);
        stopSelf();
    }
}
