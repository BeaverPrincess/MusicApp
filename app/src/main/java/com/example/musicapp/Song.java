package com.example.musicapp;

import android.net.Uri;

public class Song {
    public final Uri uri;
    public final String name;
    public final long dateAdded;

    public Song(Uri uri, String name, long dateAdded) {
        this.uri = uri;
        this.name = name;
        this.dateAdded = dateAdded;
    }
}
