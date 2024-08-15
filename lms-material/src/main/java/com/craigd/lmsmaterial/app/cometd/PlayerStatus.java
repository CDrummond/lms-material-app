/**
 * LMS-Material-App
 *
 * Copyright (c) 2020-2024 Craig Drummond <craig.p.drummond@gmail.com>
 * MIT license.
 */

package com.craigd.lmsmaterial.app.cometd;

import androidx.annotation.NonNull;

public class PlayerStatus {
    public long timestamp;
    public String id;
    public String title;
    public String artist;
    public String album;
    public String cover;
    public long duration = 0;
    public long time = 0;
    public boolean isPlaying = false;

    @NonNull
    @Override
    public String toString() {
        return "id:"+id+", title:"+title+", artist:"+artist+", album:"+album+", cover:"+cover+", duration:"+duration+", time:"+time+", isPlaying:"+isPlaying;
    }
}