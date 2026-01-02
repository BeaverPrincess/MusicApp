package com.example.musicapp;

import android.net.Uri;

public class Song {
    public final long id;
    public final String name;
    public final Uri uri;

    public Song(long id, String name, Uri uri) {
        this.id = id;
        this.name = name;
        this.uri = uri;
    }
}
