/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Rect;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.WindowInsets;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.muddz.styleabletoast.StyleableToast;

public class Utils {
    public static final String LOG_TAG = "LMS";

    public static boolean isNetworkConnected(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = connectivityManager.getActiveNetworkInfo();
        return null!=info && info.isConnected();
    }

    public static float convertPixelsToDp(float px, Context context){
        return px / ((float) context.getResources().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
    }

    public static boolean cutoutTopLeft(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            DisplayCutout displayCutout = activity.getWindowManager().getDefaultDisplay().getCutout();
            if (null != displayCutout) {
                List<Rect> rects = displayCutout.getBoundingRects();
                for (Rect rect : rects) {
                    if (rect.left >= 0 && rect.left <= 10 && rect.width() > 100 && rect.width()<300) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean usingGestureNavigation(Activity activity) {
        Resources resources = activity.getResources();
        @SuppressLint("DiscouragedApi") int resourceId = resources.getIdentifier("config_navBarInteractionMode", "integer", "android");
        if (resourceId > 0) {
            return 2==resources.getInteger(resourceId);
        }
        return false;
    }

    static int getTopPadding(Activity activity) {
        int def = 26;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets wi = activity.getWindowManager().getCurrentWindowMetrics().getWindowInsets();
            Insets i = wi.getInsets(WindowInsets.Type.systemBars());
            return Math.max((int)Math.ceil(convertPixelsToDp(i.top, activity)), def);
        }
        return def;
    }

    static int getBottomPadding(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets wi = activity.getWindowManager().getCurrentWindowMetrics().getWindowInsets();
            Insets i = wi.getInsets(WindowInsets.Type.navigationBars());
            int val = (int)Math.ceil(convertPixelsToDp(i.bottom, activity));
            Utils.debug("inset:" + val);
            return val>8 ? Math.max(val, 14) : val;
        }
        return usingGestureNavigation(activity) ? 14 : 40;
    }

    static public boolean notificationAllowed(Context context, String channelId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            debug("Check if notif permission granted");
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                debug("No notif permission");
                return false;
            }
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            debug("Notifs are disabled");
            return false;
        }
        if (channelId!=null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationManager mgr = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = mgr.getNotificationChannel(channelId);
            if (null!=channel) {
                debug("Channel " + channelId + " importance " + channel.getImportance());
                return channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
            }
        }
        debug("Notifs are allowed");
        return true;
    }

    public static boolean isEmpty(String str) {
        return null==str || str.isEmpty();
    }

    public static String encodeURIComponent(String str) {
        try {
            return URLEncoder.encode(str, "UTF-8")
                    .replaceAll("\\+", "%20")
                    .replaceAll("\\%21", "!")
                    .replaceAll("\\%27", "'")
                    .replaceAll("\\%28", "(")
                    .replaceAll("\\%29", ")")
                    .replaceAll("\\%7E", "~");
        } catch (UnsupportedEncodingException ignored)  {
        }
        return str;
    }

