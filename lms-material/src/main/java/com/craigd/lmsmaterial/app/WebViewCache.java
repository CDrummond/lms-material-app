/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2025 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class WebViewCache {
    private static final String KEY_PREFIX = "cache_";
    private static final String MATERIAL_PREFIX = "/material/";
    private static final String SVG_PREFIX = "/material/svg/";
    private static final String JS_FILE = ".js?r=";
    private static final String CSS_FILE = ".css?r=";
    private static final String FONT_FILE = ".ttf?r=";
    private static final String SVG_REVISION_KEY = "&r=";

    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, String> versions = new HashMap<>();
    private BlockingQueue<Item> downloadQueue;
    private Thread downloadThread;
    private boolean continueDownloadThread;

    private static class Item {
        public Item(String url, String key, String version) {
            this.url = url;
            this.key = key;
            this.version = version;
        }
        public String url;
        public String key;
        public String version;
    }
    public WebViewCache(Context context) {
        this.context = context;
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Map<String,?> keys = prefs.getAll();

        for (Map.Entry<String, ?> entry : keys.entrySet()) {
            if (entry.getKey().startsWith(KEY_PREFIX)) {
                Utils.debug(entry.getKey() + " -> " + entry.getValue());
                versions.put(entry.getKey(), (String) entry.getValue());
            }
        }
    }

    private static String getVersion(String url) {
        if (url.contains(MATERIAL_PREFIX)) {
            int idx = url.indexOf(JS_FILE);
            if (idx>0) {
                return url.substring(idx+JS_FILE.length());
            }
            idx = url.indexOf(CSS_FILE);
            if (idx>0) {
                return url.substring(idx+CSS_FILE.length());
            }
            idx = url.indexOf(FONT_FILE);
            if (idx>0) {
                return url.substring(idx+FONT_FILE.length());
            }
            if (url.contains(SVG_PREFIX)) {
                idx = url.indexOf(SVG_REVISION_KEY);
                if (idx>0) {
                    return url.substring(idx+SVG_REVISION_KEY.length());
                }
            }
        }
        return null;
    }

    public boolean shouldIntercept(WebResourceRequest request) {
        if (null==request) {
            return false;
        }
        if (!"GET".equals(request.getMethod())) {
            return false;
        }
        String url = request.getUrl().toString();
        String version = getVersion(url);
        Utils.debug(url+", version:" + (null==version ? "<null>" : version));
        return null!=version;
    }

    public synchronized WebResourceResponse get(WebResourceRequest request) {
        String url = request.getUrl().toString();
        String version = getVersion(url);
        String key = KEY_PREFIX + Objects.requireNonNull(request.getUrl().getPath()).substring(MATERIAL_PREFIX.length()).replaceAll("[\\.\\/\\-?=&]", "_");
        String mimetype = key.endsWith("css")
                ? "text/css"
                : key.endsWith("js")
                ? "application/javascript"
                : key.endsWith("ttf")
                ? "font/ttf"
                : "image/svg+xml";

        if (mimetype.equals("image/svg+xml")) {
            int start = url.indexOf("?");
            if (start>0) {
                int end = url.indexOf("&", start);
                if (end>start) {
                    key += "_"+url.substring(start+1, end).replaceAll("[\\.\\/\\-?=&]", "_");
                }
            }
        }
        String cacheVer = versions.get(key);
        Utils.debug("url:" + url +", version: " + version + ", key:" + key + " cacheVer:" + (null==cacheVer ? "<null>" : cacheVer));
        if (version!=null && version.equals(cacheVer)) {
            try {
                File file = new File(context.getFilesDir(), key);
                WebResourceResponse resp = new WebResourceResponse(mimetype, "utf-8", 200, "OK", null, new FileInputStream(file));
                Utils.debug("...read from cache");
                return resp;
            } catch (Exception e) {
                Utils.error("Failed to read " + key, e);
            }
        }

        download(url, key, version);
        return null;
    }

    public void stop() {
        continueDownloadThread = false;
        if (null!=downloadThread) {
            downloadThread.interrupt();
        }
    }

    private synchronized void download(String url, String key, String version) {
        if (null==downloadQueue) {
            downloadQueue = new ArrayBlockingQueue<>(500);
        }
        downloadQueue.add(new Item(url, key, version));
        if (null==downloadThread) {
            Utils.debug("Create thread");
            continueDownloadThread = true;
            downloadThread = new Thread() {
                @Override
                public void run() {
                    Utils.debug("Start thread");
                    try {
                        while (continueDownloadThread) {
                            Item item = downloadQueue.take();
                            FileOutputStream fos = null;
                            try {
                                Utils.debug("Download " + item.key);
                                ReadableByteChannel rbc = Channels.newChannel(new URL(item.url).openStream());
                                File tmpFile = new File(context.getFilesDir(), item.key + ".tmp");
                                if (tmpFile.exists()) {
                                    tmpFile.delete();
                                }
                                fos = new FileOutputStream(tmpFile);
                                fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
                                fos.close();
                                fos = null;
                                downloaded(item, tmpFile);
                            } catch (Exception e) {
                                Utils.error("Failed to download " + key, e);
                            } finally {
                                if (null!=fos) {
                                    try {
                                        fos.close();
                                    } catch (Exception ignored) {
                                    }
                                }
                            }
                        }
                    } catch (InterruptedException ignored) {
                    }
                }
            };
            downloadThread.start();
        }
    }

    private synchronized void downloaded(Item item, File tmpFile) {
        if (!continueDownloadThread) {
            tmpFile.delete();
            return;
        }
        Utils.debug(item.key);
        File dest = new File(context.getFilesDir(), item.key);
        if ((!dest.exists() || dest.delete()) && tmpFile.renameTo(dest)) {
            Utils.debug("store version:" + item.version);
            versions.put(item.key, item.version);
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(item.key, item.version);
            editor.apply();
        }
    }
}
