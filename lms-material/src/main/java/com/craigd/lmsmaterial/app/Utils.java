/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2024 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.WindowInsets;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

public class Utils {
    public static final String LOG_TAG = "LMS";

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
        int def = usingGestureNavigation(activity) ? 14 : 40;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets wi = activity.getWindowManager().getCurrentWindowMetrics().getWindowInsets();
            Insets i = wi.getInsets(WindowInsets.Type.navigationBars());
            return Math.max((int)Math.ceil(convertPixelsToDp(i.bottom, activity)), def);
        }
        return def;
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
}
