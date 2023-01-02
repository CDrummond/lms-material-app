/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2023 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.app.IntentService;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.preference.PreferenceManager;

public class TermuxResultsService extends IntentService {
    public static final String EXTRA_EXECUTION_ID = "execution_id";
    private static int EXECUTION_ID = 1000;

    public static synchronized int getNextExecutionId() {
        EXECUTION_ID++;
        return EXECUTION_ID;
    }

    public TermuxResultsService() {
        super("TermuxResultsService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        Bundle resultBundle = intent.getBundleExtra("result");
        if (resultBundle == null) {
            return;
        }

        if (intent.getIntExtra(EXTRA_EXECUTION_ID, 0)!=EXECUTION_ID) {
            return;
        }

        String stdout = resultBundle.getString("stdout", "");
        if (stdout.contains("/data/data/com.termux/files/usr/bin/squeezelite")) {
            Log.d(MainActivity.TAG, "Squeezelite is already running");
        } else {
            LocalPlayer localPlayer = new LocalPlayer(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()), getApplicationContext());
            localPlayer.startTermuxSqueezeLite();
        }
    }
}
