/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2022 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class DownloadStatusReceiver extends BroadcastReceiver {
    public static final String SHOW_DOWNLOADS_ACT = "showdownloads";

    private static DownloadService service = null;

    public static void init(DownloadService srv) {
        service = srv;
    }
    @Override
    public void onReceive(Context context, Intent intent) {
        if (DownloadManager.ACTION_NOTIFICATION_CLICKED.equals(intent.getAction())) {
            Intent startIntent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startIntent.setAction(SHOW_DOWNLOADS_ACT);
            // TODO: MainActivity needs rto look at act and call relevant javascript
            context.startActivity(startIntent);
        } else  if (DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
            service.downloadComplete(intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0));
        }
    }
}