    public static String timeStr(long ms) {
        return String.format("%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(ms) -
                        TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(ms)),
                TimeUnit.MILLISECONDS.toSeconds(ms) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(ms)));
    }

    private static String logPrefix() {
        StackTraceElement st[] = Thread.currentThread().getStackTrace();
        if (null!=st && st.length>4) {
            // Remove com.craigd.lmsmaterial.app.
            return "["+st[4].getClassName().substring(27)+"."+st[4].getMethodName()+"] ";
        }
        return "";
    }

    public static void verbose(String message) {
        if (BuildConfig.DEBUG) {
            Log.v(LOG_TAG, logPrefix() + message);
        }
    }

    public static void debug(String message) {
        if (BuildConfig.DEBUG) {
            Log.d(LOG_TAG, logPrefix() + message);
        }
    }

    public static void info(String message) {
        if (BuildConfig.DEBUG) {
            Log.i(LOG_TAG, logPrefix() + message);
        }
    }

    public static void warn(String message) {
        if (BuildConfig.DEBUG) {
            Log.w(LOG_TAG, logPrefix() + message);
        }
    }

    public static void warn(String message, Throwable t) {
        if (BuildConfig.DEBUG) {
            Log.w(LOG_TAG, logPrefix() + message, t);
        }
    }

    public static void error(String message) {
        if (BuildConfig.DEBUG) {
            Log.e(LOG_TAG, logPrefix() + message);
        }
    }

    public static void error(String message, Throwable t) {
        if (BuildConfig.DEBUG) {
            Log.e(LOG_TAG, logPrefix() + message, t);
        }
    }

    public static boolean isInstalled(Context context, String pkg, String name) {
        final PackageManager packageManager = context.getPackageManager();
        Intent intent = packageManager.getLaunchIntentForPackage(pkg);
        if (intent != null) {
            return true;
        } else {
            String text = context.getApplicationContext().getResources().getString(R.string.player_control_failed).replace("%1", name);
            StyleableToast.makeText(context.getApplicationContext(), text, Toast.LENGTH_SHORT, R.style.toast).show();
            return false;
        }
    }

    public static boolean existsInDownloads(Context context, String fileName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File file = new File(downloadsDir, fileName);
            return file.exists();
        } else {
            ContentResolver resolver = context.getContentResolver();
            String[] projection = { MediaStore.Downloads._ID };
            String selection = MediaStore.Downloads.DISPLAY_NAME + " = ?";
            String[] selectionArgs = new String[] { fileName };

            try (Cursor cursor = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null)) {
                // If the cursor has rows, the file exists in the MediaStore
                return cursor != null && cursor.getCount() > 0;
            } catch (Exception e) {
                return false;
            }
        }
    }

    public static void saveToDownloads(Context context, String fileName, byte[] data) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file = new File(downloadDir, fileName);

                // Write bytes to file
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                    fos.flush();
                }
            } catch (IOException e) {
                Utils.error("Failed to save file", e);
            }
        } else {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "image/png");
            values.put(MediaStore.Downloads.IS_PENDING, true);

            Uri uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri itemUri = context.getContentResolver().insert(uri, values);

            if (itemUri != null) {
                try (OutputStream os = context.getContentResolver().openOutputStream(itemUri)) {
                    if (null==os) {
                        Utils.error("Failed to save file - no stream?");
                        return;
                    }
                    os.write(data);
                    os.flush();
                    values.clear();
                    values.put(MediaStore.Downloads.IS_PENDING, false);
                    context.getContentResolver().update(itemUri, values, null, null);
                } catch (Exception e) {
                    Utils.error("Failed to save file", e);
                }
            }
        }
    }

    public static File saveToCache(Context context, String dirname, String name, byte[] data) {
        Utils.debug("dir:"+dirname+" name:"+name);
        try {
            File cacheDir = context.getExternalCacheDir();
            if (cacheDir == null) {
                cacheDir = context.getCacheDir();
            }
            File dir = new File(cacheDir, dirname);
            Utils.debug("dir path:" + dir.getAbsolutePath() + " exists:" + dir.exists());
            if (!dir.exists() && !dir.mkdirs()) {
                Utils.error("Failed to create: " + dir.getAbsolutePath());
                return null;
            }
            File file = new File(dir, name);
            if (file.exists()) {
                return file;
            }
            Utils.debug("Save to:" + file.getAbsolutePath());
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(data);
                fos.flush();
            }
            return file;
        } catch (IOException e) {
            Utils.error("Failed to save temp file", e);
        }
        return null;
    }

    public static void trimCache(Context context, String sub) {
        try {
            File dir = new File(context.getCacheDir(), sub);
            if (dir.exists() && dir.isDirectory()) {
                deleteDir(dir);
            }

            dir = new File(context.getExternalCacheDir(), sub);
            if (dir.exists() && dir.isDirectory()) {
                deleteDir(dir);
            }
        } catch (Exception e) {
            error("Failed to clear cache dir:" + sub, e);
        }
    }

    public static void deleteDir(File path) {
        if (path != null) {
            if (path.isDirectory()) {
                String[] children = path.list();
                if (children != null) {
                    for (String child : children) {
                        deleteDir(new File(path, child));
                    }
                }
            }
            path.delete();
        }
    }
}
