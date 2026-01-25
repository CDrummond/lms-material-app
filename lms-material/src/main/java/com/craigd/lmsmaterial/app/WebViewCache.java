/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2026 Craig Drummond <craig.p.drummond@gmail.com>
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
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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

        // Restore known versions (if any)
        Map<String,?> keys = prefs.getAll();
        for (Map.Entry<String, ?> entry : keys.entrySet()) {
            if (entry.getKey().startsWith(KEY_PREFIX)) {
                try {
                    versions.put(entry.getKey(), (String) entry.getValue());
                } catch (Exception ignored) {}
            }
        }
    }

    public void clear() {
        Map<String,?> keys = prefs.getAll();
        boolean changed = false;
        SharedPreferences.Editor edit = prefs.edit();

        for (Map.Entry<String, ?> entry : keys.entrySet()) {
            if (entry.getKey().startsWith(KEY_PREFIX)) {
                // Delete both plain and gz versions to be safe
                File file = new File(context.getFilesDir(), entry.getKey());
                File gz = new File(context.getFilesDir(), entry.getKey() + ".gz");
                boolean deletedAny = false;
                if (file.exists() && file.delete()) {
                    deletedAny = true;
                }
                if (gz.exists() && gz.delete()) {
                    deletedAny = true;
                }
                if (deletedAny) {
                    edit.remove(entry.getKey());
                    versions.remove(entry.getKey());
                    changed = true;
                }
            }
        }
        if (changed) {
            edit.apply();
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
        String key = KEY_PREFIX + Objects.requireNonNull(request.getUrl().getPath()).substring(MATERIAL_PREFIX.length()).replaceAll("[./\\-?=&]", "_");
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
                    key += "_"+url.substring(start+1, end).replaceAll("[./\\-?=&]", "_");
                }
            }
        }
        String cacheVer = versions.get(key);
        Utils.debug("url:" + url +", version: " + version + ", key:" + key + " cacheVer:" + (null==cacheVer ? "<null>" : cacheVer));

        // Prefer gzipped cached file (key + ".gz") if version matches
        if (version!=null && version.equals(cacheVer)) {
            File gzFile = new File(context.getFilesDir(), key + ".gz");
            if (gzFile.exists()) {
                try {
                    // Decompress on the app side for maximum compatibility (some WebViews don't honor Content-Encoding)
                    InputStream fis = new FileInputStream(gzFile);
                    InputStream decompressed = new GZIPInputStream(fis);
                    WebResourceResponse resp = new WebResourceResponse(mimetype, "utf-8", decompressed);
                    Utils.debug("...read gz from cache (decompressed by app)");
                    return resp;
                } catch (Exception e) {
                    Utils.error("Failed to read gz " + key, e);
                    // fall through to attempt uncompressed file or download
                }
            }

            // Fallback: maybe an old uncompressed file exists (backwards compat)
            File file = new File(context.getFilesDir(), key);
            if (file.exists()) {
                try {
                    WebResourceResponse resp = new WebResourceResponse(mimetype, "utf-8",  new FileInputStream(file));
                    Utils.debug("...read plain from cache");
                    return resp;
                } catch (Exception e) {
                    Utils.error("Failed to read " + key, e);
                }
            }
        }

        // Not present or version mismatched -> schedule download and return null so normal network fetch proceeds
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
                            // Use HttpURLConnection so we can inspect headers (e.g. Content-Encoding)
                            HttpURLConnection conn = null;
                            InputStream is = null;
                            File tmpFile = null;
                            try {
                                Utils.debug("Download " + item.key + " from " + item.url);
                                URL u = new URL(item.url);
                                conn = (HttpURLConnection) u.openConnection();
                                conn.setInstanceFollowRedirects(true);
                                conn.setConnectTimeout(15000);
                                conn.setReadTimeout(15000);
                                // Do not add Accept-Encoding header — let the server decide. We'll check Content-Encoding.
                                conn.connect();

                                String contentEncoding = conn.getHeaderField("Content-Encoding"); // may be "gzip" or null
                                is = conn.getInputStream();

                                tmpFile = new File(context.getFilesDir(), item.key + ".tmp");
                                if (tmpFile.exists()) {
                                    tmpFile.delete();
                                }

                                FileOutputStream fos = new FileOutputStream(tmpFile);
                                File destGz = new File(context.getFilesDir(), item.key + ".gz");

                                if (contentEncoding != null && contentEncoding.equalsIgnoreCase("gzip")) {
                                    // Server already sent gzip; save bytes as-is to tmp then rename to .gz
                                    copyStream(is, fos);
                                    fos.close();
                                    // atomic rename
                                    if (destGz.exists()) {
                                        destGz.delete();
                                    }
                                    if (!tmpFile.renameTo(destGz)) {
                                        Utils.error("Failed to rename tmp to dest gz for " + item.key);
                                        tmpFile.delete();
                                        continue;
                                    }
                                } else {
                                    // Server sent uncompressed; compress on the fly into destGz
                                    GZIPOutputStream gos = new GZIPOutputStream(fos);
                                    copyStream(is, gos);
                                    gos.finish();
                                    gos.close();
                                    // atomic rename from tmp -> destGz
                                    if (destGz.exists()) {
                                        destGz.delete();
                                    }
                                    if (!tmpFile.renameTo(destGz)) {
                                        Utils.error("Failed to rename tmp to dest gz for " + item.key);
                                        tmpFile.delete();
                                        continue;
                                    }
                                }

                                // success: store version metadata
                                Utils.debug("store gz version:" + item.version);
                                versions.put(item.key, item.version);
                                SharedPreferences.Editor editor = prefs.edit();
                                editor.putString(item.key, item.version);
                                editor.apply();
                            } catch (Exception e) {
                                Utils.error("Failed to download " + item.key, e);
                                if (tmpFile != null && tmpFile.exists()) {
                                    tmpFile.delete();
                                }
                            } finally {
                                try {
                                    if (is != null) {
                                        is.close();
                                    }
                                } catch (Exception ignored) { }
                                if (conn != null) {
                                    conn.disconnect();
                                }
                            }
                        }
                    } catch (InterruptedException ignored) { }
                }
            };
            downloadThread.start();
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
    }
}