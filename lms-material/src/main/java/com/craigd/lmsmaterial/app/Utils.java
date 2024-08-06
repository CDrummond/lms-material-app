/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2024 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;
import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;

public class Utils {
    public static final String LOG_TAG = "LMS";

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

    private static String logPrefix() {
        StackTraceElement st[] = Thread.currentThread().getStackTrace();
        if (null!=st && st.length>4) {
            // Remove com.craigd.lmsmaterial.app.
            return "["+st[4].getClassName().substring(27)+"."+st[4].getMethodName()+"] ";
        }
        return "";
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
}
