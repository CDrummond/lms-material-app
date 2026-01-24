/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.app.Application;
import android.webkit.WebView;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Utils.debug("");
        // Warm-up WebView off the main critical path:
        new Thread(() -> {
            try {
                Utils.debug("Warm up WebView");
                WebView wv = new WebView(getApplicationContext());
                // Optionally load about:blank and then destroy
                wv.loadUrl("about:blank");
                // Small delay to give engine time to start, then destroy:
                Thread.sleep(100);
                wv.destroy();
            } catch (Throwable ignored) {}
        }).start();
    }
}
